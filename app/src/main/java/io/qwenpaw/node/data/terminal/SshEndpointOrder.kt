package io.qwenpaw.node.data.terminal

/** 直连 SSH 的地址顺序：VPN 地址优先，其次默认地址，其余保持用户顺序。 */
object SshEndpointOrder {

    fun order(addresses: List<SshAddress>, vpnActive: Boolean): List<SshAddress> {
        val unique = addresses
            .asSequence()
            .filter { it.host.isNotBlank() && it.user.isNotBlank() }
            .distinctBy {
                Triple(
                    it.host.trim().lowercase(),
                    if (it.port in 1..65535) it.port else 22,
                    it.user.trim(),
                )
            }
            .toList()
        if (unique.isEmpty()) return emptyList()
        if (!vpnActive) {
            return unique.sortedByDescending { it.default }
        }
        return unique.sortedWith(
            compareByDescending<SshAddress> { it.preferOnVpn }
                .thenByDescending { it.default },
        )
    }
}
