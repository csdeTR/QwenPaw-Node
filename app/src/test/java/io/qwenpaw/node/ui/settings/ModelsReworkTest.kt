package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.ModelSlotDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.ProviderScope
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提供商列表与添加表单的判定。
 *
 * 这些结论直接决定「哪个卡出现在已配置区」与「请求体长什么样」，
 * 写错时的现象都是静默的：卡片归类错、密钥没提交、协议被服务端当成未知值。
 */
class ModelsReworkTest {

    private fun provider(
        id: String,
        name: String = "",
        isLocal: Boolean = false,
        isCustom: Boolean = false,
        enabled: Boolean = true,
        baseUrl: String? = null,
        requireApiKey: Boolean? = null,
        apiKey: String? = null,
        oauthConnected: Boolean = false,
        providerGroup: String? = null,
        providerGroupName: String? = null,
        providerVariant: String? = null,
        models: List<ModelSlotDto> = emptyList(),
    ) = ProviderDto(
        id = id,
        name = name,
        enabled = enabled,
        models = models,
        baseUrl = baseUrl,
        isCustom = isCustom,
        requireApiKey = requireApiKey,
        apiKey = apiKey,
        oauthConnected = oauthConnected,
        isLocal = isLocal,
        providerGroup = providerGroup,
        providerGroupName = providerGroupName,
        providerVariant = providerVariant,
    )

    @Test
    fun `search matches id name group and variant`() {
        val zhipu = provider(
            id = "zhipu-bigmodel",
            name = "智谱 BigModel",
            providerGroup = "zhipu",
            providerGroupName = "Zhipu",
            providerVariant = "BigModel",
        )
        val list = listOf(zhipu, provider(id = "openai", name = "OpenAI"))

        assertEquals(listOf(zhipu, list[1]), filterProviders(list, ""))
        assertEquals(listOf(zhipu), filterProviders(list, "zhipu"))
        assertEquals(listOf(zhipu), filterProviders(list, "BIGMODEL"))
        assertEquals(listOf(zhipu), filterProviders(list, "智谱"))
        assertTrue(filterProviders(list, "nobody").isEmpty())
    }

    @Test
    fun `disabled provider is never reported as configured`() {
        val disabled = provider(
            id = "openai",
            enabled = false,
            requireApiKey = true,
            apiKey = "sk-xxx",
        )

        assertEquals(ProviderStatus.DISABLED, providerStatus(disabled))
    }

    @Test
    fun `configured matrix follows the shared judgement`() {
        assertEquals(
            ProviderStatus.CONFIGURED,
            providerStatus(provider(id = "custom", isCustom = true, baseUrl = "https://a.b/v1")),
        )
        assertEquals(
            ProviderStatus.UNCONFIGURED,
            providerStatus(provider(id = "custom", isCustom = true)),
        )
        assertEquals(
            ProviderStatus.CONFIGURED,
            providerStatus(provider(id = "qwenpaw-local", isLocal = true)),
        )
        assertEquals(
            ProviderStatus.CONFIGURED,
            providerStatus(provider(id = "deepseek", requireApiKey = true, apiKey = "sk-1")),
        )
        // 旧版服务端不带 require_api_key 且没有密钥时，不猜「已配置」。
        assertEquals(
            ProviderStatus.UNCONFIGURED,
            providerStatus(provider(id = "deepseek")),
        )
    }

    @Test
    fun `credential hint reflects what the server told us`() {
        assertEquals(
            R.string.models_rework_credential_oauth,
            providerCredentialRes(provider(id = "openai-chatgpt", oauthConnected = true)),
        )
        assertEquals(
            R.string.models_rework_credential_missing,
            providerCredentialRes(provider(id = "deepseek", requireApiKey = true)),
        )
        assertEquals(
            R.string.models_rework_credential_set,
            providerCredentialRes(provider(id = "deepseek", requireApiKey = true, apiKey = "sk-1")),
        )
        assertEquals(
            R.string.models_rework_credential_none,
            providerCredentialRes(provider(id = "ollama", isLocal = true, requireApiKey = false)),
        )
        assertNull(providerCredentialRes(provider(id = "unknown")))
    }

