package com.saywhat.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.saywhat.app.util.Logbook
import java.nio.ByteBuffer

/**
 * 截屏通道 —— 因为微信屏蔽了无障碍，只能靠"看屏幕"来读消息。
 *
 * ═══ Android 14+ 的硬性顺序要求（踩了就抛异常）═══
 *   1. 用户授权（系统弹窗，必须由 Activity 承接）
 *   2. **先**把前台服务以 `mediaProjection` 类型启动
 *   3. **再**调用 `getMediaProjection(resultCode, data)`
 * 顺序反了会直接抛 SecurityException。
 *
 * ═══ 为什么保持一个常驻的 VirtualDisplay ═══
 * 每次截屏都新建/销毁 VirtualDisplay 要 200~500ms，
 * 而我们要在「微信来消息」的瞬间抓帧，慢了就抓不到。
 * 所以授权后建一个常驻的，抓帧时只取最新一帧。
 */
class ScreenCapture(private val context: Context) {

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    var width = 0
        private set
    var height = 0
        private set
    private var density = 0

    val isReady: Boolean get() = projection != null && reader != null

    /**
     * 用系统返回的授权结果建立截屏通道。
     *
     * ⚠️ 调用方必须**已经**把前台服务以 mediaProjection 类型启动过了。
     */
    fun start(resultCode: Int, data: Intent): Boolean {
        if (isReady) return true
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            val p = mpm.getMediaProjection(resultCode, data)
            if (p == null) {
                Logbook.e("截屏", "getMediaProjection 返回 null（授权可能已失效）")
                return false
            }
            projection = p

            // Android 14+ 要求注册回调，否则 createVirtualDisplay 会失败。
            // 回调对象要留着，注销时 unregisterCallback 不接受 null
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Logbook.i("截屏", "系统收回了截屏权限")
                    release()
                }
            }
            projectionCallback = cb
            p.registerCallback(cb, Handler(Looper.getMainLooper()))

            if (!createSurface()) return false

            Logbook.i("截屏", "通道已建立，屏幕 ${width}x$height @${density}dpi")
            true
        } catch (t: Throwable) {
            Logbook.e("截屏", "建立截屏通道失败", t)
            release()
            false
        }
    }

    fun stop() {
        projection?.stop()
        release()
    }

    private fun release() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        display = null
        reader = null
        projectionCallback = null
        projection = null
    }

    private fun createSurface(): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        width = dm.widthPixels
        height = dm.heightPixels
        density = dm.densityDpi

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r

        val d = projection?.createVirtualDisplay(
            "saywhat-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, Handler(Looper.getMainLooper())
        )
        display = d
        if (d == null) {
            Logbook.e("截屏", "createVirtualDisplay 返回 null")
            return false
        }
        return true
    }

    /**
     * 抓一帧。返回 null 表示这轮没抓到（超时或通道没建好）。
     *
     * 只做一次 `acquireLatestImage` 往往拿到的是旧帧，
     * 所以先丢掉一帧再取，确保拿到的是"当前"画面。
     */
    fun grab(timeoutMs: Long = 600L): Bitmap? {
        val r = reader ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        var bitmap: Bitmap? = null

        while (System.currentTimeMillis() < deadline) {
            val image = try {
                r.acquireLatestImage()
            } catch (t: Throwable) {
                null
            }
            if (image == null) {
                Thread.sleep(20)
                continue
            }
            try {
                bitmap = toBitmap(image)
            } finally {
                runCatching { image.close() }
            }
            break
        }
        return bitmap
    }

    /**
     * ImageReader 给的 buffer 每行可能有 padding，直接 createBitmap 会花屏。
     * 标准做法：按 rowStride 建一张更宽的图，读完之后再裁掉右侧多余部分。
     */
    private fun toBitmap(image: Image): Bitmap? {
        val plane: Image.Plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val raw = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        raw.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            raw
        } else {
            Bitmap.createBitmap(raw, 0, 0, width, height).also {
                if (it !== raw) raw.recycle()
            }
        }
    }

    companion object {
        /** 看看这台设备有没有截屏能力（有些 ROM 会禁掉） */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }
}
