package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.font.FontState
import io.qwenpaw.node.ui.theme.FontScale
import io.qwenpaw.node.ui.theme.ThemeMode
import io.qwenpaw.node.voice.ModelState

/** 设置页：实例、外观、通知与行为、关于。 */
@Composable
fun SettingsScreen(
    instances: List<InstanceConfig>,
    activeId: String?,
    themeMode: ThemeMode,
    fontScale: FontScale,
    useMiSans: Boolean,
    compactList: Boolean,
    keepScreenOn: Boolean,
    appVersion: String,
    onBack: () -> Unit,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (InstanceConfig) -> Unit,
    onPickAgent: (InstanceConfig) -> Unit,
    onAdd: (host: String, username: String, password: String, agentId: String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onFontScale: (FontScale) -> Unit,
    onUseMiSans: (Boolean) -> Unit,
    onCompactList: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    voiceEnabled: Boolean = false,
    onVoiceEnabled: (Boolean) -> Unit = {},
    modelState: ModelState = ModelState.NotInstalled,
    onDownloadModel: () -> Unit = {},
    onRemoveModel: () -> Unit = {},
    fontState: FontState = FontState.NotInstalled,
    onDownloadFont: () -> Unit = {},
    onRemoveFont: () -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<InstanceConfig?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SettingsTopBar(onBack = onBack, subtitle = "QwenPaw Node 版本 $appVersion")
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                // 服务器实例
                SectionHeader("服务器实例", "${instances.size} 个")
                Card {
                    instances.forEachIndexed { index, instance ->
                        InstanceRow(
                            instance = instance,
                            active = instance.id == activeId,
                            onSwitch = { onSwitch(instance.id) },
                            onRemove = { onRemove(instance.id) },
                            onEdit = { editing = instance },
                            onPickAgent = { onPickAgent(instance) },
                        )
                        if (index != instances.lastIndex) Divider()
                    }
                    if (instances.isNotEmpty()) Divider()
                    ActionRow(text = "添加实例", onClick = { adding = true })
                }

                // 外观
                SectionHeader("外观")
                Card {
                    SettingLabel("主题", "选择跟随系统时，随系统设置切换")
                    ChipRow {
                        ThemeMode.entries.forEach { mode ->
                            Chip(
                                text = when (mode) {
                                    ThemeMode.SYSTEM -> "跟随系统"
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                },
                                selected = themeMode == mode,
                                onClick = { onThemeMode(mode) },
                            )
                        }
                    }
                    Divider()
                    SettingLabel("字号", "与系统字体缩放叠加生效")
                    ChipRow {
                        FontScale.entries.forEach { scale ->
                            Chip(
                                text = when (scale) {
                                    FontScale.SMALL -> "小"
                                    FontScale.NORMAL -> "标准"
                                    FontScale.LARGE -> "大"
                                },
                                selected = fontScale == scale,
                                onClick = { onFontScale(scale) },
                            )
                        }
                    }
                }

                // 通知与行为
                SectionHeader("通知与行为")
                Card {
                    SwitchRow(
                        title = "使用 MiSans 字体",
                        subtitle = if (fontState is FontState.Ready) {
                            "已下载，替换系统默认字体"
                        } else {
                            "需先下载字体（约 16MB），否则跟随系统"
                        },
                        checked = useMiSans,
                        enabled = fontState is FontState.Ready,
                        onCheckedChange = onUseMiSans,
                    )
                    Divider()
                    FontRow(
                        state = fontState,
                        onDownload = onDownloadFont,
                        onRemove = {
                            onRemoveFont()
                            onUseMiSans(false)
                        },
                    )
                    Divider()
                    SwitchRow(
                        title = "会话列表紧凑排版",
                        subtitle = "只显示名称和时间，一屏能看到更多会话",
                        checked = compactList,
                        onCheckedChange = onCompactList,
                    )
                    Divider()
                    SwitchRow(
                        title = "生成中保持屏幕常亮",
                        subtitle = "长时间生成时避免息屏打断查看",
                        checked = keepScreenOn,
                        onCheckedChange = onKeepScreenOn,
                    )
                }

                // 语音识别
                SectionHeader("语音输入")
                Card {
                    SwitchRow(
                        title = "显示语音按钮",
                        subtitle = "在输入框右下角显示麦克风，需要先下载离线模型",
                        checked = voiceEnabled,
                        onCheckedChange = onVoiceEnabled,
                    )
                    Divider()
                    ModelRow(
                        state = modelState,
                        onDownload = onDownloadModel,
                        onRemove = onRemoveModel,
                    )
                }

                // 关于
                SectionHeader("关于")
                Card {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "QwenPaw Node",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = appVersion,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "基于 QwenPaw 接口构建的第三方 Android 客户端。" +
                                "离线时只保留草稿和缓存历史，不会自动重新发送。\n" +
                                "可选字体 MiSans 版权归小米科技有限责任公司所有，" +
                                "遵循 MiSans 字体许可协议，由用户在设置中按需下载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        InstanceEditorDialog(
            title = "添加实例",
            initialHost = "",
            initialAgent = "default",
            onDismiss = { adding = false },
            onConfirm = { host, user, pass, agent ->
                onAdd(host, user, pass, agent)
                adding = false
            },
        )
    }

    editing?.let { instance ->
        InstanceEditorDialog(
            title = "编辑实例",
            initialHost = instance.baseUrl,
            initialAgent = instance.agentId,
            onDismiss = { editing = null },
            onConfirm = { host, user, pass, agent ->
                onEdit(
                    instance.copy(
                        baseUrl = host.ifBlank { instance.baseUrl },
                        agentId = agent.ifBlank { instance.agentId },
                    ),
                )
                editing = null
            },
        )
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
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
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    state: ModelState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "离线识别模型", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = when (state) {
                        ModelState.NotInstalled -> "下载后不联网也能语音输入，音频不出手机"
                        ModelState.Ready -> "已下载，断网也能用"
                        is ModelState.Downloading -> "正在下载 ${(state.progress * 100).toInt()}%"
                        ModelState.Installing -> "正在解压"
                        is ModelState.Failed -> state.message
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state is ModelState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            when (state) {
                ModelState.NotInstalled -> MiniButton("下载", onDownload)
                is ModelState.Failed -> MiniButton("重试", onDownload)
                ModelState.Ready -> MiniButton("删除", onRemove, danger = true)
                else -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (state is ModelState.Downloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "模型来自 sherpa-onnx 项目，约 150MB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            ),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun InstanceRow(
    instance: InstanceConfig,
    active: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onPickAgent: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!active) onSwitch() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = instance.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = instance.baseUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable(onClick = onPickAgent)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "智能体 ${instance.agentId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                instance.serverVersion?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "版本 $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (active) {
            MiniButton("编辑", onClick = onEdit)
        } else {
            MiniButton("切换", onClick = onSwitch)
            Spacer(Modifier.width(6.dp))
            MiniButton("移除", onClick = onRemove, danger = true)
        }
    }
}

@Composable
private fun MiniButton(text: String, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun SettingLabel(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** MiSans 字体：按需下载，不随 APK 分发。 */
@Composable
private fun FontRow(
    state: FontState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "MiSans 字体包", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = when (state) {
                        FontState.NotInstalled -> "下载后可用，两个字重约 16MB"
                        FontState.Ready -> "已下载，占用约 16MB"
                        is FontState.Downloading -> "正在下载 ${(state.progress * 100).toInt()}%"
                        is FontState.Failed -> state.message
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state is FontState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            when (state) {
                FontState.NotInstalled -> MiniButton("下载", onDownload)
                is FontState.Failed -> MiniButton("重试", onDownload)
                FontState.Ready -> MiniButton("删除", onRemove, danger = true)
                else -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (state is FontState.Downloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "字体版权归小米科技有限责任公司所有，遵循 MiSans 字体许可协议；" +
                "应用内按需从 GitHub 项目 ${io.qwenpaw.node.font.FontManager.SOURCE_REPO} 下载，不随安装包分发。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun InstanceEditorDialog(
    title: String,
    initialHost: String,
    initialAgent: String,
    onDismiss: () -> Unit,
    onConfirm: (host: String, username: String, password: String, agentId: String) -> Unit,
) {
    var host by remember { mutableStateOf(initialHost) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agentId by remember { mutableStateOf(initialAgent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("192.168.1.10:8088") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
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
                Text(
                    text = "也可以添加后在卡片上点智能体切换",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(host, username, password, agentId) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
