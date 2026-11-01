package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.ModelsActiveDto
import io.qwenpaw.node.data.model.TokenUsageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2.3.0b2 实际返回能否被现有模型解析。
 *
 * 这两段报文取自 LXC 容器 `qp-test-prerelease`（2026-11-01 实测），
 * 与 2.2.2.post1 的差别只有两处：
 * - `cache_hit_rate` 在 2.2.2 上是数字，2.3.0b2 在没有任何调用时返回 `null`；
 * - `/api/models/active` 多了一个 `default_models` 对象。
 */
class VersionCompatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `token usage with null cache hit rate parses`() {
        val body = """
            {"total_prompt_tokens":0,"total_completion_tokens":0,"total_cache_read_tokens":0,
             "total_cache_write_tokens":0,"total_cache_eligible_input_tokens":0,
             "cache_observed_calls":0,"cache_hit_rate":null,"total_calls":0,
             "by_model":{},"by_date":{}}
        """.trimIndent()

        val usage = json.decodeFromString(TokenUsageDto.serializer(), body)

        assertEquals(0, usage.totalCalls)
        assertNull("null 表示「还没有调用」，不能当成解析失败", usage.cacheHitRate)
    }

    @Test
    fun `models active with default models parses`() {
        val body = """
            {"active_llm":{"provider_id":"a2a","model":"CodeArts/deepseek-v4.1-flash-1017"},
             "default_models":{"chat":{"provider_id":"a2a","model":"CodeArts/deepseek-v4.1-flash-1017"},
             "decision":null},"effective_max_input_length":1000000}
        """.trimIndent()

        val active = json.decodeFromString(ModelsActiveDto.serializer(), body)

        assertEquals("a2a", active.activeLlm?.providerId)
        assertEquals(1000000L, active.effectiveMaxInputLength)
    }
}