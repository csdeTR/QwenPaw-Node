package io.qwenpaw.node.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {

    @Test
    fun `100 MB boundary triggers warning`() {
        assertTrue(DownloadPolicy.needsWarning(DownloadPolicy.LARGE_FILE_BYTES, metered = false, vpn = false))
        assertFalse(
            DownloadPolicy.needsWarning(DownloadPolicy.LARGE_FILE_BYTES - 1, metered = false, vpn = false),
        )
    }

    @Test
    fun `metered or vpn triggers even for small or unknown size`() {
        assertTrue(DownloadPolicy.needsWarning(10, metered = true, vpn = false))
        assertTrue(DownloadPolicy.needsWarning(null, metered = false, vpn = true))
        assertTrue(DownloadPolicy.needsWarning(null, metered = true, vpn = true))
    }

    @Test
    fun `warning explains mobile data and relay traffic`() {
        val text = DownloadPolicy.warning(
            sizeBytes = DownloadPolicy.LARGE_FILE_BYTES,
            fileCount = null,
            metered = true,
            vpn = true,
        )
        assertNotNull(text)
        assertTrue(text!!, text.contains("100.0 MB"))
        assertTrue(text, text.contains("移动数据"))
        assertTrue(text, text.contains("EasyTier"))
    }

    @Test
    fun `directory warning includes file count`() {
        val text = DownloadPolicy.warning(
            sizeBytes = 3 * 1024 * 1024L,
            fileCount = 12,
            metered = true,
            vpn = false,
        )
        assertNotNull(text)
        assertTrue(text!!, text.contains("12 个文件"))
        assertTrue(text, text.contains("移动数据"))
    }

    @Test
    fun `no warning when nothing applies`() {
        assertNull(DownloadPolicy.warning(1024, fileCount = 2, metered = false, vpn = false))
    }

    @Test
    fun `format bytes keeps one decimal for MB`() {
        assertEquals("512 B", DownloadPolicy.formatBytes(512))
        assertEquals("2 KB", DownloadPolicy.formatBytes(2048))
        assertEquals("128.4 MB", DownloadPolicy.formatBytes(134_637_158))
        assertEquals("1.5 GB", DownloadPolicy.formatBytes(1_610_612_736))
    }
}
