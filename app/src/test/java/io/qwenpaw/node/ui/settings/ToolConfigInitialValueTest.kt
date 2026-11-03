package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.ToolConfigFieldDto
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 工具配置表单的初始值。 */
class ToolConfigInitialValueTest {

    private fun field(default: kotlinx.serialization.json.JsonElement? = null) =
        ToolConfigFieldDto(name = "k", label = "K", default = default)

    /** 服务端的 `***` 是占位符，不是真值：原样提交会把密钥写成三个星号。 */
    @Test
    fun `masked value starts empty`() {
        assertEquals("", initialValue(field(), JsonPrimitive("***")))
    }

    @Test
    fun `existing value is kept`() {
        assertEquals("hello", initialValue(field(), JsonPrimitive("hello")))
    }

    /** 没有现有值时用字段默认值，而不是空串。 */
    @Test
    fun `falls back to the declared default`() {
        assertEquals("8088", initialValue(field(JsonPrimitive("8088")), null))
    }

    @Test
    fun `missing everything yields empty`() {
        assertEquals("", initialValue(field(), null))
    }
}