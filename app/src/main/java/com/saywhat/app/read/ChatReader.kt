package com.saywhat.app.read

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.max

/** 消息类型。用于告诉模型「对方发的是文字、表情还是图片」。 */
enum class MsgKind { TEXT, EMOJI, STICKER, IMAGE, VOICE, VIDEO, OTHER }

/** 从聊天界面里读出的一条消息 */
data class ChatMessage(
    val kind: MsgKind,
    /** 文字原文；非文字消息为空串 */
    val text: String,
    /** 无障碍描述（表情名 / 图片描述），可能为 null */
    val label: String?,
    val fromMe: Boolean,
    val bottom: Int
) {
    /** 送给模型的展示形式：把非文字消息也变成一行可读文本 */
    val display: String
        get() = when (kind) {
            MsgKind.TEXT -> text
            MsgKind.EMOJI -> label?.let { "[表情: $it]" } ?: "[表情]"
            MsgKind.STICKER -> label?.let { "[表情包: $it]" } ?: "[表情包]"
            MsgKind.IMAGE -> label?.let { "[图片: $it]" } ?: "[图片]"
            MsgKind.VOICE -> label?.let { "[语音 $it]" } ?: "[语音]"
            MsgKind.VIDEO -> "[视频]"
            MsgKind.OTHER -> "[非文字消息]"
        }

    /** 是不是"对方说了话"（文本或表情都算，纯图片也算） */
    val isContent: Boolean get() = kind != MsgKind.OTHER
}

/**
 * 从无障碍节点树里提取聊天消息。
 *
 * ═══ 为什么不能靠控件 id ═══
 * 微信的 id（形如 com.tencent.mm:id/xxx）每个版本都会变，
 * 写死 id 的脚本活不过一次微信更新。
 *
 * 这里改用**结构 + 几何**判断，只依赖三件不会变的事实：
 * 1. 消息列表在屏幕中部（上面是标题栏，下面是输入框）；
 * 2. 自己发的消息气泡靠右对齐，对方发的靠左对齐；
 * 3. 一整行消息的容器宽度接近全屏。
 *
 * 第 2 点用「气泡相对整行容器的左右留白谁更小」判断，
 * 而不是用文字中心点 —— 长消息的中心点会跑到屏幕中间，按中心点判方向必错。
 *
 * ═══ 关于表情包（重要）═══
 * 无障碍 API **没有任何接口能读取图片像素**。所以：
 * - Unicode emoji（😂❤️）本来就混在文字里，天然拿得到；
 * - 微信内置小黄脸与自定义表情包都是图片，只能拿到 ImageView 节点本身，
 *   以及微信**是否**给它设了 contentDescription（各版本差异很大）；
 * - 拿不到是哪张图。本类的做法是：能拿到 label 就用 label，
 *   拿不到就退化成「[表情包]」三个字，让模型知道"对方发了张图"。
 *
 * 想真正看懂表情包内容，只有截屏 + 多模态模型一条路，本类不做。
 */
object ChatReader {

    /** 顶部标题栏 / 状态栏占据的比例，这之上的文字一律不算消息 */
    private const val TOP_BAND = 0.13f

    /** 底部输入区占据的比例，这之下的文字一律不算消息 */
    private const val BOTTOM_BAND = 0.85f

    /** 容器宽度超过屏幕这个比例，才认为它是「一整行消息」 */
    private const val ROW_WIDTH_RATIO = 0.70f

    /** 左右留白差小于这个值（px）就认为居中，属于时间戳/系统提示，丢弃 */
    private const val CENTER_TOLERANCE_PX = 24

    /** 往上找「整行容器」时最多爬几层 */
    private const val MAX_ANCESTOR_HOPS = 8

    /** 一次遍历最多处理多少个节点，防御超深节点树 */
    private const val NODE_BUDGET = 4000

    private const val MAX_TEXT_LEN = 500

    /** 体检时最多列几个样例节点 */
    private const val SAMPLE_LIMIT = 6

