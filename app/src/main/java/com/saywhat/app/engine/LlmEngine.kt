package com.saywhat.app.engine

import android.util.Log
import com.saywhat.app.data.Settings
import com.saywhat.app.util.Logbook
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 主线引擎：任意 OpenAI 兼容接口（默认 DeepSeek）。
 *
 * 一段式调用 —— 路由情境和作答在同一请求里完成，理由见 [Prompt] 的类注释。
 *
 * ═══ 三个踩过的坑（都是对着官方文档修掉的）═══
 *
 * **坑 1：思考模式默认打开，而且 effort 默认 high。**
 * 官方文档：「思考模式默认打开，且 effort 默认为 high」。
 * 它会在正文前先吐一大段思维链，对我们这种「对方话音刚落就要出结果」的场景是三输：
 *   - 延迟翻倍；
 *   - 思维链把 max_tokens 吃光，导致 `content` 返回空；
 *   - **思考模式下 temperature 不生效**，而我们要的恰恰是稳定概率。
 * 所以请求里显式关掉它：`"thinking": {"type": "disabled"}`。
 *
 * **坑 2：JSON Output 有概率返回空 content。**
 * 官方文档原话：「在使用 JSON Output 功能时，API 有概率会返回空的 content」。
 * 所以拿到空 content 时不能当成致命错误，要重试。
 *
 * **坑 3：不能拿 reasoning_content 当正文。**
 * 早期版本在 content 为空时会回退去读 reasoning_content —— 那是模型的思考过程，
 * 不是 JSON。结果把「返回为空」这个清晰错误，伪装成了「格式不对」这个误导性错误。
 */
class LlmEngine(private val settings: Settings) : SubtextEngine {

    override val id: String get() = ID
    override val displayName: String get() = "大模型（OpenAI 兼容）"

    override fun analyze(state: SubtextState): EngineResult {
        if (!settings.isConfigured) {
            return EngineResult.Fail("还没填 API Key，先在 App 第三步里填一下")
        }

        val started = System.currentTimeMillis()
        val label = "LLM · ${settings.model}"
        Logbook.i("引擎", "请求 ${settings.chatEndpoint()} · model=${settings.model}")

        // ── 第一步：挑一个服务端能接受的请求体 ──
        // 三档降级，因为部分中转站不认识 thinking / response_format，会直接 400。
        var body: String? = null
        var lastErr = "接口不接受我们的请求格式"

        for (variant in buildVariants(settings.model, state)) {
            when (val resp = post(variant)) {
                is Resp.Success -> {
                    body = variant
                }
                is Resp.HttpError -> {
                    lastErr = describeHttpError(resp.code, resp.body)
                    Logbook.e("引擎", "HTTP ${resp.code}：${resp.body.take(120)}")
                    if (resp.code == 400 || resp.code == 422) {
                        Log.w(TAG, "请求体被拒（$resp.code），降级重试：${resp.body.take(200)}")
                        continue
                    }
                    return EngineResult.Fail(lastErr)
                }
                is Resp.IoError -> {
                    // 网络抖动：同一个请求体重试一次
                    when (val retry = post(variant)) {
                        is Resp.Success -> body = variant
                        is Resp.IoError -> return EngineResult.Fail(retry.message)
                        is Resp.HttpError -> {
                            lastErr = describeHttpError(retry.code, retry.body)
                            if (retry.code == 400 || retry.code == 422) continue
                            return EngineResult.Fail(lastErr)
                        }
                    }
                }
            }
            if (body != null) break
        }

        val chosen = body ?: return EngineResult.Fail(lastErr)

        // ── 第二步：拿内容。空 content 和 JSON 跑偏都可重试 ──
        repeat(MAX_CONTENT_ATTEMPTS) { attempt ->
            when (val resp = post(chosen)) {
                is Resp.IoError -> return EngineResult.Fail(resp.message)
                is Resp.HttpError -> return EngineResult.Fail(describeHttpError(resp.code, resp.body))
                is Resp.Success -> {
                    val content = extractContent(resp.body)

                    if (content.isNullOrBlank()) {
                        // 官方承认的已知问题，不是我们的锅，重试
                        Logbook.e("引擎", "第 ${attempt + 1} 次拿到空 content（原始返回 ${resp.body.length} 字节）")
                        Log.w(TAG, "第 ${attempt + 1} 次拿到空 content。原始返回前 400 字：${resp.body.take(400)}")
                        lastErr = "模型这次返回了空内容，再点一次试试"
                    } else {
                        val elapsed = System.currentTimeMillis() - started
                        Logbook.i("引擎", "正文 ${content.length} 字，开始解析")
                        when (val parsed = CardParser.parse(content, label, elapsed)) {
                            is CardParser.Result.Ok -> {
                                Logbook.i(
                                    "引擎",
                                    "解析成功 · 情境=${parsed.card.scene} " +
                                            "危险=${parsed.card.danger} 用时=${elapsed}ms"
                                )
                                return EngineResult.Ok(parsed.card)
                            }
                            is CardParser.Result.Fail -> {
                                Logbook.e("引擎", "JSON 解析失败：${parsed.reason}")
                                Logbook.i("引擎", "模型原文前 200 字：${content.take(200)}")
                                Log.w(TAG, "JSON 解析失败：${parsed.reason}。模型原文前 400 字：${content.take(400)}")
                                lastErr = parsed.reason
                            }
                        }
                    }
                }
            }
        }

        return EngineResult.Fail(lastErr)
    }

