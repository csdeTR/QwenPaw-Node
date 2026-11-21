package io.qwenpaw.node.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderGroupingTest {

    private fun provider(
        id: String,
        name: String = id,
        enabled: Boolean = true,
        custom: Boolean = false,
        local: Boolean = false,
        baseUrl: String? = null,
        requireApiKey: Boolean? = null,
        apiKey: String? = null,
        oauthConnected: Boolean = false,
        group: String? = null,
        groupName: String? = null,
        variant: String? = null,
        free: Boolean = false,
        models: List<ModelSlotDto> = emptyList(),
        extra: List<ModelSlotDto> = emptyList(),
    ) = ProviderDto(
        id = id,
        name = name,
        enabled = enabled,
        isCustom = custom,
        isLocal = local,
        baseUrl = baseUrl,
        requireApiKey = requireApiKey,
        apiKey = apiKey,
        oauthConnected = oauthConnected,
        providerGroup = group,
        providerGroupName = groupName,
        providerVariant = variant,
        isFreeTier = free,
        models = models,
        extraModels = extra,
    )

    @Test
    fun `disabled providers are not configured`() {
        assertFalse(isProviderConfigured(provider("dashscope", enabled = false, requireApiKey = true, apiKey = "k")))
    }

    @Test
    fun `cloud requires key unless provider says otherwise`() {
        assertFalse(isProviderConfigured(provider("dashscope", requireApiKey = true)))
        assertTrue(isProviderConfigured(provider("dashscope", requireApiKey = true, apiKey = "k")))
        assertTrue(isProviderConfigured(provider("free", requireApiKey = false)))
    }

    @Test
    fun `custom provider with base url is configured`() {
        assertTrue(isProviderConfigured(provider("mine", custom = true, baseUrl = "https://api.example.com")))
        assertFalse(isProviderConfigured(provider("mine", custom = true)))
    }

    @Test
    fun `qwenpaw local is always configured and local`() {
        val local = provider("qwenpaw-local", local = true)
        assertTrue(isProviderConfigured(local))
        assertTrue(isLocalProvider(local))
        assertTrue(isLocalProvider(provider("ollama")))
        assertTrue(isLocalProvider(provider("lmstudio")))
    }

    @Test
    fun `openai chatgpt follows oauth state`() {
        assertFalse(isProviderConfigured(provider("openai-chatgpt")))
        assertTrue(isProviderConfigured(provider("openai-chatgpt", oauthConnected = true)))
    }

    @Test
    fun `cloud groups collapse only when several variants exist`() {
        val single = provider("single", requireApiKey = false)
        val zhipuA = provider(
            "zhipu-big", name = "Zhipu (BigModel)", requireApiKey = false,
            group = "zhipu", groupName = "Zhipu", variant = "bigmodel", free = true,
        )
        val zhipuB = provider(
            "zhipu-zai", name = "Zhipu (Z.AI)", requireApiKey = false,
            group = "zhipu", groupName = "Zhipu", variant = "zai",
        )
        val sections = groupProviders(listOf(single, zhipuA, zhipuB), ProviderScope.CLOUD)
        assertEquals(listOf("single"), sections.configured.map { it.id })
        assertEquals(1, sections.configuredGroups.size)
        assertEquals("Zhipu", sections.configuredGroups.first().name)
        assertEquals(2, sections.configuredGroups.first().providers.size)
        assertTrue(sections.configuredGroups.first().hasFree)
    }

    @Test
    fun `local tab separates configured and available`() {
        val qwenpaw = provider("qwenpaw-local", local = true)
        val ollama = provider("ollama", local = true)
        val mine = provider("mine", custom = true, baseUrl = "https://api.example.com")
        val sections = groupProviders(listOf(qwenpaw, ollama, mine), ProviderScope.LOCAL)
        assertEquals(setOf("qwenpaw-local", "mine"), sections.configured.map { it.id }.toSet())
        assertEquals(listOf("ollama"), sections.available.map { it.id })
    }

    @Test
    fun `cloud tab does not include local or custom providers`() {
        val cloud = provider("dashscope", requireApiKey = false)
        val local = provider("ollama", local = true)
        val custom = provider("mine", custom = true, baseUrl = "https://api.example.com")
        val sections = groupProviders(listOf(cloud, local, custom), ProviderScope.CLOUD)
        assertEquals(listOf("dashscope"), sections.configured.map { it.id })
        assertTrue(sections.available.isEmpty())
    }
}
