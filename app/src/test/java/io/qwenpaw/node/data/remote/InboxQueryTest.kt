package io.qwenpaw.node.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收件箱查询的 URL 拼装。
 *
 * 筛选完全交给服务端：参数拼错不会报错，只会静默返回全量数据，因此必须单测。
 */
class InboxQueryTest {
    private val api = QwenPawApi()
    private val base = "http://10.10.0.211:8088/api/console/inbox/events".toHttpUrl()

    private fun url(
        agentId: String? = null,
        sourceType: String? = null,
        unreadOnly: Boolean = false,
    ) = api.inboxEventsUrl(
        base = base,
        limit = 30,
        offset = 60,
        unreadOnly = unreadOnly,
        agentId = agentId,
        sourceType = sourceType,
    )

    @Test
    fun `默认参数只带 limit 与 offset`() {
        val url = url()
        assertEquals("30", url.queryParameter("limit"))
        assertEquals("60", url.queryParameter("offset"))
        assertNull(url.queryParameter("unread_only"))
        assertNull(url.queryParameter("agent_id"))
        assertNull(url.queryParameter("source_type"))
    }

    @Test
    fun `来源与智能体各自对应服务端参数名`() {
        val url = url(agentId = "default", sourceType = "cron")
        assertEquals("default", url.queryParameter("agent_id"))
        assertEquals("cron", url.queryParameter("source_type"))
    }

    @Test
    fun `未读筛选带 unread_only`() {
        assertEquals("true", url(unreadOnly = true).queryParameter("unread_only"))
    }

    @Test
    fun `空白筛选值不产生参数`() {
        val url = url(agentId = "  ", sourceType = "")
        assertNull(url.queryParameter("agent_id"))
        assertNull(url.queryParameter("source_type"))
    }

    @Test
    fun `含空格与中文的智能体 id 被编码`() {
        val url = url(agentId = "研究 助手")
        val encoded = url.toString()
        assertTrue(encoded.none { it == ' ' || it.isSurrogate() })
        // 编码由 OkHttp 负责，取值侧解回来应与原始 id 相同。
        assertEquals("研究 助手", url.queryParameter("agent_id"))
    }
}
