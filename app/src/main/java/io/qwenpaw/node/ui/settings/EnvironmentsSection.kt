package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.EnvCatalogDto
import io.qwenpaw.node.data.model.EnvVarDto

/**
 * 环境变量。
 *
 * **值默认遮罩。** 服务端 `GET /api/envs` 返回明文（落盘加密、读时解密），
 * 局域网里任何能连上实例的人都能看到；客户端若不遮罩，等于把密钥摊在屏幕上。
 * 只有点开某一项才显示，离开页面状态即丢弃，再次进入又回到遮罩。
 */
@Composable
internal fun EnvironmentsSection(
    vars: List<EnvVarDto>,
    catalog: List<EnvCatalogDto>,
    loading: Boolean,
    error: String?,
    onSave: (Map<String, String>) -> Unit,
    onDelete: (String) -> Unit,
    onReset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开显示明文的那一项。放在页面本地而非 ViewModel：退出页面就该忘掉。
    var revealed by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EnvVarDto?>(null) }

    val specByKey = remember(catalog) { catalog.associateBy { it.key } }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        SectionHeader("已配置", trailing = "${vars.size} 项")
        Card {
            if (vars.isEmpty()) {
                Text(
                    text = if (loading) "正在加载" else "没有自定义环境变量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
            } else {
                vars.forEachIndexed { index, v ->
                    val spec = specByKey[v.key]
                    EnvRow(
                        envVar = v,
                        editable = spec?.editable ?: true,
                        revealed = revealed == v.key,
                        onToggleReveal = { revealed = if (revealed == v.key) null else v.key },
                        onEdit = { editing = v },
                        onReset = { onReset(v.key) },
                        // 内置变量只能重置：DELETE 会返回 400「Known variable must be reset」。
                        onDelete = { onDelete(v.key) }.takeIf { spec == null },
                        resetLabel = if (spec != null) "恢复默认" else null,
                    )
                    if (index != vars.lastIndex) Divider()
                }
            }
        }

        SectionHeader("说明")
        Text(
            text = "这里的值会注入服务端进程的环境变量。内置变量不能删除，只能恢复默认；" +
                "标为只读的项由服务端控制，客户端不提供修改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
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

    editing?.let { target ->
        EnvEditDialog(
            envVar = target,
            onDismiss = { editing = null },
            onSave = { value ->
                onSave(mapOf(target.key to value))
                editing = null
            },
        )
    }
}

@Composable
private fun EnvRow(
    envVar: EnvVarDto,
    editable: Boolean,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onDelete: (() -> Unit)?,
    resetLabel: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = editable, onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = envVar.key,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (revealed) envVar.value else maskValue(envVar.value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onToggleReveal),
            )
        }
        if (resetLabel != null) {
            Spacer(Modifier.width(8.dp))
            SmallAction(text = resetLabel, onClick = onReset)
        }
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            SmallAction(text = "删除", onClick = onDelete, danger = true)
        }
    }
}

@Composable
private fun SmallAction(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun EnvEditDialog(
    envVar: EnvVarDto,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // 编辑时也先遮罩：用户点了「编辑」才填值，此时明文是他自己的选择。
    var text by remember(envVar.key) { mutableStateOf(envVar.value) }
    var visible by remember(envVar.key) { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(envVar.key, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions.Default,
                    trailingIcon = {
                        Text(
                            text = if (visible) "隐藏" else "显示",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clickable { visible = !visible }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Text(
                text = "保存",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clickable { onSave(text) }
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
 * 遮罩值。
 *
 * 长度固定 8 个圆点而不是跟随原值：圆点数量会暴露长度，
 * 而长度本身对猜密钥是有用信息。
 */
internal fun maskValue(value: String): String =
    if (value.isEmpty()) "（空）" else "••••••••"

/** 只读判定：`editable=false` 的项不允许提交。 */
internal fun isEnvEditable(spec: EnvCatalogDto?): Boolean = spec?.editable ?: true