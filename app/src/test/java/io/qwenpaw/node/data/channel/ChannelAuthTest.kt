package io.qwenpaw.node.data.channel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码状态的判定与凭据合并。
 *
 * 这两段是频道功能里最容易写错的地方：状态词因频道而异，而写回是整段替换。
 */
class ChannelAuthTest {

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    // ---- 状态判定 ----

    @Test
    fun `wechat confirmed counts as success`() {
        val outcome = qrOutcome("confirmed", json("bot_token" to "t"))
        assertTrue(outcome is QrOutcome.Success)
        assertEquals("t", (outcome as QrOutcome.Success).credentials["bot_token"]?.toString()?.trim('"'))
    }

    @Test
    fun `success value used by the other four channels counts as success`() {
        for (status in listOf("success", "SUCCESS")) {
            assertTrue(qrOutcome(status, JsonObject(emptyMap())) is QrOutcome.Success)
        }
    }

    @Test
    fun `waiting and scanned are pending`() {
        assertEquals(QrOutcome.Pending, qrOutcome("waiting", JsonObject(emptyMap())))
        assertEquals(QrOutcome.Pending, qrOutcome("scanned", JsonObject(emptyMap())))
    }

    @Test
    fun `expired is terminal`() {
        assertEquals(QrOutcome.Expired, qrOutcome("expired", JsonObject(emptyMap())))
    }

    @Test
    fun `fail carries the upstream reason`() {
        val outcome = qrOutcome("fail", json("fail_reason" to "用户拒绝授权"))
        outcome as QrOutcome.Failed
        assertEquals("用户拒绝授权", outcome.reason)
    }

    @Test
    fun `fail without reason is still failed`() {
        val outcome = qrOutcome("fail", JsonObject(emptyMap()))
        outcome as QrOutcome.Failed
        assertNull(outcome.reason)
    }

    @Test
    fun `unknown status is treated as pending rather than success`() {
        assertEquals(QrOutcome.Pending, qrOutcome("something-new", JsonObject(emptyMap())))
    }

    // ---- 写回合并 ----

    @Test
    fun `merge keeps unrelated fields and enables the channel`() {
        val current = JsonObject(
            mapOf(
                "enabled" to JsonPrimitive(false),
                "bot_prefix" to JsonPrimitive("[bot]"),
                "dm_policy" to JsonPrimitive("open"),
                "unknown_future_field" to JsonPrimitive(42),
            ),
        )
        val credentials = json("bot_token" to "secret", "base_url" to "https://example.com")
        val merged = mergeChannelCredentials(current, credentials)

        assertEquals(JsonPrimitive(true), merged["enabled"])
        assertEquals(JsonPrimitive("[bot]"), merged["bot_prefix"])
        assertEquals(JsonPrimitive("open"), merged["dm_policy"])
        assertEquals(JsonPrimitive(42), merged["unknown_future_field"])
        assertEquals(JsonPrimitive("secret"), merged["bot_token"])
        assertEquals(JsonPrimitive("https://example.com"), merged["base_url"])
    }

    @Test
    fun `merge overrides a stale credential with the fresh one`() {
        val current = json("bot_token" to "old")
        val merged = mergeChannelCredentials(current, json("bot_token" to "new"))
        assertEquals(JsonPrimitive("new"), merged["bot_token"])
    }

    @Test
    fun `merge enables an unconfigured channel`() {
        val merged = mergeChannelCredentials(JsonObject(emptyMap()), json("client_id" to "id"))
        assertEquals(JsonPrimitive(true), merged["enabled"])
        assertEquals(JsonPrimitive("id"), merged["client_id"])
    }

    // ---- 频道清单 ----

    @Test
    fun `only five channels support qr`() {
        assertEquals(listOf("wechat", "wecom", "dingtalk", "feishu", "qq"), QR_CAPABLE_CHANNELS)
        for (key in QR_CAPABLE_CHANNELS) assertTrue(isQrCapable(key))
        for (key in listOf("telegram", "discord", "slack", "console", "voice", "onebot")) {
            assertFalse(isQrCapable(key))
        }
    }

    @Test
    fun `known channel names have resources and unknown ones do not`() {
        for (key in QR_CAPABLE_CHANNELS + listOf("telegram", "console", "voice")) {
            assertTrue("missing name for $key", channelDisplayNameRes(key) != null)
        }
        assertNull(channelDisplayNameRes("third_party_channel"))
    }

