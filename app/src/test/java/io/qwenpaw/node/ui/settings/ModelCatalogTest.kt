package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.ModelSlotDto
import io.qwenpaw.node.data.model.ProviderDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可切换模型清单的合并规则。
 *
 * 聊天页的模型菜单与「设置 → 模型与提供商」共用 [modelChoicesOf]，
 * 两处各写一遍迟早会出现「菜单里有、设置里没有」。
 */
class ModelCatalogTest {

    private fun provider(
        id: String = "p1",
        enabled: Boolean = true,
        name: String = "",
        models: List<ModelSlotDto> = emptyList(),
        extra: List<ModelSlotDto> = emptyList(),
        discovered: List<ModelSlotDto> = emptyList(),
    ) = ProviderDto(
        id = id,
        name = name,
        enabled = enabled,
        models = models,
        extraModels = extra,
        discoveredModels = discovered,
    )

    @Test
    fun `keeps the three array order`() {
        val choices = modelChoicesOf(
            listOf(
                provider(
                    models = listOf(ModelSlotDto(id = "a")),
                    extra = listOf(ModelSlotDto(id = "b")),
                    discovered = listOf(ModelSlotDto(id = "c")),
                ),
            ),
        )

        assertEquals(listOf("a", "b", "c"), choices.map { it.modelId })
    }

    @Test
    fun `skips disabled providers`() {
        val choices = modelChoicesOf(
            listOf(
                provider(id = "on", models = listOf(ModelSlotDto(id = "a"))),
                provider(id = "off", enabled = false, models = listOf(ModelSlotDto(id = "b"))),
            ),
        )

        assertEquals(listOf("a"), choices.map { it.modelId })
    }

    @Test
    fun `same model under two providers is not deduplicated`() {
        val choices = modelChoicesOf(
            listOf(
                provider(id = "p1", models = listOf(ModelSlotDto(id = "same"))),
                provider(id = "p2", models = listOf(ModelSlotDto(id = "same"))),
            ),
        )

        assertEquals(2, choices.size)
        assertEquals(setOf("p1:same", "p2:same"), choices.map { it.key }.toSet())
    }

    @Test
    fun `blank display names fall back to ids`() {
        val choices = modelChoicesOf(
            listOf(provider(id = "p1", name = "", models = listOf(ModelSlotDto(id = "m", name = "")))),
        )

        assertEquals("p1", choices.first().providerName)
        assertEquals("m", choices.first().modelName)
    }

    @Test
    fun `no providers yields nothing`() {
        assertTrue(modelChoicesOf(emptyList()).isEmpty())
    }

    @Test
    fun `choice carries the model type`() {
        val choices = modelChoicesOf(
            listOf(
                provider(
                    models = listOf(
                        ModelSlotDto(id = "chat"),
                        ModelSlotDto(id = "judge", modelType = "decision"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("chat", "decision"), choices.map { it.modelType })
    }

    @Test
    fun `decision picker only lists decision models`() {
        val choices = modelChoicesOf(
            listOf(
                provider(
                    models = listOf(
                        ModelSlotDto(id = "chat", modelType = "chat"),
                        ModelSlotDto(id = "judge", modelType = "decision"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("judge"), decisionChoices(choices).map { it.modelId })
        assertEquals(2, choices.size)
    }

    @Test
    fun `decision picker is empty when no decision model exists`() {
        val choices = modelChoicesOf(
            listOf(provider(models = listOf(ModelSlotDto(id = "chat", modelType = "chat")))),
        )

        assertTrue(decisionChoices(choices).isEmpty())
    }
}
