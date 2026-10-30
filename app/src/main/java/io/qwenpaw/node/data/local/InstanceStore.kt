package io.qwenpaw.node.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一个连接地址。
 *
 * 同一台服务端常有多个入口：家里走局域网，外出走 EasyTier 之类的隧道。
 * 别名是为了让用户在设置页一眼看出「这条是哪个网络」，而不是靠记 IP。
 */
@Serializable
data class AddressEntry(
    val url: String,
    /** 用户自定义别名，例如「本地」「EasyTier」。为空时界面显示地址本身。 */
    val alias: String? = null,
    /**
     * 网络偏好：当前网络命中时优先使用此地址。
     *
     * null 表示不参与自动切换，只按列表顺序参与回退。
     */
    val preferOn: NetworkHint? = null,
) {
    /** 界面展示名：优先别名，否则退回地址。 */
    val label: String get() = alias?.takeIf { it.isNotBlank() } ?: url
}

/**
 * 地址的网络偏好。
 *
 * [kind] 取 `vpn` 或 `wifi`；`wifi` 时再用 [ssid] 限定具体网络。
 * 不区分大小写比较 SSID——用户在不同地方手打同一个名字时写法常不一致。
 */
@Serializable
data class NetworkHint(
    val kind: String,
    /** `kind == "wifi"` 时为网络名称；`kind == "vpn"` 时为空。 */
    val ssid: String? = null,
) {
    /** 当前网络是否命中这条偏好。 */
    fun matches(currentKind: String, currentSsid: String?): Boolean {
        if (!kind.equals(currentKind, ignoreCase = true)) return false
        // VPN 只看类型；WiFi 若指定了 SSID 还要对上名字。
        val want = ssid?.trim()
        if (want.isNullOrBlank()) return true
        return want.equals(currentSsid?.trim(), ignoreCase = true)
    }
}

/** 一个已保存的 QwenPaw 实例配置。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class InstanceConfig(
    val id: String,
    val name: String,
    val agentId: String,
    /**
     * 旧版的单地址字段，只用于读取历史配置。
     *
     * 早期版本把地址存在 `baseUrl` 里。为了不让老用户的实例在升级后丢地址，
     * 这个字段继续参与反序列化，但**写盘时绝不输出**（`NEVER`）——
     * 存储用的是 `encodeDefaults = true`，不加这个标记会把 `"baseUrl": null`
     * 一起写进去，下次读出来又是一堆无意义的空字段。
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("baseUrl") val legacyBaseUrl: String? = null,
    /**
     * 旧版的纯地址列表，只用于迁移。
     *
     * 上一版把地址存成 `List<String>`；现在需要别名与网络偏好，
     * 因此升级为 [addresses]。同样只在读入时参与，写盘时不输出。
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val baseUrls: List<String> = emptyList(),
    /**
     * 连接地址，按用户设定的顺序逐个尝试。
     *
     * 第一个是默认地址。某个地址不可达时自动换下一个，用户不必手动切换实例。
     */
    val addresses: List<AddressEntry> = emptyList(),
    /**
     * 用户手动指定的地址。
     *
     * 非空时优先于列表顺序，但**不排他**：指定地址不可达仍然继续回退，
     * 否则一次手动选择会让实例在地址故障时彻底不可用。
     */
    val preferredBaseUrl: String? = null,
    val serverVersion: String? = null,
    /** 仅在内存与加密存储中使用，不进入日志。 */
    val token: String? = null,
    val username: String? = null,
) {
    /** 默认地址。列表为空时返回空串，调用方据空串判断配置是否可用。 */
    val primaryBaseUrl: String
        get() = addresses.firstOrNull()?.url.orEmpty()

    /** 备用地址个数，用于设置页显示「+N 个备用」。 */
    val backupCount: Int
        get() = (addresses.size - 1).coerceAtLeast(0)

    /** 按地址查别名，用于设置页卡片与当前使用提示。 */
    fun labelOf(url: String): String =
        addresses.firstOrNull { it.url == url }?.label ?: url

    /**
     * 顶栏末尾要显示的服务器名；空串表示「用户没有起名字」。
     *
     * 有两种情况都算没起名字：
     * - `name` 本身是空白；
     * - `name` 只是表单留空时由 [InstanceDraft.resolvedName] 回落出来的地址。
     *
     * 第二种必须显式判掉。否则顶栏会显示成
     * 「已通过「EasyTier」连接至「10.0.2.9:8088」」——同一个地址在句子里出现两次，
     * 而且当实际绑定的是另一条地址时，回落值还指错了地方。
     *
     * 比较时忽略协议前缀与结尾斜杠：回落值存的是用户当时填的原文（可能没有 `http://`），
     * 而 [addresses] 里的是规范化后的结果。
     */
    val serverDisplayName: String
        get() {
            val label = name.trim()
            if (label.isEmpty()) return ""
            fun canon(raw: String): String =
                raw.trim().lowercase()
                    .removePrefix("https://").removePrefix("http://")
                    .trimEnd('/')
            val normalized = canon(label)
            val looksLikeAddress = addresses.any { canon(it.url) == normalized }
            return if (looksLikeAddress) "" else label
        }

    /**
     * 把旧字段迁移进 [addresses]，并清掉两个兼容字段。
     *
     * 迁移顺序：已有新版列表就直接用；否则从 `baseUrls` 转；再否则从
     * `legacyBaseUrl` 转。三者都空时保持原样。
     */
    fun migrated(): InstanceConfig {
        if (addresses.isNotEmpty()) {
            return if (legacyBaseUrl == null && baseUrls.isEmpty()) this
            else copy(legacyBaseUrl = null, baseUrls = emptyList())
        }
        val fromList = baseUrls.filter { it.isNotBlank() }.map { AddressEntry(url = it) }
        if (fromList.isNotEmpty()) {
            return copy(addresses = fromList, legacyBaseUrl = null, baseUrls = emptyList())
        }
        if (!legacyBaseUrl.isNullOrBlank()) {
            return copy(
                addresses = listOf(AddressEntry(url = legacyBaseUrl)),
                legacyBaseUrl = null,
                baseUrls = emptyList(),
            )
        }
        return this
    }
}

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
        val parsed = runCatching { json.decodeFromString<List<InstanceConfig>>(raw) }
            .getOrDefault(emptyList())
        // 读出后立刻迁移旧字段，之后内存与磁盘都只有 baseUrls 一种形态。
        val migrated = parsed.map { it.migrated() }
        if (migrated != parsed) save(migrated)
        return migrated
    }

    fun save(instances: List<InstanceConfig>) {
        // 落盘前统一清掉旧字段：新版本只写 baseUrls。
        val clean = instances.map { it.migrated() }
        prefs.edit().putString(KEY_INSTANCES, json.encodeToString(clean)).apply()
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
