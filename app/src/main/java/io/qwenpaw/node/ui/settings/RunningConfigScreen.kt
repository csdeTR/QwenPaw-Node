package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.chat.APPROVAL_OPTIONS
import io.qwenpaw.node.ui.common.AdminTopBar

/**
 * 运行配置。
 *
 * 服务端要求 PUT 提交完整对象，因此这里只改界面上出现的字段，其余原样回写。
 * 页面本身不解析 22 个字段；`RunningConfigEdit` 负责在原始 JSON 上做局部替换。
 */
@Composable
fun RunningConfigScreen(
    state: RunningConfigUiState,
    onBack: () -> Unit,
    onEnsureLoaded: () -> Unit,
    onSave: (RunningConfigForm) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var form by remember(state.raw) { mutableStateOf(state.form) }

    LaunchedEffect(Unit) { onEnsureLoaded() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AdminTopBar(
                title = stringResource(R.string.runconfig_title),
                onBack = onBack,
                action = stringResource(R.string.action_save),
                busy = state.saving,
                onAction = { onSave(form) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionHeader(stringResource(R.string.runconfig_section_note))
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.runconfig_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.loading && !state.loaded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            state.notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            SectionHeader(stringResource(R.string.runconfig_section_loop))
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    NumberField(
                        label = stringResource(R.string.runconfig_max_iters),
                        hint = stringResource(R.string.runconfig_max_iters_hint),
                        value = form.maxIters,
                        onValueChange = { form = form.copy(maxIters = it) },
                    )
                    NumberField(
                        label = stringResource(R.string.runconfig_max_input_length),
                        hint = stringResource(R.string.runconfig_max_input_length_hint),
                        value = form.maxInputLength,
                        onValueChange = { form = form.copy(maxInputLength = it) },
                    )
                    NumberField(
                        label = stringResource(R.string.runconfig_history_length),
                        hint = stringResource(R.string.runconfig_history_length_hint),
                        value = form.historyMaxLength,
                        onValueChange = { form = form.copy(historyMaxLength = it) },
                    )
                    NumberField(
                        label = stringResource(R.string.runconfig_shell_timeout),
                        hint = stringResource(R.string.runconfig_shell_timeout_hint),
                        value = form.shellCommandTimeout,
                        decimal = true,
                        onValueChange = { form = form.copy(shellCommandTimeout = it) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.runconfig_section_model))
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    SwitchRow(
                        title = stringResource(R.string.runconfig_retry_title),
                        subtitle = stringResource(R.string.runconfig_retry_hint),
                        checked = form.llmRetryEnabled,
                        onCheckedChange = { form = form.copy(llmRetryEnabled = it) },
                    )
                    Divider()
                    NumberField(
                        label = stringResource(R.string.runconfig_max_retries),
                        hint = stringResource(R.string.runconfig_max_retries_hint),
                        value = form.llmMaxRetries,
                        onValueChange = { form = form.copy(llmMaxRetries = it) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.runconfig_section_approval))
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.runconfig_approval_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        APPROVAL_OPTIONS.forEach { option ->
                            Chip(
                                text = option.label,
                                selected = form.approvalLevel == option.code,
                                onClick = { form = form.copy(approvalLevel = option.code) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = APPROVAL_OPTIONS.firstOrNull { it.code == form.approvalLevel }?.hint
                            ?: stringResource(R.string.runconfig_approval_unknown),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader(stringResource(R.string.runconfig_section_compact))
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    NumberField(
                        label = stringResource(R.string.runconfig_compact_threshold),
                        hint = stringResource(R.string.runconfig_compact_threshold_hint),
                        value = form.compactThresholdRatio,
                        decimal = true,
                        onValueChange = { form = form.copy(compactThresholdRatio = it) },
                    )
                }
            }

            if (state.notice != null || state.error != null) {
                Text(
                    text = stringResource(R.string.runconfig_action_clear_notice),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .clickable(onClick = onDismissNotice),
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    hint: String,
    value: String,
    decimal: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
