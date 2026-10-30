package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只发附件时补的用户侧占位。
 *
 * 对应实机发现的缺陷：只选一张图 / 一个文件直接发送，
 * 聊天页顶部直接是「处理中」，本地没有任何用户气泡。
 */
class AttachmentPlaceholderTest {

    private val imageExts = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")
    private fun isImage(name: String): Boolean =
        imageExts.any { name.lowercase().endsWith(it) }

    @Test
    fun `text only adds nothing`() {
        assertTrue(filePlaceholders(emptyList(), "你好", ::isImage).isEmpty())
    }

    @Test
    fun `text with attachment adds nothing`() {
        // 有正文时正文自身就是用户气泡，无需占位。
        val out = filePlaceholders(listOf("/x/a.png"), "看图", ::isImage)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `image only adds no placeholder`() {
        // 图片已经以缩略图气泡呈现，再加一行文字会重复。
        val out = filePlaceholders(listOf("/x/a.png", "/x/b.jpg"), "", ::isImage)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `file only gets one line per file`() {
        val out = filePlaceholders(listOf("/x/report.pdf", "/x/data.csv"), "", ::isImage)
        assertEquals(listOf("[文件] report.pdf", "[文件] data.csv"), out)
    }

    @Test
    fun `mixed attachment only lists the non-image ones`() {
        val out = filePlaceholders(listOf("/x/a.png", "/x/b.pdf"), "", ::isImage)
        assertEquals(listOf("[文件] b.pdf"), out)
    }

    @Test
    fun `empty text and empty attachments still yields a placeholder`() {
        // send() 会提前拦下这种输入，但保留兜底以免将来改动后静默丢消息。
        assertEquals(listOf("[附件]"), filePlaceholders(emptyList(), "  ", ::isImage))
    }

    @Test
    fun `server path without extension is treated as file`() {
        // 上传后的服务端路径可能是 `..._1000000158` 这类无扩展名串，
        // 这种情况按文件处理并给出可见文字，至少不会静默。
        val out = filePlaceholders(listOf("/media/abc_1000000158"), "", ::isImage)
        assertEquals(listOf("[文件] abc_1000000158"), out)
    }
}