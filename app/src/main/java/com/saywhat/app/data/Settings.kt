package com.saywhat.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本机配置。全部存在 SharedPreferences，不上传、不备份。
 * （Manifest 里已关掉 allowBackup，避免配置被云备份带走。）
 */
class Settings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("saywhat", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(K_BASE, DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = sp.edit().putString(K_BASE, v.trim()).apply()

    var apiKey: String
        get() = sp.getString(K_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_KEY, v.trim()).apply()

    var model: String
        get() = sp.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString(K_MODEL, v.trim()).apply()

    /** 用户选的服务商预设 id，用于填写页记住选择 */
    var providerId: String
        get() = sp.getString(K_PROVIDER, "deepseek") ?: "deepseek"
        set(v) = sp.edit().putString(K_PROVIDER, v.trim()).apply()

    /**
     * 用哪个分析引擎。默认 [DEFAULT_ENGINE]。
     * 取值见 com.saywhat.app.engine.EngineHub.available
     * （这里写字符串字面量而不是引用 LlmEngine.ID，避免 data 包反向依赖 engine 包）
     */
    var engineId: String
        get() = sp.getString(K_ENGINE, DEFAULT_ENGINE) ?: DEFAULT_ENGINE
        set(v) = sp.edit().putString(K_ENGINE, v.trim()).apply()

    /** 收到新消息自动分析；关掉后只能点悬浮球手动触发 */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /** 危险等级达到该阈值时，悬浮球显示角标 */
    var dangerAlert: Boolean
        get() = sp.getBoolean(K_DANGER, true)
        set(v) = sp.edit().putBoolean(K_DANGER, v).apply()

    /** 短于该字数的消息忽略（「嗯」「哦」「好」这类不值得调一次模型） */
    var minLength: Int
        get() = sp.getInt(K_MINLEN, 2)
        set(v) = sp.edit().putInt(K_MINLEN, v.coerceIn(1, 50)).apply()

    /**
     * 截屏通道是否已授权。
     * 微信屏蔽了无障碍（真机实测：连窗口根节点都返回 null），
     * 所以读消息只能靠截屏 —— 这个开关决定用不用那条通道。
     */
    var captureEnabled: Boolean
        get() = sp.getBoolean(K_CAPTURE, false)
        set(v) = sp.edit().putBoolean(K_CAPTURE, v).apply()

    /**
     * 最近一次分析成功的时刻（毫秒），0 = 从未成功过。
     *
     * 用来判断「新手引导还需不需要显示」：五步都配好了、但一次都没跑出过卡片时，
     * 主界面要显式告诉用户「现在去微信，让对方发一句话」。
     * 一旦成功过一次，这条引导就该消失 —— 引导永远赖着不走也是种干扰。
     *
     * 存时间戳而不是布尔值，是为了将来能顺手做「上次分析是多久以前」这类提示，
     * 不必再加一个键（SharedPreferences 的键越少越好维护）。
     */
    var analyzedAt: Long
        get() = sp.getLong(K_ANALYZED_AT, 0L)
        set(v) = sp.edit().putLong(K_ANALYZED_AT, v).apply()

    val hasAnalyzedOnce: Boolean
        get() = analyzedAt > 0L

    /** 悬浮球上显示的字符 id，取值见 [BallStyles.ALL] */
    var ballStyle: String
        get() = sp.getString(K_BALL_STYLE, BallStyles.ALL.first().first)
            ?: BallStyles.ALL.first().first
        set(v) = sp.edit().putString(K_BALL_STYLE, v.trim()).apply()

    /** 悬浮球位置，-1 表示还没拖动过 */
    var ballX: Int
        get() = sp.getInt(K_BX, -1)
        set(v) = sp.edit().putInt(K_BX, v).apply()

    var ballY: Int
        get() = sp.getInt(K_BY, -1)
        set(v) = sp.edit().putInt(K_BY, v).apply()

    /** 悬浮窗是否处于运行状态（进程被杀后用它决定要不要恢复） */
    var overlayRunning: Boolean
        get() = sp.getBoolean(K_RUNNING, false)
        set(v) = sp.edit().putBoolean(K_RUNNING, v).apply()

    /**
     * 读屏**曾经**成功连上过。
     *
     * 只写一次、永不回退，用来区分两种「读屏没在跑」：
     *   - 从没连上过 → 用户还没去开启授权，属于「没配好」
     *   - 连上过又没了 → 被系统 / 手机管家掐了，属于「被掐了」，必须显眼提示
     *
     * `ChatBus.everConnectedThisSession` 只看得到当前进程，
     * 而 App 进程被杀、设备重启都会丢掉它，所以需要这个持久标记。
     */
    var accessEverConnected: Boolean
        get() = sp.getBoolean(K_ACCESS_EVER, false)
        set(v) = sp.edit().putBoolean(K_ACCESS_EVER, v).apply()

    /**
     * 截屏授权**曾经成功过**。
     *
     * ⚠️ 与 [captureEnabled] 的区别很关键：
     * Android 14+ 的 MediaProjection 授权是**一次性的** —— 悬浮窗一关就失效，
     * 所以 `OverlayService.captureReady`（此刻是否活着）会变回 false。
     * 但如果用户**曾经授权成功过**，那这次失效的含义是「需要重新授权」，
     * 而不是「他还没设置过」—— 前者要弹提醒，后者该去引导页。
     *
     * 只写一次、永不回退，用来区分这两种情况。
     */
    var captureEverGranted: Boolean
        get() = sp.getBoolean(K_CAPTURE_EVER, false)
        set(v) = sp.edit().putBoolean(K_CAPTURE_EVER, v).apply()

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * 把用户填的地址补全成真正的 chat/completions 端点。
     * 用户可能填 https://api.deepseek.com、.../v1、或者已经填到 .../chat/completions。
     */
    fun chatEndpoint(): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.isEmpty()) b = DEFAULT_BASE
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://api.deepseek.com"

        /**
         * DeepSeek V4.1 Flash（2026/09/10 发布）。
         * 官方说明：552B MoE、原生多模态视觉理解、费用低于同尺寸模型。
         * 原生多模态这点很重要 —— 「表情包识别」功能（P7）要靠它看懂裁剪出来的图。
         * 注：旧名 deepseek-v4-flash / deepseek-v4-flash-vision-exp 会被路由到它；
         * 若该名字在某个中转站不可用，在设置页改回 deepseek-chat 即可。
         */
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_ENGINE = "llm"

        private const val K_ENGINE = "engine_id"
        private const val K_PROVIDER = "provider_id"
        private const val K_BASE = "base_url"
        private const val K_KEY = "api_key"
        private const val K_MODEL = "model"
        private const val K_AUTO = "auto_analyze"
        private const val K_DANGER = "danger_alert"
        private const val K_MINLEN = "min_length"
        private const val K_BALL_STYLE = "ball_style"
        private const val K_CAPTURE = "capture_enabled"
        private const val K_BX = "ball_x"
        private const val K_BY = "ball_y"
        private const val K_RUNNING = "overlay_running"
        private const val K_ACCESS_EVER = "access_ever_connected"
        private const val K_CAPTURE_EVER = "capture_ever_granted"
        private const val K_ANALYZED_AT = "analyzed_at"
    }
}
