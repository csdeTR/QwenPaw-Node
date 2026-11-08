package io.qwenpaw.node.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/search` 响应的解码。
 *
 * 与其它 DTO 一样走生产用的 [Json] 配置，重点验证两件事：`next_cursor` 为 null
 * 时不抛异常，以及 `created_at` / `score` 缺失时回落到默认值。
 */
class SearchDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `page with next cursor decodes`() {
        val raw = """
            {"items":[{"session_id":"s1","msg_id":"m1","role":"user",
            "created_at":"2026-11-01T01:21:49","snippet":"[工作区]跑完了","score":0.0}],
            "next_cursor":20,"total":31}
        """.trimIndent()
        val page = json.decodeFromString(SearchPageDto.serializer(), raw)
        assertEquals(1, page.items.size)
        assertEquals("s1", page.items[0].sessionId)
        assertEquals("m1", page.items[0].msgId)
        assertEquals("[工作区]跑完了", page.items[0].snippet)
        assertEquals(20, page.nextCursor)
        assertEquals(31, page.total)
    }

    @Test
    fun `last page has null cursor`() {
        val raw = """{"items":[],"next_cursor":null,"total":0}"""
        val page = json.decodeFromString(SearchPageDto.serializer(), raw)
        assertNull(page.nextCursor)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `missing optional fields fall back`() {
        val raw = """{"items":[{"session_id":"s1","msg_id":"m1"}],"total":1}"""
        val page = json.decodeFromString(SearchPageDto.serializer(), raw)
        assertEquals("", page.items[0].role)
        assertNull(page.items[0].createdAt)
        assertEquals(0.0, page.items[0].score, 0.0)
    }
}
