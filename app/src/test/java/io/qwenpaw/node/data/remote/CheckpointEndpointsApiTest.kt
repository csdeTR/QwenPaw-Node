package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.GcSettingsDto
import io.qwenpaw.node.data.model.RestoreCheckpointRequestDto
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 检查点端点。
 *
 * 服务端用 `asdict` 序列化 `RestoreResult`，Tuple 会变成 JSON 数组；
 * `include_files=true` 且 `files` 为空时返回 400，这是界面必须拦住的输入。
 */
class CheckpointEndpointsApiTest {

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
    fun `graph decodes nodes sessions and summary`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"nodes":[
                     {"ref":"refs/checkpoints/auto/x","kind":"auto","session_key":"k1",
                      "name":"","commit":"abcdef1234567890","sha":"abcdef123456",
                      "timestamp_ms":1762800000000,"subject":"整理文件","channel":"web",
                      "is_head":true,"user_id":"u1","session_id":"s1","session_title":"项目"},
                     {"ref":"refs/checkpoints/pre-restore/y","kind":"pre-restore","session_key":"k1",
                      "commit":"ffff000011112222","sha":"ffff00001111","timestamp_ms":1762800100000,
                      "session_title":"项目"}
                   ],
                   "sessions":[{"session_key":"k1","session_id":"s1","user_id":"u1",
                     "channel":"web","title":"项目","archived":false}],
                   "summary":{"total":2,"auto":1,"snapshots":0,"safety":1,"heads":1},
                   "truncated":false}""",
            ),
        )

        val graph = api.checkpointGraph(conn, limit = 20)

        assertEquals(2, graph.nodes.size)
        assertEquals("pre-restore", graph.nodes[1].kind)
        assertEquals(1762800000000L, graph.nodes[0].timestampMs)
        assertEquals(1, graph.summary.safety)
        assertFalse(graph.truncated)
        val request = server.takeRequest()
        assertEquals("/api/workspace/checkpoints/graph", request.requestUrl!!.encodedPath)
        assertEquals("20", request.requestUrl!!.queryParameter("limit"))
        assertEquals("agent-1", request.getHeader("X-Agent-Id"))
    }

    @Test
    fun `empty graph arrays decode without error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"nodes":[],"sessions":[],"summary":{},"truncated":true}""",
            ),
        )
        val graph = api.checkpointGraph(conn)
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.truncated)
        assertEquals("500", server.takeRequest().requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun `status and auto toggle`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"auto_enabled":true,"has_checkpoints":true,"workspace_dir":"/w"}"""),
        )
        val status = api.checkpointStatus(conn)
        assertTrue(status.autoEnabled)
        assertEquals("/w", status.workspaceDir)
        assertEquals(
            "/api/workspace/checkpoints/status",
            server.takeRequest().requestUrl!!.encodedPath,
        )

        server.enqueue(MockResponse().setBody("""{"auto_enabled":false}"""))
        assertFalse(api.setCheckpointAuto(conn, false))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/workspace/checkpoints/auto", request.requestUrl!!.encodedPath)
        assertEquals("""{"enabled":false}""", request.body.readUtf8())
    }

    @Test
    fun `preview decodes tuple arrays and dry run`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"target":"abcdef1","commit":"abcdef1234567890",
                   "restored_paths":["notes/old.md"],"pre_restore_ref":"refs/checkpoints/pre-restore/z",
                   "dry_run":true,"include_memory":false,"include_files":true,
                   "deleted_paths":["notes/new.md"],"file_paths":["notes/old.md","notes/todo.md"]}""",
            ),
        )

        val result = api.previewCheckpointRestore(
            conn,
            RestoreCheckpointRequestDto(
                commit = "abcdef1234567890",
                sessionId = "s1",
                includeFiles = true,
            ),
        )

        assertTrue(result.dryRun)
        assertEquals("refs/checkpoints/pre-restore/z", result.preRestoreRef)
        assertEquals(listOf("notes/old.md", "notes/todo.md"), result.filePaths)
        assertEquals(listOf("notes/new.md"), result.deletedPaths)
        assertEquals(
            "/api/workspace/checkpoints/restore/preview",
            server.takeRequest().requestUrl!!.encodedPath,
        )
    }

    @Test
    fun `apply without files hits 400 with server detail`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"detail":"Select at least one file before restoring files."}""",
            ),
        )
        try {
            api.applyCheckpointRestore(
                conn,
                RestoreCheckpointRequestDto(
                    commit = "abcdef1234567890",
                    sessionId = "s1",
                    includeFiles = true,
                ),
            )
            fail("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.code)
            assertEquals("Select at least one file before restoring files.", e.detail)
        }
    }

    @Test
    fun `gc settings patch sends all three fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"gc_keep_count":5,"gc_keep_days":10,"pre_restore_retention_days":1}""",
            ),
        )

        val saved = api.updateCheckpointGcSettings(conn, GcSettingsDto(5, 10, 1))

        assertEquals(5, saved.gcKeepCount)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals(
            "/api/workspace/checkpoints/gc/settings",
            request.requestUrl!!.encodedPath,
        )
        assertEquals(
            """{"gc_keep_count":5,"gc_keep_days":10,"pre_restore_retention_days":1}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `snapshot posts session context`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ref":"refs/checkpoints/snap/1","commit":"abc1234def","tree":"t",
                   "parent_commit":null,"timestamp_ms":1762800000000}""",
            ),
        )
        val result = api.createCheckpointSnapshot(
            conn,
            io.qwenpaw.node.data.model.SnapshotRequestDto(
                sessionId = "s1",
                userId = "u1",
                channel = "web",
                name = "整理前",
            ),
        )
        assertEquals("refs/checkpoints/snap/1", result.ref)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"session_id\":\"s1\""))
        assertTrue(body.contains("\"name\":\"整理前\""))
    }

    @Test
    fun `reset uses delete on collection path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"reset":true,"auto_enabled":false}"""))
        assertTrue(api.resetCheckpoints(conn).reset)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/workspace/checkpoints", request.requestUrl!!.encodedPath)
    }
}
