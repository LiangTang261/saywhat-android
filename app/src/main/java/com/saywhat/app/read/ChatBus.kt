package com.saywhat.app.read

import com.saywhat.app.engine.SubtextState
import com.saywhat.app.util.Logbook
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 读屏服务与悬浮窗服务之间的进程内总线。
 *
 * 两个 Service 互相不该持有对方引用（谁先死谁后死都不确定），
 * 用一个极简单例传话最省心。
 *
 * 这里递的不只是「她刚说的那句话」，而是完整的 [SubtextState]
 * （含我上一条回复、最近几句对话、表情包等非文字消息）——
 * 上下文越全，潜台词判断越准。
 *
 * ═══ 名字的来历（2026-09-23 由 JevBus 改名）═══
 * 原名 `JevBus`，取自参考图里那个叫 Jev 的模型。但 Jev 早已降级为
 * 「未来升级项」（官方暂停发放资格），主线上跑的是 LLM —— 让一个用不上的
 * 第三方名字占着工程里最核心的类名，是纯负债。故改为 `ChatBus`。
 *
 * ⚠️ 与之相对，[com.saywhat.app.JevAccessibilityService] **刻意没有改名**。
 * 无障碍服务的类名是**系统级标识**：它同时出现在
 * `AndroidManifest`、`accessibility_service_config`、以及系统 Settings.Secure 里
 * 用户已授权的组件名中。改名会让老用户的授权失效，需要重新手动开启 ——
 * 而「读屏掉了」正是这个 App 最需要避免的故障。详见那个类的注释。
 */
object ChatBus {

    /**
     * 读屏服务是否已连接（比查系统设置更可靠）。
     *
     * ⚠️ 不要直接改这个字段，用 [setAccessibilityConnected] ——
     * 它要顺带通知订阅者。国产 ROM（一加 / OPPO 的「保持开启」倒计时、
     * 各家内存回收）会**在背后悄悄关掉无障碍**，改字段而不通知的话，
     * 悬浮窗会一直以为自己在工作，用户只看到「球还在、没反应」。
     */
    @Volatile
    var accessibilityConnected: Boolean = false
        private set

    /** 掉线 / 恢复的订阅者（悬浮窗服务用它弹告警） */
    private val accessListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addAccessibilityListener(l: (Boolean) -> Unit) {
        if (!accessListeners.contains(l)) accessListeners.add(l)
    }

    fun removeAccessibilityListener(l: (Boolean) -> Unit) {
        accessListeners.remove(l)
    }

    /**
     * 改变连接状态并通知订阅者。
     *
     * 只在**真的变了**的时候才发通知 —— 无障碍连接回调可能重复触发，
     * 不做这个判断的话用户会被反复弹告警。
     *
     * @return 状态是否发生了变化
     */
    fun setAccessibilityConnected(value: Boolean): Boolean {
        if (accessibilityConnected == value) return false
        accessibilityConnected = value
        if (value) everConnectedThisSession = true
        for (l in accessListeners) runCatching { l(value) }
        return true
    }

    /**
     * 本次进程生命周期内，读屏是否**曾经**连上过。
     *
     * 用来区分两件完全不同的事：
     *   - 从没连上过  → 用户还没去开启授权，属于「没配好」
     *   - 连上又断了  → 被系统或手机管家关掉了，属于「被掐了」，必须告警
     */
    @Volatile
    var everConnectedThisSession: Boolean = false
        private set

    /** 最近一次抓到的完整上下文 */
    @Volatile
    var lastState: SubtextState? = null
        private set

    @Volatile
    var lastAt: Long = 0L
        private set

