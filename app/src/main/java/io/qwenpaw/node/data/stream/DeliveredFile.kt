package io.qwenpaw.node.data.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 智能体通过 `send_file_to_user` 交付给用户的一个文件。
 *
 * 服务端没有「已交付文件」的独立接口，文件信息出现在工具输出的 JSON 数组里
 * （`plugin_call_output` 的 `output`），文件名与路径优先取这一份，
 * 缺失时才退回配对的 `plugin_call` 的 `file_path` 参数。实测见 `docs/protocol.md`。
 */
data class DeliveredFile(
    /** 展示用文件名。 */
    val name: String,
    /** 服务端上的完整路径，卡片副标题显示它。 */
    val path: String,
    /**
     * 图片的 data URI（`data:image/png;base64,...`）。
     *
     * 非图片一律为 null：卡片只需要文件名与路径，把几十 MB 的字节留在内存里
     * 没有用处。
     */
    val dataUri: String? = null,
    /**
     * 服务端返回的原始地址（如 `file:///tmp/a.webp`）。
     *
     * 服务端只对部分格式内联 base64，其余给的是这种 URL。
     * [io.qwenpaw.node.ui.chat.ChatImageLoader] 会把绝对路径转成预览端点，
     * 因此 webp 这类没有内联字节的图片仍有机会显示缩略图。
     */
    val remoteUrl: String? = null,
) {
    /** 可以尝试预览的图片来源；两者都没有时为 null。 */
    val previewSource: String? get() = dataUri ?: remoteUrl

    /** 按扩展名判断是否为图片。不能依赖服务端的 `media_type`，它会把 webp 标成 octet-stream。 */
    val isImage: Boolean get() = DeliveredFiles.mimeOf(name) != null
}

/**
 * `send_file_to_user` 的解析。
 *
 * 两条链路（历史接口与 SSE 增量帧）给出的工具输出形态相同，因此共享这一处解析：
 * 历史里是 `plugin_call_output` 的 `data.output`，流式里是 `ChatEvent.ToolOutput.output`。
 */
object DeliveredFiles {

    /** 工具名。服务端固定用这个标识「把文件交给用户」。 */
    const val TOOL_NAME = "send_file_to_user"

    /** 从工具的 `arguments` 里取文件路径。参数缺失或不是合法 JSON 时返回 null。 */
    internal fun pathOf(arguments: String): String? {
        if (arguments.isBlank()) return null
        val obj = runCatching {
            Json.parseToJsonElement(arguments).let { it as? JsonObject }
        }.getOrNull() ?: return null
        fun field(key: String): String? = (obj[key] as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }
        return field("file_path") ?: field("path")
    }

    /**
     * 解析交付结果。
     *
     * 实测有两种形状：
     * - 图片且服务端能识别 MIME：`source.type = "base64"`，字节内联；
     * - 其它情况（含 webp）：`source.type = "url"`，给一个 `file:///...` 地址。
     *
     * 后一种在官方网页端也只显示文件卡片、不显示缩略图。客户端不能因为它没有
     * 内联字节就整张卡片都不出——交付事实已经发生，卡片应当照常出现。
     */
    fun parse(arguments: String, output: String?): List<DeliveredFile> {
        val argPath = pathOf(arguments) ?: return emptyList()
        val argName = argPath.substringAfterLast('/').ifBlank { argPath }
        val payloads = runCatching {
            Json.parseToJsonElement(output.orEmpty()).let { it as? JsonArray }
        }.getOrNull() ?: return emptyList()
        if (payloads.isEmpty()) return emptyList()

        val files = payloads.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val source = obj["source"] as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: argName
            val type = (source["type"] as? JsonPrimitive)?.content
            val rawUrl = (source["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            when (type) {
                "base64" -> {
                    val data = (source["data"] as? JsonPrimitive)?.content
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val mime = mimeOf(name)
                    DeliveredFile(
                        name = name,
                        path = argPath,
                        dataUri = mime?.let { "data:$it;base64,$data" },
                    )
                }
                "url" -> DeliveredFile(
                    name = name,
                    path = argPath,
                    remoteUrl = rawUrl,
                )
                else -> null
            }
        }
        // 输出能解析成交付数组但条目形状不认识时，至少保留一张无预览的卡片。
        return files.ifEmpty { listOf(DeliveredFile(name = argName, path = argPath)) }
    }

    /** 可预览图片的 MIME；其余返回 null。 */
    internal fun mimeOf(name: String): String? =
        when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> null
        }
}
