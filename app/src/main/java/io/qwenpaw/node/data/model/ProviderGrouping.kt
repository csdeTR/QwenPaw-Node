package io.qwenpaw.node.data.model

/** 提供商列表的两个页签。 */
enum class ProviderScope {
    CLOUD,
    LOCAL,
}

/** 同一提供商的不同接入场景，例如 Zhipu 的 BigModel 与 Z.AI。 */
data class ProviderGroup(
    val key: String,
    val name: String,
    val providers: List<ProviderDto>,
    val hasFree: Boolean,
)

data class ProviderSections(
    val configured: List<ProviderDto> = emptyList(),
    val configuredGroups: List<ProviderGroup> = emptyList(),
    val available: List<ProviderDto> = emptyList(),
    val availableGroups: List<ProviderGroup> = emptyList(),
)

private val LOCAL_PROVIDER_IDS = setOf("qwenpaw-local", "copaw-local", "ollama", "lmstudio")

fun isLocalProvider(provider: ProviderDto): Boolean =
    provider.isLocal || provider.id in LOCAL_PROVIDER_IDS

/**
 * 与 Console 的 `getIsConfigured` 对齐：停用、未连接 OAuth、没有密钥的云端提供商
 * 都算「可用」，不占已经配置好的位置。
 */
fun isProviderConfigured(provider: ProviderDto): Boolean {
    if (!provider.enabled) return false
    if (provider.id == "openai-chatgpt") return provider.oauthConnected
    if (provider.id == "qwenpaw-local" || provider.id == "copaw-local") return true
    if (provider.isCustom && !provider.baseUrl.isNullOrBlank()) return true
    if (provider.requireApiKey == false) return true
    if (provider.requireApiKey == true) return !provider.apiKey.isNullOrBlank()
    return false
}

fun configuredModelCount(provider: ProviderDto): Int =
    provider.models.size + provider.extraModels.size

private fun isLocalConfigured(provider: ProviderDto): Boolean =
    configuredModelCount(provider) > 0 || isProviderConfigured(provider)

fun providerDisplayName(provider: ProviderDto): String =
    provider.name.ifBlank { provider.providerVariant ?: provider.id }

private fun groupName(key: String, providers: List<ProviderDto>): String =
    providers.firstOrNull()?.providerGroupName?.takeIf { it.isNotBlank() } ?: key

/** 把提供商拆成「已配置 / 可用」并聚合同一提供商的多个接入场景。 */
fun groupProviders(providers: List<ProviderDto>, scope: ProviderScope): ProviderSections {
    val cloud = providers.filterNot { it.isLocal || it.isCustom }
    val local = providers.filter { it.isLocal || it.isCustom }
    return if (scope == ProviderScope.LOCAL) {
        val configured = local.filter(::isLocalConfigured)
            .sortedWith(compareByDescending<ProviderDto> { configuredModelCount(it) > 0 }
                .thenBy { providerDisplayName(it).lowercase() })
        val available = local.filterNot(::isLocalConfigured)
            .sortedBy { providerDisplayName(it).lowercase() }
        ProviderSections(configured = configured, available = available)
    } else {
        val configured = cloud.filter(::isProviderConfigured)
            .sortedBy { providerDisplayName(it).lowercase() }
        val available = cloud.filterNot(::isProviderConfigured)
            .sortedBy { providerDisplayName(it).lowercase() }
        val (configuredGroups, configuredSingle) = groupByProvider(configured)
        val (availableGroups, availableSingle) = groupByProvider(available)
        ProviderSections(
            configured = configuredSingle,
            configuredGroups = configuredGroups,
            available = availableSingle,
            availableGroups = availableGroups,
        )
    }
}

private fun groupByProvider(providers: List<ProviderDto>): Pair<List<ProviderGroup>, List<ProviderDto>> {
    val groups = LinkedHashMap<String, MutableList<ProviderDto>>()
    val single = mutableListOf<ProviderDto>()
    providers.forEach { provider ->
        val key = provider.providerGroup
        if (key.isNullOrBlank()) single += provider else groups.getOrPut(key) { mutableListOf() } += provider
    }
    val grouped = groups.mapNotNull { (key, list) ->
        if (list.size < 2) {
            single += list
            null
        } else {
            ProviderGroup(
                key = key,
                name = groupName(key, list),
                providers = list.sortedBy { providerDisplayName(it).lowercase() },
                hasFree = list.any { it.isFreeTier },
            )
        }
    }.sortedWith(compareByDescending<ProviderGroup> { it.hasFree }
        .thenBy { it.name.lowercase() })
    return grouped to single
}
