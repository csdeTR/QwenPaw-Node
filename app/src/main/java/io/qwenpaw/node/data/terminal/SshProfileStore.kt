package io.qwenpaw.node.data.terminal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json

/**
 * SSH 直连配置的加密存储。
 *
 * 与 [io.qwenpaw.node.data.local.InstanceStore] 不同，这里**不做明文降级**：
 * 主密钥不可用时直连直接不可用，避免把私钥或口令落到普通 SharedPreferences。
 */
class SshProfileStore internal constructor(
    private val prefs: SharedPreferences?,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    constructor(context: Context) : this(open(context))

    val available: Boolean get() = prefs != null

    fun load(instanceId: String): SshProfile? {
        val raw = prefs?.getString(key("profile", instanceId), null) ?: return null
        return runCatching { json.decodeFromString<SshProfile>(raw) }.getOrNull()
    }

    /** 返回 false 表示本机安全存储不可用，调用方应禁用直连。 */
    fun save(instanceId: String, profile: SshProfile, secret: SshSecret? = null): Boolean {
        val store = prefs ?: return false
        val editor = store.edit()
        editor.putString(
            key("profile", instanceId),
            json.encodeToString(SshProfile.serializer(), profile),
        )
        if (secret != null) {
            when (profile.auth) {
                SshAuthType.KEY -> {
                    putOrRemove(editor, key("key", instanceId), secret.privateKey())
                    putOrRemove(editor, key("passphrase", instanceId), secret.passphrase())
                    editor.remove(key("password", instanceId))
                }
                SshAuthType.PASSWORD -> {
                    putOrRemove(editor, key("password", instanceId), secret.password())
                    editor.remove(key("key", instanceId))
                    editor.remove(key("passphrase", instanceId))
                }
            }
        }
        editor.apply()
        return true
    }

    fun secret(instanceId: String): SshSecret = SshSecret(
        privateKey = prefs?.getString(key("key", instanceId), null),
        passphrase = prefs?.getString(key("passphrase", instanceId), null),
        password = prefs?.getString(key("password", instanceId), null),
    )

    fun hasCredentials(instanceId: String, auth: SshAuthType): Boolean {
        val store = prefs ?: return false
        return when (auth) {
            SshAuthType.KEY -> !store.getString(key("key", instanceId), null).isNullOrBlank()
            SshAuthType.PASSWORD ->
                !store.getString(key("password", instanceId), null).isNullOrBlank()
        }
    }

    fun delete(instanceId: String) {
        prefs?.edit()
            ?.remove(key("profile", instanceId))
            ?.remove(key("key", instanceId))
            ?.remove(key("passphrase", instanceId))
            ?.remove(key("password", instanceId))
            ?.apply()
    }

    private fun putOrRemove(editor: SharedPreferences.Editor, key: String, value: String?) {
        when {
            value == null -> Unit
            value.isBlank() -> editor.remove(key)
            else -> editor.putString(key, value)
        }
    }

    private fun key(prefix: String, instanceId: String): String = "$prefix|$instanceId"

    private companion object {
        fun open(context: Context): SharedPreferences? = runCatching {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                "qwenpaw_ssh_profiles",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }
}
