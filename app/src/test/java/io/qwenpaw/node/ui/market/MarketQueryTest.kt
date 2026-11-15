package io.qwenpaw.node.ui.market

import io.qwenpaw.node.data.model.MarketProviderDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 市场查询参数的单测。
 *
 * `provider_pages` 只能包含可用来源：服务端对未注册的 key 直接回 400，
 * 而界面拿到的错误只说「参数不对」，指不出是哪个来源的问题。
 * 判定只允许有一处出口，因此这里测的是 `MarketUiState.searchableProviders`。
 */
class MarketQueryTest {

    @Test
    fun `unavailable providers are excluded`() {
        val state = MarketUiState(
            providers = listOf(
                MarketProviderDto(key = "qwenpaw", label = "QwenPaw", available = true),
                MarketProviderDto(
                    key = "aliyun",
                    label = "Aliyun",
                    available = false,
                    reason = "missing env vars: ALIBABA_CLOUD_ACCESS_KEY_ID",
                ),
            ),
        )
        assertEquals(listOf("qwenpaw"), state.searchableProviders.map { it.key })
    }

    @Test
    fun `all available providers are kept in order`() {
        val state = MarketUiState(
            providers = listOf(
                MarketProviderDto(key = "qwenpaw", label = "QwenPaw", available = true),
                MarketProviderDto(key = "clawhub", label = "ClawHub", available = true),
                MarketProviderDto(key = "modelscope", label = "ModelScope", available = true),
            ),
        )
        assertEquals(
            listOf("qwenpaw", "clawhub", "modelscope"),
            state.searchableProviders.map { it.key },
        )
    }

    @Test
    fun `empty provider list yields nothing to search`() {
        assertEquals(emptyList<MarketProviderDto>(), MarketUiState().searchableProviders)
    }

    @Test
    fun `search limit stays within server cap`() {
        // 服务端 limit 上限是 50，超过会被截断；这里固定 20，避免改动时越界。
        assertEquals(true, MarketViewModel.SEARCH_LIMIT in 1..50)
    }
}