package io.qwenpaw.node.data.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编码 → 解码的往返。
 *
 * 这条用例覆盖了扫码链路上最容易悄悄坏掉的一环：二维码里的长令牌在
 * 提高纠错等级后仍要解得出来。相机与相册两条入口共用同一个解码器，
 * 这里只喂裸像素，不依赖 Android 视图。
 */
class QrRoundTripTest {

    @Test
    fun `payload survives encode and decode`() {
        val payload = PairQr.encode(
            listOf("http://10.10.0.211:8088", "https://a.example.com:8443"),
            "eyJzdWIiOiJkZW1vIn0.c2ln",
        )
        val (pixels, size) = QrDecoder.encodeToArgb(payload)
        val decoded = QrDecoder.decodeArgb(pixels, size, size)
        assertEquals(payload, decoded)

        val parsed = PairQr.parse(decoded!!)
        parsed as PairParse.Ok
        assertEquals("eyJzdWIiOiJkZW1vIn0.c2ln", parsed.pair.token)
    }

    @Test
    fun `long token still decodes`() {
        val token = "a".repeat(600) + ".b3J5cHRvZ3JhcGhpYw"
        val payload = PairQr.encode(listOf("http://h:1"), token)
        val (pixels, size) = QrDecoder.encodeToArgb(payload, size = 768)
        val decoded = QrDecoder.decodeArgb(pixels, size, size)
        assertEquals(payload, decoded)
        assertEquals(token, (PairQr.parse(decoded!!) as PairParse.Ok).pair.token)
    }

    @Test
    fun `non qr input returns null instead of throwing`() {
        val blank = IntArray(64 * 64) { -1 }
        assertNull(QrDecoder.decodeArgb(blank, 64, 64))
    }

    /**
     * 相机帧的行尾有填充，必须按 rowStride 裁剪。
     *
     * 旧实现把填充当成像素，图像错行，解出来的是一段乱码：真机表现为
     * 「扫到了，但提示不是配对码」。
     */
    @Test
    fun `yuv frame with row padding decodes`() {
        val payload = PairQr.encode(listOf("http://10.10.0.211:8088"), "tok")
        val (pixels, size) = QrDecoder.encodeToArgb(payload, size = 480)
        val rowStride = size + 13
        val luma = ByteArray(rowStride * size)
        for (row in 0 until size) {
            for (col in 0 until size) {
                val pixel = pixels[row * size + col]
                // 图案只有纯黑（0xFF000000）与纯白（-1），亮度直接按通道取。
                val value = if (pixel == -1) 255 else (pixel and 0xFF)
                luma[row * rowStride + col] = value.toByte()
            }
        }
        val decoded = QrDecoder.decodeYuv(luma, size, size, rowStride)
        assertEquals(payload, decoded)
    }
}