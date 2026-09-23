package com.saywhat.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.saywhat.app.capture.CapturePermissionActivity
import com.saywhat.app.data.BallStyles
import com.saywhat.app.data.Providers
import com.saywhat.app.data.Settings
import com.saywhat.app.engine.EngineHub
import com.saywhat.app.read.ChatBus
import com.saywhat.app.util.KeepAlive
import com.saywhat.app.util.Perms
import java.util.concurrent.Executors

/**
 * 主界面 = 引导页。
 *
 * 五步，每一步都只有一个按钮直达系统设置页 ——
 * 用户不需要知道「悬浮窗权限在设置的哪一层」。
 *
 * 步骤 ③ 的详细配置（服务商 / Key / 地址 / 模型 / 引擎）全部收在
 * [ApiSettingsActivity] 里，本页只显示一张「当前生效配置」的摘要。
 *
 * 步骤 ⑤ 是「保活」：整条链路挂在无障碍服务上，它被系统掐掉之后
 * 悬浮球还在、点它也有反应，但微信来消息毫无动静 —— 这一步就是防这个。
 */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 本次进程内是否已经提示过「通知被关了」，避免每次 onResume 都弹 */
    private var notifWarnedThisSession = false

    private lateinit var step1State: TextView
    private lateinit var step2State: TextView
    private lateinit var step3State: TextView
    private lateinit var step4State: TextView
    private lateinit var step4Btn: Button
    private lateinit var step5State: TextView
    private lateinit var step5Btn: Button
    private lateinit var step5BtnAutostart: Button
    private lateinit var alertBanner: TextView
    private lateinit var firstRunHint: TextView

    /**
     * 「曾经连上过」的记录。
     *
     * [ChatBus.everConnectedThisSession] 统计的是**进程**生命周期，
     * 而用户完全可能在别的界面待着、App 进程已被回收，
     * 所以这里单独记一份「用户在这台手机上确实开通过」的持久标记。
     */
    private var everConnectedBefore: Boolean = false
    private lateinit var tvCurrentApi: TextView
    private lateinit var diagText: TextView
    private lateinit var etMinLen: EditText
    private lateinit var swAuto: Switch
    private lateinit var swDanger: Switch
    private lateinit var spBallStyle: Spinner
    private lateinit var btnToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = Settings(this)
        everConnectedBefore = settings.accessEverConnected

        step1State = findViewById(R.id.step1_state)
        step2State = findViewById(R.id.step2_state)
        step3State = findViewById(R.id.step3_state)
        step4State = findViewById(R.id.step4_state)
        step4Btn = findViewById(R.id.step4_btn)
        step5State = findViewById(R.id.step5_state)
        step5Btn = findViewById(R.id.step5_btn)
        step5BtnAutostart = findViewById(R.id.step5_btn_autostart)
        alertBanner = findViewById(R.id.alert_banner)
        firstRunHint = findViewById(R.id.first_run_hint)
        // 点告警条直接去无障碍设置 —— 这是用户此刻唯一要做的动作
        alertBanner.setOnClickListener { Perms.openAccessibilitySettings(this) }
        tvCurrentApi = findViewById(R.id.tv_current_api)
        diagText = findViewById(R.id.diag_text)

        // 版本号从构建配置读，不再写死在布局里 —— 免得改了版本忘了改界面
        findViewById<TextView>(R.id.tv_version).text =
            "v${BuildConfig.VERSION_NAME} · 娱乐向 · 零第三方依赖"
        etMinLen = findViewById(R.id.et_min_len)
        swAuto = findViewById(R.id.sw_auto)
        swDanger = findViewById(R.id.sw_danger)
        spBallStyle = findViewById(R.id.sp_ball_style)

        // 悬浮球样式下拉
        val ballAdapter = ArrayAdapter(this, R.layout.item_spinner, BallStyles.LABELS)
        ballAdapter.setDropDownViewResource(R.layout.item_spinner)
        spBallStyle.adapter = ballAdapter
        btnToggle = findViewById(R.id.btn_toggle_start)

        loadIntoFields()

        findViewById<Button>(R.id.step1_btn).setOnClickListener {
            Perms.openOverlaySettings(this)
        }
        findViewById<Button>(R.id.step2_btn).setOnClickListener {
            // 国产 ROM 开启无障碍时常再弹一次确认，选错（禁止）就读不到消息了。
            // 所以这里先用一个必须点掉的弹窗把话说清楚，再跳系统设置。
            AlertDialog.Builder(this)
                .setTitle(R.string.step2_dialog_title)
                .setMessage(R.string.step2_dialog_msg)
                .setPositiveButton(R.string.step2_dialog_ok) { _, _ ->
                    Perms.openAccessibilitySettings(this)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        findViewById<Button>(R.id.step3_btn).setOnClickListener {
            startActivity(Intent(this, ApiSettingsActivity::class.java))
        }
        // 截屏权限：按御主定的原则「大大方方要」—— 明说用途，不藏在二级菜单里
        step4Btn.setOnClickListener {
            startActivity(Intent(this, CapturePermissionActivity::class.java))
        }

        // 第 ⑤ 步：保活。检测 + 一键跳转，两件事分开做
        step5Btn.setOnClickListener {
            if (KeepAlive.isIgnoringBatteryOptimizations(this)) {
                // 已经在白名单里了，按钮变成「再确认一次」的语义 —— 直接刷新状态即可
                refreshStates()
                toast(getString(R.string.toast_keepalive_already))
            } else {
                KeepAlive.requestIgnoreBatteryOptimizations(this)
            }
        }
        step5BtnAutostart.setOnClickListener {
            val opened = KeepAlive.openAutoStartSettings(this)
            toast(
                getString(
                    if (opened) R.string.toast_keepalive_autostart
                    else R.string.toast_keepalive_autostart_fallback
                )
            )
        }
        findViewById<Button>(R.id.btn_analyze_clip).setOnClickListener { analyzeClipboard() }
        findViewById<TextView>(R.id.diag_refresh).setOnClickListener { refreshDiagnostics() }
        findViewById<Button>(R.id.btn_open_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        btnToggle.setOnClickListener { toggleOverlay() }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
        // 诊断面板每 2 秒自动刷新一次：用户去微信里点几下再切回来，
        // 数字应该肉眼可见地涨，能立刻判断读屏是不是在工作
        main.post(diagTicker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(diagTicker)
        syncFromFields()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // ── 状态刷新 ───────────────────────────────────────────

    private fun refreshStates() {
        bind(step1State, Perms.canDrawOverlays(this))
        val accessOk = Perms.isAccessibilityEnabled(this)
        bind(step2State, accessOk)
        bind(step3State, settings.isConfigured)

        // 持久记住「确实开通过」——进程被杀、设备重启都不会丢
        if (accessOk) {
            if (!everConnectedBefore) {
                everConnectedBefore = true
                settings.accessEverConnected = true
            }
        }

        // 顶部告警条：只在「曾经开通过、现在没了」时出现。
        // 从没开通过的用户不该看到这条 —— 他会以为出了故障，
        // 其实只是还没做第 ② 步，那种情况由步骤卡自己说明。
        alertBanner.visibility = if (!accessOk && everConnectedBefore) View.VISIBLE else View.GONE

        // 显示「此刻投影是否活着」，而不是「用户是否允许过」——
        // Android 14+ 的截屏授权是一次性的，服务重启就得重新授权
        val capOk = OverlayService.captureReady
        bind4(step4State, capOk)
        step4Btn.text = getString(
            if (capOk) R.string.action_recapture else R.string.action_allow_capture
        )

        refreshKeepAlive()

        // ── 新手「首次验证」引导 ──
        // 五步都齐了、通知也开着、但一次都没跑出过卡片 —— 这是新手最迷茫的那一刻：
        // 他做完了所有授权，却不知道接下来该去哪里、该期望看到什么。
        // 一旦成功分析过一次（settings.hasAnalyzedOnce），这条就永久消失。
        val allReady = Perms.canDrawOverlays(this) &&
                accessOk &&
                settings.isConfigured &&
                capOk &&
                KeepAlive.isIgnoringBatteryOptimizations(this)
        firstRunHint.visibility =
            if (allReady && !settings.hasAnalyzedOnce) View.VISIBLE else View.GONE

        tvCurrentApi.text = describeCurrentApi()

        btnToggle.text = getString(
            if (OverlayService.running) R.string.btn_stop else R.string.btn_start
        )
    }

    /** 给用户看一行「当前生效的是什么」，但绝不显示 Key 本身 */
    private fun describeCurrentApi(): String {
        if (!settings.isConfigured) return "尚未配置"
        val provider = Providers.byId(settings.providerId).name
        val engine = EngineHub.available.firstOrNull { it.first == settings.engineId }?.second
            ?: settings.engineId
        return "$provider · ${settings.model} · $engine"
    }

    private fun bind(state: TextView, ok: Boolean) {
        state.text = getString(if (ok) R.string.state_granted else R.string.state_missing)
        state.setTextColor(if (ok) 0xFF4ADE80.toInt() else 0xFFFBBF24.toInt())
    }

    /** 第 4 步的用词不一样：不是「授权」而是「允许截屏」 */
    private fun bind4(state: TextView, ok: Boolean) {
        state.text = getString(
            if (ok) R.string.capture_state_ready else R.string.capture_state_missing
        )
        state.setTextColor(if (ok) 0xFF4ADE80.toInt() else 0xFFFBBF24.toInt())
    }

    /**
     * 第 ⑤ 步：保活状态。
     *
     * 「已保护」的唯一判据是**电池优化白名单**。自启动白名单没有可靠的
     * 公开 API 可查（各家 ROM 私有），所以那一项只能靠按钮让用户自己去看，
     * 不假装知道结果 —— 宁可少显示一个绿标，也不要给用户一个假的「已保护」。
     *
     * 顺带把「通知被关掉」这件事也摊在同一张卡里：
     * 读屏断开时全靠通知提醒，通知一关，App 就会**静默失效**，
     * 这与保活是同一类问题，放一起用户才看得懂。
     */
    private fun refreshKeepAlive() {
        val batteryOk = KeepAlive.isIgnoringBatteryOptimizations(this)
        val notifOk = KeepAlive.areNotificationsEnabled(this)

        bind(step5State, batteryOk)
        step5Btn.text = getString(
            if (batteryOk) R.string.action_keepalive_done else R.string.action_keepalive
        )

        val desc = StringBuilder(getString(R.string.step5_desc))
        if (!notifOk) {
            desc.append("\n\n⚠️ 通知权限被关掉了：读屏万一断开，就没法弹通知提醒你。")
        }
        findViewById<TextView>(R.id.step5_desc).text = desc
    }

    // ── 读屏诊断 ───────────────────────────────────────────

    private val diagTicker = object : Runnable {
        override fun run() {
            refreshDiagnostics()
            main.postDelayed(this, 2000)
        }
    }

    /**
     * 把「无障碍服务到底看见了什么」直接摊在界面上。
     *
     * 真机上读不到微信时，用户没法看 logcat。有了这块，
     * 一眼就能区分三种情况：
     *   1. 清单里压根没有微信包名 → 分身用了别的包名，或跑在独立用户空间
     *   2. 有包名但「扫描到消息」是 0 → 节点树结构和预期不同，要改 ChatReader
     *   3. 有扫描但「触发分析」是 0 → 去重逻辑太激进
     */
    private fun refreshDiagnostics() {
        val sb = StringBuilder()
        sb.append("服务状态     ")
            .append(if (ChatBus.accessibilityConnected) "运行中" else "未连接")
            .append('\n')

        val pkgs = ChatBus.topPackages(6)
        if (pkgs.isEmpty()) {
            sb.append("尚未收到任何 App 的事件\n（去微信里点几下，再回来看这里）")
        } else {
            sb.append("事件最多的 App：\n")
            for ((name, count) in pkgs) {
                sb.append("  ").append(name).append("  ×").append(count).append('\n')
            }
        }

        sb.append("扫描到消息   ").append(ChatBus.scanHits).append(" 次\n")
        sb.append("触发分析     ").append(ChatBus.fireCount).append(" 次")
        ChatBus.lastSkipReason?.let {
            sb.append("\n未触发原因   ").append(it)
        }
        ChatBus.lastScanned?.let {
            sb.append("\n最新读到     「").append(it.take(36)).append("」")
        }

        diagText.text = sb.toString()
    }

    // ── 配置读写（本页只剩行为设置） ────────────────────────

    private fun loadIntoFields() {
        etMinLen.setText(settings.minLength.toString())
        swAuto.isChecked = settings.autoAnalyze
        swDanger.isChecked = settings.dangerAlert

        val styleIdx = BallStyles.ALL.indexOfFirst { it.first == settings.ballStyle }
        spBallStyle.setSelection(if (styleIdx >= 0) styleIdx else 0)
    }

    private fun syncFromFields() {
        settings.minLength = etMinLen.text.toString().toIntOrNull() ?: 2
        settings.autoAnalyze = swAuto.isChecked
        settings.dangerAlert = swDanger.isChecked

        val pos = spBallStyle.selectedItemPosition
        if (pos in BallStyles.ALL.indices) settings.ballStyle = BallStyles.ALL[pos].first
    }

    // ── 动作 ───────────────────────────────────────────────

    private fun toggleOverlay() {
        syncFromFields()
        refreshStates()

        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            settings.overlayRunning = false
            toast(getString(R.string.toast_stopped))
            main.postDelayed({ refreshStates() }, 300)
            return
        }

        if (!Perms.canDrawOverlays(this)) {
            toast(getString(R.string.toast_no_overlay))
            Perms.openOverlaySettings(this)
            return
        }
        if (!Perms.isAccessibilityEnabled(this)) {
            toast(getString(R.string.toast_no_access))
            Perms.openAccessibilitySettings(this)
            return
        }

        // 没填 Key 也允许启动 —— 这样能单独验证「读屏能不能读到微信」，
        // 把「能不能读消息」和「有没有 Key」解耦。读屏成功时卡片仍会显示识别到的原句。
        if (!settings.isConfigured) {
            toast(getString(R.string.toast_no_key_hint))
        } else {
            toast(getString(R.string.toast_started))
        }

        startForegroundService(
            Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        )
        main.postDelayed({ refreshStates() }, 500)
    }

    private fun analyzeClipboard() {
        syncFromFields()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        } else ""

        if (text.isEmpty()) {
            toast(getString(R.string.toast_clip_empty))
            return
        }
        if (!settings.isConfigured) {
            toast(getString(R.string.toast_no_key))
            startActivity(Intent(this, ApiSettingsActivity::class.java))
            return
        }

        if (!OverlayService.running) {
            if (!Perms.canDrawOverlays(this)) {
                toast(getString(R.string.toast_no_overlay))
                Perms.openOverlaySettings(this)
                return
            }
            startForegroundService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            )
        }

        startForegroundService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_ANALYZE)
                .putExtra(OverlayService.EXTRA_TEXT, text)
        )

        moveTaskToBack(true)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    /**
     * 通知被关掉时必须主动说一声。
     *
     * 为什么值得单独弹一个对话框：本 App 的**唯一失效告警渠道就是通知**。
     * 读屏被 ROM 掐掉时，全靠那条高优先级通知告诉用户「出事了」；
     * 通知一旦被关，App 就会**静默失效** —— 悬浮球还在、点它也有反应，
     * 但微信来消息毫无动静，用户完全无从判断是 App 坏了还是没人发消息。
     *
     * 只弹一次（内存标记，不落盘）：用户明确拒绝后不该反复骚扰，
     * 但每次冷启动都该有机会看到 —— 这跟「永久不再提示」是两回事。
     */
    private fun warnIfNotificationsOff() {
        if (notifWarnedThisSession) return
        if (KeepAlive.areNotificationsEnabled(this)) return
        notifWarnedThisSession = true

        AlertDialog.Builder(this)
            .setTitle(R.string.notif_off_title)
            .setMessage(R.string.toast_notif_off)
            .setPositiveButton(R.string.notif_off_fix) { _, _ ->
                runCatching {
                    // 全限定名：本文件已导入 App 自己的 data.Settings，简写会撞名
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_NOTIF = 100
    }
}
