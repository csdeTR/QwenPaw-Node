package io.qwenpaw.node.data.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 导入备份时的一次上传源。
 *
 * SAF 的 `OpenableColumns.SIZE` 可能缺失，而 OkHttp 的流式请求体需要
 * `Content-Length` 才能给出准确进度。缺失时先把文件复制到 `cacheDir`，
 * 用完由 [cleanup] 删除；大小已知时直接按需打开输入流，不落第二份副本。
 *
 * 每次重试（覆盖确认、信任确认）都要重新上传，因此 [newBody] 可以调用
 * 多次，每次返回一个独立请求体。
 */
class BackupImportSource private constructor(
    val fileName: String,
    private val length: Long,
    private val openStream: () -> InputStream,
    private val tempFile: File?,
) {

    fun newBody(onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> }): RequestBody =
        SafStreamBody(length, openStream, onProgress)

    fun cleanup() {
        tempFile?.delete()
    }

    companion object {
        /**
         * 读取所选 URI 的名称与大小。大小未知时复制到缓存目录并返回临时文件，
         * 复制工作在线程池执行，避免阻塞界面。
         */
        suspend fun prepare(context: Context, uri: Uri): BackupImportSource = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var displayName = "backup.zip"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            displayName = cursor.getString(nameIndex)
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            if (size >= 0) {
                return@withContext BackupImportSource(
                    fileName = displayName,
                    length = size,
                    openStream = {
                        resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
                    },
                    tempFile = null,
                )
            }

            val temp = File.createTempFile("backup-import-", ".zip", context.cacheDir)
            try {
                resolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("无法读取所选文件")
            } catch (e: IOException) {
                temp.delete()
                throw e
            }
            BackupImportSource(
                fileName = displayName,
                length = temp.length(),
                openStream = { FileInputStream(temp) },
                tempFile = temp,
            )
        }

        /** 测试与预置场景用：[openStream] 由调用方提供，不涉及 SAF。 */
        internal fun fromStream(
            fileName: String,
            length: Long,
            openStream: () -> InputStream,
        ): BackupImportSource = BackupImportSource(fileName, length, openStream, null)
    }
}

/**
 * 把输入流写进请求体，并按时间节流上报进度。
 *
 * 进度回调在写线程触发，调用方负责切回主线程。`total` 始终是文件总大小，
 * 不会出现中途变化的情况。
 */
internal class SafStreamBody(
    private val length: Long,
    private val openStream: () -> InputStream,
    private val onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) : RequestBody() {

    override fun contentType(): MediaType = MEDIA

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val input = openStream()
        input.use {
            val buffer = ByteArray(BUFFER_SIZE)
            var readTotal = 0L
            var lastReport = 0L
            onProgress(0L, length)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                readTotal += read
                val now = clock()
                if (now - lastReport >= REPORT_INTERVAL_MS) {
                    lastReport = now
                    onProgress(readTotal, length)
                }
            }
            onProgress(readTotal, length)
        }
    }

    companion object {
        private val MEDIA = "application/zip".toMediaType()
        private const val BUFFER_SIZE = 64 * 1024
        private const val REPORT_INTERVAL_MS = 200L
    }
}
