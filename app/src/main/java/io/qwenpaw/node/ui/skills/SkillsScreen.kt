package io.qwenpaw.node.ui.skills

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.remote.SafZipSource
import io.qwenpaw.node.ui.chat.MarkdownText
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.DestructiveActionDialog
import io.qwenpaw.node.ui.common.PlainTextEditor
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.theme.qwenSwitchColors
import kotlinx.coroutines.launch

/** 页内层级。技能详情属于本页的下一级，不占顶层 `Screen`。 */
private enum class SkillPage { LIST, DETAIL }

/**
 * 技能：当前智能体的技能与技能池。
 *
 * 两段信息都来自「一屏能扫完」的量级（实测各 16 条），因此不做搜索与筛选。
 * 技能池只读：池内技能没有 `enabled`，启停的对象是工作区里的那一份。
 */
@Composable
fun SkillsScreen(
    state: SkillsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSkill: (String) -> Unit,
    onOpenPoolSkill: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onBeginEdit: () -> Unit,
    onOptimize: () -> Unit = {},
    onUpdateDraft: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onCreate: (String, String, onResult: (Boolean) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onImportZip: (toPool: Boolean, source: SafZipSource) -> Unit = { _, _ -> },
    onRetryImport: (Map<String, String>) -> Unit = {},
    onDismissImportConflicts: () -> Unit = {},
    onOpenBroadcast: (String) -> Unit = {},
    onToggleBroadcastTarget: (String) -> Unit = {},
    onRunBroadcast: (Boolean) -> Unit = {},
    onDismissBroadcast: () -> Unit = {},
    onOpenConfig: (String) -> Unit = {},
    onOpenPoolAdmin: () -> Unit = {},
    onOpenConsole: (() -> Unit)? = null,
    onBatchEnable: (List<String>) -> Unit = {},
    onBatchDisable: (List<String>) -> Unit = {},
    onBatchDelete: (List<String>) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(SkillPage.LIST) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var batchDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var importToPool by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val source = runCatching {
                    SafZipSource.prepare(context, uri, "skill.zip")
                }.getOrNull()
                if (source != null) onImportZip(importToPool, source)
            }
        }
    }

    WebEntryDialog(pageKey = "skills", onOpenConsole = onOpenConsole)

    if (batchDialog) {
        BatchSkillsDialog(
            names = state.skills.map { it.name },
            busy = state.batchBusy,
            onEnable = onBatchEnable,
            onDisable = onBatchDisable,
            onDelete = onBatchDelete,
            onDismiss = { batchDialog = false },
        )
    }

    // 详情被清空（例如删除后）要退回列表，否则会停在一个空白的详情页。
    // 用派生判定而不是在组合期写状态：组合期写 `page` 会额外触发一轮重组，
    // 且一旦条件持续为真就会不断重组。
    val detailName = state.detail?.name ?: state.poolDetail?.name
    val showDetail = page == SkillPage.DETAIL &&
        (detailName != null || state.edit != null || state.detailLoading)

    BackHandler {
        when {
            state.edit != null -> Unit // 编辑器自己处理返回与未保存确认
            page == SkillPage.DETAIL -> {
                page = SkillPage.LIST
                onCloseDetail()
            }
            else -> onBack()
        }
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
                PageTopBar(
                    title = if (page == SkillPage.LIST) {
                        stringResource(R.string.skills_title)
                    } else {
                        stringResource(R.string.skills_title_detail)
                    },
                    onBack = {
                        if (page == SkillPage.DETAIL) {
                            page = SkillPage.LIST
                            onCloseDetail()
                        } else {
                            onBack()
                        }
                    },
                    action = if (page == SkillPage.LIST) {
                        stringResource(R.string.skills_action_new)
                    } else {
                        null
                    },
                    onAction = { creating = true },
                )
                if (page == SkillPage.LIST && state.skills.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { batchDialog = true }) {
                            Text(stringResource(R.string.skills_batch_action))
                        }
                    }
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
            when {
                // 编辑态整页交给编辑器：它有顶栏与保存按钮，再套一层页面壳会多出一条返回。
                state.edit != null -> PlainTextEditor(
                    title = state.edit.name,
                    backDescription = stringResource(R.string.skills_back_to_detail),
                    draft = state.edit.draft,
                    dirty = state.edit.dirty,
                    saving = state.edit.saving,
                    saved = state.edit.saved,
                    error = state.edit.error,
                    onDraftChange = onUpdateDraft,
                    onSave = onSaveEdit,
                    onExit = onCancelEdit,
                    hint = SkillCopy.frontmatterHint(state.edit.draft),
                )

                showDetail -> SkillDetail(
                    state = state,
                    onBeginEdit = onBeginEdit,
                    onOptimize = onOptimize,
                    onOpenConfig = onOpenConfig,
                    onDelete = { name -> deleting = name },
                )

                state.loading && !state.loaded -> CenterBox { CircularProgressIndicator() }

                else -> SkillList(
                    state = state,
                    onImport = { importDialog = true },
                    onBroadcast = onOpenBroadcast,
                    onPoolAdmin = onOpenPoolAdmin,
                    onRefresh = onRefresh,
                    onOpenSkill = { name ->
                        onOpenSkill(name)
                        page = SkillPage.DETAIL
                    },
                    onOpenPoolSkill = { name ->
                        onOpenPoolSkill(name)
                        page = SkillPage.DETAIL
                    },
                    onSetEnabled = onSetEnabled,
                    onOpenConsole = onOpenConsole,
                )
            }
        }
    }

    if (creating) {
        CreateSkillDialog(
            onDismiss = { creating = false },
            onCreate = { name, content, onResult ->
                onCreate(name, content) { ok ->
                    if (ok) creating = false
                    onResult(ok)
                }
            },
        )
    }

    state.notice?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text(stringResource(R.string.skills_notice_title)) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Text(
                    text = stringResource(R.string.skills_action_got_it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clickable(onClick = onDismissNotice)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
        )
    }

    deleting?.let { name ->
        DestructiveActionDialog(
            title = stringResource(R.string.skills_delete_title, name),
            message = stringResource(R.string.skills_delete_message),
            impacts = listOf(
                stringResource(R.string.skills_delete_impact_files, name),
                stringResource(R.string.skills_delete_impact_enabled),
            ),
            undoable = false,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                deleting = null
                onDelete(name)
            },
            onDismiss = { deleting = null },
        )
    }

    if (importDialog) {
        AlertDialog(
            onDismissRequest = { importDialog = false },
            title = { Text(stringResource(R.string.skills_import_title)) },
            text = { Text(stringResource(R.string.skills_import_message)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        importDialog = false
                        importToPool = false
                        importLauncher.launch("application/zip")
                    }) { Text(stringResource(R.string.skills_import_current)) }
                    TextButton(onClick = {
                        importDialog = false
                        importToPool = true
                        importLauncher.launch("application/zip")
                    }) { Text(stringResource(R.string.skills_import_pool)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { importDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.importConflicts.isNotEmpty()) {
        var names by remember(state.importConflicts) {
            mutableStateOf(
                state.importConflicts.associate {
                    it.skillName to (it.suggestedName ?: it.skillName)
                },
            )
        }
        AlertDialog(
            onDismissRequest = onDismissImportConflicts,
            title = { Text(stringResource(R.string.skills_conflict_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        stringResource(R.string.skills_conflict_message),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.importConflicts.forEach { conflict ->
                        OutlinedTextField(
                            value = names[conflict.skillName].orEmpty(),
                            onValueChange = { value ->
                                names = names + (conflict.skillName to value)
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.skills_conflict_field,
                                        conflict.skillName,
                                    ),
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onRetryImport(names.filterValues { it.isNotBlank() }) }) {
                    Text(stringResource(R.string.skills_action_rename_import))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissImportConflicts) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.broadcast?.let { broadcast ->
        AlertDialog(
            onDismissRequest = onDismissBroadcast,
            title = { Text(stringResource(R.string.skills_broadcast_title, broadcast.skillName)) },
            text = {
                Column(
                    modifier = Modifier
                        .height(400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (state.agentOptions.isEmpty()) {
                        Text(
                            stringResource(R.string.skills_broadcast_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.agentOptions.forEach { agent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleBroadcastTarget(agent.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = agent.id in broadcast.targets,
                                onCheckedChange = { onToggleBroadcastTarget(agent.id) },
                            )
                            Text(
                                text = agent.name.ifBlank { agent.id },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    broadcast.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (broadcast.conflicts.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.skills_broadcast_conflicts,
                                broadcast.conflicts.joinToString {
                                    it.workspaceName.ifBlank { it.workspaceId }
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.skills_broadcast_conflict_hint),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                if (broadcast.conflicts.isNotEmpty()) {
                    TextButton(enabled = !broadcast.running, onClick = { onRunBroadcast(true) }) {
                        Text(stringResource(R.string.skills_action_overwrite_retry))
                    }
                } else {
                    TextButton(enabled = !broadcast.running, onClick = { onRunBroadcast(false) }) {
                        Text(stringResource(R.string.skills_action_broadcast))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBroadcast) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SkillList(
    state: SkillsUiState,
    onImport: () -> Unit,
    onBroadcast: (String) -> Unit,
    onPoolAdmin: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSkill: (String) -> Unit,
    onOpenPoolSkill: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConsoleHint(onOpenConsole = onOpenConsole)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onImport) {
                Text(stringResource(R.string.skills_action_import_zip))
            }
            TextButton(onClick = onPoolAdmin) {
                Text(stringResource(R.string.skills_action_pool_admin))
            }
        }
        SectionTitle(
            title = stringResource(R.string.skills_section_current),
            trailing = pluralStringResource(
                R.plurals.skills_count,
                state.skills.size,
                state.skills.size,
            ),
        )
        CardBox {
            if (state.skills.isEmpty()) {
                EmptyLine(
                    text = when {
                        state.loading -> stringResource(R.string.skills_state_loading)
                        state.error != null ->
                            stringResource(R.string.skills_state_load_failed, state.error)
                        else -> stringResource(R.string.skills_state_empty_current)
                    },
                    error = state.error != null,
                    onClick = onRefresh,
                )
            } else {
                state.skills.forEachIndexed { index, skill ->
                    SkillRow(
                        emoji = skill.emoji,
                        name = skill.name,
                        subtitle = buildString {
                            append(SkillCopy.sourceLabel(skill.source))
                            skill.versionText.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                            skill.description.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        },
                        enabled = skill.enabled,
                        onClick = { onOpenSkill(skill.name) },
                        onToggle = { onSetEnabled(skill.name, !skill.enabled) },
                        error = state.rowErrors[skill.name],
                    )
                    if (index != state.skills.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                }
            }
        }

        SectionTitle(
            title = stringResource(R.string.skills_section_pool),
            trailing = stringResource(R.string.skills_badge_readonly),
        )
        CardBox {
            if (state.pool.isEmpty()) {
                EmptyLine(
                    text = when {
                        state.loading -> stringResource(R.string.skills_state_loading)
                        state.poolError != null ->
                            stringResource(R.string.skills_state_load_failed, state.poolError)
                        else -> stringResource(R.string.skills_state_empty_pool)
                    },
                    error = state.poolError != null,
                    onClick = onRefresh,
                )
            } else {
                state.pool.forEachIndexed { index, skill ->
                    SkillRow(
                        emoji = skill.emoji,
                        name = skill.name,
                        subtitle = buildString {
                            append(SkillCopy.sourceLabel(skill.source))
                            SkillCopy.syncStatusLabel(skill.syncStatus).takeIf { it.isNotBlank() }
                                ?.let { append(" · $it") }
                            skill.description.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        },
                        enabled = null,
                        // 池内技能没有启停，点一行就是看详情。
                        onClick = { onOpenPoolSkill(skill.name) },
                        onToggle = null,
                        error = null,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onBroadcast(skill.name) }) {
                            Text(stringResource(R.string.skills_action_broadcast))
                        }
                    }
                    if (index != state.pool.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                Text(
                    text = stringResource(R.string.skills_pool_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SkillDetail(
    state: SkillsUiState,
    onBeginEdit: () -> Unit,
    onOptimize: () -> Unit,
    onOpenConfig: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val detail = state.detail
    val poolDetail = state.poolDetail

    if (state.detailLoading) {
        CenterBox { CircularProgressIndicator() }
        return
    }
    state.detailError?.let { message ->
        CenterBox {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }
    if (detail == null && poolDetail == null) {
        CenterBox {
            Text(
                text = stringResource(R.string.skills_detail_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val name = detail?.name ?: poolDetail!!.name
    val description = detail?.description ?: poolDetail!!.description
    val source = detail?.source ?: poolDetail!!.source
    val versionText = detail?.versionText ?: poolDetail!!.versionText
    val channels = detail?.channels ?: emptyList()
    val preload = detail?.preload ?: false
    val updated = detail?.lastUpdated ?: poolDetail!!.lastUpdated
    val content = detail?.content ?: poolDetail!!.content
    val requirements = detail?.requirements ?: poolDetail!!.requirements

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardBox {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                MetaLine(stringResource(R.string.skills_label_source), SkillCopy.sourceLabel(source))
                if (versionText.isNotBlank()) {
                    MetaLine(stringResource(R.string.skills_label_version), versionText)
                }
                if (detail != null) {
                    MetaLine(stringResource(R.string.skills_label_channels), SkillCopy.channelsLabel(channels))
                }
                if (detail != null) {
                    MetaLine(
                        stringResource(R.string.skills_label_preload),
                        if (preload) {
                            stringResource(R.string.skills_value_on)
                        } else {
                            stringResource(R.string.skills_value_off)
                        },
                    )
                }
                val updatedLabel = SkillCopy.updatedLabel(updated)
                if (updatedLabel.isNotBlank()) {
                    MetaLine(stringResource(R.string.skills_label_updated), updatedLabel)
                }
            }
        }

        if (!requirements.isEmpty) {
            CardBox {
                Column(modifier = Modifier.padding(14.dp)) {
                    // 需求是「跑这个技能需要什么」，用户在启用前就该看到，
                    // 否则只会在一次失败的调用之后才发现少了一个命令行工具。
                    Text(
                        text = stringResource(R.string.skills_section_requirements),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    RequirementLine(
                        stringResource(R.string.skills_requirement_bins),
                        requirements.requireBins,
                    )
                    RequirementLine(
                        stringResource(R.string.skills_requirement_envs),
                        requirements.requireEnvs,
                    )
                    RequirementLine(
                        stringResource(R.string.skills_requirement_mcps),
                        requirements.requireMcps,
                    )
                }
            }
        }

        // 操作放在正文之前：正文可以很长（实测 SKILL.md 常有两三百行），
        // 放在后面等于把「编辑」「删除」藏到滚动末尾。
        if (detail != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton(
                    text = stringResource(R.string.skills_action_edit_content),
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = onBeginEdit,
                )
                ActionButton(
                    text = stringResource(R.string.skill_config_title),
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenConfig(name) },
                )
                ActionButton(
                    text = stringResource(R.string.action_delete),
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onDelete(name) },
                )
            }
            Spacer(Modifier.height(6.dp))
            ActionButton(
                text = stringResource(R.string.skills_action_ai_optimize),
                primary = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOptimize,
            )
        } else {
            Text(
                text = stringResource(R.string.skills_detail_readonly),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CardBox {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "SKILL.md",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                if (content.isBlank()) {
                    Text(
                        text = stringResource(R.string.skills_detail_no_content),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MarkdownText(markdown = content)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 新建技能。
 *
 * 正文预填一段最小的合法 frontmatter：服务端要求 `name` 与 `description` 都非空，
 * 空白正文会让第一次创建必然失败。名称仍由用户填写，预填只解决「照着写」的问题。
 */
@Composable
private fun CreateSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, onResult: (Boolean) -> Unit) -> Unit,
) {
    val template = stringResource(R.string.skills_create_template)
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf(template) }
    var submitting by remember { mutableStateOf(false) }

    val hint = when {
        name.isBlank() -> stringResource(R.string.skills_error_name_required)
        else -> SkillCopy.frontmatterHint(content)
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.skills_dialog_create)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skills_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.skills_field_content)) },
                    minLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                hint?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Text(
                text = if (submitting) {
                    stringResource(R.string.skills_action_creating)
                } else {
                    stringResource(R.string.skills_action_create)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (hint == null && !submitting) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable(enabled = hint == null && !submitting) {
                        submitting = true
                        // frontmatter 里 name 留空时用输入框的名称补上：
                        // 服务端校验的是 frontmatter，界面上填的名字只是目录名。
                        val filled = content.replaceFirst("name: ", "name: $name")
                        onCreate(name.trim(), filled) { submitting = false }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
        dismissButton = {
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = !submitting, onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}

@Composable
private fun SkillRow(
    emoji: String,
    name: String,
    subtitle: String,
    enabled: Boolean?,
    onClick: () -> Unit,
    onToggle: (() -> Unit)?,
    error: String?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (emoji.isNotBlank()) {
                    Text(text = emoji, fontSize = 18.sp)
                } else {
                    Icon(
                        imageVector = QwenIcons.sparkles,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (enabled == false) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled == false) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onToggle != null && enabled != null) {
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggle() },
                    colors = qwenSwitchColors(),
                )
            }
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 52.dp, end = 14.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun RequirementLine(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = items.joinToString(stringResource(R.string.skills_list_separator)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MetaLine(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (primary) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun PageTopBar(
    title: String,
    onBack: () -> Unit,
    action: String?,
    onAction: () -> Unit,
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
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        action?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

@Composable
private fun EmptyLine(text: String, error: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    )
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BatchSkillsDialog(
    names: List<String>,
    busy: Boolean,
    onEnable: (List<String>) -> Unit,
    onDisable: (List<String>) -> Unit,
    onDelete: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.skills_batch_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.skills_batch_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (names.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_batch_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                names.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (name in selected) {
                                    selected - name
                                } else {
                                    selected + name
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = name in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + name else selected - name
                            },
                        )
                        Text(text = name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = selected.isNotEmpty() && !busy,
                    onClick = {
                        onEnable(selected.toList())
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.skills_batch_enable))
                }
                TextButton(
                    enabled = selected.isNotEmpty() && !busy,
                    onClick = {
                        onDisable(selected.toList())
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.skills_batch_disable))
                }
                TextButton(
                    enabled = selected.isNotEmpty() && !busy,
                    onClick = {
                        onDelete(selected.toList())
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.skills_batch_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}