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
    /**
     * `send_file_to_user` 交付给用户的文件。
     *
     * 这类工具的输出是几十 MB 的 base64，解析成文件后 [output] 会被清空：
     * 界面上要的是文件卡片，不是那一串字节。
     */
    val delivered: List<DeliveredFile> = emptyList(),
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
    /** 消息创建时间，ISO 8601（UTC）。用户侧的时间标签用它。 */
    val timestamp: String? = null,
    /** 消息完成时间，ISO 8601（UTC）。智能体侧优先用它。 */
    val finishedAt: String? = null,
    /** 发送者名称。`metadata.original_name`：用户侧多为 `user`，助手侧是智能体名。 */
    val senderName: String? = null,
    /** 本轮用量统计，只在该轮最后一条 assistant 消息上有值。 */
    val stats: TurnStats? = null,
    /**
     * 本条消息是否为本轮的收尾消息。
     *
     * 服务端把整轮用量挂在收尾消息上，界面也只在它下面显示一次统计与操作按钮，
     * 否则每一段正文都会重复一遍同样的数字。
     */
    val turnEnd: Boolean = false,
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
        // 交付文件的路径在 plugin_call 的 arguments 里，输出帧只有字节，
        // 因此要记住每个 call_id 的参数，配对时才取得到文件名。
        val argsByCallId = mutableMapOf<String, String>()

        messages.forEachIndexed { index, dto ->
            // 这一条 dto 可能拆成多个渲染单元（文字 + 图片），
            // 记住起点，处理完再统一把 metadata 盖上去。
            val firstOfDto = out.size
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
                    argsByCallId[callId] = card.arguments
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
                    val name = data.string("name").orEmpty()
                    val raw = data.string("output").orEmpty()
                    // 交付文件的输出是 base64，先用参数换出文件列表；
                    // 拿到了就把原始输出丢掉，否则这串字节会一直留在界面状态里。
                    val delivered = if (name == DeliveredFiles.TOOL_NAME) {
                        DeliveredFiles.parse(argsByCallId[callId].orEmpty(), raw)
                    } else {
                        emptyList()
                    }
                    val output = if (delivered.isEmpty()) raw else null
                    val at = toolIndex[callId]
                    if (at != null) {
                        val existing = out[at]
                        out[at] = existing.copy(
                            tool = existing.tool?.copy(
                                output = output,
                                done = true,
                                delivered = delivered,
                            ),
                        )
                    } else {
                        out += ChatMessage(
                            id = dto.id ?: "toolout-$index-$callId",
                            role = Role.ASSISTANT,
                            kind = ChatMessage.Kind.TOOL,
                            tool = ToolCard(
                                callId = callId,
                                name = name,
                                arguments = argsByCallId[callId].orEmpty(),
                                output = output,
                                done = true,
                                delivered = delivered,
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

            // 把这条 dto 的元信息盖到它产生的每个渲染单元上。
            //
            // 为什么不只盖第一个：同一条消息拆成文字与图片后，图片块的时间与发送者
            // 同样需要显示；漏掉会让图片气泡上方缺一行。
            val meta = dto.metadata
            if (out.size > firstOfDto) {
                val stats = turnStatsOf(dto)
                for (i in firstOfDto until out.size) {
                    out[i] = out[i].copy(
                        timestamp = meta?.timestamp,
                        finishedAt = meta?.finishedAt,
                        senderName = meta?.originalName,
                        stats = stats,
                    )
                }
            }
        }
        return uniqueIds(markTurnEnd(out))
    }

    /**
     * 每轮只保留一条带统计的消息。
     *
     * 实测发现服务端把整轮的 `qwenpaw_turn_usage` 挂在**该轮每一条** assistant 消息上，
     * 而不是只挂在收尾那条。若原样渲染，一个多步回合里每一段正文下面都会重复
     * 一遍完全相同的 token 数字，看起来像每段话都花了那么多。
     *
     * 因此这里按「连续的非用户消息」切成回合，只让该轮**最后一条正文**带上统计并标
     * [ChatMessage.turnEnd]；其余消息的 `stats` 清空，数字仍然保留在那一条上。
     */
    internal fun markTurnEnd(messages: List<ChatMessage>): List<ChatMessage> {
        val out = messages.toMutableList()
        var i = 0
        while (i < out.size) {
            if (out[i].role == Role.USER || out[i].role == Role.SYSTEM) {
                i++
                continue
            }
            var end = i
            while (end < out.size && out[end].role != Role.USER && out[end].role != Role.SYSTEM) {
                end++
            }
            // 该轮里最后一条带统计的正文才是收尾消息。
            var closing = -1
            for (k in end - 1 downTo i) {
                if (out[k].kind == ChatMessage.Kind.TEXT && out[k].stats != null) {
                    closing = k
                    break
                }
            }
            for (k in i until end) {
                out[k] = out[k].copy(
                    turnEnd = k == closing,
                    stats = if (k == closing) out[k].stats else null,
                )
            }
            i = end
        }
        return out
    }

    /**
     * 取这条消息携带的本轮用量。
     *
     * 服务端只在该轮最后一条 assistant 消息上写 `qwenpaw_turn_usage`，
     * 其余消息这一层为空——所以「没有统计」是常态，不需要任何告警。
     */
    private fun turnStatsOf(dto: MessageDto): TurnStats? {
        val usage = dto.metadata?.metadata?.turnUsage?.usage ?: return null
        val stats = TurnStats(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            cacheReadTokens = usage.cacheReadTokens,
            // 服务端给百分数（实测 74.99 表示 75%），折成 0..1 小数。
            cacheHitRate = usage.cacheHitRate / 100.0,
            model = usage.modelName,
            providerId = usage.providerId,
        )
        return stats.takeIf { it.hasAny }
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

    /**
     * 取 `data` 对象里的字符串字段。
     *
     * 用 `JsonPrimitive.content` 而不是 `toString().trim('"')`：后者对字符串值会保留
     * JSON 转义，`arguments` 里内嵌的 JSON 于是变成 `{\"file_path\": ...}`，
     * 再解析一次就失败——交付文件正是卡在这里。
     */
    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? {
        val element = this[key] ?: return null
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive
        return primitive?.content ?: element.toString()
    }
}
