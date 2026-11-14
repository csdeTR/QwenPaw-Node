package io.qwenpaw.node.voice

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.util.concurrent.TimeUnit

/**
 * 端上识别模型的下载与安装（`full` 变体专用）。
 *
 * 模型来自 sherpa-onnx 官方发布的流式中文模型，下载后解压到应用私有目录，
 * 识别全程在本机完成，不走网络。
 */
class SherpaAsr(private val context: Context) : LocalAsr {

    override val available: Boolean = true

    private val _modelState = MutableStateFlow<LocalModelState>(
        if (SherpaEngine.isReady(installedDir())) LocalModelState.Ready else LocalModelState.NotInstalled,
    )
    override val modelState: StateFlow<LocalModelState> = _modelState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .addInterceptor(io.qwenpaw.node.log.LoggingInterceptor(io.qwenpaw.node.log.LogKind.VOICE))
        .build()

    private fun root(): File = File(context.filesDir, "voice").apply { mkdirs() }

    /** 已解压的模型目录，识别时从这里读文件。 */
    fun installedDir(): File = File(root(), "streaming-zh")

    override suspend fun download() = withContext(Dispatchers.IO) {
        val target = installedDir()
        val archive = File(root(), "model.tar.bz2")
        try {
            _modelState.value = LocalModelState.Downloading(0f)
            val request = Request.Builder().url(MODEL_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("下载失败，服务端返回 ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("下载内容为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                _modelState.value = LocalModelState.Downloading(
                                    (written.toFloat() / total).coerceIn(0f, 1f),
                                )
                            }
                        }
                    }
                }
            }
            _modelState.value = LocalModelState.Installing
            target.deleteRecursively()
            target.mkdirs()
            extract(archive, target)
            archive.delete()
            if (target.listFiles().isNullOrEmpty()) {
                throw IllegalStateException("模型包内容为空")
            }
            if (!SherpaEngine.isReady(target)) {
                throw IllegalStateException("模型文件不全，重新下载一次")
            }
            _modelState.value = LocalModelState.Ready
        } catch (e: Exception) {
            archive.delete()
            File(root(), "model.tar").delete()
            target.deleteRecursively()
            _modelState.value = LocalModelState.Failed(e.message ?: "下载没有完成")
        }
    }

    override fun remove() {
        installedDir().deleteRecursively()
        _modelState.value = LocalModelState.NotInstalled
    }

    override fun createEngine(endpointing: Boolean): LocalEngine? =
        SherpaEngine.create(installedDir(), SAMPLE_RATE, endpointing)

    /** 解压 tar.bz2 或 zip。 */
    private fun extract(archive: File, target: File) {
        val header = ByteArray(3)
        archive.inputStream().use { it.read(header) }
        if (header[0] == 'Z'.code.toByte() && header[1] == 'I'.code.toByte()) {
            extractZip(archive, target)
        } else {
            // bzip2 边解边读，不落中间文件
            BZip2CompressorInputStream(archive.inputStream(), true).use { input ->
                extractTar(input, target)
            }
        }
    }

    private fun extractZip(archive: File, target: File) {
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(target, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    /**
     * 解 tar。官方模型包里只有一层子目录和普通文件，
     * 这里统一把文件平铺到 [target]，方便识别时按后缀查找。
     */
    private fun extractTar(raw: java.io.InputStream, target: File) {
        TarArchiveInputStream(raw.buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isFile) continue
                val safeName = entry.name.substringAfterLast('/')
                if (safeName.isBlank()) continue
                val dest = File(target, safeName)
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var left = entry.size
                    while (left > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        left -= read
                    }
                }
            }
        }
    }

    companion object {
        /** sherpa-onnx 官方发布的中英流式识别模型，带标点。 */
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"

        private const val SAMPLE_RATE = 16_000
    }
}