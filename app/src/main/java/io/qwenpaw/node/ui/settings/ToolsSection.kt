package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.ToolConfigFieldDto
import io.qwenpaw.node.data.model.ToolDto

/**
 * 工具开关。
 *
 * 每行两个开关：启用与异步执行。后者只对已启用的工具开放——关掉的工具不会被执行，
 * 给它保留一个异步开关只会让人以为设置没生效。
 */
@Composable
internal fun ToolsSection(
    tools: List<ToolDto>,
    loading: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onAsync: (String, Boolean) -> Unit,
    /** 保存工具配置。参数是工具名与已填好的字段值。 */
    onSaveConfig: (String, Map<String, String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // 按分类分组，保持服务端顺序；没有分类的排在最后。
    val grouped = remember(tools) {
        tools.groupBy { it.category.orEmpty() }.toList()
            .sortedBy { (category, _) -> if (category.isBlank()) 1 else 0 }
    }
    // 正在编辑配置的工具。`requires_config` 为真但没有字段定义的工具不进这里：
    // 那是服务端声明了配置却没有下发规格，客户端没有可填的东西。
    var configuring by remember { mutableStateOf<ToolDto?>(null) }
    val configurable = remember(tools) {
        tools.associateBy { it.name }.filterValues { it.requiresConfig && !it.configFields.isNullOrEmpty() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        if (tools.isEmpty()) {
            SectionHeader("工具")
            Card {
                Text(
                    text = when {
                        loading -> "正在加载"
                        // 空列表 + 错误时要说「加载失败」而不是「没有工具」：
                        // 后者会让用户以为服务端确实一个工具都没装。
                        error != null -> "加载失败：$error"
                        else -> "没有可用的工具"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
            }
        }
        grouped.forEach { (category, list) ->
            SectionHeader(category.ifBlank { "未分类" }, trailing = "${list.size} 个")
            Card {
                list.forEachIndexed { index, tool ->
                    SwitchRow(
                        title = "${tool.icon} ${tool.name}",
                        subtitle = tool.description.ifBlank {
                            if (tool.asyncExecution) "已启用 · 异步执行" else "已启用"
                        },
                        checked = tool.enabled,
                        onCheckedChange = { onToggle(tool.name) },
                    )
                    if (tool.enabled) {
                        Divider()
                        SwitchRow(
                            title = "异步执行",
                            subtitle = "超时后转入后台继续跑，结果稍后送达",
                            checked = tool.asyncExecution,
                            onCheckedChange = { onAsync(tool.name, it) },
                        )
                    }
                    if (configurable.containsKey(tool.name)) {
                        Divider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { configuring = tool }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "配置",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "填写${configurable[tool.name]!!.configFields!!.size} 项参数后才能使用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "填写",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    if (index != list.lastIndex) Divider()
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    configuring?.let { tool ->
        ToolConfigDialog(
            tool = tool,
            onDismiss = { configuring = null },
            onSave = { values ->
                onSaveConfig(tool.name, values)
                configuring = null
            },
        )
    }
}

/**
 * 工具配置表单。
 *
 * 按 `config_fields.type` 渲染，密码字段默认遮罩。提交时把值转成字符串交给上层，
 * 上层负责按类型序列化——表单本身不关心服务端要的是数字还是布尔。
 */
@Composable
private fun ToolConfigDialog(
    tool: ToolDto,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    val fields = tool.configFields.orEmpty()
    val initial = remember(tool.name) {
        fields.associate { field ->
            field.name to initialValue(field, tool.configValues?.get(field.name))
        }
    }
    var values by remember(tool.name) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tool.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                fields.forEach { field ->
                    OutlinedTextField(
                        value = values[field.name].orEmpty(),
                        onValueChange = { values = values + (field.name to it) },
                        label = { Text(field.label.ifBlank { field.name }) },
                        singleLine = true,
                        // 服务端把密码字段打成 `***`，原样回显等于把占位符写回去。
                        placeholder = if (field.placeholder.isNullOrBlank()) null else {
                            { Text(field.placeholder) }
                        },
                        isError = field.required && values[field.name].isNullOrBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                    if (!field.help.isNullOrBlank()) {
                        Text(
                            text = field.help,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                if (fields.isEmpty()) {
                    Text(
                        text = "该工具没有可填的配置项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Text(
                text = "保存",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clickable { onSave(values) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
        dismissButton = {
            Text(
                text = "取消",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}

/**
 * 配置项的初始值。
 *
 * 服务端把敏感字段换成 `***`，那是个占位符而不是真实值：原样提交会把密钥写成三个星号，
 * 因此遇到它一律当作空。其余字段取已有的值，没有则用默认值。
 */
internal fun initialValue(field: ToolConfigFieldDto, current: kotlinx.serialization.json.JsonElement?): String {
    val text = when (val v = current) {
        null -> ""
        is kotlinx.serialization.json.JsonPrimitive -> v.content
        else -> v.toString()
    }
    if (text == "***") return ""
    if (text.isNotEmpty()) return text
    return (field.default as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
}