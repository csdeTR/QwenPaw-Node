package io.qwenpaw.node.data.model

/** 模型池的筛选条件。 */
data class PoolFilters(
    val tab: String = "all",
    val search: String = "",
    val family: String = "all",
)

/**
 * 已选模型只来自 `models + extra_models`。
 *
 * `discovered_models` 只是探测到的候选，服务端池列表也不带每项的选中标记；
 * 把它算进已选会让刚打开页面时所有探测到的模型都显示成已启用。
 */
fun selectedModelIds(provider: ProviderDto): Set<String> =
    (provider.models + provider.extraModels).map { it.id }.toSet()

/** 页签、搜索或 family 变化时重新请求，并把 offset 归零。 */
fun shouldReloadPool(old: PoolFilters, new: PoolFilters): Boolean =
    old.tab != new.tab ||
        old.family != new.family ||
        old.search.trim() != new.search.trim()
