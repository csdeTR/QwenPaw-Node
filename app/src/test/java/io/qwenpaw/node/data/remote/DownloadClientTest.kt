package io.qwenpaw.node.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DownloadClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DownloadClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(path: String = "/download"): Request =
        Request.Builder().url(server.url(path)).build()

    @Test
    fun `writes whole body and reports content length`() = runBlocking {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))

        val sink = ByteArrayOutputStream()
        var reported: Pair<Long, Long?>? = null
        val written = client.download(request(), sink) { read, total -> reported = read to total }

        assertEquals(payload.size.toLong(), written)
        assertTrue(payload.contentEquals(sink.toByteArray()))
        assertEquals(payload.size.toLong(), reported?.first)
        assertEquals(payload.size.toLong(), reported?.second)
    }

    @Test
    fun `chunked response reports unknown total`() = runBlocking {
        val payload = "没有长度".toByteArray()
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(payload), 4))

        val sink = ByteArrayOutputStream()
        var total: Long? = -1
        client.download(request(), sink) { _, length -> total = length }

        assertTrue(payload.contentEquals(sink.toByteArray()))
        assertNull(total)
    }

    /**
     * 取消必须真的停下。用 1 字节 / 100 ms 的慢响应，200 ms 后取消，
     * 写入量应远小于完整长度。
     */
    @Test
    fun `cancellation stops mid stream`() = runBlocking {
        val payload = ByteArray(1000) { 1 }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(1, 100, TimeUnit.MILLISECONDS),
        )

        val sink = ByteArrayOutputStream()
        val job = launch(Dispatchers.IO) { client.download(request(), sink) }
        delay(250)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue("取消后不应写完整文件", sink.size() < payload.size)
    }

    @Test
    fun `server error surfaces detail`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"File not found"}"""))
        try {
            client.download(request(), ByteArrayOutputStream())
            fail("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertEquals(404, e.code)
            assertEquals("File not found", e.detail)
        }
    }

    /**
     * 服务端不支持 Range：中断后重下必须得到与原文件一致的字节。
     * 这里模拟「第一次写到一半失败」，第二次完整下载后对比。
     */
    @Test
    fun `redownload after failure yields identical bytes`() = runBlocking {
        val payload = ByteArray(3000) { (it * 7 % 256).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))

        val failing = object : java.io.OutputStream() {
            private var written = 0
            override fun write(b: Int) {
                if (++written > 100) throw IOException("磁盘已满")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written + len > 100) throw IOException("磁盘已满")
                written += len
            }
        }
        try {
            client.download(request(), failing)
            fail("应当因写入失败而中止")
        } catch (_: IOException) {
        }

        val second = ByteArrayOutputStream()
        client.download(request(), second)
        assertTrue(payload.contentEquals(second.toByteArray()))
    }
}
