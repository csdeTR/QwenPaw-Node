package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.DeliveredFile
import io.qwenpaw.node.data.stream.Role

/**
 * 智能体输出的折叠方式。
 *
 * 三档与 QwenPaw 官方 Console 的「Assistant message collapse」一一对应，
 * 默认值是官方默认的 [RESULT_COLLAPSED]。
 */
enum class AssistantCollapse {
    /** 全部展开，不做任何折叠。 */
    EXPANDED,

    /** 折叠过程：每条正文单独显示，思考与工具调用并成一组（官方 text-only）。 */
    PROCESS_COLLAPSED,

    /**
     * 折叠结果：一个回合里只显示最后一条正文，其余内容——包括中间正文、
     * 思考、工具调用——全部并进一组（官方 result-only）。
     */
    RESULT_COLLAPSED,
}

/**
 * 对话列表的渲染单元。
 *
 * 连续的「中间正文 / 思考 / 工具调用」会按 [AssistantCollapse] 并成一组 [Steps]，
 * 折叠成一行「处理完成 · N 步」；正文等「结果」各自成条。
 */
sealed interface ChatItem {

    /** LazyColumn 的 key，必须在整个列表内唯一且尽量稳定。 */
    val key: String

    data class Single(val message: ChatMessage) : ChatItem {
        override val key: String get() = message.id
    }

    data class Steps(val steps: List<ChatMessage>) : ChatItem {
        // 用首条的 id 做 key：后续步骤追加进来时 key 不变，列表项不会被重建。
        override val key: String get() = "steps:" + (steps.firstOrNull()?.id ?: "empty")
    }

    /**
     * 本回合交付给用户的文件。
     *
     * 与 [Steps] 分开成条，是因为它们不是「过程」而是**结果**：
     * 折叠结果模式下过程会收成一行，文件卡片仍要看得见。
     */
    data class Files(val id: String, val files: List<DeliveredFile>) : ChatItem {
        override val key: String get() = "files:$id"
    }
}

/** 过程里的一步：思考或工具调用。正文不算步骤，与官方计数口径一致。 */
private fun ChatMessage.isStep(): Boolean =
    kind == ChatMessage.Kind.REASONING || kind == ChatMessage.Kind.TOOL

/** 正文类消息：文本或图片，也就是用户想看的「回复」。 */
private fun ChatMessage.isResult(): Boolean =
    kind == ChatMessage.Kind.TEXT || kind == ChatMessage.Kind.IMAGE

/**
 * 一组步骤里真正算作「步骤」的条数。
 *
 * 只数思考与工具调用：中间正文虽然也折进这一组，但官方标题里的数字不含它。
 */
fun countSteps(steps: List<ChatMessage>): Int = steps.count { it.isStep() }

/**
 * 把消息列表折叠成渲染单元。
 *
 * 先按用户消息切成回合（用户消息永远单独成条），再对每个智能体回合套用折叠方式——
 * 与官方「按回合分组、只保留该回合最后一条结果」的口径一致。
 */
fun buildItems(messages: List<ChatMessage>, mode: AssistantCollapse): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    var i = 0
    while (i < messages.size) {
        if (messages[i].role == Role.USER) {
            out += ChatItem.Single(messages[i])
            i++
            continue
        }
        // 收集连续的非用户消息，直到下一个用户消息为止，作为一个智能体回合。
        var end = i
        while (end < messages.size && messages[end].role != Role.USER) end++
        out += buildTurn(messages.subList(i, end), mode)
        i = end
    }
    return out
}

/** 折叠一个智能体回合。 */
private fun buildTurn(turn: List<ChatMessage>, mode: AssistantCollapse): List<ChatItem> {
    val body = if (mode == AssistantCollapse.EXPANDED) {
        turn.map { ChatItem.Single(it) }
    } else {
        buildFolded(turn, mode)
    }
    // 交付的文件挂在回合末尾，不受折叠方式影响：
    // 它们是用户要拿去用的东西，不该被折进「处理完成 · N 步」里。
    val delivered = deliveredFilesOf(turn)
    return if (delivered == null) body else body + ChatItem.Files(delivered.first, delivered.second)
}

