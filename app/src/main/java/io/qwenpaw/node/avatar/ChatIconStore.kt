package io.qwenpaw.node.avatar

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.qwenpaw.node.ui.theme.QwenIcons

/** 会话图标的预设项。 */
data class ChatIconPreset(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 会话图标存储。
 *
 * 与 [AvatarStore] 同构，键换成会话 id，两类来源并存：
 * - **预设**：只存 key 到 SharedPreferences 的 `chat_id → key` 映射，不落文件；
 * - **自选图片**：`filesDir/chat-icons/{sha256(chatId)}.png`，500×500 PNG。
 *
 * 文件名用哈希而不是原 id：服务端会话 id 虽多为 uuid，但渠道会话会带冒号与斜杠，
 * 直接拼进路径轻则建不出目录，重则写到目录之外。
 *
 * 优先级固定为「图片 > 预设 > 默认图标」，由 [resolveChatIcon] 判定，
 * 保存其中一类时同时清掉另一类，因此两者实际不会共存——
 * 保留优先级判断只是为了在数据被手工改动时仍有确定结果。
 */
class ChatIconStore(private val context: Context, private val prefs: android.content.SharedPreferences) {

    private val _version = MutableStateFlow(0L)

    /** 任何一次写入或删除都会 +1，列表据此重新读取图标。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun dir(): File = File(context.filesDir, "chat-icons").apply { mkdirs() }

    private fun fileForHash(hash: String): File = File(dir(), "$hash.png")

    /** 会话图标图片。没有自选图片时返回 null。 */
    fun fileFor(chatId: String): File? = fileForHash(hashOf(chatId)).takeIf { it.isFile }

    /** 会话选用的预设 key。没有预设时返回 null。 */
    fun presetFor(chatId: String): String? = prefs.getString(keyOf(chatId), null)

    /** 按预设 key 取图标定义；key 不在清单里时返回 null。 */
    fun presetIcon(key: String?): ImageVector? = presets.firstOrNull { it.key == key }?.icon

    /** 保存预设。传 null 等同于 [clear]。 */
    fun savePreset(chatId: String, key: String?) {
        if (key == null) {
            clear(chatId)
            return
        }
        // 预设与自选图片互斥：列表行只画一个图标，留着另一份只会让下次切换时
        // 出现「改成预设了，图还是旧的」。
        fileForHash(hashOf(chatId)).delete()
        prefs.edit().putString(keyOf(chatId), key).apply()
        _version.value = _version.value + 1
    }

    /** 保存自选图片。成功后清掉预设，两者互斥。 */
    fun saveImage(chatId: String, bitmap: Bitmap): Boolean {
        val hash = hashOf(chatId)
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, MAX_SIZE, MAX_SIZE, true).let { scaled ->
                fileForHash(hash).outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            prefs.edit().remove(keyOf(chatId)).apply()
            _version.value = _version.value + 1
            true
        }.getOrDefault(false)
    }

    /**
     * 清除会话图标，回到默认图标。
     *
     * 删除会话时也调它：图标文件占磁盘，而会话已经不存在，
     * 留着只会在「新建了同 id 的会话」时显示上一次的图标。
     */
    fun clear(chatId: String) {
        fileForHash(hashOf(chatId)).delete()
        prefs.edit().remove(keyOf(chatId)).apply()
        _version.value = _version.value + 1
    }

    private fun keyOf(chatId: String): String = "icon_$chatId"

    companion object {
        /** 图标边长。与头像裁剪输出一致，显示时再缩到 17dp。 */
        const val MAX_SIZE = 500

        /** 8 个预设，全部用 Material 矢量图标，免去打包位图。 */
        val presets: List<ChatIconPreset>
            get() = listOf(
                ChatIconPreset("chat", "对话", QwenIcons.messageCircle),
                ChatIconPreset("code", "代码", QwenIcons.code),
                ChatIconPreset("translate", "翻译", QwenIcons.languages),
                ChatIconPreset("lightbulb", "想法", QwenIcons.lightbulb),
                ChatIconPreset("science", "研究", QwenIcons.microscope),
                ChatIconPreset("work", "工作", QwenIcons.briefcase),
                ChatIconPreset("star", "收藏", QwenIcons.star),
                ChatIconPreset("book", "阅读", QwenIcons.bookOpen),
            )

        /**
         * 会话 id 的稳定哈希。
         *
         * 取 SHA-256 前 16 位十六进制：冲突概率可以忽略，同时把任意字符
         * 都收敛成合法的文件名。
         */
        internal fun hashOf(chatId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(chatId.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/** 会话图标最终显示的来源。 */
enum class ChatIconSource { IMAGE, PRESET, DEFAULT }

/**
 * 决定某会话显示哪个图标。
 *
 * 图片优先于预设：两者在保存时已互斥，这里仍按「图片更强」判一次，
 * 是因为图标文件可能被用户从备份里恢复回来，而预设 key 也在同一份备份里——
 * 那种情况下应当以看得见的图片为准，而不是显示回溯不出时序的预设。
 */
fun resolveChatIcon(
    hasImage: Boolean,
    hasPreset: Boolean,
): ChatIconSource = when {
    hasImage -> ChatIconSource.IMAGE
    hasPreset -> ChatIconSource.PRESET
    else -> ChatIconSource.DEFAULT
}