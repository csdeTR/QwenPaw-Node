package io.qwenpaw.node.data.channel

import io.qwenpaw.node.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频道配置页的中文文案映射。
 *
 * qp-test（2.3.0.post1）的 `channels/schemas` 里，20 个频道中有 18 个只给英文
 * label/help/placeholder；Console 不用 schema 文案，而是按 `form_kind` 逐字段写死
 * `channels.*` 中文。这里把 Console 的中文与我们按字段名的映射对齐，锁住漏翻。
 *
 * 表里的字段名取自 qp-test 的实际 schema，按频道分组；只放「服务端没有中文」的字段，
 * 插件自带中文 label 的频道（如 qwenpaw_node_push）不在表内。
 */
class ChannelCopyMappingTest {

    private val englishOnlyFields: Map<String, List<String>> = mapOf(
        "dingtalk" to listOf(
            "client_id", "client_secret", "message_type", "cron_message_type",
            "card_template_id", "card_template_key", "card_auto_layout", "robot_code",
            "endpoint", "at_sender_on_reply", "share_session_in_group", "media_dir",
        ),
        "onebot" to listOf(
            "ws_host", "ws_port", "access_token", "media_dir", "media_download_max_mb",
            "media_base64", "media_base64_max_mb", "share_session_in_group",
        ),
        "sip" to listOf(
            "sip_mode", "sip_host", "sip_port", "sip_username", "sip_password",
            "sip_server", "sip_transport", "rtp_port_low", "rtp_port_high",
            "dashscope_api_key", "tts_provider", "tts_voice", "stt_provider", "language",
            "welcome_greeting", "call_timeout", "livekit_url", "livekit_api_key",
            "livekit_api_secret", "livekit_sip_trunk_id", "livekit_room_name",
            "livekit_output_sample_rate", "max_concurrent_calls",
        ),
        "voice" to listOf(
            "twilio_account_sid", "twilio_auth_token", "phone_number", "phone_number_sid",
            "tts_provider", "tts_voice", "stt_provider", "language", "welcome_greeting",
        ),
        "matrix" to listOf(
            "homeserver", "user_id", "access_token", "group_allow_from", "groups",
            "encryption", "vision_enabled", "history_limit", "device_name",
            "sync_timeout_ms", "mention_pill_in_body", "outbound_structured_mentions",
            "share_session_in_group",
        ),
        "mqtt" to listOf(
            "transport", "clean_session", "qos", "subscribe_topic", "publish_topic",
            "tls_enabled", "tls_ca_certs", "tls_certfile", "tls_keyfile",
        ),
        "wecom" to listOf(
            "bot_id", "secret", "ws_url", "welcome_text", "share_session_in_group",
            "max_reconnect_attempts",
        ),
        "xiaoyi" to listOf("ak", "sk", "agent_id", "ws_url", "task_timeout_ms"),
        "yuanbao" to listOf(
            "api_domain", "ws_url", "media_dir", "accept_bot_messages",
        ),
        "azure_bot" to listOf(
            "app_id", "app_password", "tenant_id", "http_host", "http_port", "media_dir",
            "share_session_in_group",
        ),
        "qq" to listOf(
            "client_secret", "markdown_enabled", "max_reconnect_attempts", "ack_message",
        ),
        "imessage" to listOf("db_path", "poll_sec", "max_decoded_size", "media_dir"),
        "feishu" to listOf(
            "domain", "share_session_in_group", "auto_collapse_thinking", "media_dir",
        ),
        "mattermost" to listOf(
            "url", "media_dir", "thread_follow_without_mention",
        ),
        "slack" to listOf("app_token", "proxy", "media_dir"),
        "discord" to listOf("accept_bot_messages", "media_dir"),
    )

    @Test
    fun `every english-only field has a chinese label`() {
        val missing = englishOnlyFields.flatMap { (channel, names) ->
            names.filter { channelFieldLabelRes(it) == null }.map { "$channel.$it" }
        }

        assertTrue(
            "以下字段缺少中文标签映射：" + missing.joinToString("、"),
            missing.isEmpty(),
        )
    }

