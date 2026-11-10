package io.qwenpaw.node.ui.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 图片预览的上限保护。超过时不再继续读，提示用户直接下载。 */
internal class ImageTooLargeException : IOException("图片超过预览上限")

/**
 * 有上限的内存流。
 *
 * 预览图片不需要把任意大的文件都读进内存；超过 [maxBytes] 立刻抛错，
 * 由 `DownloadClient` 的 `use` 关闭连接。
 */
internal class CappedByteArrayOutputStream(
    private val maxBytes: Int,
) : ByteArrayOutputStream() {

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (size() + len > maxBytes) throw ImageTooLargeException()
        super.write(b, off, len)
    }

    override fun write(b: Int) {
        if (size() + 1 > maxBytes) throw ImageTooLargeException()
        super.write(b)
    }
}

/**
 * 按最长边 [maxEdge] 采样解码。
 *
 * 先只读尺寸再决定 `inSampleSize`，避免一张 8000 px 的照片直接占满堆内存。
 * 解码失败返回 null，由界面提示「可下载后查看」。
 */
internal fun decodeWorkspaceImage(bytes: ByteArray, maxEdge: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
