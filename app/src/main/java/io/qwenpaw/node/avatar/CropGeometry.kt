package io.qwenpaw.node.avatar

/**
 * 裁剪取景框与源图之间的坐标换算。
 *
 * 抽成纯函数是为了能在 JVM 单测里验证：手势拖动与缩放最容易出「拖到底还能拖」
 * 或「裁剪区域偏出图片」这类问题，而在 Composable 里没法断言。
 *
 * 坐标系约定：
 * - 源图以像素为单位，原点在左上角，宽 [imageWidth] 高 [imageHeight]。
 * - 取景框是边长 [viewSize] 的正方形，原点也在左上角。
 * - 图片默认按「短边铺满取景框」缩放并居中，[userScale] 是在此基础上的额外缩放。
 */
object CropGeometry {

    /** 图片铺满取景框所需的基础缩放。 */
    fun baseScale(imageWidth: Int, imageHeight: Int, viewSize: Float): Float {
        if (imageWidth <= 0 || imageHeight <= 0 || viewSize <= 0f) return 1f
        return maxOf(viewSize / imageWidth, viewSize / imageHeight)
    }

    /** 实际生效的缩放：基础缩放乘用户手势缩放。 */
    fun effectiveScale(
        imageWidth: Int,
        imageHeight: Int,
        viewSize: Float,
        userScale: Float,
    ): Float = baseScale(imageWidth, imageHeight, viewSize) * userScale.coerceAtLeast(1f)

    /**
     * 把平移量限制在合法范围内，使图片始终覆盖整个取景框。
     *
     * 图片铺满后允许左右上下移动的距离 = (显示尺寸 - 取景框) / 2，超出就会出现空白边。
     */
    fun clampOffset(
        imageWidth: Int,
        imageHeight: Int,
        viewSize: Float,
        userScale: Float,
        offsetX: Float,
        offsetY: Float,
    ): Pair<Float, Float> {
        val scale = effectiveScale(imageWidth, imageHeight, viewSize, userScale)
        val maxX = ((imageWidth * scale - viewSize) / 2f).coerceAtLeast(0f)
        val maxY = ((imageHeight * scale - viewSize) / 2f).coerceAtLeast(0f)
        return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
    }

    /**
     * 取景框对应到源图的像素区域。
     *
     * 返回 `intArrayOf(x, y, size)`，`size` 是正方形边长。调用方再把这个区域
     * 缩放到头像输出尺寸。取值全部夹在图片范围内，避免 `createBitmap` 抛异常。
     */
    fun cropRect(
        imageWidth: Int,
        imageHeight: Int,
        viewSize: Float,
        userScale: Float,
        offsetX: Float,
        offsetY: Float,
    ): IntArray {
        val scale = effectiveScale(imageWidth, imageHeight, viewSize, userScale)
        // 图片左上角在取景框坐标系里的位置。
        val left = (viewSize - imageWidth * scale) / 2f + offsetX
        val top = (viewSize - imageHeight * scale) / 2f + offsetY
        // 取景框左上角 (0,0) 映射回源图像素。
        val rawX = (0f - left) / scale
        val rawY = (0f - top) / scale
        val rawSize = viewSize / scale

        val size = rawSize.toInt().coerceIn(1, minOf(imageWidth, imageHeight))
        val x = rawX.toInt().coerceIn(0, imageWidth - size)
        val y = rawY.toInt().coerceIn(0, imageHeight - size)
        return intArrayOf(x, y, size)
    }
}