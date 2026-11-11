package io.qwenpaw.node.data.remote

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 导入上传体。
 *
 * 长度已知时直接流式读取，不落缓存；重试（覆盖、信任）时每次都要拿到
 * 一个能重新读完的请求体，因此 `newBody` 必须独立打开输入流。
 */
class BackupImportBodyTest {

    private val payload = ByteArray(150_000) { (it % 251).toByte() }

    @Test
    fun `stream body reports length and writes all bytes`() {
        var lastRead = -1L
        val body = SafStreamBody(
            length = payload.size.toLong(),
            openStream = { ByteArrayInputStream(payload) },
            onProgress = { read, total ->
                lastRead = read
                assertEquals(payload.size.toLong(), total)
            },
            clock = { 0L },
        )

        assertEquals(payload.size.toLong(), body.contentLength())
        assertEquals("application/zip", body.contentType().toString())
        val sink = Buffer()
        body.writeTo(sink)
        assertArrayEquals(payload, sink.readByteArray())
        assertEquals(payload.size.toLong(), lastRead)
    }

    @Test
    fun `progress is throttled by clock`() {
        var reports = 0
        var now = 0L
        val body = SafStreamBody(
            length = payload.size.toLong(),
            openStream = { ByteArrayInputStream(payload) },
            onProgress = { _, _ -> reports++ },
            clock = {
                now += 1000
                now
            },
        )
        body.writeTo(Buffer())
        // 首帧 + 每 200ms 一次 + 结束帧；150 KB 分三块读，报告次数远小于字节数。
        assertTrue(reports in 2..5)
    }

    @Test
    fun `source can produce independent bodies for retries`() {
        val source = BackupImportSource.fromStream(
            fileName = "night.zip",
            length = payload.size.toLong(),
        ) { ByteArrayInputStream(payload) }

        val first = Buffer().also { source.newBody().writeTo(it) }.readByteArray()
        val second = Buffer().also { source.newBody().writeTo(it) }.readByteArray()

        assertEquals("night.zip", source.fileName)
        assertArrayEquals(payload, first)
        assertArrayEquals(payload, second)
        source.cleanup()
    }
}