/**
 * 取本回合交付的文件。
 *
 * 同一个文件可能被重复交付（模型偶尔会把一张图发两次），按路径去重。
 * 返回值的第一项是用于生成列表 key 的消息 id，第二项是文件列表。
 */
private fun deliveredFilesOf(turn: List<ChatMessage>): Pair<String, List<DeliveredFile>>? {
    val anchor = turn.firstOrNull { !it.tool?.delivered.isNullOrEmpty() } ?: return null
    val files = turn.asSequence()
        .flatMap { it.tool?.delivered.orEmpty().asSequence() }
        .distinctBy { it.path }
        .toList()
    return anchor.id to files
}

/** 折叠过程：保留该回合最后一条正文（折叠结果模式），其余并进一组。 */
private fun buildFolded(turn: List<ChatMessage>, mode: AssistantCollapse): List<ChatItem> {

    // 出错的消息必须露出来，否则用户看不到失败原因与「重试」入口。
    // 折叠结果模式下，只有该回合最后一条正文留在外面。
    val lastResult = if (mode == AssistantCollapse.RESULT_COLLAPSED) {
        turn.indexOfLast { it.isResult() && it.error == null }
    } else {
        -1
    }

    val out = mutableListOf<ChatItem>()
    val buffer = mutableListOf<ChatMessage>()
    fun flush() {
        if (buffer.isNotEmpty()) {
            out += ChatItem.Steps(buffer.toList())
            buffer.clear()
        }
    }

    turn.forEachIndexed { index, message ->
        val standalone = message.error != null ||
            (mode == AssistantCollapse.PROCESS_COLLAPSED && message.isResult()) ||
            index == lastResult
        if (standalone) {
            flush()
            out += ChatItem.Single(message)
        } else {
            buffer += message
        }
    }
    flush()
    return out
}

/**
 * 切出当前要渲染的一段。
 *
 * [start] 之前的历史先不渲染，向上滚动时再逐页补上，避免一次铺开上千条。
 *
 * 注意窗口是按**分组后的条目**算的，不是按原始消息数：一组「处理过程」
 * 可能吃掉几十条原始消息，若按消息数分页，一页可能只有一两个条目、根本填不满屏幕。
 */
fun buildTimeline(items: List<ChatItem>, start: Int): List<ChatItem> {
    val from = start.coerceIn(0, items.size)
    return items.subList(from, items.size)
}

/**
 * 是否要在列表末尾画一行「正在生成」。
 *
 * 判据是「本轮在跑，且最后一条消息还是用户自己发的」。用「最后一条」而不是
 * 「有没有正在流式的智能体消息」是因为后者在收尾时会闪一下：`ResponseStatus=completed`
 * 会先把流式标记清掉，而 `running` 要到流处理结束才复位，中间那段空档会让等待行
 * 重新冒出来，紧跟着又消失。按「最后一条是用户消息」判断，任何一个智能体消息
 * ——正文、思考、工具、错误——落下来它就永久退场。
 *
 * 抽成纯函数是为了能脱离 Compose 断言：这一步的时序很容易被后续改动碰坏，
 * 而它在设备上只能靠肉眼观察。
 */
fun needsAwaitingPlaceholder(messages: List<ChatMessage>, running: Boolean): Boolean {
    if (!running) return false
    return messages.lastOrNull()?.role == Role.USER
}

/** 长消息折叠后保留的行数。 */
private const val USER_PREVIEW_LINES = 6

/** 长消息折叠后保留的字符数，兜住「一行但超长」的粘贴内容。 */
private const val USER_PREVIEW_CHARS = 420

/** 用户消息是否需要折叠展示。 */
fun isLongUserMessage(text: String): Boolean =
    text.lines().size > USER_PREVIEW_LINES || text.length > USER_PREVIEW_CHARS

/** 折叠态展示的截断文本。 */
fun collapsedUserPreview(text: String): String {
    val lines = text.lines()
    val head = lines.take(USER_PREVIEW_LINES).joinToString("\n")
    val clipped = head.take(USER_PREVIEW_CHARS)
    val truncated = lines.size > USER_PREVIEW_LINES || head.length > USER_PREVIEW_CHARS
    return if (truncated) "$clipped…" else clipped
}