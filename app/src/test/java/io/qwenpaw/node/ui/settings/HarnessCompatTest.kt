package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.HarnessProviderDto
import io.qwenpaw.node.data.model.PluginCatalogEntryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Harness 新旧 provider 形状与目录补齐。 */
class HarnessCompatTest {

    @Test
    fun `old shape keeps name and available`() {
        val provider = HarnessProviderDto(
            id = "codex",
            name = "Codex",
            available = true,
            installed = true,
        )
        assertEquals("Codex", provider.displayName)
        assertTrue(provider.isAvailable)
    }

    @Test
    fun `new shape uses label and status`() {
        val provider = HarnessProviderDto(
            id = "codex",
            label = "Codex Harness",
            pluginId = "qwenpaw-harness-codex",
            status = "disabled",
        )
        assertEquals("Codex Harness", provider.displayName)
        assertFalse(provider.isAvailable)
    }

    @Test
    fun `catalog adds only unregistered harnesses`() {
        val providers = listOf(
            HarnessProviderDto(
                id = "codex",
                pluginId = "qwenpaw-harness-codex",
                status = "available",
            ),
        )
        val catalog = listOf(
            PluginCatalogEntryDto(
                id = "qwenpaw-harness-codex-1.0.0",
                pluginId = "qwenpaw-harness-codex",
                name = "Codex Harness",
                kind = "harness",
            ),
            PluginCatalogEntryDto(
                id = "qwenpaw-harness-qoder-1.0.0",
                pluginId = "qwenpaw-harness-qoder",
                name = "Qoder Harness",
                kind = "harness",
            ),
            PluginCatalogEntryDto(pluginId = "other", kind = "apps"),
        )
        val installable = harnessInstallable(providers, catalog)
        assertEquals(listOf("qwenpaw-harness-qoder"), installable.map { it.pluginId })
    }
}
