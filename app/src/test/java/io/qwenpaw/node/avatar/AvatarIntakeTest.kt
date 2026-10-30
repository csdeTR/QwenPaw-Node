package io.qwenpaw.node.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片识别：是不是 GIF、采样率怎么定。
 *
 * 这两件都不需要 Android 环境，所以能在 JVM 里测。
 * 尤其是 GIF 判定——只认 MIME 会把某些文件管理器报
 * `application/octet-stream` 的动图误判成静态图，接着被裁成一帧静止图。
 */
class AvatarIntakeTest {

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `detects gif89a`() {
        // "GIF89a" 的 ASCII 码
        assertTrue(AvatarIntake.isGif(bytesOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x00)))
    }

    @Test
    fun `detects gif87a`() {
        assertTrue(AvatarIntake.isGif(bytesOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)))
    }

    /** PNG 头必须判成非 GIF，否则会被当成动图原样保存，裁剪页就打不开了。 */
    @Test
    fun `png is not gif`() {
        assertTrue(!AvatarIntake.isGif(bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    }

    @Test
    fun `jpeg is not gif`() {
        assertTrue(!AvatarIntake.isGif(bytesOf(0xFF, 0xD8, 0xFF, 0xE0)))
    }

    /** 短于文件头长度时不能越界读取。 */
    @Test
    fun `short input is not gif`() {
        assertTrue(!AvatarIntake.isGif(ByteArray(0)))
        assertTrue(!AvatarIntake.isGif(bytesOf(0x47, 0x49)))
    }

    // ---------------- 采样率 ----------------

    /**
     * 采样后最长边必须仍不小于目标的两倍，留出缩放余量。
     * 4000×3000 的图、目标 500，最多能除到 1000，所以 sample = 2。
     */
    @Test
    fun `sample size leaves margin above target`() {
        val sample = AvatarIntake.sampleSizeFor(4000, 3000, 500)
        assertEquals(2, sample)
        assertTrue(4000 / sample >= 500 * 2)
    }

    /** 图片本来就小的时候不采样。 */
    @Test
    fun `small image is not downsampled`() {
        assertEquals(1, AvatarIntake.sampleSizeFor(600, 600, 500))
        assertEquals(1, AvatarIntake.sampleSizeFor(500, 500, 500))
    }

    @Test
    fun `very large image gets larger sample`() {
        val sample = AvatarIntake.sampleSizeFor(12000, 9000, 500)
        assertTrue(sample >= 4)
        assertTrue(12000 / sample >= 500 * 2)
    }

    /** 异常输入一律返回 1，避免 0 采样率导致解码失败。 */
    @Test
    fun `invalid input returns sample 1`() {
        assertEquals(1, AvatarIntake.sampleSizeFor(0, 0, 500))
        assertEquals(1, AvatarIntake.sampleSizeFor(4000, 3000, 0))
    }
}