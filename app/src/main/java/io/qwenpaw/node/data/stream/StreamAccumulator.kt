package io.qwenpaw.node.data.stream

import java.util.concurrent.atomic.AtomicInteger

/**
 * 把增量事件折叠成可渲染的消息列表。
 *
 * 服务端的增量帧只带 `msg_id` + `index` + `text`，不带消息类型；
 * 类型由先前的 `object=message` 帧声明。这里维护 msg_id → 类型 的映射，
 * 因此必须是单线程顺序消费（SSE 读取本身是顺序的）。
 */
class StreamAccumulator {

    private val messages = mutableListOf<ChatMessage>()
    private val kindByMsgId = mutableMapOf<String, ChatMessage.Kind>()
    private val indexByMsgId = mutableMapOf<String, Int>()
    private val toolIndexByCallId = mutableMapOf<String, Int>()
    private val seq = AtomicInteger(0)

    /** 本轮 turn_usage 带来的统计，等 [finish] 时挂到收尾消息。 */
    private var pendingStats: TurnStats? = null

    fun snapshot(): List<ChatMessage> = messages.toList()

    fun onMessageBlock(event: ChatEvent.MessageBlock) {
        if (event.msgId.isBlank()) return
        val kind = when (event.kind) {
            "reasoning" -> ChatMessage.Kind.REASONING
            "plugin_call", "plugin_call_output" -> ChatMessage.Kind.TOOL
            else -> ChatMessage.Kind.TEXT
        }
        kindByMsgId[event.msgId] = kind
        if (kind == ChatMessage.Kind.TOOL) return

        val existing = indexByMsgId[event.msgId]
        if (existing == null) {
            val message = ChatMessage(
                id = event.msgId,
                role = Role.ASSISTANT,
                kind = kind,
                streaming = event.status != "completed",
            )
            indexByMsgId[event.msgId] = messages.size
            messages += message
        } else {
            val current = messages[existing]
            messages[existing] = current.copy(streaming = event.status != "completed")
        }
    }

    fun onDelta(event: ChatEvent.TextDelta) {
        val index = indexByMsgId[event.msgId] ?: run {
            val message = ChatMessage(
                id = event.msgId,
                role = Role.ASSISTANT,
                kind = kindByMsgId[event.msgId] ?: ChatMessage.Kind.TEXT,
                streaming = true,
            )
            indexByMsgId[event.msgId] = messages.size
            messages += message
            messages.size - 1
        }
        val current = messages[index]
        messages[index] = current.copy(
            text = current.text + event.text,
            streaming = true,
        )
    }

    fun onToolCall(event: ChatEvent.ToolCall) {
        val card = ToolCard(
            callId = event.callId,
            name = event.name,
            arguments = event.arguments,
        )
        val existing = toolIndexByCallId[event.callId]
        if (existing != null) {
            val current = messages[existing]
            messages[existing] = current.copy(tool = card.copy(output = current.tool?.output, done = current.tool?.done ?: false))
        } else {
            toolIndexByCallId[event.callId] = messages.size
            messages += ChatMessage(
                id = "tool-${event.callId}",
                role = Role.ASSISTANT,
                kind = ChatMessage.Kind.TOOL,
                tool = card,
            )
        }
    }

    fun onToolOutput(event: ChatEvent.ToolOutput) {
        val existing = toolIndexByCallId[event.callId]
        if (existing != null) {
            val current = messages[existing]
            val delivered = deliveredOf(event, current.tool?.arguments.orEmpty())
            messages[existing] = current.copy(
                tool = current.tool?.copy(
                    // 交付文件的输出是 base64，解析成文件后不再保留原文。
                    output = if (delivered.isEmpty()) event.output else null,
                    done = true,
                    delivered = delivered,
                ),
            )
        } else {
            toolIndexByCallId[event.callId] = messages.size
            val delivered = deliveredOf(event, arguments = "")
            messages += ChatMessage(
                id = "tool-${event.callId}",
                role = Role.ASSISTANT,
                kind = ChatMessage.Kind.TOOL,
                tool = ToolCard(
                    callId = event.callId,
                    name = event.name,
                    arguments = "",
                    output = if (delivered.isEmpty()) event.output else null,
                    done = true,
                    delivered = delivered,
                ),
            )
        }
    }

