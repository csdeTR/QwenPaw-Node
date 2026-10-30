package io.qwenpaw.node.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁剪取景框的坐标换算。
 *
 * 这几个函数决定了「保存出来的头像是不是取景框里看到的那块」。
 * 放在 JVM 单测里是因为在 Compose 里没法断言，只能靠肉眼比对。
 */
class CropGeometryTest {

    /** 宽图按短边铺满：1600×800 放进 400 的框，缩放应为 0.5。 */
    @Test
    fun `base scale fills the shorter edge`() {
        assertEquals(0.5f, CropGeometry.baseScale(1600, 800, 400f), 1e-6f)
        assertEquals(0.5f, CropGeometry.baseScale(800, 1600, 400f), 1e-6f)
        // 正方形图直接铺满。
        assertEquals(0.5f, CropGeometry.baseScale(800, 800, 400f), 1e-6f)
    }

    /** 异常输入返回 1 而不是崩溃或除零。 */
    @Test
    fun `base scale handles invalid input`() {
        assertEquals(1f, CropGeometry.baseScale(0, 800, 400f), 1e-6f)
        assertEquals(1f, CropGeometry.baseScale(800, 800, 0f), 1e-6f)
    }

    /** 用户缩放乘在基础缩放上；低于 1 的缩放被夹住，否则框里会露白。 */
    @Test
    fun `user scale is multiplied and clamped`() {
        val scale = CropGeometry.effectiveScale(1600, 800, 400f, 2f)
        assertEquals(1.0f, scale, 1e-6f)
        val shrink = CropGeometry.effectiveScale(1600, 800, 400f, 0.2f)
        // 0.2 被夹到 1，所以等于基础缩放。
        assertEquals(0.5f, shrink, 1e-6f)
    }

    /**
     * 未缩放时，宽图左右仍有可移动余量（800 宽的图片铺满 400 的框，
     * 缩放后宽 800，余量 400，每侧 200）；上下没有余量，纵向拖动必须被夹成 0。
     */
    @Test
    fun `offset clamp keeps image covering frame`() {
        val (x, y) = CropGeometry.clampOffset(
            imageWidth = 1600,
            imageHeight = 800,
            viewSize = 400f,
            userScale = 1f,
            offsetX = 500f,
            offsetY = 500f,
        )
        assertEquals(200f, x, 1e-6f)
        assertEquals(0f, y, 1e-6f)
    }

    @Test
    fun `offset clamp is symmetric`() {
        val (x, y) = CropGeometry.clampOffset(1600, 800, 400f, 1f, -500f, -500f)
        assertEquals(-200f, x, 1e-6f)
        assertEquals(0f, y, 1e-6f)
    }

    /** 未移动、未缩放时，裁剪区域应当居中。 */
    @Test
    fun `crop rect is centered for wide image`() {
        // 1600×800 铺满 400 框 → 缩放 0.5，取景框对应源图 800×800。
        val rect = CropGeometry.cropRect(1600, 800, 400f, 1f, 0f, 0f)
        assertEquals(400, rect[0]) // x
        assertEquals(0, rect[1])   // y
        assertEquals(800, rect[2]) // size
    }

    @Test
    fun `crop rect is centered for tall image`() {
        // 800×1600 铺满 400 框 → 缩放 0.5，取景框对应源图 800×800。
        val rect = CropGeometry.cropRect(800, 1600, 400f, 1f, 0f, 0f)
        assertEquals(0, rect[0])
        assertEquals(400, rect[1])
        assertEquals(800, rect[2])
    }

    /** 区域必须完整落在图片内，否则 `Bitmap.createBitmap` 会抛 IllegalArgumentException。 */
    @Test
    fun `crop rect stays inside image bounds`() {
        val rect = CropGeometry.cropRect(1000, 600, 300f, 1f, 9_999f, -9_999f)
        val (x, y, size) = Triple(rect[0], rect[1], rect[2])
        assertTrue(x >= 0)
        assertTrue(y >= 0)
        assertTrue(size > 0)
        assertTrue(x + size <= 1000)
        assertTrue(y + size <= 600)
    }

    /** 放大后裁剪区域应当变小：这正是「双指放大再保存」的预期。 */
    @Test
    fun `zoom shrinks crop rect`() {
        val normal = CropGeometry.cropRect(1600, 800, 400f, 1f, 0f, 0f)[2]
        val zoomed = CropGeometry.cropRect(1600, 800, 400f, 2f, 0f, 0f)[2]
        assertTrue(zoomed < normal)
    }

    /** 图片比取景框还小时（异常输入）也不能返回 0 或负数。 */
    @Test
    fun `crop rect of tiny image is at least 1`() {
        val rect = CropGeometry.cropRect(10, 10, 400f, 1f, 0f, 0f)
        assertTrue(rect[2] >= 1)
        assertTrue(rect[0] >= 0)
        assertTrue(rect[1] >= 0)
    }
}