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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
                title = "运行配置",
                onBack = onBack,
                action = "保存",
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
            SectionHeader("说明")
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "以下配置属于当前智能体。保存时会整份回写，未列出的字段保持不变。",
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

            SectionHeader("循环与输入")
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    NumberField(
                        label = "最大迭代次数",
                        hint = "单轮任务允许的最大推理-行动次数",
                        value = form.maxIters,
                        onValueChange = { form = form.copy(maxIters = it) },
                    )
                    NumberField(
                        label = "最大输入长度",
                        hint = "单次请求允许的最大输入长度",
                        value = form.maxInputLength,
                        onValueChange = { form = form.copy(maxInputLength = it) },
                    )
                    NumberField(
                        label = "历史保留长度",
                        hint = "上下文中保留的历史长度",
                        value = form.historyMaxLength,
                        onValueChange = { form = form.copy(historyMaxLength = it) },
                    )
                    NumberField(
                        label = "命令超时（秒）",
                        hint = "shell 命令允许的最长执行时间",
                        value = form.shellCommandTimeout,
                        decimal = true,
                        onValueChange = { form = form.copy(shellCommandTimeout = it) },
                    )
                }
            }

            SectionHeader("模型调用")
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    SwitchRow(
                        title = "自动重试",
                        subtitle = "模型接口临时失败时自动重试",
                        checked = form.llmRetryEnabled,
                        onCheckedChange = { form = form.copy(llmRetryEnabled = it) },
                    )
                    Divider()
                    NumberField(
                        label = "最大重试次数",
                        hint = "仅在开启自动重试时生效",
                        value = form.llmMaxRetries,
                        onValueChange = { form = form.copy(llmMaxRetries = it) },
                    )
                }
            }

            SectionHeader("审批模式")
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "审批模式作用于该智能体的全部对话。",
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
                            ?: "未知审批模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader("上下文压缩")
            Card {
                Column(modifier = Modifier.padding(14.dp)) {
                    NumberField(
                        label = "压缩阈值",
                        hint = "0 到 1 之间；达到该比例时触发上下文压缩",
                        value = form.compactThresholdRatio,
                        decimal = true,
                        onValueChange = { form = form.copy(compactThresholdRatio = it) },
                    )
                }
            }

            if (state.notice != null || state.error != null) {
                Text(
                    text = "点此清除提示",
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
