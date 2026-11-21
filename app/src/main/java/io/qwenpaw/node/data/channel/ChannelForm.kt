package io.qwenpaw.node.data.channel

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.SecretDraft
import io.qwenpaw.node.ui.common.formatJsonObject
import io.qwenpaw.node.ui.common.parseJsonObjectOrNull
import io.qwenpaw.node.ui.common.secretMergeValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ChannelFieldType {
    SWITCH,
    TEXT,
    PASSWORD,
    NUMBER,
    SELECT,
    JSON,
}

/** 一个可编辑字段，来自 `channels/schemas` 的 `config_fields`。 */
data class ChannelField(
    val name: String,
    val label: String,
    /** 已知字段的中文标签；为空时退回 [label] 或字段名。 */
    @StringRes val labelRes: Int? = null,
    val type: ChannelFieldType,
    val options: List<String> = emptyList(),
    val help: String? = null,
    val placeholder: String? = null,
)

data class ChannelFormValues(
    val text: Map<String, String> = emptyMap(),
    val switches: Map<String, Boolean> = emptyMap(),
    val secrets: Map<String, SecretDraft> = emptyMap(),
)

enum class ChannelFieldError {
    NUMBER,
    JSON,
}

sealed interface ChannelPayloadResult {
    data class Ok(val body: JsonObject) : ChannelPayloadResult

    data class Invalid(val errors: Map<String, ChannelFieldError>) : ChannelPayloadResult
}

