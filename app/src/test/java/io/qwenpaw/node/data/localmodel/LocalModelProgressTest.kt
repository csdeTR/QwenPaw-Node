package io.qwenpaw.node.data.localmodel

import io.qwenpaw.node.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度比例与状态判定。
 *
 * 服务端不返回百分比，`total_bytes` 还可能为 null；界面靠这里决定用确定还是不确定进度条。
 */
class LocalModelProgressTest {

    @Test
    fun `null total means unknown progress`() {
        assertNull(progressFraction(1024, null))
    }

    @Test
    fun `zero total means unknown progress`() {
        assertNull(progressFraction(1024, 0))
    }

    @Test
    fun `normal fraction`() {
        assertEquals(0.25f, progressFraction(25, 100)!!, 0.0001f)
    }

    @Test
    fun `downloaded beyond total is clamped`() {
        assertEquals(1f, progressFraction(150, 100)!!, 0.0001f)
    }

    @Test
    fun `nothing downloaded yet is zero`() {
        assertEquals(0f, progressFraction(0, 100)!!, 0.0001f)
    }

    @Test
    fun `canceling still counts as active`() {
        assertTrue(isActiveDownload("pending"))
        assertTrue(isActiveDownload("downloading"))
        assertTrue(isActiveDownload("canceling"))
        assertTrue(isActiveDownload("DOWNLOADING"))
    }

    @Test
    fun `terminal and idle statuses are not active`() {
        for (status in listOf("idle", "completed", "failed", "cancelled", "")) {
            assertFalse("$status should be inactive", isActiveDownload(status))
        }
    }

    @Test
    fun `every known status has its own label`() {
        assertEquals(R.string.local_models_status_idle, progressLabelRes("idle"))
        assertEquals(R.string.local_models_status_pending, progressLabelRes("pending"))
        assertEquals(R.string.local_models_status_downloading, progressLabelRes("downloading"))
        assertEquals(R.string.local_models_status_canceling, progressLabelRes("canceling"))
        assertEquals(R.string.local_models_status_completed, progressLabelRes("completed"))
        assertEquals(R.string.local_models_status_failed, progressLabelRes("failed"))
        assertEquals(R.string.local_models_status_cancelled, progressLabelRes("cancelled"))
    }

    @Test
    fun `unknown status is not disguised as idle`() {
        assertEquals(R.string.local_models_status_unknown, progressLabelRes("mystery"))
    }
}