    /** 体检结果最长多少字，防止一条日志过长 */
    private const val MAX_DIAG_LEN = 600

    /** 节点树 dump 最长多少字 */
    private const val MAX_TREE_LEN = 1200

    /**
     * 表情 / 表情包 / 图片的尺寸判据（相对屏宽）。
     * ⚠️ 这三个阈值是**估计值，必须在真机上校准** —— 不同微信版本渲染尺寸不同。
     */
    private const val EMOJI_MAX_RATIO = 0.12f
    private const val STICKER_MAX_RATIO = 0.42f

    private val TIME_PATTERNS = listOf(
        Regex("""^\d{1,2}:\d{2}$"""),
        Regex("""^(上午|下午|凌晨|晚上|中午)?\s*\d{1,2}:\d{2}$"""),
        Regex("""^(今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天])\s*\d{0,2}:?\d{0,2}$"""),
        Regex("""^\d{4}年\d{1,2}月\d{1,2}日.*$"""),
        Regex("""^\d{1,2}月\d{1,2}日.*$"""),
        Regex("""^以下为新消息$"""),
        Regex("""^\d+\s*条新消息$"""),
        Regex("""^[你您]已添加.*$"""),
        Regex("""^.*撤回了一条消息$"""),
        Regex("""^.*加入了群聊$""")
    )

    /** 语音时长，如 3'' / 3″ / 3" */
    private val VOICE_PATTERN = Regex("""^\d{1,3}\s*['′"″]{1,2}$""")

    private val UI_NOISE = setOf(
        "发送", "按住 说话", "按住说话", "表情", "更多", "语音输入",
        "输入", "搜索", "返回", "聊天信息", "转账", "红包"
    )

    /** 一「行」消息的聚合容器 */
    private class Row(
        val key: String,
        val fromMe: Boolean,
        val bottom: Int
    ) {
        val texts = ArrayList<String>(2)
        val labels = ArrayList<String>(2)
        var imageMaxDim = 0
        var imageCount = 0
        var voiceHint = false
    }

