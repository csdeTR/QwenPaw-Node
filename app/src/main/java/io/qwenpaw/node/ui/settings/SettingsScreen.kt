package io.qwenpaw.node.ui.settings

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.NetworkMonitor
import io.qwenpaw.node.data.local.AddressEntry
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.font.FontState
import io.qwenpaw.node.ui.VoiceTrigger
import io.qwenpaw.node.ui.common.pageContentInsets
import io.qwenpaw.node.ui.common.pageImePadding
import io.qwenpaw.node.ui.i18n.AppLanguage
import io.qwenpaw.node.ui.i18n.Texts
import io.qwenpaw.node.ui.theme.ThemeMode
import io.qwenpaw.node.ui.theme.QwenIcons
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface

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
    /** 每日首次启动是否静默检查新版本。 */
    autoUpdateCheck: Boolean = true,
    onAutoUpdateCheck: (Boolean) -> Unit = {},
    /** 手动检查更新；结论（已是最新 / 失败原因）由上层以 Toast 呈现。 */
    onCheckUpdate: () -> Unit = {},
    /** 进入设置时直接打开「关于」：更新对话框里的「关闭检查更新」走这条路。 */
    openAboutOnEnter: Boolean = false,
    onConsumeOpenAboutOnEnter: () -> Unit = {},
    /** 在「关于」页弹一次关闭自动检查的二次确认。 */
    pendingDisableUpdateCheck: Boolean = false,
    onConsumePendingDisableUpdateCheck: () -> Unit = {},
    /** 打开设置页下的「工作区文件」。默认空实现，预览与测试不受影响。 */
    onOpenWorkspace: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    /** 打开设置页下的「备份」。 */
    onOpenBackup: () -> Unit = {},
    /** 打开设置页下的「检查点」。 */
    onOpenCheckpoint: () -> Unit = {},
    /** 打开设置页下的「模型与提供商」。 */
    onOpenModels: () -> Unit = {},
    /** 打开设置页下的「技能」。 */
    onOpenSkills: () -> Unit = {},
    /** 打开设置页下的「MCP」。 */
    onOpenMcp: () -> Unit = {},
    /** 打开访问控制（待审批与名单增删）。 */
    onOpenAccessControl: () -> Unit = {},
    /** 打开设置页下的「扩展市场」。 */
    onOpenMarket: () -> Unit = {},
    /** 打开用量统计。从设置进入时，返回也回设置页。 */
    onOpenUsage: () -> Unit = {},
    /** 打开运行配置。 */
    onOpenRunningConfig: () -> Unit = {},
    /** 打开安全页。 */
    onOpenSecurity: () -> Unit = {},
    /** 打开智能体管理。 */
    onOpenAgents: () -> Unit = {},
    /** 打开运行时（ACP 与 Harness）。 */
    onOpenRuntime: () -> Unit = {},
    /** 打开 App 中心。 */
    onOpenAppCenter: () -> Unit = {},
    /** 打开频道页（扫码授权）。 */
    onOpenChannels: () -> Unit = {},
    /** 打开本地模型页（运行时状态与下载进度）。 */
    onOpenLocalModels: () -> Unit = {},
    /** 打开浏览器控制链路诊断。 */
    onOpenBrowserDiagnostics: () -> Unit = {},
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
    /** 编辑实例并验证凭据；返回 null 表示已保存，否则是给界面显示的原因。 */
    onEditWithCredentials: suspend (
        InstanceConfig,
        io.qwenpaw.node.data.local.InstanceDraft,
    ) -> String? = { _, _ -> null },
    onPickAgent: (InstanceConfig) -> Unit,
    /**
     * 添加实例。第二个参数是连接结果回调，成功后表单自己收起来，
     * 避免用户盯着一个已经连上的表单不知道下一步做什么。
     */
    onAdd: (io.qwenpaw.node.data.local.InstanceDraft, onResult: (Boolean) -> Unit) -> Unit,
    /** 添加实例时的连接中状态与错误，由外部 ViewModel 提供。 */
    addConnecting: Boolean = false,
    addError: String? = null,
    onLoadAgents: suspend (
        hosts: List<String>,
        username: String,
        password: String,
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth?,
    ) -> List<io.qwenpaw.node.data.model.AgentDto> = { _, _, _, _ -> emptyList() },
    onVerifyHosts: suspend (
        hosts: List<String>,
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth?,
        onAttempt: (VerifyAttempt) -> Unit,
        /** 已保存实例的 token；新增实例传 null。 */
        token: String?,
        /** 已保存实例的 id；新增实例传 null。用于优先上次成功地址。 */
        instanceId: String?,
    ) -> io.qwenpaw.node.data.VerifyResult,
    /** 引导式添加时查服务端是否要求登录。 */
    onCheckAuth: suspend (address: String) -> Boolean = { false },
    /** 引导式添加时试登录一次。返回 null 表示通过，否则是给用户看的原因。 */
    onCheckLogin: suspend (
        address: String,
        username: String,
        password: String,
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth?,
    ) -> String? = { _, _, _, _ -> null },
    onLoadAgentsFor: suspend (InstanceConfig) -> List<io.qwenpaw.node.data.model.AgentDto> = { emptyList() },
    /** 扫码页得到的配对码；非空时表单自动填入。 */
    scannedPair: io.qwenpaw.node.data.qr.TokenPair? = null,
    onConsumeScannedPair: () -> Unit = {},
    /** 打开扫码页；为 null 时不显示扫描按钮。 */
    onOpenScanner: (() -> Unit)? = null,
    /** 扫码登录时用令牌读智能体列表。 */
    onLoadAgentsWithToken: suspend (
        hosts: List<String>,
        token: String,
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth?,
    ) -> List<io.qwenpaw.node.data.model.AgentDto> = { _, _, _ -> emptyList() },
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
    /** 是否把界面固定在竖屏。 */
    lockPortrait: Boolean = false,
    onLockPortrait: (Boolean) -> Unit = {},
    /** 应用界面语言。1.0.0 之前只有简体中文。 */
    appLanguage: AppLanguage = AppLanguage.ZH_CN,
    onAppLanguage: (AppLanguage) -> Unit = {},
    /** 是否在公网地址未设防时提醒。 */
    securityHintsEnabled: Boolean = true,
    onSecurityHintsEnabled: (Boolean) -> Unit = {},
    /** 已归一化的受信任主机；命中后地址检查不再弹提示。 */
    trustedHosts: Set<String> = emptySet(),
    /** 添加一条受信任的地址，返回是否被接受。 */
    onAddTrustedHost: (String) -> Boolean = { false },
    onRemoveTrustedHost: (String) -> Unit = {},
    /** 该主机是否已在受信任集合里。地址校验完成后据此过滤提示。 */
    isTrustedHost: (String) -> Boolean = { false },
    /** 已确认允许经配套插件下发 SSH 凭据的主机。 */
    sshTrustedHosts: Set<String> = emptySet(),
    /** 新增地址：同时写入提示豁免与 SSH 凭据授权。 */
    onAddTrustedHostForSsh: (String) -> Boolean = { false },
    /** 老地址只补 SSH 授权，不改提示豁免。 */
    onConfirmSshTrust: (String) -> Boolean = { false },
    voiceEnabled: Boolean = false,
    onVoiceEnabled: (Boolean) -> Unit = {},
    voiceTrigger: VoiceTrigger = VoiceTrigger.TAP,
    onVoiceTrigger: (VoiceTrigger) -> Unit = {},
    /** 转写来源，默认本地识别。 */
    voiceSource: io.qwenpaw.node.ui.VoiceSource = io.qwenpaw.node.ui.VoiceSource.LOCAL,
    onVoiceSource: (io.qwenpaw.node.ui.VoiceSource) -> Unit = {},
    /** 本变体是否带端上识别引擎。`_no-sherpa` 包为 false，此时不显示来源与模型两段。 */
    localAsrAvailable: Boolean = false,
    /** 端上识别模型的状态。 */
    localModel: io.qwenpaw.node.voice.LocalModelState =
        io.qwenpaw.node.voice.LocalModelState.NotInstalled,
    onDownloadModel: () -> Unit = {},
    onRemoveModel: () -> Unit = {},
    /** 「说完自动停止」，只在本地识别 + 点一下模式下显示。 */
    voiceAutoStop: Boolean = true,
    onVoiceAutoStop: (Boolean) -> Unit = {},
    /** 服务端语音转写的可用性，语音页底部据此给结论并支持重探。 */
    transcribeStatus: io.qwenpaw.node.data.transcription.TranscriptionStatus =
        io.qwenpaw.node.data.transcription.TranscriptionStatus.Unknown,
    onRefreshTranscribe: () -> Unit = {},
    assistantCollapse: io.qwenpaw.node.ui.chat.AssistantCollapse =
        io.qwenpaw.node.ui.chat.AssistantCollapse.RESULT_COLLAPSED,
    onAssistantCollapse: (io.qwenpaw.node.ui.chat.AssistantCollapse) -> Unit = {},
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
    /** 是否使用服务端（网页端）头像。本机头像优先，关掉不影响本机头像。 */
    useServerAvatar: Boolean = true,
    onUseServerAvatar: (Boolean) -> Unit = {},
    /** 服务端头像的本地缓存文件，用于预览。 */
    serverAvatarFile: java.io.File? = null,
    /** 进入设置页时重新核对服务端头像。 */
    onRefreshServerAvatar: () -> Unit = {},
    /** 消息底部操作行样式。 */
    actionStyle: io.qwenpaw.node.ui.chat.ActionStyle = io.qwenpaw.node.ui.chat.ActionStyle.TEXT,
    onActionStyle: (io.qwenpaw.node.ui.chat.ActionStyle) -> Unit = {},
    /** 用户消息是否按 Markdown / LaTeX 渲染。 */
    renderUserMarkdown: Boolean = true,
    onRenderUserMarkdown: (Boolean) -> Unit = {},
    /** 智能体消息是否按 Markdown / LaTeX 渲染。 */
    renderAssistantMarkdown: Boolean = true,
    onRenderAssistantMarkdown: (Boolean) -> Unit = {},
    /** 工具、环境变量、后台策略与定时任务的状态与动作。为 null 时这些入口不显示。 */
    config: SettingsConfig? = null,
    /** 配套插件的探测结论，显示在「服务端配置」的第一行。 */
    bridgeStatus: io.qwenpaw.node.data.plugin.BridgeStatus =
        io.qwenpaw.node.data.plugin.BridgeStatus.Unknown,
    /** 重新探测配套插件。 */
    onRefreshBridge: () -> Unit = {},
    /** 依赖配套插件的功能总开关。 */
    bridgeFeaturesEnabled: Boolean = false,
    onBridgeFeaturesEnabled: (Boolean) -> Unit = {},
    /** 未装插件时点开关：跳扩展市场并预填安装地址。 */
    onInstallBridge: () -> Unit = {},
    /** 退出登录：吊销服务端 token 并清本地凭据。 */
    onLogout: (String) -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    // 待确认退出登录的实例。
    var logoutFor by remember { mutableStateOf<InstanceConfig?>(null) }
    // 添加实例的两种方式：null 表示在方式选择页，否则是具体形态。
    var addMode by remember { mutableStateOf<AddMode?>(null) }
    var editing by remember { mutableStateOf<InstanceConfig?>(null) }
    var languagePicker by remember { mutableStateOf(false) }
    var appLanguagePicker by remember { mutableStateOf(false) }
    var askInstallBridge by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val confirmBrowser = io.qwenpaw.node.ui.common.rememberBrowserConfirm()

    // 每次进入设置页核对一次服务端头像。不做轮询：头像不是时效性数据，
    // 用户改完网页端的头像再来这里就是最常见的一次刷新时机。
    androidx.compose.runtime.LaunchedEffect(Unit) { onRefreshServerAvatar() }

    // 一级页当前打开的分区。MAIN 是列表，其余是子页。
    var section by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    // 内部子页是否从「服务端配置」进入；是则返回二级页，否则回一级。
    var serverConfigChild by remember { mutableStateOf(false) }
    // 待确认移除的实例。移除会连带清掉地址与智能体选择，点一下就走太危险。
    var pendingRemove by remember { mutableStateOf<InstanceConfig?>(null) }

    // 从更新对话框的「关闭检查更新」进来时直接落到关于页。
    // 消费掉标记后再设分区：否则每次返回设置页都会被拉回关于。
    androidx.compose.runtime.LaunchedEffect(openAboutOnEnter) {
        if (openAboutOnEnter) {
            section = SettingsPage.ABOUT
            onConsumeOpenAboutOnEnter()
        }
    }

    // 每一页上次离开时的滚动位置。
    //
    // 必须存在 AnimatedContent 外面：它为每个目标页单独建组合，页内的
    // rememberScrollState 会随旧页一起销毁，返回时只能从 0 重新开始——
    // 表现为「滑到底点进『关于』，返回后跳回顶部」。
    var scrollOffsets by rememberSaveable {
        mutableStateOf(List(SettingsPage.entries.size) { 0 })
    }

    // 一级收藏：只存稳定 id，不存枚举序号；服务端配置组里可收藏的项在二级页加星。
    val prefs = remember {
        context.getSharedPreferences("qwenpaw_ui", android.content.Context.MODE_PRIVATE)
    }
    var favorites by remember {
        mutableStateOf(prefs.getStringSet("settings_favorites", emptySet()) ?: emptySet())
    }
    fun toggleFavorite(id: String) {
        val next = if (id in favorites) favorites - id else favorites + id
        favorites = next
        prefs.edit().putStringSet("settings_favorites", next).apply()
    }
    fun openFavorite(id: String) {
        // 收藏在一级，返回也回一级。
        serverConfigChild = false
        when (id) {
            "models" -> onOpenModels()
            "agents" -> onOpenAgents()
            "runtime" -> onOpenRuntime()
            "envs" -> {
                config?.onLoadEnvs()
                section = SettingsPage.ENVIRONMENTS
            }
            "cron" -> {
                config?.onLoadCron()
                section = SettingsPage.CRON
            }
            "market" -> onOpenMarket()
            "acl" -> onOpenAccessControl()
            "security" -> {
                config?.onLoadSecurity()
                onOpenSecurity()
            }
        }
    }

    // 子页的导航状态。
    //
    // 必须把「页 + 这一页依赖的数据」作为整体交给 AnimatedContent，而不是只传枚举：
    // 只传枚举时，正在退出的旧槽位仍会读外部的 addMode / editing，
    // 而它们在点击瞬间已经变了，旧槽位就被画成新页面——
    // 表现为先瞬间跳到目标页，再两个相同的画面互推一遍。
    val nav = SettingsNav(
        page = when {
            adding && addMode == null -> SettingsPage.ADD_MODE
            adding && addMode == AddMode.FORM -> SettingsPage.ADD_FORM
            adding && addMode == AddMode.WIZARD -> SettingsPage.ADD_WIZARD
            editing != null -> SettingsPage.EDIT
            else -> section
        },
        addMode = addMode,
        editing = editing,
    )

    // 系统返回键按层级后退：表单 → 方式选择 → 关闭添加；子页 → 一级列表。
    androidx.activity.compose.BackHandler(enabled = nav.page != SettingsPage.MAIN) {
        when (nav.page) {
            SettingsPage.ADD_FORM, SettingsPage.ADD_WIZARD -> addMode = null
            SettingsPage.ADD_MODE -> adding = false
            SettingsPage.EDIT -> editing = null
            SettingsPage.APPEARANCE_PREVIEW -> section = SettingsPage.APPEARANCE
            // 受信任的连接是服务器页的下一级，返回时回到服务器而不是一级列表。
            SettingsPage.TRUSTED -> section = SettingsPage.SERVER
            else -> {
                if (serverConfigChild) {
                    serverConfigChild = false
                    section = SettingsPage.SERVER_CONFIG
                } else {
                    section = SettingsPage.MAIN
                }
            }
        }
    }

    if (languagePicker && config != null) {
        LanguagePickerDialog(
            current = config.serverSettings.language,
            onPick = { code ->
                languagePicker = false
                config.onSetServerLanguage(code)
            },
            onDismiss = { languagePicker = false },
        )
    }

    if (appLanguagePicker) {
        AppLanguagePickerDialog(
            current = appLanguage,
            onPick = { language ->
                appLanguagePicker = false
                onAppLanguage(language)
            },
            onDismiss = { appLanguagePicker = false },
        )
    }

    // 移除实例的确认。不提供撤销：服务端与本机都没有回收站，
    // 承诺一个做不到的撤销比多弹一次确认更糟。
    pendingRemove?.let { instance ->
        val unnamed = stringResource(R.string.settings_instance_unnamed)
        io.qwenpaw.node.ui.common.DestructiveActionDialog(
            title = stringResource(R.string.settings_instance_remove_title),
            message = stringResource(
                R.string.settings_instance_remove_message,
                instance.name.ifBlank { unnamed },
            ),
            impacts = listOf(
                stringResource(R.string.settings_instance_remove_impact_addresses),
                stringResource(R.string.settings_instance_remove_impact_cache),
            ),
            confirmLabel = stringResource(R.string.action_remove),
            undoable = false,
            onConfirm = {
                onRemove(instance.id)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null },
        )
    }

    // 退出登录的确认。吊销凭据是不可撤销的服务端动作，必须二次确认。
    logoutFor?.let { instance ->
        AlertDialog(
            onDismissRequest = { logoutFor = null },
            title = { Text(stringResource(R.string.settings_logout_title)) },
            text = {
                Text(stringResource(R.string.settings_logout_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    onLogout(instance.id)
                    logoutFor = null
                }) {
                    Text(
                        text = stringResource(R.string.settings_logout_title),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { logoutFor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // 未装配套插件时点开关：先问是否安装，确认后跳扩展市场并预填地址。
    if (askInstallBridge) {
        AlertDialog(
            onDismissRequest = { askInstallBridge = false },
            title = { Text(stringResource(R.string.bridge_guide_title)) },
            text = { Text(stringResource(R.string.bridge_guide_message)) },
            confirmButton = {
                TextButton(onClick = {
                    askInstallBridge = false
                    onInstallBridge()
                }) { Text(stringResource(R.string.bridge_guide_install)) }
            },
            dismissButton = {
                TextButton(onClick = { askInstallBridge = false }) {
                    Text(stringResource(R.string.bridge_guide_later))
                }
            },
        )
    }

    // 「关闭检查更新」的二次确认。关闭不影响已保存的实例与偏好，只是不再自动查新版，
    // 因此确认按钮用同一句动作文案，取消不写任何配置。
    if (pendingDisableUpdateCheck) {
        AlertDialog(
            onDismissRequest = onConsumePendingDisableUpdateCheck,
            title = { Text(stringResource(R.string.settings_update_disable_confirm_title)) },
            text = { Text(stringResource(R.string.settings_update_disable_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onAutoUpdateCheck(false)
                    onConsumePendingDisableUpdateCheck()
                }) { Text(stringResource(R.string.update_action_disable_check)) }
            },
            dismissButton = {
                TextButton(onClick = onConsumePendingDisableUpdateCheck) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    androidx.compose.animation.AnimatedContent(
        targetState = nav,
        transitionSpec = {
            // 子页的入口都在行尾箭头（屏幕右边缘），因此深入从右滑入、返回向右退出。
            val forward = targetState.page.ordinal > initialState.page.ordinal
            (androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(180),
            ) + androidx.compose.animation.slideInHorizontally(
                androidx.compose.animation.core.tween(220),
            ) { if (forward) it / 3 else -it / 3 })
                .togetherWith(
                    androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(140),
                    ) + androidx.compose.animation.slideOutHorizontally(
                        androidx.compose.animation.core.tween(220),
                    ) { if (forward) -it / 3 else it / 3 },
                )
        },
        label = "settings-page",
    ) { current ->
    when (current.page) {
    // 添加实例先选方式：填表适合熟手，引导适合不清楚各项含义的人。
    SettingsPage.ADD_MODE, SettingsPage.ADD_FORM, SettingsPage.ADD_WIZARD -> {
        var addProxyAuthOpen by remember(current.addMode) { mutableStateOf(false) }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            // 反代二级页自己处理系统栏与键盘；打开时不能再让父层留一遍。
            contentWindowInsets = if (addProxyAuthOpen) {
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            } else {
                androidx.compose.foundation.layout.WindowInsets.systemBars
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // 添加实例的两种模式都要填表，键盘弹出时内容必须整体让开，
                    // 否则表单下半部分始终被挡住。
                    .then(if (addProxyAuthOpen) Modifier else Modifier.imePadding()),
            ) {
                if (!addProxyAuthOpen) {
                    SettingsTopBar(
                        onBack = {
                            if (addMode != null) addMode = null else adding = false
                        },
                        subtitle = when (current.addMode) {
                            null -> stringResource(R.string.settings_add_instance)
                            AddMode.FORM -> stringResource(R.string.settings_add_instance_form)
                            AddMode.WIZARD -> stringResource(R.string.settings_add_instance_wizard)
                        },
                    )
                }
                // 读 current 而不是外部 addMode：退出中的旧槽位要保留它自己那一页的内容。
                when (current.addMode) {
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
                        // 新增实例也可能用访问令牌：验证与读取列表都要带上它，
                        // 否则令牌方式会退化成「空账号密码」并拉不到列表。
                        onVerifyHosts = { hosts, basic, attempt, token ->
                            onVerifyHosts(hosts, basic, attempt, token, null)
                        },
                        loadAgentsWithToken = { hosts, token, basic ->
                            onLoadAgentsWithToken(hosts, token, basic)
                        },
                        currentNetKind = currentNetKind,
                        currentSsid = currentSsid,
                        submitLabel = stringResource(R.string.action_add),
                        isTrustedHost = isTrustedHost,
                        onCancel = { addMode = null },
                        scannedPair = scannedPair,
                        onConsumeScannedPair = onConsumeScannedPair,
                        onOpenScanner = onOpenScanner,
                        proxyAuthOpen = addProxyAuthOpen,
                        onProxyAuthOpenChange = { addProxyAuthOpen = it },
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
                        onCheckLogin = onCheckLogin,
                        onLoadAgents = onLoadAgents,
                        // 第 1 步只验地址，还没有登录，插件版本留给后面的页签确认。
                        onVerifyHosts = { hosts, basic, attempt ->
                            onVerifyHosts(hosts, basic, attempt, null, null)
                        },
                        isTrustedHost = isTrustedHost,
                        scannedPair = scannedPair,
                        onConsumeScannedPair = onConsumeScannedPair,
                        onOpenScanner = onOpenScanner,
                        onLoadAgentsWithToken = onLoadAgentsWithToken,
                    )
                }
            }
        }
    }

    // 编辑实例同样走这套交互，地址预填并自动读取智能体列表。
    SettingsPage.EDIT -> current.editing?.let { instance ->
        val context = LocalContext.current
        val editScope = rememberCoroutineScope()
        var editSaving by remember(instance.id) { mutableStateOf(false) }
        var editError by remember(instance.id) { mutableStateOf<String?>(null) }
        var showSshProfile by remember(instance.id) { mutableStateOf(false) }
        var editProxyAuthOpen by remember(instance.id) { mutableStateOf(false) }
        val sshProfileStore = remember(context, instance.id) {
            io.qwenpaw.node.data.terminal.SshProfileStore(context)
        }
        // 二级页覆盖在实例编辑表单之上：表单状态不丢，也不必另开 Dialog 窗口。
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                // 编辑表单的输入框在正文里，键盘高度并进 Scaffold 的 padding：
                // 顶栏不动，正文整体上移，滑到底能看见保存按钮。
                contentWindowInsets = if (editProxyAuthOpen) {
                    androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                } else {
                    pageContentInsets()
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (!editProxyAuthOpen) {
                        SettingsTopBar(
                            onBack = { editing = null },
                            subtitle = stringResource(R.string.settings_edit_instance),
                        )
                    }
                    InstanceForm(
                        showHeader = false,
                        connecting = editSaving,
                        error = editError,
                        initialAddresses = instance.addresses,
                        initialName = instance.name,
                        initialUsername = instance.username.orEmpty(),
                        initialAgentId = instance.agentId,
                        initialBasicUser = instance.basicUser.orEmpty(),
                        // 密码不回显：编辑页留空表示「不修改」，填了才覆盖。
                        // 与「留空即清空」相比，这种做法不会因为用户没注意就丢掉配置。
                        initialBasicPassword = "",
                        onConnect = { draft ->
                            editScope.launch {
                                editSaving = true
                                editError = null
                                val failure = onEditWithCredentials(instance, draft)
                                editSaving = false
                                if (failure == null) {
                                    editing = null
                                } else {
                                    editError = failure
                                }
                            }
                        },
                        onLoadAgents = { _, _, _, _ -> emptyList() },
                        // 编辑已有实例：带上保存的 token，验证时才能读到配套插件版本。
                        onVerifyHosts = { hosts, basic, attempt, token ->
                            onVerifyHosts(hosts, basic, attempt, token, instance.id)
                        },
                        verifyToken = instance.token,
                        loadAgentsWithToken = { _, _, _ -> onLoadAgentsFor(instance) },
                        initialMode = if (instance.usesTokenCredential) {
                            io.qwenpaw.node.data.CredentialMode.TOKEN
                        } else {
                            io.qwenpaw.node.data.CredentialMode.PASSWORD
                        },
                        scannedPair = scannedPair,
                        onConsumeScannedPair = onConsumeScannedPair,
                        onOpenScanner = onOpenScanner,
                        currentNetKind = currentNetKind,
                        currentSsid = currentSsid,
                        submitLabel = stringResource(R.string.action_save),
                        isTrustedHost = isTrustedHost,
                        onOpenSshProfile = { showSshProfile = true },
                        onCancel = { editing = null },
                        proxyAuthOpen = editProxyAuthOpen,
                        onProxyAuthOpenChange = { editProxyAuthOpen = it },
                    )
                }
            }
            if (showSshProfile) {
                SshProfileScreen(
                    store = sshProfileStore,
                    instanceId = instance.id,
                    onBack = { showSshProfile = false },
                )
            }
        }
    }

    // 一级列表与各子页共用同一个 Scaffold：只有内容按 section 切换，
// 这样标题栏与返回行为不必在每页重复一遍。
    else -> Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        // 这一分支必须读 current.page 而不是外层 section：
        // section 在点击瞬间就变成新值，正在退出的旧槽位会被画成新页面，
        // 表现为先瞬切到目标页、再两个相同画面互推一遍。
        val page = current.page
        // 直接以保存的位置作为初值：先建在顶部再用 LaunchedEffect 滚下去，
        // 会在动画结束后补一次可见的跳动。没来过的页保存值是 0，即从顶部开始。
        val scrollState = rememberScrollState(
            initial = scrollOffsets.getOrElse(page.ordinal) { 0 },
        )
        // 离开这一页（含 AnimatedContent 的退出动画结束）时记下位置。
        androidx.compose.runtime.DisposableEffect(page) {
            onDispose {
                // 枚举新增页后，升级前保存的列表比新枚举短，直接按下标写会越界。
                val next = scrollOffsets.toMutableList()
                while (next.size <= page.ordinal) next += 0
                next[page.ordinal] = scrollState.value
                scrollOffsets = next
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SettingsTopBar(
                // 字号预览是外观页的下一级，返回要回到外观，不能一键跳回一级列表。
                onBack = {
                    when (page) {
                        SettingsPage.MAIN -> onBack()
                        SettingsPage.APPEARANCE_PREVIEW -> section = SettingsPage.APPEARANCE
                        SettingsPage.TRUSTED -> section = SettingsPage.SERVER
                        else -> {
                            if (serverConfigChild) {
                                serverConfigChild = false
                                section = SettingsPage.SERVER_CONFIG
                            } else {
                                section = SettingsPage.MAIN
                            }
                        }
                    }
                },
                subtitle = if (page == SettingsPage.MAIN) {
                    stringResource(R.string.settings_version_subtitle, appVersion)
                } else {
                    sectionTitle(page)
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 顶栏在正文之上不随键盘上移，只把滚动区让开键盘：
                    // 关键词在页内的定时任务、工具与环境变量表单滑到底才看得见保存。
                    .pageImePadding()
                    .then(
                        // 预览页的滑块必须钉在底部，这一页不能整页滚动。
                        if (page == SettingsPage.APPEARANCE_PREVIEW) {
                            Modifier
                        } else {
                            Modifier.verticalScroll(scrollState)
                        },
                    )
                    .padding(bottom = 24.dp),
            ) {
                when (page) {
                SettingsPage.MAIN -> {
                    // 一级列表：每行一个入口，副标题直接给出当前状态，
                    // 不必点进去才知道「语音是开还是关」。
                    Card {
                        NavRow(
                            icon = QwenIcons.server,
                            title = stringResource(R.string.settings_nav_server),
                            subtitle = serverSummary(instances, activeId),
                            onClick = { section = SettingsPage.SERVER },
                        )
                    }
                    SectionHeader(stringResource(R.string.settings_section_profile_appearance))
                    Card {
                        NavRow(
                            icon = QwenIcons.user,
                            title = stringResource(R.string.settings_nav_profile),
                            subtitle = stringResource(R.string.settings_nav_profile_subtitle),
                            onClick = { section = SettingsPage.PROFILE },
                        )
                        Divider()
                        NavRow(
                            icon = QwenIcons.palette,
                            title = stringResource(R.string.settings_nav_appearance),
                            subtitle = stringResource(R.string.settings_nav_appearance_subtitle),
                            onClick = { section = SettingsPage.APPEARANCE },
                        )
                        Divider()
                        NavRow(
                            icon = QwenIcons.messageSquare,
                            title = stringResource(R.string.settings_nav_messages),
                            subtitle = stringResource(R.string.settings_nav_messages_subtitle),
                            onClick = { section = SettingsPage.MESSAGES },
                        )
                    }
                    SectionHeader(stringResource(R.string.settings_section_this_phone))
                    Card {
                        NavRow(
                            icon = QwenIcons.mic,
                            title = stringResource(R.string.settings_nav_voice),
                            subtitle = stringResource(R.string.settings_nav_voice_subtitle),
                            onClick = { section = SettingsPage.VOICE },
                        )
                        Divider()
                        NavRow(
                            icon = QwenIcons.bell,
                            title = stringResource(R.string.settings_nav_behavior),
                            subtitle = stringResource(R.string.settings_nav_behavior_subtitle),
                            onClick = { section = SettingsPage.BEHAVIOR },
                        )
                        Divider()
                        NavRow(
                            icon = QwenIcons.receiptText,
                            title = stringResource(R.string.settings_nav_logs),
                            subtitle = stringResource(R.string.settings_nav_logs_subtitle),
                            onClick = { section = SettingsPage.LOGS },
                        )
                    }
                    val favoriteIds = FAVORITE_ORDER.filter { it in favorites }.take(6)
                    if (favoriteIds.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.settings_section_favorites))
                        Card {
                            favoriteIds.forEachIndexed { index, id ->
                                val entry = FAVORITE_ENTRIES[id] ?: return@forEachIndexed
                                NavRow(
                                    icon = entry.icon,
                                    title = stringResource(entry.titleRes),
                                    subtitle = stringResource(entry.subtitleRes),
                                    onClick = { openFavorite(id) },
                                )
                                if (index != favoriteIds.lastIndex) Divider()
                            }
                        }
                    }
                    if (config != null) {
                        Card {
                            NavRow(
                                icon = QwenIcons.server,
                                title = stringResource(R.string.settings_section_server_config),
                                subtitle = stringResource(R.string.settings_nav_server_config_subtitle),
                                onClick = {
                                    serverConfigChild = false
                                    section = SettingsPage.SERVER_CONFIG
                                },
                            )
                        }
                    }
                    SectionHeader(stringResource(R.string.settings_nav_about))
                    Card {
                        NavRow(
                            icon = QwenIcons.info,
                            title = stringResource(R.string.settings_nav_about),
                            subtitle = stringResource(R.string.settings_version, appVersion),
                            onClick = { section = SettingsPage.ABOUT },
                        )
                    }
                }

                // 服务端配置：二级菜单，避免一级列表被二十多行占满。
                SettingsPage.SERVER_CONFIG -> {
                    if (config != null) {
                        SectionHeader(stringResource(R.string.settings_group_models_agents))
                        Card {
                            NavRow(
                                icon = QwenIcons.zap,
                                title = stringResource(R.string.settings_nav_models),
                                subtitle = stringResource(R.string.settings_nav_models_subtitle),
                                onClick = onOpenModels,
                                trailing = { FavoriteStar("models" in favorites) { toggleFavorite("models") } },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.sparkles,
                                title = stringResource(R.string.settings_nav_agents),
                                subtitle = stringResource(R.string.settings_nav_agents_subtitle),
                                onClick = onOpenAgents,
                                trailing = { FavoriteStar("agents" in favorites) { toggleFavorite("agents") } },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.workflow,
                                title = stringResource(R.string.settings_nav_runtime),
                                subtitle = stringResource(R.string.settings_nav_runtime_subtitle),
                                onClick = onOpenRuntime,
                                trailing = { FavoriteStar("runtime" in favorites) { toggleFavorite("runtime") } },
                            )
                        }

                        SectionHeader(stringResource(R.string.settings_group_tools_extensions))
                        Card {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onRefreshBridge)
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = QwenIcons.packageIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.settings_bridge_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    val featureLine = when {
                                        bridgeStatus is io.qwenpaw.node.data.plugin.BridgeStatus.Ready &&
                                            bridgeFeaturesEnabled ->
                                            stringResource(R.string.settings_bridge_feature_on)
                                        bridgeStatus is io.qwenpaw.node.data.plugin.BridgeStatus.Ready ->
                                            stringResource(R.string.settings_bridge_feature_off)
                                        else -> stringResource(R.string.settings_bridge_unavailable)
                                    }
                                    Text(
                                        text = bridgeStatus.label + "\n" + featureLine,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        softWrap = true,
                                    )
                                }
                                Switch(
                                    checked = bridgeFeaturesEnabled &&
                                        bridgeStatus is io.qwenpaw.node.data.plugin.BridgeStatus.Ready,
                                    onCheckedChange = { on ->
                                        if (bridgeStatus is io.qwenpaw.node.data.plugin.BridgeStatus.Ready) {
                                            onBridgeFeaturesEnabled(on)
                                        } else if (bridgeStatus is io.qwenpaw.node.data.plugin.BridgeStatus.Absent) {
                                            askInstallBridge = true
                                        }
                                    },
                                    enabled = bridgeStatus is io.qwenpaw.node.data.plugin.BridgeStatus.Ready ||
                                        bridgeStatus is io.qwenpaw.node.data.plugin.BridgeStatus.Absent,
                                )
                            }
                            Divider()
                            NavRow(
                                icon = QwenIcons.globe,
                                title = stringResource(R.string.settings_nav_channels),
                                subtitle = stringResource(R.string.settings_nav_channels_subtitle),
                                onClick = onOpenChannels,
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.monitor,
                                title = stringResource(R.string.settings_nav_browser_title),
                                subtitle = stringResource(R.string.settings_nav_browser_subtitle),
                                onClick = onOpenBrowserDiagnostics,
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.wrench,
                                title = stringResource(R.string.settings_nav_tools),
                                subtitle = if (config.tools.loaded) {
                                    pluralStringResource(
                                        R.plurals.settings_tools_enabled_summary,
                                        config.tools.tools.size,
                                        config.tools.tools.count { it.enabled },
                                        config.tools.tools.size,
                                    )
                                } else {
                                    stringResource(R.string.settings_nav_tools_subtitle)
                                },
                                onClick = {
                                    config.onLoadTools()
                                    serverConfigChild = true
                                    section = SettingsPage.TOOLS
                                },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.sparkles,
                                title = stringResource(R.string.settings_nav_skills),
                                subtitle = stringResource(R.string.settings_nav_skills_subtitle),
                                onClick = onOpenSkills,
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.blocks,
                                title = stringResource(R.string.settings_nav_mcp),
                                subtitle = stringResource(R.string.settings_nav_mcp_subtitle),
                                onClick = onOpenMcp,
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.library,
                                title = stringResource(R.string.settings_nav_market),
                                subtitle = stringResource(R.string.settings_nav_market_subtitle),
                                onClick = onOpenMarket,
                                trailing = { FavoriteStar("market" in favorites) { toggleFavorite("market") } },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.blocks,
                                title = stringResource(R.string.settings_nav_app_center),
                                subtitle = stringResource(R.string.settings_nav_app_center_subtitle),
                                onClick = onOpenAppCenter,
                            )
                        }

                        SectionHeader(stringResource(R.string.settings_group_access_security))
                        Card {
                            NavRow(
                                icon = QwenIcons.shieldCheck,
                                title = stringResource(R.string.acl_title),
                                subtitle = stringResource(R.string.acl_entry_subtitle),
                                onClick = onOpenAccessControl,
                                trailing = { FavoriteStar("acl" in favorites) { toggleFavorite("acl") } },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.shieldCheck,
                                title = stringResource(R.string.settings_nav_security),
                                subtitle = stringResource(R.string.settings_nav_security_subtitle),
                                onClick = {
                                    config.onLoadSecurity()
                                    onOpenSecurity()
                                },
                                trailing = { FavoriteStar("security" in favorites) { toggleFavorite("security") } },
                            )
                        }

                        SectionHeader(stringResource(R.string.settings_group_data_ops))
                        Card {
                            NavRow(
                                icon = QwenIcons.zap,
                                title = stringResource(R.string.settings_nav_envs),
                                subtitle = if (config.envs.loaded) {
                                    pluralStringResource(
                                        R.plurals.settings_envs_configured_summary,
                                        config.envs.vars.size,
                                        config.envs.vars.size,
                                    )
                                } else {
                                    stringResource(R.string.settings_nav_envs_subtitle)
                                },
                                onClick = {
                                    config.onLoadEnvs()
                                    serverConfigChild = true
                                    section = SettingsPage.ENVIRONMENTS
                                },
                                trailing = { FavoriteStar("envs" in favorites) { toggleFavorite("envs") } },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.calendarClock,
                                title = stringResource(R.string.settings_nav_cron),
                                subtitle = if (config.cron.loaded) {
                                    pluralStringResource(
                                        R.plurals.settings_cron_jobs_summary,
                                        config.cron.jobs.size,
                                        config.cron.jobs.size,
                                    )
                                } else {
                                    stringResource(R.string.settings_nav_cron_subtitle)
                                },
                                onClick = {
                                    config.onLoadCron()
                                    serverConfigChild = true
                                    section = SettingsPage.CRON
                                },
                                trailing = { FavoriteStar("cron" in favorites) { toggleFavorite("cron") } },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.clock,
                                title = stringResource(R.string.settings_nav_offload),
                                subtitle = if (config.offload.action == OFFLOAD) {
                                    stringResource(R.string.settings_nav_offload_subtitle_offload)
                                } else {
                                    stringResource(R.string.settings_nav_offload_subtitle_abort)
                                },
                                onClick = {
                                    config.onLoadOffload()
                                    serverConfigChild = true
                                    section = SettingsPage.OFFLOAD
                                },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.folder,
                                title = stringResource(R.string.settings_nav_workspace),
                                subtitle = stringResource(R.string.settings_nav_workspace_subtitle),
                                onClick = onOpenWorkspace,
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.terminal,
                                title = stringResource(R.string.settings_nav_terminal),
                                subtitle = stringResource(R.string.settings_nav_terminal_subtitle),
                                onClick = onOpenTerminal,
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.packageIcon,
                                title = stringResource(R.string.settings_nav_backup),
                                subtitle = stringResource(R.string.settings_nav_backup_subtitle),
                                onClick = onOpenBackup,
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.workflow,
                                title = stringResource(R.string.settings_nav_checkpoint),
                                subtitle = stringResource(R.string.settings_nav_checkpoint_subtitle),
                                onClick = onOpenCheckpoint,
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.settings,
                                title = stringResource(R.string.settings_nav_running_config),
                                subtitle = stringResource(R.string.settings_nav_running_config_subtitle),
                                onClick = {
                                    config.onLoadRunningConfig()
                                    onOpenRunningConfig()
                                },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.languages,
                                title = stringResource(R.string.settings_nav_server_language),
                                subtitle = serverLanguageLabel(config.serverSettings.language),
                                onClick = {
                                    config.onLoadServerSettings()
                                    languagePicker = true
                                },
                            )
                            Divider()
                            SettingLabel(
                                title = stringResource(R.string.settings_upload_limit_title),
                                subtitle = when {
                                    !config.serverSettings.loaded ->
                                        stringResource(R.string.settings_upload_limit_unread)
                                    config.serverSettings.uploadLimitMb == null ->
                                        stringResource(R.string.settings_upload_limit_none)
                                    else -> "${config.serverSettings.uploadLimitMb} MB"
                                },
                            )
                            Divider()
                            NavRow(
                                icon = QwenIcons.barChart,
                                title = stringResource(R.string.settings_nav_usage),
                                subtitle = stringResource(R.string.settings_nav_usage_subtitle),
                                onClick = onOpenUsage,
                            )
                        }
                    }
                }

                // 服务器实例
                SettingsPage.SERVER -> {
                SectionHeader(
                    stringResource(R.string.settings_section_server_instances),
                    pluralStringResource(
                        R.plurals.settings_item_count_summary,
                        instances.size,
                        instances.size,
                    ),
                )
                Card {
                    instances.forEachIndexed { index, instance ->
                        InstanceRow(
                            instance = instance,
                            active = instance.id == activeId,
                            onSwitch = { onSwitch(instance.id) },
                            onRemove = { pendingRemove = instance },
                            onEdit = { editing = instance },
                            onPickAgent = { onPickAgent(instance) },
                            onChooseAddress = { url -> onChooseAddress(instance.id, url) },
                            onChooseSessionAddress = { url -> onChooseSessionAddress(instance.id, url) },
                            sessionPick = sessionAddressOf(instance.id),
                            onVerifySingle = { url -> onVerifySingleAddress(instance.id, url) },
                            onLogout = { logoutFor = instance },
                        )
                        if (index != instances.lastIndex) Divider()
                    }
                    if (instances.isNotEmpty()) Divider()
                    // 受信任的连接紧挨实例清单：它记的正是这些实例的地址。
                    NavRow(
                        icon = QwenIcons.shieldCheck,
                        title = stringResource(R.string.settings_nav_trusted),
                        subtitle = pluralStringResource(
                            R.plurals.settings_trusted_hosts_summary,
                            trustedHosts.size,
                            trustedHosts.size,
                        ),
                        onClick = { section = SettingsPage.TRUSTED },
                    )
                    Divider()
                    ActionRow(
                        text = stringResource(R.string.settings_add_instance),
                        onClick = { adding = true },
                    )
                }
                }

                SettingsPage.TRUSTED -> {
                    TrustedHostsPage(
                        hosts = trustedHosts,
                        sshHosts = sshTrustedHosts,
                        onAddForSsh = onAddTrustedHostForSsh,
                        onConfirmSshTrust = onConfirmSshTrust,
                        onRemove = onRemoveTrustedHost,
                        securityHintsEnabled = securityHintsEnabled,
                        onSecurityHintsEnabled = onSecurityHintsEnabled,
                    )
                }

                SettingsPage.APPEARANCE -> {
                Card {
                    // 语言放在最前：它决定界面文案，其余外观项都建立在语言之上。
                    NavRow(
                        icon = QwenIcons.globe,
                        title = stringResource(R.string.settings_app_language_title),
                        subtitle = stringResource(appLanguage.labelRes),
                        onClick = { appLanguagePicker = true },
                    )
                    Divider()
                    // 主题不另起一行标题：三张预览卡下方各自写着「跟随系统」「浅色」「深色」，
                    // 选中的那张有描边，再加一行说明只是把同一件事说第二遍。
                    ThemeSelector(
                        current = themeMode,
                        onPick = onThemeMode,
                    )
                    Divider()
                    ScaleSlider(
                        title = stringResource(R.string.settings_ui_scale_title),
                        subtitle = stringResource(
                            R.string.settings_ui_scale_subtitle,
                            percent(uiScale),
                        ),
                        value = uiScale,
                        min = io.qwenpaw.node.ui.theme.FontScaling.UI_MIN,
                        max = io.qwenpaw.node.ui.theme.FontScaling.UI_MAX,
                        onValueChange = onUiScale,
                    )
                    Divider()
                    ScaleSlider(
                        title = stringResource(R.string.settings_chat_scale_title),
                        subtitle = stringResource(
                            R.string.settings_chat_scale_subtitle,
                            percent(chatScale),
                        ),
                        value = chatScale,
                        min = io.qwenpaw.node.ui.theme.FontScaling.CHAT_MIN,
                        max = io.qwenpaw.node.ui.theme.FontScaling.CHAT_MAX,
                        onValueChange = onChatScale,
                        onPreview = { section = SettingsPage.APPEARANCE_PREVIEW },
                    )
                    Divider()
                    // MiSans 与字号同属「字怎么显示」，放在外观组而不是通知组。
                    SwitchRow(
                        title = stringResource(R.string.settings_use_misans_title),
                        subtitle = if (fontState is FontState.Ready) {
                            stringResource(R.string.settings_use_misans_ready)
                        } else {
                            stringResource(R.string.settings_use_misans_not_ready)
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
                        title = stringResource(R.string.settings_lock_portrait_title),
                        subtitle = stringResource(R.string.settings_lock_portrait_subtitle),
                        checked = lockPortrait,
                        onCheckedChange = onLockPortrait,
                    )
                }
                }

                SettingsPage.APPEARANCE_PREVIEW -> {
                    ChatFontPreview(
                        chatScale = chatScale,
                        onChatScale = onChatScale,
                        modifier = Modifier.weight(1f),
                    )
                }

                SettingsPage.PROFILE -> {
                Card {
                    AvatarRow(
                        title = stringResource(R.string.settings_avatar_user_title),
                        subtitle = stringResource(R.string.settings_avatar_user_subtitle),
                        kind = io.qwenpaw.node.avatar.AvatarStore.KIND_USER,
                        store = avatarStore,
                        version = avatarVersion,
                        onPick = onPickAvatar,
                        onRemove = onRemoveAvatar,
                    )
                    Divider()
                    AvatarRow(
                        title = stringResource(R.string.settings_avatar_agent_title),
                        subtitle = stringResource(R.string.settings_avatar_agent_subtitle),
                        kind = io.qwenpaw.node.avatar.AvatarStore.KIND_AGENT,
                        store = avatarStore,
                        version = avatarVersion,
                        onPick = onPickAvatar,
                        onRemove = onRemoveAvatar,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(R.string.settings_use_server_avatar_title),
                        subtitle = stringResource(R.string.settings_use_server_avatar_subtitle),
                        checked = useServerAvatar,
                        onCheckedChange = onUseServerAvatar,
                    )
                    Divider()
                    ServerAvatarRow(file = serverAvatarFile)
                    Divider()
                    NicknameRow(value = userName, onCommit = onUserName)
                    Divider()
                    SettingLabel(
                        stringResource(R.string.settings_image_processing_title),
                        stringResource(R.string.settings_image_processing_subtitle),
                    )
                }
                }

                SettingsPage.MESSAGES -> {
                Card {
                    SettingLabel(
                        stringResource(R.string.settings_assistant_collapse_title),
                        stringResource(R.string.settings_assistant_collapse_subtitle),
                    )
                    ChipRow {
                        io.qwenpaw.node.ui.chat.AssistantCollapse.entries.forEach { mode ->
                            Chip(
                                text = when (mode) {
                                    io.qwenpaw.node.ui.chat.AssistantCollapse.EXPANDED ->
                                        stringResource(R.string.settings_collapse_expanded)
                                    io.qwenpaw.node.ui.chat.AssistantCollapse.PROCESS_COLLAPSED ->
                                        stringResource(R.string.settings_collapse_process)
                                    io.qwenpaw.node.ui.chat.AssistantCollapse.RESULT_COLLAPSED ->
                                        stringResource(R.string.settings_collapse_result)
                                },
                                selected = assistantCollapse == mode,
                                onClick = { onAssistantCollapse(mode) },
                            )
                        }
                    }
                    Divider()
                    SwitchRow(
                        title = stringResource(R.string.settings_show_stats_title),
                        subtitle = stringResource(R.string.settings_show_stats_subtitle),
                        checked = showMessageStats,
                        onCheckedChange = onShowMessageStats,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(R.string.settings_render_user_markdown_title),
                        subtitle = stringResource(R.string.settings_render_markdown_subtitle),
                        checked = renderUserMarkdown,
                        onCheckedChange = onRenderUserMarkdown,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(R.string.settings_render_agent_markdown_title),
                        subtitle = stringResource(R.string.settings_render_markdown_subtitle),
                        checked = renderAssistantMarkdown,
                        onCheckedChange = onRenderAssistantMarkdown,
                    )
                    Divider()
                    SettingLabel(
                        stringResource(R.string.settings_action_row_title),
                        stringResource(R.string.settings_action_row_subtitle),
                    )
                    ChipRow {
                        io.qwenpaw.node.ui.chat.ActionStyle.entries.forEach { style ->
                            Chip(
                                text = when (style) {
                                    io.qwenpaw.node.ui.chat.ActionStyle.TEXT ->
                                        stringResource(R.string.settings_action_style_text)
                                    io.qwenpaw.node.ui.chat.ActionStyle.ICON ->
                                        stringResource(R.string.settings_action_style_icon)
                                },
                                selected = actionStyle == style,
                                onClick = { onActionStyle(style) },
                            )
                        }
                    }
                }
                }

                SettingsPage.BEHAVIOR -> {
                Card {
                    SwitchRow(
                        title = stringResource(R.string.settings_compact_list_title),
                        subtitle = stringResource(R.string.settings_compact_list_subtitle),
                        checked = compactList,
                        onCheckedChange = onCompactList,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(R.string.settings_keep_screen_on_title),
                        subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
                        checked = keepScreenOn,
                        onCheckedChange = onKeepScreenOn,
                    )
                }
                }

                SettingsPage.VOICE -> {
                    Card {
                        SwitchRow(
                            title = stringResource(R.string.settings_voice_enabled_title),
                            subtitle = stringResource(R.string.settings_voice_enabled_subtitle),
                            checked = voiceEnabled,
                            onCheckedChange = onVoiceEnabled,
                        )
                        // 转写来源只在带端上引擎的包里有得选。
                        // `_no-sherpa` 包没有本地识别，给出一个用不了的选项只会误导。
                        if (localAsrAvailable) {
                            Divider()
                            Text(
                                text = stringResource(R.string.settings_voice_source_title),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
                            )
                            ChipRow {
                                io.qwenpaw.node.ui.VoiceSource.entries.forEach { source ->
                                    Chip(
                                        text = when (source) {
                                            io.qwenpaw.node.ui.VoiceSource.LOCAL ->
                                                stringResource(R.string.settings_voice_source_local)
                                            io.qwenpaw.node.ui.VoiceSource.SERVER ->
                                                stringResource(R.string.settings_voice_source_server)
                                        },
                                        selected = voiceSource == source,
                                        onClick = { onVoiceSource(source) },
                                    )
                                }
                            }
                            Text(
                                text = when (voiceSource) {
                                    io.qwenpaw.node.ui.VoiceSource.LOCAL ->
                                        stringResource(R.string.settings_voice_source_local_hint)
                                    io.qwenpaw.node.ui.VoiceSource.SERVER ->
                                        stringResource(R.string.settings_voice_source_server_hint)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            )
                        }
                        Divider()
                        Text(
                            text = stringResource(R.string.settings_voice_trigger_title),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(
                                start = 14.dp,
                                end = 14.dp,
                                top = 12.dp,
                                bottom = 8.dp,
                            ),
                        )
                        ChipRow {
                            VoiceTrigger.entries.forEach { trigger ->
                                Chip(
                                    text = when (trigger) {
                                        VoiceTrigger.TAP ->
                                            stringResource(R.string.settings_voice_trigger_tap)
                                        VoiceTrigger.HOLD ->
                                            stringResource(R.string.settings_voice_trigger_hold)
                                    },
                                    selected = voiceTrigger == trigger,
                                    onClick = { onVoiceTrigger(trigger) },
                                )
                            }
                        }
                        // 自动停止只在「本地识别 + 点一下」下有意义：
                        // 按住说话本来就是松手即停，服务端转写则是整段上传。
                        if (localAsrAvailable &&
                            voiceSource == io.qwenpaw.node.ui.VoiceSource.LOCAL &&
                            voiceTrigger == VoiceTrigger.TAP
                        ) {
                            Divider()
                            SwitchRow(
                                title = stringResource(R.string.settings_voice_autostop_title),
                                subtitle = stringResource(R.string.settings_voice_autostop_subtitle),
                                checked = voiceAutoStop,
                                onCheckedChange = onVoiceAutoStop,
                            )
                        }
                        if (localAsrAvailable && voiceSource == io.qwenpaw.node.ui.VoiceSource.LOCAL) {
                            Divider()
                            ModelRow(
                                state = localModel,
                                onDownload = onDownloadModel,
                                onRemove = onRemoveModel,
                            )
                        }
                        // 服务端转写这一行只在真的走服务端时才有意义。
                        if (!localAsrAvailable ||
                            voiceSource == io.qwenpaw.node.ui.VoiceSource.SERVER
                        ) {
                            Divider()
                            TranscribeRow(
                                status = transcribeStatus,
                                onRefresh = onRefreshTranscribe,
                            )
                        }
                    }
                }

                SettingsPage.LOGS -> {
                    Card {
                        SwitchRow(
                            title = stringResource(R.string.settings_log_enabled_title),
                            subtitle = stringResource(R.string.settings_log_enabled_subtitle),
                            checked = logEnabled,
                            onCheckedChange = onLogEnabled,
                        )
                        Divider()
                        SettingLabel(
                            stringResource(R.string.settings_log_kinds_title),
                            stringResource(R.string.settings_log_kinds_subtitle),
                        )
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
                            title = stringResource(R.string.settings_log_view_title),
                            subtitle = stringResource(R.string.settings_log_view_subtitle),
                            onClick = onOpenLogs,
                        )
                        Divider()
                        LinkRow(
                            title = stringResource(R.string.settings_log_share_title),
                            subtitle = stringResource(R.string.settings_log_share_subtitle),
                            action = stringResource(R.string.settings_action_share),
                            onClick = onExportLogs,
                        )
                    }
                }

                SettingsPage.TOOLS -> {
                    config?.let { c ->
                        ToolsSection(
                            tools = c.tools.tools,
                            loading = c.tools.loading,
                            error = c.tools.error,
                            onToggle = c.onToggleTool,
                            onAsync = c.onToolAsync,
                            onSaveConfig = c.onSaveToolConfig,
                        )
                    }
                }

                SettingsPage.ENVIRONMENTS -> {
                    config?.let { c ->
                        EnvironmentsSection(
                            vars = c.envs.vars,
                            catalog = c.envs.catalog,
                            loading = c.envs.loading,
                            error = c.envs.error,
                            onSave = c.onSaveEnvs,
                            onSaveAuthEnabled = c.onSaveAuthEnabled,
                            onDelete = c.onDeleteEnv,
                            onReset = c.onResetEnv,
                        )
                    }
                }

                SettingsPage.OFFLOAD -> {
                    config?.let { c ->
                        OffloadPolicySection(
                            action = c.offload.action,
                            loading = c.offload.loading,
                            error = c.offload.error,
                            onAction = c.onSetOffload,
                        )
                    }
                }

                SettingsPage.CRON -> {
                    config?.let { c ->
                        CronSection(
                            state = c.cron,
                            onToggle = c.onToggleCron,
                            onRun = c.onRunCron,
                            onPromote = c.onPromoteCron,
                            onDelete = c.onDeleteCron,
                            onLoadHistory = c.onLoadCronHistory,
                            dismissRowError = c.dismissCronRowError,
                            dismissError = c.dismissCronError,
                            onNew = {
                                c.onEditCron(null)
                                section = SettingsPage.CRON_EDIT
                            },
                            onEdit = {
                                c.onEditCron(it)
                                section = SettingsPage.CRON_EDIT
                            },
                        )
                    }
                }

                SettingsPage.CRON_EDIT -> {
                    config?.let { c ->
                        CronEditorSection(
                            initial = c.cron.draft,
                            targets = c.cron.targets,
                            targetChannels = c.cron.targetChannels,
                            chatNames = c.cron.chatNames,
                            serverTimezone = c.cron.serverTimezone,
                            saving = c.cron.saving,
                            error = c.cron.error,
                            onLoadTargets = c.onLoadCronTargets,
                            onSave = { spec, originalId ->
                                c.onSaveCron(spec, originalId) { ok ->
                                    if (ok) section = SettingsPage.CRON
                                }
                            },
                        )
                    }
                }

                SettingsPage.ABOUT -> {
                // 检查更新放在最前：用户点进「关于」多半就是为了这一件事。
                Card {
                    LinkRow(
                        title = stringResource(R.string.settings_update_check_title),
                        subtitle = stringResource(R.string.settings_update_check_subtitle),
                        action = stringResource(R.string.settings_update_check_action),
                        onClick = onCheckUpdate,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(R.string.settings_update_auto_title),
                        subtitle = stringResource(R.string.settings_update_auto_subtitle),
                        checked = autoUpdateCheck,
                        onCheckedChange = onAutoUpdateCheck,
                    )
                }
                Spacer(Modifier.height(8.dp))
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
                            text = stringResource(R.string.settings_about_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Divider()
                    LinkRow(
                        title = stringResource(R.string.settings_about_repo_title),
                        subtitle = io.qwenpaw.node.BuildConfig.REPO_URL,
                        action = stringResource(R.string.settings_action_visit),
                        onClick = { confirmBrowser(io.qwenpaw.node.BuildConfig.REPO_URL) },
                    )
                    Divider()
                    LinkRow(
                        title = stringResource(R.string.settings_about_bridge_repo_title),
                        subtitle = io.qwenpaw.node.BuildConfig.BRIDGE_REPO_URL,
                        action = stringResource(R.string.settings_action_visit),
                        onClick = { confirmBrowser(io.qwenpaw.node.BuildConfig.BRIDGE_REPO_URL) },
                    )
                    Divider()
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.settings_about_credits_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(6.dp))
                        CreditLine(
                            name = "agentscope-ai/QwenPaw",
                            url = "https://github.com/agentscope-ai/QwenPaw",
                            detail = stringResource(R.string.settings_about_credit_qwenpaw),
                        )
                        CreditLine(
                            name = "openclaw/openclaw",
                            url = "https://github.com/openclaw/openclaw",
                            detail = stringResource(R.string.settings_about_credit_openclaw),
                        )
                        CreditLine(
                            name = "termux/termux-app",
                            url = "https://github.com/termux/termux-app",
                            detail = stringResource(R.string.settings_about_credit_termux),
                        )
                        CreditLine(
                            name = "k2-fsa/sherpa-onnx",
                            url = "https://github.com/k2-fsa/sherpa-onnx",
                            detail = stringResource(R.string.settings_about_credit_sherpa),
                        )
                        CreditLine(
                            name = "adivenxnataly/MiSans",
                            url = "https://github.com/adivenxnataly/MiSans",
                            detail = stringResource(R.string.settings_about_credit_misans),
                        )
                        CreditLine(
                            name = "coil-kt/coil",
                            url = "https://github.com/coil-kt/coil",
                            detail = stringResource(R.string.settings_about_credit_coil),
                        )
                        Spacer(Modifier.height(10.dp))
                        // 结尾这句不挂链接：它不是某一项资源，硬塞一个地址会误导点击。
                        Text(
                            text = stringResource(R.string.settings_about_credits_footer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                }
                // 子页之外的枚举值不会出现在这里：添加与编辑已被外层 when 接走。
                else -> Unit
                }
            }
        }
    }
    }
    }
}

/**
 * 设置页内的层级。
 *
 * 顺序即深度，子页一律排在 MAIN 之后：`AnimatedContent` 用它判断这次切换
 * 是「深入」还是「返回」，从而决定滑动方向。ADD_* 与 EDIT 是实例表单，
 * 深度最大，排在最后。
 */
internal enum class SettingsPage {
    MAIN,
    SERVER,
    /** 服务端配置的二级菜单：模型、工具、访问与运维。 */
    SERVER_CONFIG,
    /** 受信任的连接：命中这些主机的地址不再弹安全提示。 */
    TRUSTED,
    PROFILE,
    APPEARANCE,
    /** 聊天字号预览页，外观页的下一级。 */
    APPEARANCE_PREVIEW,
    MESSAGES,
    VOICE,
    BEHAVIOR,
    LOGS,
    ABOUT,
    ADD_MODE,
    ADD_FORM,
    ADD_WIZARD,
    EDIT,
    TOOLS,
    ENVIRONMENTS,
    OFFLOAD,
    CRON,
    CRON_EDIT,
}

/**
 * 交给 `AnimatedContent` 的导航状态。
 *
 * 把页与它依赖的数据包在一起，是为了让正在退出的旧槽位保留自己那一帧的数据。
 * 只传枚举时，旧槽位会读外部状态（已被点击改掉），于是先瞬切、再播一遍动画。
 */
private data class SettingsNav(
    val page: SettingsPage,
    val addMode: AddMode? = null,
    val editing: InstanceConfig? = null,
)

/** 一级收藏项；只收服务端配置组里的入口。 */
private data class FavoriteEntry(
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
)

private val FAVORITE_ORDER =
    listOf("models", "agents", "runtime", "envs", "cron", "market", "acl", "security")

private val FAVORITE_ENTRIES: Map<String, FavoriteEntry> = mapOf(
    "models" to FavoriteEntry(
        R.string.settings_nav_models,
        R.string.settings_nav_models_subtitle,
        QwenIcons.zap,
    ),
    "agents" to FavoriteEntry(
        R.string.settings_nav_agents,
        R.string.settings_nav_agents_subtitle,
        QwenIcons.sparkles,
    ),
    "runtime" to FavoriteEntry(
        R.string.settings_nav_runtime,
        R.string.settings_nav_runtime_subtitle,
        QwenIcons.workflow,
    ),
    "envs" to FavoriteEntry(
        R.string.settings_nav_envs,
        R.string.settings_nav_envs_subtitle,
        QwenIcons.zap,
    ),
    "cron" to FavoriteEntry(
        R.string.settings_nav_cron,
        R.string.settings_nav_cron_subtitle,
        QwenIcons.calendarClock,
    ),
    "market" to FavoriteEntry(
        R.string.settings_nav_market,
        R.string.settings_nav_market_subtitle,
        QwenIcons.library,
    ),
    "acl" to FavoriteEntry(
        R.string.acl_title,
        R.string.acl_entry_subtitle,
        QwenIcons.shieldCheck,
    ),
    "security" to FavoriteEntry(
        R.string.settings_nav_security,
        R.string.settings_nav_security_subtitle,
        QwenIcons.shieldCheck,
    ),
)

/** 子页标题。与一级列表的入口文案保持一致，避免同一个页有两个名字。 */
private fun sectionTitle(section: SettingsPage): String = when (section) {
    SettingsPage.SERVER -> Texts.get(R.string.settings_nav_server)
    SettingsPage.SERVER_CONFIG -> Texts.get(R.string.settings_section_server_config)
    SettingsPage.TRUSTED -> Texts.get(R.string.settings_nav_trusted)
    SettingsPage.PROFILE -> Texts.get(R.string.settings_nav_profile)
    SettingsPage.APPEARANCE -> Texts.get(R.string.settings_nav_appearance)
    SettingsPage.APPEARANCE_PREVIEW -> Texts.get(R.string.settings_section_font_preview)
    SettingsPage.MESSAGES -> Texts.get(R.string.settings_nav_messages)
    SettingsPage.VOICE -> Texts.get(R.string.settings_nav_voice)
    SettingsPage.BEHAVIOR -> Texts.get(R.string.settings_nav_behavior)
    SettingsPage.LOGS -> Texts.get(R.string.settings_nav_logs)
    SettingsPage.ABOUT -> Texts.get(R.string.settings_nav_about)
    SettingsPage.TOOLS -> Texts.get(R.string.settings_nav_tools)
    SettingsPage.ENVIRONMENTS -> Texts.get(R.string.settings_nav_envs)
    SettingsPage.OFFLOAD -> Texts.get(R.string.settings_nav_offload)
    SettingsPage.CRON -> Texts.get(R.string.settings_nav_cron)
    SettingsPage.CRON_EDIT -> Texts.get(R.string.settings_section_cron_edit)
    else -> Texts.get(R.string.settings_title)
}

/**
 * 三张配置表单的状态与动作。
 *
 * 打包成一个参数而不是十几个回调：三者都挂在同一个 ViewModel 上，
 * 生命周期与加载时机一致，拆开只会让调用方多写十几行转发。
 */
data class SettingsConfig(
    val tools: ToolsUiState = ToolsUiState(),
    val envs: EnvsUiState = EnvsUiState(),
    val offload: OffloadUiState = OffloadUiState(),
    val runningConfig: RunningConfigUiState = RunningConfigUiState(),
    val security: SecurityUiState = SecurityUiState(),
    val serverSettings: ServerSettingsUiState = ServerSettingsUiState(),
    val onLoadRunningConfig: () -> Unit = {},
    val onLoadSecurity: () -> Unit = {},
    val onLoadServerSettings: () -> Unit = {},
    val onSetServerLanguage: (String) -> Unit = {},
    val onLoadTools: () -> Unit = {},
    val onToggleTool: (String) -> Unit = {},
    val onToolAsync: (String, Boolean) -> Unit = { _, _ -> },
    val onSaveToolConfig: (String, Map<String, String>) -> Unit = { _, _ -> },
    val onLoadEnvs: () -> Unit = {},
    val onSaveEnvs: (Map<String, String>) -> Unit = {},
    val onSaveAuthEnabled: (String) -> Unit = {},
    val onDeleteEnv: (String) -> Unit = {},
    val onResetEnv: (String) -> Unit = {},
    val onLoadOffload: () -> Unit = {},
    val onSetOffload: (String) -> Unit = {},
    val cron: CronUiState = CronUiState(),
    val onLoadCron: () -> Unit = {},
    val onToggleCron: (String, Boolean) -> Unit = { _, _ -> },
    val onRunCron: (String) -> Unit = {},
    val onPromoteCron: (String) -> Unit = {},
    val onDeleteCron: (String) -> Unit = {},
    val onLoadCronHistory: (String) -> Unit = {},
    val dismissCronRowError: (String) -> Unit = {},
    val dismissCronError: () -> Unit = {},
    val onLoadCronTargets: () -> Unit = {},
    /** 打开编辑器：传 null 表示新建，传任务表示编辑。 */
    val onEditCron: (io.qwenpaw.node.data.model.CronJobSpecDto?) -> Unit = {},
    /** 保存。[onResult] 返回是否成功，失败时留在表单里显示错误。 */
    val onSaveCron: (
        spec: io.qwenpaw.node.data.model.CronJobSpecDto,
        originalId: String?,
        onResult: (Boolean) -> Unit,
    ) -> Unit = { _, _, _ -> },
)

/** 一级列表「服务器」行的副标题：当前实例名与地址，没有实例时如实说明。 */
private fun serverSummary(instances: List<InstanceConfig>, activeId: String?): String {
    val active = instances.firstOrNull { it.id == activeId } ?: instances.firstOrNull()
        ?: return Texts.get(R.string.settings_server_summary_empty)
    val host = active.addresses.firstOrNull()?.url?.let { compactUrl(it) }
        ?: Texts.get(R.string.settings_server_summary_no_address)
    // 名称留空时连接流程会用地址当名称，两者相同就只说一次。
    val name = active.name.ifBlank { "" }
    return if (name.isEmpty() || name == host) {
        host
    } else {
        Texts.get(R.string.settings_server_summary_named, name, host)
    }
}

@Composable
internal fun SettingsTopBar(onBack: () -> Unit, subtitle: String) {
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
                    imageVector = QwenIcons.arrowLeft,
                    contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
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
    state: io.qwenpaw.node.voice.LocalModelState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_asr_model_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = when (state) {
                        // 模型是本地识别的前提，不是可选增强。
                        io.qwenpaw.node.voice.LocalModelState.NotInstalled ->
                            stringResource(R.string.settings_asr_model_not_installed)
                        io.qwenpaw.node.voice.LocalModelState.Ready ->
                            stringResource(R.string.settings_asr_model_ready)
                        is io.qwenpaw.node.voice.LocalModelState.Downloading ->
                            stringResource(
                                R.string.settings_asr_model_downloading,
                                (state.progress * 100).toInt(),
                            )
                        io.qwenpaw.node.voice.LocalModelState.Installing ->
                            stringResource(R.string.settings_asr_model_installing)
                        is io.qwenpaw.node.voice.LocalModelState.Failed -> state.message
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state is io.qwenpaw.node.voice.LocalModelState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            when (state) {
                io.qwenpaw.node.voice.LocalModelState.NotInstalled ->
                    MiniButton(stringResource(R.string.settings_action_download), onDownload)
                is io.qwenpaw.node.voice.LocalModelState.Failed ->
                    MiniButton(stringResource(R.string.action_retry), onDownload)
                io.qwenpaw.node.voice.LocalModelState.Ready ->
                    MiniButton(stringResource(R.string.action_delete), onRemove, danger = true)
                else -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (state is io.qwenpaw.node.voice.LocalModelState.Downloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_asr_model_source),
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
    }
}

@Composable
private fun TranscribeRow(
    status: io.qwenpaw.node.data.transcription.TranscriptionStatus,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_voice_source_server),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.canRecord) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            // 配置在服务端改，不会自动通知；提供重探入口，改完不必重启应用。
            MiniButton(stringResource(R.string.settings_asr_transcribe_refresh), onRefresh)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_transcribe_hint),
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
    }
}

