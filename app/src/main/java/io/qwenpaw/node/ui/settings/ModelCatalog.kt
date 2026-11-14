package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.ui.chat.ModelChoice

/**
 * 把供应商列表摊平成可切换的模型清单。
 *
 * 只用 `GET /api/models` 的三个数组（配置 / 额外 / 探测），**不调
 * `/api/models/{id}/pool`**：本地部署下该端点返回空数组，依赖它会得到
 * 「没有可用模型」的错误结论。未启用的供应商直接跳过，它们的模型即便列出来也用不了。
 *
 * 抽成纯函数是因为聊天页的模型菜单与本页的默认模型选择必须同源：
 * 两处各写一遍合并逻辑，迟早出现「菜单里有、设置里没有」。
 */
fun modelChoicesOf(providers: List<ProviderDto>): List<ModelChoice> = providers
    .filter { it.enabled }
    .flatMap { provider ->
        provider.allModels.map { slot ->
            ModelChoice(
                providerId = provider.id,
                providerName = provider.name.ifBlank { provider.id },
                modelId = slot.id,
                modelName = slot.name.ifBlank { slot.id },
            )
        }
    }
    .distinctBy { it.key }
