package io.qwenpaw.node.data.remote

import io.qwenpaw.node.TestTexts
import io.qwenpaw.node.data.model.AclActionEntryDto
import io.qwenpaw.node.data.model.MailAclActionEntryDto
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 访问控制的响应形状与请求体。 */
class AccessControlApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        TestTexts.install()
        server = MockWebServer()
        server.start()
        conn = Connection(server.url("/").toString().trimEnd('/'), null, "default")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `chat overview parses nested maps and pending`() {
        server.enqueue(
            MockResponse().setBody(
                """{"telegram":{"whitelist":{"u1":{"remark":"老用户","username":"Alice"}},
                    "blacklist":{},"pending":[{"user_id":"u2","channel":"telegram",
                    "timestamp":1700000000.0,"first_message":"你好","remark":"",
                    "username":"Bob"}]}}""",
            ),
        )
        val map = api.aclOverview(conn)
        assertEquals("Alice", map.getValue("telegram").whitelist.getValue("u1").username)
        assertEquals("你好", map.getValue("telegram").pending.single().firstMessage)
        assertEquals("Bob", map.getValue("telegram").pending.single().label)
    }

    @Test
    fun `chat action posts entries body`() {
        server.enqueue(MockResponse().setBody("""{"status":"ok","count":1}"""))
        api.aclAction(
            conn,
            "pending/approve",
            listOf(
                AclActionEntryDto(
                    channel = "telegram",
                    userId = "u2",
                    remark = "同意",
                    username = "Bob",
                ),
            ),
        )
        val request = server.takeRequest()
        assertEquals("/api/access-control/pending/approve", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"channel\":\"telegram\""))
        assertTrue(body.contains("\"user_id\":\"u2\""))
        assertTrue(body.contains("\"remark\":\"同意\""))
    }

    @Test
    fun `mail pending count parses`() {
        server.enqueue(MockResponse().setBody("""{"count":3}"""))
        assertEquals(3, api.mailAclPendingCount(conn))
    }

    @Test
    fun `mail action uses agent id and address`() {
        server.enqueue(MockResponse().setBody("""{"status":"ok","count":1}"""))
        api.mailAclAction(
            conn,
            "pending/deny",
            listOf(MailAclActionEntryDto(agentId = "default", address = "a@b.c")),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"agent_id\":\"default\""))
        assertTrue(body.contains("\"address\":\"a@b.c\""))
        assertTrue(!body.contains("remark"))
    }

    @Test
    fun `mail overview empty object is accepted`() {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(api.mailAclOverview(conn).isEmpty())
    }

    @Test
    fun `mail pending reads sender address and subject`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"sender_address":"a@b.c","agent_id":"default",
                    "display_name":"Alice","subject":"主题","body_preview":"正文",
                    "timestamp":1700000000.0,"remark":""}]""",
            ),
        )
        val entry = api.mailAclPending(conn).single()
        assertEquals("a@b.c", entry.effectiveAddress)
        assertTrue(entry.effectiveFirstMessage.contains("主题"))
        assertTrue(entry.effectiveFirstMessage.contains("正文"))
        assertEquals("default||a@b.c", entry.key)
    }
}
