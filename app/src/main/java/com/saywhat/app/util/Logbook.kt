package com.saywhat.app.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 内的环形缓冲日志。
 *
 * ═══ 为什么需要它 ═══
 * 真机上一旦读屏不工作，用户看不懂 logcat，我们也拿不到任何信息，
 * 只能靠用户口述「没反应」—— 这样排查等于盲猜。
 *
 * 把关键判断点记下来并显示在 App 里，用户点一下「分享」就能发出来，
 * 排查效率完全不同。
 *
 * ═══ 使用约定 ═══
 * - **绝不记录 API Key、聊天记录的完整历史**（只记判据与摘要）
 * - 有噪声的地方（无障碍事件是每秒几十次）要在调用处自己收敛，
 *   只记「状态有变化」的那一次，否则缓冲区几秒就被冲光了
 */
object Logbook {

    private const val TAG = "saywhat"
    private const val MAX_LINES = 800

    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun i(tag: String, msg: String) {
        push("$tag", msg, null)
    }

    @Synchronized
    fun e(tag: String, msg: String, t: Throwable? = null) {
        push(tag, "⚠ $msg", t)
    }

    /**
     * 需要注意、但不算故障的一条。
     *
     * 与 [e] 的区别只在视觉上：用 ⚠ 还是 ·。真机上排查时，
     * 一眼能分出「这是异常」和「这只是个提醒」很重要。
     */
    @Synchronized
    fun w(tag: String, msg: String) {
        push(tag, "· $msg", null)
    }

    private fun push(tag: String, msg: String, t: Throwable?) {
        val suffix = t?.let { " （${it.javaClass.simpleName}: ${it.message}）" } ?: ""
        val line = "${fmt.format(Date())} [$tag] $msg$suffix"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        // 同时打到 logcat，接 USB 调试时两条路都能看
        Log.i(TAG, line)
    }

    @Synchronized
    fun dump(): String =
        if (buffer.isEmpty()) "（暂无日志）" else buffer.joinToString("\n")

    @Synchronized
    fun clear() {
        buffer.clear()
        push("日志", "已清空", null)
    }

    @Synchronized
    fun count(): Int = buffer.size
}
