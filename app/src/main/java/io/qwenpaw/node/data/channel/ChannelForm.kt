package io.qwenpaw.node.data.channel

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.SecretDraft
import io.qwenpaw.node.ui.common.secretMergeValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
    /** 已知字段的中文说明；为空时退回 [help]。 */
    @StringRes val helpRes: Int? = null,
    val placeholder: String? = null,
    /** 已知字段的中文输入提示；为空时退回 [placeholder]。 */
    @StringRes val placeholderRes: Int? = null,
    /**
     * `json_type`：`array` 或 `object`，未声明为 null。
     *
     * 服务端把 `allow_from` 声明成数组，客户端若一律按对象解析并写回 `{}`，
     * 保存会被 422 拒绝（`Input should be a valid list`）。
     */
    val jsonType: String? = null,
    /** 服务端声明必填；留空的必填字段要报错，而不是写入空值。 */
    val required: Boolean = false,
)

data class ChannelFormValues(
    val text: Map<String, String> = emptyMap(),
    val switches: Map<String, Boolean> = emptyMap(),
    val secrets: Map<String, SecretDraft> = emptyMap(),
)

enum class ChannelFieldError {
    NUMBER,
    JSON,

    /** 语法正确但形状不对：`json_type=array` 却填了对象，或反过来。 */
    JSON_TYPE,
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
            helpRes = channelFieldHelpRes(name),
            placeholder = localizedText(obj["placeholder"]),
            placeholderRes = channelFieldPlaceholderRes(name),
            jsonType = obj.string("json_type")?.lowercase()?.takeIf { it == JSON_ARRAY || it == JSON_OBJECT },
            required = (obj["required"] as? JsonPrimitive)?.booleanOrNull ?: false,
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
                // JSON 字段可能是数组（如 allow_from）也可能是对象，两者都要原样回填。
                val element = current?.takeUnless { it is JsonNull }
                    ?: fallback?.takeUnless { it is JsonNull }
                text[field.name] = element?.let(::formatChannelJson) ?: ""
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
                val raw = values.text[field.name].orEmpty().trim()
                when {
                    // 可选字段留空时沿用原值：写 {} 或空串会把服务端已有配置冲掉。
                    raw.isEmpty() -> if (field.required) {
                        errors[field.name] = if (field.jsonType != null) {
                            ChannelFieldError.JSON_TYPE
                        } else {
                            ChannelFieldError.JSON
                        }
                    }

                    else -> {
                        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                        when {
                            element == null -> errors[field.name] = ChannelFieldError.JSON
                            field.jsonType == JSON_ARRAY && element !is JsonArray ->
                                errors[field.name] = ChannelFieldError.JSON_TYPE

                            field.jsonType == JSON_OBJECT && element !is JsonObject ->
                                errors[field.name] = ChannelFieldError.JSON_TYPE

                            else -> body[field.name] = element
                        }
                    }
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

/** `json_type` 的两个取值。 */
private const val JSON_ARRAY = "array"
private const val JSON_OBJECT = "object"

/** console 频道键：服务端把它当管理端入口，停用后手机端就进不去配置。 */
const val CONSOLE_CHANNEL_KEY = "console"

/** 编辑框里的回填文本：数组、对象与标量都按缩进后的 JSON 展示，保存时再原样解析回来。 */
fun formatChannelJson(element: JsonElement): String =
    if (element is JsonNull) {
        ""
    } else {
        Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element)
    }

/**
 * console 频道强制保持启用。
 *
 * 服务端允许 `PUT` 把 `enabled` 写成 false，但 console 是 Web 控制台与调试入口，
 * 停用后要回到服务端手动打开才能恢复，因此保存前一律改写为 true。
 */
fun keepConsoleEnabled(channel: String, body: JsonObject): JsonObject =
    if (channel == CONSOLE_CHANNEL_KEY) {
        JsonObject(body.toMutableMap().apply { put("enabled", JsonPrimitive(true)) })
    } else {
        body
    }

/** console 的「启用」开关不显示：它的值由 [keepConsoleEnabled] 决定，不给用户改。 */
fun isChannelFieldHidden(channel: String, fieldName: String): Boolean =
    channel == CONSOLE_CHANNEL_KEY && fieldName == "enabled"

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