    /**
     * 从工具输出里取交付文件。
     *
     * 只有 `send_file_to_user` 会交付文件；文件名需要配对那次调用的 `arguments`，
     * 因此调用方要把 `ToolCard.arguments` 传进来。
     */
    private fun deliveredOf(event: ChatEvent.ToolOutput, arguments: String): List<DeliveredFile> =
        if (event.name == DeliveredFiles.TOOL_NAME) {
            DeliveredFiles.parse(arguments, event.output)
        } else {
            emptyList()
        }

    /** 运行结束时把所有流式标记清掉，并把本轮统计挂到收尾消息上。 */
    fun finish() {
        for (i in messages.indices) {
            messages[i] = messages[i].copy(streaming = false)
        }
        // 收尾时把「本轮最后一条 assistant 正文」标出来：统计与操作按钮只挂它一份，
        // 否则每一段中间正文下面都会重复一遍同样的数字。
        val last = messages.indexOfLast { it.role == Role.ASSISTANT && it.kind == ChatMessage.Kind.TEXT }
        if (last >= 0) {
            messages[last] = messages[last].copy(turnEnd = true, stats = pendingStats)
        }
    }

    /**
     * 暂存本轮统计。
     *
     * 流式期间无法预知哪条是最后一条，`turn_usage` 到达时先记下来，
     * 由 [finish] 统一挂上去；否则统计会停在一条中间正文上，
     * 界面上看起来像是「这半段话用了这么多 token」。
     */
    fun applyTurnStats(stats: TurnStats) {
        pendingStats = stats
    }

    fun appendUser(text: String): ChatMessage {
        val message = ChatMessage(
            id = "local-user-${seq.incrementAndGet()}",
            role = Role.USER,
            kind = ChatMessage.Kind.TEXT,
            text = text,
            // 本地发出的消息没有服务端时间戳，自己盖一个，否则气泡上方的时间会缺一格。
            timestamp = java.time.Instant.now().toString(),
        )
        messages += message
        return message
    }

    /** 追加一条用户发出的图片，用于发送后立即在气泡里回显。 */
    fun appendUserImage(localUri: String, name: String? = null): ChatMessage {
        val message = ChatMessage(
            id = "local-img-${seq.incrementAndGet()}",
            role = Role.USER,
            kind = ChatMessage.Kind.IMAGE,
            imageUrl = localUri,
            imageName = name,
            timestamp = java.time.Instant.now().toString(),
        )
        messages += message
        return message
    }

    fun appendError(message: String, retryText: String? = null) {
        messages += ChatMessage(
            id = "error-${seq.incrementAndGet()}",
            role = Role.ASSISTANT,
            kind = ChatMessage.Kind.TEXT,
            error = message,
            retryText = retryText,
        )
    }

    /** 用户点「重试」后，原失败气泡不再需要，避免界面上堆一排错误。 */
    fun removeError(id: String) {
        val at = messages.indexOfFirst { it.id == id }
        if (at >= 0) messages.removeAt(at)
    }

    /**
     * 撤销几条本地回显。
     *
     * 用于「消息根本没发出去」的情形：发送前的回显已经画到界面上，而建会话那一步失败时
     * 请求从未离开设备。留着这条气泡，用户点「重试」会把同一段文字再画一遍，变成两条。
     *
     * 不需要重建索引：调用方只会撤掉本轮刚追加在末尾的用户消息，
     * 而 `indexByMsgId` / `toolIndexByCallId` 只登记智能体消息，删尾部不影响它们的下标。
     */
    fun rollback(ids: List<String>) {
        if (ids.isEmpty()) return
        val drop = ids.toSet()
        messages.removeAll { it.id in drop }
    }
}
