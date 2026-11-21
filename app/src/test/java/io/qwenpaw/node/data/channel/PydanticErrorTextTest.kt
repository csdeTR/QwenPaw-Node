package io.qwenpaw.node.data.channel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PydanticErrorTextTest {

    @Test
    fun `list type error names the field and is localized`() {
        val detail = Json.parseToJsonElement(
            """[{"type":"list_type","loc":["body","allow_from"],
                "msg":"Input should be a valid list","input":{}}]""",
        )
        assertEquals("allow_from：应为列表", pydanticDetailText(detail))
    }

    @Test
    fun `several errors are joined`() {
        val detail = Json.parseToJsonElement(
            """[{"loc":["allow_from"],"msg":"Field required"},
                {"loc":["port"],"msg":"Input should be a valid integer"}]""",
        )
        assertEquals("allow_from：必填；port：应为整数", pydanticDetailText(detail))
    }

    @Test
    fun `plain string detail is returned`() {
        val detail = Json.parseToJsonElement("\"Provider 'nope' not found.\"")
        assertEquals("Provider 'nope' not found.", pydanticDetailText(detail))
    }

    @Test
    fun `empty detail returns null`() {
        assertNull(pydanticDetailText(null))
        assertNull(pydanticDetailText(Json.parseToJsonElement("[]")))
    }
}
