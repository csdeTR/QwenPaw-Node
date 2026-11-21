package io.qwenpaw.node.ui.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 密钥字段的草稿。
 *
 * [original] 是从服务端读到的原值，只用于整段回写，不进入界面文本；
 * [replacement] 是用户新填的值；[clearing] 表示用户明确要清空。
 */
data class SecretDraft(
    val original: String = "",
    val replacement: String = "",
    val replacing: Boolean = false,
    val clearing: Boolean = false,
) {
    val hasOriginal: Boolean get() = original.isNotBlank()

    /** 是否会产生写入。仅打开替换但没填内容时不算。 */
    val changed: Boolean
        get() = clearing || (replacing && replacement.isNotEmpty() && replacement != original)
}

/**
 * 提交给「省略即不改」的端点（如供应商配置）。
 *
 * 返回 null 表示请求里不出现该字段；返回空串表示明确清除。
 */
fun secretWireValue(draft: SecretDraft): String? = when {
    draft.clearing -> ""
    draft.replacing && draft.replacement.isNotEmpty() -> draft.replacement
    else -> null
}

/** 提交给「整段替换」的端点（如频道配置）：未改时回写原值。 */
fun secretMergeValue(draft: SecretDraft): String =
    secretWireValue(draft) ?: draft.original

/** 标签输入：支持中英文逗号与换行分隔，去重并保持出现顺序。 */
fun normalizeTags(raw: String): List<String> =
    raw.split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** 空白视为空对象；语法错误或顶层不是对象时返回 null。 */
fun parseJsonObjectOrNull(text: String): JsonObject? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return runCatching {
        Json.parseToJsonElement(trimmed).jsonObject
    }.getOrNull()
}

/** 供编辑框回填使用的格式化文本。 */
fun formatJsonObject(value: JsonObject): String =
    Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value)
