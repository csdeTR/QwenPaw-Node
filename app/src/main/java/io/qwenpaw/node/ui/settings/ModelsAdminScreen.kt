package io.qwenpaw.node.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle

@Composable
fun ModelsAdminScreen(
    state: ModelsAdminUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSetTab: (ModelsAdminTab) -> Unit,
    onSave: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetName: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSetAuthMode: (String) -> Unit,
    onSetChatModel: (String) -> Unit,
    onSetCustomHeaders: (String) -> Unit,
    onSetGenerateKwargs: (String) -> Unit,
    onBeginSecret: () -> Unit,
    onSetSecret: (String) -> Unit,
    onClearSecret: () -> Unit,
    onCancelSecret: () -> Unit,
    onTestModel: (String) -> Unit,
    onDiscover: () -> Unit,
    onDelete: () -> Unit,
    onBeginAddModel: () -> Unit,
    onBeginEditModel: (String, String) -> Unit,
    onCancelEditModel: () -> Unit,
    onSaveModel: () -> Unit,
    onEditId: (String) -> Unit,
    onEditName: (String) -> Unit,
    onEditFree: (Boolean) -> Unit,
    onEditImage: (Boolean) -> Unit,
    onEditVideo: (Boolean) -> Unit,
    onEditType: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onToggleVisibility: (String, Boolean) -> Unit,
    onProbe: (String) -> Unit,
    onPoolSearch: (String) -> Unit,
    onPoolFamily: (String) -> Unit,
    onPoolTab: (String) -> Unit,
    onPoolLoad: () -> Unit,
    onSelectAllPool: (Boolean) -> Unit,
    onSelectPoolModel: (String, Boolean) -> Unit,
    onStartOAuth: () -> Unit,
    onDismissOAuth: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenConsole: (() -> Unit)? = null,
    onOpenModelDetail: (io.qwenpaw.node.data.model.PoolModelDto) -> Unit = {},
    onOpenModelDetailById: (String) -> Unit = {},
    onCloseModelDetail: () -> Unit = {},
    onModelDetailChange: ((ModelDetailDraft) -> ModelDetailDraft) -> Unit = {},
    onSaveModelDetail: () -> Unit = {},
) {
    var deletingModel by remember { mutableStateOf<String?>(null) }
    var confirmingDeleteProvider by remember { mutableStateOf(false) }
    val detail = state.detail
    val detailDraft = state.detailDraft

    WebEntryDialog(pageKey = "models_admin", onOpenConsole = onOpenConsole)
    LaunchedEffect(Unit) { if (!state.loading && state.provider == null && detail == null) onRetry() }
    // 模型详情是页内二级页，不是独立导航页；系统返回必须先回到提供商页。
    BackHandler(enabled = detail != null) { onCloseModelDetail() }

    val header: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            ConsoleHint(onOpenConsole = onOpenConsole)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabChip(
                    stringResource(R.string.models_admin_tab_config),
                    state.tab == ModelsAdminTab.CONFIG,
                ) { onSetTab(ModelsAdminTab.CONFIG) }
                TabChip(
                    stringResource(R.string.models_admin_tab_models),
                    state.tab == ModelsAdminTab.MODELS,
                ) { onSetTab(ModelsAdminTab.MODELS) }
                TabChip(
                    stringResource(R.string.models_admin_tab_pool),
                    state.tab == ModelsAdminTab.POOL,
                ) { onSetTab(ModelsAdminTab.POOL) }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = io.qwenpaw.node.ui.common.pageContentInsets(),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircle(
                    icon = QwenIcons.arrowLeft,
                    description = stringResource(R.string.action_back),
                    onClick = if (detail != null) onCloseModelDetail else onBack,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (detail != null) detail.name.ifBlank { detail.id } else state.providerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = if (detail != null) onSaveModelDetail else onSave,
                    enabled = !state.saving && !state.busy,
                ) {
                    Text(stringResource(R.string.models_admin_save))
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (detail != null && detailDraft != null) {
                ModelDetailScreen(
                    state = state,
                    model = detail,
                    draft = detailDraft,
                    onChange = onModelDetailChange,
                )
                return@Box
            }
            Column(modifier = Modifier.fillMaxSize()) {
                state.notice?.let { NoticeRow(it, false, onDismissNotice) }
                state.error?.let { NoticeRow(it, true, onDismissNotice) }
                state.oauth?.let { oauth ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (oauth.status == "pending") {
                                    stringResource(R.string.models_admin_oauth_pending)
                                } else if (oauth.status == "completed") {
                                    stringResource(R.string.models_admin_oauth_connected)
                                } else {
                                    oauth.error ?: oauth.status
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissOAuth) {
                                Text(stringResource(R.string.models_admin_oauth_cancel))
                            }
                        }
                    }
                }
                when (state.tab) {
                    ModelsAdminTab.CONFIG -> ConfigTab(
                        state = state,
                        header = header,
                        onSetEnabled = onSetEnabled,
                        onSetName = onSetName,
                        onSetBaseUrl = onSetBaseUrl,
                        onSetAuthMode = onSetAuthMode,
                        onSetChatModel = onSetChatModel,
                        onSetCustomHeaders = onSetCustomHeaders,
                        onSetGenerateKwargs = onSetGenerateKwargs,
                        onBeginSecret = onBeginSecret,
                        onSetSecret = onSetSecret,
                        onClearSecret = onClearSecret,
                        onCancelSecret = onCancelSecret,
                        onDiscover = onDiscover,
                        onDelete = { confirmingDeleteProvider = true },
                        onStartOAuth = onStartOAuth,
                    )

                    ModelsAdminTab.MODELS -> ModelsTab(
                        state = state,
                        header = header,
                        onAddModel = onBeginAddModel,
                        onEditModel = onBeginEditModel,
                        onDeleteModel = { deletingModel = it },
                        onToggleVisibility = onToggleVisibility,
                        onProbe = onProbe,
                        onTestModel = onTestModel,
                        onOpenDetail = onOpenModelDetailById,
                    )

                    ModelsAdminTab.POOL -> PoolTab(
                        state = state,
                        header = header,
                        onSearch = onPoolSearch,
                        onFamily = onPoolFamily,
                        onTab = onPoolTab,
                        onLoad = onPoolLoad,
                        onSelectAll = onSelectAllPool,
                        onSelectModel = onSelectPoolModel,
                        onOpenDetail = onOpenModelDetail,
                    )
                }
            }
        }
    }

    state.editing?.let { editing ->
        ModelEditDialog(
            editing = editing,
            onId = onEditId,
            onName = onEditName,
            onFree = onEditFree,
            onImage = onEditImage,
            onVideo = onEditVideo,
            onType = onEditType,
            onSave = onSaveModel,
            onCancel = onCancelEditModel,
        )
    }

    deletingModel?.let { modelId ->
        DestructiveActionDialog(
            title = stringResource(R.string.models_admin_delete_model),
            message = stringResource(R.string.models_admin_delete_model_message, modelId),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                deletingModel = null
                onDeleteModel(modelId)
            },
            onDismiss = { deletingModel = null },
        )
    }

    if (confirmingDeleteProvider) {
        DestructiveActionDialog(
            title = stringResource(R.string.models_admin_delete_title),
            message = stringResource(R.string.models_admin_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmingDeleteProvider = false
                onDelete()
            },
            onDismiss = { confirmingDeleteProvider = false },
        )
    }
}

