package io.qwenpaw.node.data.channel

import io.qwenpaw.node.ui.common.SecretDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFormTest {

    private val schema = parseJson(
        """
        {"config_fields":[
          {"name":"enabled","label":"Enabled","type":"switch"},
          {"name":"bot_prefix","label":"Bot Prefix","type":"text"},
          {"name":"limit","label":"Limit","type":"number"},
          {"name":"mode","label":"Mode","type":"select","options":["open","closed"]},
          {"name":"bot_token","label":"Token","type":"password"},
          {"name":"proxy","label":"Proxy","type":"json"},
          {"name":"allow_from","label":"Allow From","type":"json","json_type":"array"},
          {"name":"extra","label":"Extra","type":"json","json_type":"object"}
        ],"defaults":{"enabled":false,"bot_prefix":"","limit":200,"mode":"open","bot_token":"","proxy":{}}}
        """,
    )

    @Test
    fun `parse fields keeps order and types`() {
        val fields = parseChannelFields(schema)

        assertEquals(
            listOf(
                "enabled",
                "bot_prefix",
                "limit",
                "mode",
                "bot_token",
                "proxy",
                "allow_from",
                "extra",
            ),
            fields.map { it.name },
        )
        assertEquals(ChannelFieldType.SWITCH, fields[0].type)
        assertEquals(ChannelFieldType.SELECT, fields[3].type)
        assertEquals(listOf("open", "closed"), fields[3].options)
        assertEquals(ChannelFieldType.PASSWORD, fields[4].type)
        assertEquals(ChannelFieldType.JSON, fields[5].type)
        assertEquals("array", fields[6].jsonType)
        assertEquals("object", fields[7].jsonType)
    }

    @Test
    fun `initial values use current config then defaults`() {
        val fields = parseChannelFields(schema)
        val original = parseJson(
            """{"enabled":true,"bot_prefix":"qp","limit":10,"mode":"closed",
               "bot_token":"secret","proxy":{"a":1},"unknown":"keep"}""",
        )

        val values = initialChannelValues(fields, original, channelDefaults(schema))

        assertEquals(true, values.switches["enabled"])
        assertEquals("qp", values.text["bot_prefix"])
        assertEquals("10", values.text["limit"])
        assertEquals("closed", values.text["mode"])
        assertEquals("secret", values.secrets["bot_token"]?.original)
        assertTrue(values.text["proxy"].orEmpty().contains("\"a\""))
    }

    @Test
    fun `payload preserves unknown keys and untouched secret`() {
        val fields = parseChannelFields(schema)
        val original = parseJson(
            """{"enabled":true,"bot_token":"secret","proxy":{"a":1},
               "unknown":"keep","mode":"closed","limit":10,"bot_prefix":"qp"}""",
        )
        val values = initialChannelValues(fields, original, channelDefaults(schema))

        val payload = buildChannelPayload(original, fields, values)
        assertTrue(payload is ChannelPayloadResult.Ok)
        val body = (payload as ChannelPayloadResult.Ok).body

        assertEquals("keep", body["unknown"]!!.jsonPrimitive.content)
        assertEquals("secret", body["bot_token"]!!.jsonPrimitive.content)
        assertEquals(10L, body["limit"]!!.jsonPrimitive.long)
        assertEquals(true, body["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `payload applies replacement and clearing`() {
        val fields = parseChannelFields(schema)
        val original = parseJson("""{"bot_token":"old"}""")
        val values = initialChannelValues(fields, original, channelDefaults(schema))

        val replaced = buildChannelPayload(
            original,
            fields,
            values.copy(
                secrets = values.secrets + (
                    "bot_token" to SecretDraft(
                        original = "old",
                        replacement = "new",
                        replacing = true,
                    )
                    ),
            ),
        )
        assertEquals(
            "new",
            (replaced as ChannelPayloadResult.Ok).body["bot_token"]!!.jsonPrimitive.content,
        )

        val cleared = buildChannelPayload(
            original,
            fields,
            values.copy(
                secrets = values.secrets + (
                    "bot_token" to SecretDraft(original = "old", clearing = true)
                    ),
            ),
        )
        assertEquals(
            "",
            (cleared as ChannelPayloadResult.Ok).body["bot_token"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `invalid number and json are reported per field`() {
        val fields = parseChannelFields(schema)
        val original = JsonObject(emptyMap())
        val values = initialChannelValues(fields, original, channelDefaults(schema))
            .let { it.copy(text = it.text + ("limit" to "abc") + ("proxy" to "{oops")) }

        val result = buildChannelPayload(original, fields, values)

        assertTrue(result is ChannelPayloadResult.Invalid)
        val errors = (result as ChannelPayloadResult.Invalid).errors
        assertEquals(ChannelFieldError.NUMBER, errors["limit"])
        assertEquals(ChannelFieldError.JSON, errors["proxy"])
    }

    @Test
    fun `allow_from array round trips as a json array`() {
        val fields = parseChannelFields(schema)
        val original = parseJson("""{"enabled":true,"allow_from":["a","b"]}""")
        val values = initialChannelValues(fields, original, channelDefaults(schema))

        assertTrue(values.text["allow_from"].orEmpty().contains("\"a\""))

        val body = (buildChannelPayload(original, fields, values) as ChannelPayloadResult.Ok).body
        assertEquals(
            listOf("a", "b"),
            body["allow_from"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `object json field round trips as an object`() {
        val fields = parseChannelFields(schema)
        val original = parseJson("""{"extra":{"k":1}}""")
        val values = initialChannelValues(fields, original, channelDefaults(schema))

        val body = (buildChannelPayload(original, fields, values) as ChannelPayloadResult.Ok).body
        assertEquals("1", body["extra"]!!.jsonObject["k"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank optional json field keeps the original value`() {
        val fields = parseChannelFields(schema)
        val original = parseJson("""{"allow_from":["a"],"proxy":{"x":1}}""")
        val values = initialChannelValues(fields, original, channelDefaults(schema))
            .let { it.copy(text = it.text + ("allow_from" to "   ") + ("proxy" to "")) }

        val body = (buildChannelPayload(original, fields, values) as ChannelPayloadResult.Ok).body

        // 留空不能写 {} 或空串，否则服务端已有配置会被覆盖。
        assertEquals(1, body["allow_from"]!!.jsonArray.size)
        assertEquals("1", body["proxy"]!!.jsonObject["x"]!!.jsonPrimitive.content)
    }

    @Test
    fun `json type mismatch is reported as JSON_TYPE`() {
        val fields = parseChannelFields(schema)
        val original = JsonObject(emptyMap())
        val values = initialChannelValues(fields, original, channelDefaults(schema))
            .let {
                it.copy(
                    text = it.text +
                        ("allow_from" to """{"a":1}""") +
                        ("extra" to "[1]"),
                )
            }

        val result = buildChannelPayload(original, fields, values)

        val errors = (result as ChannelPayloadResult.Invalid).errors
        assertEquals(ChannelFieldError.JSON_TYPE, errors["allow_from"])
        assertEquals(ChannelFieldError.JSON_TYPE, errors["extra"])
    }

    @Test
    fun `json field without declared type accepts an array`() {
        val fields = parseChannelFields(schema)
        val original = parseJson("""{"proxy":{"x":1}}""")
        val values = initialChannelValues(fields, original, channelDefaults(schema))
            .let { it.copy(text = it.text + ("proxy" to "[1,2]")) }

        val body = (buildChannelPayload(original, fields, values) as ChannelPayloadResult.Ok).body
        assertEquals(2, body["proxy"]!!.jsonArray.size)
    }

    @Test
    fun `console is forced enabled and its switch is hidden`() {
        val body = parseJson("""{"enabled":false,"bot_prefix":"qp"}""")

        val kept = keepConsoleEnabled("console", body)
        assertEquals(true, kept["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("qp", kept["bot_prefix"]!!.jsonPrimitive.content)

        // 其它频道不做改动。
        assertEquals(
            false,
            keepConsoleEnabled("wechat", body)["enabled"]!!.jsonPrimitive.boolean,
        )
        assertTrue(isChannelFieldHidden("console", "enabled"))
        assertFalse(isChannelFieldHidden("wechat", "enabled"))
        assertFalse(isChannelFieldHidden("console", "bot_prefix"))
    }

    private fun parseJson(text: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
}
