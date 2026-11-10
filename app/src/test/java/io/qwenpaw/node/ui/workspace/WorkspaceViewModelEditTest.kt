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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import java.util.concurrent.TimeUnit

/**
 * 编辑态与保存。重点是乐观锁：第一次保存必须带打开文件时的 etag，
 * 成功后换成服务端返回的新 etag；409 时草稿不能丢。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelEditTest {

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

    private fun tree(entries: String): MockResponse = MockResponse().setBody(
        """{"entries":[$entries],"has_more":false,"next_cursor":null}""",
    )

    private fun chunk(content: String, etag: String?, eof: Boolean): String = buildJsonObject {
        put("content", content)
        put("etag", etag)
        put("eof", eof)
        put("next_offset", content.length.toLong())
        put("offset", 0)
        put("truncated", !eof)
    }.toString()

    private suspend fun await(predicate: (WorkspaceUiState) -> Boolean): WorkspaceUiState =
        withTimeout(5_000) { vm.state.first(predicate) }

    /** 打开一个文本文件，并把响应排进队列。 */
    private suspend fun openText(
        content: String,
        etag: String? = "W/\"1\"",
        eof: Boolean = true,
    ) {
        server.enqueue(
            tree("""{"kind":"file","name":"a.md","path":"a.md","preview_kind":"text","size":${content.length}}"""),
        )
        server.enqueue(MockResponse().setBody(chunk(content, etag, eof)))
        vm.refresh()
        await { it.loaded }
        vm.openFile(
            WorkspaceEntry("a.md", "a.md", isDir = false, previewKind = "text", size = content.length.toLong(), modifiedAt = null),
        )
        await { (it.preview as? WorkspacePreview.Text)?.loading == false }
    }

    @Test
    fun `edit unavailable before eof`() = runBlocking {
        // 一块正好覆盖首屏的 1 MB，读到目标后停下且 eof 仍为 false。
        val big = "x".repeat(1024 * 1024)
        openText(big, eof = false)
        assertFalse(vm.state.value.canEdit)
        vm.beginEdit()
        assertNull(vm.state.value.edit)
    }

    @Test
    fun `edit available after eof`() = runBlocking {
        openText("hello")
        val state = await { it.preview != null }
        assertTrue(state.canEdit)
        vm.beginEdit()
        val edit = vm.state.value.edit!!
        assertEquals("hello", edit.draft)
        assertEquals("hello", edit.savedText)
        assertFalse(edit.dirty)
    }

    @Test
    fun `memory root is not editable`() {
        val state = WorkspaceUiState(
            root = WorkspaceRoot.MEMORY,
            preview = WorkspacePreview.Text(
                title = "MEMORY.md",
                content = "x",
                eof = true,
                etag = "W/\"1\"",
            ),
        )
        assertFalse(state.canEdit)
        assertEquals("记忆文件不支持编辑", state.editBlockedReason)
    }

    @Test
    fun `large content is not editable`() {
        val state = WorkspaceUiState(
            preview = WorkspacePreview.Text(
                title = "big.md",
                content = "x".repeat(EDIT_MAX_CHARS + 1),
                eof = true,
                etag = "W/\"1\"",
            ),
        )
        assertFalse(state.canEdit)
        assertEquals("文件超过 2 MB，请在服务端编辑", state.editBlockedReason)
    }

    @Test
    fun `missing etag is not editable`() {
        val state = WorkspaceUiState(
            preview = WorkspacePreview.Text(
                title = "a.md",
                content = "x",
                eof = true,
                etag = null,
            ),
        )
        assertFalse(state.canEdit)
        assertEquals("未取得版本信息，暂时无法编辑", state.editBlockedReason)
    }

    @Test
    fun `save uses first etag and advances it`() = runBlocking {
        openText("v1")
        server.enqueue(MockResponse().setBody("""{"etag":"W/\"2\"","path":"a.md","size":2}"""))
        vm.beginEdit()
        vm.updateDraft("v2")
        vm.saveEdit()

        val edit = await { it.edit?.etag == "W/\"2\"" }.edit!!
        assertEquals("v2", edit.savedText)
        assertFalse(edit.dirty)
        assertTrue(edit.saved)
        assertEquals("v2", (vm.state.value.preview as WorkspacePreview.Text).content)

        // 第一个请求是打开文件，第二个是保存。
        server.takeRequest()
        server.takeRequest()
        val save = server.takeRequest()
        assertEquals("W/\"1\"", save.getHeader("If-Match"))
        assertEquals("""{"content":"v2"}""", save.body.readUtf8())
    }

    @Test
    fun `second save uses new etag`() = runBlocking {
        openText("v1")
        server.enqueue(MockResponse().setBody("""{"etag":"W/\"2\"","path":"a.md","size":2}"""))
        server.enqueue(MockResponse().setBody("""{"etag":"W/\"3\"","path":"a.md","size":2}"""))
        vm.beginEdit()
        vm.updateDraft("v2")
        vm.saveEdit()
        await { it.edit?.etag == "W/\"2\"" }

        vm.updateDraft("v3")
        vm.saveEdit()
        val edit = await { it.edit?.etag == "W/\"3\"" }.edit!!
        assertEquals("v3", edit.savedText)

        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        val secondSave = server.takeRequest()
        assertEquals("W/\"2\"", secondSave.getHeader("If-Match"))
    }

    @Test
    fun `conflict keeps draft and flags state`() = runBlocking {
        openText("v1")
        server.enqueue(
            MockResponse().setResponseCode(409).setBody("""{"detail":"File changed on disk"}"""),
        )
        vm.beginEdit()
        vm.updateDraft("mine")
        vm.saveEdit()

        val edit = await { it.edit?.conflict == true }.edit!!
        assertEquals("mine", edit.draft)
        assertEquals("v1", edit.savedText)
        assertNull(edit.error)
    }

    @Test
    fun `reload after conflict replaces draft`() = runBlocking {
        openText("v1")
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"File changed on disk"}"""))
        server.enqueue(MockResponse().setBody(chunk("external", "W/\"9\"", eof = true)))
        vm.beginEdit()
        vm.updateDraft("mine")
        vm.saveEdit()
        await { it.edit?.conflict == true }

        vm.reloadAfterConflict()
        val edit = await { it.edit?.etag == "W/\"9\"" && it.edit?.conflict == false }.edit!!
        assertEquals("external", edit.draft)
        assertEquals("external", edit.savedText)
        assertFalse(edit.dirty)
    }

    @Test
    fun `reload failure reports missing file`() = runBlocking {
        openText("v1")
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"File changed on disk"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"File not found"}"""))
        vm.beginEdit()
        vm.updateDraft("mine")
        vm.saveEdit()
        await { it.edit?.conflict == true }

        vm.reloadAfterConflict()
        val edit = await { it.edit?.error != null }.edit!!
        assertEquals("文件或目录不存在，可能已被删除", edit.error)
        assertFalse(edit.conflict)
    }

    @Test
    fun `force overwrite omits if match`() = runBlocking {
        openText("v1")
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"File changed on disk"}"""))
        server.enqueue(MockResponse().setBody("""{"etag":"W/\"4\"","path":"a.md","size":4}"""))
        vm.beginEdit()
        vm.updateDraft("mine")
        vm.saveEdit()
        await { it.edit?.conflict == true }

        vm.forceOverwrite()
        val edit = await { it.edit?.etag == "W/\"4\"" }.edit!!
        assertEquals("mine", edit.savedText)
        assertFalse(edit.conflict)

        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun `force overwrite conflict again`() = runBlocking {
        openText("v1")
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"File changed on disk"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"File changed on disk"}"""))
        vm.beginEdit()
        vm.updateDraft("mine")
        vm.saveEdit()
        await { it.edit?.conflict == true }
        vm.forceOverwrite()
        val edit = await { it.edit?.conflict == true && it.edit?.saving == false }.edit!!
        assertEquals("mine", edit.draft)
    }

    @Test
    fun `cancel edit discards draft`() = runBlocking {
        openText("v1")
        vm.beginEdit()
        vm.updateDraft("changed")
        vm.cancelEdit()

        assertNull(vm.state.value.edit)
        assertEquals("v1", (vm.state.value.preview as WorkspacePreview.Text).content)
    }

    @Test
    fun `switching root closes editor`() = runBlocking {
        openText("v1")
        vm.beginEdit()
        server.enqueue(tree("""{"kind":"file","name":"b.md","path":"b.md","preview_kind":"text"}"""))
        vm.openRoot(WorkspaceRoot.PROJECT)
        await { it.root == WorkspaceRoot.PROJECT && it.loaded }
        assertNull(vm.state.value.edit)
    }

    @Test
    fun `whitespace is preserved in body`() = runBlocking {
        openText("v1")
        server.enqueue(MockResponse().setBody("""{"etag":"W/\"2\"","path":"a.md","size":8}"""))
        val draft = "a\n\t  b  \n"
        vm.beginEdit()
        vm.updateDraft(draft)
        vm.saveEdit()
        await { it.edit?.etag == "W/\"2\"" }

        server.takeRequest()
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertEquals(draft, kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `download blocks save`() = runBlocking {
        openText("v1")
        vm.beginEdit()
        vm.updateDraft("mine")

        val payload = ByteArray(50_000) { 1 }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(1, 5, TimeUnit.MILLISECONDS),
        )
        vm.requestDownload(
            WorkspaceEntry("big.bin", "big.bin", isDir = false, previewKind = "binary", size = payload.size.toLong(), modifiedAt = null),
        )
        vm.startDownload(TestSink())
        await { it.download?.phase == DownloadPhase.RUNNING }

        vm.saveEdit()
        assertEquals("请等待当前下载结束", vm.state.value.edit!!.error)
        vm.cancelDownload()
        await { it.download?.phase == DownloadPhase.CANCELLED }
        Unit
    }

    @Test
    fun `save error can be retried`() = runBlocking {
        openText("v1")
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        server.enqueue(MockResponse().setBody("""{"etag":"W/\"2\"","path":"a.md","size":2}"""))
        vm.beginEdit()
        vm.updateDraft("v2")
        vm.saveEdit()

        val failed = await { it.edit?.error != null }.edit!!
        assertTrue(failed.dirty)
        assertFalse(failed.saving)

        vm.saveEdit()
        val saved = await { it.edit?.etag == "W/\"2\"" }.edit!!
        assertEquals("v2", saved.savedText)
    }

    private class TestSink : DownloadSink {
        private val buffer = ByteArrayOutputStream()
        override suspend fun open(): OutputStream = buffer
        override suspend fun discard() {
            buffer.reset()
        }
    }
}
