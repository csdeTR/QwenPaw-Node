package io.qwenpaw.node.data.remote

import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 服务端头像的拉取与缓存。
 *
 * 三条规则值得钉住：
 * 1. `selected` 为 null 时要清空缓存——否则网页端删掉头像后手机上还留着旧图；
 * 2. 同一个 id 不重复下载——头像上限 2 MB，每次进设置页都拉一遍没有意义；
 * 3. 换 id 后旧文件要被清掉——攒几张就很可观。
 */
class ServerAvatarClientTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var client: ServerAvatarClient
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = File(System.getProperty("java.io.tmpdir"), "qwenpaw-avatar-test-${System.nanoTime()}")
        client = ServerAvatarClient(QwenPawApi(), dir)
        conn = Connection(server.url("/").toString().trimEnd('/'), null, "default")
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun imageResponse() = MockResponse()
        .setHeader("Content-Type", "image/png")
        .setBody(okio.Buffer().write(PNG_BYTES))

    /** 有头像时返回文件，内容与服务端字节一致。 */
    @Test
    fun `downloads selected avatar`() {
        server.enqueue(
            MockResponse().setBody("""{"selected":"a1","history":[{"id":"a1","mime":"image/png"}]}"""),
        )
        server.enqueue(imageResponse())

        val file = client.refresh(conn)
        assertNotNull(file)
        assertArrayEquals(PNG_BYTES, file!!.readBytes())
        // 第一次是清单，第二次是图片。
        assertEquals("/api/profile/avatars", server.takeRequest().path)
        assertEquals("/api/profile/avatars/a1", server.takeRequest().path)
    }

    /** 服务端没有设置头像时返回 null，并且把本地缓存清掉。 */
    @Test
    fun `empty selection clears cache`() {
        server.enqueue(
            MockResponse().setBody("""{"selected":"a1","history":[{"id":"a1","mime":"image/png"}]}"""),
        )
        server.enqueue(imageResponse())
        assertNotNull(client.refresh(conn))

        server.enqueue(MockResponse().setBody("""{"selected":null,"history":[]}"""))
        assertNull(client.refresh(conn))
        assertNull(client.cached())
    }

    /** 同一个 id 再次刷新不再下载图片，只用一次清单请求。 */
    @Test
    fun `same id reuses cached bytes`() {
        server.enqueue(
            MockResponse().setBody("""{"selected":"a1","history":[{"id":"a1","mime":"image/png"}]}"""),
        )
        server.enqueue(imageResponse())
        client.refresh(conn)
        server.takeRequest()
        server.takeRequest()

        val before = server.requestCount
        server.enqueue(
            MockResponse().setBody("""{"selected":"a1","history":[{"id":"a1","mime":"image/png"}]}"""),
        )
        assertNotNull(client.refresh(conn))
        assertEquals(1, server.requestCount - before)
    }

    /** 换 id 后旧文件被清理，目录里只留当前这张与索引。 */
    @Test
    fun `switching id prunes old file`() {
        server.enqueue(
            MockResponse().setBody("""{"selected":"a1","history":[{"id":"a1","mime":"image/png"}]}"""),
        )
        server.enqueue(imageResponse())
        client.refresh(conn)

        server.enqueue(
            MockResponse().setBody("""{"selected":"a2","history":[{"id":"a2","mime":"image/png"}]}"""),
        )
        server.enqueue(imageResponse())
        client.refresh(conn)

        val names = dir.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(setOf("a2.img", "index.txt"), names)
    }

    /** 冷启动不联网也能拿到上次的头像。 */
    @Test
    fun `cached file is available without network`() {
        server.enqueue(
            MockResponse().setBody("""{"selected":"a1","history":[{"id":"a1","mime":"image/png"}]}"""),
        )
        server.enqueue(imageResponse())
        client.refresh(conn)

        val fresh = ServerAvatarClient(QwenPawApi(), dir)
        assertNotNull(fresh.cached())
        assertTrue(fresh.cached()!!.name == "a1.img")
    }

    /** 服务端返回空字节时视为没有头像，避免留下 0 字节的坏文件。 */
    @Test
    fun `empty body is treated as no avatar`() {
        server.enqueue(
            MockResponse().setBody("""{"selected":"a1","history":[{"id":"a1","mime":"image/png"}]}"""),
        )
        server.enqueue(MockResponse().setBody(""))
        assertNull(client.refresh(conn))
        assertNull(client.cached())
    }

    private companion object {
        /** 只验证「字节原样落盘」，内容不必是合法 PNG。 */
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03,
        )
    }
}