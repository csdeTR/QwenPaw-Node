package io.qwenpaw.node.font

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** MiSans 字体的下载与安装状态。 */
sealed interface FontState {
    data object NotInstalled : FontState
    data class Downloading(val progress: Float) : FontState
    data object Ready : FontState
    data class Failed(val message: String) : FontState
}

/**
 * MiSans 字体管理。
 *
 * 字体体积约 15.8MB（两个字重各约 7.9MB），全部打进 APK 会让包体无谓变大，
 * 因此改为**按需下载**：默认用系统字体，用户在设置里主动下载后才会用上 MiSans。
 *
 * 字体文件来自 GitHub 上的第三方镜像仓库 `adivenxnataly/MiSans`，
 * 版权归小米科技有限责任公司所有，遵循 MiSans 字体知识产权许可协议
 * （允许免费商用，但**禁止再分发字体本体**）——所以这里只在运行时下载，
 * 不把字体文件放进本仓库、也不随 APK 分发。
 */
class FontManager(private val context: Context) {

    private val _state = MutableStateFlow<FontState>(
        if (isReady()) FontState.Ready else FontState.NotInstalled,
    )
    val state: StateFlow<FontState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .addInterceptor(io.qwenpaw.node.log.LoggingInterceptor(io.qwenpaw.node.log.LogKind.STORAGE))
        .build()

    private fun root(): File = File(context.filesDir, "fonts").apply { mkdirs() }

    private fun regularFile(): File = File(root(), REGULAR_FILE)
    private fun mediumFile(): File = File(root(), MEDIUM_FILE)

    fun isReady(): Boolean = regularFile().let { it.isFile && it.length() > MIN_SIZE } &&
        mediumFile().let { it.isFile && it.length() > MIN_SIZE }

    /** 构造 Compose 字体族；字体没下载时返回 null，交给系统默认字体。 */
    fun family(): FontFamily? {
        if (!isReady()) return null
        return FontFamily(
            Font(regularFile(), FontWeight.Normal),
            Font(mediumFile(), FontWeight.Medium),
        )
    }

    /**
     * 下载两个字重到应用私有目录。
     *
     * 先都下到 `.tmp`，两个都成功后再原子改名，避免下载中途失败留下半个字体
     * 让下次启动误判为「已安装」。
     */
    suspend fun download() = withContext(Dispatchers.IO) {
        try {
            _state.value = FontState.Downloading(0f)
            val targets = listOf(
                REGULAR_URL to File(root(), "$REGULAR_FILE.tmp"),
                MEDIUM_URL to File(root(), "$MEDIUM_FILE.tmp"),
            )
            targets.forEachIndexed { index, (url, tmp) ->
                fetch(url, tmp) { fraction ->
                    // 两个字重各占一半进度。
                    _state.value = FontState.Downloading((index + fraction) / targets.size)
                }
            }
            // 全部下完再落位。
            File(root(), "$REGULAR_FILE.tmp").renameTo(regularFile())
            File(root(), "$MEDIUM_FILE.tmp").renameTo(mediumFile())
            if (!isReady()) throw IllegalStateException("字体文件不完整，重新下载一次")
            _state.value = FontState.Ready
        } catch (e: Exception) {
            // 清掉半成品，保持「未安装」状态。
            File(root(), "$REGULAR_FILE.tmp").delete()
            File(root(), "$MEDIUM_FILE.tmp").delete()
            regularFile().delete()
            mediumFile().delete()
            _state.value = FontState.Failed(e.message ?: "下载没有完成")
        }
    }

    private fun fetch(url: String, target: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败，返回 ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("下载内容为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }

    /** 删除已下载字体，回到系统默认字体。 */
    fun remove() {
        root().deleteRecursively()
        _state.value = FontState.NotInstalled
    }

    companion object {
        private const val REGULAR_FILE = "misans_regular.ttf"
        private const val MEDIUM_FILE = "misans_medium.ttf"

        /** 小于这个体积一律视为损坏（正常单个字重约 7.9MB）。 */
        private const val MIN_SIZE = 4L * 1024 * 1024

        /**
         * 字体来源：GitHub 上的 MiSans 镜像仓库。
         *
         * 上游为小米官方发布的 MiSans，版权归小米科技有限责任公司，
         * 遵循 MiSans 字体知识产权许可协议（免费商用，禁止再分发字体本体）。
         */
        private const val BASE =
            "https://raw.githubusercontent.com/adivenxnataly/MiSans/main/MiSans"

        /** MiSans-Static.ttf 的 usWeightClass = 400，对应 Regular。 */
        private const val REGULAR_URL = "$BASE/MiSans-Static.ttf"

        /** MiSans.ttf 的 usWeightClass = 500，对应 Medium。 */
        private const val MEDIUM_URL = "$BASE/MiSans.ttf"

        /** 字体来源仓库，用于在界面上标注出处。 */
        const val SOURCE_REPO = "adivenxnataly/MiSans"
    }
}