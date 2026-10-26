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
            messages[existing] = current.copy(
                tool = current.tool?.copy(output = event.output, done = true),
            )
        } else {
            toolIndexByCallId[event.callId] = messages.size
            messages += ChatMessage(
                id = "tool-${event.callId}",
                role = Role.ASSISTANT,
                kind = ChatMessage.Kind.TOOL,
                tool = ToolCard(
                    callId = event.callId,
                    name = event.name,
                    arguments = "",
                    output = event.output,
                    done = true,
                ),
            )
        }
    }

    /** 运行结束时把所有流式标记清掉。 */
    fun finish() {
        for (i in messages.indices) {
            messages[i] = messages[i].copy(streaming = false)
        }
    }

    fun appendUser(text: String): ChatMessage {
        val message = ChatMessage(
            id = "local-user-${seq.incrementAndGet()}",
            role = Role.USER,
            kind = ChatMessage.Kind.TEXT,
            text = text,
        )
        messages += message
        return message
    }

    /** 追加一条用户发出的图片，用于发送后立即在气泡里回显。 */
    fun appendUserImage(localUri: String, name: String? = null) {
        messages += ChatMessage(
            id = "local-img-${seq.incrementAndGet()}",
            role = Role.USER,
            kind = ChatMessage.Kind.IMAGE,
            imageUrl = localUri,
            imageName = name,
        )
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
}
