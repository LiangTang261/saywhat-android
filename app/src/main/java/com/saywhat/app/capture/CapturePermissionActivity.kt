package com.saywhat.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.saywhat.app.OverlayService

/**
 * 申请截屏授权的透明页面。
 *
 * 系统必须在 Activity 里弹出截屏授权框、并把结果回传给 Activity，
 * 所以这里开一个不可见的页面专门承接回调，用户不会看到界面闪动。
 *
 * 拿到的授权结果（resultCode + data）转交给 [OverlayService] ——
 * 因为建立 MediaProjection 必须在「已以 mediaProjection 类型启动的前台服务」里做。
 */
class CapturePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("系统截屏授权只能走 onActivityResult，没有对应的新 API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) {
            finish()
            return
        }

        val intent = Intent(this, OverlayService::class.java).apply {
            if (resultCode == RESULT_OK && data != null) {
                action = OverlayService.ACTION_GRANT_CAPTURE
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            } else {
                action = OverlayService.ACTION_CAPTURE_DENIED
            }
        }

        runCatching { startForegroundService(intent) }
        finish()
    }

    companion object {
        private const val REQ_CAPTURE = 9001
    }
}
