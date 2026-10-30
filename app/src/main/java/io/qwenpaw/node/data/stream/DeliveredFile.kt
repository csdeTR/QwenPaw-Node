package io.qwenpaw.node.data.stream

/**
 * 智能体通过 `send_file_to_user` 交付给用户的一个文件。
 *
 * 服务端没有「已交付文件」的独立接口，文件字节被 **base64 内联在工具输出的
 * JSON 字符串**里（`plugin_call_output` 的 `output`），文件名只能从配对的
 * `plugin_call` 的 `file_path` 参数取。实测见 `docs/protocol.md`。
 */
data class DeliveredFile(
    /** 展示用文件名，取自 `file_path` 的最后一段。 */
    val name: String,
    /** 服务端上的完整路径，卡片副标题显示它。 */
    val path: String,
    /**
     * 图片的 data URI（`data:image/png;base64,...`）。
     *
     * 非图片一律为 null：卡片只需要文件名与路径，把几十 MB 的字节留在内存里
     * 没有用处——这正是「只在需要时解码」的边界。
     */
    val dataUri: String? = null,
) {
    /** 是否可以在气泡里直接预览。 */
    val isImage: Boolean get() = dataUri != null
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
            kotlinx.serialization.json.Json.parseToJsonElement(arguments)
                .let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return null
        fun field(key: String): String? = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }
        return field("file_path") ?: field("path")
    }

    /**
     * 解析交付结果。
     *
     * [output] 形如 `[{"type":"data","source":{"type":"base64","data":"..."}}]`。
     * 不是图片时不解析字节：卡片只显示名字与路径，几十 MB 的 base64 留在内存里没有意义。
     */
    fun parse(arguments: String, output: String?): List<DeliveredFile> {
        val path = pathOf(arguments) ?: return emptyList()
        val name = path.substringAfterLast('/').ifBlank { path }
        val mime = mimeOf(name) ?: return listOf(DeliveredFile(name = name, path = path))
        if (output.isNullOrBlank()) return emptyList()

        val payloads = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(output)
                .let { it as kotlinx.serialization.json.JsonArray }
        }.getOrNull() ?: return emptyList()

        val encoded = payloads.mapNotNull { element ->
            val source = (element as? kotlinx.serialization.json.JsonObject)
                ?.get("source") as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val type = (source["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (type != "base64") return@mapNotNull null
            (source["data"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                ?.takeIf { it.isNotBlank() }
        }
        if (encoded.isEmpty()) return emptyList()
        return encoded.map { data -> DeliveredFile(name = name, path = path, dataUri = "data:$mime;base64,$data") }
    }

    /** 可预览图片的 MIME；其余返回 null。 */
    private fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> null
    }
}