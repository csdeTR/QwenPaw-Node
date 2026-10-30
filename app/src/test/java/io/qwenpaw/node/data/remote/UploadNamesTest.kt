package io.qwenpaw.node.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传文件名补全。
 *
 * 对应实机缺陷：相册 uri 末段是 `..._1000000158` 这类无扩展名串，
 * 上传后服务端返回的路径同样没有扩展名，客户端按扩展名判断媒体类型时
 * 把图片当成普通文件发出去。
 */
class UploadNamesTest {

    private val imageExts = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")
    private fun isImage(name: String) = imageExts.any { name.lowercase().endsWith(it) }

    @Test
    fun `display name with extension is kept as is`() {
        assertEquals("IMG_0042.PNG", UploadNames.resolve("IMG_0042.PNG", "x/1", "image/png"))
    }

    @Test
    fun `display name without extension gets one from mime`() {
        val name = UploadNames.resolve("screenshot", null, "image/png")
        assertEquals("screenshot.png", name)
        assertTrue("补全后应能被识别为图片", isImage(name))
    }

    @Test
    fun `media store style segment becomes an image name`() {
        // 这正是系统相册在 Android 13+ 给出的形态。
        val segment = "1000000158"
        val name = UploadNames.resolve(null, segment, "image/jpeg")
        assertEquals("1000000158.jpg", name)
        assertTrue(isImage(name))
    }

    @Test
    fun `unknown mime leaves the name untouched`() {
        assertEquals("blob", UploadNames.resolve(null, "blob", "application/x-thing"))
    }

    @Test
    fun `missing everything falls back to upload`() {
        assertEquals("upload", UploadNames.resolve(null, null, null))
    }

    @Test
    fun `blank display name is ignored`() {
        assertEquals("a.png", UploadNames.resolve("   ", "a", "image/png"))
    }

    @Test
    fun `mime lookup is case insensitive and trimmed`() {
        assertEquals(".png", UploadNames.extensionFor(" IMAGE/PNG "))
        assertEquals("", UploadNames.extensionFor(null))
    }

    @Test
    fun `dotfile like name is not treated as having an extension`() {
        // `.nomedia` 这类以点开头的名字，substringAfterLast 会得到自身，
        // 这里允许它被补成 `.nomedia`，但不应崩溃。
        assertEquals(".nomedia", UploadNames.resolve(".nomedia", null, null))
    }

    @Test
    fun `pdf stays a file`() {
        val name = UploadNames.resolve(null, "1000000159", "application/pdf")
        assertEquals("1000000159.pdf", name)
        assertTrue("PDF 不应被认成图片", !isImage(name))
    }
}