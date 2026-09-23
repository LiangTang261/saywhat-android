package com.saywhat.app.capture

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 把整屏截图处理成「能直接发给多模态模型」的一小张图。
 *
 * ═══ 为什么要裁 ═══
 * 全屏 1264x2780 直接发出去有三个问题：
 *   1. 图片 token 很贵，整屏比裁剪后贵好几倍
 *   2. 聊天区之外的内容（通知栏、其他界面）**不该发给任何服务器**
 *   3. 状态栏/标题栏/输入框对分析毫无用处，只会干扰模型
 *
 * ═══ 裁剪范围的取舍 ═══
 * 取屏幕纵向 45%~88% 这一段：
 *   - 上界 45%：标题栏、以及更早的历史消息都排除掉，只要最近的
 *   - 下界 88%：输入框和导航栏排除掉
 * 微信的新消息总是出现在这一段里。
 */
object ChatShot {

    private const val TOP_RATIO = 0.45f
    private const val BOTTOM_RATIO = 0.88f

    /** 缩放后的目标宽度 —— 800px 足够认字，又能显著压小体积 */
    private const val TARGET_WIDTH = 800

    private const val JPEG_QUALITY = 80

    /**
     * 裁出聊天区域、缩放、转成 JPEG 的 Base64。
     * 返回 null 表示处理失败（调用方记日志即可，不要崩）。
     */
    fun toJpegBase64(screen: Bitmap): String? {
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            cropped = cropChatArea(screen)
            scaled = scaleDown(cropped)

            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        } finally {
            // 只回收我们自己新建的图，screen 由调用方负责
            if (scaled != null && scaled !== cropped) runCatching { scaled.recycle() }
            if (cropped != null && cropped !== screen && cropped !== scaled) {
                runCatching { cropped.recycle() }
            }
        }
    }

    private fun cropChatArea(screen: Bitmap): Bitmap {
        val top = (screen.height * TOP_RATIO).toInt().coerceIn(0, screen.height - 1)
        val bottom = (screen.height * BOTTOM_RATIO).toInt().coerceIn(top + 1, screen.height)
        val h = bottom - top
        return Bitmap.createBitmap(screen, 0, top, screen.width, h)
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        if (src.width <= TARGET_WIDTH) return src
        val ratio = TARGET_WIDTH.toFloat() / src.width
        val w = TARGET_WIDTH
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
