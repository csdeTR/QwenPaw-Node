package io.qwenpaw.node.data.stream

import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 备份任务的 SSE。
 *
 * 每一帧都是完整快照；`: heartbeat` 是注释行，不应产生状态；
 * 终态之后流立即结束，避免界面停在「实时更新」。
 */
class BackupJobStreamTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        conn = Connection(server.url("/").toString().trimEnd('/'), null, "default")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun snapshot(
        status: String,
        percent: Int,
        agent: String = "a1",
    ): String = """{"job_id":"j1","backup_id":"b1","status":"$status","phase":"agents","percent":$percent,"current_agent":"$agent","agent_index":1,"total_agents":2,"result":null,"error":null}"""

    @Test
    fun `frames replace state and stop at terminal`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: ${snapshot("running", 10)}\n\n" +
                        ": heartbeat\n\n" +
                        "data: ${snapshot("running", 60, "a2")}\n\n" +
                        "data: ${snapshot("completed", 100)}\n\n" +
                        "data: ${snapshot("running", 99)}\n\n",
                ),
        )

        val frames = BackupJobStreamClient(api, OkHttpClient()).open(conn, "j1").toList()

        assertEquals(3, frames.size)
        assertEquals(10, frames[0].percent)
        assertEquals("a2", frames[1].currentAgent)
        assertEquals("completed", frames[2].status)
        assertTrue(frames[2].isTerminal)
        val request = server.takeRequest()
        assertEquals("/api/backups/jobs/j1/events", request.requestUrl!!.encodedPath)
        assertEquals("text/event-stream", request.getHeader("Accept"))
    }

    @Test
    fun `404 becomes api exception`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"detail":"job not found"}"""),
        )
        try {
            BackupJobStreamClient(api, OkHttpClient()).open(conn, "missing").toList()
            fail("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertEquals(404, e.code)
        }
    }
}
