package com.saywhat.app.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 极简文件提供者 —— 只为「把日志当文件分享出去」这一件事存在。
 *
 * ═══ 为什么不用 AndroidX 的 FileProvider ═══
 * 本项目坚持**零第三方依赖**（换来 Gradle 不会卡依赖解析、APK 不到 1MB）。
 * 而 FileProvider 要做的无非是「把私有目录里的文件以 content:// 形式借给别的 App 读」，
 * 这点事自己写几十行就够，不值得为它引一整个 androidx.core。
 *
 * ═══ 安全约束（重要）═══
 * 只允许读取 filesDir 下、文件名形如 `log-*.txt` 的文件，
 * 且文件名里不允许出现路径分隔符或 `..` ——
 * 否则别人构造一个 content URI 就能读到我们私有目录里的任意文件
 * （比如以后可能存着的配置）。
 */
class LogFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("没有 context")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("URI 里没有文件名")

        if (!isAllowed(name)) throw FileNotFoundException("不允许读取：$name")

        val f = File(ctx.filesDir, name)
        if (!f.exists()) throw FileNotFoundException("文件不存在：$name")

        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** 只放行我们自己的日志文件 */
    private fun isAllowed(name: String): Boolean =
        name.startsWith("log-") &&
                name.endsWith(".txt") &&
                !name.contains('/') &&
                !name.contains('\\') &&
                !name.contains("..")

    /**
     * ═══ 这个方法不能返回 null（踩过坑）═══
     *
     * 很多 App —— **尤其是微信** —— 在真正读取 content:// 之前，
     * 会先 `query()` 一次拿 `DISPLAY_NAME` 与 `SIZE`。
     *
     * 最初这里直接 `return null`，结果就是：分享面板里能选中微信，
     * 但对方拿到的是个"读不出大小"的 URI，**发送直接失败**。
     */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!isAllowed(name)) return null

        val f = File(ctx.filesDir, name)
        if (!f.exists()) return null

        val cols = if (projection.isNullOrEmpty()) {
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        } else {
            projection.map { it }.toTypedArray()
        }

        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()
        for (c in cols) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> row.add(name)
                OpenableColumns.SIZE -> row.add(f.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
