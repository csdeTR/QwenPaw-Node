package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 备份列表、详情、任务与删除端点。
 *
 * 契约以服务端 `app/routers/backup.py` 为准：列表是裸数组，
 * `active` 空闲时返回 JSON `null`，删除请求体是 `{"ids": [...]}`。
 */
class BackupEndpointsApiTest {

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
    fun `list decodes bare array and keeps trust flag`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[
                    {"id":"b1","name":"夜间","created_at":"2026-11-10T23:00:00+08:00",
                     "version":"1","scope":{"include_agents":true,"include_global_config":true,
                     "include_secrets":false,"include_skill_pool":true},
                     "agent_count":2,"qwenpaw_version":"2.2.2.post1","accepted_via_trust":null},
                    {"id":"b2","name":"导入","accepted_via_trust":true}
                ]""",
            ),
        )

        val list = api.backups(conn)

        assertEquals(2, list.size)
        assertEquals("b1", list[0].id)
        assertEquals(2, list[0].agentCount)
        assertNull(list[0].acceptedViaTrust)
        assertEquals(true, list[1].acceptedViaTrust)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/backups", request.requestUrl!!.encodedPath)
        assertEquals("agent-1", request.getHeader("X-Agent-Id"))
    }

    @Test
    fun `start job posts explicit agents and decodes snapshot`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"job_id":"j1","backup_id":"b3","status":"pending","phase":"preparing",
                   "percent":0,"agent_index":0,"total_agents":1,"result":null,"error":null}""",
            ),
        )

        val job = api.startBackupJob(
            conn,
            io.qwenpaw.node.data.model.CreateBackupRequestDto(
                name = "手动",
                scope = io.qwenpaw.node.data.model.BackupScopeDto(),
                agents = listOf("a1"),
            ),
        )

        assertEquals("j1", job.jobId)
        assertEquals("pending", job.status)
        assertEquals(false, job.isTerminal)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"agents\":[\"a1\"]"))
        assertTrue(body.contains("\"name\":\"手动\""))
    }

    @Test
    fun `active job returns null when idle`() = runBlocking {
        server.enqueue(MockResponse().setBody("null"))
        assertNull(api.activeBackupJob(conn))
        assertEquals("/api/backups/jobs/active", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `delete posts ids and decodes failures`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"deleted":["b1"],"failed":[{"id":"b2","error":"正在恢复"}]}""",
            ),
        )

        val result = api.deleteBackups(conn, listOf("b1", "b2"))

        assertEquals(listOf("b1"), result.deleted)
        assertEquals("b2", result.failed.single().id)
        assertEquals("正在恢复", result.failed.single().error)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/backups/delete", request.requestUrl!!.encodedPath)
        assertEquals("""{"ids":["b1","b2"]}""", request.body.readUtf8())
    }

    @Test
    fun `job events request carries auth and accept header`() = runBlocking {
        val connWithToken = conn.copy(token = "t0ken")
        val request = api.backupJobEventsRequest(connWithToken, "j1")
        assertEquals("GET", request.method)
        assertEquals("/api/backups/jobs/j1/events", request.url.encodedPath)
        assertEquals("text/event-stream", request.header("Accept"))
        // 与其他端点一致，token 走 Authorization 头。
        assertEquals("Bearer t0ken", request.header("Authorization"))
    }

    @Test
    fun `detail exposes workspace stats`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"b1","name":"夜间","agent_count":1,
                   "workspace_stats":{"a1":{"files":3,"size":2048}}}""",
            ),
        )
        val detail = api.backupDetail(conn, "b1")
        assertEquals(3, detail.workspaceStats.getValue("a1").files)
        assertEquals(2048L, detail.workspaceStats.getValue("a1").size)
        assertNotNull(detail.scope)
        assertEquals(
            "/api/backups/b1",
            server.takeRequest().requestUrl!!.encodedPath,
        )
    }
}