@Composable
internal fun SectionHeader(title: String, trailing: String? = null) {
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
internal fun Card(content: @Composable () -> Unit) {
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

/**
 * 一级列表的入口行：图标 + 标题 + 当前状态 + 箭头。
 *
 * 副标题显示的是**当前值**而不是固定说明（「跟随系统 · 字号 100%」），
 * 这样不必点进子页就能确认状态；这也是把它与普通设置行区分开的地方。
 */
@Composable
internal fun NavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    /** 行尾附加控件，例如收藏按钮；不传时只显示箭头。 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        Spacer(Modifier.width(8.dp))
        // 箭头是装饰：整行都可点，读屏不需要再念一遍「进入」。
        Icon(
            imageVector = QwenIcons.chevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FavoriteStar(favorite: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = if (favorite) {
                stringResource(R.string.settings_fav_on)
            } else {
                stringResource(R.string.settings_fav_off)
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
internal fun Divider() {
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
    /** 退出登录：吊销服务端 token 并清本地凭据。 */
    onLogout: () -> Unit = {},
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
                // 带「地址：」前缀是必须的：不带前缀时这一行紧接在实例名下方，
                // 字号又小一号，读起来就是「实例名的别名」，而不是「这台服务器连在哪」。
                val addressAlias = instance.addresses.firstOrNull { it.url == shown }?.alias
                val addressLine = if (addressAlias.isNullOrBlank()) {
                    stringResource(R.string.settings_instance_address, compactUrl(shown))
                } else {
                    stringResource(
                        R.string.settings_instance_address_with_alias,
                        addressAlias,
                        compactUrl(shown),
                    )
                }
                val backupSuffix = if (instance.backupCount > 0) {
                    pluralStringResource(
                        R.plurals.settings_instance_backup_suffix,
                        instance.backupCount,
                        instance.backupCount,
                    )
                } else {
                    ""
                }
                // 「临时」与「已保存」要分清楚：前者重启即失效，后者长期有效。
                val pickMarker = when {
                    sessionPick != null && sessionPick == shown ->
                        stringResource(R.string.settings_instance_address_marker_session)
                    chosen != null && chosen == shown ->
                        stringResource(R.string.settings_instance_address_marker_saved)
                    else -> ""
                }
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
                                text = stringResource(R.string.settings_instance_current),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = addressLine + backupSuffix + pickMarker,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 当前智能体单独一行。此前只能进「选择智能体」对话框才知道连的是谁，
                // 而多智能体实例下不同智能体的对话互不可见，串号时用户没有可对照的线索。
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_instance_agent_line, instance.agentId),
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
                            text = stringResource(
                                R.string.settings_instance_agent_chip,
                                instance.agentId,
                            ),
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
                                text = if (pickingAddress) {
                                    stringResource(R.string.settings_instance_address_collapse)
                                } else {
                                    stringResource(R.string.settings_instance_address_choose)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    instance.serverVersion?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_version, it),
                            style = MaterialTheme.typography.labelSmall,
                            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (active) {
                MiniButton(stringResource(R.string.action_edit), onClick = onEdit)
            } else {
                MiniButton(stringResource(R.string.settings_action_switch), onClick = onSwitch)
                Spacer(Modifier.width(6.dp))
                MiniButton(
                    text = stringResource(R.string.action_remove),
                    onClick = onRemove,
                    danger = true,
                )
            }
        }
        // 「退出登录」只在实例确实持有 token 时出现：没有 token 的实例上执行
        // 吊销只会得到 403，把这个入口摆出来等于给用户一个点了没用的按钮。
        if (instance.token != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_instance_credentials_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                )
                Spacer(Modifier.weight(1f))
                MiniButton(
                    text = stringResource(R.string.settings_logout_title),
                    onClick = onLogout,
                    danger = true,
                )
            }
        }
        // 展开后的地址清单。
        //
        // 这里有两层语义，必须分开呈现，否则用户会以为「点一下就永久改了」：
        // - 「本次使用」只写内存，重启后回到自动；
        // - 长期偏好由编辑实例表单里的「使用时机」决定。
        if (pickingAddress) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                val sessionCurrent = sessionPick
                val sessionLabel = if (sessionCurrent.isNullOrBlank()) {
                    stringResource(R.string.settings_session_address_auto)
                } else {
                    instance.addresses.firstOrNull { it.url == sessionCurrent }?.label
                        ?: sessionCurrent
                }
                Text(
                    text = stringResource(R.string.settings_session_address_label, sessionLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(6.dp))
                AddressChoice(
                    label = stringResource(R.string.settings_instance_address_auto_ordered),
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
                    text = stringResource(R.string.settings_session_address_hint),
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
                imageVector = QwenIcons.check,
                contentDescription = stringResource(R.string.settings_address_selected_desc),
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
                    text = stringResource(R.string.settings_action_verify),
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
internal fun ActionRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = QwenIcons.plus,
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

/**
 * 受信任的连接：0.7.5 起视同私有网络，只有一层。
 *
 * [hosts] 与 [sshHosts] 在读取时是同一个并集；[sshHosts] 仅为兼容旧记录保留。
 * 新增地址的确认框同时写两个键，命中后既不再提示，也不再限制 SSH 凭据。
 */
@Composable
private fun TrustedHostsPage(
    hosts: Set<String>,
    sshHosts: Set<String>,
    onAddForSsh: (String) -> Boolean,
    onConfirmSshTrust: (String) -> Boolean,
    onRemove: (String) -> Unit,
    securityHintsEnabled: Boolean,
    onSecurityHintsEnabled: (Boolean) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var confirmDisableHints by remember { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<String?>(null) }
    var pendingSsh by remember { mutableStateOf<String?>(null) }
    var ownershipConfirmed by remember { mutableStateOf(false) }
    val recognized = io.qwenpaw.node.data.normalizeTrustedHost(input) != null
    val errorText = if (input.isNotBlank() && !recognized) {
        stringResource(R.string.settings_trusted_invalid_host)
    } else {
        null
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    fun closeConfirm() {
        pendingAdd = null
        pendingSsh = null
        ownershipConfirmed = false
    }

    Column {
        Card {
            SwitchRow(
                title = stringResource(R.string.settings_security_hints_title),
                subtitle = stringResource(R.string.settings_security_hints_subtitle),
                checked = securityHintsEnabled,
                onCheckedChange = { want ->
                    if (want) onSecurityHintsEnabled(true) else confirmDisableHints = true
                },
            )
        }
        Text(
            text = stringResource(R.string.settings_trusted_page_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(stringResource(R.string.settings_trusted_input_placeholder))
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_add),
                style = MaterialTheme.typography.labelLarge,
                color = if (recognized) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (recognized) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
                        },
                    )
                    .clickable(enabled = recognized) {
                        pendingAdd = input
                        ownershipConfirmed = false
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        SectionHeader(
            stringResource(R.string.settings_section_trusted_added),
            pluralStringResource(
                R.plurals.settings_item_count_summary,
                hosts.size,
                hosts.size,
            ),
        )
        Card {
            val sorted = hosts.sorted()
            if (sorted.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_trusted_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                )
            } else {
                sorted.forEachIndexed { index, host ->
                    val sshAllowed = io.qwenpaw.node.data.isTrustedHost(host, sshHosts)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = host,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (sshAllowed) {
                                    stringResource(R.string.settings_trusted_ssh_allowed)
                                } else {
                                    stringResource(R.string.settings_trusted_ssh_pending)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sshAllowed) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                },
                            )
                        }
                        if (!sshAllowed) {
                            Text(
                                text = stringResource(R.string.settings_trusted_confirm_ssh),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        pendingSsh = host
                                        ownershipConfirmed = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_remove),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onRemove(host) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    if (index != sorted.lastIndex) Divider()
                }
            }
        }
    }

    if (confirmDisableHints) {
        AlertDialog(
            onDismissRequest = { confirmDisableHints = false },
            title = { Text(stringResource(R.string.settings_security_hints_off_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_security_hints_off_message))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_security_hints_off_warning),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_security_hints_off_ssh_note))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisableHints = false
                    onSecurityHintsEnabled(false)
                }) { Text(stringResource(R.string.settings_security_hints_off_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisableHints = false }) {
                    Text(stringResource(R.string.settings_security_hints_keep))
                }
            },
        )
    }

    val target = pendingAdd ?: pendingSsh
    if (target != null) {
        val isNew = pendingAdd != null
        AlertDialog(
            onDismissRequest = { closeConfirm() },
            title = {
                Text(
                    if (isNew) {
                        stringResource(R.string.settings_trusted_add_title)
                    } else {
                        stringResource(R.string.settings_trusted_ssh_title)
                    },
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_trusted_confirm_message, target),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isNew) {
                            stringResource(R.string.settings_trusted_add_body)
                        } else {
                            stringResource(R.string.settings_trusted_ssh_body)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_trusted_risk),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = ownershipConfirmed,
                            onCheckedChange = { ownershipConfirmed = it },
                        )
                        Text(stringResource(R.string.settings_trusted_ownership))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = ownershipConfirmed,
                    onClick = {
                        val accepted = if (isNew) {
                            onAddForSsh(target)
                        } else {
                            onConfirmSshTrust(target)
                        }
                        if (accepted && isNew) {
                            input = ""
                            focus.clearFocus()
                            keyboard?.hide()
                        }
                        closeConfirm()
                    },
                ) {
                    Text(
                        if (isNew) {
                            stringResource(R.string.action_add)
                        } else {
                            stringResource(R.string.settings_action_confirm)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { closeConfirm() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
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
internal fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
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
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
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
    /** 非空时在「重置」左侧多一个「预览」入口，目前只有聊天字号用到。 */
    onPreview: (() -> Unit)? = null,
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
            if (onPreview != null) {
                Text(
                    text = stringResource(R.string.settings_action_preview),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onPreview)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_action_reset),
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
    action: String = stringResource(R.string.settings_action_view),
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

/**
 * 一条第三方资源。整行可点，跳转到 [url]。
 *
 * 显示的是 `仓库主人/仓库名称` 而不是项目的商品名：用户要判断「这条是谁的代码」，
 * 仓库路径比「MiSans」这种名字更能说明来源，点进去也必然落在真实存在的地址上。
 */
@Composable
private fun CreditLine(name: String, url: String, detail: String) {
    val confirmBrowser = io.qwenpaw.node.ui.common.rememberBrowserConfirm()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { confirmBrowser(url) }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
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

@Composable
internal fun SwitchRow(
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
            title = stringResource(R.string.settings_add_mode_form_title),
            subtitle = stringResource(R.string.settings_add_mode_form_subtitle),
            onClick = onPickFill,
        )
        Spacer(Modifier.height(10.dp))
        ModeCard(
            title = stringResource(R.string.settings_add_mode_wizard_title),
            subtitle = stringResource(R.string.settings_add_mode_wizard_subtitle),
            onClick = onPickWizard,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_add_mode_ssh_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val fallbackName = if (kind == io.qwenpaw.node.avatar.AvatarStore.KIND_USER) {
        stringResource(R.string.settings_avatar_me)
    } else {
        stringResource(R.string.settings_avatar_agent)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.qwenpaw.node.ui.chat.ChatAvatar(
            store = store,
            kind = kind,
            name = fallbackName,
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
        MiniButton(
            text = if (hasAvatar) {
                stringResource(R.string.settings_avatar_change)
            } else {
                stringResource(R.string.settings_avatar_choose)
            },
            onClick = { onPick(kind) },
        )
        if (hasAvatar) {
            Spacer(Modifier.width(6.dp))
            MiniButton(
                text = stringResource(R.string.action_remove),
                onClick = { onRemove(kind) },
                danger = true,
            )
        }
    }
}

/**
 * 服务端头像的只读预览行。
 *
 * 不提供「从服务端移除」：这是网页端的设置，客户端删掉会让用户以为需要在
 * 两边各删一次。这里只如实显示当前取到的结果。
 */
@Composable
private fun ServerAvatarRow(file: java.io.File?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.qwenpaw.node.ui.chat.ChatAvatar(
            store = null,
            kind = io.qwenpaw.node.avatar.AvatarStore.KIND_USER,
            name = stringResource(R.string.settings_avatar_me),
            size = 40.dp,
            serverImage = file,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_server_avatar_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (file == null) {
                    stringResource(R.string.settings_server_avatar_none)
                } else {
                    stringResource(R.string.settings_server_avatar_loaded)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Text(
            text = stringResource(R.string.settings_nickname_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_nickname_subtitle),
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
                Text(
                    text = stringResource(R.string.settings_font_package_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = when (state) {
                        FontState.NotInstalled ->
                            stringResource(R.string.settings_font_not_installed)
                        FontState.Ready -> stringResource(R.string.settings_font_ready)
                        is FontState.Downloading -> stringResource(
                            R.string.settings_font_downloading,
                            (state.progress * 100).toInt(),
                        )
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
                FontState.NotInstalled ->
                    MiniButton(stringResource(R.string.settings_action_download), onDownload)
                is FontState.Failed -> MiniButton(stringResource(R.string.action_retry), onDownload)
                FontState.Ready ->
                    MiniButton(stringResource(R.string.action_delete), onRemove, danger = true)
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
            text = stringResource(
                R.string.settings_font_note,
                io.qwenpaw.node.font.FontManager.SOURCE_REPO,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
    }
}


