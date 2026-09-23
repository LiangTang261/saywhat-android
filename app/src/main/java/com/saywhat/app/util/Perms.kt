package com.saywhat.app.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.saywhat.app.JevAccessibilityService
import com.saywhat.app.read.ChatBus

/**
 * 三项权限的查询与跳转。
 *
 * 「傻瓜式」的关键就在这个文件：用户不需要知道
 * 「悬浮窗权限在哪个菜单的第几层」，点一下按钮直达对应系统页面。
 */
object Perms {

    fun canDrawOverlays(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

    /**
     * 读屏权限是否已开。
     *
     * 先用 ChatBus 里的实时标志（最准，服务自己报的），
     * 再退回系统设置里的字符串（覆盖 App 刚启动、服务还没连上的那一瞬间）。
     */
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        if (ChatBus.accessibilityConnected) return true

        try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val list = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (list != null) {
                for (info in list) {
                    val si = info.resolveInfo?.serviceInfo ?: continue
                    if (si.packageName == ctx.packageName) return true
                }
            }
        } catch (_: Throwable) {
            // 某些 ROM 会限制查询，忽略，走下面的兜底
        }

        return try {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = ComponentName(ctx, JevAccessibilityService::class.java).flattenToString()
            val short = ComponentName(ctx, JevAccessibilityService::class.java).flattenToShortString()
            enabled.split(':').any { it.equals(target, true) || it.equals(short, true) }
        } catch (_: Throwable) {
            false
        }
    }

    fun openOverlaySettings(ctx: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    fun openAccessibilitySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
