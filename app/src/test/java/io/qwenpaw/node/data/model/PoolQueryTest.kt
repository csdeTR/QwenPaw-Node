package io.qwenpaw.node.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolQueryTest {

    @Test
    fun `selected ids ignore discovered models`() {
        val provider = ProviderDto(
            id = "dashscope",
            models = listOf(ModelSlotDto(id = "a"), ModelSlotDto(id = "b")),
            extraModels = listOf(ModelSlotDto(id = "c")),
            discoveredModels = listOf(ModelSlotDto(id = "d")),
        )
        assertEquals(setOf("a", "b", "c"), selectedModelIds(provider))
    }

    @Test
    fun `tab family and search changes requery`() {
        val base = PoolFilters(tab = "all", search = "", family = "all")
        assertTrue(shouldReloadPool(base, base.copy(tab = "selected")))
        assertTrue(shouldReloadPool(base, base.copy(family = "qwen")))
        assertTrue(shouldReloadPool(base, base.copy(search = "qwen")))
    }

    @Test
    fun `same filters do not requery`() {
        val base = PoolFilters(tab = "all", search = "qwen", family = "qwen")
        assertFalse(shouldReloadPool(base, base.copy(search = " qwen ")))
    }

    @Test
    fun `offset is not part of filter comparison`() {
        val base = PoolFilters(tab = "all", search = "", family = "all")
        assertFalse(shouldReloadPool(base, base))
    }
}
