package io.qwenpaw.node.data.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 从相机帧或相册图片里解出二维码文本。
 *
 * 解码放在这里而不是扫码页里，是为了能在 JVM 单测里跑「编码 → 解码」的往返：
 * CameraX 的 `ImageProxy` 与 `Bitmap` 都造不出来，而这两个输入都可以退化成
 * 裸的亮度数组。
 */
object QrDecoder {

    /**
     * 相机预览帧。取 Y 平面即可，二维码识别不需要色度。
     *
     * [rowStride] 是每行实际占用的字节数，通常大于 [width]（行尾有填充）。
     * 必须按它构造数据宽度再裁剪，否则图像会错行，解出的是一段乱码
     * （表现为「扫到了但不是配对码」）。
     */
    fun decodeYuv(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? {
        if (width <= 0 || height <= 0) return null
        if (rowStride < width || yPlane.size < rowStride * height) return null
        return decodeSource(
            PlanarYUVLuminanceSource(
                yPlane, rowStride, height, 0, 0, width, height, false,
            ),
        )
    }

    /** 相册图片。按 ARGB 取亮度。 */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? =
        decodeSource(RGBLuminanceSource(width, height, pixels))

    private fun decodeSource(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    // 静态配对码密度偏高（地址 + 长令牌），不开这些选项时
                    // 手机在稍远处就会解不出来。
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.ALSO_INVERTED to true,
                ),
            )
        }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /** 自测与界面预览用：把一段文本编成灰度像素，喂回 [decodeArgb]。 */
    internal fun encodeToArgb(text: String, size: Int = 512): Pair<IntArray, Int> {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.MARGIN to 1,
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
        )
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text,
            com.google.zxing.BarcodeFormat.QR_CODE,
            size,
            size,
            hints,
        )
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix[x, y]) 0xFF000000.toInt() else -1
            }
        }
        return pixels to matrix.width
    }
}