package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.ui.theme.FontScale
import io.qwenpaw.node.ui.theme.ThemeMode

/** 设置页：多实例管理、主题、字号、关于。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    instances: List<InstanceConfig>,
    activeId: String?,
    themeMode: ThemeMode,
    fontScale: FontScale,
    onBack: () -> Unit,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (host: String, username: String, password: String, agentId: String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onFontScale: (FontScale) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agentId by remember { mutableStateOf("default") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("服务器实例", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            instances.forEach { instance ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(instance.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = buildString {
                                append(instance.baseUrl)
                                append("  ·  agent ")
                                append(instance.agentId)
                                instance.serverVersion?.let { append("  ·  v").append(it) }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (instance.id == activeId) {
                        Text("当前", color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = { onSwitch(instance.id) }) { Text("切换") }
                    }
                    TextButton(onClick = { onRemove(instance.id) }) { Text("移除") }
                }
                HorizontalDivider()
            }

            Spacer(Modifier.height(8.dp))
            if (adding) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器地址（如 192.168.1.10:8088）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名（开启鉴权时填写）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = agentId,
                    onValueChange = { agentId = it },
                    label = { Text("智能体 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onAdd(host, username, password, agentId)
                        adding = false
                    }) { Text("连接") }
                    TextButton(onClick = { adding = false }) { Text("取消") }
                }
            } else {
                Button(onClick = { adding = true }) { Text("添加实例") }
            }

            Spacer(Modifier.height(24.dp))
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "跟随系统"
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                },
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontScale.entries.forEach { scale ->
                    FilterChip(
                        selected = fontScale == scale,
                        onClick = { onFontScale(scale) },
                        label = {
                            Text(
                                when (scale) {
                                    FontScale.SMALL -> "小字"
                                    FontScale.NORMAL -> "标准"
                                    FontScale.LARGE -> "大字"
                                },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "QwenPaw Node · 基于 QwenPaw REST + SSE 的第三方 Android 客户端\n" +
                    "离线时仅保留草稿与缓存历史，不自动补发消息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
