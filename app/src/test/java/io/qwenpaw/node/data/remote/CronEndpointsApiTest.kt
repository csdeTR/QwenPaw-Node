package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 定时任务端点。
 *
 * 路径全部走全局前缀加 `X-Agent-Id`，不拼 agent 段。
 * `PUT` 的 body 必须把 `meta` 与 `request` 原样带回。
 */
class CronEndpointsApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var conn: Connection

    private val specJson = """
        {"id":"job-1","name":"日报","enabled":true,
         "schedule":{"type":"cron","cron":"0 9 * * mon","timezone":"Asia/Shanghai"},
         "task_type":"text","text":"早",
         "dispatch":{"type":"channel","channel":"console",
                     "target":{"user_id":"u","session_id":"s"},"mode":"stream","silent":false},
         "save_result_to_inbox":false,
         "runtime":{"max_concurrency":1,"timeout_seconds":120,
                    "misfire_grace_seconds":600,"share_session":false,"tool_safety":false},
         "meta":{"portability":{"requires_review":true}}}
    """.trimIndent()

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

    @Test
    fun `list parses spec with meta and request`() = runBlocking {
        server.enqueue(MockResponse().setBody("[$specJson]"))
        val job = api.cronJobs(conn).single()
        assertEquals("日报", job.name)
        assertEquals("0 9 * * mon", job.schedule.cron)
        assertEquals("Asia/Shanghai", job.schedule.timezone)
        assertTrue(job.meta.containsKey("portability"))
        assertEquals("/api/cron/jobs", server.takeRequest().path)
    }

    @Test
    fun `state and history use their own paths`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"next_run_at":"2026-11-05T01:00:00Z","last_status":"success"}""",
            ),
        )
        val state = api.cronJobState(conn, "job-1")
        assertEquals("2026-11-05T01:00:00Z", state.nextRunAt)
        assertEquals("/api/cron/jobs/job-1/state", server.takeRequest().path)

        server.enqueue(
            MockResponse().setBody(
                """[{"run_at":"2026-11-05T01:00:00Z","status":"success","trigger":"manual"}]""",
            ),
        )
        val record = api.cronJobHistory(conn, "job-1").single()
        assertEquals("manual", record.trigger)
        assertEquals("/api/cron/jobs/job-1/history", server.takeRequest().path)
    }

    @Test
    fun `create posts the spec body with timezone`() = runBlocking {
        server.enqueue(MockResponse().setBody(specJson))
        val saved = api.createCronJob(
            conn,
            io.qwenpaw.node.data.model.CronJobSpecDto(
                name = "日报",
                taskType = "text",
                text = "早",
                schedule = io.qwenpaw.node.data.model.CronScheduleDto(cron = "0 9 * * 1"),
            ),
        )
        assertEquals("job-1", saved.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/cron/jobs", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(
            "Asia/Shanghai",
            body["schedule"]?.jsonObject?.get("timezone")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `actions use their verb paths`() = runBlocking {
        listOf("pause", "resume", "run", "promote").forEach { action ->
            server.enqueue(MockResponse().setBody("""{"${action}d":true}"""))
            when (action) {
                "pause" -> api.pauseCronJob(conn, "job-1")
                "resume" -> api.resumeCronJob(conn, "job-1")
                "run" -> api.runCronJob(conn, "job-1")
                else -> api.promoteCronJob(conn, "job-1")
            }
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/cron/jobs/job-1/$action", req.path)
        }
    }

    @Test
    fun `user timezone is read only`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"timezone":"America/New_York"}"""))
        assertEquals("America/New_York", api.userTimezone(conn))
        assertEquals("/api/config/user-timezone", server.takeRequest().path)
    }
}