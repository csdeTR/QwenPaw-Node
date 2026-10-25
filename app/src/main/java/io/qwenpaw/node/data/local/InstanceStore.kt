package io.qwenpaw.node.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一个已保存的 QwenPaw 实例配置。 */
@Serializable
data class InstanceConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val agentId: String,
    val serverVersion: String? = null,
    /** 仅在内存与加密存储中使用，不进入日志。 */
    val token: String? = null,
    val username: String? = null,
)

/**
 * 多实例凭据存储。
 *
 * token 写入 EncryptedSharedPreferences（由 Android Keystore 派生主密钥），
 * 明文只存在于内存中的 [InstanceConfig]。
 */
class InstanceStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "qwenpaw_instances",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // 极少数机型 Keystore 异常时降级到普通存储，保证应用可用。
        context.getSharedPreferences("qwenpaw_instances_fallback", Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<InstanceConfig> {
        val raw = prefs.getString(KEY_INSTANCES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<InstanceConfig>>(raw) }
            .getOrDefault(emptyList())
    }

    fun save(instances: List<InstanceConfig>) {
        prefs.edit().putString(KEY_INSTANCES, json.encodeToString(instances)).apply()
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActiveId(id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id) }.apply()
    }

    private companion object {
        const val KEY_INSTANCES = "instances_json"
        const val KEY_ACTIVE = "active_instance"
    }
}
