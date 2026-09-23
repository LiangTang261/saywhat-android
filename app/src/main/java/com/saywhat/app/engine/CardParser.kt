package com.saywhat.app.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 把模型返回的文本解析成 [CardData]。
 *
 * ═══ 为什么写得这么啰嗦 ═══
 * 模型一定会跑偏：夹带解释、包 markdown 代码块、字段用中文名、
 * 概率写成 "72%" 字符串、各项之和不是 1。任何一种都不该让整张卡片消失。
 * 这里按「四层降级 + 字段校验」逐步兜，最差也要给出可读的失败原因。
 */
object CardParser {

    sealed class Result {
        data class Ok(val card: CardData) : Result()
        data class Fail(val reason: String) : Result()
    }

    /** 概率项数组最多取几项，防止模型给 12 条把卡片撑爆 */
    private const val MAX_ITEMS = 6

    /** advice 最长保留多少字 */
    private const val MAX_ADVICE = 80

    fun parse(raw: String, engineName: String, elapsedMs: Long): Result {
        val json = extractJson(raw)
            ?: return Result.Fail("模型没按 JSON 格式返回，再点一次试试")

        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return Result.Fail("返回的 JSON 不完整，再点一次试试")
        }

        return try {
            Result.Ok(build(o, engineName, elapsedMs))
        } catch (t: Throwable) {
            Result.Fail("返回内容解析失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ── 分层降级取 JSON ────────────────────────────────────
    //
    // 计划书列了四层。实际实现里第 1、3 层会自然合流：
    // 若原文本身就是干净 JSON，括号配对会原样返回它；
    // 若原文夹在说明文字里，配对会把它抠出来。所以只需两步。

    private fun extractJson(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        // 第 2 层：剥掉 ```json ... ``` 围栏
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            if (firstNl > 0) s = s.substring(firstNl + 1)
            val fence = s.lastIndexOf("```")
            if (fence >= 0) s = s.substring(0, fence)
            s = s.trim()
        }

        // 第 1 + 3 层：从第一个 { 起做括号配对
        return trimToObject(s)
    }

    /** 从字符串里抠出第一个配平的 JSON 对象 */
    private fun trimToObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    // ── 字段构建与校验 ──────────────────────────────────────

    private fun build(o: JSONObject, engineName: String, elapsedMs: Long): CardData {
        val scene = Scene.normalize(optString(o, "scene", "情境", "场景"))

        // 日常闲聊：强制清空，避免模型多嘴给出毫无意义的选项
        if (scene == Scene.IDLE) {
            return CardData(
                scene = scene,
                literal = null,
                intents = emptyList(),
                actions = emptyList(),
                danger = 1,
                advice = optString(o, "advice", "建议") ?: "正常聊天即可",
                engine = engineName,
                elapsedMs = elapsedMs
            )
        }

        val literalObj = o.optJSONObject("literal")
            ?: o.optJSONObject("是/否")
            ?: o.optJSONObject("noul")

        val literal = if (literalObj != null) {
            val yes = toProb(literalObj.opt("yes") ?: literalObj.opt("是") ?: literalObj.opt("noul"))
            if (yes == null) null
            else ProbPair(
                question = optString(literalObj, "question", "问句", "问题"),
                yes = yes
            )
        } else {
            // 容忍模型把 literal 直接写成一个数字
            toProb(o.opt("literal"))?.let { ProbPair(question = null, yes = it) }
        }

        val intents = parseItems(o, "intents", "意图", "intent")
        val actions = parseItems(o, "actions", "动作", "action", "最佳动作")

        val danger = (toInt(o.opt("danger"))
            ?: toInt(o.opt("危险等级"))
            ?: toInt(o.opt("danger_level")))?.coerceIn(1, 10)

        val advice = optString(o, "advice", "建议", "suggestion")?.take(MAX_ADVICE)

        return CardData(
            scene = scene,
            literal = literal,
            intents = intents,
            actions = actions,
            danger = danger,
            advice = advice,
            reply = optString(o, "reply", "回复", "最佳回复", "best_reply"),
            engine = engineName,
            elapsedMs = elapsedMs,
            // 看图模式下模型会把它从截图里认出的原句放在这个字段
            readMessage = optString(o, "her_message", "对方消息", "原句", "message")
        )
    }

    private fun parseItems(o: JSONObject, vararg keys: String): List<ProbItem> {
        var arr: JSONArray? = null
        for (k in keys) {
            val a = o.optJSONArray(k)
            if (a != null) {
                arr = a
                break
            }
        }
        val a = arr ?: return emptyList()

        val out = ArrayList<ProbItem>(a.length())
        for (i in 0 until a.length()) {
            when (val item = a.opt(i)) {
                is JSONObject -> {
                    val name = optString(item, "name", "label", "text", "名称", "标签", "内容")
                        ?: continue
                    val p = toProb(item.opt("p") ?: item.opt("percent") ?: item.opt("prob") ?: item.opt("概率"))
                        ?: continue
                    out.add(ProbItem(name, p))
                }
                is String -> {
                    // 容忍 "搜索聊天记录: 91%" 这种字符串写法
                    val m = Regex("""^(.*?)[\s:：]+(\d{1,3}(?:\.\d+)?)\s*%?$""").find(item.trim())
                    if (m != null) {
                        val p = toProb(m.groupValues[2]) ?: continue
                        out.add(ProbItem(m.groupValues[1].trim(), p))
                    }
                }
            }
        }

        // 按概率降序，截断，再归一
        val sorted = out.sortedByDescending { it.p }.take(MAX_ITEMS)
        return normalizeSum(sorted)
    }

    /**
     * 把一组概率归一到和为 1。
     * 只在偏差超过 2% 时才动手 —— 模型给的数字本身是自洽的，别改花。
     */
    private fun normalizeSum(items: List<ProbItem>): List<ProbItem> {
        if (items.isEmpty()) return items
        val sum = items.sumOf { it.p }
        if (sum <= 0.0) {
            val even = 1.0 / items.size
            return items.map { it.copy(p = even) }
        }
        if (abs(sum - 1.0) < 0.02) return items
        return items.map { it.copy(p = (it.p / sum).coerceIn(0.0, 1.0)) }
    }

    /**
     * 概率归一化：既接受 0.72，也接受 72（百分数）和 "72%"。
     * 判据是「大于 1 就当成百分数」，因为真实概率不会超过 1。
     */
    private fun toProb(v: Any?): Double? {
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().removeSuffix("%").trim().toDoubleOrNull()
            else -> null
        } ?: return null
        val normalized = if (d > 1.0) d / 100.0 else d
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun toInt(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> Regex("""\d{1,2}""").find(v)?.value?.toIntOrNull()
        else -> null
    }

    private fun optString(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val v = o.optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }
}
