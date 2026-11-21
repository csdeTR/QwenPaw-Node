package io.qwenpaw.node.data.channel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 把 FastAPI / pydantic 的 422 `detail` 变成一行可读文本。
 *
 * 服务端原样返回的是 `[{"type":"list_type","loc":["allow_from"],...}]`，
 * 直接把 JSON 贴到界面上既占地方又看不出是哪个字段错了。
 */
fun pydanticDetailText(detail: JsonElement?): String? = when (detail) {
    null -> null
    is JsonPrimitive -> detail.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonArray -> detail.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val message = obj.string("msg") ?: return@mapNotNull null
        val localized = localizePydanticMessage(message)
        val field = fieldNameOf(obj["loc"])
        if (field != null) "$field：$localized" else localized
    }.takeIf { it.isNotEmpty() }?.joinToString("；")
    else -> null
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun fieldNameOf(loc: JsonElement?): String? {
    val parts = (loc as? JsonArray)?.mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull
    } ?: return null
    return parts.asReversed().firstOrNull { part ->
        part.isNotBlank() && part !in LOCATION_PREFIXES
    }
}

private fun localizePydanticMessage(message: String): String = when (message.trim()) {
    "Input should be a valid list" -> "\u5e94\u4e3a\u5217\u8868"
    "Input should be a valid dictionary or object" -> "\u5e94\u4e3a JSON \u5bf9\u8c61"
    "Field required" -> "\u5fc5\u586b"
    "Input should be a valid string" -> "\u5e94\u4e3a\u6587\u672c"
    "Input should be a valid integer" -> "\u5e94\u4e3a\u6574\u6570"
    "Input should be a valid number" -> "\u5e94\u4e3a\u6570\u5b57"
    "Input should be a valid boolean" -> "\u5e94\u4e3a\u5f00\u5173\u503c"
    else -> message
}

private val LOCATION_PREFIXES = setOf("body", "query", "path", "header", "cookie")
