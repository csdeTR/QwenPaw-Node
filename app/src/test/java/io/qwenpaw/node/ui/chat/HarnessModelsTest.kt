package io.qwenpaw.node.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第三方后端的模型列表解析。 */
class HarnessModelsTest {

    @Test
    fun `parses string and object models`() {
        val view = Json.parseToJsonElement(
            """{"models":["gpt-5",{"id":"o3"}]}""",
        ).jsonObject
        assertEquals(listOf("gpt-5", "o3"), harnessModelChoices(view).map { it.modelId })
    }

    @Test
    fun `missing models yields empty list`() {
        val view = Json.parseToJsonElement("""{"servers":[]}""").jsonObject
        assertEquals(emptyList<ModelChoice>(), harnessModelChoices(view))
    }

    @Test
    fun `parses display name and default flag`() {
        val view = Json.parseToJsonElement(
            """{"models":[{"id":"auto","name":"Auto","is_default":true},
                {"id":"ultimate","name":"Ultimate"}]}""",
        ).jsonObject
        val choices = harnessModelChoices(view)
        assertEquals("Auto", choices[0].modelName)
        assertTrue(choices[0].isDefault)
        assertEquals("Ultimate", choices[1].modelName)
        assertFalse(choices[1].isDefault)
    }

    @Test
    fun `backend default label keeps user selection`() {
        val choices = listOf(
            ModelChoice("backend", "", "auto", "Auto", isDefault = true),
            ModelChoice("backend", "", "ultimate", "Ultimate"),
        )
        assertEquals("Ultimate", backendDefaultLabel(choices, "Ultimate"))
        assertEquals("Auto", backendDefaultLabel(choices, null))
        assertEquals("Auto", backendDefaultLabel(choices, "Auto"))
    }
}