    @Test
    fun `counts follow the tab split used by grouping`() {
        val list = listOf(
            provider(id = "openai"),
            provider(id = "zhipu"),
            provider(id = "ollama", isLocal = true),
            provider(id = "mine", isCustom = true),
        )

        assertEquals(2, countProviders(list, ProviderScope.CLOUD))
        assertEquals(2, countProviders(list, ProviderScope.LOCAL))
        assertEquals(ProviderScope.LOCAL, providerScopeOf(list[3]))
    }

    @Test
    fun `variant label prefers provider variant`() {
        assertEquals(
            "Z.AI",
            variantLabel(provider(id = "zhipu-zai", name = "智谱 Z.AI", providerVariant = "Z.AI")),
        )
        assertEquals("智谱 Z.AI", variantLabel(provider(id = "zhipu-zai", name = "智谱 Z.AI")))
        assertEquals("zhipu-zai", variantLabel(provider(id = "zhipu-zai")))
    }

    @Test
    fun `provider id must be lower case and start with a letter`() {
        assertNull(validateProviderId("my-provider_2"))
        assertNull(validateProviderId("a"))
        assertEquals(ProviderFormError.ID_EMPTY, validateProviderId("   "))
        assertEquals(ProviderFormError.ID_INVALID, validateProviderId("My-Provider"))
        assertEquals(ProviderFormError.ID_INVALID, validateProviderId("1provider"))
        assertEquals(ProviderFormError.ID_INVALID, validateProviderId("my provider"))
        assertEquals(ProviderFormError.ID_INVALID, validateProviderId("a".repeat(65)))
    }

    @Test
    fun `base url only accepts http and https`() {
        assertNull(validateProviderBaseUrl("https://api.openai.com/v1"))
        assertNull(validateProviderBaseUrl("http://10.10.0.211:8088/v1"))
        assertEquals(ProviderFormError.BASE_URL_EMPTY, validateProviderBaseUrl(" "))
        assertEquals(ProviderFormError.BASE_URL_INVALID, validateProviderBaseUrl("ftp://a.b"))
        assertEquals(ProviderFormError.BASE_URL_INVALID, validateProviderBaseUrl("api.openai.com"))
    }

    @Test
    fun `create body keeps only the fields the endpoint accepts`() {
        val body = providerCreateBody(
            id = " my-provider ",
            name = "  ",
            baseUrl = " https://api.example.com/v1 ",
            apiKey = "",
            protocol = ProviderProtocol.OPENAI_CHAT,
        )

        assertEquals(setOf("id", "name", "default_base_url", "chat_model"), body.keys)
        assertEquals("my-provider", body["id"]!!.jsonPrimitive.content)
        assertEquals("my-provider", body["name"]!!.jsonPrimitive.content)
        assertEquals("https://api.example.com/v1", body["default_base_url"]!!.jsonPrimitive.content)
        assertEquals("OpenAIChatModel", body["chat_model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create body includes the key and the chosen protocol`() {
        val body = providerCreateBody(
            id = "anthropic-x",
            name = "Anthropic X",
            baseUrl = "https://api.example.com",
            apiKey = "sk-ant",
            protocol = ProviderProtocol.ANTHROPIC,
        )

        assertEquals("Anthropic X", body["name"]!!.jsonPrimitive.content)
        assertEquals("sk-ant", body["api_key"]!!.jsonPrimitive.content)
        assertEquals("AnthropicChatModel", body["chat_model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `protocol wires are exactly the three accepted values`() {
        assertEquals(
            listOf("OpenAIChatModel", "OpenAIResponseModel", "AnthropicChatModel"),
            ProviderProtocol.entries.map { it.wire },
        )
        assertFalse(ProviderProtocol.OPENAI_RESPONSES.wire.isEmpty())
    }
}
