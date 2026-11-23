package io.qwenpaw.node.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.ActiveModelDto
import io.qwenpaw.node.ui.chat.ModelChoice
import io.qwenpaw.node.ui.chat.OptionDialog
import io.qwenpaw.node.ui.chat.OptionItem
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.theme.QwenIcons
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 本页会弹出的选择器。用一个枚举驱动同一个对话框，避免六个状态各写一个弹窗。 */
private enum class PickerKind {
    GLOBAL_CHAT,
    GLOBAL_DECISION,
    AGENT_PRIMARY,
    AGENT_DECISION,
    AGENT_SUBAGENT,
    FALLBACK,
}

/** 「清除」选项的 key。模型键里不会出现双下划线，不会与真实选项冲突。 */
private const val NONE_KEY = "__none__"

/**
 * 默认模型：全局与当前智能体两个页签。
 *
 * 全局改动选中即保存；智能体改动先在本页暂存，统一由右上角保存提交，
 * 因为失败、子智能体与备用模型是一整份快照，逐项提交会出现「主模型换了、备用还是旧的」。
 */
@Composable
fun ModelDefaultsScreen(
    state: ModelDefaultsUiState,
    onBack: () -> Unit,
    onSelectTab: (DefaultsTab) -> Unit,
    onRetry: () -> Unit,
    onGlobalChat: (String, String) -> Unit,
    onGlobalDecision: (String, String) -> Unit,
    onLlmEnabled: (Boolean) -> Unit = {},
    onLlmMode: (String) -> Unit = {},
    onAgentPrimary: (String, String) -> Unit,
    onAgentDecision: (ActiveModelDto?) -> Unit,
    onAgentSubagent: (ActiveModelDto?) -> Unit,
    onFallbackEnabled: (Boolean) -> Unit,
    onAddFallback: (String, String) -> Unit,
    onRemoveFallback: (Int) -> Unit,
    onThinking: (String?, Long?) -> Unit,
    onSave: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var picker by remember { mutableStateOf<PickerKind?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = io.qwenpaw.node.ui.common.pageContentInsets(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                AdminTopBar(
                    title = stringResource(R.string.models_rework_defaults_title),
                    onBack = onBack,
                    action = if (state.tab == DefaultsTab.AGENT) {
                        stringResource(R.string.action_save)
                    } else {
                        null
                    },
                    busy = state.saving,
                    onAction = onSave,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TabChip(
                        label = stringResource(R.string.models_rework_defaults_tab_global),
                        selected = state.tab == DefaultsTab.GLOBAL,
                        onClick = { onSelectTab(DefaultsTab.GLOBAL) },
                    )
                    TabChip(
                        label = stringResource(R.string.models_rework_defaults_tab_agent),
                        selected = state.tab == DefaultsTab.AGENT,
                        onClick = { onSelectTab(DefaultsTab.AGENT) },
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

                state.choices.isEmpty() && state.loaded && state.error != null -> Column(
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

                state.tab == DefaultsTab.GLOBAL -> GlobalTab(
                    state = state,
                    onPickChat = { picker = PickerKind.GLOBAL_CHAT },
                    onPickDecision = { picker = PickerKind.GLOBAL_DECISION },
                    onDismissNotice = onDismissNotice,
                    onLlmEnabled = onLlmEnabled,
                    onLlmMode = onLlmMode,
                )

                else -> AgentTab(
                    state = state,
                    onPick = { picker = it },
                    onFallbackEnabled = onFallbackEnabled,
                    onRemoveFallback = onRemoveFallback,
                    onThinking = onThinking,
                    onDismissNotice = onDismissNotice,
                )
            }
        }
    }

    val kind = picker
    if (kind != null) {
        ModelPickerDialog(
            kind = kind,
            state = state,
            onPick = { choice ->
                picker = null
                when (kind) {
                    PickerKind.GLOBAL_CHAT -> onGlobalChat(choice.providerId, choice.modelId)
                    PickerKind.GLOBAL_DECISION -> onGlobalDecision(choice.providerId, choice.modelId)
                    PickerKind.AGENT_PRIMARY -> onAgentPrimary(choice.providerId, choice.modelId)
                    PickerKind.AGENT_DECISION -> onAgentDecision(
                        ActiveModelDto(choice.providerId, choice.modelId),
                    )
                    PickerKind.AGENT_SUBAGENT -> onAgentSubagent(
                        ActiveModelDto(choice.providerId, choice.modelId),
                    )
                    PickerKind.FALLBACK -> onAddFallback(choice.providerId, choice.modelId)
                }
            },
            onClear = {
                picker = null
                when (kind) {
                    PickerKind.AGENT_DECISION -> onAgentDecision(null)
                    PickerKind.AGENT_SUBAGENT -> onAgentSubagent(null)
                    PickerKind.GLOBAL_CHAT,
                    PickerKind.GLOBAL_DECISION,
                    PickerKind.AGENT_PRIMARY,
                    PickerKind.FALLBACK -> Unit
                }
            },
            onDismiss = { picker = null },
        )
    }
}

@Composable
private fun GlobalTab(
    state: ModelDefaultsUiState,
    onPickChat: () -> Unit,
    onPickDecision: () -> Unit,
    onDismissNotice: () -> Unit,
    onLlmEnabled: (Boolean) -> Unit,
    onLlmMode: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoticeBlock(state = state, onDismissNotice = onDismissNotice)
        DefaultsCard {
            SelectorRow(
                label = stringResource(R.string.models_rework_defaults_chat),
                value = state.labelOf(state.globalChat)
                    ?: stringResource(R.string.models_rework_not_set),
                onClick = onPickChat,
            )
            SectionDivider()
            SelectorRow(
                label = stringResource(R.string.models_rework_defaults_decision),
                value = state.labelOf(state.globalDecision)
                    ?: stringResource(R.string.models_rework_defaults_decision_unset),
                onClick = onPickDecision,
            )
        }
        state.llmRouting?.let { routing ->
            val enabled = routing["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
            val mode = routing["mode"]?.jsonPrimitive?.contentOrNull ?: "local_first"
            DefaultsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.models_llm_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = enabled,
                        enabled = !state.llmSaving,
                        onCheckedChange = onLlmEnabled,
                    )
                }
                Text(
                    text = stringResource(R.string.models_llm_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (enabled) {
                    SectionDivider()
                    SelectorRow(
                        label = stringResource(R.string.models_llm_mode_label),
                        value = stringResource(
                            if (mode == "cloud_first") {
                                R.string.models_llm_mode_cloud
                            } else {
                                R.string.models_llm_mode_local
                            },
                        ),
                        onClick = {
                            onLlmMode(if (mode == "cloud_first") "local_first" else "cloud_first")
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.models_default_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun AgentTab(
    state: ModelDefaultsUiState,
    onPick: (PickerKind) -> Unit,
    onFallbackEnabled: (Boolean) -> Unit,
    onRemoveFallback: (Int) -> Unit,
    onThinking: (String?, Long?) -> Unit,
    onDismissNotice: () -> Unit,
) {
    if (state.agentId.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.models_rework_agent_no_agent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoticeBlock(state = state, onDismissNotice = onDismissNotice)

        DefaultsCard {
            SelectorRow(
                label = stringResource(R.string.models_rework_agent_primary),
                value = state.labelOf(state.agentChat)
                    ?: stringResource(R.string.models_follow_global),
                onClick = { onPick(PickerKind.AGENT_PRIMARY) },
            )
            Text(
                text = stringResource(R.string.models_rework_agent_primary_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            SectionDivider()
            SelectorRow(
                label = stringResource(R.string.models_rework_agent_decision),
                value = state.labelOf(state.agentDecision)
                    ?: stringResource(R.string.models_rework_agent_decision_unset),
                onClick = { onPick(PickerKind.AGENT_DECISION) },
            )
            SectionDivider()
            SelectorRow(
                label = stringResource(R.string.models_rework_agent_subagent),
                value = state.labelOf(state.agentSubagent)
                    ?: stringResource(R.string.models_rework_agent_subagent_same),
                onClick = { onPick(PickerKind.AGENT_SUBAGENT) },
            )
        }

        DefaultsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.models_rework_agent_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.fallbackEnabled, onCheckedChange = onFallbackEnabled)
            }
            Text(
                text = stringResource(R.string.models_rework_agent_fallback_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.fallbackEnabled) {
                if (state.fallbacks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.models_rework_agent_fallback_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    state.fallbacks.forEachIndexed { index, slot ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.labelOf(slot) ?: slot.model.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.models_rework_agent_fallback_remove),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onRemoveFallback(index) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.models_rework_agent_fallback_add),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(PickerKind.FALLBACK) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }

        ThinkingSection(state = state, onThinking = onThinking)
    }
}

/** 思考强度。模型未声明支持时整段隐藏，而不是显示一组点了无效的按钮。 */
@Composable
private fun ThinkingSection(
    state: ModelDefaultsUiState,
    onThinking: (String?, Long?) -> Unit,
) {
    val control = state.thinkingControl ?: return
    val effort = control.kind == "effort" && control.efforts.isNotEmpty()
    val budget = control.kind == "budget"
    if (!effort && !budget) return

    DefaultsCard {
        Text(
            text = stringResource(R.string.models_rework_agent_thinking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                label = stringResource(R.string.models_rework_picker_follow),
                selected = state.thinkingLevel == null,
                onClick = { onThinking(null, null) },
            )
            if (effort) {
                if (control.supportsOff) {
                    FilterChip(
                        label = stringResource(R.string.chattop_thinking_off),
                        selected = state.thinkingLevel == "off",
                        onClick = { onThinking("off", null) },
                    )
                }
                control.efforts.forEach { level ->
                    FilterChip(
                        label = effortLabel(level),
                        selected = state.thinkingLevel == level,
                        onClick = { onThinking(level, null) },
                    )
                }
            }
            if (budget) {
                thinkingBudgetStops(control).forEach { tokens ->
                    FilterChip(
                        label = stringResource(R.string.models_rework_budget_chip, tokens),
                        selected = state.thinkingLevel == "budget" &&
                            state.thinkingBudget == tokens,
                        onClick = { onThinking("budget", tokens) },
                    )
                }
            }
        }
    }
}

@Composable
private fun effortLabel(level: String): String = when (level) {
    "minimal" -> stringResource(R.string.chattop_thinking_minimal)
    "low" -> stringResource(R.string.chattop_thinking_low)
    "medium" -> stringResource(R.string.chattop_thinking_medium)
    "high" -> stringResource(R.string.chattop_thinking_high)
    "xhigh" -> stringResource(R.string.chattop_thinking_xhigh)
    "max" -> stringResource(R.string.chattop_thinking_max)
    else -> level
}

@Composable
private fun NoticeBlock(state: ModelDefaultsUiState, onDismissNotice: () -> Unit) {
    state.error?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDismissNotice)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
    state.notice?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDismissNotice)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DefaultsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) { content() }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * 一个模型选择行。
 *
 * 当前值放第二行：模型名常超过 30 个字符，右对齐会被截成看不出是哪一个。
 */
@Composable
private fun SelectorRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

/**
 * 模型选择框。
 *
 * 只有智能体决策模型与子智能体模型给「清除」：两者的空值有明确含义
 * （沿用全局、与主模型相同），全局的两项必须填具体模型。
 */
@Composable
private fun ModelPickerDialog(
    kind: PickerKind,
    state: ModelDefaultsUiState,
    onPick: (ModelChoice) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val titleLabel = when (kind) {
        PickerKind.GLOBAL_CHAT -> stringResource(R.string.models_rework_defaults_chat)
        PickerKind.GLOBAL_DECISION -> stringResource(R.string.models_rework_defaults_decision)
        PickerKind.AGENT_PRIMARY -> stringResource(R.string.models_rework_agent_primary)
        PickerKind.AGENT_DECISION -> stringResource(R.string.models_rework_agent_decision)
        PickerKind.AGENT_SUBAGENT -> stringResource(R.string.models_rework_agent_subagent)
        PickerKind.FALLBACK -> stringResource(R.string.models_rework_agent_fallback)
    }
    val title = if (kind == PickerKind.FALLBACK) {
        stringResource(R.string.models_rework_agent_fallback_pick)
    } else {
        stringResource(R.string.models_rework_picker_title, titleLabel)
    }
    val current = when (kind) {
        PickerKind.GLOBAL_CHAT -> state.globalChat
        PickerKind.GLOBAL_DECISION -> state.globalDecision
        PickerKind.AGENT_PRIMARY -> state.agentChat
        PickerKind.AGENT_DECISION -> state.agentDecision
        PickerKind.AGENT_SUBAGENT -> state.agentSubagent
        PickerKind.FALLBACK -> null
    }
    val currentKey = slotKey(current?.providerId, current?.model)
    val decisionPicker = kind == PickerKind.GLOBAL_DECISION || kind == PickerKind.AGENT_DECISION
    val visibleChoices = if (decisionPicker) {
        decisionChoices(state.choices)
    } else {
        state.choices
    }
    val taken = state.fallbacks.map { slotKey(it.providerId, it.model) }.toSet()
    val items = visibleChoices
        .filterNot { kind == PickerKind.FALLBACK && it.key in taken }
        .map { choice ->
            OptionItem(
                key = choice.key,
                label = choice.modelName,
                sublabel = choice.providerName,
                selected = choice.key == currentKey,
            )
        }
    val leading = when (kind) {
        PickerKind.AGENT_DECISION -> OptionItem(
            key = NONE_KEY,
            label = stringResource(R.string.models_rework_agent_decision_unset),
            selected = current == null,
        )
        PickerKind.AGENT_SUBAGENT -> OptionItem(
            key = NONE_KEY,
            label = stringResource(R.string.models_rework_agent_subagent_same),
            selected = current == null,
        )
        else -> null
    }
    OptionDialog(
        title = title,
        items = items,
        leading = leading,
        emptyHint = if (decisionPicker && visibleChoices.isEmpty()) {
            stringResource(R.string.models_rework_no_decision_models)
        } else {
            stringResource(R.string.models_rework_no_models_hint)
        },
        onPick = { key ->
            if (key == NONE_KEY) {
                onClear()
            } else {
                state.choices.firstOrNull { it.key == key }?.let(onPick)
            }
        },
        onDismiss = onDismiss,
    )
}