    /**
     * 抓取当前屏幕上所有能识别的消息，从上到下排序。
     */
    fun collect(root: AccessibilityNodeInfo?, screenW: Int, screenH: Int): List<ChatMessage> {
        if (root == null || screenW <= 0 || screenH <= 0) return emptyList()

        val topLimit = (screenH * TOP_BAND).toInt()
        val bottomLimit = (screenH * BOTTOM_BAND).toInt()
        val minRowWidth = (screenW * ROW_WIDTH_RATIO).toInt()

        val rows = LinkedHashMap<String, Row>()
        val rect = Rect()
        val rowRect = Rect()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0

        while (stack.isNotEmpty() && guard++ < NODE_BUDGET) {
            val node = stack.removeLast()

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val isImage = node.className?.toString()?.contains("ImageView") == true

            val hasText = text.isNotEmpty() && isTextCandidate(text)
            val hasImage = isImage && node.isVisibleToUser
            val hasDescOnly = !hasText && !hasImage && desc.isNotEmpty() && isTextCandidate(desc)

            if ((hasText || hasImage || hasDescOnly) && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val cy = rect.centerY()
                if (cy in topLimit..bottomLimit && rect.width() > 0) {
                    val dir = resolveDirection(node, rect, rowRect, minRowWidth, screenW)
                    if (dir != null) {
                        val key = "${rowRect.left},${rowRect.top},${rowRect.right},${rowRect.bottom}"
                        val row = rows.getOrPut(key) { Row(key, dir, rowRect.bottom) }

                        when {
                            hasText -> {
                                if (VOICE_PATTERN.matches(text)) row.voiceHint = true
                                else if (text.length <= MAX_TEXT_LEN) row.texts.add(text)
                            }
                            hasImage -> {
                                row.imageCount++
                                if (desc.isNotEmpty()) row.labels.add(desc)
                                row.imageMaxDim = max(
                                    row.imageMaxDim,
                                    max(rect.width(), rect.height())
                                )
                            }
                            hasDescOnly -> row.labels.add(desc)
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return rows.values
            .map { toMessage(it, screenW) }
            .sortedBy { it.bottom }
    }

    /**
     * 把「我们实际能看到的节点树」逐行摊开 —— 最后的手段。
     *
     * 当体检显示「整棵树没有带文字的节点」时，需要知道那寥寥几个节点到底是什么。
     * 关键看 **`id=content` 那个容器下面有没有东西**：
     *   - 它是空的 → App 的真实界面压根没暴露给无障碍（平台限制）
     *   - 它下面有东西但没文字 → 界面是自绘的（文字画在 Canvas 上）
     *
     * 单行返回，节点之间用 ` / ` 分隔，避免把 Logbook 的一行格式冲乱。
     */
    fun dumpTree(root: AccessibilityNodeInfo?, maxNodes: Int = 40): String {
        if (root == null) return "根节点为 null"
        val sb = StringBuilder()
        val rect = Rect()
        var count = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            count++
            node.getBoundsInScreen(rect)

            sb.append(buildString {
                append("·".repeat(depth.coerceAtMost(5)))
                append(node.className?.toString()?.substringAfterLast('.') ?: "?")
                node.viewIdResourceName?.let { append('#').append(it.substringAfterLast('/')) }
                val t = node.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) append("〔").append(t.take(14)).append("〕")
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                if (d.isNotEmpty()) append("(d:").append(d.take(10)).append(')')
                append('[').append(rect.left).append(',').append(rect.top).append(' ')
                append(rect.width()).append('x').append(rect.height()).append(']')
                if (!node.isVisibleToUser) append("!可见")
                append("子").append(node.childCount)
            }).append(" / ")

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        walk(root, 0)
        if (count >= maxNodes) sb.append("…(已截断)")
        return "树($count 节点): " + sb.toString().take(MAX_TREE_LEN)
    }

    /** 屏幕上最后一个「对方发来的」内容消息；没有就返回 null */
    fun latestIncoming(root: AccessibilityNodeInfo?, screenW: Int, screenH: Int): ChatMessage? =
        collect(root, screenW, screenH).lastOrNull { !it.fromMe && it.isContent }

    /**
     * 节点树体检 —— 扫不到消息时调用，把「卡在哪一环」摊开。
     *
     * 排障时的三层漏斗：
     *   节点总数 → 有文字的 → 在消息带内的 → 判定出方向的
     * 哪一层归零，问题就在哪一层：
     *   - 有文字 = 0        → 微信没把文字暴露给无障碍（要换思路，比如截图 + OCR）
     *   - 在带内 = 0        → 我的上下边界切错了（TOP_BAND / BOTTOM_BAND）
     *   - 判定方向 = 0      → 我的行容器判据（ROW_WIDTH_RATIO）或对齐判据不对
     *
     * 只返回**一行**紧凑文本 —— Logbook 每条日志就是一行，多行会把格式冲乱。
     */
    fun diagnose(root: AccessibilityNodeInfo?, screenW: Int, screenH: Int): String {
        if (root == null) return "根节点为 null"

        val topLimit = (screenH * TOP_BAND).toInt()
        val bottomLimit = (screenH * BOTTOM_BAND).toInt()
        val minRowWidth = (screenW * ROW_WIDTH_RATIO).toInt()

        var total = 0
        var withText = 0
        var inBand = 0
        var withDir = 0
        val samples = ArrayList<String>(SAMPLE_LIMIT)

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val rect = Rect()
        val rowRect = Rect()
        var guard = 0

        while (stack.isNotEmpty() && guard++ < NODE_BUDGET) {
            val node = stack.removeLast()
            total++

            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                withText++
                node.getBoundsInScreen(rect)
                val cy = rect.centerY()
                if (cy in topLimit..bottomLimit) {
                    inBand++
                    val dir = resolveDirection(node, rect, rowRect, minRowWidth, screenW)
                    if (dir != null) withDir++
                    if (samples.size < SAMPLE_LIMIT) {
                        samples.add(
                            "「${text.take(14)}」y=$cy x=[${rect.left},${rect.right}]" +
                                    "${node.className?.toString()?.substringAfterLast('.')}" +
                                    "/${dir?.let { if (it) "我" else "她" } ?: "未判定"}"
                        )
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return buildString {
            append("屏 ${screenW}x${screenH} 带[$topLimit,$bottomLimit] 行宽≥$minRowWidth │ ")
            append("节点 $total · 有文字 $withText · 带内 $inBand · 判定方向 $withDir │ ")
            if (samples.isEmpty()) {
                append("整棵树没有带文字的节点")
            } else {
                append("样例: ").append(samples.joinToString(" "))
            }
        }.take(MAX_DIAG_LEN)
    }

    // ── 内部 ────────────────────────────────────────────────

    private fun toMessage(row: Row, screenW: Int): ChatMessage {
        // 有文字 → 就是文字消息（表情可能混在文字里，属于 Unicode emoji，天然带上了）
        if (row.texts.isNotEmpty()) {
            return ChatMessage(
                kind = MsgKind.TEXT,
                text = row.texts.joinToString(" "),
                label = null,
                fromMe = row.fromMe,
                bottom = row.bottom
            )
        }

        if (row.voiceHint) {
            return ChatMessage(MsgKind.VOICE, "", row.labels.firstOrNull(), row.fromMe, row.bottom)
        }

        // 图片类：靠尺寸猜是内置表情、自定义表情包还是照片
        if (row.imageCount > 0) {
            val kind = when {
                row.imageMaxDim <= screenW * EMOJI_MAX_RATIO -> MsgKind.EMOJI
                row.imageMaxDim <= screenW * STICKER_MAX_RATIO -> MsgKind.STICKER
                else -> MsgKind.IMAGE
            }
            return ChatMessage(kind, "", row.labels.firstOrNull(), row.fromMe, row.bottom)
        }

        if (row.labels.isNotEmpty()) {
            // 只有 contentDescription、没有实体图片，多半是某种富媒体
            return ChatMessage(MsgKind.OTHER, "", row.labels.first(), row.fromMe, row.bottom)
        }

        return ChatMessage(MsgKind.OTHER, "", null, row.fromMe, row.bottom)
    }

    private fun isTextCandidate(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.length > MAX_TEXT_LEN * 2) return false
        if (text in UI_NOISE) return false
        return TIME_PATTERNS.none { it.matches(text) }
    }

    /**
     * 判断这一行是不是自己发的，同时把「整行容器」的边界写回 [rowRect]。
     *
     * 做法：往上找到一个「几乎占满屏幕宽度」的祖先，它就是一整行消息。
     * 再看文字/图片是靠左还是靠右 —— 留白小的那一侧就是贴边的那一侧。
     * 两侧留白接近说明是居中的时间戳/系统提示，返回 null 丢弃。
     */
    private fun resolveDirection(
        node: AccessibilityNodeInfo,
        selfRect: Rect,
        rowRect: Rect,
        minRowWidth: Int,
        screenW: Int
    ): Boolean? {
        var cur: AccessibilityNodeInfo? = node.parent
        var hops = 0

        while (cur != null && hops++ < MAX_ANCESTOR_HOPS) {
            cur.getBoundsInScreen(rowRect)
            if (rowRect.width() >= minRowWidth) {
                val leftGap = selfRect.left - rowRect.left
                val rightGap = rowRect.right - selfRect.right
                if (abs(leftGap - rightGap) <= CENTER_TOLERANCE_PX) return null
                return rightGap < leftGap
            }
            cur = cur.parent
        }

        // 兜底：找不到整行容器就退回中心点判断
        rowRect.set(selfRect)
        val centerX = selfRect.centerX()
        return when {
            centerX < screenW * 0.42f -> false
            centerX > screenW * 0.58f -> true
            else -> null
        }
    }
}
