package io.qwenpaw.node.ui.market

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.MarketProviderDto
import io.qwenpaw.node.data.model.MarketResultDto
import io.qwenpaw.node.data.model.PluginCatalogEntryDto
import io.qwenpaw.node.data.model.PluginListItemDto
import io.qwenpaw.node.data.model.PluginMarketEntryDto
import io.qwenpaw.node.data.remote.SafZipSource
import kotlinx.coroutines.launch
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 扩展市场：技能市场与插件目录。
 *
 * 安装/导入在本版放开：插件支持 zip 与 URL，技能支持从市场导入当前智能体。
 * 配置表单、插件启停仍留 Web 控制台；PawApp 页面仍交给系统浏览器，
 * 客户端不注入凭据、也不接管返回键。
 */
@Composable
fun MarketScreen(
    state: MarketUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onSetTab: (MarketTab) -> Unit,
    onQuery: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onSearch: () -> Unit,
    onPluginQuery: (String) -> Unit,
    onSearchPlugins: () -> Unit,
    onOpenResult: (MarketResultDto, onOpen: (String) -> Unit, onNote: (String) -> Unit) -> Unit,
    onInstallPluginFile: (SafZipSource) -> Unit = {},
    onInstallPluginUrl: (String) -> Unit = {},
    onForcePluginInstall: () -> Unit = {},
    onDismissPluginInstall: () -> Unit = {},
    onImportSkill: (MarketResultDto) -> Unit = {},
    onCancelHubInstall: () -> Unit = {},
    onDismissHubInstall: () -> Unit = {},
    onDismissNotice: () -> Unit = {},
    onStopHubPolling: () -> Unit = {},
    onConsumePrefill: () -> Unit = {},
    /** 打开某个插件的管理页。目录里已安装的行与「已安装插件」段共用。 */
    onManagePlugin: (String) -> Unit = {},
    /** 安装被依赖升级拦住时打开依赖升级页。 */
    onOpenDependency: (String) -> Unit = {},
    onDismissDependency: () -> Unit = {},
    /** 重拉已安装列表与目录。管理页里改过启停后用。 */
    onRefreshPlugins: () -> Unit = {},
) {
    val context = LocalContext.current
    // 解析失败等一次性说明。放在 Nav 之上而不是状态里：它只描述「这一次点击」，
    // 留着会让用户下次进页面还看到上次的提示。
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var pluginUrlDialog by remember { mutableStateOf(false) }
    var pluginUrl by remember { mutableStateOf("") }
    // 其他页面（Bridge 引导、Harness 未安装）跳来时预填并打开安装框。
    androidx.compose.runtime.LaunchedEffect(state.prefillInstallUrl) {
        state.prefillInstallUrl?.let { url ->
            pluginUrl = url
            pluginUrlDialog = true
            onConsumePrefill()
        }
    }
    val pluginFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val prepared = runCatching {
                    SafZipSource.prepare(context, uri, "plugin.zip")
                }.getOrNull()
                if (prepared != null) {
                    onInstallPluginFile(prepared)
                } else {
                    note = context.getString(R.string.market_error_file_unreadable)
                }
            }
        }
    }

    // 进入页面就拉基础数据：来源、分类与插件目录。三者互不依赖。
    androidx.compose.runtime.LaunchedEffect(Unit) { onEnsureLoaded() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onStopHubPolling() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                        .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
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
                        text = stringResource(R.string.market_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.market_badge_installable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TabChip(
                        text = stringResource(R.string.market_tab_skills),
                        selected = state.tab == MarketTab.SKILLS,
                    ) { onSetTab(MarketTab.SKILLS) }
                    TabChip(
                        text = stringResource(R.string.market_tab_plugins),
                        selected = state.tab == MarketTab.PLUGINS,
                    ) { onSetTab(MarketTab.PLUGINS) }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.tab) {
                MarketTab.SKILLS -> SkillMarket(
                    state = state,
                    onQuery = onQuery,
                    onCategory = onCategory,
                    onSearch = onSearch,
                    onOpen = { url -> openUrl(context, url) },
                    onOpenResult = { result ->
                        onOpenResult(result, { url -> openUrl(context, url) }) { reason ->
                            note = reason
                        }
                    },
                    onImport = onImportSkill,
                )

                MarketTab.PLUGINS -> PluginMarket(
                    state = state,
                    onQuery = onPluginQuery,
                    onSearch = onSearchPlugins,
                    onOpen = { url -> openUrl(context, url) },
                    onInstallFromFile = { pluginFileLauncher.launch("application/zip") },
                    onInstallFromUrl = { pluginUrlDialog = true },
                    onInstallCatalog = { entry -> onInstallPluginUrl(entry.installUrl) },
                    onManagePlugin = onManagePlugin,
                    onRefreshPlugins = onRefreshPlugins,
                )
            }
        }
    }

    if (pluginUrlDialog) {
        AlertDialog(
            onDismissRequest = { pluginUrlDialog = false },
            title = { Text(stringResource(R.string.market_dialog_install_url)) },
            text = {
                OutlinedTextField(
                    value = pluginUrl,
                    onValueChange = { pluginUrl = it },
                    label = { Text(stringResource(R.string.market_field_zip_url)) },
                    placeholder = { Text("https://…/plugin.zip") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pluginUrl.trim().startsWith("http"),
                    onClick = {
                        pluginUrlDialog = false
                        onInstallPluginUrl(pluginUrl)
                        pluginUrl = ""
                    },
                ) { Text(stringResource(R.string.market_action_install)) }
            },
            dismissButton = {
                TextButton(onClick = { pluginUrlDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.pluginInstall?.let { install ->
        AlertDialog(
            onDismissRequest = onDismissPluginInstall,
            title = {
                Text(
                    when {
                        install.conflictSource != null ->
                            stringResource(R.string.market_install_conflict_title)
                        install.stage == "done" -> stringResource(R.string.market_install_done_title)
                        install.stage == "failed" -> stringResource(R.string.market_install_failed_title)
                        else -> stringResource(R.string.market_install_running_title)
                    },
                )
            },
            text = {
                Column {
                    Text(install.label, style = MaterialTheme.typography.bodyMedium)
                    if (install.conflictSource != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.market_install_conflict_message),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (install.total != null && install.total > 0 && install.stage != "done") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(
                                R.string.market_install_uploaded,
                                install.read,
                                install.total,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    install.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                when {
                    install.conflictSource != null -> TextButton(onClick = onForcePluginInstall) {
                        Text(stringResource(R.string.market_action_overwrite))
                    }
                    else -> TextButton(onClick = onDismissPluginInstall) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            },
            dismissButton = {
                if (install.conflictSource != null) {
                    TextButton(onClick = onDismissPluginInstall) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    // 安装被依赖升级拦住：出口是依赖升级页，不能给「覆盖」。
    state.dependencyOperationId?.let { operationId ->
        AlertDialog(
            onDismissRequest = onDismissDependency,
            title = { Text(stringResource(R.string.plugin_admin_market_dependency_title)) },
            text = {
                Text(
                    text = state.dependencyMessage
                        ?: stringResource(R.string.plugin_admin_market_dependency_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissDependency()
                        onOpenDependency(operationId)
                    },
                ) { Text(stringResource(R.string.plugin_admin_market_dependency_action_open)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDependency) {
                    Text(stringResource(R.string.plugin_admin_market_dependency_action_later))
                }
            },
        )
    }

    state.hubInstall?.let { task ->
        AlertDialog(
            onDismissRequest = onDismissHubInstall,
            title = {
                Text(
                    if (task.isTerminal) {
                        stringResource(R.string.market_hub_result_title)
                    } else {
                        stringResource(R.string.market_hub_running_title)
                    },
                )
            },
            text = {
                Column {
                    Text(task.bundleUrl, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.market_hub_status, task.status),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    task.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    task.result?.let { result ->
                        if (result.imported.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.market_hub_imported,
                                    result.imported.joinToString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (result.conflicts.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.market_hub_conflicts,
                                    result.conflicts.joinToString { it.skillName },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!task.isTerminal) {
                    TextButton(onClick = onCancelHubInstall) {
                        Text(stringResource(R.string.market_action_cancel_task))
                    }
                } else {
                    TextButton(onClick = onDismissHubInstall) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            },
            dismissButton = {
                if (!task.isTerminal) {
                    TextButton(onClick = onDismissHubInstall) {
                        Text(stringResource(R.string.market_action_hide))
                    }
                }
            },
        )
    }

    if (state.notice != null) {
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text(stringResource(R.string.market_notice_title)) },
            text = { Text(state.notice) },
            confirmButton = {
                TextButton(onClick = onDismissNotice) {
                    Text(stringResource(R.string.market_action_got_it))
                }
            },
        )
    }

    note?.let { text ->
        AlertDialog(
            onDismissRequest = { note = null },
            title = { Text(stringResource(R.string.market_note_title)) },
            text = {
                Text(
                    text = stringResource(R.string.market_note_message, text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Text(
                    text = stringResource(R.string.market_action_got_it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.clickable { note = null }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
        )
    }
}

@Composable
private fun SkillMarket(
    state: MarketUiState,
    onQuery: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenResult: (MarketResultDto) -> Unit,
    onImport: (MarketResultDto) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.market_search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.metaLoading && state.providers.isEmpty()) {
            LoadingLine(stringResource(R.string.market_loading_providers))
        } else {
            state.metaError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.providers.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.market_label_sources),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 不可用的来源保留在列表里但不可点：用户需要知道「有这个来源、只是没配好」，
                // 直接隐藏会让人以为市场只有这几家。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 与分类行同理：来源标签由服务端返回，长度不可控，溢出时同样会压成竖线。
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.providers.forEach { provider ->
                        ProviderChip(provider)
                    }
                }
                state.providers.filter { !it.available }.forEach { provider ->
                    Text(
                        text = stringResource(
                            R.string.market_provider_unavailable,
                            provider.label,
                            provider.reason.orEmpty(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.categories.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.market_label_categories),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 服务端实测 13 个分类，截断后仍有 7 个 chip，一屏放不下。
                        // 不滚动时最后一个只分到接近 0 的宽度，文字逐字换行会渲染成一条竖线。
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TabChip(
                        text = stringResource(R.string.market_category_all),
                        selected = state.selectedCategory == null,
                    ) { onCategory(null) }
                    state.categories.take(CATEGORY_PREVIEW).forEach { category ->
                        TabChip(category.label, state.selectedCategory == category.id) {
                            onCategory(category.id)
                        }
                    }
                }
            }
        }

        SearchButton(
            label = if (state.searching) {
                stringResource(R.string.market_action_searching)
            } else {
                stringResource(R.string.action_search)
            },
            enabled = !state.searching && state.searchableProviders.isNotEmpty(),
            onClick = onSearch,
        )

        state.searchError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.resultErrors.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            state.searching -> LoadingLine(stringResource(R.string.market_hint_searching))

            state.searched && state.results.isEmpty() && state.searchError == null ->
                Text(
                    text = stringResource(R.string.market_empty_search),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            state.results.isNotEmpty() -> {
                CardBox {
                    state.results.forEachIndexed { index, result ->
                        MarketRow(
                            result = result,
                            resolving = state.resolvingSource != null,
                            onOpen = { onOpenResult(result) },
                            onImport = { onImport(result) },
                        )
                        if (index != state.results.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
                if (state.truncated) {
                    Text(
                        text = stringResource(R.string.market_hint_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.market_hint_import),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PluginMarket(
    state: MarketUiState,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onInstallFromFile: () -> Unit,
    onInstallFromUrl: () -> Unit,
    onInstallCatalog: (PluginCatalogEntryDto) -> Unit,
    onManagePlugin: (String) -> Unit,
    onRefreshPlugins: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onInstallFromFile) {
                Text(stringResource(R.string.market_action_install_from_file))
            }
            TextButton(onClick = onInstallFromUrl) {
                Text(stringResource(R.string.market_action_install_from_url))
            }
        }

        // 已安装段放在目录之前：装完之后用户回到这一页，先要看的是「现在有什么、怎么管」，
        // 而不是再翻一遍目录找同一行。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.plugin_admin_market_section_installed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefreshPlugins) {
                Text(stringResource(R.string.action_refresh))
            }
        }
        CardBox {
            when {
                state.installedPlugins.isNotEmpty() -> {
                    state.installedPlugins.forEachIndexed { index, plugin ->
                        InstalledPluginRow(plugin = plugin, onManage = { onManagePlugin(plugin.id) })
                        if (index != state.installedPlugins.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }

                state.installedLoading -> InlineHint(
                    text = stringResource(R.string.plugin_admin_market_loading_installed),
                )

                state.installedError != null -> InlineHint(
                    text = stringResource(
                        R.string.plugin_admin_market_error_installed,
                        state.installedError,
                    ),
                    error = true,
                )

                else -> InlineHint(stringResource(R.string.plugin_admin_market_empty_installed))
            }
        }

        Text(
            text = stringResource(R.string.market_label_catalog),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardBox {
            when {
                state.catalogLoading && state.catalog == null -> Text(
                    text = stringResource(R.string.market_loading_catalog),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )

                // 目录「成功但 error 非空」表示 CDN 拉不到，此时不能报成「没有插件」。
                state.catalogError != null -> Text(
                    text = stringResource(R.string.market_error_catalog, state.catalogError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )

                state.catalog?.plugins.isNullOrEmpty() -> Text(
                    text = stringResource(R.string.market_empty_catalog),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )

                else -> {
                    val entries = state.catalog?.plugins.orEmpty()
                    entries.forEachIndexed { index, entry ->
                        CatalogRow(
                            entry = entry,
                            onOpen = onOpen,
                            onInstall = { onInstallCatalog(entry) },
                            onManage = { onManagePlugin(entry.pluginId) },
                        )
                        if (index != entries.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }
        }
        state.catalog?.updatedAt?.let {
            Text(
                text = stringResource(R.string.market_label_catalog_updated, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.market_label_online_plugins),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.pluginQuery,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.market_plugin_search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth(),
        )
        SearchButton(
            label = if (state.pluginSearching) {
                stringResource(R.string.market_action_searching)
            } else {
                stringResource(R.string.action_search)
            },
            enabled = !state.pluginSearching,
            onClick = onSearch,
        )
        state.pluginError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            state.pluginSearching -> LoadingLine(stringResource(R.string.market_loading_online_plugins))
            state.pluginResults.isNotEmpty() -> CardBox {
                state.pluginResults.forEachIndexed { index, entry ->
                    PluginMarketRow(entry = entry, onOpen = onOpen)
                    if (index != state.pluginResults.lastIndex) {
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                    }
                }
            }

            state.pluginSearched && state.pluginError == null -> Text(
                text = stringResource(R.string.market_empty_plugins),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.plugin_admin_market_hint_install),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MarketRow(
    result: MarketResultDto,
    resolving: Boolean,
    onOpen: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 解析期间整列不可点：连点会让同一行发起多次解析，也看不出哪一次生效。
            .clickable(enabled = result.sourceUrl.isNotBlank() && !resolving, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.name.ifBlank { result.slug },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                enabled = (result.sourceUrl.startsWith("http")) && !resolving,
                onClick = onImport,
            ) { Text(stringResource(R.string.market_action_import)) }
            Text(
                text = result.sourceLabel ?: result.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        result.author?.takeIf { it.isNotBlank() }?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        result.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        result.version?.takeIf { it.isNotBlank() }?.let { version ->
            Text(
                text = stringResource(R.string.market_label_version, version),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 已安装插件的一行。
 *
 * 只展示「现在是什么状态」与一个入口，不在这里放开关：启停属于管理页，
 * 两处都能改会让用户分不清哪一次生效。
 */
@Composable
private fun InstalledPluginRow(
    plugin: PluginListItemDto,
    onManage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plugin.name.ifBlank { plugin.id },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(plugin.id)
                    if (plugin.version.isNotBlank()) {
                        append(" · ")
                        append(stringResource(R.string.market_label_version, plugin.version))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = buildString {
                append(
                    stringResource(
                        if (plugin.enabled) {
                            R.string.plugin_admin_market_state_enabled
                        } else {
                            R.string.plugin_admin_market_state_disabled
                        },
                    ),
                )
                if (plugin.enabled) {
                    append(" · ")
                    append(
                        stringResource(
                            if (plugin.loaded) {
                                R.string.plugin_admin_market_state_loaded
                            } else {
                                R.string.plugin_admin_market_state_unloaded
                            },
                        ),
                    )
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onManage) {
            Text(stringResource(R.string.plugin_admin_market_action_manage))
        }
    }
}

/** 卡片里的单行说明。与空态、加载态、错误态共用，避免三处各写一套间距。 */
@Composable
private fun InlineHint(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
    )
}

@Composable
private fun CatalogRow(
    entry: PluginCatalogEntryDto,
    onOpen: (String) -> Unit,
    onInstall: () -> Unit,
    onManage: () -> Unit,
) {
    val installStatus = when {
        entry.installed && entry.upgradeAvailable -> R.string.market_badge_installed_upgradable
        entry.installed -> R.string.market_badge_installed
        else -> R.string.market_badge_not_installed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.installUrl.isNotBlank()) { onOpen(entry.installUrl) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name.ifBlank { entry.pluginId },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.installed) {
                // 已安装的行给「管理」：目录是发现入口，改配置与启停都在管理页。
                TextButton(onClick = onManage) {
                    Text(stringResource(R.string.plugin_admin_market_action_manage))
                }
            }
            if (entry.installUrl.isNotBlank()) {
                TextButton(onClick = onInstall) {
                    Text(
                        if (entry.installed) {
                            stringResource(R.string.market_action_upgrade)
                        } else {
                            stringResource(R.string.market_action_install)
                        },
                    )
                }
            }
            Text(
                text = stringResource(installStatus),
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.upgradeAvailable) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (entry.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = buildString {
                if (entry.author.isNotBlank()) append(entry.author)
                if (entry.version.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(stringResource(R.string.market_label_version, entry.version))
                }
                if (entry.size.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(entry.size)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PluginMarketRow(entry: PluginMarketEntryDto, onOpen: (String) -> Unit) {
    // 中文本地化优先，没有时用英文：平台的两个 locale 都可能缺。
    val locale = entry.locales?.get("zh") ?: entry.locales?.get("en")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.detailsUrl.isNotBlank()) { onOpen(entry.detailsUrl) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = entry.displayName.ifBlank { entry.id },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        locale?.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = buildString {
                if (entry.developer.isNotBlank()) append(entry.developer)
                if (entry.version.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(stringResource(R.string.market_label_version, entry.version))
                }
                if (entry.downloads > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(
                        pluralStringResource(
                            R.plurals.market_downloads,
                            entry.downloads.toInt(),
                            entry.downloads,
                        ),
                    )
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderChip(provider: MarketProviderDto) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (provider.available) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = provider.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (provider.available) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun SearchButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LoadingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) { content() }
}

/**
 * 用系统浏览器打开一个地址。
 *
 * 失败只当无事发生：市场里的地址来自第三方，格式不对是常见情况，
 * 抛出来会让用户在一个只读页面上看到崩溃。
 */
private fun openUrl(context: android.content.Context, url: String) {
    val target = url.trim()
    if (target.isEmpty()) return
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(target),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 分类只展示前几项：实测服务端有十几项，一屏放不下，全列出来会把搜索结果挤出屏幕。 */
private const val CATEGORY_PREVIEW = 6