    // ══════════════════════════════════════════════════════════
    //  「新消息」判定 —— 这里踩过一个致命的坑，改动前务必读完
    // ══════════════════════════════════════════════════════════
    //
    // 【旧实现的 bug】
    // 曾经用「上一条底部消息是否还在新快照里」来判断是不是用户滚动。
    // 但当时算指纹时**把像素坐标 bottom 也算进去了**：
    //     fingerprint = "TEXT|她说的话|null|800"
    // 新消息一到达，微信列表往上滚，上一条消息的 bottom 从 800 变成 650，
    // 于是「找不到上一条」→ 判定为滚动 → 直接 return false。
    // 结果：**只有第一条消息能触发分析，之后全被静默丢弃。**
    // 这个 bug 只在真机上暴露 —— 模拟器测试走的是剪贴板路径，没经过这段代码。
    //
    // 【现在的做法】
    // 指纹**只由内容构成，绝不含坐标**。再用一个「见过的内容」集合来区分
    // 「来了新消息」和「用户滚回去看旧消息」：
    //   - 底部那条的内容没见过  → 新消息，触发分析
    //   - 底部那条的内容见过    → 多半是滚回去的，不触发
    // 这样滚动不会误触发，新消息也不会漏。

    /** 见过的消息内容（有界，越老的先丢） */
    private val seen = LinkedHashSet<String>()

    private const val MAX_SEEN = 80

    /** 只按内容算指纹 —— 坐标绝不能被掺进来 */
    private fun contentKey(m: ChatMessage): String = "${m.kind}|${m.text}|${m.label}"

