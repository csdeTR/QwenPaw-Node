package io.qwenpaw.node.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前网络类型。
 *
 * 用户同时有局域网地址与 VPN（例如 EasyTier）地址，出门在外时局域网不通、
 * VPN 通。客户端需要知道「现在在哪张网上」，才能优先选对地址，
 * 而不是每次先等一遍连接超时再回退。
 *
 * 权限：判断 VPN 只需要 `ACCESS_NETWORK_STATE`（安装即授予，无弹窗）。
 * 读取 WiFi 名称（SSID）需要定位权限，因此 [Snapshot.ssid] 拿不到时为 null，
 * 此时只是无法按 WiFi 名称绑定地址，VPN 判断不受影响。
 */
class NetworkMonitor(private val context: Context) {

    /** 网络类型：`vpn` / `wifi` / `other`。 */
    data class Snapshot(val kind: String, val ssid: String?) {
        companion object {
            const val KIND_VPN = "vpn"
            const val KIND_WIFI = "wifi"
            const val KIND_OTHER = "other"

            val UNKNOWN = Snapshot(KIND_OTHER, null)
        }
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _current = MutableStateFlow(read())
    val current: StateFlow<Snapshot> = _current.asStateFlow()

    /** 是否已联网。断网恢复的自动重载依赖它的 false → true 跳变。 */
    private val _online = MutableStateFlow(hasNetwork())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 开始监听。
     *
     * 必须同时处理 `onCapabilitiesChanged` 与 `onLost`：VPN 断开时系统**只**回调
     * `onLost`，不会再给一次能力变化。只听前者的话，VPN 拔掉后客户端仍以为在 VPN 上。
     */
    fun start() {
        if (callback != null) return
        val manager = cm ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = refresh()
        }
        callback = cb
        runCatching {
            manager.registerDefaultNetworkCallback(cb)
        }.onFailure {
            // 个别系统在无网络时注册会抛异常；此时退化为「只在启动时读一次」，
            // 不影响手动刷新，只是少了自动感知。
            callback = null
        }
    }

    fun stop() {
        val manager = cm ?: return
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
    }

    /** 重新读一次当前网络，供手动刷新时调用。 */
    fun refresh() {
        val snapshot = read()
        _current.value = snapshot
        _online.value = hasNetwork()
    }

    private fun hasNetwork(): Boolean {
        val manager = cm ?: return false
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun read(): Snapshot {
        val manager = cm ?: return Snapshot.UNKNOWN
        val active = manager.activeNetwork ?: return Snapshot.UNKNOWN
        val caps = manager.getNetworkCapabilities(active) ?: return Snapshot.UNKNOWN
        // 判 VPN 要看 TRANSPORT_VPN 本身，不能用「非 wifi 即 vpn」反推：
        // VPN 生效时 TRANSPORT_WIFI 通常仍为 true，反推会把 VPN 判成 wifi。
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val kind = when {
            isVpn -> Snapshot.KIND_VPN
            isWifi -> Snapshot.KIND_WIFI
            else -> Snapshot.KIND_OTHER
        }
        return Snapshot(kind, if (isWifi || isVpn) currentSsid() else null)
    }

    /**
     * 读当前 WiFi 名称。
     *
     * 未授予定位权限时系统返回 `<unknown ssid>` 或空串，这里统一当作拿不到。
     * 不去主动申请权限：不用该功能的用户不该被打扰。
     */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = runCatching {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val raw = wifi.connectionInfo?.ssid ?: return null
        val cleaned = raw.trim().trim('"')
        cleaned.takeIf { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
    }.getOrNull()
}