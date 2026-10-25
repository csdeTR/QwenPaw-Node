package io.qwenpaw.node.data.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把一帧 SSE JSON 归约成 UI 可以直接消费的事件。
 *
 * 实测形态（QwenPaw 2.2.1）：
 * - `object=response`：一次运行的响应对象，`status` 走 created → in_progress → completed
 * - `object=message` + `type=message|reasoning`：思考/正文消息，delta 帧单独走 `object=content`
 * - `object=message` + `type=plugin_call`：`content[].data` 内是 {call_id, name, arguments}
 * - `object=message` + `type=plugin_call_output`：`content[].data` 内是 {call_id, name, output}
 * - `object=content` + `delta=true`：增量文本，带 `msg_id` / `index` / `text`
 * - `type=turn_usage`：上下文用量
 * - `type=rate_limited`：模型限流，带 alternatives
 * - 顶层 `error`：运行失败
 */
sealed interface ChatEvent {
    data class ResponseStatus(val status: String, val sequence: Int) : ChatEvent

    /** 一条消息的生命周期。kind 为 message / reasoning / plugin_call / plugin_call_output。 */
    data class MessageBlock(
        val msgId: String,
        val kind: String,
        val status: String?,
        val sequence: Int,
    ) : ChatEvent

    /** 增量文本。 */
    data class TextDelta(
        val msgId: String,
        val index: Int,
        val text: String,
    ) : ChatEvent

    /** 工具调用参数。 */
    data class ToolCall(
        val callId: String,
        val name: String,
        val arguments: String,
        val sequence: Int,
    ) : ChatEvent

    /** 工具输出。 */
    data class ToolOutput(
        val callId: String,
        val name: String,
        val output: String,
        val sequence: Int,
    ) : ChatEvent

    /** 上下文用量。 */
    data class TurnUsage(
        val estimatedTokens: Long,
        val maxInputLength: Long,
        val ratio: Double,
    ) : ChatEvent

    data class RateLimited(val message: String, val alternatives: List<String>) : ChatEvent

    data class Failure(val message: String) : ChatEvent

    /** 无法识别但可以安全忽略的帧。 */
    data object Ignored : ChatEvent
}

object ChatEventParser {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(raw: String): ChatEvent {
        val root = runCatching { lenientJson.parseToJsonElement(raw).jsonObject }
            .getOrElse { return ChatEvent.Ignored }

        // rate_limited 自带 error 字段，必须先于通用错误分支处理。
        when (root.string("type")) {
            "turn_usage" -> return parseTurnUsage(root)
            "rate_limited" -> return ChatEvent.RateLimited(
                message = root.string("error").orEmpty(),
                alternatives = root.stringList("alternatives"),
            )
        }

        errorOf(root)?.let { return ChatEvent.Failure(it) }

        val sequence = root.int("sequence_number") ?: 0
        return when (root.string("object")) {
            "response" -> ChatEvent.ResponseStatus(
                status = root.string("status").orEmpty(),
                sequence = sequence,
            )

            "message" -> parseMessage(root, sequence)

            "content" -> parseContent(root)

            else -> ChatEvent.Ignored
        }
    }

    private fun parseMessage(root: JsonObject, sequence: Int): ChatEvent {
        val kind = root.string("type").orEmpty()
        val msgId = root.string("id").orEmpty()

        // 工具调用与工具输出在消息完成时一次性带出完整 data 块。
        if (kind == "plugin_call" || kind == "plugin_call_output") {
            val block = root["content"]?.let { content ->
                runCatching { content.jsonArray.firstOrNull()?.jsonObject }.getOrNull()
            }
            val data = block?.get("data")?.let { runCatching { it.jsonObject }.getOrNull() }
            if (data != null) {
                val callId = data.string("call_id").orEmpty()
                val name = data.string("name").orEmpty()
                return if (kind == "plugin_call") {
                    ChatEvent.ToolCall(
                        callId = callId,
                        name = name,
                        arguments = data.string("arguments").orEmpty(),
                        sequence = sequence,
                    )
                } else {
                    ChatEvent.ToolOutput(
                        callId = callId,
                        name = name,
                        output = data.string("output").orEmpty(),
                        sequence = sequence,
                    )
                }
            }
        }

        return ChatEvent.MessageBlock(
            msgId = msgId,
            kind = kind,
            status = root.string("status"),
            sequence = sequence,
        )
    }

    private fun parseContent(root: JsonObject): ChatEvent {
        if (!root.boolean("delta", default = false)) {
            // 非增量 content 帧是整段文本的收尾快照，忽略以免重复渲染。
            return ChatEvent.Ignored
        }
        return ChatEvent.TextDelta(
            msgId = root.string("msg_id").orEmpty(),
            index = root.int("index") ?: 0,
            text = root.string("text").orEmpty(),
        )
    }

    private fun parseTurnUsage(root: JsonObject): ChatEvent {
        val obj = root["context_usage"]
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return ChatEvent.Ignored
        return ChatEvent.TurnUsage(
            estimatedTokens = obj.long("estimated_tokens") ?: 0,
            maxInputLength = obj.long("max_input_length") ?: 0,
            ratio = obj.double("context_usage_ratio") ?: 0.0,
        )
    }

    private fun errorOf(root: JsonObject): String? {
        val error: JsonElement = root["error"] ?: return null
        if (error is JsonObject) return error.string("message") ?: error.toString()
        return runCatching { error.jsonPrimitive.contentOrNull }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull?.toLongOrNull() }.getOrNull() }

    private fun JsonObject.double(key: String): Double? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }.getOrNull() }

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean =
        this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: default

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrDefault(emptyList())
        }.orEmpty()
}
