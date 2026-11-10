package io.qwenpaw.node.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传暂存的纯逻辑：文件名清洗与上限判断。
 *
 * 复制到 `cacheDir` 的部分依赖 `ContentResolver`，不在这里测；
 * 临时文件的创建与清理由 `WorkspaceViewModelUploadTest` 覆盖。
 */
class WorkspaceUploadsTest {

    @Test
    fun `sanitize keeps last segment`() {
        assertEquals("passwd", sanitizeUploadName("../../etc/passwd"))
    }

    @Test
    fun `sanitize handles backslashes`() {
        assertEquals("c.md", sanitizeUploadName("a\\b\\c.md"))
    }

    @Test
    fun `sanitize falls back for blank name`() {
        assertEquals("upload", sanitizeUploadName("  "))
    }

    @Test
    fun `sanitize keeps chinese and extension`() {
        assertEquals("报告 2026.md", sanitizeUploadName("报告 2026.md"))
    }

    @Test
    fun `limit is inclusive`() {
        assertFalse(exceedsLimit(1024L * 1024, 1))
        assertTrue(exceedsLimit(1024L * 1024 + 1, 1))
    }

    @Test
    fun `unknown limit never blocks`() {
        assertFalse(exceedsLimit(Long.MAX_VALUE, null))
    }

    @Test
    fun `unknown size never blocks`() {
        assertFalse(exceedsLimit(-1, 1))
    }
}