    // 钉钉
    "client_id" -> R.string.channels_field_client_id
    "client_secret" -> R.string.channels_field_client_secret
    "message_type" -> R.string.channels_field_message_type
    "cron_message_type" -> R.string.channels_field_cron_message_type
    "card_template_id" -> R.string.channels_field_card_template_id
    "card_template_key" -> R.string.channels_field_card_template_key
    "card_auto_layout" -> R.string.channels_field_card_auto_layout
    "robot_code" -> R.string.channels_field_robot_code
    "endpoint" -> R.string.channels_field_endpoint
    "at_sender_on_reply" -> R.string.channels_field_at_sender_on_reply
    "share_session_in_group" -> R.string.channels_field_share_session_in_group

    // OneBot 与其它走 WebSocket 的频道
    "ws_host" -> R.string.channels_field_ws_host
    "ws_port" -> R.string.channels_field_ws_port
    "ws_url" -> R.string.channels_field_ws_url
    "access_token" -> R.string.channels_field_access_token
    "media_download_max_mb" -> R.string.channels_field_media_download_max_mb
    "media_base64" -> R.string.channels_field_media_base64
    "media_base64_max_mb" -> R.string.channels_field_media_base64_max_mb

    // Matrix
    "homeserver" -> R.string.channels_field_homeserver
    "user_id" -> R.string.channels_field_user_id
    "group_allow_from" -> R.string.channels_field_group_allow_from
    "groups" -> R.string.channels_field_groups
    "encryption" -> R.string.channels_field_encryption
    "vision_enabled" -> R.string.channels_field_vision_enabled
    "history_limit" -> R.string.channels_field_history_limit
    "device_name" -> R.string.channels_field_device_name
    "sync_timeout_ms" -> R.string.channels_field_sync_timeout_ms
    "mention_pill_in_body" -> R.string.channels_field_mention_pill_in_body
    "outbound_structured_mentions" -> R.string.channels_field_outbound_structured_mentions

    // MQTT
    "transport" -> R.string.channels_field_transport
    "clean_session" -> R.string.channels_field_clean_session
    "qos" -> R.string.channels_field_qos
    "subscribe_topic" -> R.string.channels_field_subscribe_topic
    "publish_topic" -> R.string.channels_field_publish_topic
    "tls_enabled" -> R.string.channels_field_tls_enabled
    "tls_ca_certs" -> R.string.channels_field_tls_ca_certs
    "tls_certfile" -> R.string.channels_field_tls_certfile
    "tls_keyfile" -> R.string.channels_field_tls_keyfile

    // SIP 与语音
    "sip_mode" -> R.string.channels_field_sip_mode
    "sip_host" -> R.string.channels_field_sip_host
    "sip_port" -> R.string.channels_field_sip_port
    "sip_username" -> R.string.channels_field_sip_username
    "sip_password" -> R.string.channels_field_sip_password
    "sip_server" -> R.string.channels_field_sip_server
    "sip_transport" -> R.string.channels_field_sip_transport
    "rtp_port_low" -> R.string.channels_field_rtp_port_low
    "rtp_port_high" -> R.string.channels_field_rtp_port_high
    "dashscope_api_key" -> R.string.channels_field_dashscope_api_key
    "tts_provider" -> R.string.channels_field_tts_provider
    "tts_voice" -> R.string.channels_field_tts_voice
    "stt_provider" -> R.string.channels_field_stt_provider
    "language" -> R.string.channels_field_language
    "welcome_greeting" -> R.string.channels_field_welcome_greeting
    "welcome_text" -> R.string.channels_field_welcome_text
    "call_timeout" -> R.string.channels_field_call_timeout
    "livekit_url" -> R.string.channels_field_livekit_url
    "livekit_api_key" -> R.string.channels_field_livekit_api_key
    "livekit_api_secret" -> R.string.channels_field_livekit_api_secret
    "livekit_sip_trunk_id" -> R.string.channels_field_livekit_sip_trunk_id
    "livekit_room_name" -> R.string.channels_field_livekit_room_name
    "livekit_output_sample_rate" -> R.string.channels_field_livekit_output_sample_rate
    "max_concurrent_calls" -> R.string.channels_field_max_concurrent_calls
    "twilio_account_sid" -> R.string.channels_field_twilio_account_sid
    "twilio_auth_token" -> R.string.channels_field_twilio_auth_token
    "phone_number" -> R.string.channels_field_phone_number
    "phone_number_sid" -> R.string.channels_field_phone_number_sid

