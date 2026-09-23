package com.saywhat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.saywhat.app.capture.ChatShot
import com.saywhat.app.capture.ScreenCapture
import com.saywhat.app.data.BallStyles
import com.saywhat.app.data.Settings
import com.saywhat.app.engine.CardData
import com.saywhat.app.engine.EngineHub
import com.saywhat.app.engine.EngineResult
import com.saywhat.app.engine.ProbItem
import com.saywhat.app.engine.Scene
import com.saywhat.app.engine.SubtextState
import com.saywhat.app.read.ChatBus
import com.saywhat.app.util.Logbook
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮窗服务：一个常驻悬浮球 + 一张分析卡片。
 *
 * 设计取舍：
 * - 卡片窗口只加 FLAG_NOT_FOCUSABLE，不加 FLAG_NOT_TOUCHABLE。
 *   这样既不会抢走微信的输入焦点，卡片上的关闭按钮又点得到。
 * - 调模型是阻塞 IO，绝不能放主线程；这里用单线程池串行执行，
 *   天然避免「连发三条消息时三个请求互相覆盖」。
 * - 引擎由 [EngineHub] 造，本类不认识任何具体引擎；
 *   将来换成 Jev、本地小模型或别的服务，这里一行都不用改。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var ballView: View? = null
    private var cardView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var cardParams: WindowManager.LayoutParams? = null

    private var screenW = 0
    private var screenH = 0
    private var cardVisible = false
    private var busy = false
    private var currentQuote: String = ""

    /** 上一次构建的通知，提升前台类型时要复用同一个（不能传 null） */
    private var lastNotification: Notification? = null

    /** 截屏通道。仅当用户在设置里开启、且授权成功后才非空 */
    private var capture: ScreenCapture? = null

    /** 上一次分析是否失败了 —— 失败时再点球会自动重试 */
    private var lastFailed = false

    /**
     * 最近一次拿到的最佳回复。
     *
     * 存下来是为了两件事：① 简略模式要在键盘弹出时立刻能用，不能等下一次分析；
     * ② 用户点了简略条时要能复制到它。所以它必须活得比某一次渲染久。
     */
    private var lastReply: String? = null

    /** 长按关闭用的定时器与倒计时 */
    private val closeRunnable = Runnable { closeByLongPress() }
    private var countdownRunnable: Runnable? = null
    private var holdStartedAt = 0L

    /** 判定为日常闲聊时，卡片自动收起的时间点 */
    private var autoHideAt = 0L

    private val incomingListener: (SubtextState) -> Unit = { state ->
        if (settings.autoAnalyze && state.herMessage.length >= settings.minLength) {
            analyze(state)
        }
    }

    // ══════════════════════════════════════════════════════════
    //  「断线告警」—— 保活这条线上最关键的一段
    // ══════════════════════════════════════════════════════════
    //
    // 整条链路挂在无障碍服务上：无障碍事件 → 触发截屏 → 分析 → 卡片。
    // 它被系统或手机管家关掉之后，**悬浮球还在、点它也有反应，但微信来消息毫无动静**。
    // 用户只会认为 App 坏了 —— 这是最容易把信任一次性耗光的失败模式。
    //
    // 所以：只要断开，就主动弹一条高优先级通知 + 在卡片上写明原因与怎么恢复。
    // 恢复后自动清掉，不留残留。

    /** 当前是否处于「读屏断了」的告警态，用于抑制重复告警 */
    private var accessAlerted = false

    /** 卡片上此刻显示的是告警而不是分析结果 */
    private var alertOnCard = false

    // ── 截屏授权失效的提醒 ────────────────────────────────
    //
    // ⚠️ 这是真机反馈暴露出来的一个盲区（御主 2026-09-23）：
    // 「关闭悬浮窗再重启后，没有对截屏权限的提示」。
    //
    // 根因：Android 14+ 的 MediaProjection 授权是**一次性**的 ——
    // 悬浮窗一关，授权就没了；重新开启时必须重新授权一次。
    // 而授权只写在引导页第 ④ 步，**服务启动路径上完全没有检查**，
    // 于是用户重启悬浮窗后看到的是「球在、但永远不分析」，又一次以为 App 坏了。

    /** 当前是否已就截屏失效提醒过，避免重复打扰 */
    private var captureAlerted = false

    /** 本次服务启动的时刻，用于「等一下再断言没连上」的宽限期 */
    private var startedAt = 0L

    /** 无障碍连接状态变化的回调 */
    private val accessListener: (Boolean) -> Unit = { connected ->
        main.post { onAccessibilityChanged(connected) }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startedAt = SystemClock.elapsedRealtime()
        settings = Settings(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshScreenSize()

        startAsForeground()
        showBall()
        ChatBus.addListener(incomingListener)
        ChatBus.addWeChatChangedListener(wechatChangedListener)
        ChatBus.addAccessibilityListener(accessListener)
        // 输入法弹起/收起 → 在「整张卡片」与「只留最佳回复的简略条」之间切换
        ChatBus.addImeListener(imeListener)
        // 服务刚起来时键盘可能已经弹着（比如用户从微信切回来），补一次状态
        imeVisible = ChatBus.imeVisible
        if (imeVisible) main.post { onImeChanged(true) }

        // 启动瞬间读屏可能还没连上（用户刚开完授权、或服务正在重建），
        // 给 3 秒宽限再断言，避免刚启动就误报一次
        main.postDelayed({
            checkAccessibility(initial = true)
            checkCapturePermission()
        }, ACCESS_GRACE_MS)

        if (settings.overlayRunning) {
            main.postDelayed({ ChatBus.lastState?.let { analyze(it) } }, 800)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.overlayRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ANALYZE -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.trim()
                if (!text.isNullOrEmpty()) {
                    analyze(SubtextState(herMessage = text))
                }
            }
            ACTION_GRANT_CAPTURE -> {
                onCaptureGranted(
                    intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                )
            }
            ACTION_CAPTURE_DENIED -> {
                settings.captureEnabled = false
                toast(getString(R.string.capture_denied))
            }
            ACTION_REQUEST_CAPTURE -> {
                // 从「截屏授权已失效」的提醒里点进来的：直接把系统授权页拉起来，
                // 用户不必先回主界面找第 ④ 步
                runCatching {
                    startActivity(
                        Intent(this, com.saywhat.app.capture.CapturePermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            else -> settings.overlayRunning = true
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        ChatBus.removeListener(incomingListener)
        ChatBus.removeWeChatChangedListener(wechatChangedListener)
        ChatBus.removeAccessibilityListener(accessListener)
        ChatBus.removeImeListener(imeListener)
        // 告警通知不能在悬浮窗关闭后继续挂着：它描述的是一件已经不存在的事
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(ALERT_NOTIF_ID)
        }
        main.removeCallbacks(captureRunnable)
        capture?.stop()
        capture = null
        captureReady = false
        runCatching { ballView?.let { wm.removeView(it) } }
        runCatching { cardView?.let { wm.removeView(it) } }
        runCatching { compactView?.let { wm.removeView(it) } }
        ballView = null
        cardView = null
        compactView = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ── 断线检测与告警 ─────────────────────────────────────

    /** 无障碍连接状态一变就会被叫到 */
    private fun onAccessibilityChanged(connected: Boolean) {
        checkAccessibility(initial = false)
    }

    /**
     * 弹「截屏授权已失效」的提醒。
     *
     * 只在**用户确实授权过**（[Settings.captureEverGranted]）时才提醒 ——
     * 从没授权过的人不该看到这条，他本来就没走到第 ④ 步，
     * 该看到的是引导页而不是故障提醒。
     */
    private fun checkCapturePermission() {
        if (!running) return
        if (captureReady) {
            captureAlerted = false
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(CAPTURE_ALERT_ID)
            }
            return
        }
        if (captureAlerted) return
        if (!settings.captureEverGranted) return

        captureAlerted = true
        Logbook.e("保活", "截屏授权已失效 —— 悬浮窗收不到消息，正在提醒用户重新授权")
        runCatching { postCaptureAlertNotification() }
        // 与通知栏**双渠道**提醒（御主 2026-09-23 要求）：
        // 通知栏可能被下拉清掉、也可能被用户忽略，卡片上再来一次才够显眼。
        runCatching {
            renderAlertCard(
                title = getString(R.string.capture_lost_title),
                body = getString(R.string.capture_lost_card)
            )
        }
    }

    private fun postCaptureAlertNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureAlertChannel(nm)

        val fix = PendingIntent.getService(
            this, 4,
            Intent(this, OverlayService::class.java).setAction(ACTION_REQUEST_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_lost_title))
            .setContentText(getString(R.string.capture_lost_text))
            .setStyle(
                Notification.BigTextStyle().bigText(getString(R.string.capture_lost_long))
            )
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(fix)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.capture_lost_action), fix).build()
            )
            .build()

        nm.notify(CAPTURE_ALERT_ID, notif)
    }

    /**
     * 检查读屏是否还在，需要时告警。
     *
     * 两种情况都算「断了」：
     *   - 本次会话**曾经连上过**，现在断了 → 被系统 / 手机管家掐掉的，立刻告警
     *   - 本次启动后**从来没连上**（过了宽限期）→ 用户还没授权或授权没生效
     *
     * 两者文案不同：前者要教用户「怎么把它弄回来」，后者要教「怎么去开」。
     */
    private fun checkAccessibility(initial: Boolean) {
        if (!running) return
        if (ChatBus.accessibilityConnected) {
            clearAccessAlert()
            return
        }
        if (initial && SystemClock.elapsedRealtime() - startedAt < ACCESS_GRACE_MS) return
        raiseAccessAlert(dropped = ChatBus.everConnectedThisSession)
    }

    /**
     * 弹出「读屏断了」的告警：一条高优先级通知 + 卡片上的说明。
     *
     * 通知必须与前台服务的常驻通知**分开 ID** ——
     * 前台通知在 `IMPORTANCE_MIN` 通道里（设计如此，常驻通知不该打扰人），
     * 复用同一个 ID 换通道会让它时隐时现，用户反而更迷惑。
     * 用独立的高优先级通道，才能真的弹出来被人看见。
     */
    private fun raiseAccessAlert(dropped: Boolean) {
        if (accessAlerted) return
        accessAlerted = true

        val reason = if (dropped) "读屏被系统关掉了" else "读屏还没开启"
        Logbook.e("保活", "$reason —— 悬浮窗已收不到微信，正在提醒用户")

        runCatching { postAccessAlertNotification(dropped) }
        runCatching { renderAlert(dropped) }
    }

    private fun postAccessAlertNotification(dropped: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureAlertChannel(nm)

        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val fix = PendingIntent.getActivity(
            this, 3,
            // 用全限定名：本文件已经导入了 App 自己的 data.Settings，简写会撞名
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(if (dropped) R.string.alert_access_dropped_title else R.string.alert_access_missing_title))
            .setContentText(getString(if (dropped) R.string.alert_access_dropped_text else R.string.alert_access_missing_text))
            .setStyle(
                Notification.BigTextStyle().bigText(
                    getString(if (dropped) R.string.alert_access_dropped_long else R.string.alert_access_missing_long)
                )
            )
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.alert_action_fix), fix).build()
            )
            .build()

        nm.notify(ALERT_NOTIF_ID, notif)
    }

    private fun clearAccessAlert() {
        if (!accessAlerted) return
        accessAlerted = false
        Logbook.i("保活", "读屏已恢复 —— 告警解除")
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(ALERT_NOTIF_ID)
        }
        // 卡片上如果正挂着告警说明，就把它收起来
        if (cardVisible && alertOnCard) {
            alertOnCard = false
            hideCard()
        }
    }

    /**
     * 把告警画在卡片上。
     *
     * 为什么不复用 [renderCard]：那张卡片的结构是为「分析结果」设计的，
     * 硬塞一条告警进去会让人以为这是模型的分析结论。
     * 这里只用状态行 + 正文两处，说清「出了什么事 / 怎么修」。
     */
    private fun renderAlert(dropped: Boolean) {
        renderAlertCard(
            title = getString(
                if (dropped) R.string.alert_access_dropped_title else R.string.alert_access_missing_title
            ),
            body = getString(
                if (dropped) R.string.alert_access_dropped_long else R.string.alert_access_missing_long
            )
        )
    }

    /**
     * 把一条告警画到悬浮卡片上（三个告警共用）。
     *
     * ⚠️ 会不会打断用户？
     * 分成两种情况，刻意区别对待：
     *   - **卡片当前没显示** → 直接弹出来。用户本来就没在看分析，弹告警是净收益。
     *   - **卡片正显示着分析结果** → **不动它**，只记进运行日志。
     *     用户正眯着眼看某条分析，这时把内容换成故障提示是纯粹的干扰，
     *     而通知栏那条提醒已经在等着他了。（这是本轮特意做的取舍，不是漏了。）
     */
    private fun renderAlertCard(title: String, body: String) {
        if (cardVisible && !alertOnCard) {
            Logbook.i("保活", "卡片正显示分析结果，告警不打断它（通知栏已有提醒）")
            return
        }

        val v = ensureCard()
        resetCardBody(v)
        alertOnCard = true
        lastFailed = true

        v.findViewById<TextView>(R.id.card_status).text = getString(R.string.alert_card_status)
        v.findViewById<TextView>(R.id.card_quote).apply {
            text = title
            setTextColor(COLOR_ERR)
            visibility = View.VISIBLE
        }
        v.findViewById<TextView>(R.id.card_advice).apply {
            text = body
            setTextColor(COLOR_TEXT)
            visibility = View.VISIBLE
        }

        if (!cardVisible) showCard() else v.post { positionCardNearBall() }
    }

    // ── 前台通知 ───────────────────────────────────────────

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notif_action_stop), stop
                ).build()
            )
            .setOngoing(true)

        val notif: Notification = builder.build()
        lastNotification = notif
        promoteToForeground(notif, withCapture = false)
    }

    /**
     * 告警通道。
     *
     * 与前台服务那条「悬浮窗运行中」通道**刻意分开**：
     * 常驻通知用了 `IMPORTANCE_MIN`（安静、不打扰，这是对的），
     * 但告警必须能弹出来。通道的重要性一旦建好就改不了，
     * 所以只能建两条 —— 这也是为什么不能用同一个 ID 换通道。
     */
    private fun ensureAlertChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            ALERT_CHANNEL_ID,
            getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.alert_channel_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 把服务提升/更新为前台。
     *
     * ⚠️ `withCapture = true` 时必须**先**以 `mediaProjection` 类型启动前台服务，
     * **再**调用 `getMediaProjection()` —— Android 14+ 对顺序有硬性要求，反了直接抛异常。
     * 本服务原本已是 `specialUse` 类型，这里再补上 `mediaProjection`。
     */
    private fun promoteToForeground(notif: Notification, withCapture: Boolean) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (withCapture) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIF_ID, notif, type)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && withCapture ->
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else ->
                startForeground(NOTIF_ID, notif)
        }
    }

    // ── 悬浮球 ─────────────────────────────────────────────

    private fun showBall() {
        if (ballView != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_ball, null)
        v.findViewById<TextView>(R.id.ball_text).text = ballGlyph()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val size = dp(BALL_DP)
            val defX = screenW - size - dp(12)
            // 默认高度：0.34 → 0.20 屏。御主 2026-09-23 反馈「位置有点低」，
            // 收到上方五分之一处 —— 单手拇指仍够得着，又不压住聊天气泡。
            val defY = (screenH * BALL_DEFAULT_Y_RATIO).toInt()
            x = if (settings.ballX >= 0) settings.ballX else defX
            y = if (settings.ballY >= 0) settings.ballY else defY
        }

        v.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var dragStartX = 0
            private var dragStartY = 0
            private var moved = false

            override fun onTouch(view: View, ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                        dragStartX = p.x; dragStartY = p.y
                        moved = false
                        // 按下即开始计长按：满 3 秒关闭悬浮窗。
                        // 计时期间球中心会显示倒数，让用户看得见进度
                        holdStartedAt = SystemClock.uptimeMillis()
                        main.postDelayed(closeRunnable, CLOSE_HOLD_MS)
                        startCountdown()
                        view.scaleX = 0.92f
                        view.scaleY = 0.92f
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val dist = max(abs(dx), abs(dy))

                        // ── 阈值 ①：够大才算「要拖动」──
                        // 锚点定在按下位置（不是当前位置），球才会跟手、不跳。
                        // 用 dp 而不是 px：14px 在 560dpi 上只有约 4dp，
                        // 手指还没动就被判成拖动了。
                        val slop = dp(TOUCH_SLOP_DP)
                        if (!moved && dist > slop) moved = true

                        // ── 阈值 ②：更大才算「不是长按」──
                        // ⚠️ 这是长按一直不好使的根因：
                        // 原来拖动与长按共用同一个（且极小的）阈值，
                        // 用户按住 3 秒期间手指总会漂移几个像素 →
                        // 一超过就 cancelHold() → 长按**永远按不到 3 秒**。
                        // 现在给长按更高的容忍度：小球在 44dp 以内的小幅移动
                        // 仍然算「按住了」，真正的拖动（几十 dp）才会取消它。
                        if (countdownRunnable != null && dist > dp(HOLD_CANCEL_SLOP_DP)) {
                            cancelHold()
                        }

                        if (moved) {
                            p.x = (dragStartX + dx).toInt()
                            p.y = (dragStartY + dy).toInt()
                            clampBall(p)
                            runCatching { wm.updateViewLayout(view, p) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.scaleX = 1f
                        view.scaleY = 1f
                        // 倒计时已被 closeByLongPress 清掉 → 说明确实按满了 3 秒
                        // （命名按实际语义写：这是「长按已经触发过」，不是「还在长按」）
                        val holdFired = countdownRunnable == null
                        cancelHold()
                        when {
                            moved -> {
                                settings.ballX = p.x
                                settings.ballY = p.y
                            }
                            // 长按已触发过就不再当点击，避免关窗的同时又弹出一张卡片
                            !holdFired -> toggleCard()
                        }
                        return true
                    }
                }
                return false
            }
        })

        runCatching { wm.addView(v, p) }
        ballView = v
        ballParams = p
    }

    private fun clampBall(p: WindowManager.LayoutParams) {
        val size = dp(BALL_DP)
        p.x = p.x.coerceIn(0, max(0, screenW - size))
        p.y = p.y.coerceIn(0, max(0, screenH - size - dp(24)))
    }

    // ── 截屏通道 ───────────────────────────────────────────
    //
    // 微信屏蔽了无障碍（真机实测：窗口根节点返回 null），读不到它的节点树。
    // 但「界面变了」的事件我们收得到 —— 于是把它当触发器，内容改由截图提供。

    /** 无障碍服务报告「微信界面变了」时，延迟一点点去抓帧 */
    private val wechatChangedListener: () -> Unit = {
        val sc = capture
        if (settings.captureEnabled && sc != null && sc.isReady) {
            // 延迟一下让新消息渲染完；同时去掉上一次还没执行的，避免连发时抓一堆
            main.removeCallbacks(captureRunnable)
            main.postDelayed(captureRunnable, CAPTURE_DELAY_MS)
        }
    }

    private val captureRunnable = Runnable { captureLatest() }

    private fun onCaptureGranted(resultCode: Int, data: Intent?) {
        if (data == null) {
            settings.captureEnabled = false
            toast(getString(R.string.capture_denied))
            return
        }
        val notif = lastNotification
        if (notif == null) {
            toast(getString(R.string.capture_failed))
            return
        }

        // ⚠️ 顺序不能反：先以 mediaProjection 类型把前台服务挂上，再建立投影。
        // Android 14+ 对这个顺序有硬性要求，反了直接抛 SecurityException。
        promoteToForeground(notif, withCapture = true)

        val sc = capture ?: ScreenCapture(this).also { capture = it }
        val ok = sc.start(resultCode, data)
        captureReady = ok
        settings.captureEnabled = ok
        if (ok) {
            // 记住「确实授权成功过」—— 服务重启后授权会失效，
            // 那时靠这个标记区分「需要重新授权」与「还没设置过」（见 checkCapturePermission）
            settings.captureEverGranted = true
            // 刚重新授权成功，之前那条失效提醒要撤掉
            captureAlerted = false
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(CAPTURE_ALERT_ID)
            }
        }
        toast(getString(if (ok) R.string.capture_ready else R.string.capture_failed))
        Logbook.i("截屏", if (ok) "截屏通道就绪" else "截屏通道建立失败")
    }

    /**
     * 抓一帧 → 裁出聊天区域 → 交给多模态模型分析。
     *
     * 之所以要"看屏幕"而不是读节点树：微信 8.0.78 屏蔽了无障碍，
     * 真机实测它的窗口连根节点都返回 null（详见任务书）。
     */
    private fun captureLatest() {
        if (busy) return
        val sc = capture ?: return
        if (!sc.isReady) return

        worker.execute {
            val screen = sc.grab()
            if (screen == null) {
                Logbook.e("截屏", "抓帧失败（超时或通道异常）")
                return@execute
            }

            val jpeg = ChatShot.toJpegBase64(screen)
            val w = screen.width
            val h = screen.height
            screen.recycle()

            if (jpeg == null) {
                Logbook.e("截屏", "裁剪或编码失败")
                return@execute
            }

            Logbook.i("截屏", "整屏 ${w}x$h → 裁剪编码后 ${jpeg.length / 1024} KB，交给多模态模型")

            // 回到主线程走统一的分析入口（herMessage 为空，内容由模型从图里读）
            main.post {
                analyze(SubtextState(herMessage = "", imageJpegBase64 = jpeg))
            }
        }
    }

    // ── 长按 3 秒关闭 ──────────────────────────────────────

    private fun ballText(): TextView? = ballView?.findViewById(R.id.ball_text)

    /** 长按倒计时的红环；不在倒计时时隐藏 */
    private fun ballRing(): View? = ballView?.findViewById(R.id.ball_ring)

    /**
     * 当前配置的悬浮球字符。
     *
     * 默认值是「她」（见 [BallStyles]）—— 曾经默认是字母 `J`，为的是呼应参考图里
     * 那个叫 Jev 的模型；后来御主要求界面上不出现第三方模型名，默认字形与文案都改掉了。
     */
    private fun ballGlyph(): String = BallStyles.glyphOf(settings.ballStyle)

    /**
     * 长按倒计时：最后几秒在球中心显示 3 → 2 → 1，并套上红环。
     *
     * 两个细节是踩过坑才加的：
     *   1. **数字要放大**（18sp → 24sp）。球的默认字形是 emoji，字号本来就偏小，
     *      沿用同一字号时数字很难一眼看清。
     *   2. **要加红环**。emoji 是彩色的，「文字转红」这个信号在它上面几乎无效；
     *      外圈红环则不受字形影响，一眼就知道「正在倒计时」。
     */
    private fun startCountdown() {
        val start = SystemClock.uptimeMillis()
        ballRing()?.visibility = View.VISIBLE
        val r = object : Runnable {
            override fun run() {
                val left = CLOSE_HOLD_MS - (SystemClock.uptimeMillis() - start)
                if (left <= 0) return
                val secs = ((left + 999) / 1000).toInt()
                if (secs <= COUNTDOWN_FROM_SECONDS) {
                    ballText()?.apply {
                        text = secs.toString()
                        setTextColor(COLOR_DANGER)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, COUNTDOWN_TEXT_SP)
                    }
                }
                main.postDelayed(this, 80)
            }
        }
        countdownRunnable = r
        main.post(r)
    }

    /** 松手 / 转为拖动：取消长按并还原球的样子 */
    private fun cancelHold() {
        main.removeCallbacks(closeRunnable)
        countdownRunnable?.let { main.removeCallbacks(it) }
        countdownRunnable = null
        ballRing()?.visibility = View.GONE
        ballText()?.apply {
            text = ballGlyph()
            setTextColor(COLOR_BRAND)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BALL_TEXT_SP)
        }
    }

    private fun closeByLongPress() {
        main.removeCallbacks(closeRunnable)
        countdownRunnable?.let { main.removeCallbacks(it) }
        countdownRunnable = null
        ballView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        toast(getString(R.string.toast_closed))
        settings.overlayRunning = false
        stopSelf()
    }

    // ── 分析卡片 ───────────────────────────────────────────

    private fun ensureCard(): View {
        cardView?.let { return it }

        val v = LayoutInflater.from(this).inflate(R.layout.overlay_card, null)
        // 宽度：原来固定 300dp，在 1264px(≈450dp) 的机器上占了三分之二屏，
        // 挡视野挡得厉害。收到 264dp，并给两侧各留 12dp 边距。
        val cardWidth = min(dp(CARD_DP), screenW - dp(24))
        val p = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(80)
        }

        v.findViewById<View>(R.id.card_close).setOnClickListener { hideCard() }
        // 正文限高：原来 0.52 屏，现在 0.40 屏 —— 卡片总高因此显著下降。
        // 超出的部分仍可滚动，信息不会丢，只是不再一次性怼满整屏。
        v.findViewById<com.saywhat.app.view.MaxHeightScrollView>(R.id.card_scroll).maxHeightDp =
            (screenH * CARD_MAX_HEIGHT_RATIO / resources.displayMetrics.density).toInt()

        attachHeaderDrag(v.findViewById(R.id.card_header_row), p, v)

        runCatching { wm.addView(v, p) }
        cardView = v
        cardParams = p
        return v
    }

    // ══════════════════════════════════════════════════════════
    //  简略模式：输入法弹起时，把整张卡片收成一条「最佳回复」
    // ══════════════════════════════════════════════════════════
    //
    // 场景：用户正在打字。这时整张卡片是纯干扰 —— 他要的是「我该发什么」，
    // 而不是概率分布。所以键盘一弹就把卡片收成一行大字，键盘收起再还原。
    //
    // 为什么用「独立窗口」而不是在卡片内改布局：
    // 卡片的显隐由用户点击控制，而简略模式由键盘触发，两者是**正交**的状态。
    // 用同一个窗口既保存用户意图又保存键盘状态，改起来必然打架。

    /** 简略条（一行最佳回复）。懒加载，不用时不创建窗口 */
    private var compactView: View? = null
    private var compactParams: WindowManager.LayoutParams? = null

    /** 输入法是否弹起 —— 由无障碍服务通过 [ChatBus] 通知 */
    @Volatile
    private var imeVisible = false

    /** 键盘状态变化的回调 */
    private val imeListener: (Boolean) -> Unit = { visible ->
        main.post { onImeChanged(visible) }
    }

    private fun onImeChanged(visible: Boolean) {
        imeVisible = visible
        if (!running) return
        if (visible) showCompact() else hideCompact()
    }

    private fun ensureCompact(): View {
        compactView?.let { return it }

        val v = LayoutInflater.from(this).inflate(R.layout.overlay_compact, null)
        val width = min(dp(COMPACT_DP), screenW - dp(24))
        val p = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        v.setOnClickListener { copyReplyFromCompact() }
        runCatching { wm.addView(v, p) }
        compactView = v
        compactParams = p
        return v
    }

    /**
     * 显示简略条。
     *
     * 没有可用回复时不显示 —— 与其弹一个空条占地方，不如什么都不显示。
     * 用户的键盘已经占了下半屏，再放一条空的只会更烦。
     */
    private fun showCompact() {
        val reply = lastReply
        if (reply.isNullOrBlank()) {
            hideCompact()
            return
        }
        val v = ensureCompact()
        v.findViewById<TextView>(R.id.compact_reply).text = reply
        v.visibility = View.VISIBLE
        // 整张卡片收起来，把屏幕让给键盘
        hideCard()
        v.post { positionCompactNearBall() }
    }

    private fun hideCompact() {
        compactView?.visibility = View.GONE
    }

    private fun copyReplyFromCompact() {
        val reply = lastReply ?: return
        copyReply(reply)
    }

    private fun positionCompactNearBall() {
        val v = compactView ?: return
        val p = compactParams ?: return
        val b = ballParams ?: return
        val cw = v.width
        if (cw == 0) return
        var y = b.y + dp(BALL_DP) + dp(10)
        // 简略条别压到键盘上（键盘大约占下半屏）
        val keyboardTop = (screenH * (1f - COMPACT_KEYBOARD_GUARD)).toInt()
        if (y > keyboardTop) y = keyboardTop - dp(56)
        p.x = (b.x + dp(BALL_DP) - cw).coerceIn(dp(8), max(dp(8), screenW - cw - dp(8)))
        p.y = y.coerceAtLeast(dp(8))
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 键盘弹起过程中，分析结果回来了也要同步刷新简略条 */
    private fun refreshCompactIfVisible() {
        if (!imeVisible) return
        val reply = lastReply
        if (reply.isNullOrBlank()) { hideCompact(); return }
        compactView?.findViewById<TextView>(R.id.compact_reply)?.text = reply
        showCompact()
    }

    private fun attachHeaderDrag(handle: View, p: WindowManager.LayoutParams, card: View) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(view: View, ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                        startX = p.x; startY = p.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = (startX + (ev.rawX - downX)).toInt()
                        p.y = (startY + (ev.rawY - downY)).toInt()
                        p.x = p.x.coerceIn(0, max(0, screenW - card.width))
                        p.y = p.y.coerceIn(0, max(0, screenH - card.height))
                        runCatching { wm.updateViewLayout(card, p) }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    private fun showCard() {
        val v = ensureCard()
        // 简略条与整张卡片互斥：键盘弹着的时候不该两个都在屏幕上
        hideCompact()
        v.visibility = View.VISIBLE
        if (!cardVisible) {
            cardVisible = true
            v.post { positionCardNearBall() }
        }
    }

    private fun hideCard() {
        cardVisible = false
        cardView?.visibility = View.GONE
    }

    private fun toggleCard() {
        if (cardVisible) {
            hideCard()
            return
        }
        // 正在打字时点球，用户要的是「最佳回复」而不是整张卡片 ——
        // 这时整张卡片会压住键盘上方的输入框，反而不如简略条好用。
        if (imeVisible && !lastReply.isNullOrBlank()) {
            showCompact()
            return
        }
        showCard()
        val state = ChatBus.lastState
        when {
            // 上次分析失败了 —— 重新展开时自动重试（原来长按重试的手势被长按关闭占用了）
            lastFailed && currentQuote.isNotEmpty() ->
                analyze(state ?: SubtextState(herMessage = currentQuote))
            // 还什么都没分析过 —— 拿最新的消息跑一次
            currentQuote.isEmpty() && state != null -> analyze(state)
            // 一次都没跑过、手上也没有可分析的消息 —— 显示空状态。
            // 旧版本这里什么都不画，用户点开气泡看到一张空卡片，完全不知道下一步该干嘛。
            currentQuote.isEmpty() && !settings.hasAnalyzedOnce -> renderEmpty()
        }
    }

    /**
     * 空状态：还没有任何消息可分析时，告诉用户「接下来会发生什么」。
     *
     * 只在「从未成功分析过」时出现。已经用过的人点开看到这堆说明会觉得啰嗦，
     * 而且他多半只是想收起卡片而已。
     */
    private fun renderEmpty() {
        val v = ensureCard()
        resetCardBody(v)
        alertOnCard = false
        lastFailed = false

        v.findViewById<TextView>(R.id.card_status).text = getString(R.string.card_empty_status)
        v.findViewById<TextView>(R.id.card_advice).apply {
            text = getString(R.string.card_empty)
            setTextColor(COLOR_TEXT)
            visibility = View.VISIBLE
        }
        if (!cardVisible) showCard() else v.post { positionCardNearBall() }
    }

    private fun positionCardNearBall() {
        val card = cardView ?: return
        val p = cardParams ?: return
        val b = ballParams ?: return
        val cw = card.width
        val ch = card.height
        if (cw == 0 || ch == 0) return

        val ballSize = dp(BALL_DP)
        var y = b.y - ch - dp(10)
        if (y < dp(40)) y = b.y + ballSize + dp(10)

        p.x = (b.x + ballSize - cw).coerceIn(dp(8), max(dp(8), screenW - cw - dp(8)))
        p.y = y.coerceIn(dp(40), max(dp(40), screenH - ch - dp(40)))
        runCatching { wm.updateViewLayout(card, p) }
    }

    // ── 分析流程 ───────────────────────────────────────────

    private fun analyze(state: SubtextState) {
        if (busy) return
        busy = true
        lastFailed = false
        currentQuote = state.herMessage
        main.removeCallbacks(autoHideRunnable)

        showCard()
        renderLoading(state.herMessage)
        Logbook.i("悬浮窗", "开始分析：「${state.herMessage.take(30)}」")

        worker.execute {
            val engine = EngineHub.create(settings)
            val result = engine.analyze(state)
            main.post {
                busy = false
                when (result) {
                    is EngineResult.Ok -> {
                        renderCard(result.card)
                        // 记一笔「跑通过一次」，主界面的新手引导据此自动收起来
                        settings.analyzedAt = System.currentTimeMillis()
                        updateBallBadge(result.card.danger ?: 1)
                        Logbook.i(
                            "悬浮窗",
                            "卡片已渲染 · 情境=${result.card.scene} 危险=${result.card.danger}"
                        )
                    }
                    is EngineResult.Fail -> {
                        renderError(result.message)
                        Logbook.e("悬浮窗", "分析失败：${result.message}")
                    }
                }
            }
        }
    }

    /** 只在卡片上重置「结构性」部分，避免每次渲染漏掉某个字段 */
    private fun resetCardBody(v: View) {
        v.findViewById<TextView>(R.id.card_scene).visibility = View.GONE
        v.findViewById<TextView>(R.id.card_question).visibility = View.GONE
        v.findViewById<LinearLayout>(R.id.card_question_rows).visibility = View.GONE
        v.findViewById<TextView>(R.id.card_intent_title).visibility = View.GONE
        v.findViewById<LinearLayout>(R.id.card_intent_rows).visibility = View.GONE
        v.findViewById<TextView>(R.id.card_action_title).visibility = View.GONE
        v.findViewById<LinearLayout>(R.id.card_action_rows).visibility = View.GONE
        v.findViewById<LinearLayout>(R.id.card_danger_block).visibility = View.GONE
        v.findViewById<TextView>(R.id.card_advice).visibility = View.GONE
        v.findViewById<LinearLayout>(R.id.card_reply_block).visibility = View.GONE
    }

    private fun renderLoading(quote: String) {
        val v = cardView ?: return
        alertOnCard = false
        resetCardBody(v)
        v.findViewById<TextView>(R.id.card_status).text = getString(R.string.card_analyzing)
        v.findViewById<TextView>(R.id.card_quote).apply {
            text = quoteLine(quote)
            visibility = View.VISIBLE
        }
        v.findViewById<TextView>(R.id.card_advice).apply {
            text = "…"
            setTextColor(COLOR_TEXT)
            visibility = View.VISIBLE
        }
        v.post { positionCardNearBall() }
    }

    private fun renderError(message: String) {
        lastFailed = true
        val v = cardView ?: return
        alertOnCard = false
        resetCardBody(v)
        v.findViewById<TextView>(R.id.card_status).text = getString(R.string.card_error)
        v.findViewById<TextView>(R.id.card_quote).apply {
            text = quoteLine(currentQuote)
            visibility = View.VISIBLE
        }
        v.findViewById<TextView>(R.id.card_advice).apply {
            text = message
            setTextColor(COLOR_ERR)
            visibility = View.VISIBLE
        }
        v.post { positionCardNearBall() }
    }

    private fun renderCard(card: CardData) {
        val v = cardView ?: return
        alertOnCard = false
        resetCardBody(v)

        // 状态行：引擎 · 耗时
        v.findViewById<TextView>(R.id.card_status).text = buildString {
            if (card.engine.isNotEmpty()) append(card.engine)
            if (card.elapsedMs > 0) {
                if (isNotEmpty()) append(" · ")
                append(String.format(Locale.US, "%.1fs", card.elapsedMs / 1000.0))
            }
        }

        // 情境标签
        v.findViewById<TextView>(R.id.card_scene).apply {
            text = card.scene
            setTextColor(sceneColor(card.scene))
            visibility = View.VISIBLE
        }

        // 被分析的原句。
        // 看图模式下我们自己读不到文字，用模型从截图里认出来的那句
        val quoted = card.readMessage?.takeIf { it.isNotBlank() } ?: currentQuote
        if (quoted.isNotBlank()) currentQuote = quoted
        v.findViewById<TextView>(R.id.card_quote).apply {
            if (quoted.isBlank()) {
                visibility = View.GONE
            } else {
                text = quoteLine(quoted)
                visibility = View.VISIBLE
            }
        }

        // 是 / 否
        val literal = card.literal
        val q = v.findViewById<TextView>(R.id.card_question)
        val qRows = v.findViewById<LinearLayout>(R.id.card_question_rows)
        if (literal == null) {
            q.visibility = View.GONE
            qRows.visibility = View.GONE
        } else {
            q.text = literal.question ?: "这句话是字面意思吗？"
            q.visibility = View.VISIBLE
            qRows.removeAllViews()
            qRows.addView(scoreRow("是", literal.yes))
            qRows.addView(scoreRow("不是", literal.no))
            qRows.visibility = View.VISIBLE
        }

        bindSection(
            v.findViewById(R.id.card_intent_title),
            v.findViewById(R.id.card_intent_rows),
            "当前真实意图", card.intents
        )
        bindSection(
            v.findViewById(R.id.card_action_title),
            v.findViewById(R.id.card_action_rows),
            "最佳动作", card.actions
        )

        // 危险等级
        card.danger?.let { level ->
            val block = v.findViewById<LinearLayout>(R.id.card_danger_block)
            val value = v.findViewById<TextView>(R.id.card_danger_value)
            val bar = v.findViewById<FrameLayout>(R.id.card_danger_bar)
            val fill = v.findViewById<View>(R.id.card_danger_fill)
            val lv = level.coerceIn(1, 10)
            val color = dangerColor(lv)

            value.text = "$lv / 10"
            value.setTextColor(color)
            fill.background.setTint(color)
            block.visibility = View.VISIBLE

            // 按实测宽度算像素宽，等布局完成后再设。
            // 之前用 layout_weight 会算错（父容器从 GONE 转 VISIBLE 的瞬间测量不可靠）
            bar.post {
                val w = bar.width
                if (w > 0) {
                    fill.layoutParams = FrameLayout.LayoutParams(
                        (w * lv / 10f).toInt(),
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
            }
        }

        // 一句话建议
        v.findViewById<TextView>(R.id.card_advice).apply {
            if (card.advice.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                text = card.advice
                setTextColor(COLOR_TEXT)
                visibility = View.VISIBLE
            }
        }

        // 最佳回复：一条能直接复制发出去的完整句子，点一下即复制
        val replyBlock = v.findViewById<LinearLayout>(R.id.card_reply_block)
        // 兜底截断：prompt 已要求 ≤10 字，但模型未必每次都听话。
        // 客户端再切一刀，保证卡片底部那一行永远不换行、不撑高卡片。
        val reply = card.reply?.trim().orEmpty().let {
            if (it.length > REPLY_MAX_CHARS) it.take(REPLY_MAX_CHARS) else it
        }
        if (reply.isEmpty()) {
            replyBlock.visibility = View.GONE
        } else {
            v.findViewById<TextView>(R.id.card_reply).text = reply
            replyBlock.setOnClickListener { copyReply(reply) }
            replyBlock.visibility = View.VISIBLE
        }
        // 记下来给简略模式用（键盘此刻若正弹着，顺手把简略条刷新一下）
        lastReply = reply.takeIf { it.isNotEmpty() }
        refreshCompactIfVisible()

        v.post { positionCardNearBall() }

        // 日常闲聊：给个「无事」的轻提示后自动收起，避免用户以为工具坏了
        if (card.isIdle) {
            autoHideAt = System.currentTimeMillis() + IDLE_AUTO_HIDE_MS
            main.postDelayed(autoHideRunnable, IDLE_AUTO_HIDE_MS)
        }
    }

    /** 把最佳回复复制到剪贴板 —— 用户切回微信长按粘贴就能发 */
    private fun copyReply(text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", text))
            toast(getString(R.string.card_reply_copied))
            Logbook.i("悬浮窗", "最佳回复已复制：${text.take(24)}")
        }.onFailure {
            toast("复制失败")
        }
    }

    private val autoHideRunnable = Runnable {
        if (System.currentTimeMillis() >= autoHideAt) hideCard()
    }

    private fun bindSection(
        title: TextView,
        container: LinearLayout,
        titleText: String,
        scores: List<ProbItem>
    ) {
        container.removeAllViews()
        if (scores.isEmpty()) {
            title.visibility = View.GONE
            container.visibility = View.GONE
            return
        }
        title.text = titleText
        title.visibility = View.VISIBLE
        for (s in scores) container.addView(scoreRow(s.name, s.p))
        container.visibility = View.VISIBLE
    }

    /** 生成一行「- 想确认你在不在乎她: 72%」 */
    private fun scoreRow(label: String, p: Double): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_score, null, false)
        row.findViewById<TextView>(R.id.row_label).text = label
        row.findViewById<TextView>(R.id.row_percent).text = pct(p)
        return row
    }

    private fun pct(p: Double): String = "${(p.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

    private fun quoteLine(quote: String): String =
        if (quote.length > 60) "「${quote.take(60)}…」" else "「$quote」"

    private fun updateBallBadge(level: Int) {
        val badge = ballView?.findViewById<TextView>(R.id.ball_badge) ?: return
        if (settings.dangerAlert && level >= DANGER_ALERT_LEVEL) {
            badge.text = level.toString()
            badge.setTextColor(dangerColor(level))
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun sceneColor(scene: String): Int = when (scene) {
        Scene.PROBE -> COLOR_SCENE_PROBE
        Scene.CONFRONT -> COLOR_SCENE_CONFRONT
        Scene.DEMAND -> COLOR_SCENE_DEMAND
        Scene.DEESCALATE -> COLOR_SCENE_DEESCALATE
        else -> COLOR_SCENE_IDLE
    }

    private fun dangerColor(level: Int): Int = when {
        level >= 9 -> 0xFFEF4444.toInt()
        level >= 7 -> 0xFFF97316.toInt()
        level >= 4 -> 0xFFEAB308.toInt()
        else -> 0xFF22C55E.toInt()
    }

    // ── 工具 ───────────────────────────────────────────────

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refreshScreenSize() {
        try {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } catch (t: Throwable) {
            screenW = resources.displayMetrics.widthPixels
            screenH = resources.displayMetrics.heightPixels
        }
    }

    private fun toast(msg: String) {
        main.post {
            runCatching {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ACTION_START = "com.saywhat.app.action.START"
        const val ACTION_STOP = "com.saywhat.app.action.STOP"
        const val ACTION_ANALYZE = "com.saywhat.app.action.ANALYZE"
        const val EXTRA_TEXT = "extra_text"

        /** 用户同意截屏授权后，由 CapturePermissionActivity 把结果转交过来 */
        const val ACTION_GRANT_CAPTURE = "com.saywhat.app.action.GRANT_CAPTURE"
        const val ACTION_CAPTURE_DENIED = "com.saywhat.app.action.CAPTURE_DENIED"

        /**
         * 「截屏授权已失效」时用户点「去重新授权」触发的动作。
         *
         * 由本服务自己拉起 [com.saywhat.app.capture.CapturePermissionActivity]，
         * 这样从通知直接进来的用户不必先回主界面找第 ④ 步。
         */
        const val ACTION_REQUEST_CAPTURE = "com.saywhat.app.action.REQUEST_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        /** 收到「微信界面变了」之后等多久再截 —— 给新消息留渲染时间 */
        private const val CAPTURE_DELAY_MS = 350L

        private const val CHANNEL_ID = "saywhat_overlay"
        private const val NOTIF_ID = 1001

        /** 断线告警走独立通道与独立 ID —— 见 ensureAlertChannel 的说明 */
        private const val ALERT_CHANNEL_ID = "saywhat_alert"
        private const val ALERT_NOTIF_ID = 1002

        /** 「截屏授权已失效」的提醒用另一个 ID，两条告警可以并存 */
        private const val CAPTURE_ALERT_ID = 1003

        /** 服务启动后给读屏多久的宽限期才断言「没连上」，避免开机瞬间误报 */
        private const val ACCESS_GRACE_MS = 3_000L

        /**
         * 判定「用户要拖动球」的阈值（dp）。
         *
         * ⚠️ 单位是 **dp 不是 px**。旧代码用的是裸的 `14f`，
         * 在 560dpi 的机器上只相当于约 4dp —— 手指几乎没动就被判成拖动了。
         */
        private const val TOUCH_SLOP_DP = 8

        /**
         * 拖动超过这个距离才**取消长按**（dp）。
         *
         * 必须显著大于 [TOUCH_SLOP_DP]：球本身就才 44dp，
         * 正常拖动的手部位移通常也只有几十 dp。两个手势若共用一个阈值，
         * 长按必然被拖动吃掉 —— 这就是「长按关闭不好使」的根因。
         */
        private const val HOLD_CANCEL_SLOP_DP = 20

        private const val DANGER_ALERT_LEVEL = 7
        private const val IDLE_AUTO_HIDE_MS = 2_500L

        /** 悬浮球直径（dp）。从 52 缩到 44，同时保留足够的长按命中面积 */
        private const val BALL_DP = 44

        /** 卡片宽度（dp）。原 300，挡视野，收到 264 */
        private const val CARD_DP = 196

        /** 简略条宽度（dp）。比卡片窄一点，让聊天内容在两侧多透出来一些 */
        private const val COMPACT_DP = 200

        /**
         * 最佳回复的兜底字数上限（御主 2026-09-23：「尽量不超过十个字」）。
         *
         * prompt 里已经这么要求了，这里是**第二道保险**：
         * 模型不听话时至少不会把卡片底部撑成两行。
         * ⚠️ 改这个值时要同步改 Prompt 里那条规则，两边不一致会出现「显示被砍掉」的怪现象。
         */
        private const val REPLY_MAX_CHARS = 10

        /**
         * 简略条的下边界保护：屏幕底部这么多比例留给键盘，不往上压。
         * 键盘高度各家不同（0.35~0.45 屏），取 0.45 偏保守。
         */
        private const val COMPACT_KEYBOARD_GUARD = 0.45f

        /** 卡片正文最多占屏幕高度的比例。原 0.52，收到 0.40 */
        private const val CARD_MAX_HEIGHT_RATIO = 0.26f

        /**
         * 悬浮球默认纵向位置（屏幕高度的比例）。
         * 原 0.34，御主反馈偏低 → 0.20。
         * 注意这只影响**默认值**：用户一旦拖动过，后续都用他拖到的位置。
         */
        private const val BALL_DEFAULT_Y_RATIO = 0.20f

        /** 球上字形的字号 */
        private const val BALL_TEXT_SP = 18f

        /** 长按倒计时数字的字号 —— 比字形大一档，emoji 旁边也能一眼看清 */
        private const val COUNTDOWN_TEXT_SP = 24f

        /** 长按多久关闭悬浮窗 */
        private const val CLOSE_HOLD_MS = 3_000L

        /** 剩最后几秒时开始显示倒数数字 */
        private const val COUNTDOWN_FROM_SECONDS = 3

        private const val COLOR_BRAND = 0xFF22D3EE.toInt()
        private const val COLOR_DANGER = 0xFFEF4444.toInt()

        private const val COLOR_TEXT = 0xFFE8EAED.toInt()
        private const val COLOR_ERR = 0xFFF87171.toInt()
        private const val COLOR_SCENE_PROBE = 0xFF7DD3FC.toInt()
        private const val COLOR_SCENE_CONFRONT = 0xFFFCA5A5.toInt()
        private const val COLOR_SCENE_DEMAND = 0xFFFCD34D.toInt()
        private const val COLOR_SCENE_DEESCALATE = 0xFF86EFAC.toInt()
        private const val COLOR_SCENE_IDLE = 0xFF9CA3AF.toInt()

        /** 供 UI 查询运行状态 */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * 截屏通道是否**当前真的可用**（供 UI 查询）。
         *
         * 注意与 `Settings.captureEnabled` 的区别：
         * 后者是「用户是否允许过」，前者是「此刻投影是否活着」。
         * Android 14+ 起 MediaProjection 的授权是一次性的 ——
         * 服务一重启就得重新授权，所以界面上要显示前者才不会骗人。
         */
        @Volatile
        var captureReady: Boolean = false
            private set
    }
}
