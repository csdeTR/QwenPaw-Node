package io.qwenpaw.node.data.stream

import io.qwenpaw.node.data.model.ContentBlockDto
import io.qwenpaw.node.data.model.MessageDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** UI 层的消息角色。 */
enum class Role { USER, ASSISTANT, SYSTEM }

/** 工具调用的展示卡片。 */
data class ToolCard(
    val callId: String,
    val name: String,
    val arguments: String,
    val output: String? = null,
    val done: Boolean = false,
)

/** 渲染用的消息单元。 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val kind: Kind,
    val text: String = "",
    val tool: ToolCard? = null,
    val streaming: Boolean = false,
    val error: String? = null,
    /** 发送失败时保留的原始输入，用于「重试」；非失败消息为 null。 */
    val retryText: String? = null,
    /**
     * 图片资源地址。可能是 `data:image/...;base64,...`（历史接口内联）、
     * `http(s)://...` 外链，或服务端本地绝对路径（走 `/api/files/preview/`）。
     * 仅在 [Kind.IMAGE] 时有值。
     */
    val imageUrl: String? = null,
    /** 图片附件的原始文件名，仅用于展示。 */
    val imageName: String? = null,
) {
    enum class Kind { TEXT, REASONING, TOOL, IMAGE }
}

/**
 * 把服务端返回的消息列表（历史接口）转换成渲染单元。
 *
 * 规则与 Console 一致：
 * - `type=message` 且 role=user → 用户气泡
 * - `type=message` 且 role=assistant → 正文
 * - `type=reasoning` → 可折叠的思考过程
 * - `type=plugin_call` / `plugin_call_output` → 工具卡片，按 call_id 配对
 */
object HistoryMapper {

    fun map(messages: List<MessageDto>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        val toolIndex = mutableMapOf<String, Int>()

        messages.forEachIndexed { index, dto ->
            val role = when (dto.role) {
                "user" -> Role.USER
                "system" -> Role.SYSTEM
                else -> Role.ASSISTANT
            }
            when (dto.type) {
                "plugin_call" -> {
                    val data = firstData(dto) ?: return@forEachIndexed
                    val callId = data.string("call_id").orEmpty()
                    val card = ToolCard(
                        callId = callId,
                        name = data.string("name").orEmpty(),
                        arguments = data.string("arguments").orEmpty(),
                    )
                    toolIndex[callId] = out.size
                    out += ChatMessage(
                        id = dto.id ?: "tool-$index-$callId",
                        role = Role.ASSISTANT,
                        kind = ChatMessage.Kind.TOOL,
                        tool = card,
                    )
                }

                "plugin_call_output" -> {
                    val data = firstData(dto) ?: return@forEachIndexed
                    val callId = data.string("call_id").orEmpty()
                    val output = data.string("output").orEmpty()
                    val at = toolIndex[callId]
                    if (at != null) {
                        val existing = out[at]
                        out[at] = existing.copy(
                            tool = existing.tool?.copy(output = output, done = true),
                        )
                    } else {
                        out += ChatMessage(
                            id = dto.id ?: "toolout-$index-$callId",
                            role = Role.ASSISTANT,
                            kind = ChatMessage.Kind.TOOL,
                            tool = ToolCard(
                                callId = callId,
                                name = data.string("name").orEmpty(),
                                arguments = "",
                                output = output,
                                done = true,
                            ),
                        )
                    }
                }

                "reasoning" -> {
                    val text = dto.content.filter { it.type == "text" }
                        .joinToString("") { it.text.orEmpty() }
                    if (text.isNotBlank()) {
                        out += ChatMessage(
                            id = dto.id ?: "reason-$index",
                            role = Role.ASSISTANT,
                            kind = ChatMessage.Kind.REASONING,
                            text = text,
                        )
                    }
                }

                else -> {
                    // 一条 message 里可能同时有文字和图片，按内容块顺序拆成多个渲染单元，
                    // 这样用户发的「文字 + 图片」能保持原始顺序。
                    var textBuffer = StringBuilder()
                    var part = 0
                    fun flushText() {
                        val text = textBuffer.toString()
                        textBuffer = StringBuilder()
                        if (text.isNotBlank()) {
                            out += ChatMessage(
                                id = if (part == 0) dto.id ?: "msg-$index" else (dto.id ?: "msg-$index") + "-t$part",
                                role = role,
                                kind = ChatMessage.Kind.TEXT,
                                text = text,
                            )
                        }
                    }
                    dto.content.forEach { block ->
                        when (block.type) {
                            "text" -> {
                                val text = block.text.orEmpty()
                                if (text.isNotBlank()) {
                                    if (textBuffer.isNotEmpty()) {
                                        flushText()
                                        part++
                                    }
                                    textBuffer.append(text)
                                }
                            }

                            "image" -> {
                                val url = mediaUrlOf(block)
                                if (!url.isNullOrBlank()) {
                                    flushText()
                                    part++
                                    out += ChatMessage(
                                        id = (dto.id ?: "msg-$index") + "-img$part",
                                        role = role,
                                        kind = ChatMessage.Kind.IMAGE,
                                        imageUrl = url,
                                        imageName = block.filename,
                                    )
                                }
                            }

                            "video" -> {
                                val url = mediaUrlOf(block)
                                if (!url.isNullOrBlank()) {
                                    if (textBuffer.isNotEmpty()) {
                                        flushText()
                                        part++
                                    }
                                    textBuffer.append("[视频] ").append(block.filename ?: url)
                                }
                            }

                            "file" -> {
                                val url = mediaUrlOf(block)
                                if (!url.isNullOrBlank()) {
                                    if (textBuffer.isNotEmpty()) {
                                        flushText()
                                        part++
                                    }
                                    textBuffer.append("[文件] ").append(block.filename ?: url)
                                }
                            }
                        }
                    }
                    flushText()
                }
            }
        }
        return uniqueIds(out)
    }

    /**
     * 服务端历史里同一个 msg_id 会出现多次（同一 assistant 消息被拆成多条记录，
     * 或 reasoning 与正文共用 msg_id）。LazyColumn 的 key 必须唯一，否则直接崩溃，
     * 因此对重复 id 追加序号。
     */
    internal fun uniqueIds(messages: List<ChatMessage>): List<ChatMessage> {
        val seen = mutableMapOf<String, Int>()
        return messages.map { message ->
            val count = seen.getOrElse(message.id) { 0 }
            seen[message.id] = count + 1
            if (count == 0) message else message.copy(id = "${message.id}#$count")
        }
    }

    private fun firstData(dto: MessageDto): kotlinx.serialization.json.JsonObject? {
        val block: ContentBlockDto = dto.content.firstOrNull { it.type == "data" } ?: return null
        val element: JsonElement = block.data ?: return null
        return runCatching { element.jsonObject }.getOrNull()
    }

    /**
     * 取媒体块的可渲染 URL。
     *
     * 历史接口把本地图片内联成 data URI（放在 `image_url`）；新协议用扁平字段，
     * 老协议用 `source.url`。三者都兜住。
     */
    private fun mediaUrlOf(block: ContentBlockDto): String? =
        block.imageUrl
            ?: block.videoUrl
            ?: block.fileUrl
            ?: block.source?.url

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.toString().trim('"') }.getOrNull() }
}
