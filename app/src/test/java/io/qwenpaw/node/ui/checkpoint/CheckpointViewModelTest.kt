package io.qwenpaw.node.ui.checkpoint

import io.qwenpaw.node.data.model.CheckpointNodeDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 检查点页面的状态流转。
 *
 * 刷新时图谱与清理设置并发请求，用按路径分发的 dispatcher 固定响应，
 * 避免依赖请求到达顺序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var vm: CheckpointViewModel
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty().substringBefore("?")
                return routes[path]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val conn = Connection(server.url("/").toString().trimEnd('/'), null, "agent-1")
        vm = CheckpointViewModel(api = QwenPawApi(), connection = { conn })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private suspend fun await(predicate: (CheckpointUiState) -> Boolean): CheckpointUiState =
        withTimeout(5_000) { vm.state.first(predicate) }

    private fun ok(body: String): () -> MockResponse = { MockResponse().setBody(body) }

    private fun statusOk() {
        routes["/api/workspace/checkpoints/status"] = ok(
            """{"auto_enabled":true,"has_checkpoints":true,"workspace_dir":"/w"}""",
        )
    }

    private fun graphOk(vararg commits: String) {
        val nodes = commits.joinToString(",") { commit ->
            """{"ref":"refs/$commit","kind":"auto","session_key":"k1","commit":"$commit",
               "sha":"${commit.take(12)}","timestamp_ms":1762800000000,"session_id":"s1",
               "user_id":"u1","channel":"web","is_head":true,"session_title":"项目"}"""
        }
        routes["/api/workspace/checkpoints/graph"] = ok(
            """{"nodes":[$nodes],
               "sessions":[{"session_key":"k1","session_id":"s1","user_id":"u1",
                 "channel":"web","title":"项目","archived":false}],
               "summary":{"total":${commits.size},"auto":${commits.size},"snapshots":0,
                 "safety":0,"heads":${commits.size}},"truncated":false}""",
        )
    }

    private fun gcSettingsOk() {
        routes["/api/workspace/checkpoints/gc/settings"] = ok(
            """{"gc_keep_count":20,"gc_keep_days":30,"pre_restore_retention_days":7}""",
        )
    }

    private fun node(commit: String) = CheckpointNodeDto(
        ref = "refs/$commit",
        kind = "auto",
        sessionKey = "k1",
        commit = commit,
        sha = commit.take(12),
        timestampMs = 1762800000000,
        sessionId = "s1",
        userId = "u1",
        channel = "web",
    )

    @Test
    fun `status 400 disables writes`() = runBlocking {
        routes["/api/workspace/checkpoints/status"] = {
            MockResponse().setResponseCode(400)
                .setBody("""{"detail":"Workspace is not a git repository"}""")
        }

        vm.refresh()
        val state = await { it.loaded }

        assertTrue(state.writesDisabled)
        assertFalse(state.error.isNullOrBlank())
    }

    @Test
    fun `preview then select file then apply`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()
        routes["/api/workspace/checkpoints/restore/preview"] = ok(
            """{"target":"abcdef1","commit":"abcdef1234567890","restored_paths":[],
               "pre_restore_ref":null,"dry_run":true,"include_memory":false,
               "include_files":true,"deleted_paths":[],"file_paths":["a.md","b.md"]}""",
        )

        vm.refresh()
        await { it.loaded }
        vm.openRestore(node("abcdef1234567890"))
        await { it.restorePreview != null }

        vm.setIncludeFiles(true)
        await { it.restorePreview != null && it.restore?.includeFiles == true }

        vm.toggleFile("a.md")
        routes["/api/workspace/checkpoints/restore"] = ok(
            """{"target":"abcdef1","commit":"abcdef1234567890","restored_paths":["a.md"],
               "pre_restore_ref":"refs/pre","dry_run":false,"include_memory":false,
               "include_files":true,"deleted_paths":[],"file_paths":["a.md"]}""",
        )
        graphOk("2389abcdef012345")

        vm.applyRestore()
        val state = await { it.notice != null }

        assertTrue(state.notice!!.contains("已回滚"))
        val apply = requests.last { it.path.orEmpty().endsWith("/checkpoints/restore") }
        val body = apply.body.readUtf8()
        assertTrue(body.contains("\"commit\":\"abcdef1234567890\""))
        assertTrue(body.contains("\"files\":[\"a.md\"]"))
        assertTrue(body.contains("\"session_id\":\"s1\""))
    }

    @Test
    fun `apply is blocked without memory or files`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()
        routes["/api/workspace/checkpoints/restore/preview"] = ok(
            """{"target":"abcdef1","commit":"abcdef1234567890","dry_run":true,
               "file_paths":[],"deleted_paths":[]}""",
        )

        vm.refresh()
        await { it.loaded }
        vm.openRestore(node("abcdef1234567890"))
        await { it.restorePreview != null }
        vm.applyRestore()

        val state = vm.state.value
        assertFalse(state.restoreApplying)
        // 没有任何请求打到执行端点。
        assertTrue(requests.none { it.path.orEmpty() == "/api/workspace/checkpoints/restore" })
    }

    @Test
    fun `gc preview and apply then reload graph`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()
        routes["/api/workspace/checkpoints/gc/preview"] = ok(
            """{"deleted_refs":["r1","r2"],"kept_refs":["r3"],"dry_run":true}""",
        )

        vm.refresh()
        await { it.loaded }
        vm.previewGc()
        await { it.gcPreview != null }

        routes["/api/workspace/checkpoints/gc"] = ok(
            """{"deleted_refs":["r1","r2"],"kept_refs":["r3"],"dry_run":false}""",
        )
        graphOk("abcdef1234567890")
        vm.applyGc()
        val state = await { it.notice != null }

        assertTrue(state.notice!!.contains("已清理"))
        val apply = requests.last { it.path.orEmpty() == "/api/workspace/checkpoints/gc" }
        val body = apply.body.readUtf8()
        assertTrue(body.contains("\"keep_count\":20"))
        assertTrue(body.contains("\"pre_restore_days\":7"))
    }

    @Test
    fun `reset clears checkpoints and reloads`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()

        vm.refresh()
        await { it.loaded }
        routes["/api/workspace/checkpoints"] = ok("""{"reset":true,"auto_enabled":false}""")
        graphOk()

        vm.confirmReset()
        val state = await { it.notice != null && it.groups.isEmpty() }

        assertEquals("已删除全部检查点", state.notice)
        assertFalse(state.autoEnabled)
        assertTrue(state.groups.isEmpty())
        val reset = requests.last { it.method == "DELETE" }
        assertEquals("/api/workspace/checkpoints", reset.path.orEmpty().substringBefore("?"))
    }

    /** 打开只读页：选中会话，名称对话框稍后由只读页触发。 */
    @Test
    fun `open snapshot enters read only view`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()

        vm.refresh()
        await { it.loaded }
        vm.openSnapshot("k1")

        val state = vm.state.value
        assertEquals("s1", state.viewingSession?.sessionId)
        assertEquals("k1", state.viewingSession?.sessionKey)
        assertEquals("", state.snapshotName)
    }

    /** 创建成功后退出只读页并给提示，时间线随之重新加载。 */
    @Test
    fun `snapshot submit leaves read only view`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()

        vm.refresh()
        await { it.loaded }
        vm.openSnapshot("k1")
        routes["/api/workspace/checkpoints/snapshot"] = ok(
            """{"ref":"refs/snap-1","commit":"fedcba9876543210","tree":"t",
               "parent_commit":null,"timestamp_ms":1762800000000}""",
        )

        vm.setSnapshotName("手动")
        vm.submitSnapshot()
        val state = await { it.notice != null }

        assertEquals("已创建检查点", state.notice)
        assertEquals(null, state.viewingSession)
        assertEquals(null, state.snapshotSession)
        val snapshot = requests.first { it.path.orEmpty().contains("/snapshot") }
        assertTrue(snapshot.body.readUtf8().contains("\"name\":\"手动\""))
    }

    /** 失败留在只读页，错误挂在底部操作区，方便直接重试。 */
    @Test
    fun `snapshot failure keeps read only view`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()

        vm.refresh()
        await { it.loaded }
        vm.openSnapshot("k1")
        routes["/api/workspace/checkpoints/snapshot"] =
            { MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}""") }

        vm.submitSnapshot()
        val state = await { it.snapshotError != null }

        assertEquals("s1", state.viewingSession?.sessionId)
        assertNotNull(state.snapshotSession)
        assertFalse(state.snapshotPending)
    }

    /** 取消命名对话框不离开只读页，否则用户再点「创建检查点」就没有目标会话。 */
    @Test
    fun `dismiss keeps session for retry`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()

        vm.refresh()
        await { it.loaded }
        vm.openSnapshot("k1")
        vm.setSnapshotName("草稿")
        vm.dismissSnapshot()

        assertEquals("", vm.state.value.snapshotName)
        assertEquals("s1", vm.state.value.snapshotSession?.sessionId)
        assertEquals("s1", vm.state.value.viewingSession?.sessionId)

        vm.closeSnapshot()
        assertEquals(null, vm.state.value.viewingSession)
        assertEquals(null, vm.state.value.snapshotSession)
    }

    @Test
    fun `invalid gc input never reaches server`() = runBlocking {
        statusOk()
        graphOk("abcdef1234567890")
        gcSettingsOk()

        vm.refresh()
        await { it.loaded }
        vm.updateGcDraft(vm.state.value.gcDraft.copy(keepCount = "abc"))
        vm.saveGcSettings()

        val state = vm.state.value
        assertNotNull(state.gcError)
        assertTrue(requests.none { it.method == "PATCH" && it.path.orEmpty().endsWith("/gc/settings") })
    }
}
