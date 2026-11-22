package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.ProviderGroup
import io.qwenpaw.node.data.model.ProviderScope
import io.qwenpaw.node.data.model.ProviderSections
import io.qwenpaw.node.data.model.providerDisplayName
import io.qwenpaw.node.ui.chat.OptionDialog
import io.qwenpaw.node.ui.chat.OptionItem
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 模型与提供商：按「云端 / 本地与自定义」两个页签铺开的提供商列表。
 *
 * 这一页只负责浏览与入口：配置进提供商详情页，默认模型进默认模型页。
 * 卡片里不再放表单，长列表中的输入框既难填也容易误触。
 */
@Composable
fun ModelsScreen(
    state: ModelsUiState,
    onBack: () -> Unit,
    // 保留旧签名：默认模型已迁到默认模型页，本页不再直接切换。
    onPickDefault: (ModelScope, String, String) -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    // 保留「去 Web 控制台」的入口参数：本页已全部原生实现，不再挂常驻提示。
    onOpenConsole: (() -> Unit)? = null,
    onOpenProvider: (String) -> Unit = {},
    onOpenProviderAdd: () -> Unit = {},
    onOpenModelDefaults: () -> Unit = {},
    // 「模型」按钮的落点。不单独给时与「配置」同页，宿主可改成直接落到模型页签。
    onOpenProviderModels: ((String) -> Unit)? = null,
    onSelectTab: (ProviderScope) -> Unit = {},
) {
    val openModels: (String) -> Unit = onOpenProviderModels ?: onOpenProvider
    val tab = state.tab
    var query by remember { mutableStateOf("") }
    // 需要先选版本的可用分组。选完立刻进配置页，不留在本页。
    var pickingGroup by remember { mutableStateOf<ProviderGroup?>(null) }
    // 已配置分组内正在看哪个变体：分组 key 到提供商 id。
    val variantSelection = remember { mutableStateMapOf<String, String>() }

    val sections = state.sections(tab, query)
    val cloudCount = countProviders(state.providers, ProviderScope.CLOUD)
    val localCount = countProviders(state.providers, ProviderScope.LOCAL)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = io.qwenpaw.node.ui.common.pageContentInsets(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = QwenIcons.arrowLeft,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.models_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !state.loading, onClick = onRetry),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = QwenIcons.refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = stringResource(R.string.models_rework_add_provider),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenProviderAdd)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScopeTab(
                        label = stringResource(
                            R.string.models_rework_tab_count,
                            stringResource(R.string.models_rework_tab_cloud),
                            cloudCount,
                        ),
                        selected = tab == ProviderScope.CLOUD,
                        onClick = { onSelectTab(ProviderScope.CLOUD) },
                    )
                    ScopeTab(
                        label = stringResource(
                            R.string.models_rework_tab_count,
                            stringResource(R.string.models_rework_tab_local),
                            localCount,
                        ),
                        selected = tab == ProviderScope.LOCAL,
                        onClick = { onSelectTab(ProviderScope.LOCAL) },
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.models_rework_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = QwenIcons.search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading && !state.loaded -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.providers.isEmpty() && state.error != null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.models_action_retry_click),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onRetry),
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 10.dp,
                        bottom = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "defaults") {
                        DefaultsRow(
                            summary = stringResource(
                                R.string.models_rework_defaults_summary,
                                state.labelOf(ModelScope.GLOBAL)
                                    ?: stringResource(R.string.models_rework_not_set),
                                state.labelOf(ModelScope.AGENT)
                                    ?: stringResource(R.string.models_follow_global),
                            ),
                            onClick = onOpenModelDefaults,
                        )
                    }

                    state.notice?.let { notice ->
                        item(key = "notice") {
                            NoticeLine(text = notice, error = false, onClick = onDismissNotice)
                        }
                    }
                    state.error?.let { error ->
                        item(key = "error") {
                            NoticeLine(text = error, error = true, onClick = onDismissNotice)
                        }
                    }

                    if (tab == ProviderScope.LOCAL) {
                        localTabItems(
                            sections = sections,
                            onOpenProvider = onOpenProvider,
                            onOpenProviderModels = openModels,
                        )
                    } else {
                        cloudTabItems(
                            sections = sections,
                            variantSelection = variantSelection,
                            onOpenProvider = onOpenProvider,
                            onOpenProviderModels = openModels,
                            onPickVariants = { pickingGroup = it },
                        )
                    }
                }
            }
        }
    }

    val pending = pickingGroup
    if (pending != null) {
        OptionDialog(
            title = stringResource(R.string.models_rework_variant_pick_title, pending.name),
            items = pending.providers.map { provider ->
                OptionItem(
                    key = provider.id,
                    label = variantLabel(provider),
                    sublabel = providerDisplayName(provider),
                )
            },
            onPick = { id ->
                pickingGroup = null
                onOpenProvider(id)
            },
            onDismiss = { pickingGroup = null },
        )
    }
}