/** 解析 schema 的 `config_fields`；无法识别的类型跳过，避免渲染出一个改不了的输入框。 */
fun parseChannelFields(schema: JsonObject?): List<ChannelField> {
    val rows = schema?.get("config_fields") ?: return emptyList()
    val array = runCatching { rows.jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val name = obj.string("name") ?: return@mapNotNull null
        val type = when (obj.string("type")) {
            "switch" -> ChannelFieldType.SWITCH
            "password" -> ChannelFieldType.PASSWORD
            "number" -> ChannelFieldType.NUMBER
            "select" -> ChannelFieldType.SELECT
            "json" -> ChannelFieldType.JSON
            "text" -> ChannelFieldType.TEXT
            else -> return@mapNotNull null
        }
        ChannelField(
            name = name,
            label = localizedText(obj["label"]) ?: name,
            labelRes = channelFieldLabelRes(name),
            type = type,
            options = obj["options"]?.let { options ->
                runCatching { options.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
                    .getOrDefault(emptyList())
            } ?: emptyList(),
            help = localizedText(obj["help"]),
            placeholder = localizedText(obj["placeholder"]),
        )
    }
}

fun channelDefaults(schema: JsonObject?): JsonObject =
    runCatching { schema?.get("defaults")?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())

/** 用当前配置与 schema 默认值初始化表单。 */
fun initialChannelValues(
    fields: List<ChannelField>,
    original: JsonObject,
    defaults: JsonObject,
): ChannelFormValues {
    val text = linkedMapOf<String, String>()
    val switches = linkedMapOf<String, Boolean>()
    val secrets = linkedMapOf<String, SecretDraft>()
    fields.forEach { field ->
        val current = original[field.name]
        val fallback = defaults[field.name]
        when (field.type) {
            ChannelFieldType.SWITCH -> switches[field.name] = when {
                current is JsonPrimitive && current.booleanOrNull != null -> current.booleanOrNull!!
                fallback is JsonPrimitive && fallback.booleanOrNull != null -> fallback.booleanOrNull!!
                else -> false
            }

            ChannelFieldType.PASSWORD -> {
                val value = (current as? JsonPrimitive)?.contentOrNull
                    ?: (fallback as? JsonPrimitive)?.contentOrNull
                    ?: ""
                secrets[field.name] = SecretDraft(original = value)
            }

            ChannelFieldType.JSON -> {
                val value = (current as? JsonObject) ?: (fallback as? JsonObject)
                text[field.name] = value?.let(::formatJsonObject) ?: ""
            }

            else -> {
                text[field.name] = (current as? JsonPrimitive)?.contentOrNull
                    ?: (fallback as? JsonPrimitive)?.contentOrNull
                    ?: ""
            }
        }
    }
    return ChannelFormValues(text, switches, secrets)
}

/**
 * 构造整段回写的 body。
 *
 * 频道端点会用配置模型重建对象，未提交的字段回落默认值，因此以原始配置为底，
 * 只覆盖表单里的字段；密钥未改时回写原值。
 */
fun buildChannelPayload(
    original: JsonObject,
    fields: List<ChannelField>,
    values: ChannelFormValues,
): ChannelPayloadResult {
    val body = original.toMutableMap()
    val errors = linkedMapOf<String, ChannelFieldError>()
    fields.forEach { field ->
        when (field.type) {
            ChannelFieldType.PASSWORD -> {
                val draft = values.secrets[field.name] ?: SecretDraft()
                val value = secretMergeValue(draft)
                if (value.isNotEmpty() || original.containsKey(field.name)) {
                    body[field.name] = JsonPrimitive(value)
                }
            }

            ChannelFieldType.SWITCH -> {
                body[field.name] = JsonPrimitive(values.switches[field.name] ?: false)
            }

            ChannelFieldType.NUMBER -> {
                val raw = values.text[field.name].orEmpty().trim()
                if (raw.isEmpty()) {
                    body[field.name] = JsonPrimitive(0)
                } else {
                    val number = raw.toLongOrNull()
                        ?: raw.toDoubleOrNull()
                    if (number == null) {
                        errors[field.name] = ChannelFieldError.NUMBER
                    } else {
                        body[field.name] = JsonPrimitive(number)
                    }
                }
            }

            ChannelFieldType.JSON -> {
                val parsed = parseJsonObjectOrNull(values.text[field.name].orEmpty())
                if (parsed == null) {
                    errors[field.name] = ChannelFieldError.JSON
                } else {
                    body[field.name] = parsed
                }
            }

            ChannelFieldType.SELECT, ChannelFieldType.TEXT -> {
                body[field.name] = JsonPrimitive(values.text[field.name].orEmpty())
            }
        }
    }
    return if (errors.isEmpty()) {
        ChannelPayloadResult.Ok(JsonObject(body))
    } else {
        ChannelPayloadResult.Invalid(errors)
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** 运行状态值的中文标签；未知值原样显示。 */
@StringRes
fun channelAvailabilityRes(value: String?): Int? = when (value) {
    "running" -> R.string.channels_availability_running
    "validated" -> R.string.channels_availability_validated
    "validation_pending" -> R.string.channels_availability_validation_pending
    "disabled" -> R.string.channels_availability_disabled
    "missing_code" -> R.string.channels_availability_missing_code
    "missing_runtime" -> R.string.channels_availability_missing_runtime
    "failed" -> R.string.channels_availability_failed
    else -> null
}

/** 已知字段名的中文标签。服务端 schema 只给英文时用它覆盖。 */
@StringRes
fun channelFieldLabelRes(name: String): Int? = when (name) {
    "enabled" -> R.string.channels_field_enabled
    "bot_prefix" -> R.string.channels_field_bot_prefix
    "dm_policy" -> R.string.channels_field_dm_policy
    "group_policy" -> R.string.channels_field_group_policy
    "dm_disabled" -> R.string.channels_field_dm_disabled
    "group_disabled" -> R.string.channels_field_group_disabled
    "show_tool_calls" -> R.string.channels_field_show_tool_calls
    "show_tool_results" -> R.string.channels_field_show_tool_results
    "tool_call_max_length" -> R.string.channels_field_tool_call_max_length
    "tool_result_max_length" -> R.string.channels_field_tool_result_max_length
    "show_thinking" -> R.string.channels_field_show_thinking
    "streaming_enabled" -> R.string.channels_field_streaming_enabled
    "require_mention" -> R.string.channels_field_require_mention
    "access_control_dm" -> R.string.channels_field_access_control_dm
    "access_control_group" -> R.string.channels_field_access_control_group
    "no_text_debounce" -> R.string.channels_field_no_text_debounce
    "show_typing" -> R.string.channels_field_show_typing
    "http_proxy" -> R.string.channels_field_http_proxy
    "http_proxy_auth" -> R.string.channels_field_http_proxy_auth
    "allow_from" -> R.string.channels_field_allow_from
    "deny_message" -> R.string.channels_field_deny_message
    "base_url" -> R.string.channels_field_base_url
    "bot_token" -> R.string.channels_field_bot_token
    "app_id" -> R.string.channels_field_app_id
    "app_secret" -> R.string.channels_field_app_secret
    "encrypt_key" -> R.string.channels_field_encrypt_key
    "verification_token" -> R.string.channels_field_verification_token
    "port" -> R.string.channels_field_port
    "host" -> R.string.channels_field_host
    "username" -> R.string.channels_field_username
    "password" -> R.string.channels_field_password
    "token" -> R.string.channels_field_token
    "aes_key" -> R.string.channels_field_aes_key
    "bot_token_file" -> R.string.channels_field_bot_token_file
    "media_dir" -> R.string.channels_field_media_dir
    "message_merge_enabled" -> R.string.channels_field_message_merge_enabled
    "message_merge_delay_ms" -> R.string.channels_field_message_merge_delay_ms
    else -> null
}

/** 下拉值的界面标签；不认识的值原样显示。 */
@StringRes
fun channelOptionLabelRes(fieldName: String, option: String): Int? = when (fieldName) {
    "dm_policy", "group_policy" -> when (option) {
        "open" -> R.string.channels_option_open
        "allowlist" -> R.string.channels_option_allowlist
        "closed", "disabled" -> R.string.channels_option_closed
        else -> null
    }

    else -> null
}
