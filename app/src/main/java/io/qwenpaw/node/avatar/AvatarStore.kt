package io.qwenpaw.node.avatar

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 头像是否有可显示的图片。 */
enum class AvatarKind { NONE, STATIC, ANIMATED }

/**
 * 本地头像存储。
 *
 * 头像只服务于本机界面，服务端没有消息级头像接口，因此**存在应用私有目录里、不上传**。
 *
 * 两类头像共用一套文件规则：`filesDir/avatars/{user|agent}.png`（静态）或 `.gif`（动图）。
 * 同一时刻只会有一个文件存在，切换类型时先删旧文件，避免 `kindFor` 的分支判断依赖时间戳。
 *
 * 动图处理：GIF 原样落盘，由界面通过 [imageLoader] 逐帧播放。
 * 之所以不转成 PNG，是因为转换会直接丢掉动画；原样保存也让用户之后换成别的
 * 尺寸或帧率的 GIF 时不必重新走一遍裁剪流程。
 */
class AvatarStore(private val context: Context) {

    private val _version = MutableStateFlow(0L)

    /** 任何一次写入或删除都会 +1，界面据此重新读取图片。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    /**
     * 头像专用图片加载器。
     *
     * 默认的 `ImageLoader` 只能解出 GIF 的第一帧，必须显式注册逐帧解码组件：
     * - API 28 及以上用 [ImageDecoderDecoder]，走系统 `ImageDecoder`，动画由框架驱动；
     * - API 26–27 用 [GifDecoder]，用 Coil 自带的 `Movie` 实现逐帧播放。
     *
     * 两种都能动，差异只在解码路径。minSdk 是 26，所以必须保留后者这条分支。
     */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    private fun dir(): File = File(context.filesDir, "avatars").apply { mkdirs() }

    private fun staticFile(kind: String): File = File(dir(), "$kind.png")

    private fun animatedFile(kind: String): File = File(dir(), "$kind.gif")

    /** 现有文件的类型。两种文件都不存在时返回 [AvatarKind.NONE]。 */
    fun kindFor(kind: String): AvatarKind = when {
        animatedFile(kind).isFile -> AvatarKind.ANIMATED
        staticFile(kind).isFile -> AvatarKind.STATIC
        else -> AvatarKind.NONE
    }

    /** 头像文件路径。没有头像时返回 null。 */
    fun fileFor(kind: String): File? = when (kindFor(kind)) {
        AvatarKind.ANIMATED -> animatedFile(kind)
        AvatarKind.STATIC -> staticFile(kind)
        AvatarKind.NONE -> null
    }

    /**
     * 保存静态头像。
     *
     * 512 以上要先缩到 [MAX_SIZE]，否则每帧解码一张 4000px 的图会让滚动直接掉帧。
     * 这里用 PNG 而不是 JPEG：头像多为带透明背景的截图，JPEG 会把透明区染成黑色。
     */
    fun saveStatic(kind: String, bitmap: Bitmap): Boolean {
        val scaled = scaleDown(bitmap)
        return runCatching {
            clear(kind, notify = false)
            staticFile(kind).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            _version.value = _version.value + 1
            true
        }.getOrDefault(false)
    }

    /** 保存动图。原样落盘，不做任何解码与缩放。 */
    fun saveAnimated(kind: String, bytes: ByteArray): Boolean = runCatching {
        clear(kind, notify = false)
        animatedFile(kind).writeBytes(bytes)
        _version.value = _version.value + 1
        true
    }.getOrDefault(false)

    /** 删除头像，同时清掉另一种格式的残留文件。 */
    fun clear(kind: String, notify: Boolean = true) {
        staticFile(kind).delete()
        animatedFile(kind).delete()
        if (notify) _version.value = _version.value + 1
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIZE) return bitmap
        val ratio = MAX_SIZE.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    companion object {
        const val KIND_USER = "user"
        const val KIND_AGENT = "agent"

        /** 头像最长边上限。裁剪页输出 500×500，这里只是兜住异常输入。 */
        const val MAX_SIZE = 500

        /** 裁剪页输出的正方形尺寸。 */
        const val OUTPUT_SIZE = 500
    }
}