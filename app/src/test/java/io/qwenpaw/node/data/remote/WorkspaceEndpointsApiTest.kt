package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 工作区文件端点。
 *
 * 形状以 `qp-test` 2.2.2.post1 与 `qp-test-prerelease` 2.3.0b2 的实测为准：
 * `tree` 返回 `entries / has_more / next_cursor`，`file-content` 的 `limit` 是字节上限，
 * `memory` 是裸数组，`file-download` 只构造请求。
 */
class WorkspaceEndpointsApiTest {

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

    @Test
    fun `tree sends root path cursor and limit`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"directory":"media","entries":[
                   {"kind":"directory","modified_at":"2026-11-01T01:29:41Z","name":"sub",
                    "path":"media/sub","preview_kind":"directory","size":null},
                   {"kind":"file","modified_at":"2026-11-01T01:29:41Z","name":"a.png",
                    "path":"media/a.png","preview_kind":"image","size":12}],
                   "has_more":true,"next_cursor":"c1"}""",
            ),
        )

        val page = api.workspaceTree(conn, root = "workspace", path = "media", cursor = "c0", limit = 50)

        assertEquals("media", page.directory)
        assertEquals(2, page.entries.size)
        assertEquals("directory", page.entries[0].kind)
        assertEquals("image", page.entries[1].previewKind)
        assertEquals(12L, page.entries[1].size)
        assertTrue(page.hasMore)
        assertEquals("c1", page.nextCursor)

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.startsWith("/api/workspace/tree?"))
        assertTrue(path, path.contains("root=workspace"))
        assertTrue(path, path.contains("path=media"))
        assertTrue(path, path.contains("cursor=c0"))
        assertTrue(path, path.contains("limit=50"))
    }

    @Test
    fun `tree omits blank path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"entries":[],"has_more":false,"next_cursor":null}"""))
        val page = api.workspaceTree(conn, root = "workspace")
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("root=workspace"))
        assertTrue("空 path 不应下发", !path.contains("path="))
        assertNull(page.nextCursor)
    }

    @Test
    fun `file content sends offset and limit and reads eof`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":"你好","encoding":"utf-8","eof":true,"etag":"W/\"1-6\"",
                   "limit":6,"next_offset":6,"offset":0,"path":"MEMORY.md","truncated":false}""",
            ),
        )
        val chunk = api.workspaceFileContent(conn, root = "workspace", path = "MEMORY.md", offset = 0, limit = 64)
        assertEquals("你好", chunk.content)
        assertTrue(chunk.eof)
        assertEquals(6L, chunk.nextOffset)
        assertEquals("W/\"1-6\"", chunk.etag)

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("offset=0"))
        assertTrue(path, path.contains("limit=64"))
    }

    @Test
    fun `memory list decodes bare array`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"filename":"2026-11-07.md","path":"/w/memory/2026-11-07.md","size":1333,
                    "created_time":"2026-11-06T21:25:30Z","modified_time":"2026-11-06T21:25:30Z"}]""",
            ),
        )
        val files = api.workspaceMemory(conn)
        assertEquals(1, files.size)
        assertEquals("2026-11-07.md", files[0].filename)
        assertEquals(1333L, files[0].size)
        assertEquals("/api/workspace/memory", server.takeRequest().path)
    }

    @Test
    fun `memory file encodes nested segments`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":"# 标题"}"""))
        val content = api.workspaceMemoryFile(conn, "2026-11-07/睡眠 检查.md")
        assertEquals("# 标题", content)
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.startsWith("/api/workspace/memory/2026-11-07/"))
        assertTrue("空格应编码", path.contains("%20"))
    }

    @Test
    fun `download request carries agent header and query`() {
        val request = api.workspaceFileDownloadRequest(conn, root = "workspace", path = "media/a.png")
        assertEquals("GET", request.method)
        assertEquals("default", request.header("X-Agent-Id"))
        assertTrue(request.url.encodedPath, request.url.encodedPath == "/api/workspace/file-download")
        assertEquals("workspace", request.url.queryParameter("root"))
        assertEquals("media/a.png", request.url.queryParameter("path"))
    }

    @Test
    fun `browse dirs sends path and show hidden`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"current":"/tmp","parent":"/",
                   "dirs":[{"name":"sub","path":"/tmp/sub"}]}""",
            ),
        )

        val page = api.browseDirs(conn, "/tmp")

        assertEquals("/tmp", page.current)
        assertEquals("/", page.parent)
        assertEquals("sub", page.dirs.single().name)
        assertEquals("/tmp/sub", page.dirs.single().path)

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.startsWith("/api/workspace/project-directory/browse-dirs?"))
        assertTrue(path, path.contains("path=%2Ftmp"))
        // 不传 show_hidden 时服务端可能返回空列表，因此固定为 true。
        assertTrue(path, path.contains("show_hidden=true"))
    }

    @Test
    fun `browse dirs decodes missing parent`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"current":"/","parent":null,"dirs":[]}"""))
        val page = api.browseDirs(conn, "/")
        assertEquals("/", page.current)
        assertNull(page.parent)
        assertTrue(page.dirs.isEmpty())
    }
}
