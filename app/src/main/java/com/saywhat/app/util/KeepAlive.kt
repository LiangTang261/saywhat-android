package com.saywhat.app.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 保活引导。
 *
 * ═══ 为什么必须有这个 ═══
 * 本 App 的整条链路都挂在「无障碍服务活着」这一件事上：
 *
 *     无障碍事件（微信界面变了）→ 触发截屏 → 分析 → 悬浮卡片
 *
 * 它一旦被系统回收，**截屏触发器就彻底失效**，而悬浮球还在屏幕上飘着 ——
 * 用户看到的是「球在、点它有反应、但微信来消息毫无动静」，
 * 只会以为 App 坏了。所以「让它活着」不是优化项，是功能的一部分。
 *
 * 各家 ROM 会从三个地方掐它：
 *   1. **电池优化 / 省电模式** —— 系统冻结后台进程（原生安卓也有，最普遍）
 *   2. **自启动白名单** —— 国产 ROM 特有，不在白名单里的 App 重启后不给起
 *   3. **无障碍「保持开启」倒计时** —— 一加 / OPPO 手机管家，弹窗 5 秒不点就自动关
 *      （第 3 条由引导页文案提醒，代码拦不住）
 *
 * 本文件只负责**检测状态 + 把用户送到对应系统页面**。
 * 各家 ROM 的设置页类名互不相同，所以每条路径都带兜底 ——
 * 兜底打不开也不能让用户卡住，宁可退到「应用详情页」让他自己找。
 */
object KeepAlive {

    // ══════════════════════════════════════════════════════════
    //  第 1 项：电池优化白名单
    // ══════════════════════════════════════════════════════════

    /**
     * 是否已加入「不优化电池」白名单。
     *
     * 用 `isIgnoringBatteryOptimizations` 查询，而不是直接看有没有权限，
     * 因为它是**逐 App 的开关**，只有系统知道当前值。
     * 任何异常都当作「未加入」——宁可多提示一次，也不要骗用户说没事。
     */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
    } catch (_: Throwable) {
        false
    }

    /**
     * 弹出系统原生的「是否允许后台运行」对话框。
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 会直接弹一个系统对话框，
     * 点一下就好 —— 比把用户丢进设置里翻菜单友好得多。
     *
     * ⚠️ 这条 Intent 需要 Manifest 里声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
     * 若 ROM 不支持（部分国产 ROM 直接拦掉），退回电池优化列表页；
     * 再不行退回应用详情页。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        val pkg = ctx.packageName

        if (tryStart(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))) return
        if (tryStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetails(ctx)
    }

    /** 进入系统「电池优化」列表页，供用户自己找到本 App 设为「不优化」 */
    fun openBatteryOptimizationSettings(ctx: Context) {
        if (tryStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetails(ctx)
    }

    // ══════════════════════════════════════════════════════════
    //  第 2 项：自启动 / 后台运行白名单
    // ══════════════════════════════════════════════════════════

    /**
     * 本机 ROM 的「自启动管理」页怎么走。
     *
     * 这些类名都是各家 ROM 的私有页面，**没有官方 API**，
     * 只能按厂商列举。列在这里的每一条都用 `ComponentName` 显式指定，
     * 打不开就走 [openAppDetails] —— 绝不会把用户卡在空白页。
     *
     * 已知现状（2026-09）：一加 / OPPO（ColorOS）实测存在该页面；
     * 其余厂商的类名来自公开的常见做法，**未逐台验证**，
     * 失败时兜底到应用详情页，功能不受影响，只是需要用户自己多找一步。
     */
    private val AUTO_START_ROUTES: List<Pair<String, String>> = listOf(
        // 一加 / OPPO · ColorOS —— 御主的真机就是这一系，优先
        "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
        "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        // 小米 · MIUI / HyperOS
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // 华为 / 荣耀 · EMUI / MagicOS
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // vivo · OriginOS / FuntouchOS
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // 三星 · One UI
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // 魅族 · Flyme
        "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        // 联想 · ZUI
        "com.lenovo.security" to "com.lenovo.security.purebackground.PureBackgroundActivity"
    )

    /**
     * 打开自启动管理页；全都打不开就退到应用详情页。
     *
     * @return 跳去的到底是自启动页（true）还是兜底的应用详情页（false）——
     *         调用方可以据此决定提示文案，别让用户以为点错了
     */
    fun openAutoStartSettings(ctx: Context): Boolean {
        for ((pkg, cls) in AUTO_START_ROUTES) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(ctx, intent)) return true
        }
        openAppDetails(ctx)
        return false
    }

    // ══════════════════════════════════════════════════════════
    //  第 3 项：通知权限（用户关了通知 = 掉线告警也传不出去）
    // ══════════════════════════════════════════════════════════

    /**
     * 通知是否被允许。
     *
     * 这一项关系到**掉线告警能不能送达**：
     * 前台服务的常驻通知被关掉后，App 静默失效时用户将一无所知。
     * Android 13+ 需要在运行时申请 POST_NOTIFICATIONS，用户也可能事后手动关掉。
     */
    fun areNotificationsEnabled(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.areNotificationsEnabled() ?: true
    } catch (_: Throwable) {
        true
    }

    // ══════════════════════════════════════════════════════════
    //  公共兜底
    // ══════════════════════════════════════════════════════════

    /** 应用详情页 —— 所有 ROM 都有，最后的兜底 */
    fun openAppDetails(ctx: Context) {
        tryStart(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        )
    }

    /**
     * 试一下这条 Intent 能不能打开。
     *
     * 必须先 `resolveActivity` 再 `startActivity` —— 直接 start 抛的
     * `ActivityNotFoundException` 在某些 ROM 上是异步的，抓不住，
     * 会变成一次闪退。
     */
    private fun tryStart(ctx: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (ctx.packageManager.resolveActivity(intent, 0) == null) return false
            ctx.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 当前厂商名，用于给用户显示「你的手机是 XX，请去 XX 里设置」 */
    fun romLabel(): String = when {
        Build.MANUFACTURER.isNullOrBlank() -> "本机"
        else -> Build.MANUFACTURER
    }
}
