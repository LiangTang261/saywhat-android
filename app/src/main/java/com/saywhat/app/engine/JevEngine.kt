package com.saywhat.app.engine

import com.saywhat.app.data.Settings

/**
 * 升级项插槽：TypeSafe 的 System One（Jev）。
 *
 * ═══ 现状（2026-09-22）═══
 * Jev 接口用量过大，官方暂停发放新资格，御主申请未通过，手上的 key 用不了。
 * 因此本引擎**挂起**，主线由 [LlmEngine] 承担。
 *
 * ═══ 为什么保留这个类 ═══
 * 1. 参考图的三种卡片（是/否、多选、打分）恰好就是 System One 的三个原语
 *    （noul / choice / score），接上它是「最还原」的做法；
 * 2. 它证明了 [SubtextEngine] 这层抽象是必要的 —— 下面这段注释里的调用形态
 *    与 OpenAI 协议完全不同，但上层 UI 不需要知道。
 *
 * ═══ 将来接进来要改什么 ═══
 * 接口：POST https://api.typesafe.ai/v1/systemone
 * 鉴权：Authorization: Bearer ts_...
 * 模型：jev-latest
 * 请求体：{ model, state, questions }   ← 注意不是 messages，需要独立的请求构建
 *
 * 关键差异：System One **不会自己生成问题**，必须把 questions（含 criteria）喂给它，
 * 它只返回概率。所以它必须两段式：
 *   第 1 段用 choice 问 scene；
 *   第 2 段按 scene 取对应问题集再问一次。
 * 这正是「两段式是 Jev 的约束、不是 LLM 的约束」的含义 ——
 * LlmEngine 因此不必背这个包袱。
 *
 * 完整设计（5 情境 × 问题集 JSON）见配套设计文档《Jev 接入 · 两段式设计》，
 * 以及可运行验证脚本 `jev-two-stage.mjs`（均为本地开发文档，不随本仓库分发）。
 */
class JevEngine(private val settings: Settings) : SubtextEngine {

    override val id: String get() = ID
    override val displayName: String get() = "Jev / System One（未接入）"

    override fun analyze(state: SubtextState): EngineResult =
        EngineResult.Fail("Jev 引擎尚未接入：TypeSafe 接口过载，暂未放量。请在设置里切回大模型引擎。")

    companion object {
        const val ID = "jev"
        const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
        const val MODEL = "jev-latest"
    }
}