    @Test
    fun `dingtalk help and placeholders`() {
        assertEquals(R.string.channels_help_message_type, channelFieldHelpRes("message_type"))
        assertEquals(
            R.string.channels_help_cron_message_type,
            channelFieldHelpRes("cron_message_type"),
        )
        assertEquals(
            R.string.channels_help_card_auto_layout,
            channelFieldHelpRes("card_auto_layout"),
        )
        assertEquals(R.string.channels_help_endpoint, channelFieldHelpRes("endpoint"))
        assertEquals(
            R.string.channels_help_share_session_in_group,
            channelFieldHelpRes("share_session_in_group"),
        )
        assertEquals(
            R.string.channels_placeholder_client_id,
            channelFieldPlaceholderRes("client_id"),
        )
        assertEquals(
            R.string.channels_placeholder_endpoint,
            channelFieldPlaceholderRes("endpoint"),
        )
    }

    @Test
    fun `onebot help and placeholders`() {
        assertEquals(R.string.channels_help_ws_host, channelFieldHelpRes("ws_host"))
        assertEquals(
            R.string.channels_help_media_download_max_mb,
            channelFieldHelpRes("media_download_max_mb"),
        )
        assertEquals(
            R.string.channels_help_media_base64,
            channelFieldHelpRes("media_base64"),
        )
        assertEquals(
            R.string.channels_help_media_base64_max_mb,
            channelFieldHelpRes("media_base64_max_mb"),
        )
        assertEquals(
            R.string.channels_placeholder_ws_host,
            channelFieldPlaceholderRes("ws_host"),
        )
        assertEquals(
            R.string.channels_placeholder_ws_port,
            channelFieldPlaceholderRes("ws_port"),
        )
    }

    @Test
    fun `select options are localized`() {
        assertEquals(
            R.string.channels_option_message_type_markdown,
            channelOptionLabelRes("message_type", "markdown"),
        )
        assertEquals(
            R.string.channels_option_message_type_card,
            channelOptionLabelRes("message_type", "card"),
        )
        assertEquals(
            R.string.channels_option_message_type_card,
            channelOptionLabelRes("cron_message_type", "card"),
        )
        assertEquals(
            R.string.channels_option_message_type_markdown,
            channelOptionLabelRes("cron_message_type", "markdown"),
        )
        assertEquals(
            R.string.channels_option_open,
            channelOptionLabelRes("dm_policy", "open"),
        )
        assertEquals(
            R.string.channels_option_allowlist,
            channelOptionLabelRes("group_policy", "allowlist"),
        )
    }

    @Test
    fun `unknown names and values return null`() {
        assertNull(channelFieldLabelRes("not_a_field"))
        assertNull(channelFieldHelpRes("not_a_field"))
        assertNull(channelFieldPlaceholderRes("not_a_field"))
        assertNull(channelOptionLabelRes("not_a_field", "markdown"))
        assertNull(channelOptionLabelRes("message_type", "not_a_value"))
    }

    @Test
    fun `parsed fields carry label help and placeholder resources`() {
        val schema = Json.parseToJsonElement(
            """
            {"config_fields":[
              {"name":"client_id","label":"Client Id","type":"text"},
              {"name":"message_type","label":"Message Type","type":"text",
               "help":"markdown or card","placeholder":"markdown"},
              {"name":"unknown_field","label":"Unknown","type":"text"}
            ]}
            """,
        ).jsonObject

        val fields = parseChannelFields(schema)

        assertEquals(R.string.channels_field_client_id, fields[0].labelRes)
        assertEquals(R.string.channels_field_message_type, fields[1].labelRes)
        assertEquals(R.string.channels_help_message_type, fields[1].helpRes)
        // message_type 没有单独的 placeholder 文案：服务端原文保留在 placeholder 里。
        assertNull(fields[1].placeholderRes)
        assertEquals("markdown", fields[1].placeholder)
        assertNull(fields[2].labelRes)
        assertNull(fields[2].helpRes)
        assertNull(fields[2].placeholderRes)
    }
}
