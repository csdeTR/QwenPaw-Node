package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用量统计的三个端点。
 *
 * 三者的口径不同（`agent-stats` 按智能体过滤，另两个全局），
 * 且 `agent_cache_hit_rate` 在空区间实测为 `null`——这两点是本测试的重点。
 */
class AgentStatsApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        conn = Connection(server.url("/").toString().trimEnd('/'), null, "agent-1")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `agent stats decodes summary and sends date range`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"total_active_sessions":14,"total_messages":68,"total_user_messages":32,
                   "total_assistant_messages":36,"total_prompt_tokens":1104365,
                   "total_completion_tokens":67790,"total_llm_calls":87,"total_tool_calls":32,
                   "by_date":[{"date":"2026-11-14","chats":1,"active_sessions":2,
                     "user_messages":3,"assistant_messages":4,"total_messages":7,
                     "prompt_tokens":10,"completion_tokens":20,"llm_calls":5,
                     "tool_calls":6,"agent_prompt_tokens":7,"agent_completion_tokens":8,
                     "agent_llm_calls":9,"agent_cache_read_tokens":10}],
                   "channel_stats":[{"channel":"console","session_count":14,
                     "user_messages":32,"assistant_messages":36,"total_messages":68}],
                   "start_date":"2026-11-08","end_date":"2026-11-14",
                   "agent_prompt_tokens":773163,"agent_completion_tokens":52960,
                   "agent_llm_calls":27,"agent_cache_read_tokens":420096,
                   "agent_cache_eligible_input_tokens":744662,
                   "agent_cache_hit_rate":56.41}""",
            ),
        )

        val stats = api.agentStats(conn, "2026-11-08", "2026-11-14")

        assertEquals(14, stats.totalActiveSessions)
        assertEquals(87, stats.totalLlmCalls)
        assertEquals(56.41, stats.agentCacheHitRate!!, 0.001)
        assertEquals(1, stats.byDate.size)
        assertEquals(9, stats.byDate.first().agentLlmCalls)
        assertEquals("console", stats.channelStats.first().channel)
        val request = server.takeRequest()
        assertEquals("/api/agent-stats", request.requestUrl!!.encodedPath)
        assertEquals("2026-11-08", request.requestUrl!!.queryParameter("start_date"))
        assertEquals("2026-11-14", request.requestUrl!!.queryParameter("end_date"))
        assertEquals("agent-1", request.getHeader("X-Agent-Id"))
    }

    /** 空区间实测返回 `null`，声明成非空 `Double` 会让整页解析失败。 */
    @Test
    fun `null cache hit rate on empty range parses`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"total_active_sessions":0,"total_messages":0,"by_date":[],
                   "channel_stats":[],"start_date":"2020-01-01","end_date":"2020-01-03",
                   "agent_cache_hit_rate":null}""",
            ),
        )

        val stats = api.agentStats(conn, "2020-01-01", "2020-01-03")

        assertNull("null 表示「这一段没有命中可算」，不是解析失败", stats.agentCacheHitRate)
        assertEquals(0, stats.totalToolCalls)
        assertTrue(stats.byDate.isEmpty())
    }

    /** 不传日期时不拼 `?`，否则服务端会收到一个空参数。 */
    @Test
    fun `no range means no query string`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        api.agentStats(conn)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/agent-stats", url.encodedPath)
        assertEquals(0, url.querySize)
    }

    @Test
    fun `llm tool trend decodes bare array`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"date":"2026-11-13","agent_llm_calls":16,"tool_calls":18},
                    {"date":"2026-11-14","agent_llm_calls":0,"tool_calls":0}]""",
            ),
        )

        val trend = api.agentLlmToolTrend(conn, "2026-11-13", "2026-11-14")

        assertEquals(2, trend.size)
        assertEquals(16, trend.first().agentLlmCalls)
        assertEquals("2026-11-14", trend.last().date)
        val request = server.takeRequest()
        assertEquals("/api/agent-stats/llm-tool-trend", request.requestUrl!!.encodedPath)
        assertEquals("2026-11-13", request.requestUrl!!.queryParameter("start_date"))
    }

    @Test
    fun `token usage details decodes rows with nullable agent id`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"prompt_tokens":142091,"completion_tokens":3278,"cache_read_tokens":77568,
                     "call_count":11,"date":"2026-11-01","provider_id":"a2a",
                     "model":"CodeArts/deepseek-v4.1-flash-1017","agent_id":"default"},
                    {"prompt_tokens":10,"completion_tokens":2,"cache_read_tokens":0,
                     "call_count":1,"date":"2026-11-02","provider_id":"a2a",
                     "model":"CodeArts/deepseek-v4.1-flash-1017","agent_id":null}]""",
            ),
        )

        val rows = api.tokenUsageDetails(conn)

        assertEquals(2, rows.size)
        assertEquals("default", rows.first().agentId)
        assertNull("历史行没有智能体归属，不能因此丢弃", rows.last().agentId)
        assertEquals(77568L, rows.first().cacheReadTokens)
        assertEquals("/api/token-usage/details", server.takeRequest().requestUrl!!.encodedPath)
    }
}
