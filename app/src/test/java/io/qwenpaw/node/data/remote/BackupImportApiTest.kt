package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
 * 导入备份的 multipart、冲突与签名错误。
 *
 * 服务端的三个关键行为：
 * - 表单字段名是 `file`，确认覆盖时改为只带 `pending_token`；
 * - 重名 409 的 body 是 `{detail, existing, pending_token}`；
 * - 签名不可信 400 的 `detail` 是对象，带 `code` 与 `message`。
 */
class BackupImportApiTest {

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

    private fun body(bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        bytes.toRequestBody("application/zip".toMediaType())

    private val metaJson =
        """{"id":"b1","name":"导入的备份","agent_count":1,"accepted_via_trust":true}"""

    @Test
    fun `upload uses file field and filename`() = runBlocking {
        server.enqueue(MockResponse().setBody(metaJson))

        val meta = api.importBackup(conn, body(), "night.zip")

        assertEquals("b1", meta.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/backups/import", request.requestUrl!!.encodedPath)
        val multipart = request.body.readUtf8()
        assertTrue(multipart.contains("name=\"file\""))
        assertTrue(multipart.contains("filename=\"night.zip\""))
        assertFalse(multipart.contains("pending_token"))
    }

    @Test
    fun `conflict carries existing and pending token`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"detail":"backup_conflict",
                   "existing":{"id":"b1","name":"旧备份","agent_count":3},
                   "pending_token":"tok-9"}""",
            ),
        )

        try {
            api.importBackup(conn, body(), "night.zip")
            fail("应当抛出冲突异常")
        } catch (e: BackupImportConflictException) {
            assertEquals("b1", e.existing.id)
            assertEquals("旧备份", e.existing.name)
            assertEquals(3, e.existing.agentCount)
            assertEquals("tok-9", e.pendingToken)
        }
    }

    @Test
    fun `pending token retry does not resend file`() = runBlocking {
        server.enqueue(MockResponse().setBody(metaJson))

        api.importBackup(conn, null, pendingToken = "tok-9")

        val multipart = server.takeRequest().body.readUtf8()
        assertTrue(multipart.contains("name=\"pending_token\""))
        assertTrue(multipart.contains("tok-9"))
        assertFalse(multipart.contains("name=\"file\""))
    }

    @Test
    fun `legacy signature maps to trust exception`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"detail":{"code":"backup_legacy_unsigned","message":"备份未签名"}}""",
            ),
        )

        try {
            api.importBackup(conn, body(), "old.zip")
            fail("应当抛出签名异常")
        } catch (e: BackupTrustException) {
            assertEquals("backup_legacy_unsigned", e.code)
            assertEquals("备份未签名", e.serverMessage)
        }
    }

    @Test
    fun `trust mode is sent when retrying`() = runBlocking {
        server.enqueue(MockResponse().setBody(metaJson))

        api.importBackup(conn, body(), "foreign.zip", trustMode = "foreign")

        val multipart = server.takeRequest().body.readUtf8()
        assertTrue(multipart.contains("name=\"trust_mode\""))
        assertTrue(multipart.contains("foreign"))
    }

    @Test
    fun `plain 400 stays api exception`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"detail":"文件不是有效的备份包"}"""),
        )

        try {
            api.importBackup(conn, body(), "bad.zip")
            fail("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.code)
            assertEquals("文件不是有效的备份包", e.detail)
        }
    }
}