/** 云端页签：已配置在前（分组卡与单卡混排），可用提供商在后。 */
private fun LazyListScope.cloudTabItems(
    sections: ProviderSections,
    variantSelection: MutableMap<String, String>,
    onOpenProvider: (String) -> Unit,
    onOpenProviderModels: (String) -> Unit,
    onPickVariants: (ProviderGroup) -> Unit,
) {
    val hasConfigured = sections.configured.isNotEmpty() || sections.configuredGroups.isNotEmpty()
    if (hasConfigured) {
        item(key = "cloud-configured-header") {
            SectionHeader(stringResource(R.string.models_rework_section_configured))
        }
        items(sections.configuredGroups, key = { "cg:${it.key}" }) { group ->
            val selectedId = variantSelection[group.key]
                ?.takeIf { id -> group.providers.any { it.id == id } }
            val active = group.providers.firstOrNull { it.id == selectedId }
                ?: group.providers.first()
            GroupProviderCard(
                group = group,
                active = active,
                onSelectVariant = { id -> variantSelection[group.key] = id },
                onOpen = { onOpenProvider(active.id) },
                onModels = { onOpenProviderModels(active.id) },
            )
        }
        items(sections.configured, key = { "cp:${it.id}" }) { provider ->
            ProviderCard(
                title = providerDisplayName(provider),
                provider = provider,
                onClick = { onOpenProvider(provider.id) },
                onModels = { onOpenProviderModels(provider.id) },
            )
        }
    }

    if (sections.available.isNotEmpty() || sections.availableGroups.isNotEmpty()) {
        item(key = "cloud-available-header") {
            SectionHeader(stringResource(R.string.models_rework_section_available))
        }
        items(sections.availableGroups, key = { "ag:${it.key}" }) { group ->
            AvailableGroupRow(group = group, onPick = { onPickVariants(group) })
        }
        items(sections.available, key = { "ap:${it.id}" }) { provider ->
            AvailableRow(
                title = providerDisplayName(provider),
                provider = provider,
                onConfig = { onOpenProvider(provider.id) },
            )
        }
    }

    if (!hasConfigured && sections.available.isEmpty() && sections.availableGroups.isEmpty()) {
        item(key = "cloud-empty") {
            EmptyHint(stringResource(R.string.models_rework_empty_cloud))
        }
    }
}

/** 本地与自定义页签。QwenPaw Local 固定排第一，且不出现启停开关。 */
private fun LazyListScope.localTabItems(
    sections: ProviderSections,
    onOpenProvider: (String) -> Unit,
    onOpenProviderModels: (String) -> Unit,
) {
    // 本地分支的「已配置」判据只看模型数量，停用的运行时会留在里面；
    // 这里再按启停拆一次，停用项归入可用区，与云端页签保持一致。
    val configured = sections.configured.filter { it.enabled }
    val available = sections.available + sections.configured.filterNot { it.enabled }
    val localFirst = configured.firstOrNull {
        it.id == "qwenpaw-local" || it.id == "copaw-local"
    }
    val others = configured.filter { it !== localFirst }

    if (configured.isNotEmpty()) {
        item(key = "local-configured-header") {
            SectionHeader(stringResource(R.string.models_rework_section_configured))
        }
        localFirst?.let { provider ->
            item(key = "local-embedded") {
                // 内嵌运行时没有独立的提供商配置页，点进去就是本地模型设置本身。
                LocalProviderCard(
                    provider = provider,
                    onOpen = { onOpenProvider(provider.id) },
                )
            }
        }
        items(others, key = { "lp:${it.id}" }) { provider ->
            ProviderCard(
                title = providerDisplayName(provider),
                provider = provider,
                onClick = { onOpenProvider(provider.id) },
                onModels = { onOpenProviderModels(provider.id) },
            )
        }
    }

    if (available.isNotEmpty()) {
        item(key = "local-available-header") {
            SectionHeader(stringResource(R.string.models_rework_section_available))
        }
        items(available, key = { "la:${it.id}" }) { provider ->
            AvailableRow(
                title = providerDisplayName(provider),
                provider = provider,
                onConfig = { onOpenProvider(provider.id) },
            )
        }
    }

    if (configured.isEmpty() && available.isEmpty()) {
        item(key = "local-empty") {
            EmptyHint(stringResource(R.string.models_rework_empty_local))
        }
    }
}