    private fun remember(messages: List<ChatMessage>) {
        for (m in messages) {
            val k = contentKey(m)
            seen.remove(k)   // 重新放到队尾，保持 LRU
            seen.add(k)
        }
        val it = seen.iterator()
        while (seen.size > MAX_SEEN && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    private var lastFiredKey: String? = null
    private var lastFiredAt = 0L

    /** 上一次记日志时的判定，用于抑制重复日志 */
    private var lastLoggedKey: String? = null
    private var lastLoggedReason: String? = null

    /** 送进 prompt 的最近对话条数 */
    private const val HISTORY_SIZE = 6

    // ── 诊断统计 ───────────────────────────────────────────
    //
    // 真机上读屏一旦不工作，用户在手机上看不出原因，我们也拿不到 logcat。
    // 把「无障碍服务到底看见了什么」直接显示在界面上，就能一眼定位。

    private val pkgEvents = ConcurrentHashMap<String, AtomicInteger>()

    /** 无障碍服务每收到一个事件（不论哪个包）都记一笔 */
    fun notePackage(pkg: String) {
        if (pkg.isEmpty()) return
        pkgEvents.getOrPut(pkg) { AtomicInteger() }.incrementAndGet()
    }

    /** 事件次数最多的前 n 个包名 */
    fun topPackages(n: Int = 6): List<Pair<String, Int>> =
        pkgEvents.entries
            .sortedByDescending { it.value.get() }
            .take(n)
            .map { it.key to it.value.get() }

    fun resetPackages() = pkgEvents.clear()

    /** 扫到过消息的次数（说明节点树里确实有消息） */
    @Volatile
    var scanHits: Int = 0
        private set

    /** 真正判定为「新消息」并触发分析的次数 */
    @Volatile
    var fireCount: Int = 0
        private set

    /** 最近一次扫到的最后一条消息 */
    @Volatile
    var lastScanned: String? = null
        private set

    /** 最近一次「扫到了但没触发」的原因 —— 诊断面板会显示它 */
    @Volatile
    var lastSkipReason: String? = null
        private set

    fun resetCounters() {
        scanHits = 0
        fireCount = 0
        lastSkipReason = null
        seen.clear()
        lastFiredKey = null
        lastLoggedKey = null
        lastLoggedReason = null
    }

    private val listeners = CopyOnWriteArrayList<(SubtextState) -> Unit>()

    /**
     * 「微信界面变了」的信号 —— 给截屏通道当触发器用。
     *
     * 为什么需要它：微信屏蔽了无障碍（真机实测：窗口根节点都返回 null），
     * 我们**读不到**它的节点树，但**收得到**它的界面变化事件。
     * 于是把无障碍降级成「触发器」：一收到事件就去截屏，内容由截图提供。
     */
    private val wechatChangedListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addWeChatChangedListener(l: () -> Unit) {
        if (!wechatChangedListeners.contains(l)) wechatChangedListeners.add(l)
    }

    fun removeWeChatChangedListener(l: () -> Unit) {
        wechatChangedListeners.remove(l)
    }

    fun notifyWeChatChanged() {
        for (l in wechatChangedListeners) runCatching { l() }
    }

    // ══════════════════════════════════════════════════════════
    //  输入法是否弹起
    // ══════════════════════════════════════════════════════════
    //
    // 悬浮窗用它决定「显示整张卡片」还是「只留一行最佳回复」：
    // 用户正在打字时，概率分布是纯干扰，他只想看「我该发什么」。
    //
    // 判据由无障碍服务提供（它看得到窗口类型），这里只负责转发。

    /** 输入法是否正在显示 */
    @Volatile
    var imeVisible: Boolean = false
        private set

    private val imeListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addImeListener(l: (Boolean) -> Unit) {
        if (!imeListeners.contains(l)) imeListeners.add(l)
    }

    fun removeImeListener(l: (Boolean) -> Unit) {
        imeListeners.remove(l)
    }

    /**
     * 无障碍服务检测到输入法出现 / 消失时调用。
     * @return 状态是否变化（调用方据此决定要不要记日志，避免刷屏）
     */
    fun setImeVisible(value: Boolean): Boolean {
        if (imeVisible == value) return false
        imeVisible = value
        for (l in imeListeners) runCatching { l(value) }
        return true
    }

    fun addListener(l: (SubtextState) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (SubtextState) -> Unit) {
        listeners.remove(l)
    }

    /**
     * 读屏服务每扫到一次消息列表就调用。
     * @return 是否判定为「来了新消息」因而触发了分析
     */
    fun publish(messages: List<ChatMessage>): Boolean {
        if (messages.isEmpty()) return false

        scanHits++
        lastScanned = messages.last().display

        val bottom = messages.last()
        val bottomKey = contentKey(bottom)

        // ⚠️ 顺序很重要：先判断「这条见过没有」，再把本屏内容记进 seen。
        // 反过来的话，新消息一进来就已经在 seen 里了，永远不会被判为新。
        val skipReason = when {
            bottom.fromMe -> "底部那条是自己发的"
            !bottom.isContent -> "底部那条不是有效内容"
            bottomKey !in seen -> null                       // 没见过 → 新消息
            bottomKey == lastFiredKey -> "同一条已经分析过了"
            else -> "这条内容以前见过（多半是滚回去看旧消息）"
        }

        remember(messages)

        // 只在「判定结果有变化」时记日志 —— 无障碍事件很密，
        // 每条都记的话缓冲区几秒就被冲光了
        if (bottomKey != lastLoggedKey || skipReason != lastLoggedReason) {
            lastLoggedKey = bottomKey
            lastLoggedReason = skipReason
            Logbook.i(
                "总线",
                "共 ${messages.size} 条 | 底部「${bottom.display.take(24)}」 | " +
                        (skipReason?.let { "跳过：$it" } ?: "→ 触发分析 ✓")
            )
        }

        if (skipReason != null) {
            lastSkipReason = skipReason
            return false
        }

        val now = System.currentTimeMillis()
        lastFiredKey = bottomKey
        lastFiredAt = now
        lastAt = now
        lastSkipReason = null
        fireCount++

        val state = buildState(messages)
        lastState = state

        for (l in listeners) runCatching { l(state) }
        return true
    }

    private fun buildState(messages: List<ChatMessage>): SubtextState {
        val idx = messages.indexOfLast { !it.fromMe && it.isContent }
        if (idx < 0) return SubtextState(herMessage = messages.last().display)

        val herMsg = messages[idx]

        // 「我上一句」取她这条之前、我发过的最后一条
        val myLastReply = messages.subList(0, idx)
            .lastOrNull { it.fromMe && it.isContent }
            ?.display

        // 最近对话，带角色前缀，让模型能分清谁说的
        val history = messages.takeLast(HISTORY_SIZE).map {
            if (it.fromMe) "我：${it.display}" else "她：${it.display}"
        }

        return SubtextState(
            herMessage = herMsg.display,
            myLastReply = myLastReply,
            recentHistory = history
        )
    }
}
