package io.qwenpaw.node.data.remote

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 技能池管理端点的请求体与响应形状。 */
class SkillPoolApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: QwenPawApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = QwenPawApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun conn() = Connection("http://${server.hostName}:${server.port}", "tk")

    @Test
    fun `create and save pool skill send name content config`() {
        server.enqueue(MockResponse().setBody("""{"created":true,"name":"s1"}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"name":"s1"}"""))

        api.createPoolSkill(conn(), "s1", "---\nname: s1\n---\nbody", null)
        val create = server.takeRequest()
        assertEquals("/api/skills/pool/create", create.requestUrl?.encodedPath)
        assertTrue(create.body.readUtf8().contains("\"content\""))

        api.savePoolSkill(conn(), "s1", "body", "old", null, true)
        val save = server.takeRequest()
        assertEquals("/api/skills/pool/save", save.requestUrl?.encodedPath)
        val saveBody = save.body.readUtf8()
        assertTrue(saveBody.contains("\"source_name\":\"old\""))
        assertTrue(saveBody.contains("\"overwrite\":true"))
    }

    @Test
    fun `batch delete and tags send raw arrays`() {
        server.enqueue(MockResponse().setBody("""{"results":{}}"""))
        server.enqueue(MockResponse().setBody("""{"updated":true}"""))

        api.batchDeletePoolSkills(conn(), listOf("a", "b"))
        api.setPoolSkillTags(conn(), "a", listOf("x", "y"))

        assertEquals(
            """["a","b"]""",
            server.takeRequest().body.readUtf8(),
        )
        assertEquals(
            """["x","y"]""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `automation builds nested auto sync object`() {
        server.enqueue(MockResponse().setBody("""{"updated":true}"""))

        api.setPoolSkillAutomation(conn(), "a", true, true, listOf("default", "qa"))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"auto_update\":true"))
        assertTrue(body.contains("\"auto_sync\""))
        assertTrue(body.contains("\"targets\":[\"default\",\"qa\"]"))
    }

    @Test
    fun `import builtin and update builtin use documented paths`() {
        server.enqueue(MockResponse().setBody("""{"imported":["a"]}"""))
        server.enqueue(MockResponse().setBody("""{"updated":true}"""))

        api.importBuiltinSkills(conn(), listOf("a" to "zh"), true)
        api.updateBuiltinSkill(conn(), "a", "zh")

        val importReq = server.takeRequest()
        assertEquals("/api/skills/pool/import-builtin", importReq.requestUrl?.encodedPath)
        assertTrue(importReq.body.readUtf8().contains("\"overwrite_conflicts\":true"))

        val updateReq = server.takeRequest()
        assertEquals("/api/skills/pool/a/update-builtin", updateReq.requestUrl?.encodedPath)
        assertEquals("""{"language":"zh"}""", updateReq.body.readUtf8())
    }

    @Test
    fun `async download start and poll parse job`() {
        server.enqueue(MockResponse().setBody("""{"job_id":"j1","state":"running"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j1","state":"completed","result":{}}"""))

        val start = api.startPoolDownload(conn(), "s1", listOf("default"), false, false)
        assertEquals("j1", start.jobId)

        val status = api.poolDownloadStatus(conn(), "j1")
        assertEquals("completed", status.state)

        assertEquals("/api/skills/pool/downloads", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/api/skills/pool/downloads/j1", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `workspaces and builtin notice decode`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"agent_id":"default","agent_name":"Default","skill_names":["a"]}]""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"fingerprint":"f","has_updates":true,"total_changes":1,"actionable_skill_names":["a"],"added":[],"missing":[],"updated":[],"removed":[]}"""))

        val workspaces = api.skillWorkspaces(conn())
        assertEquals("Default", workspaces.single().agentName)

        val notice = api.poolBuiltinNotice(conn())
        assertTrue(notice.hasUpdates)
        assertEquals(listOf("a"), notice.actionableSkillNames)
    }

    @Test
    fun `upload from workspace posts body and refresh reconciles`() {
        server.enqueue(MockResponse().setBody("""{"success":true,"name":"a"}"""))
        server.enqueue(MockResponse().setBody("""[]"""))

        api.uploadPoolSkill(conn(), "default", "a", true)
        val upload = server.takeRequest()
        assertEquals("/api/skills/pool/upload", upload.requestUrl?.encodedPath)
        assertTrue(upload.body.readUtf8().contains("\"workspace_id\":\"default\""))

        api.refreshPoolSkills(conn())
        val refresh = server.takeRequest()
        assertEquals("POST", refresh.method)
        assertEquals("/api/skills/pool/refresh", refresh.requestUrl?.encodedPath)
    }
}
