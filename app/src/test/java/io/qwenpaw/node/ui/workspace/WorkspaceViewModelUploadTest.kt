package io.qwenpaw.node.ui.workspace

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.DownloadClient
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * 上传编排：逐文件请求、部分失败、同名冲突暂停与重试、上限跳过、取消与清理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelUploadTest {

    private lateinit var server: MockWebServer
    private lateinit var vm: WorkspaceViewModel
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        val conn = Connection(server.url("/").toString().trimEnd('/'), null, "default")
        vm = WorkspaceViewModel(
            api = QwenPawApi(),
            connection = { conn },
            downloadClient = DownloadClient(OkHttpClient()),
            network = null,
            decodeImage = { null },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        tempFiles.forEach { it.delete() }
    }

    private fun staged(name: String, bytes: Int = 3): StagedUpload {
        val file = File.createTempFile("upload-test", ".bin")
        file.writeBytes(ByteArray(bytes) { 'a'.code.toByte() })
        tempFiles += file
        return StagedUpload(name, file)
    }

    private fun tree(): MockResponse = MockResponse().setBody(
        """{"entries":[],"has_more":false,"next_cursor":null}""",
    )

    private fun uploaded(name: String, size: Int = 3): MockResponse = MockResponse().setBody(
        """{"files":[{"name":"$name","path":"$name","size":$size,"status":"uploaded"}]}""",
    )

    private fun conflict(vararg names: String): MockResponse {
        val list = names.joinToString(",") { "\"$it\"" }
        return MockResponse().setResponseCode(409)
            .setBody("""{"detail":{"code":"upload_conflict","files":[$list]}}""")
    }

    private suspend fun await(predicate: (WorkspaceUiState) -> Boolean): WorkspaceUiState =
        withTimeout(5_000) { vm.state.first(predicate) }

    @Test
    fun `uploads each file and counts partial failure`() = runBlocking {
        val a = staged("a.txt")
        val b = staged("b.md")
        val c = staged("c.txt")
        server.enqueue(uploaded("a.txt"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        server.enqueue(uploaded("c.txt"))
        server.enqueue(tree())

        vm.upload(listOf(a, b, c))
        val upload = await { it.upload?.phase == UploadPhase.DONE }.upload!!

        assertEquals(2, upload.succeeded)
        assertEquals(1, upload.failed)
        assertEquals(0, upload.skipped)
        assertTrue(upload.message.orEmpty().contains("失败 1 个"))
        assertFalse(a.file.exists())
        assertFalse(b.file.exists())
        assertFalse(c.file.exists())

        val first = server.takeRequest()
        assertTrue(first.path.orEmpty().contains("/api/workspace/file-upload"))
        assertTrue(first.body.readUtf8().contains("filename=\"a.txt\""))
        server.takeRequest()
        server.takeRequest()
        assertTrue("结束后应刷新当前目录", server.takeRequest().path.orEmpty().contains("/api/workspace/tree"))
    }

    @Test
    fun `conflict pauses and overwrite retries only conflicted`() = runBlocking {
        val a = staged("a.txt")
        val b = staged("b.md")
        server.enqueue(uploaded("a.txt"))
        server.enqueue(conflict("b.md"))

        vm.upload(listOf(a, b))
        val paused = await { it.upload?.phase == UploadPhase.WAITING_CONFLICT }.upload!!
        assertEquals(listOf("b.md"), paused.conflicts)
        assertEquals(1, paused.succeeded)

        server.enqueue(uploaded("b.md"))
        server.enqueue(tree())
        vm.resolveUploadConflict(io.qwenpaw.node.data.remote.UploadConflictPolicy.OVERWRITE)

        val done = await { it.upload?.phase == UploadPhase.DONE }.upload!!
        assertEquals(2, done.succeeded)
        assertTrue(done.conflicts.isEmpty())

        server.takeRequest()
        server.takeRequest()
        val retry = server.takeRequest()
        assertTrue(retry.body.readUtf8().contains("filename=\"b.md\""))
        assertEquals("overwrite", retry.requestUrl!!.queryParameter("conflict"))
    }

    @Test
    fun `skip retry counts skipped`() = runBlocking {
        val a = staged("a.txt")
        server.enqueue(conflict("a.txt"))
        vm.upload(listOf(a))
        await { it.upload?.phase == UploadPhase.WAITING_CONFLICT }

        server.enqueue(
            MockResponse().setBody("""{"files":[{"name":"a.txt","path":"a.txt","size":3,"status":"skipped"}]}"""),
        )
        server.enqueue(tree())
        vm.resolveUploadConflict(io.qwenpaw.node.data.remote.UploadConflictPolicy.SKIP)

        val done = await { it.upload?.phase == UploadPhase.DONE }.upload!!
        assertEquals(1, done.skipped)
        assertEquals(0, done.succeeded)
    }

    @Test
    fun `oversize is skipped without request`() = runBlocking {
        val uploadPaths = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/settings/upload-limit") ->
                        MockResponse().setBody("""{"upload_max_size_mb":1}""")

                    path.startsWith("/api/workspace/tree") ->
                        MockResponse().setBody("""{"entries":[],"has_more":false,"next_cursor":null}""")

                    path.startsWith("/api/workspace/file-upload") -> {
                        uploadPaths += path
                        uploaded("small.txt", 1024)
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        vm.ensureLoaded()
        await { it.loaded && it.uploadLimitMb == 1L }

        val big = staged("big.bin", bytes = 2 * 1024 * 1024)
        val small = staged("small.txt", bytes = 1024)
        vm.upload(listOf(big, small))

        val done = await { it.upload?.phase == UploadPhase.DONE }.upload!!
        assertEquals(1, done.skipped)
        assertEquals(1, done.succeeded)
        assertEquals(1, uploadPaths.size)
        assertFalse(big.file.exists())
        assertFalse(small.file.exists())
    }

    @Test
    fun `cancel marks cancelled and deletes staged`() = runBlocking {
        val a = staged("a.txt")
        server.enqueue(uploaded("a.txt").setBodyDelay(800, java.util.concurrent.TimeUnit.MILLISECONDS))
        vm.upload(listOf(a))
        await { it.upload?.phase == UploadPhase.RUNNING }

        vm.cancelUpload()
        val cancelled = await { it.upload?.phase == UploadPhase.CANCELLED }.upload!!
        assertEquals("上传已取消", cancelled.message)
        assertFalse(a.file.exists())
    }

    @Test
    fun `clear removes upload state and staged files`() = runBlocking {
        val a = staged("a.txt")
        server.enqueue(uploaded("a.txt").setBodyDelay(800, java.util.concurrent.TimeUnit.MILLISECONDS))
        vm.upload(listOf(a))
        await { it.upload?.phase == UploadPhase.RUNNING }

        vm.clear()
        assertNull(vm.state.value.upload)
        assertFalse(a.file.exists())
    }

    @Test
    fun `upload is blocked while downloading`() {
        val state = WorkspaceUiState(
            download = DownloadState(title = "x", phase = DownloadPhase.RUNNING),
        )
        assertFalse(state.canUpload)
    }
}
