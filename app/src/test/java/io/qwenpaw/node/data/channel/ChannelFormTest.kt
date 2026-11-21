package io.qwenpaw.node.data.channel

import io.qwenpaw.node.ui.common.SecretDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
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
          {"name":"proxy","label":"Proxy","type":"json"}
        ],"defaults":{"enabled":false,"bot_prefix":"","limit":200,"mode":"open","bot_token":"","proxy":{}}}
        """,
    )

    @Test
    fun `parse fields keeps order and types`() {
        val fields = parseChannelFields(schema)

        assertEquals(
            listOf("enabled", "bot_prefix", "limit", "mode", "bot_token", "proxy"),
            fields.map { it.name },
        )
        assertEquals(ChannelFieldType.SWITCH, fields[0].type)
        assertEquals(ChannelFieldType.SELECT, fields[3].type)
        assertEquals(listOf("open", "closed"), fields[3].options)
        assertEquals(ChannelFieldType.PASSWORD, fields[4].type)
        assertEquals(ChannelFieldType.JSON, fields[5].type)
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
            .let { it.copy(text = it.text + ("limit" to "abc") + ("proxy" to "[1]")) }

        val result = buildChannelPayload(original, fields, values)

        assertTrue(result is ChannelPayloadResult.Invalid)
        val errors = (result as ChannelPayloadResult.Invalid).errors
        assertEquals(ChannelFieldError.NUMBER, errors["limit"])
        assertEquals(ChannelFieldError.JSON, errors["proxy"])
    }

    private fun parseJson(text: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
}
