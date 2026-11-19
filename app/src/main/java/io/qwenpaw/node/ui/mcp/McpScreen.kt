package io.qwenpaw.node.ui.mcp

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.theme.qwenSwitchColors

/** 页内层级：列表 → 详情（白名单 / 策略）。 */
private enum class McpPage { LIST, DETAIL }

/** 详情里的两段。 */
private enum class McpTab(@StringRes val title: Int) {
    TOOLS(R.string.mcp_tab_tools),
    POLICY(R.string.mcp_tab_policy),
}

/**
 * MCP：客户端开关、工具白名单与访问策略。
 *
 * 三件事都是「看一眼状态、必要时停用」。编辑面刻意收窄：
 * 白名单只勾选，策略只改默认效果与逐工具默认效果；
 * 按来源与用户的五维规则表在手机上无法审阅，因此只显示条数并让用户去 Web 控制台。
 */
@Composable
fun McpScreen(
    state: McpUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleTool: (String) -> Unit,
    onSaveTools: () -> Unit,
    onSetDefaultEffect: (String) -> Unit,
    onSetToolEffect: (String, String) -> Unit,
    onSavePolicy: () -> Unit,
    onRetryDetail: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var page by remember { mutableStateOf(McpPage.LIST) }
    var tab by remember { mutableStateOf(McpTab.TOOLS) }

    // 客户端被删除或列表刷新后选中项会消失，此时按列表渲染。
    // 派生判定而不是组合期写状态，理由同技能页。
    val selected = state.selected
    val showDetail = page == McpPage.DETAIL && selected != null

    BackHandler {
        if (page == McpPage.DETAIL) {
            page = McpPage.LIST
            onCloseDetail()
        } else {
            onBack()
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
                            .clickable {
                                if (page == McpPage.DETAIL) {
                                    page = McpPage.LIST
                                    onCloseDetail()
                                } else {
                                    onBack()
                                }
                            },
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
                        text = if (page == McpPage.LIST) "MCP" else (selected?.name ?: "MCP"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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
                !showDetail -> McpList(
                    state = state,
                    onRefresh = onRefresh,
                    onToggle = onToggle,
                    onSelect = { key ->
                        onSelect(key)
                        tab = McpTab.TOOLS
                        page = McpPage.DETAIL
                    },
                )

                selected == null -> CenterBox { CircularProgressIndicator() }

                else -> McpDetail(
                    state = state,
                    tab = tab,
                    onTab = { tab = it },
                    onToggleTool = onToggleTool,
                    onSaveTools = onSaveTools,
                    onSetDefaultEffect = onSetDefaultEffect,
                    onSetToolEffect = onSetToolEffect,
                    onSavePolicy = onSavePolicy,
                    onRetryDetail = onRetryDetail,
                )
            }
        }
    }

    state.notice?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text(stringResource(R.string.mcp_notice_title)) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Text(
                    text = stringResource(R.string.mcp_action_got_it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clickable(onClick = onDismissNotice)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
        )
    }
}