    // ── 请求构建 ───────────────────────────────────────────

    private sealed class Resp {
        data class Success(val body: String) : Resp()
        data class HttpError(val code: Int, val body: String) : Resp()
        data class IoError(val message: String) : Resp()
    }

    /**
     * 请求体降级梯度。
     * 首选关掉思考模式 + 要求 JSON 输出；
     * 中转站不认哪个字段，就去掉哪个。
     */
    private fun buildVariants(model: String, state: SubtextState): List<String> = listOf(
        buildBody(model, state, jsonMode = true, noThinking = true),
        buildBody(model, state, jsonMode = false, noThinking = true),
        buildBody(model, state, jsonMode = false, noThinking = false)
    )

    private fun buildBody(
        model: String,
        state: SubtextState,
        jsonMode: Boolean,
        noThinking: Boolean
    ): String {
        // 带图时走多模态通道：system prompt 换成"先读出对方那句话再分析"的版本
        val vision = !state.imageJpegBase64.isNullOrBlank()

        val userMessage = if (vision) {
            JSONObject().put("role", "user").put(
                "content",
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", Prompt.userVision()))
                    put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put(
                                "url",
                                "data:image/jpeg;base64,${state.imageJpegBase64}"
                            )
                        )
                    )
                }
            )
        } else {
            JSONObject().put("role", "user").put("content", Prompt.user(state))
        }

        val messages = JSONArray().apply {
            put(
                JSONObject().put("role", "system")
                    .put("content", if (vision) Prompt.SYSTEM_VISION else Prompt.SYSTEM)
            )
            put(userMessage)
        }

        val root = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", if (vision) MAX_TOKENS_VISION else MAX_TOKENS)
            .put("stream", false)
            // 关掉思考模式后 temperature 才生效；0.2 是为了要稳定概率，不是要创造力
            .put("temperature", 0.2)

        if (noThinking) {
            root.put("thinking", JSONObject().put("type", "disabled"))
        }
        if (jsonMode) {
            root.put("response_format", JSONObject().put("type", "json_object"))
        }
        return root.toString()
    }

    private fun post(body: String): Resp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(settings.chatEndpoint()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { readAll(it) } ?: ""

            if (code in 200..299) Resp.Success(text) else Resp.HttpError(code, text)
        } catch (e: Exception) {
            Resp.IoError(friendlyIoMessage(e))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readAll(input: InputStream): String =
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            var line = reader.readLine()
            while (line != null) {
                sb.append(line).append('\n')
                line = reader.readLine()
            }
            sb.toString()
        }

    /**
     * 只取 `content`。
     * **刻意不回退读 `reasoning_content`** —— 那是模型的思考过程，不是我们要的 JSON
     * （见类注释「坑 3」）。拿不到就返回 null，交给上层重试并打出原始返回。
     */
    private fun extractContent(body: String): String? = try {
        val choices = JSONObject(body).optJSONArray("choices")
        if (choices == null || choices.length() == 0) null
        else choices.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")?.ifBlank { null }
    } catch (t: Throwable) {
        null
    }

    private fun describeHttpError(code: Int, body: String): String {
        val hint = try {
            JSONObject(body).optJSONObject("error")?.optString("message")?.take(140)
        } catch (t: Throwable) {
            null
        } ?: body.take(140)

        val prefix = when (code) {
            401, 403 -> "API Key 无效或没权限"
            402 -> "账户余额不足"
            404 -> "接口地址不对（拼出来的路径是 404）"
            429 -> "请求太频繁或额度用完了"
            in 500..599 -> "对方服务器出错"
            else -> "接口返回 $code"
        }
        return if (hint.isBlank()) prefix else "$prefix：$hint"
    }

    private fun friendlyIoMessage(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Unable to resolve host", true) -> "域名解析失败，检查网络或接口地址"
            m.contains("timed out", true) || m.contains("timeout", true) -> "请求超时，网络慢或对方没响应"
            m.contains("Connection refused", true) -> "连接被拒绝，检查接口地址和端口"
            m.contains("SSL", true) || m.contains("Certificate", true) -> "HTTPS 证书校验失败"
            else -> "网络请求失败：$m"
        }
    }

    companion object {
        const val ID = "llm"
        private const val TAG = "LlmEngine"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 25_000

        /**
         * 2000 而不是 800。
         * 关掉思考模式后正文很短（JSON 约 300 token），但各家兼容实现的
         * max_tokens 语义不完全一致，留足余量避免 JSON 被截断。
         */
        private const val MAX_TOKENS = 2000

        /** 看图模式要额外"读"一遍图，正文更长，留足余量 */
        private const val MAX_TOKENS_VISION = 3000

        /** 空 content / JSON 跑偏的额外尝试次数 */
        private const val MAX_CONTENT_ATTEMPTS = 2
    }
}
