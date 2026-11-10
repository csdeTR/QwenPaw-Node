package io.qwenpaw.node.ui.workspace

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.DownloadClient
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var vm: WorkspaceViewModel

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
    }

    private fun tree(
        entries: String,
        hasMore: Boolean = false,
        next: String? = null,
    ): MockResponse = MockResponse().setBody(
        """{"entries":[$entries],"has_more":$hasMore,"next_cursor":${next?.let { "\"$it\"" } ?: "null"}}""",
    )

    private suspend fun await(
        predicate: (WorkspaceUiState) -> Boolean,
    ): WorkspaceUiState = withTimeout(5_000) { vm.state.first(predicate) }

    @Test
    fun `refresh loads entries and directory flag`() = runBlocking {
        server.enqueue(
            tree(
                """{"kind":"directory","name":"media","path":"media","preview_kind":"directory","size":null},
                   {"kind":"file","name":"a.png","path":"a.png","preview_kind":"image","size":12}""",
            ),
        )
        vm.refresh()
        val state = await { it.loaded }
        assertEquals(2, state.entries.size)
        assertTrue(state.entries[0].isDir)
        assertEquals("image", state.entries[1].previewKind)
        assertEquals(12L, state.entries[1].size)
        assertNull(state.error)
    }

    @Test
    fun `loadMore appends second page`() = runBlocking {
        server.enqueue(tree("""{"kind":"file","name":"a","path":"a","preview_kind":"text"}""", hasMore = true, next = "c1"))
        server.enqueue(tree("""{"kind":"file","name":"b","path":"b","preview_kind":"text"}"""))
        vm.refresh()
        await { it.loaded }
        vm.loadMore()
        val state = await { it.entries.size == 2 && !it.loadingMore }
        assertEquals(listOf("a", "b"), state.entries.map { it.name })
        assertTrue(!state.hasMore)
    }

    @Test
    fun `text preview reads until eof and keeps etag`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":"你好","etag":"W/\"1\"","eof":false,"next_offset":6,"offset":0,"truncated":true}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"content":"世界","etag":"W/\"1\"","eof":true,"next_offset":12,"offset":6,"truncated":false}""",
            ),
        )
        val entry = WorkspaceEntry("MEMORY.md", "MEMORY.md", isDir = false, previewKind = "text", size = 12, modifiedAt = null)
        vm.openFile(entry)

        val preview = await {
            (it.preview as? WorkspacePreview.Text)?.eof == true
        }.preview as WorkspacePreview.Text
        assertEquals("你好世界", preview.content)
        assertEquals("W/\"1\"", preview.etag)
        assertEquals(12L, preview.nextOffset)
    }

    @Test
    fun `not found uses file wording`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"File not found"}"""))
        vm.refresh()
        val state = await { it.error != null }
        assertEquals("文件或目录不存在，可能已被删除", state.error)
    }

    @Test
    fun `large file needs confirmation`() {
        val entry = WorkspaceEntry(
            name = "big.bin",
            path = "big.bin",
            isDir = false,
            previewKind = "binary",
            size = DownloadPolicy.LARGE_FILE_BYTES,
            modifiedAt = null,
        )
        vm.requestDownload(entry)
        val plan = vm.state.value.plan as DownloadPlan.Single
        assertNotNull(plan.warning)
    }

    @Test
    fun `single download writes bytes and finishes`() = runBlocking {
        val payload = ByteArray(4096) { (it % 97).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val entry = WorkspaceEntry("a.bin", "a.bin", isDir = false, previewKind = "binary", size = payload.size.toLong(), modifiedAt = null)
        val sink = FakeSink()

        vm.requestDownload(entry)
        vm.startDownload(sink)
        val state = await { it.download?.phase == DownloadPhase.DONE }

        assertTrue(payload.contentEquals(sink.bytes.toByteArray()))
        assertEquals("已保存到所选位置", state.download?.message)
        assertEquals(payload.size.toLong(), state.download?.read)
    }

    @Test
    fun `cancel marks download cancelled and discards partial`() = runBlocking {
        val payload = ByteArray(50_000) { 1 }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(1, 5, TimeUnit.MILLISECONDS),
        )
        val entry = WorkspaceEntry("a.bin", "a.bin", isDir = false, previewKind = "binary", size = payload.size.toLong(), modifiedAt = null)
        val sink = FakeSink()

        vm.requestDownload(entry)
        vm.startDownload(sink)
        delay(100)
        vm.cancelDownload()
        val state = await { it.download?.phase == DownloadPhase.CANCELLED }

        assertEquals("下载已取消", state.download?.message)
        assertTrue(sink.discarded)
        assertTrue(sink.bytes.size() < payload.size)
    }

    @Test
    fun `directory plan lists files with relative paths`() = runBlocking {
        server.enqueue(
            tree(
                """{"kind":"directory","name":"root","path":"root","preview_kind":"directory"},
                   {"kind":"file","name":"top.txt","path":"top.txt","preview_kind":"text","size":1}""",
            ),
        )
        server.enqueue(
            tree(
                """{"kind":"directory","name":"sub","path":"root/sub","preview_kind":"directory"},
                   {"kind":"file","name":"a.txt","path":"root/a.txt","preview_kind":"text","size":10},
                   {"kind":"file","name":"b.txt","path":"root/b.txt","preview_kind":"text","size":null}""",
            ),
        )
        server.enqueue(
            tree(
                """{"kind":"directory","name":"sub","path":"root/sub","preview_kind":"directory"},
                   {"kind":"file","name":"a.txt","path":"root/a.txt","preview_kind":"text","size":10},
                   {"kind":"file","name":"b.txt","path":"root/b.txt","preview_kind":"text","size":null}""",
            ),
        )
        server.enqueue(
            tree("""{"kind":"file","name":"c.txt","path":"root/sub/c.txt","preview_kind":"text","size":5}"""),
        )

        vm.refresh()
        val loaded = await { it.loaded }
        vm.enter(loaded.entries.first { it.isDir })
        await { it.path == "root" && it.loaded }
        vm.requestDirectoryDownload()
        val plan = await { it.plan != null }.plan as DownloadPlan.Directory

        assertEquals(3, plan.files.size)
        assertEquals(listOf("a.txt", "b.txt", "sub/c.txt"), plan.files.map { it.relative })
        assertNull("有文件体积未知时合计为 null", plan.totalBytes)
    }

    @Test
    fun `memory root lists markdown and reads content`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"filename":"2026-11-07.md","path":"/w/memory/2026-11-07.md","size":1333,
                    "created_time":"2026-11-06T21:25:30Z","modified_time":"2026-11-06T21:25:30Z"}]""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"content":"# 记忆"}"""))

        vm.openRoot(WorkspaceRoot.MEMORY)
        val loaded = await { it.loaded }
        assertEquals("2026-11-07.md", loaded.entries[0].name)
        assertEquals("text", loaded.entries[0].previewKind)

        vm.openFile(loaded.entries[0])
        val preview = await {
            (it.preview as? WorkspacePreview.Text)?.eof == true
        }.preview as WorkspacePreview.Text
        assertEquals("# 记忆", preview.content)
        assertEquals("不支持下载的根不提供目录下载", false, vm.state.value.canDownloadDirectory)
    }

    private class FakeSink : DownloadSink {
        val bytes = ByteArrayOutputStream()
        var discarded = false

        override suspend fun open() = bytes

        override suspend fun discard() {
            discarded = true
            bytes.reset()
        }
    }
}
