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

/** 离线识别模型的下载与安装状态。 */
sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Installing : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * 离线语音识别模型的管理。
 *
 * 模型来自 sherpa-onnx 官方发布的流式中文模型，下载后解压到应用私有目录，
 * 识别全程在本机完成，不走网络。
 */
class SpeechModelManager(private val context: Context) {

    private val _state = MutableStateFlow<ModelState>(
        if (StreamingRecognizer.isReady(installedDir())) ModelState.Ready else ModelState.NotInstalled,
    )
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private fun root(): File = File(context.filesDir, "voice").apply { mkdirs() }

    /** 已解压的模型目录，识别时从这里读文件。 */
    fun installedDir(): File = File(root(), "streaming-zh")

    /** 下载并解压。重复调用会从头开始。 */
    suspend fun download(url: String = MODEL_URL) = withContext(Dispatchers.IO) {
        val target = installedDir()
        val archive = File(root(), "model.tar.bz2")
        try {
            _state.value = ModelState.Downloading(0f)
            val request = Request.Builder().url(url).build()
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
                                _state.value = ModelState.Downloading(
                                    (written.toFloat() / total).coerceIn(0f, 1f),
                                )
                            }
                        }
                    }
                }
            }
            _state.value = ModelState.Installing
            target.deleteRecursively()
            target.mkdirs()
            extract(archive, target)
            archive.delete()
            if (target.listFiles().isNullOrEmpty()) {
                throw IllegalStateException("模型包内容为空")
            }
            if (!StreamingRecognizer.isReady(target)) {
                throw IllegalStateException("模型文件不全，重新下载一次")
            }
            _state.value = ModelState.Ready
        } catch (e: Exception) {
            archive.delete()
            File(root(), "model.tar").delete()
            target.deleteRecursively()
            _state.value = ModelState.Failed(e.message ?: "下载没有完成")
        }
    }

    /** 删除已下载的模型，释放空间。 */
    fun remove() {
        installedDir().deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

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
    }
}
