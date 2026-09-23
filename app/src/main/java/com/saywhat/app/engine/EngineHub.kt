package com.saywhat.app.engine

import com.saywhat.app.data.FeatureFlags
import com.saywhat.app.data.Settings

/**
 * 引擎工厂。上层只调这里，不直接 new 具体引擎。
 *
 * 将来接入第三个引擎（比如本地小模型、或其他决策模型），
 * 只需在这里加一项 —— UI 与悬浮窗一行都不用改。
 */
object EngineHub {

    /**
     * 全部已实现的引擎。
     * 是否对用户可见由 [FeatureFlags] 决定（当前只露出大模型一路）。
     */
    private val ALL: List<Pair<String, String>> = listOf(
        LlmEngine.ID to "DeepSeek",
        JevEngine.ID to "Jev / System One（等官方放量）"
    )

    /** 当前版本对用户可见的引擎 */
    val available: List<Pair<String, String>> = ALL.filter { (id, _) ->
        when (id) {
            JevEngine.ID -> FeatureFlags.JEV_ENGINE
            else -> true
        }
    }

    fun create(settings: Settings): SubtextEngine = when (settings.engineId) {
        JevEngine.ID -> JevEngine(settings)
        else -> LlmEngine(settings)
    }
}