@Composable
private fun ConfigTab(
    state: ModelsAdminUiState,
    header: @Composable () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetName: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSetAuthMode: (String) -> Unit,
    onSetChatModel: (String) -> Unit,
    onSetCustomHeaders: (String) -> Unit,
    onSetGenerateKwargs: (String) -> Unit,
    onBeginSecret: () -> Unit,
    onSetSecret: (String) -> Unit,
    onClearSecret: () -> Unit,
    onCancelSecret: () -> Unit,
    onDiscover: () -> Unit,
    onDelete: () -> Unit,
    onStartOAuth: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header()
        if (!state.isQwenPawLocal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.models_admin_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.enabled, onCheckedChange = onSetEnabled)
            }
        }
        if (state.isCustom) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onSetName,
                label = { Text(stringResource(R.string.models_admin_name)) },
                singleLine = true,
           
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.baseUrlOptions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.models_admin_base_url_scenario),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.baseUrlOptions.forEach { option ->
                    TabChip(option.label, state.baseUrl == option.value) {
                        onSetBaseUrl(option.value)
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onSetBaseUrl,
                readOnly = state.freezeUrl,
                enabled = !state.freezeUrl,
                label = { Text(stringResource(R.string.models_admin_base_url)) },
                singleLine = true,
                supportingText = if (state.freezeUrl) {
                    { Text(stringResource(R.string.models_admin_base_url_fixed)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SecretField(
            state = state,
            onBegin = onBeginSecret,
            onSet = onSetSecret,
            onClear = onClearSecret,
            onCancel = onCancelSecret,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.models_admin_auth_mode),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSetAuthMode(if (state.authMode == "api_key") "auth_token" else "api_key") }) {
                Text(state.authMode)
            }
        }
        if (state.chatModel.isNotBlank()) {
            OutlinedTextField(
                value = state.chatModel,
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text(stringResource(R.string.models_admin_chat_model)) },
                singleLine = true,
                supportingText = {
                    Text(stringResource(R.string.models_admin_protocol_readonly))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = state.customHeaders,
            onValueChange = onSetCustomHeaders,
            label = { Text(stringResource(R.string.models_admin_custom_headers)) },
            minLines = 2,
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
       
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.generateKwargs,
            onValueChange = onSetGenerateKwargs,
            label = { Text(stringResource(R.string.models_admin_generate_kwargs)) },
            minLines = 2,
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
       
            modifier = Modifier.fillMaxWidth(),
        )
        state.discover?.let {
            Text(
                text = stringResource(
                    R.string.models_admin_discover_result,
                    it.discoveredCount,
                    it.message,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDiscover, enabled = !state.busy) {
                Text(stringResource(R.string.models_admin_discover))
            }
            if (state.supportsOauth && !state.oauthConnected) {
                TextButton(onClick = onStartOAuth, enabled = !state.busy) {
                    Text(stringResource(R.string.models_admin_oauth))
                }
            } else if (state.oauthConnected) {
                Text(stringResource(R.string.models_admin_oauth_connected))
            }
        }
        if (state.isCustom) {
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.models_admin_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SecretField(
    state: ModelsAdminUiState,
    onBegin: () -> Unit,
    onSet: (String) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = state.apiKey
    Column {
        Text(
            text = stringResource(R.string.models_admin_api_key),
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            draft.clearing -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.models_admin_secret_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }

            draft.replacing || !draft.hasOriginal -> OutlinedTextField(
                value = draft.replacement,
                onValueChange = onSet,
                label = { Text(stringResource(R.string.models_admin_secret_new)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.models_admin_secret_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBegin) {
                    Text(stringResource(R.string.models_admin_secret_replace))
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.models_admin_secret_clear))
                }
            }
        }
        if (draft.replacing && draft.hasOriginal) {
            Text(
                text = stringResource(R.string.models_admin_secret_keep_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelsTab(
    state: ModelsAdminUiState,
    header: @Composable () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (String, String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onToggleVisibility: (String, Boolean) -> Unit,
    onProbe: (String) -> Unit,
    onTestModel: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val models = state.provider?.allModels.orEmpty()
    Column(modifier = Modifier.fillMaxSize()) {
        if (models.isEmpty()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item { header() }
                item {
                    Text(
                        text = stringResource(R.string.models_admin_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    header()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.models_admin_tab_models),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAddModel) {
                            Text(stringResource(R.string.models_admin_add_model))
                        }
                    }
                }
                items(models, key = { it.id }) { model ->
                    val hidden = model.id in (state.provider?.hiddenModelIds.orEmpty())
                    val testing = model.id in state.testingModels
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = model.id + if (hidden) {
                                            " " + stringResource(R.string.models_admin_hidden)
                                        } else {
                                            ""
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            state.modelTestResults[model.id]?.let { test ->
                                Text(
                                    text = if (test.success) {
                                        stringResource(R.string.models_admin_test_ok, test.message)
                                    } else {
                                        stringResource(R.string.models_admin_test_fail, test.message)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (test.success) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    maxLines = 2,
                                )
                            }
                            state.probes[model.id]?.let { probe ->
                                Text(
                                    text = stringResource(
                                        R.string.models_admin_probe_image,
                                        probe.supportsImage?.toString() ?: "?",
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                TextButton(
                                    onClick = { onTestModel(model.id) },
                                    enabled = !testing,
                                ) { Text(stringResource(R.string.models_admin_test)) }
                                TextButton(onClick = { onToggleVisibility(model.id, !hidden) }) {
                                    Text(
                                        if (hidden) {
                                            stringResource(R.string.models_admin_show)
                                        } else {
                                            stringResource(R.string.models_admin_hide)
                                        },
                                    )
                                }
                                TextButton(onClick = { onProbe(model.id) }) {
                                    Text(stringResource(R.string.models_admin_probe))
                                }
                                TextButton(onClick = { onOpenDetail(model.id) }) {
                                    Text(stringResource(R.string.models_admin_open_detail))
                                }
                                TextButton(onClick = { onDeleteModel(model.id) }) {
                                    Text(stringResource(R.string.action_delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PoolTab(
    state: ModelsAdminUiState,
    header: @Composable () -> Unit,
    onSearch: (String) -> Unit,
    onFamily: (String) -> Unit,
    onTab: (String) -> Unit,
    onLoad: () -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onSelectModel: (String, Boolean) -> Unit,
    onOpenDetail: (io.qwenpaw.node.data.model.PoolModelDto) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { header() }
        item {
            OutlinedTextField(
                value = state.poolSearch,
                onValueChange = onSearch,
                label = { Text(stringResource(R.string.models_admin_pool_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("all", "candidates", "selected").forEach { tab ->
                    TabChip(
                        when (tab) {
                            "candidates" -> stringResource(R.string.models_admin_pool_candidates)
                            "selected" -> stringResource(R.string.models_admin_pool_selected)
                            else -> stringResource(R.string.models_admin_pool_all)
                        },
                        state.poolTab == tab,
                    ) { onTab(tab) }
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (listOf("all") + state.pool.families).forEach { family ->
                    TabChip(
                        if (family == "all") stringResource(R.string.models_admin_pool_all) else family,
                        state.poolFamily == family,
                    ) { onFamily(family) }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.models_admin_pool_count,
                        state.pool.total,
                        state.pool.selectedCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onSelectAll(true) }) {
                    Text(stringResource(R.string.models_admin_pool_select_all))
                }
                TextButton(onClick = { onSelectAll(false) }) {
                    Text(stringResource(R.string.models_admin_pool_clear_all))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLoad) {
                    Text(stringResource(R.string.action_refresh))
                }
                if (state.poolLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.models_admin_pool_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.pool.models.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.models_admin_pool_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
        items(state.pool.models, key = { it.id }) { model ->
            val selected = model.id in state.selectedModelIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelectModel(model.id, it) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name.ifBlank { model.id }, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = model.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.isFree) {
                    Text(
                        text = stringResource(R.string.models_admin_is_free),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = { onOpenDetail(model) }) {
                    Text(stringResource(R.string.models_admin_open_detail))
                }
            }
        }
    }
}

@Composable
private fun ModelEditDialog(
    editing: ModelEditState,
    onId: (String) -> Unit,
    onName: (String) -> Unit,
    onFree: (Boolean) -> Unit,
    onImage: (Boolean) -> Unit,
    onVideo: (Boolean) -> Unit,
    onType: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.models_admin_add_model)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = editing.id,
                    onValueChange = onId,
                    label = { Text(stringResource(R.string.models_admin_model_id)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = editing.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.models_admin_model_name)) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.models_admin_model_type),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        onType(if (editing.modelType == "chat") "decision" else "chat")
                    }) { Text(editing.modelType) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = editing.isFree, onCheckedChange = onFree)
                    Text(stringResource(R.string.models_admin_is_free))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = editing.supportsImage, onCheckedChange = onImage)
                    Text(stringResource(R.string.models_admin_supports_image))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = editing.supportsVideo, onCheckedChange = onVideo)
                    Text(stringResource(R.string.models_admin_supports_video))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = editing.id.isNotBlank() && editing.name.isNotBlank() && !editing.generating,
            ) { Text(stringResource(R.string.models_admin_add_model)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun NoticeRow(text: String, error: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (error) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
    }
}