    // 企业微信、小艺、元宝、Azure Bot
    "bot_id" -> R.string.channels_field_bot_id
    "secret" -> R.string.channels_field_secret
    "ak" -> R.string.channels_field_ak
    "sk" -> R.string.channels_field_sk
    "agent_id" -> R.string.channels_field_agent_id
    "task_timeout_ms" -> R.string.channels_field_task_timeout_ms
    "api_domain" -> R.string.channels_field_api_domain
    "app_password" -> R.string.channels_field_app_password
    "tenant_id" -> R.string.channels_field_tenant_id
    "http_host" -> R.string.channels_field_http_host
    "http_port" -> R.string.channels_field_http_port

    // QQ、iMessage、飞书、Mattermost、Slack、Discord
    "markdown_enabled" -> R.string.channels_field_markdown_enabled
    "ack_message" -> R.string.channels_field_ack_message
    "max_reconnect_attempts" -> R.string.channels_field_max_reconnect_attempts
    "auto_collapse_thinking" -> R.string.channels_field_auto_collapse_thinking
    "accept_bot_messages" -> R.string.channels_field_accept_bot_messages
    "db_path" -> R.string.channels_field_db_path
    "poll_sec" -> R.string.channels_field_poll_sec
    "max_decoded_size" -> R.string.channels_field_max_decoded_size
    "url" -> R.string.channels_field_url
    "thread_follow_without_mention" -> R.string.channels_field_thread_follow_without_mention
    "app_token" -> R.string.channels_field_app_token
    "proxy" -> R.string.channels_field_http_proxy
    "domain" -> R.string.channels_field_domain
    else -> null
}

/**
 * 已知字段名的中文说明。文案取自 Console 的 `channels.*` 提示，服务端 schema 只给英文。
 *
 * 同一字段名在不同频道语义不同的（如 `streaming_enabled` 的钉钉与飞书提示）不在这里映射，
 * 避免把某一个频道的说法套到其它频道上。
 */
@StringRes
fun channelFieldHelpRes(name: String): Int? = when (name) {
    "show_tool_calls" -> R.string.channels_help_show_tool_calls
    "show_tool_results" -> R.string.channels_help_show_tool_results
    "tool_call_max_length" -> R.string.channels_help_tool_max_length
    "tool_result_max_length" -> R.string.channels_help_tool_max_length
    "show_thinking" -> R.string.channels_help_show_thinking
    "no_text_debounce" -> R.string.channels_help_no_text_debounce
    "auto_collapse_thinking" -> R.string.channels_help_auto_collapse_thinking
    "access_control_dm" -> R.string.channels_help_access_control_dm
    "access_control_group" -> R.string.channels_help_access_control_group
    "dm_disabled" -> R.string.channels_help_dm_disabled
    "group_disabled" -> R.string.channels_help_group_disabled
    "require_mention" -> R.string.channels_help_require_mention
    "share_session_in_group" -> R.string.channels_help_share_session_in_group
    "accept_bot_messages" -> R.string.channels_help_accept_bot_messages
    "allow_from" -> R.string.channels_help_allow_from
    "dm_policy" -> R.string.channels_help_dm_policy
    "group_policy" -> R.string.channels_help_group_policy
    "deny_message" -> R.string.channels_help_deny_message
    "message_type" -> R.string.channels_help_message_type
    "cron_message_type" -> R.string.channels_help_cron_message_type
    "card_template_key" -> R.string.channels_help_card_template_key
    "card_auto_layout" -> R.string.channels_help_card_auto_layout
    "robot_code" -> R.string.channels_help_robot_code
    "endpoint" -> R.string.channels_help_endpoint
    "at_sender_on_reply" -> R.string.channels_help_at_sender_on_reply
    "domain" -> R.string.channels_help_domain
    "ack_message" -> R.string.channels_help_ack_message
    "proxy" -> R.string.channels_help_proxy
    "phone_number_sid" -> R.string.channels_help_phone_number_sid
    "sip_mode" -> R.string.channels_help_sip_mode
    "dashscope_api_key" -> R.string.channels_help_dashscope_api_key
    "livekit_room_name" -> R.string.channels_help_livekit_room_name
    "welcome_text" -> R.string.channels_help_welcome_text
    "bot_token_file" -> R.string.channels_help_bot_token_file
    "message_merge_delay_ms" -> R.string.channels_help_message_merge_delay_ms
    "api_domain" -> R.string.channels_help_api_domain
    "ws_host" -> R.string.channels_help_ws_host
    "media_download_max_mb" -> R.string.channels_help_media_download_max_mb
    "media_base64" -> R.string.channels_help_media_base64
    "media_base64_max_mb" -> R.string.channels_help_media_base64_max_mb
    "user_id" -> R.string.channels_help_user_id
    "encryption" -> R.string.channels_help_encryption
    "device_name" -> R.string.channels_help_device_name
    else -> null
}

