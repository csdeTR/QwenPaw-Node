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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.NetworkMonitor
import io.qwenpaw.node.data.local.AddressEntry
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.font.FontState
import io.qwenpaw.node.ui.VoiceTrigger
import io.qwenpaw.node.ui.theme.ThemeMode
import io.qwenpaw.node.voice.ModelState

/** 设置页：实例、外观、消息显示、通知与行为、语音输入、运行日志、关于。 */
@Composable
fun SettingsScreen(
    instances: List<InstanceConfig>,
    activeId: String?,
    themeMode: ThemeMode,
    uiScale: Float,
    chatScale: Float,
    useMiSans: Boolean,
    compactList: Boolean,
    keepScreenOn: Boolean,
    appVersion: String,
    onBack: () -> Unit,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (InstanceConfig) -> Unit,
    onPickAgent: (InstanceConfig) -> Unit,
    /**
     * 添加实例。第二个参数是连接结果回调，成功后表单自己收起来，
     * 避免用户盯着一个已经连上的表单不知道下一步做什么。
     */
    onAdd: (io.qwenpaw.node.data.local.InstanceDraft, onResult: (Boolean) -> Unit) -> Unit,
    /** 添加实例时的连接中状态与错误，由外部 ViewModel 提供。 */
    addConnecting: Boolean = false,
    addError: String? = null,
    onLoadAgents: suspend (hosts: List<String>, username: String, password: String) -> List<io.qwenpaw.node.data.model.AgentDto> = { _, _, _ -> emptyList() },
    onVerifyHosts: suspend (hosts: List<String>, onAttempt: (VerifyAttempt) -> Unit) -> io.qwenpaw.node.data.VerifyResult,
    /** 引导式添加时查服务端是否要求登录。 */
    onCheckAuth: suspend (address: String) -> Boolean = { false },
    onLoadAgentsFor: suspend (InstanceConfig) -> List<io.qwenpaw.node.data.model.AgentDto> = { emptyList() },
    /** 手动指定实例优先使用的地址；传 null 表示恢复「按列表顺序」。写配置，长期有效。 */
    onChooseAddress: (instanceId: String, url: String?) -> Unit = { _, _ -> },
    /** 只在本次运行生效的地址选择；不写配置，重启后回到自动。 */
    onChooseSessionAddress: (instanceId: String, url: String?) -> Unit = { _, _ -> },
    /** 查询本次运行已指定的地址。用于在地址清单里标出当前值。 */
    sessionAddressOf: (instanceId: String) -> String? = { null },
    /** 只验证单条地址，结果由调用方以 Toast 呈现。 */
    onVerifySingleAddress: (instanceId: String, url: String) -> Unit = { _, _ -> },
    /** 当前网络类型与 WiFi 名称，用于地址编辑区提示。 */
    currentNetKind: String = NetworkMonitor.Snapshot.KIND_OTHER,
    currentSsid: String? = null,
    onThemeMode: (ThemeMode) -> Unit,
    onUiScale: (Float) -> Unit,
    onChatScale: (Float) -> Unit,
    onUseMiSans: (Boolean) -> Unit,
    onCompactList: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    voiceEnabled: Boolean = false,
    onVoiceEnabled: (Boolean) -> Unit = {},
    voiceTrigger: VoiceTrigger = VoiceTrigger.TAP,
    onVoiceTrigger: (VoiceTrigger) -> Unit = {},
    voiceAutoStop: Boolean = true,
    onVoiceAutoStop: (Boolean) -> Unit = {},
    assistantCollapse: io.qwenpaw.node.ui.chat.AssistantCollapse =
        io.qwenpaw.node.ui.chat.AssistantCollapse.RESULT_COLLAPSED,
    onAssistantCollapse: (io.qwenpaw.node.ui.chat.AssistantCollapse) -> Unit = {},
    modelState: ModelState = ModelState.NotInstalled,
    onDownloadModel: () -> Unit = {},
    onRemoveModel: () -> Unit = {},
    fontState: FontState = FontState.NotInstalled,
    onDownloadFont: () -> Unit = {},
    onRemoveFont: () -> Unit = {},
    logEnabled: Boolean = false,
    onLogEnabled: (Boolean) -> Unit = {},
    logKinds: Set<io.qwenpaw.node.log.LogKind> = io.qwenpaw.node.log.LogKind.entries.toSet(),
    onToggleLogKind: (io.qwenpaw.node.log.LogKind) -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onExportLogs: () -> Unit = {},
    showMessageStats: Boolean = false,
    onShowMessageStats: (Boolean) -> Unit = {},
    /** 用户昵称，显示在自己发出的消息头部。 */
    userName: String = io.qwenpaw.node.ui.DEFAULT_USER_NAME,
    onUserName: (String) -> Unit = {},
    /** 头像存储。为 null 时头像分区只显示说明与首字回落。 */
    avatarStore: io.qwenpaw.node.avatar.AvatarStore? = null,
    /** 头像文件版本：变化时重新读图，换完头像立刻生效。 */
    avatarVersion: Long = 0L,
    onPickAvatar: (String) -> Unit = {},
    onRemoveAvatar: (String) -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    // 添加实例的两种方式：null 表示在方式选择页，否则是具体形态。
    var addMode by remember { mutableStateOf<AddMode?>(null) }
    var editing by remember { mutableStateOf<InstanceConfig?>(null) }
    val context = LocalContext.current

    // 添加/编辑是设置页内的子页面：系统返回键先退回方式选择页，再退出添加，
    // 而不是直接跳回聊天页，否则用户会以为设置被关掉了。
    androidx.activity.compose.BackHandler(enabled = adding || editing != null) {
        when {
            addMode != null && adding -> addMode = null
            adding -> adding = false
            else -> editing = null
        }
    }

    // 添加实例先选方式：填表适合熟手，引导适合不清楚各项含义的人。
    if (adding) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                SettingsTopBar(
                    onBack = {
                        if (addMode != null) addMode = null else adding = false
                    },
                    subtitle = if (addMode == null) "添加实例" else "添加实例 · 引导式",
                )
                when (addMode) {
                    null -> AddModeChooser(
                        onPickFill = { addMode = AddMode.FORM },
                        onPickWizard = { addMode = AddMode.WIZARD },
                    )

                    AddMode.FORM -> InstanceForm(
                        showHeader = false,
                        connecting = addConnecting,
                        error = addError,
                        onConnect = { draft ->
                            onAdd(draft) { ok ->
                                if (ok) {
                                    adding = false
                                    addMode = null
                                }
                            }
                        },
                        onLoadAgents = onLoadAgents,
                        onVerifyHosts = onVerifyHosts,
                        currentNetKind = currentNetKind,
                        currentSsid = currentSsid,
                        submitLabel = "添加",
                        onCancel = { addMode = null },
                    )

                    AddMode.WIZARD -> InstanceWizard(
                        connecting = addConnecting,
                        error = addError,
                        onFinish = { draft ->
                            onAdd(draft) { ok ->
                                if (ok) {
                                    adding = false
                                    addMode = null
                                }
                            }
                        },
                        onCheckAuth = onCheckAuth,
                        onLoadAgents = onLoadAgents,
                        onVerifyHosts = onVerifyHosts,
                    )
                }
            }
        }
        return
    }

    // 编辑实例同样走这套交互，地址预填并自动读取智能体列表。
    editing?.let { instance ->
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                SettingsTopBar(onBack = { editing = null }, subtitle = "编辑实例")
                InstanceForm(
                    showHeader = false,
                    connecting = false,
                    error = null,
                    initialAddresses = instance.addresses,
                    initialName = instance.name,
                    initialAgentId = instance.agentId,
                    onConnect = { draft ->
                        // 这里只保存本地配置，不发网络请求：地址与名称都是用户手改的，
                        // 立即连通性验证交给设置主页的「验证」。
                        onEdit(
                            instance.copy(
                                name = draft.resolvedName(),
                                addresses = draft.addresses,
                                agentId = draft.agentId,
                            ),
                        )
                        editing = null
                    },
                    onLoadAgents = { _, _, _ -> emptyList() },
                    onVerifyHosts = onVerifyHosts,
                    loadAgentsWithToken = { onLoadAgentsFor(instance) },
                    currentNetKind = currentNetKind,
                    currentSsid = currentSsid,
                    submitLabel = "保存",
                    onCancel = { editing = null },
                )
            }
        }
        return
    }

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
                            onChooseAddress = { url -> onChooseAddress(instance.id, url) },
                            onChooseSessionAddress = { url -> onChooseSessionAddress(instance.id, url) },
                            sessionPick = sessionAddressOf(instance.id),
                            onVerifySingle = { url -> onVerifySingleAddress(instance.id, url) },
                        )
                        if (index != instances.lastIndex) Divider()
                    }
                    if (instances.isNotEmpty()) Divider()
                    ActionRow(text = "添加实例", onClick = { adding = true })
                }

                // 外观
                SectionHeader("外观")
                Card {
                    // 主题不另起一行标题：三张预览卡下方各自写着「跟随系统」「浅色」「深色」，
                    // 选中的那张有描边，再加一行说明只是把同一件事说第二遍。
                    ThemeSelector(
                        current = themeMode,
                        onPick = onThemeMode,
                    )
                    Divider()
                    ScaleSlider(
                        title = "界面字号",
                        subtitle = "当前 ${percent(uiScale)}",
                        value = uiScale,
                        min = io.qwenpaw.node.ui.theme.FontScaling.UI_MIN,
                        max = io.qwenpaw.node.ui.theme.FontScaling.UI_MAX,
                        onValueChange = onUiScale,
                    )
                    Divider()
                    ScaleSlider(
                        title = "聊天字号",
                        subtitle = "只影响对话正文；当前 ${percent(chatScale)}",
                        value = chatScale,
                        min = io.qwenpaw.node.ui.theme.FontScaling.CHAT_MIN,
                        max = io.qwenpaw.node.ui.theme.FontScaling.CHAT_MAX,
                        onValueChange = onChatScale,
                    )
                }

                // 头像
                SectionHeader("头像")
                Card {
                    AvatarRow(
                        title = "我的头像",
                        subtitle = "显示在消息右侧",
                        kind = io.qwenpaw.node.avatar.AvatarStore.KIND_USER,
                        store = avatarStore,
                        version = avatarVersion,
                        onPick = onPickAvatar,
                        onRemove = onRemoveAvatar,
                    )
                    Divider()
                    AvatarRow(
                        title = "智能体头像",
                        subtitle = "显示在消息左侧",
                        kind = io.qwenpaw.node.avatar.AvatarStore.KIND_AGENT,
                        store = avatarStore,
                        version = avatarVersion,
                        onPick = onPickAvatar,
                        onRemove = onRemoveAvatar,
                    )
                    Divider()
                    NicknameRow(value = userName, onCommit = onUserName)
                    Divider()
                    SettingLabel(
                        "图片处理",
                        "静态图片裁剪为正方形并压缩到 500×500；GIF 动图原样保存并循环播放。" +
                            "头像在对话里显示为 32dp 的圆形，超过这个尺寸的部分会被裁掉",
                    )
                }

                // 消息显示
                SectionHeader("消息显示")
                Card {
                    SettingLabel(
                        "智能体消息折叠",
                        "默认值与官方网页一致",
                    )
                    ChipRow {
                        io.qwenpaw.node.ui.chat.AssistantCollapse.entries.forEach { mode ->
                            Chip(
                                text = when (mode) {
                                    io.qwenpaw.node.ui.chat.AssistantCollapse.EXPANDED -> "全部展开"
                                    io.qwenpaw.node.ui.chat.AssistantCollapse.PROCESS_COLLAPSED -> "折叠过程"
                                    io.qwenpaw.node.ui.chat.AssistantCollapse.RESULT_COLLAPSED -> "折叠结果"
                                },
                                selected = assistantCollapse == mode,
                                onClick = { onAssistantCollapse(mode) },
                            )
                        }
                    }
                    Divider()
                    SwitchRow(
                        title = "在回复末尾显示用量",
                        subtitle = "显示本轮回复的输入输出 token、所用模型与完成时间",
                        checked = showMessageStats,
                        onCheckedChange = onShowMessageStats,
                    )
                }

                // 通知与行为
                SectionHeader("通知与行为")
                Card {
                    SwitchRow(
                        title = "使用 MiSans 字体",
                        subtitle = if (fontState is FontState.Ready) {
                            "替换系统默认字体"
                        } else {
                            "需先在下方下载字体"
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
                        subtitle = "仅显示名称与时间，单屏可容纳更多会话",
                        checked = compactList,
                        onCheckedChange = onCompactList,
                    )
                    Divider()
                    SwitchRow(
                        title = "生成中保持屏幕常亮",
                        subtitle = "仅在生成回复期间生效",
                        checked = keepScreenOn,
                        onCheckedChange = onKeepScreenOn,
                    )
                }

                // 语音识别
                SectionHeader("语音输入")
                Card {
                    SwitchRow(
                        title = "显示语音按钮",
                        subtitle = "首次使用需下载识别模型",
                        checked = voiceEnabled,
                        onCheckedChange = onVoiceEnabled,
                    )
                    Divider()
                    Text(
                        text = "触发方式",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
                    )
                    ChipRow {
                        VoiceTrigger.entries.forEach { trigger ->
                            Chip(
                                text = when (trigger) {
                                    VoiceTrigger.TAP -> "点击开始"
                                    VoiceTrigger.HOLD -> "按住说话"
                                },
                                selected = voiceTrigger == trigger,
                                onClick = { onVoiceTrigger(trigger) },
                            )
                        }
                    }
                    // 自动停止只在「点一下」模式下有意义：按住说话本来就是松手即停。
                    if (voiceTrigger == VoiceTrigger.TAP) {
                        Divider()
                        SwitchRow(
                            title = "说完自动停止",
                            subtitle = "关闭时需再次点击麦克风结束",
                            checked = voiceAutoStop,
                            onCheckedChange = onVoiceAutoStop,
                        )
                    }
                    Divider()
                    ModelRow(
                        state = modelState,
                        onDownload = onDownloadModel,
                        onRemove = onRemoveModel,
                    )
                }

                // 运行日志。这里记录的是本机客户端的事件，
                // 与 QwenPaw 服务端的日志是两回事，后者要在 Web 控制台查看。
                SectionHeader("运行日志")
                Card {
                    SwitchRow(
                        title = "记录运行日志",
                        subtitle = "只记录本次运行期间的事件",
                        checked = logEnabled,
                        onCheckedChange = onLogEnabled,
                    )
                    Divider()
                    SettingLabel("记录类型", "排查某一类问题时，可关闭其余类别以减少干扰")
                    ChipRow {
                        io.qwenpaw.node.log.LogKind.entries.forEach { kind ->
                            Chip(
                                text = kind.label,
                                selected = kind in logKinds,
                                onClick = { onToggleLogKind(kind) },
                            )
                        }
                    }
                    Divider()
                    LinkRow(
                        title = "查看运行日志",
                        subtitle = "按时间查看本次运行记录的事件",
                        onClick = onOpenLogs,
                    )
                    Divider()
                    LinkRow(
                        title = "导出运行日志",
                        subtitle = "导出为文本文件，便于反馈问题时一并提供",
                        action = "导出",
                        onClick = onExportLogs,
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
                                "离线时只保留草稿和缓存历史，不会自动重新发送。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Divider()
                    LinkRow(
                        title = "项目仓库",
                        subtitle = io.qwenpaw.node.BuildConfig.REPO_URL,
                        action = "前往",
                        onClick = { openUrl(context, io.qwenpaw.node.BuildConfig.REPO_URL) },
                    )
                    Divider()
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "第三方资源",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(6.dp))
                        CreditLine(
                            name = "sherpa-onnx",
                            detail = "离线语音识别引擎（Apache-2.0），语音模型按需下载",
                        )
                        CreditLine(
                            name = "MiSans",
                            detail = "可选字体，版权归小米科技有限责任公司，" +
                                "遵循 MiSans 字体许可协议，按需从 GitHub 项目 " +
                                "${io.qwenpaw.node.font.FontManager.SOURCE_REPO} 下载",
                        )
                        CreditLine(
                            name = "Coil",
                            detail = "头像图片加载与 GIF 逐帧解码（Apache-2.0）",
                        )
                    }
                }
            }
        }
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
                Text(text = "语音识别模型", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = when (state) {
                        // 模型是使用前提，不是可选增强。原文案容易读成「下载后还能离线用」，
                        // 实际是不下载就完全不能用。
                        ModelState.NotInstalled -> "未下载，语音输入现不可用"
                        ModelState.Ready -> "已安装，语音输入可用"
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
            text = "来自 sherpa-onnx；约 150MB，识别全程在本机完成",
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
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
                color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
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

/**
 * 地址的紧凑写法：去掉 `http://` 前缀与尾部斜杠。
 *
 * 卡片一行只放得下这么多字，而 `http://` 对「连在哪台机器」毫无信息量。
 * https 保留一个 `s` 标记，因为两者在排查连接问题时是不同的东西。
 */
internal fun compactUrl(url: String): String = url
    .removePrefix("http://")
    .removePrefix("https://")
    .let { if (url.startsWith("https://")) "https://$it" else it }
    .trimEnd('/')

@Composable
private fun InstanceRow(
    instance: InstanceConfig,
    active: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onPickAgent: () -> Unit,
    /** 保存长期偏好（写配置）。 */
    onChooseAddress: (String?) -> Unit,
    /** 只在本次运行生效的地址选择（不写配置）。 */
    onChooseSessionAddress: (String?) -> Unit,
    /** 本次运行已指定的地址；null 表示自动。 */
    sessionPick: String?,
    /** 只验证单条地址，结果通过 Toast 呈现。 */
    onVerifySingle: (String) -> Unit,
) {
    // 显示顺序的取法必须与请求时的解析顺序一致，否则卡片说「走这条」、
    // 实际却在走另一条。临时选择优先级最高，其次是长期偏好，最后是列表第一个。
    val chosen = instance.preferredBaseUrl?.takeIf { url -> instance.addresses.any { it.url == url } }
    val shown = sessionPick?.takeIf { url -> instance.addresses.any { it.url == url } }
        ?: chosen
        ?: instance.addresses.firstOrNull()?.url.orEmpty()
    var pickingAddress by remember(instance.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
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
                    text = buildString {
                        // 带「地址：」前缀是必须的：不带前缀时这一行紧接在实例名下方，
                        // 字号又小一号，读起来就是「实例名的别名」，而不是「这台服务器连在哪」。
                        append("地址：")
                        val alias = instance.addresses
                            .firstOrNull { it.url == shown }?.alias
                        if (!alias.isNullOrBlank()) {
                            append(alias).append(" · ")
                        }
                        append(compactUrl(shown))
                        if (instance.backupCount > 0) {
                            append("（+").append(instance.backupCount).append(" 个备用）")
                        }
                        // 「临时」与「已保存」要分清楚：前者重启即失效，后者长期有效。
                        when {
                            sessionPick != null && sessionPick == shown -> append(" · 本次指定")
                            chosen != null && chosen == shown -> append(" · 已指定")
                        }
                    },
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
                    // 有多个地址时才需要手动选择。只有一个地址时点开也没得选。
                    if (instance.addresses.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(999.dp),
                                )
                                .clickable { pickingAddress = !pickingAddress }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = if (pickingAddress) "收起地址" else "选择地址",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    instance.serverVersion?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "版本 $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
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
        // 展开后的地址清单。
        //
        // 这里有两层语义，必须分开呈现，否则用户会以为「点一下就永久改了」：
        // - 「本次使用」只写内存，重启后回到自动；
        // - 长期偏好由编辑实例表单里的「使用时机」决定。
        if (pickingAddress) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                Text(
                    text = buildString {
                        append("本次使用：")
                        val current = sessionPick
                        if (current.isNullOrBlank()) {
                            append("自动选择")
                        } else {
                            append(instance.addresses.firstOrNull { it.url == current }?.label ?: current)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(6.dp))
                AddressChoice(
                    label = "自动选择（按列表顺序）",
                    selected = sessionPick.isNullOrBlank() && chosen == null,
                    onClick = {
                        // 「自动」要同时清掉临时选择与长期偏好，否则用户点了自动却仍被旧偏好挡住。
                        onChooseSessionAddress(null)
                        onChooseAddress(null)
                        pickingAddress = false
                    },
                )
                instance.addresses.forEach { entry ->
                    Spacer(Modifier.height(6.dp))
                    AddressChoice(
                        // 没有别名时 label 就是 url 本身，再拼一次会显示成重复的两串地址。
                        label = if (entry.label == entry.url) {
                            compactUrl(entry.url)
                        } else {
                            "${entry.label}  ${compactUrl(entry.url)}"
                        },
                        selected = entry.url == sessionPick,
                        onClick = {
                            onChooseSessionAddress(entry.url)
                            pickingAddress = false
                        },
                        onVerify = { onVerifySingle(entry.url) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "「本次使用」只影响这次打开；长期偏好请在编辑实例里设置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                )
            }
        }
    }
}

/** 地址选择行。选中项填浅底色并带对勾。 */
@Composable
private fun AddressChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    /** 非空时在行尾显示「验证」按钮：只测这一条地址。 */
    onVerify: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选择",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        // 「验证」只测这一条地址，不改变选择。单独给一个入口是因为
        // 「点整行 = 切换」与「点按钮 = 验证」是两件不同的事，混在一起会误触。
        if (onVerify != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable(onClick = onVerify)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "验证",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
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

/** 外观选项卡片行：每个选项是一张可点的卡，比小圆片更好按也更好认。 */
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

/**
 * 主题选择器。
 *
 * 用三张并排的预览卡代替文字小圆片：每张卡直接画出该主题的底色与前景色块，
 * 选中的那张加一圈主色描边，一眼就能看出选的是哪个。
 */
@Composable
private fun ThemeSelector(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val selected = mode == current
            // 预览用固定色，不跟随当前主题——否则「浅色」卡在深色模式下也会变黑。
            val previewBg = when (mode) {
                ThemeMode.SYSTEM -> MaterialTheme.colorScheme.surfaceContainerHigh
                ThemeMode.LIGHT -> androidx.compose.ui.graphics.Color(0xFFFFFFFF)
                ThemeMode.DARK -> androidx.compose.ui.graphics.Color(0xFF1F1F1F)
            }
            val previewFg = when (mode) {
                ThemeMode.LIGHT -> androidx.compose.ui.graphics.Color(0xFF141413)
                else -> androidx.compose.ui.graphics.Color(0xFFE8E8E8)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surface,
                    )
                    .border(
                        if (selected) 1.5.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onPick(mode) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(previewBg)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(previewFg.copy(alpha = 0.75f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(previewFg.copy(alpha = 0.4f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when (mode) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "浅色"
                        ThemeMode.DARK -> "深色"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 连续字号滑块。整数百分比显示，拖动实时生效。 */
@Composable
private fun ScaleSlider(
    title: String,
    subtitle: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "重置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onValueChange(io.qwenpaw.node.ui.theme.FontScaling.DEFAULT) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onValueChange,
            valueRange = min..max,
            // 步进 5%，避免拖出 1.037 这种没意义的精度。
            steps = (((max - min) / 0.05f).toInt() - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 可点击的链接行，用于「关于」里的仓库地址与日志入口。
 *
 * 尾部文字随动作变化：查看、导出、前往的语义各不相同，统一写「打开」
 * 会让「导出运行日志」这一行读起来像又一个查看入口。
 */
@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    action: String = "查看",
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            text = action,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** 「关于」里的第三方资源说明行。 */
@Composable
private fun CreditLine(name: String, detail: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 把缩放系数显示成百分比，1.0 显示为 100%。 */
private fun percent(value: Float): String = "${(value * 100).toInt()}%"

/** 用系统浏览器打开一个网址。 */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
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
            // 关闭态用带描边的轨道，避免和「不可用」的灰混淆。
            colors = io.qwenpaw.node.ui.theme.qwenSwitchColors(),
        )
    }
}

/** 设置页注释：与小节、卡片组件同处一文件。 */
private enum class AddMode { FORM, WIZARD }

/** 添加实例的方式选择：两种形态各自一句话说明，避免用户猜。 */
@Composable
private fun AddModeChooser(onPickFill: () -> Unit, onPickWizard: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ModeCard(
            title = "填表添加",
            subtitle = "逐项填写地址、账号与智能体，可配置多个地址",
            onClick = onPickFill,
        )
        Spacer(Modifier.height(10.dp))
        ModeCard(
            title = "引导式添加",
            subtitle = "一步一条，先验证服务器再选智能体",
            onClick = onPickWizard,
        )
    }
}

@Composable
private fun ModeCard(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 头像设置行：预览 + 「选择图片」「移除」。 */
@Composable
private fun AvatarRow(
    title: String,
    subtitle: String,
    kind: String,
    store: io.qwenpaw.node.avatar.AvatarStore?,
    version: Long,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val hasAvatar = store?.kindFor(kind)?.let { it != io.qwenpaw.node.avatar.AvatarKind.NONE } == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.qwenpaw.node.ui.chat.ChatAvatar(
            store = store,
            kind = kind,
            name = if (kind == io.qwenpaw.node.avatar.AvatarStore.KIND_USER) "我" else "智能体",
            size = 40.dp,
            version = version,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        MiniButton(if (hasAvatar) "更换" else "选择", onClick = { onPick(kind) })
        if (hasAvatar) {
            Spacer(Modifier.width(6.dp))
            MiniButton("移除", onClick = { onRemove(kind) }, danger = true)
        }
    }
}

/**
 * 昵称输入行。
 *
 * 不用「点开再弹框」：昵称很短，就地编辑更直接。
 *
 * **逐键提交**而不是等失焦：实测在设置页里按返回键只是收起键盘，焦点仍留在
 * 输入框上，`onFocusChanged` 不会触发；而页面本身不会销毁（设置页是页面栈里的一层），
 * `DisposableEffect` 也等不到。只挂失焦回调会让用户敲完直接返回时静默丢内容。
 */
@Composable
private fun NicknameRow(value: String, onCommit: (String) -> Unit) {
    // 只认 `remember`，不把 value 当 key：提交后调用方会把 value 换成去空白、
    // 空串回落「我」的结果，若以此重建输入框，正在输入的字符会被抹掉、光标也会跳。
    var text by remember { mutableStateOf(value) }

    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(text = "我的昵称", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "显示在自己发出的消息头部",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onCommit(it)
            },
            singleLine = true,
            placeholder = { Text(io.qwenpaw.node.ui.DEFAULT_USER_NAME) },
            modifier = Modifier.fillMaxWidth(),
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
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
    }
}