@Composable
private fun McpList(
    state: McpUiState,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = pluralStringResource(
                R.plurals.mcp_label_clients,
                state.clients.size,
                state.clients.size,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardBox {
            if (state.clients.isEmpty()) {
                Text(
                    text = when {
                        state.loading && !state.loaded -> stringResource(R.string.mcp_state_loading)
                        state.error != null ->
                            stringResource(R.string.mcp_state_load_failed, state.error)
                        else -> stringResource(R.string.mcp_state_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRefresh)
                        .padding(horizontal = 14.dp, vertical = 16.dp),
                )
            } else {
                state.clients.forEachIndexed { index, client ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(client.key) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = QwenIcons.blocks,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (client.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = client.name.ifBlank { client.key },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (client.enabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append(McpPolicyEdit.transportLabel(client.transport))
                                        append(" · ")
                                        append(
                                            McpPolicyEdit.endpointLabel(
                                                client.transport, client.url, client.command,
                                            ),
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append(stringResource(R.string.mcp_label_default_policy))
                                        append(McpPolicyEdit.effectLabel(client.accessSummary.defaultEffect))
                                        val oauth = client.oauthStatus
                                        if (oauth != null && !oauth.authorized) {
                                            append(stringResource(R.string.mcp_suffix_oauth_required))
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = client.enabled,
                                onCheckedChange = { onToggle(client.key) },
                                colors = qwenSwitchColors(),
                            )
                        }
                        state.rowErrors[client.key]?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 42.dp, end = 14.dp, bottom = 8.dp),
                            )
                        }
                        if (index != state.clients.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }
        }
        if (state.clients.isNotEmpty()) {
            Text(
                text = stringResource(R.string.mcp_hint_console),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun McpDetail(
    state: McpUiState,
    tab: McpTab,
    onTab: (McpTab) -> Unit,
    onToggleTool: (String) -> Unit,
    onSaveTools: () -> Unit,
    onSetDefaultEffect: (String) -> Unit,
    onSetToolEffect: (String, String) -> Unit,
    onSavePolicy: () -> Unit,
    onRetryDetail: () -> Unit,
) {
    val client = state.selected ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McpTab.entries.forEach { entry ->
                TabChip(
                    text = stringResource(entry.title),
                    selected = entry == tab,
                    onClick = { onTab(entry) },
                )
            }
        }

        when (tab) {
            McpTab.TOOLS -> ToolsSection(
                state = state,
                clientEnabled = client.enabled,
                onToggleTool = onToggleTool,
                onSave = onSaveTools,
                onRetry = onRetryDetail,
            )

            McpTab.POLICY -> PolicySection(
                state = state,
                onSetDefaultEffect = onSetDefaultEffect,
                onSetToolEffect = onSetToolEffect,
                onSave = onSavePolicy,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ToolsSection(
    state: McpUiState,
    clientEnabled: Boolean,
    onToggleTool: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    if (!clientEnabled) {
        // 禁用时服务端直接回空数组，与「这个 MCP 服务端没有工具」表现相同。
        // 不解释这一点，用户会以为工具列表坏了。
        CardBox {
            Text(
                text = stringResource(R.string.mcp_tools_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            )
        }
        return
    }

    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.mcp_tools_hint_checked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when {
                state.toolsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.mcp_tools_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                state.toolsError != null -> Column {
                    Text(
                        text = state.toolsError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.action_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onRetry),
                    )
                }

                state.tools.isEmpty() -> Text(
                    text = stringResource(R.string.mcp_tools_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> state.tools.forEachIndexed { index, tool ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleTool(tool.name) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            tool.description.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Switch(
                            checked = tool.name in state.draftEnabled,
                            onCheckedChange = { onToggleTool(tool.name) },
                            colors = qwenSwitchColors(),
                        )
                    }
                    if (index != state.tools.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }

    if (state.tools.isNotEmpty()) {
        SaveButton(
            label = if (state.savingTools) {
                stringResource(R.string.mcp_action_saving)
            } else {
                stringResource(R.string.mcp_action_save_tools)
            },
            enabled = state.toolsDirty && !state.savingTools,
            onClick = onSave,
        )
        Text(
            text = stringResource(R.string.mcp_tools_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PolicySection(
    state: McpUiState,
    onSetDefaultEffect: (String) -> Unit,
    onSetToolEffect: (String, String) -> Unit,
    onSave: () -> Unit,
) {
    val policy = state.policy
    if (state.policyLoading && policy == null) {
        CenterBox { CircularProgressIndicator() }
        return
    }
    state.policyError?.takeIf { policy == null }?.let { message ->
        CardBox {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            )
        }
        return
    }
    if (policy == null) return

    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.mcp_label_default_effect),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.mcp_hint_default_effect),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McpPolicyEdit.effects.forEach { effect ->
                    TabChip(
                        text = McpPolicyEdit.effectLabel(effect),
                        selected = policy.defaultEffect == effect,
                        onClick = { onSetDefaultEffect(effect) },
                    )
                }
            }
        }
    }

    if (state.tools.isNotEmpty()) {
        CardBox {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.mcp_label_per_tool),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.mcp_hint_per_tool),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                state.tools.forEach { tool ->
                    val current = McpPolicyEdit.toolEffectOf(policy, tool.name)
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TabChip(
                                text = stringResource(R.string.mcp_action_follow),
                                selected = current.isBlank(),
                                onClick = { onSetToolEffect(tool.name, "") },
                            )
                            McpPolicyEdit.effects.forEach { effect ->
                                TabChip(
                                    text = McpPolicyEdit.effectLabel(effect),
                                    selected = current == effect,
                                    onClick = { onSetToolEffect(tool.name, effect) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val overrideCount = policy.clientOverrides.size + policy.toolOverrides.size
    if (overrideCount > 0 || policy.unmanagedRulesCount > 0) {
        CardBox {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.mcp_label_other_rules),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (overrideCount > 0) {
                            append(
                                stringResource(R.string.mcp_rules_override, overrideCount),
                            )
                        }
                        if (policy.unmanagedRulesCount > 0) {
                            append(
                                stringResource(
                                    R.string.mcp_rules_unmanaged,
                                    policy.unmanagedRulesCount,
                                ),
                            )
                        }
                        append(stringResource(R.string.mcp_rules_kept))
                        append(stringResource(R.string.mcp_rules_edit_hint))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    state.policyError?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    SaveButton(
        label = if (state.savingPolicy) {
            stringResource(R.string.mcp_action_saving)
        } else {
            stringResource(R.string.mcp_action_save_policy)
        },
        enabled = !state.savingPolicy,
        onClick = onSave,
    )
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
private fun SaveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
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
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) { content() }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}