@Composable
private fun ScopeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun DefaultsRow(summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.models_rework_defaults_row),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = QwenIcons.chevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoticeLine(text: String, error: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

@Composable
private fun Tag(text: String, emphasized: Boolean = false) {
    Surface(
        color = if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 模型数与凭据状态的同一行。 */
@Composable
private fun MetaLine(primary: String, secondary: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = primary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (secondary != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一张提供商卡。
 *
 * 名称与状态同一行，标签与模型数同一行，Base URL 独占一行并单行省略：
 * 360dp 宽度下把地址与按钮挤进一行，地址只剩十来字符，看不出连的是哪个场景。
 */
@Composable
private fun ProviderCard(
    title: String,
    provider: ProviderDto,
    onClick: () -> Unit,
    onModels: () -> Unit,
    typeLabel: String? = null,
    showConfig: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = if (showConfig) onClick else onModels)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            if (provider.isFreeTier) Tag(stringResource(R.string.models_rework_tag_free), true)
            Spacer(Modifier.width(4.dp))
            Tag(providerStatusText(provider))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (provider.isCustom) {
                Tag(stringResource(R.string.models_rework_tag_custom))
                Spacer(Modifier.width(4.dp))
            }
            if (provider.isLocal) {
                Tag(stringResource(R.string.models_rework_tag_local))
                Spacer(Modifier.width(4.dp))
            }
            MetaLine(
                primary = modelCountText(provider),
                secondary = providerCredentialRes(provider)?.let { stringResource(it) },
            )
        }
        typeLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        provider.baseUrl?.takeIf { it.isNotBlank() }?.let { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showConfig) {
                CardAction(
                    text = stringResource(R.string.models_rework_action_config),
                    onClick = onClick,
                )
                Spacer(Modifier.width(6.dp))
            }
            CardAction(
                text = stringResource(R.string.models_rework_action_models),
                onClick = onModels,
            )
        }
    }
}

/** 已配置分组卡：标题是分组名，卡内用变体 chip 切换现在看哪一个。 */
@Composable
private fun GroupProviderCard(
    group: ProviderGroup,
    active: ProviderDto,
    onSelectVariant: (String) -> Unit,
    onOpen: () -> Unit,
    onModels: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            if (group.hasFree) Tag(stringResource(R.string.models_rework_tag_free), true)
            Spacer(Modifier.width(4.dp))
            Tag(providerStatusText(active))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            group.providers.forEach { provider ->
                VariantChip(
                    label = variantLabel(provider),
                    selected = provider.id == active.id,
                    onClick = { onSelectVariant(provider.id) },
                )
            }
        }
        MetaLine(
            primary = modelCountText(active),
            secondary = providerCredentialRes(active)?.let { stringResource(it) },
        )
        active.baseUrl?.takeIf { it.isNotBlank() }?.let { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardAction(
                text = stringResource(R.string.models_rework_action_config),
                onClick = onOpen,
            )
            Spacer(Modifier.width(6.dp))
            CardAction(
                text = stringResource(R.string.models_rework_action_models),
                onClick = onModels,
            )
        }
    }
}

/** 本地内嵌提供商卡：只有类型、模型状态与「模型」入口，没有启停开关。 */
@Composable
private fun LocalProviderCard(
    provider: ProviderDto,
    onOpen: () -> Unit,
) {
    ProviderCard(
        title = providerDisplayName(provider),
        provider = provider,
        onClick = onOpen,
        onModels = onOpen,
        typeLabel = stringResource(
            R.string.models_rework_local_type,
            stringResource(R.string.models_rework_local_embedded_type),
        ),
        showConfig = false,
    )
}

@Composable
private fun VariantChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CardAction(text: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** 可用分组：多版本时先选版本，再进配置页。 */
@Composable
private fun AvailableGroupRow(group: ProviderGroup, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (group.hasFree) {
                    Spacer(Modifier.width(6.dp))
                    Tag(stringResource(R.string.models_rework_tag_free), true)
                }
            }
            Text(
                text = group.providers.joinToString(" / ") { variantLabel(it) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        CardAction(
            text = stringResource(R.string.models_rework_action_config),
            onClick = onPick,
        )
    }
}

/** 可用的单个提供商。整行可点，右侧「配置」给一个明确入口。 */
@Composable
private fun AvailableRow(title: String, provider: ProviderDto, onConfig: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onConfig)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (provider.isFreeTier) {
                    Spacer(Modifier.width(6.dp))
                    Tag(stringResource(R.string.models_rework_tag_free), true)
                }
            }
            MetaLine(
                primary = providerStatusText(provider),
                secondary = if (provider.isLocal) {
                    stringResource(R.string.models_rework_tag_local)
                } else {
                    null
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        CardAction(
            text = stringResource(R.string.models_rework_action_config),
            onClick = onConfig,
        )
    }
}
