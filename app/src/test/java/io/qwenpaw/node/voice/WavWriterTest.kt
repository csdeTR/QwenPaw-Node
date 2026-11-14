package io.qwenpaw.node.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * WAV 头的字节级形状。
 *
 * 这组用例存在的理由：头字段写错时**服务端仍会照常接受上传**（它只看文件后缀），
 * 错误要到播放或识别阶段才暴露，而那时已经很难定位到是客户端封装的锅。
 * 因此这里逐字段比对，而不是只断言「文件能打开」。
 */
class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun le32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun ascii(bytes: ByteArray, from: Int, len: Int): String =
        String(bytes, from, len, Charsets.US_ASCII)

    @Test
    fun `header describes 16k mono 16 bit pcm`() {
        val dataSize = 33_000 * 2
        val h = WavWriter.header(16_000, dataSize)

        assertEquals(WavWriter.HEADER_SIZE, h.size)
        assertEquals("RIFF", ascii(h, 0, 4))
        // RIFF 的长度字段是「文件总长减 8」。
        assertEquals((44 - 8 + dataSize).toLong(), le32(h, 4))
        assertEquals("WAVE", ascii(h, 8, 4))
        assertEquals("fmt ", ascii(h, 12, 4))
        assertEquals(16L, le32(h, 16))
        assertEquals(1, le16(h, 20)) // PCM
        assertEquals(1, le16(h, 22)) // 单声道
        assertEquals(16_000L, le32(h, 24))
        assertEquals(16_000L * 2, le32(h, 28)) // byteRate = 采样率 × 声道 × 位宽/8
        assertEquals(2, le16(h, 32)) // blockAlign
        assertEquals(16, le16(h, 34)) // 位宽
        assertEquals("data", ascii(h, 36, 4))
        assertEquals(dataSize.toLong(), le32(h, 40))
    }

    @Test
    fun `empty payload still yields a valid header`() {
        val h = WavWriter.header(16_000, 0)
        assertEquals(WavWriter.HEADER_SIZE, h.size)
        assertEquals(36L, le32(h, 4))
        assertEquals(0L, le32(h, 40))
    }

    /** 采样率变化时字节率与 blockAlign 必须跟着变，否则播放速度会错。 */
    @Test
    fun `byte rate follows sample rate`() {
        val h = WavWriter.header(8_000, 100)
        assertEquals(8_000L, le32(h, 24))
        assertEquals(16_000L, le32(h, 28))
        assertEquals(2, le16(h, 32))
    }

    /** 补头后原有 PCM 必须原样保留在后面，不能覆盖也不能丢字节。 */
    @Test
    fun `writeHeader prepends and keeps payload`() {
        val file = File(tmp.root, "a.wav")
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        file.writeBytes(payload)

        WavWriter.writeHeader(file, 16_000)

        val bytes = file.readBytes()
        assertEquals(WavWriter.HEADER_SIZE + payload.size, bytes.size)
        assertEquals("RIFF", ascii(bytes, 0, 4))
        assertTrue(bytes.copyOfRange(WavWriter.HEADER_SIZE, bytes.size).contentEquals(payload))
        assertEquals(payload.size.toLong(), le32(bytes, 40))
    }

    @Test
    fun `writeHeader on empty file writes header only`() {
        val file = File(tmp.root, "empty.wav")
        file.writeBytes(ByteArray(0))

        WavWriter.writeHeader(file, 16_000)

        assertEquals(WavWriter.HEADER_SIZE.toLong(), file.length())
        assertEquals(0L, le32(file.readBytes(), 40))
    }
}