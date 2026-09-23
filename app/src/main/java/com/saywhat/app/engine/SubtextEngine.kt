package com.saywhat.app.engine

/**
 * 分析引擎的统一契约。
 *
 * ═══ 为什么要有这一层 ═══
 * 参考图里的「Jev」是 TypeSafe 的 System One 决策模型，它原生吐概率、
 * 但不会自己生成问题（必须把 questions 喂给它），所以它必须两段式调用。
 * 而普通 LLM 能一次同时完成「判断情境」和「按情境给选项」。
 *
 * 两种引擎的**调用形态本来就不同**，这个接口恰好容纳差异：
 *   SubtextEngine
 *   ├── LlmEngine  ← 主线（DeepSeek / 任意 OpenAI 兼容），一段式
 *   └── JevEngine  ← 插槽（等 TypeSafe 放量），两段式
 * 上层 UI 只认 [CardData]，对引擎无感。
 *
 * ═══ 为什么是阻塞式而不是 suspend ═══
 * 计划书里写的是 `suspend fun analyze(...)`。但 suspend 函数真要跑起来
 * 需要 kotlinx-coroutines 提供的调度与续体机制，引入它就会打破本项目
 * 「零第三方依赖」的约束（该约束换来：Gradle 不会卡依赖解析、APK 不到 1MB）。
 *
 * 而调用方本来就跑在后台线程池上（OverlayService 的单线程 executor），
 * 阻塞式接口在语义上完全等价，且不引入任何依赖。故采用阻塞式。
 */
interface SubtextEngine {

    /** 稳定标识，存进 Settings 用 */
    val id: String

    /** 展示名，给设置页用 */
    val displayName: String

    /**
     * 同步分析。调用方负责放到后台线程，不要在主线程调用。
     */
    fun analyze(state: SubtextState): EngineResult
}

/** 一次分析的输入：她刚发的消息 + 上下文 */
data class SubtextState(
    /** 对方刚发来的那条 */
    val herMessage: String,
    /** 我上一条回复（可能为空） */
    val myLastReply: String? = null,
    /** 最近的对话，按时间正序，形如 ["我：……", "她：……"] */
    val recentHistory: List<String> = emptyList(),
    /**
     * 聊天区域的截图（JPEG → Base64）。
     *
     * 为什么需要它：微信 8.0.78 屏蔽了无障碍（真机实测连窗口根节点都返回 null），
     * 读不到文案，只能截屏让多模态模型自己"看"。
     * 非空时引擎会走**多模态**通道：让模型先从图里读出对方那句话，再分析。
     */
    val imageJpegBase64: String? = null
)

/** 「是 / 否」概率对 */
data class ProbPair(
    /**
     * 卡片上显示的那句问话。参考图里每一张卡片的问句都是**针对该条消息生成**的
     * （如「她真的在问"你记不记得"吗？」），不是固定文案，所以这里存原文。
     * 计划书的 ProbPair 只有 yes，这是必要的扩展。
     */
    val question: String? = null,
    /** 答案为「是」的概率，0.0 ~ 1.0 */
    val yes: Double
) {
    /** 「不是」的概率，由互补得出，避免模型给出两个不自洽的数字 */
    val no: Double get() = (1.0 - yes).coerceIn(0.0, 1.0)
}

/** 多选项：一个名字 + 它的概率 */
data class ProbItem(val name: String, val p: Double)

/**
 * 一张卡片的全部内容 —— UI 只依赖它，与引擎无关。
 *
 * 相比计划书的 CardData，这里多了两个字段（都是为了对齐参考图）：
 * - [actions]：参考图里有「最佳动作」区块（`- 搜索聊天记录: 91%`），
 *   结构上与 intents 同为多选，但语义不同，不能合并。
 * - [ProbPair.question]：见上。
 */
data class CardData(
    /** 情境，取值见 [Scene] */
    val scene: String,
    /** 是/否概率；日常闲聊时为 null */
    val literal: ProbPair? = null,
    /** 真实意图候选 */
    val intents: List<ProbItem> = emptyList(),
    /** 行动建议候选 */
    val actions: List<ProbItem> = emptyList(),
    /** 危险等级 1~10；日常闲聊固定 1 */
    val danger: Int? = null,
    /** 一句话建议 */
    val advice: String? = null,
    /**
     * **可以直接发出去的回复原文**。
     *
     * 与 [advice] 的区别：advice 是"该怎么办"（如"先认错"），
     * 而这里是"照着发就行"的完整句子。用户要的往往是后者。
     */
    val reply: String? = null,
    /** 引擎给出的把握度（Jev 有，LLM 版通常为空） */
    val confidence: Double? = null,
    /** 由哪个引擎产出，显示在卡片角上 */
    val engine: String = "",
    val elapsedMs: Long = 0L,
    /**
     * 多模态模式下模型「看图读出来的原句」。
     * 微信读不到文字，卡片上引用哪句话只能靠模型从截图里认。
     */
    val readMessage: String? = null
) {
    /** 是否判定为无需分析的日常闲聊 */
    val isIdle: Boolean get() = scene == Scene.IDLE
}

/** 引擎返回值。失败也要是个明确的对象，不要用异常穿透到 UI。 */
sealed class EngineResult {
    data class Ok(val card: CardData) : EngineResult()
    data class Fail(val message: String) : EngineResult()
}

/** 五种情境。字符串是给模型看的，必须与 Prompt 里完全一致。 */
object Scene {
    const val PROBE = "试探态度"
    const val CONFRONT = "直接质问"
    const val DEMAND = "索求动作"
    const val DEESCALATE = "情绪降温"
    const val IDLE = "日常闲聊"

    val ALL = listOf(PROBE, CONFRONT, DEMAND, DEESCALATE, IDLE)

    /** 把模型返回的字符串归一到标准取值，认不出来就当日常闲聊（最保守） */
    fun normalize(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return IDLE
        ALL.firstOrNull { it == s }?.let { return it }
        // 容忍模型加了修饰，如「试探态度（她在试探）」
        ALL.firstOrNull { s.contains(it) }?.let { return it }
        return IDLE
    }
}
