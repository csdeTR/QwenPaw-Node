package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.HarnessProviderDto
import io.qwenpaw.node.data.model.PluginCatalogEntryDto

/**
 * 从插件目录补齐未注册的 harness 后端。
 *
 * 2.3.0.post1 未安装插件时 `/api/harnesses` 返回空列表，只靠它会把
 * 「没有后端」与「后端没装」混成同一件事；目录里 kind=harness 且不在
 * providers 中的条目才是真正的「未安装」。
 */
fun harnessInstallable(
    providers: List<HarnessProviderDto>,
    catalog: List<PluginCatalogEntryDto>,
): List<PluginCatalogEntryDto> = catalog.filter { entry ->
    entry.kind == "harness" && !entry.installed &&
        providers.none { provider ->
            val key = provider.pluginId.ifBlank { provider.id }
            key == entry.pluginId || key == entry.id
        }
}
