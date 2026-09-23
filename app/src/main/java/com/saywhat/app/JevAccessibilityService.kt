package com.saywhat.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.saywhat.app.data.Settings
import com.saywhat.app.read.ChatBus
import com.saywhat.app.read.ChatReader
import com.saywhat.app.util.Logbook

/**
 * 读屏服务：只在聊天界面里读「对方发来的最新一条」，别的什么都不碰。
 *
 * 性能考虑：微信的内容变化事件非常密集（打字、动画、表情都会触发），
 * 每次事件都遍历一遍节点树会明显发热。这里用 250ms 节流，
 * 再把去重交给 [ChatBus] —— 同一句话不会被反复送去烧 token。
 *
 * ═══ ⚠️ 为什么这个类名叫 Jev 却刻意不改（2026-09-23 决定）═══
 * 工程里其它 Jev* 命名已清理（`JevBus` → [ChatBus]），
 * 但**这个类名必须留着**，因为它是**系统级标识**，同时被三处引用：
 *
 *   1. `AndroidManifest.xml` 的 `android:name=".JevAccessibilityService"`
 *   2. `res/xml/accessibility_service_config.xml` 挂在这个组件上
 *   3. **系统 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里存的组件名**
 *      —— 也就是「用户已经点过授权」的那个字符串
 *
 * 第 3 条是关键：改名之后，系统里记着的那个组件名就再也匹配不上任何东西，
 * 老用户的读屏授权**当场失效**，必须重新手动开启一次。
 * 而「读屏掉了」恰恰是这个 App 最致命的故障（悬浮球还在、点它也有反应，
 * 微信来消息却毫无动静）—— 我们刚为此专门做了断线告警，
 * 没有理由再用一次改名把它主动制造出来。
 *
 * 真要改，正确做法是新老两个类并存、老类做空壳转发，并在引导页提示用户重开授权。
 * 目前不值得为此付这个代价。
 */
class JevAccessibilityService : AccessibilityService() {

    private var lastScanAt = 0L
    private var screenW = 0
    private var screenH = 0
    private var lastLogKey = ""
    private var lastLogAt = 0L
    private var lastRootDesc = ""

    /** 已经体检过的包名，每个只记一次 */
    private val probedPkgs = HashSet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val changed = ChatBus.setAccessibilityConnected(true)
        if (changed) Logbook.i("读屏", "无障碍服务已连接")
        refreshScreenSize()

        // ═══ 不依赖 XML 里的 accessibilityFlags ═══
        // 真机（OnePlus / Android 16）上出现「每个窗口子节点都只有 1~3 个」的现象，
        // 而 XML 里明明配了 flagIncludeNotImportantViews。
        // 有可能是 ROM 没把 XML 配置应用上 —— 这里用代码再强制设一遍，
        // 并**把最终生效的配置记进日志**，一次看清到底生效了没有。
        try {
            val info = serviceInfo
            if (info == null) {
                Logbook.e("读屏", "serviceInfo 为 null，无法确认无障碍配置是否生效")
            } else {
                info.flags = info.flags or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                serviceInfo = info
                Logbook.i(
                    "读屏",
                    "配置 flags=${decodeFlags(info.flags)} eventTypes=${info.eventTypes} " +
                            "capabilities=${info.capabilities} " +
                            "是无障碍工具=${if (info.isAccessibilityTool) "是" else "否 ← Android 16 会因此读不到内容"} " +
                            "包名过滤=${info.packageNames?.joinToString() ?: "无"}"
                )
            }
        } catch (t: Throwable) {
            Logbook.e("读屏", "设置 serviceInfo 失败", t)
        }

