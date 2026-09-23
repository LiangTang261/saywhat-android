package com.saywhat.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.saywhat.app.read.ChatBus
import com.saywhat.app.util.Logbook
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志页。
 *
 * 分享出去的文本**开头带一段设备信息**（机型 / 安卓版本 / 分辨率 / 读屏状态）——
 * 这些恰恰是排查读屏问题时最先要问的东西，让用户手打太费劲，直接带上。
 */
class LogActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tvCount: TextView

    /** 上一次渲染的内容，没变就不重绘，免得每秒闪一下还把滚动位置顶掉 */
    private var lastRendered = ""

    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        tvCount = findViewById(R.id.tv_count)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.btn_share).setOnClickListener { share() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            Logbook.clear()
            lastRendered = ""
            render()
            toast(getString(R.string.log_clear))
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    // ── 渲染 ───────────────────────────────────────────────

    private fun render() {
        val body = Logbook.dump()
        if (body == lastRendered) return
        lastRendered = body

        logText.text = body
        tvCount.text = "${Logbook.count()} 行"

        // 自动滚到底部（最新的一条在最后）
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 拼出发送的完整文本。
     * 开头这段设备信息是刻意加的 —— 排查读屏问题首先就要问这些。
     */
    private fun composeReport(): String {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return buildString {
            append("===== SayWhat · 运行日志 =====\n")
            append("时间      ").append(stamp).append('\n')
            append("机型      ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("安卓版本  ").append(Build.VERSION.RELEASE)
            append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
            append("屏幕      ").append(dm.widthPixels).append('x').append(dm.heightPixels)
            append(" @").append(dm.densityDpi).append("dpi\n")
            append("读屏服务  ")
            append(if (ChatBus.accessibilityConnected) "运行中" else "未连接").append('\n')
            append("==============================\n\n")
            append(Logbook.dump())
        }
    }

    private fun copyAll() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("saywhat log", composeReport()))
        toast(getString(R.string.log_copied))
    }

    private fun share() {
        val name = runCatching { writeLogFile() }.getOrNull()
        if (name == null) {
            // 写文件失败就退回纯文本，别让用户点了没反应
            shareAsText()
            return
        }

        val uri = Uri.parse("content://$packageName.logfiles/$name")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_share_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
        }.onFailure { shareAsText() }
    }

    /**
     * 把日志写进私有目录的一个 txt 文件。
     *
     * 为什么要走文件：日志动辄几百行，当文本粘贴进微信又长又容易截断；
     * 发文件则清爽得多，我们那边也能原样拿到。
     */
    private fun writeLogFile(): String {
        // 顺手清掉上次的日志，避免越积越多
        runCatching {
            filesDir.listFiles { f -> f.name.startsWith("log-") && f.name.endsWith(".txt") }
                ?.forEach { it.delete() }
        }
        val name = "log-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                ".txt"
        File(filesDir, name).writeText(composeReport(), Charsets.UTF_8)
        return name
    }

    private fun shareAsText() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_share_title))
            putExtra(Intent.EXTRA_TEXT, composeReport())
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
        }.onFailure { copyAll() }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
