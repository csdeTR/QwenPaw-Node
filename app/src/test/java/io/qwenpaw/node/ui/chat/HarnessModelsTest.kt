package io.qwenpaw.node.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
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
}