/** 已知字段名的输入提示。服务端 schema 的 placeholder 多为英文示例或英文说明。 */
@StringRes
fun channelFieldPlaceholderRes(name: String): Int? = when (name) {
    "bot_prefix" -> R.string.channels_placeholder_bot_prefix
    "http_proxy" -> R.string.channels_placeholder_http_proxy
    "http_proxy_auth" -> R.string.channels_placeholder_http_proxy_auth
    "proxy" -> R.string.channels_placeholder_proxy
    "db_path" -> R.string.channels_placeholder_db_path
    "client_id" -> R.string.channels_placeholder_client_id
    "card_template_id" -> R.string.channels_placeholder_card_template_id
    "card_template_key" -> R.string.channels_placeholder_card_template_key
    "endpoint" -> R.string.channels_placeholder_endpoint
    "robot_code" -> R.string.channels_placeholder_robot_code
    "ws_host" -> R.string.channels_placeholder_ws_host
    "ws_port" -> R.string.channels_placeholder_ws_port
    "homeserver" -> R.string.channels_placeholder_homeserver
    "user_id" -> R.string.channels_placeholder_user_id
    "tenant_id" -> R.string.channels_placeholder_tenant_id
    "http_host" -> R.string.channels_placeholder_http_host
    "http_port" -> R.string.channels_placeholder_http_port
    "url" -> R.string.channels_placeholder_url
    "host" -> R.string.channels_placeholder_host
    "port" -> R.string.channels_placeholder_port
    "subscribe_topic" -> R.string.channels_placeholder_subscribe_topic
    "publish_topic" -> R.string.channels_placeholder_publish_topic
    "tls_ca_certs" -> R.string.channels_placeholder_tls_ca_certs
    "tls_certfile" -> R.string.channels_placeholder_tls_certfile
    "tls_keyfile" -> R.string.channels_placeholder_tls_keyfile
    "twilio_account_sid" -> R.string.channels_placeholder_twilio_account_sid
    "phone_number" -> R.string.channels_placeholder_phone_number
    "phone_number_sid" -> R.string.channels_placeholder_phone_number_sid
    "sip_host" -> R.string.channels_placeholder_sip_host
    "sip_port" -> R.string.channels_placeholder_sip_port
    "sip_username" -> R.string.channels_placeholder_sip_username
    "sip_server" -> R.string.channels_placeholder_sip_server
    "dashscope_api_key" -> R.string.channels_placeholder_dashscope_api_key
    "livekit_url" -> R.string.channels_placeholder_livekit_url
    "livekit_api_key" -> R.string.channels_placeholder_livekit_api_key
    "livekit_api_secret" -> R.string.channels_placeholder_livekit_api_secret
    "livekit_sip_trunk_id" -> R.string.channels_placeholder_livekit_sip_trunk_id
    "livekit_room_name" -> R.string.channels_placeholder_livekit_room_name
    "ack_message" -> R.string.channels_placeholder_ack_message
    "welcome_text" -> R.string.channels_placeholder_welcome_text
    "bot_id" -> R.string.channels_placeholder_bot_id
    "secret" -> R.string.channels_placeholder_secret
    "ak" -> R.string.channels_placeholder_ak
    "sk" -> R.string.channels_placeholder_sk
    "agent_id" -> R.string.channels_placeholder_agent_id
    "api_domain" -> R.string.channels_placeholder_api_domain
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

    // 钉钉的消息类型：协议值不变，界面上补出中文含义。
    "message_type", "cron_message_type" -> when (option) {
        "markdown" -> R.string.channels_option_message_type_markdown
        "card" -> R.string.channels_option_message_type_card
        else -> null
    }

    else -> null
}
