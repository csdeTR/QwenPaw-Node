package io.qwenpaw.node.ui.backup

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.DownloadClient
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.stream.BackupJobStreamClient
import io.qwenpaw.node.ui.workspace.DownloadSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * 备份页面的状态流转。
 *
 * 这一层最容易出问题的是时序：SSE 终态后要刷新列表、409 要给人话、
 * 导出失败要删除半成品。网络一律走 MockWebServer，SSE 直接喂字符串帧。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var vm: BackupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        val conn = Connection(server.url("/").toString().trimEnd('/'), null, "agent-1")
        val api = QwenPawApi()
        vm = BackupViewModel(
            api = api,
            connection = { conn },
            downloadClient = DownloadClient(OkHttpClient()),
            jobStream = BackupJobStreamClient(api, OkHttpClient()),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private suspend fun await(predicate: (BackupUiState) -> Boolean): BackupUiState =
        withTimeout(5_000) { vm.state.first(predicate) }

    private fun listBody(vararg names: String): String = names.joinToString(",", "[", "]") { name ->
        """{"id":"$name","name":"$name","agent_count":1}"""
    }

    private fun enqueueLoad(backups: String = "[]", active: String = "null") {
        server.enqueue(MockResponse().setBody(backups))
        server.enqueue(MockResponse().setBody("""{"agents":[{"id":"a1","name":"主智能体"}]}"""))
        server.enqueue(MockResponse().setBody(active))
    }

    private fun job(status: String, percent: Int = 0): String =
        """{"job_id":"j1","backup_id":"b1","status":"$status","phase":"agents","percent":$percent,"agent_index":0,"total_agents":1,"result":null,"error":null}"""

    @Test
    fun `load fills backups and agents`() = runBlocking {
        enqueueLoad(listBody("b1"))
        vm.refresh()
        val state = await { it.loaded }
        assertEquals(1, state.backups.size)
        assertEquals("主智能体", state.agents.single().name)
        assertNull(state.error)
    }

    @Test
    fun `create conflict shows actionable message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody("""{"detail":"backup job already running"}"""),
        )
        vm.toggleCreateAgent("a1")
        vm.startCreate()
        val state = await { it.createError != null }
        assertTrue(state.createError!!.contains("已有备份任务或恢复正在进行"))
        assertFalse(state.creating)
    }

    @Test
    fun `completed stream refreshes list and shows notice`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody(job("running", 5)))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: ${job("running", 40)}\n\n" +
                        ": heartbeat\n\n" +
                        "data: ${job("completed", 100)}\n\n",
                ),
        )
        enqueueLoad(listBody("b1"))

        vm.toggleCreateAgent("a1")
        vm.startCreate()
        val state = await { it.notice == "备份已完成" && it.backups.isNotEmpty() }

        assertEquals("completed", state.job?.status)
        assertFalse(state.jobStreaming)
        assertEquals(1, state.backups.size)
    }

    @Test
    fun `export failure discards part file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"磁盘已满"}"""))
        val sink = RecordingSink()

        vm.export("b1", sink)
        val state = await { it.exportError != null }

        assertTrue(sink.discarded)
        assertTrue(state.exportError!!.contains("导出失败"))
    }

    @Test
    fun `delete failure keeps target and reports reason`() = runBlocking {
        vm.requestDelete(io.qwenpaw.node.data.model.BackupMetaDto(id = "b1", name = "夜间"))
        server.enqueue(
            MockResponse().setBody("""{"deleted":[],"failed":[{"id":"b1","error":"备份正在使用"}]}"""),
        )
        vm.confirmDelete()
        val state = await { !it.deleting }
        assertTrue(state.deleteTarget != null)
        assertTrue(state.error!!.contains("备份正在使用"))
    }

    private class RecordingSink : DownloadSink {
        var discarded = false
        private val out = ByteArrayOutputStream()

        override suspend fun open(): OutputStream = out

        override suspend fun discard() {
            discarded = true
        }
    }
}
