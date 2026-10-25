package io.qwenpaw.node.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/** SSE 帧读取测试：覆盖标准帧、CRLF、无 data 前缀行与末尾无空行。 */
class SseReaderTest {

    private fun readerOf(text: String) = SseReader(BufferedReader(StringReader(text)))

    @Test
    fun `reads single data frame`() {
        val reader = readerOf("data: {\"a\":1}\n\n")
        assertEquals("{\"a\":1}", reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test
    fun `reads multiple frames in order`() {
        val reader = readerOf("data: one\n\ndata: two\n\n")
        assertEquals("one", reader.nextFrame())
        assertEquals("two", reader.nextFrame())
    }

    @Test
    fun `handles crlf line endings`() {
        val reader = readerOf("data: {\"a\":1}\r\n\r\n")
        assertEquals("{\"a\":1}", reader.nextFrame())
    }

    @Test
    fun `joins multiple data lines in one frame`() {
        val reader = readerOf("data: line1\ndata: line2\n\n")
        assertEquals("line1\nline2", reader.nextFrame())
    }

    @Test
    fun `flushes trailing frame without blank line`() {
        val reader = readerOf("data: tail")
        assertEquals("tail", reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test
    fun `ignores non data fields`() {
        val reader = readerOf("event: message\nid: 1\nretry: 3000\ndata: payload\n\n")
        assertEquals("payload", reader.nextFrame())
    }

    @Test
    fun `skips consecutive blank lines`() {
        val reader = readerOf("\n\n\ndata: x\n\n")
        assertEquals("x", reader.nextFrame())
    }

    @Test
    fun `empty stream returns null`() {
        assertNull(readerOf("").nextFrame())
    }

    @Test
    fun `frame content is not truncated`() {
        val big = "x".repeat(50_000)
        val reader = readerOf("data: $big\n\n")
        val frame = reader.nextFrame()
        assertTrue(frame != null && frame.length == 50_000)
    }
}