    @Test
    fun `jsonBoolean reads enabled flag`() {
        assertTrue(jsonBoolean(json("enabled" to "true"), "enabled"))
        assertFalse(jsonBoolean(json("enabled" to "false"), "enabled"))
        assertFalse(jsonBoolean(JsonObject(emptyMap()), "enabled"))
        assertTrue(jsonBoolean(JsonObject(emptyMap()), "enabled", default = true))
    }

    // ---- 能力解析（2.3.0 频道插件化） ----

    @Test
    fun `qrcode flag decides capability even for a plugin channel`() {
        val schema = json(
            "qrcode_available" to "true",
            "label" to "DingTalk",
            "availability" to "validated",
        )
        val capability = channelCapability("some-plugin-channel", schema)
        assertTrue(capability.qrAvailable)
        assertEquals("DingTalk", capability.label)
        assertEquals("validated", capability.availability)
    }

    @Test
    fun `explicit false blocks a builtin channel`() {
        // 2.3.0 上内置频道也是插件，未安装时 qrcode_available=false。
        val capability = channelCapability(
            "dingtalk",
            json("qrcode_available" to "false", "availability" to "missing_code"),
        )
        assertFalse(capability.qrAvailable)
        assertEquals("missing_code", capability.availability)
    }

    @Test
    fun `missing flag falls back to the builtin list`() {
        // 2.2.2 的 schemas 没有 qrcode_available，按原五频道名单判断。
        assertTrue(channelCapability("wechat", JsonObject(emptyMap())).qrAvailable)
        assertFalse(channelCapability("telegram", JsonObject(emptyMap())).qrAvailable)
        assertTrue(channelCapability("wechat", null).qrAvailable)
        assertNull(channelCapability("wechat", null).label)
    }

    @Test
    fun `install repair carries action source and plugin id`() {
        val schema = JsonObject(
            mapOf(
                "availability" to JsonPrimitive("missing_code"),
                "repair" to kotlinx.serialization.json.buildJsonObject {
                    put("action", "install")
                    put("plugin_id", "dingtalk")
                    put("source", "bundled:dingtalk")
                },
            ),
        )
        val capability = channelCapability("dingtalk", schema)
        assertEquals("install", capability.repairAction)
        assertEquals("dingtalk", capability.pluginId)
        assertEquals("bundled:dingtalk", capability.repairSource)
    }

    @Test
    fun `enable and repair keep their action`() {
        val enable = JsonObject(
            mapOf(
                "repair" to kotlinx.serialization.json.buildJsonObject {
                    put("action", "enable")
                    put("plugin_id", "telegram")
                },
            ),
        )
        assertEquals("enable", channelCapability("telegram", enable).repairAction)
        assertEquals("telegram", channelCapability("telegram", enable).pluginId)

        val repair = JsonObject(
            mapOf(
                "repair" to kotlinx.serialization.json.buildJsonObject {
                    put("action", "repair")
                    put("plugin_id", "feishu")
                },
            ),
        )
        assertEquals("repair", channelCapability("feishu", repair).repairAction)
    }

    @Test
    fun `localized text prefers chinese object labels`() {
        val label = JsonObject(
            mapOf(
                "en" to JsonPrimitive("Bot Prefix"),
                "zh-CN" to JsonPrimitive("机器人前缀"),
            ),
        )
        assertEquals("机器人前缀", localizedText(label))
        assertEquals("Bot Prefix", localizedText(JsonPrimitive("Bot Prefix")))
        assertNull(localizedText(null))
    }

    @Test
    fun `diagnostic is surfaced`() {
        val capability = channelCapability(
            "dingtalk",
            json("availability" to "failed", "diagnostic" to "plugin crashed"),
        )
        assertEquals("plugin crashed", capability.diagnostic)
    }

    @Test
    fun `missing code runtime or install action counts as missing plugin`() {
        assertTrue(isMissingPlugin("missing_code", null))
        assertTrue(isMissingPlugin("missing_runtime", "repair"))
        assertTrue(isMissingPlugin(null, "install"))
        assertTrue(isMissingPlugin("failed", "install"))

        assertFalse(isMissingPlugin("running", null))
        assertFalse(isMissingPlugin("disabled", "enable"))
        assertFalse(isMissingPlugin("validation_pending", "repair"))
        assertFalse(isMissingPlugin(null, null))

        val capability = channelCapability(
            "dingtalk",
            json("availability" to "missing_code"),
        )
        assertTrue(capability.missingPlugin)
    }
}