        Logbook.i("读屏", "服务已连接，屏幕 ${screenW}x${screenH}")
    }

    /** 把 flags 翻译成人看得懂的词，免得对着十六进制数猜 */
    private fun decodeFlags(f: Int): String {
        val sb = StringBuilder("0x").append(Integer.toHexString(f)).append("[")
        if (f and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS != 0) sb.append("含不重要视图 ")
        if (f and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) sb.append("可读多窗口 ")
        if (f and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0) sb.append("报告viewId ")
        return sb.append(']').toString()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreenSize()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        val pkg = e.packageName?.toString() ?: return
        val type = e.eventType

        // 诊断：不论是不是微信，先把包名记一笔。
        // 手机厂商的「应用双开 / 微信分身」实现方式不一，有的会换包名、
        // 有的跑在独立用户空间。真机上读不到时，界面上的这份清单能一眼定位原因。
        ChatBus.notePackage(pkg)

        // ── 输入法弹起/收起 ──
        // 悬浮窗靠这个信号切换到「只留一行最佳回复」的简略模式：用户一打字，
        // 概率分布就成了干扰。必须先于下面的 isTarget 判断 —— 输入法事件的
        // packageName 是输入法自己（如 com.baidu.input），不是微信。
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            syncImeState()
        }

        if (!isTarget(pkg)) {
            probeOtherApp(pkg)
            return
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val now = SystemClock.uptimeMillis()
        if (now - lastScanAt < SCAN_INTERVAL_MS) return
        lastScanAt = now

        if (screenW == 0 || screenH == 0) refreshScreenSize()

        val root = findRoot()
        if (root == null) {
            logThrottled("no-root") {
                "收到微信事件，但拿不到根节点（rootInActiveWindow 与 windows 都为空）"
            }
            return
        }
        // 抓整屏消息（含上下文与表情等非文字消息），交给总线去判断是否算「新消息」
        val messages = ChatReader.collect(root, screenW, screenH)
        if (messages.isEmpty()) {
            // 读不到内容 —— 微信把无障碍屏蔽了，只能改走截屏。
            // 但「界面变了」这件事我们收得到，就把它当成截屏的触发器发出去。
            ChatBus.notifyWeChatChanged()

            // 同时把节点树的样子记下来（诊断用，3 秒最多一条）
            logThrottled("tree") { ChatReader.dumpTree(root) }
            return
        }
        ChatBus.publish(messages)
    }

    /**
     * 体检「非目标 App」的节点树 —— 判断限制是全局的还是只针对微信。
     *
     * 这个问题的答案已经拿到了（结论：**只有微信读不到，别的 App 完全正常**），
     * 所以现在改成**每个包整个会话只记一次**，纯粹留个底，不再刷屏。
     */
    private fun probeOtherApp(pkg: String) {
        if (!probedPkgs.add(pkg)) return

        val root = rootInActiveWindow ?: return
        // 只在「这个 App 确实是当前前台窗口」时才记，免得记到别家的树
        if (root.packageName?.toString() != pkg) {
            probedPkgs.remove(pkg)   // 没对上就下次再说
            return
        }
        Logbook.i("体检", "非目标 App [$pkg] → " + ChatReader.dumpTree(root, 24))
    }

    /**
     * 同一类问题 3 秒内只记一条。
     * 无障碍事件每秒几十次，不收敛的话缓冲区几秒就被冲光了。
     *
     * 传 lambda 而不是字符串：体检要走一遍节点树，不该在「这次不记日志」时白跑。
     */
    private fun logThrottled(key: String, msg: () -> String) {
        val now = SystemClock.uptimeMillis()
        if (key == lastLogKey && now - lastLogAt < LOG_THROTTLE_MS) return
        lastLogKey = key
        lastLogAt = now
        Logbook.i("读屏", msg())
    }

    /**
     * 拿根节点。
     *
     * ═══ 这里踩过一个坑（真机 Android 16 上暴露）═══
     * 旧写法是 `rootInActiveWindow?.let { return it }`，只要非 null 就直接用。
     * 但真机上 `rootInActiveWindow` 会返回一个**只有 1~5 个空节点的壳**
     * （既不是微信的窗口，也没有任何文字），于是：
     *   - 后面写的 `windows` 兜底**永远走不到**
     *   - 每 5 秒扫一次都是「节点 5 · 有文字 0」
     *
     * 现在改成**把所有候选根节点拿出来比一比**，挑内容最多的那个：
     *   1. 收集 `rootInActiveWindow` 与 `windows` 里属于目标 App 的所有根节点
     *   2. 优先选「活跃窗口且确实是目标 App 且有子节点」的
     *   3. 否则选子节点最多的
     * 并把这个决策过程记进日志，方便下次直接看出窗口到底长什么样。
     */
    private fun findRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString().orEmpty()
        val activeKids = active?.childCount ?: -1

        val fromWindows = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w?.root ?: continue
                val p = r.packageName?.toString().orEmpty()
                if (isTarget(p)) fromWindows.add(r)
            }
        } catch (_: Throwable) {
            // 某些 ROM 会限制读取窗口列表，忽略，用活跃窗口兜
        }

        val bestWin = fromWindows.maxByOrNull { it.childCount }
        val bestWinKids = bestWin?.childCount ?: -1

        val chosen = when {
            active != null && isTarget(activePkg) && activeKids > 0 -> active
            bestWin != null && bestWinKids > activeKids -> bestWin
            active != null && activeKids > 0 -> active
            bestWin != null -> bestWin
            else -> active
        }

        // 只在这个决策发生变化时记一条，避免刷屏
        val desc = "选根节点 pkg=${chosen?.packageName?.toString()?.takeLast(20).orEmpty()} " +
                "子=${chosen?.childCount ?: -1} │ 活跃=${activePkg.takeLast(20).ifEmpty { "null" }}" +
                "/$activeKids │ windows 里目标窗口 ${fromWindows.size} 个" +
                (bestWin?.let { "/最大子节点 $bestWinKids" } ?: "")
        if (desc != lastRootDesc) {
            lastRootDesc = desc
            Logbook.i("读屏", desc)
        }
        return chosen
    }

    /**
     * 窗口清单 —— 扫不到消息时一起记下来。
     *
     * 除了「包名 / 子节点数」，还特意打出**根节点的类名**与**直接子节点的类名**：
     * 如果微信根节点下挂着 3 个 `View` 而没有 `TextView`，说明它的界面是自绘的
     * （文字画在 Canvas 上，不暴露给无障碍）—— 那就得彻底换技术路线，
     * 而不是继续调边界参数。
     */
    private fun windowInventory(): String = try {
        windows.joinToString(" ｜ ") { w ->
            val r = w?.root
            val kids = (0 until (r?.childCount ?: 0)).mapNotNull { i ->
                r?.getChild(i)?.className?.toString()?.substringAfterLast('.')
            }
            "t${w?.type}/pkg=${r?.packageName?.toString()?.takeLast(20).orEmpty()}" +
                    "/子=${r?.childCount ?: -1}" +
                    "/根=${r?.className?.toString()?.substringAfterLast('.')}" +
                    "/子类=[${kids.joinToString(",")}]"
        }
    } catch (t: Throwable) {
        "窗口列表读取失败：${t.message}"
    }

    /**
     * 判断输入法现在是否显示，并把结果发给 [ChatBus]。
     *
     * ═══ 为什么用「窗口类型」而不是包名 ═══
     * 输入法的包名五花八门（各家输入法、系统自带、安全键盘…），
     * 靠包名列表判断必然漏。而系统给输入法窗口的类型是恒定的
     * [AccessibilityWindowInfo.TYPE_INPUT_METHOD] —— 这是唯一可靠的判据。
     *
     * ═══ 触发时机 ═══
     * 由 `TYPE_WINDOW_STATE_CHANGED` 驱动（键盘弹出/收起时系统会发）。
     * ⚠️ 各 ROM 的行为不完全一致，**真机需要验证一次**：
     * 若发现键盘弹起时不切换简略模式，说明这台机器不发该事件，
     * 届时改用轮询 `windows` 或改在 `TYPE_WINDOWS_CHANGED` 上触发。
     *
     * 任何异常都当作「没弹键盘」—— 宁可退回完整卡片，也不要卡在简略模式。
     */
    private fun syncImeState() {
        val visible = try {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: false
        } catch (_: Throwable) {
            false
        }
        if (ChatBus.setImeVisible(visible)) {
            Logbook.i("读屏", if (visible) "输入法已弹起 → 悬浮窗切换为简略模式" else "输入法已收起 → 恢复完整卡片")
        }
    }

    override fun onInterrupt() {
        // 系统要求实现，这里没有需要中断的任务
    }

    override fun onUnbind(intent: Intent?): Boolean {
        noteDisconnected("onUnbind")
        return super.onUnbind(intent)
    }

    /**
     * 服务被销毁时的兜底。
     *
     * 有些 ROM 掐掉无障碍时走的是 `onDestroy` 而不是 `onUnbind`，
     * 只挂 `onUnbind` 会漏掉一半的掉线。
     * [noteDisconnected] 内部有幂等判断，两个回调都触发也只记一条日志。
     */
    override fun onDestroy() {
        noteDisconnected("onDestroy")
        super.onDestroy()
    }

    /**
     * 掉线的统一出口。
     *
     * 国产 ROM 会在背后把无障碍关掉（一加 / OPPO 的「保持开启」倒计时、
     * 各家内存回收、用户误点「关闭无障碍」）。这条路径必须留痕，
     * 否则真机排查时完全看不出「它是什么时候没的」。
     */
    private fun noteDisconnected(from: String) {
        if (ChatBus.setAccessibilityConnected(false)) {
            Logbook.w("读屏", "无障碍服务已断开（$from）—— 悬浮窗收不到微信了，需重新开启")
        }
    }

    private fun refreshScreenSize() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                screenW = bounds.width()
                screenH = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
                screenW = dm.widthPixels
                screenH = dm.heightPixels
            }
        } catch (t: Throwable) {
            val dm = resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
    }

    companion object {
        /** 只读微信 */
        const val TARGET_PACKAGE = "com.tencent.mm"

        /**
         * 判断是不是目标 App。
         *
         * 用「包含 tencent.mm」而不是「等于 com.tencent.mm」，
         * 是为了兼容部分「微信分身」实现 —— 它们会给包名加后缀。
         * 实测遇到没覆盖到的分身包名，看界面上的诊断清单即可补进来。
         */
        private fun isTarget(pkg: String): Boolean =
            pkg == TARGET_PACKAGE || pkg.contains("tencent.mm")

        private const val SCAN_INTERVAL_MS = 250L

        private const val LOG_THROTTLE_MS = 3_000L
    }
}
