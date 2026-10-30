package io.qwenpaw.node.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/** 用户选中的头像图片，已解码并纠正方向。 */
data class AvatarSource(
    /** 是否 GIF 动图。动图原样保存，不裁剪。 */
    val animated: Boolean,
    /** 解码后的位图；动图时是首帧。 */
    val bitmap: Bitmap?,
    /** 原始字节，保存动图时直接落盘。 */
    val bytes: ByteArray,
)

/**
 * 把用户选中的图片读成可裁剪或可保存的形态。
 *
 * 两件事必须在这里做掉，否则后面每处调用都要重复处理：
 *
 * 1. **是不是 GIF。** 只认文件头 `GIF8`，不看 MIME：部分文件管理器给 GIF
 *    报 `application/octet-stream`，认 MIME 会把动图误判成静态图去做裁剪。
 * 2. **按 EXIF 旋转。** 竖拍照片的像素是横着存的，方向写在 EXIF 里；
 *    不旋转的话裁剪页里躺着、保存出来的头像也躺着。
 */
object AvatarIntake {

    /** GIF 文件头，含 GIF87a 与 GIF89a。 */
    private val GIF_MAGIC = "GIF8".toByteArray(Charsets.US_ASCII)

    /**
     * 是否为 GIF。
     *
     * 只比较前 4 个字节：`GIF87a` 与 `GIF89a` 的差异在版本号，对判断无意义。
     */
    fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < GIF_MAGIC.size) return false
        return GIF_MAGIC.indices.all { bytes[it] == GIF_MAGIC[it] }
    }

    /**
     * 解码并纠正方向。
     *
     * [maxSize] 用于限制解码尺寸：直接解一张 4000×3000 的照片要占约 48MB，
     * 而裁剪输出只要 500×500，没必要先全解再缩。这里按 2 的幂做 `inSampleSize`
     * 预采样，保证解码后的最长边不小于 [maxSize] 的两倍，留出缩放余量。
     */
    fun decode(bytes: ByteArray, maxSize: Int = AvatarStore.OUTPUT_SIZE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull() ?: return null

        return runCatching { rotateByExif(bytes, decoded) }.getOrDefault(decoded)
    }

    /** 按 2 的幂计算采样率，使采样后的最长边仍不小于 `maxSize * 2`。 */
    internal fun sampleSizeFor(width: Int, height: Int, maxSize: Int): Int {
        if (width <= 0 || height <= 0 || maxSize <= 0) return 1
        val target = maxSize * 2
        var sample = 1
        // 用严格大于：除以 2 之后刚好等于目标时已经够用，再除一次就没有余量了。
        while (maxOf(width, height) / (sample * 2) > target) {
            sample *= 2
        }
        return sample
    }

    /** 读取 EXIF 方向并旋转/翻转到正向。无方向信息时原样返回。 */
    private fun rotateByExif(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            // 转置与反转置：先翻转再旋转，与 EXIF 规范的定义一致。
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}