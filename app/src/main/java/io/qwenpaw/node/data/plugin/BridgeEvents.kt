package io.qwenpaw.node.data.plugin

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 配套插件事件流里的一帧。
 *
 * 只承载两类计数。消息增量仍然走原有的 `push-messages` 轮询：那条链路还负责
 * 当前对话的流式片段，插件事件流替代不了它。
 */
sealed interface BridgeEvent {
    /** 连接已建立，服务端声明了协议版本。 */
    data object Ready : BridgeEvent

    /** 收件箱未读数变化。 */
    data class Unread(val unreadCount: Int) : BridgeEvent

    /** 待审批数变化。 */
    data class PendingApproval(val pending: Int) : BridgeEvent
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * 解析一个 SSE 事件块。
 *
 * 入参是不含空行分隔符的一段文本（`event:` / `data:` 行）。无法识别的块返回
 * `null` 而不是抛异常：服务端将来新增事件类型时，旧客户端应当忽略它们。
 */
internal fun parseBridgeEvent(block: String): BridgeEvent? {
    var name: String? = null
    val data = StringBuilder()
    block.lineSequence().forEach { line ->
        when {
            line.startsWith("event:") -> name = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trim())
            }
        }
    }
    val payload = data.toString().ifEmpty { return null }
    return try {
        val obj = json.parseToJsonElement(payload).jsonObject
        when (name) {
            "ready" -> BridgeEvent.Ready
            "inbox" -> BridgeEvent.Unread(obj["unread_count"]?.jsonPrimitive?.int ?: return null)
            "approval" -> BridgeEvent.PendingApproval(obj["pending"]?.jsonPrimitive?.int ?: return null)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 订阅插件事件流。
 *
 * 阻塞式读取（okhttp 的 `readUtf8Line`）在 IO 线程上做，收集方拿到的仍是普通
 * `Flow`。流结束不重连：插件本身不可用或连接被服务端关闭时，客户端回到
 * 「进入页面时刷新」的路径，靠反复重连掩盖问题只会让日志更难读。
 */
fun QwenPawApi.bridgeEventStream(conn: Connection): Flow<BridgeEvent> = flow {
    val request = bridgeEventsRequest(conn)
    QwenPawApi.streamClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use
        val source = response.body?.source() ?: return@use
        val block = StringBuilder()
        // 不用 `?: break`：`use` 是内联 lambda，`break` 在这里不合法。
        var line: String? = source.readUtf8Line()
        while (line != null && currentCoroutineContext().isActive) {
            val text = line
            if (text.isEmpty()) {
                val event = parseBridgeEvent(block.toString())
                block.clear()
                if (event != null) emit(event)
            } else {
                block.append(text).append('\n')
            }
            line = source.readUtf8Line()
        }
    }
}.flowOn(Dispatchers.IO)
