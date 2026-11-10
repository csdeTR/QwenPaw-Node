package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 工作区写入端点：保存（`PUT`）与上传（`POST`，multipart）。
 *
 * 契约以 `qp-test` 2.2.2.post1 与 `qp-test-prerelease` 2.3.0b2 的实测为准：
 * 保存体是 `{"content": "..."}`，`If-Match` 做字符串全等比较；
 * 上传的表单字段名是 `files`，冲突时 409 的 `detail` 是对象而不是字符串。
 */
class WorkspaceWriteApiTest {

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
    fun `save sends json body path and exact if match`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"etag":"W/\"2-5\"","path":"a.md","size":5}"""),
        )

        val result = api.workspaceSaveFileContent(
            conn,
            root = "workspace",
            path = "a.md",
            content = "你好",
            etag = "W/\"1-3\"",
        )

        assertEquals("W/\"2-5\"", result.etag)
        assertEquals("a.md", result.path)
        assertEquals(5L, result.size)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("W/\"1-3\"", request.getHeader("If-Match"))
        assertTrue(request.getHeader("Content-Type").orEmpty().contains("application/json"))
        assertEquals("""{"content":"你好"}""", request.body.readUtf8())
        val url = request.requestUrl!!
        assertEquals("/api/workspace/file-content", url.encodedPath)
        assertEquals("workspace", url.queryParameter("root"))
        assertEquals("a.md", url.queryParameter("path"))
    }

    @Test
    fun `save without etag omits if match`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"etag":"W/\"9-1\"","path":"a.md","size":9}"""))

        api.workspaceSaveFileContent(conn, "workspace", "a.md", "覆盖", etag = null)

        val request = server.takeRequest()
        assertNull("强制覆盖不应带 If-Match", request.getHeader("If-Match"))
        assertEquals("""{"content":"覆盖"}""", request.body.readUtf8())
    }

    @Test
    fun `save conflict surfaces api exception`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody("""{"detail":"File changed on disk"}"""),
        )

        try {
            api.workspaceSaveFileContent(conn, "workspace", "a.md", "x", "W/\"old\"")
            fail("期望 409")
        } catch (e: ApiException) {
            assertEquals(409, e.code)
            assertTrue(e.detail.orEmpty(), e.detail.orEmpty().contains("File changed on disk"))
        }
    }

    @Test
    fun `save validation error passes through`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody("""{"detail":"content must be a string"}"""),
        )

        try {
            api.workspaceSaveFileContent(conn, "workspace", "a.md", "x", null)
            fail("期望 422")
        } catch (e: ApiException) {
            assertEquals(422, e.code)
        }
    }

    @Test
    fun `upload uses files field and conflict policy`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"files":[{"name":"a.txt","path":"docs/a.txt","size":3,"status":"uploaded"}]}""",
            ),
        )
        val file = File.createTempFile("probe", ".txt").apply { writeText("abc") }
        try {
            val result = api.workspaceFileUpload(
                conn,
                root = "workspace",
                path = "docs",
                file = file,
                fileName = "a.txt",
                conflict = UploadConflictPolicy.OVERWRITE,
            )

            assertEquals("uploaded", result.files.single().status)
            assertEquals(3L, result.files.single().size)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            val url = request.requestUrl!!
            assertEquals("/api/workspace/file-upload", url.encodedPath)
            assertEquals("workspace", url.queryParameter("root"))
            assertEquals("docs", url.queryParameter("path"))
            assertEquals("overwrite", url.queryParameter("conflict"))
            assertTrue(
                request.getHeader("Content-Type").orEmpty().contains("multipart/form-data"),
            )
            val body = request.body.readUtf8()
            assertTrue(body, body.contains("name=\"files\""))
            assertTrue(body, body.contains("filename=\"a.txt\""))
            assertFalse("不应把 cacheDir 里的随机暂存名传上去", body.contains("filename=\"${file.name}\""))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `upload root keeps root and empty path`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"files":[{"name":"a.txt","path":"a.txt","size":3,"status":"uploaded"}]}"""),
        )
        val file = File.createTempFile("probe", ".txt").apply { writeText("abc") }
        try {
            api.workspaceFileUpload(conn, "workspace", "", file, "a.txt")
            val url = server.takeRequest().requestUrl!!
            assertEquals("workspace", url.queryParameter("root"))
            assertEquals("", url.queryParameter("path"))
            assertNull("未指定策略时不应下发", url.queryParameter("conflict"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `upload conflict parses names from object detail`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"detail":{"code":"upload_conflict","files":["a.txt","b.md"]}}""",
            ),
        )
        val file = File.createTempFile("probe", ".txt").apply { writeText("abc") }
        try {
            api.workspaceFileUpload(conn, "workspace", "", file, "a.txt")
            fail("期望冲突")
        } catch (e: UploadConflictException) {
            assertEquals(listOf("a.txt", "b.md"), e.names)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `upload skip policy decodes skipped status`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"files":[{"name":"a.txt","path":"a.txt","size":3,"status":"skipped"}]}"""),
        )
        val file = File.createTempFile("probe", ".txt").apply { writeText("abc") }
        try {
            val result = api.workspaceFileUpload(
                conn, "workspace", "", file, "a.txt", UploadConflictPolicy.SKIP,
            )
            assertEquals("skipped", result.files.single().status)
            assertEquals("skip", server.takeRequest().requestUrl!!.queryParameter("conflict"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `upload limit decodes null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"upload_max_size_mb":null}"""))
        assertNull(api.workspaceUploadLimit(conn))
    }

    @Test
    fun `upload limit decodes number`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"upload_max_size_mb":50}"""))
        assertEquals(50L, api.workspaceUploadLimit(conn))
        assertNotNull(conn)
    }

    @Test
    fun `save encodes chinese and spaces in path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"etag":"W/\"1\"","path":"目录/a b.md","size":1}"""))
        api.workspaceSaveFileContent(conn, "workspace", "目录/a b.md", "x", "W/\"0\"")
        val url = server.takeRequest().requestUrl!!
        assertEquals("目录/a b.md", url.queryParameter("path"))
    }
}
