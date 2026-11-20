package io.qwenpaw.node

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.qwenpaw.node.R
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.AppVisibility
import io.qwenpaw.node.ui.AppViewModel
import io.qwenpaw.node.ui.chat.ChatListScreen
import io.qwenpaw.node.ui.chat.ChatScreen
import io.qwenpaw.node.ui.chat.ChatUiState
import io.qwenpaw.node.ui.chat.SearchScreen
import io.qwenpaw.node.ui.i18n.AppLanguage
import io.qwenpaw.node.ui.i18n.AppLocale
import io.qwenpaw.node.ui.i18n.LanguageGateScreen
import io.qwenpaw.node.ui.i18n.Texts
import io.qwenpaw.node.ui.settings.AgentPickerDialog
import io.qwenpaw.node.ui.settings.ConnectScreen
import io.qwenpaw.node.ui.settings.SettingsScreen
import io.qwenpaw.node.ui.theme.QwenPawTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
    }

    override fun onStop() {
        AppVisibility.onStop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程可能从旧语言启动，Activity 重建时这里刷新一次，存活的 ViewModel 才能取到新语言。
        Texts.install(this)
        handleTerminalIntent(intent)
        val app = application as QwenPawApp
        setContent {
            val viewModel: AppViewModel = viewModel(
                factory = AppViewModel.factory(this, app),
            )
            val appState by viewModel.state.collectAsState()
            val instances by viewModel.instances.collectAsState()
            val activeId by viewModel.activeId.collectAsState()
            val chatVm = viewModel.chat()
            val chatState by chatVm.state.collectAsState()
            // MiSans 是运行时下载的；只有「已下载 + 用户开启」才拿到字体族。
            val misans by viewModel.miSansFamily.collectAsState()

            // 生成回复时按设置保持屏幕常亮。
            val view = androidx.compose.ui.platform.LocalView.current
            LaunchedEffect(chatState.running, appState.keepScreenOn) {
                view.keepScreenOn = appState.keepScreenOn && chatState.running
            }

            QwenPawTheme(
                mode = appState.themeMode,
                uiScale = appState.uiScale,
                chatScale = appState.chatScale,
                misans = misans.takeIf { appState.useMiSans },
            ) {
                if (appState.appLanguage == null) {
                    // 全新安装的首屏：先选语言，再进入连接或聊天流程。
                    LanguageGateScreen(
                        onConfirm = { language ->
                            viewModel.setAppLanguage(language)
                            Texts.install(this@MainActivity)
                        },
                    )
                } else {
                    QwenPawNav(
                        appState = appState,
                        chatState = chatState,
                        instances = instances,
                        activeId = activeId,
                        viewModel = viewModel,
                        chatVm = chatVm,
                        app = app,
                    )
                }
            }

            // 通知权限（Android 13+），用于后台回复完成提示。
            // 语言未选定前不申请：系统弹窗会盖在语言选择页上。
            if (appState.appLanguage != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTerminalIntent(intent)
    }

    private fun handleTerminalIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_TERMINAL) return
        io.qwenpaw.node.notify.TerminalSessionNotifier.requestOpen()
        intent.action = null
    }

    companion object {
        const val ACTION_OPEN_TERMINAL = "io.qwenpaw.node.action.OPEN_TERMINAL"
    }
}

/** 配套插件的发布包地址；安装引导与 Harness 补装共用。 */
private const val BRIDGE_ZIP_URL =
    "https://github.com/csdeTR/QwenPaw-Node-Bridge/releases/latest/download/qwenpaw-node-bridge.zip"

// 枚举顺序即页面层级：聊天页是主页面，其余页面按深度排在其后，
// 用于 AnimatedContent 判断进入方向是「深入」还是「返回」。
private enum class Screen { CHAT, LIST, SETTINGS, ARCHIVED, TOKEN_USAGE, RUN_LOG, MESSAGE_SOURCE, INBOX, ACL, SEARCH, WORKSPACE, BACKUP, CHECKPOINT, CHECKPOINT_CHAT, MODELS, SKILLS, MCP, MARKET, RUNNING_CONFIG, SECURITY, AGENTS, RUNTIME, PORTABILITY, APP_CENTER, CHANNEL, LOCAL_MODELS, TERMINAL, GIT }

@Composable
private fun QwenPawNav(
    appState: io.qwenpaw.node.ui.AppState,
    chatState: ChatUiState,
    instances: List<io.qwenpaw.node.data.local.InstanceConfig>,
    activeId: String?,
    viewModel: AppViewModel,
    chatVm: io.qwenpaw.node.ui.chat.ChatViewModel,
    app: QwenPawApp,
) {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    // 终端可从设置、对话列表或通知进入；返回时回到进入前那一页。
    var terminalOrigin by remember { mutableStateOf(Screen.SETTINGS) }
    // 用量统计有两个入口（对话列表顶栏、设置页）。记住是谁打开的，
    // 返回时才回到进入前那一层——固定回列表会让「设置 → 用量 → 返回」跳过设置页。
    var usageOrigin by remember { mutableStateOf(Screen.LIST) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatVmState = chatState
    var pickingAgent by remember { mutableStateOf<io.qwenpaw.node.data.local.InstanceConfig?>(null) }
    // 原文页要显示的那条消息。与 Screen 一起构成页面状态，
    // 单独存一份而不是塞进 Screen 枚举的参数里，是为了让枚举本身保持无载荷。
    var sourceText by remember { mutableStateOf<String?>(null) }
    val lastChatId by viewModel.lastChatId.collectAsState()
    LaunchedEffect(Unit) {
        io.qwenpaw.node.notify.TerminalSessionNotifier.openRequests.collect {
            if (screen != Screen.TERMINAL) terminalOrigin = screen
            screen = Screen.TERMINAL
            io.qwenpaw.node.notify.TerminalSessionNotifier.consumeOpen()
        }
    }

    // 对话切换后记下来，下次启动恢复到这个对话。
    LaunchedEffect(chatVmState.currentChatId) {
        viewModel.rememberLastChat(chatVmState.currentChatId)
    }

    // ChatViewModel 的轻量提示（归档 / 删除 / 批量操作 / 新审批）。
    //
    // 这里必须有人订阅：notifications 是带缓冲的 SharedFlow，没有收集方时
    // tryEmit 的返回值被丢弃，用户点完归档只会看到对话消失、没有任何反馈。
    // 放在 Nav 层而不是某个页面里，是因为这些操作在列表、聊天页、已归档页都可能触发。
    LaunchedEffect(chatVm) {
        chatVm.notifications.collect { message -> toast(context, message) }
    }

    // 可撤销操作的提示条状态。放在 Nav 层与 notifications 同理：
    // 归档可能从列表页、聊天页、已归档页触发，三个页面都要能看到撤销入口。
    val undoController = remember { io.qwenpaw.node.ui.common.UndoController() }
    LaunchedEffect(chatVm) {
        chatVm.undoOffers.collect { offer -> undoController.offer(offer.message, offer.onUndo) }
    }

    // 当前实例对应的连接，图片加载与导出都要用。
    //
    // 必须走 resolvedBaseUrl 而不是 primaryBaseUrl：多地址实例下，
    // 实际可用的地址可能不是列表第一个，用第一个会让图片加载与导出
    // 连到一条已经不通的路上，表现为「聊天正常但图片一直转圈」。
    val connection = remember(instances, activeId) {
        instances.firstOrNull { it.id == activeId }?.let {
            io.qwenpaw.node.data.remote.Connection(
                app.instanceRepository.resolvedBaseUrl(it),
                it.token,
                it.agentId,
                it.basicAuth,
            )
        }
    }
    val imageLoader = remember { io.qwenpaw.node.ui.chat.ChatImageLoader() }
    // 设置页的 rememberSaveable 状态（当前分区、各分页的滚动位置）。
    // AnimatedContent 会销毁离开页面的组合；没有这个 holder，从设置进入
    // 工作区 / 备份 / 检查点 / 用量 / 模型再返回时，会回到一级列表顶部。
    val settingsStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val usageState by viewModel.usage.state.collectAsState()
    val modelsState by viewModel.models.state.collectAsState()
    val skillsState by viewModel.skills.state.collectAsState()
    val mcpState by viewModel.mcp.state.collectAsState()
    val aclState by viewModel.acl.state.collectAsState()
    val marketState by viewModel.market.state.collectAsState()
    val inboxState by viewModel.inbox.state.collectAsState()
    val channelState by viewModel.channels.state.collectAsState()
    val localModelsState by viewModel.localModels.state.collectAsState()
    // 全文检索状态单独一条流，与对话页状态无关。
    val searchState by chatVm.search.collectAsState()
    val inboxUnread = inboxState.unreadCount
    val toolsState by viewModel.settingsConfig.tools.collectAsState()
    val envsState by viewModel.settingsConfig.envs.collectAsState()
    val offloadState by viewModel.settingsConfig.offload.collectAsState()
    val runningConfigState by viewModel.settingsConfig.runningConfig.collectAsState()
    val securityState by viewModel.settingsConfig.security.collectAsState()
    val serverSettingsState by viewModel.settingsConfig.serverSettings.collectAsState()
    val agentsState by viewModel.agents.state.collectAsState()
    val acpState by viewModel.runtime.acp.collectAsState()
    val harnessState by viewModel.runtime.harness.collectAsState()
    val portabilityState by viewModel.portability.state.collectAsState()
    val appCenterState by viewModel.appCenter.state.collectAsState()
    val cronState by viewModel.cron.state.collectAsState()
    // 工作区文件的状态单独一条流，与设置页无关。
    val workspaceState by viewModel.workspace.state.collectAsState()
    val backupState by viewModel.backup.state.collectAsState()
    val checkpointState by viewModel.checkpoint.state.collectAsState()
    val fontState by viewModel.fontState.collectAsState()
    // 当前网络类型与 WiFi 名称：地址编辑区据此提示「这条现在会被优先使用」。
    val netSnapshot by app.networkMonitor.current.collectAsState()
    // 头像文件版本：设置里换完头像返回聊天页，靠它触发重读。
    val avatarVersion by app.avatarStore.version.collectAsState()
    // 对话图标版本：换完图标返回列表，行据此重新读图。
    val iconVersion by app.chatIconStore.version.collectAsState()
    // 服务端头像的本地缓存文件，冷启动直接读盘，联网后再刷新。
    val serverAvatarFile by viewModel.serverAvatarFile.collectAsState()

    val messageAppearance = io.qwenpaw.node.ui.chat.MessageAppearance(
        avatarStore = app.avatarStore,
        avatarVersion = avatarVersion,
        userName = appState.userName,
        agentName = connection?.agentId ?: stringResource(R.string.main_agent_default_name),
        showStats = appState.showMessageStats,
        serverUserAvatar = serverAvatarFile,
        useServerAvatar = appState.useServerAvatar,
        actionStyle = appState.actionStyle,
    )

    // 导出统一走系统分享：用户自己决定交给哪个应用处理，
// 客户端不在用户存储里留副本，也就不需要 FileProvider 与存储权限。
    // 文本在内存里直接交给 ACTION_SEND，没有「选位置」这一步，自然没有
    // 「Activity 被重建后文本丢失」的问题。

    val speech = remember(context) { io.qwenpaw.node.voice.VoiceInput(context) }
    val transcriber = remember { io.qwenpaw.node.voice.ServerTranscriber(app.api) }
    // 端上识别能力由变体决定：full 有 sherpa，_no-sherpa 恒为不可用。
    val localAsr = app.localAsr
    val localModel by localAsr.modelState.collectAsState()

    // 日志导入：读外部文本后替换日志页内容，用于查看别人分享的日志。
    val logImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            toast(context, context.getString(R.string.main_import_cancelled))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val text = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                toast(context, context.getString(R.string.main_read_file_failed))
            } else {
                viewModel.importLog(text)
                toast(context, context.getString(R.string.main_log_loaded))
            }
        }
    }
    val voiceState by speech.state.collectAsState()
    val voiceStartedAt by speech.startedAt.collectAsState()
    val voicePartial by speech.partial.collectAsState()

    // ---------------- 头像 ----------------
    //
    // 选中哪一类头像（user / agent）与刚读到的图片都放在 rememberSaveable 里：
    // 系统相册是另一个 Activity，低内存下本页可能被回收重建，
    // 用普通 remember 会让「选完图回来什么都没发生」。
    var avatarTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // 裁剪用的位图没法 Saveable，重建时退回未选状态即可（用户再选一次）。
    var cropping by remember { mutableStateOf<io.qwenpaw.node.avatar.AvatarSource?>(null) }
    var croppingKind by remember { mutableStateOf<String?>(null) }
    // 为某个对话选图标时记下 chatId；非空表示这次裁剪的产物属于对话图标，
    // 而不是头像。两者共用裁剪页，靠这个字段决定存到哪个 store。
    var croppingChatId by remember { mutableStateOf<String?>(null) }
    var pendingIconChatId by rememberSaveable { mutableStateOf<String?>(null) }

    // 对话图标只接受静态图：列表行是 34dp 的小方块，动图在那里看不出动画，
    // 只会让滚动时每一行都在解码。因此这条链路不走 GIF 分支。
    val pickChatIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val chatId = pendingIconChatId
        pendingIconChatId = null
        if (uri == null || chatId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                toast(context, context.getString(R.string.main_read_image_failed))
                return@launch
            }
            val decoded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.qwenpaw.node.avatar.AvatarIntake.decode(bytes)
            }
            if (decoded == null) {
                toast(context, context.getString(R.string.main_image_unreadable))
                return@launch
            }
            croppingChatId = chatId
            cropping = io.qwenpaw.node.avatar.AvatarSource(
                animated = false,
                bitmap = decoded,
                bytes = bytes,
            )
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(
        // 头像只要静态图或 GIF，不要视频，所以是 ImageOnly。
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val kind = avatarTarget
        avatarTarget = null
        if (uri == null || kind == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                toast(context, context.getString(R.string.main_read_image_failed))
                return@launch
            }
            if (io.qwenpaw.node.avatar.AvatarIntake.isGif(bytes)) {
                // GIF 不裁剪：项目没有动图解码能力，裁剪后只剩首帧，
                // 等于把用户的动图毁掉。原样保存，至少换成支持动图的版本时还在。
                val saved = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.avatarStore.saveAnimated(kind, bytes)
                }
                toast(
                    context,
                    if (saved) {
                        context.getString(R.string.main_gif_saved)
                    } else {
                        context.getString(R.string.action_save_failed)
                    },
                )
                return@launch
            }
            val decoded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.qwenpaw.node.avatar.AvatarIntake.decode(bytes)
            }
            if (decoded == null) {
                toast(context, context.getString(R.string.main_image_unreadable))
                return@launch
            }
            croppingKind = kind
            cropping = io.qwenpaw.node.avatar.AvatarSource(
                animated = false,
                bitmap = decoded,
                bytes = bytes,
            )
        }
    }
    var showVoiceIntro by remember { mutableStateOf(false) }
    // 扫码页是全屏覆盖层，与头像裁剪同层：扫完回到原表单，输入不会丢。
    var scanningPair by remember { mutableStateOf(false) }
    // 归档提示按自然日抑制。用 state 而非每次直接读 prefs，
    // 勾选后当次就能立刻生效，不必等下一次重组。
    var archiveTipVisible by remember { mutableStateOf(viewModel.shouldShowArchiveTip()) }
    // 点击麦克风但服务端转写不可用：说明原因，不发起录音。
    var voiceUnavailable by remember {
        mutableStateOf<io.qwenpaw.node.data.transcription.TranscriptionStatus?>(null)
    }
    // 第一次选了「关闭」后，询问是否顺带隐藏按钮。
    var showHideVoiceButton by remember { mutableStateOf(false) }
    // 本地识别未下载模型时的一级提示。
    var showVoiceModelRequired by remember { mutableStateOf(false) }

    /**
     * 「说完自动停止」的生效条件。
     *
     * 只有本地识别 + 点一下模式才启用：按住说话本来就是松手即停，
     * 服务端转写则是整段上传，断句没有意义。
     */
    fun voiceAutoStopNow(): Boolean =
        appState.voiceSource == io.qwenpaw.node.ui.VoiceSource.LOCAL &&
            appState.voiceTrigger == io.qwenpaw.node.ui.VoiceTrigger.TAP &&
            appState.voiceAutoStop

    /** 转写结果追加进草稿，不覆盖用户已输入的文字。 */
    fun appendDraft(text: String) {
        val existing = chatVmState.draft
        val merged = if (existing.isBlank()) text else "$existing$text"
        chatVm.updateDraft(merged)
    }

    /** 录音结束后的上传与结果填充（服务端转写）。 */
    fun transcribe(file: File) {
        val conn = connection
        if (conn == null) {
            speech.resetToIdle()
            toast(context, context.getString(R.string.main_server_not_connected))
            return
        }
        speech.markTranscribing()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { transcriber.transcribe(conn, file) }
            }
            result
                .onSuccess { text -> appendDraft(text) }
                .onFailure { e ->
                    toast(context, e.message ?: context.getString(R.string.main_transcribe_failed))
                }
            speech.resetToIdle()
        }
    }

    /** 端上识别：边录边出字，结束时填入草稿。 */
    fun beginLocalRecording() {
        val engine = localAsr.createEngine(endpointing = voiceAutoStopNow())
        if (engine == null) {
            toast(context, context.getString(R.string.main_model_incomplete))
            return
        }
        speech.start(
            target = io.qwenpaw.node.voice.VoiceTarget.Local(
                engine = engine,
                autoStop = voiceAutoStopNow(),
                onPartial = {},
                onFinal = { text -> appendDraft(text) },
            ),
            onError = { message ->
                speech.resetToIdle()
                toast(context, message)
            },
        )
    }

    fun beginUploadRecording() {
        speech.start(
            target = io.qwenpaw.node.voice.VoiceTarget.Upload(
                onRecorded = { file, reachedLimit ->
                    if (reachedLimit) {
                        toast(
                            context,
                            context.getString(
                                R.string.main_recording_limit,
                                io.qwenpaw.node.voice.VoiceInput.MAX_DURATION_MS / 1000,
                            ),
                        )
                    }
                    transcribe(file)
                },
            ),
            onError = { message ->
                speech.resetToIdle()
                toast(context, message)
            },
        )
    }

    /** 按当前来源开录。本地识别不看服务端，只有上传才需要先确认服务端可用。 */
    fun beginRecording() {
        if (appState.voiceSource == io.qwenpaw.node.ui.VoiceSource.LOCAL && localAsr.available) {
            beginLocalRecording()
        } else {
            beginUploadRecording()
        }
    }

    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginRecording() else toast(context, context.getString(R.string.main_no_mic_permission))
    }

    /**
     * 点麦克风。
     *
     * 本地识别不依赖服务端，直接开录；服务端转写要先确认可用，
     * 否则用户会白录一段再等一次失败上传。
     */
    fun startVoice() {
        // 本地识别要先有模型：未下载时弹说明并引导去设置。
        if (appState.voiceSource == io.qwenpaw.node.ui.VoiceSource.LOCAL && localAsr.available) {
            if (localModel !is io.qwenpaw.node.voice.LocalModelState.Ready) {
                showVoiceModelRequired = true
                return
            }
            if (!speech.hasPermission()) {
                askMic.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            beginLocalRecording()
            return
        }
        when (val status = appState.transcribeStatus) {
            io.qwenpaw.node.data.transcription.TranscriptionStatus.Ready -> Unit
            // 还没问出来（首次进入、或 401 期间）先探一次，拿到结论再决定。
            io.qwenpaw.node.data.transcription.TranscriptionStatus.Unknown -> {
                viewModel.refreshTranscribeStatus { result ->
                    if (result.canRecord) {
                        if (speech.hasPermission()) beginUploadRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        voiceUnavailable = result
                    }
                }
                return
            }
            else -> {
                voiceUnavailable = status
                return
            }
        }
        if (!speech.hasPermission()) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginUploadRecording()
    }

    // 屏幕是 Compose 内部状态，系统返回键默认会直接退出 Activity。
    // 按层级后退而不是一律回聊天页：从列表进设置再返回时应当回到列表，
    // 否则「设置盖住列表」的层级关系在返回时被跳过，滑向也就无从推导。
    BackHandler(enabled = appState.connected && screen != Screen.CHAT) {
        if (screen == Screen.TERMINAL) {
            screen = terminalOrigin
        } else {
            parentOf(screen, usageOrigin)?.let { screen = it }
        }
    }

    // 附件同时支持图片与文档。用 ACTION_GET_CONTENT 而不是 OpenDocument：
    // 后者由系统文档提供器接管，小米上固定进自家文件管理器、选不到第三方来源；
    // 前者会先让用户挑应用，能选 CX / NP 文件管理器、WPS、相册等。
    // 单个文件失败只跳过它，不打断其余附件。
    val pickFiles = rememberLauncherForActivityResult(
        io.qwenpaw.node.ui.common.GetMultipleContent(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var failed = 0
            uris.forEach { uri ->
                val url = uploadAttachment(context, app, uri)
                if (url != null) chatVm.addAttachment(url) else failed++
            }
            if (failed > 0) {
                toast(
                    context,
                    context.resources.getQuantityString(
                        R.plurals.main_attach_upload_failed,
                        failed,
                        failed,
                    ),
                )
            }
        }
    }

    // 扫码页是覆盖层，但**不能让下层退出组合**：早期用早返回渲染，打开扫码后
    // 连接页被整个销毁，取消回来时引导进度与已填内容全丢。改用全屏 Dialog 叠在上面。
    if (scanningPair) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { scanningPair = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            io.qwenpaw.node.ui.settings.QrScanScreen(
                onBack = { scanningPair = false },
                onDecoded = { pair ->
                    scanningPair = false
                    viewModel.applyScannedPair(pair)
                },
            )
        }
    }

    if (!appState.connected) {
        ConnectScreen(
            connecting = appState.connecting,
            error = appState.connectError,
            onConnect = { draft ->
                viewModel.connect(draft)
            },
            onLoadAgents = { hosts, user, pass, basic ->
                viewModel.loadAgents(hosts, user, pass, basic)
            },
            onVerifyHosts = { hosts, basic, onAttempt ->
                viewModel.verifyHosts(hosts, onAttempt, basicAuth = basic)
            },
            onCheckAuth = { address -> viewModel.authRequired(address) },
            onCheckLogin = { address, user, pass, basic ->
                viewModel.checkLogin(address, user, pass, basic)
            },
            saved = instances,
            onUseSaved = { viewModel.useSaved(it) },
            // 受信任的主机不再弹地址安全提示。
            isTrustedHost = viewModel::isTrustedHost,
            scannedPair = appState.scannedPair,
            onConsumeScannedPair = viewModel::consumeScannedPair,
            onOpenScanner = { scanningPair = true },
            onLoadAgentsWithToken = { hosts, token, basic ->
                viewModel.loadAgentsWithToken(hosts, token, basic)
            },
        )
        return
    }

    appState.pendingInstanceSwitch?.let { target ->
        val otherInstance = stringResource(R.string.main_terminal_switch_other)
        AlertDialog(
            onDismissRequest = viewModel::cancelInstanceSwitch,
            title = { Text(stringResource(R.string.main_terminal_running_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.main_terminal_switch_message,
                        target.name.ifBlank { otherInstance },
                    ),
                )
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.cancelInstanceSwitch()
                            screen = Screen.TERMINAL
                        },
                    ) { Text(stringResource(R.string.main_open_terminal_page)) }
                    TextButton(onClick = viewModel::confirmInstanceSwitch) {
                        Text(stringResource(R.string.main_stop_terminal))
                    }
                    TextButton(onClick = viewModel::cancelInstanceSwitch) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    // 头像裁剪是一个全屏模态层：放在页面切换之前，盖住任何页面。
    // 用早返回而不是塞进 Screen 枚举，是因为裁剪属于「选图的后续」，
    // 不该出现在页面栈里，也不该被 AnimatedContent 的滑动动画带动。
    val pendingCrop = cropping
    if (pendingCrop?.bitmap != null) {
        val kind = croppingKind
        val chatId = croppingChatId
        io.qwenpaw.node.avatar.AvatarCropScreen(
            bitmap = pendingCrop.bitmap!!,
            onCancel = {
                cropping = null
                croppingKind = null
                croppingChatId = null
            },
            onSave = { bitmap ->
                // 两种用途共用同一个裁剪页，落盘目标由进入时选的入口决定。
                val saved = if (chatId != null) {
                    app.chatIconStore.saveImage(chatId, bitmap)
                } else {
                    app.avatarStore.saveStatic(kind.orEmpty(), bitmap)
                }
                cropping = null
                croppingKind = null
                croppingChatId = null
                toast(
                    context,
                    if (!saved) {
                        context.getString(R.string.action_save_failed)
                    } else if (chatId != null) {
                        context.getString(R.string.main_icon_saved)
                    } else {
                        context.getString(R.string.main_avatar_saved)
                    },
                )
            },
        )
        return
    }

    // 页面切换：淡入 + 轻推。方向分三种情况：
    //
    // 1. 进出对话列表（LIST）：它是「抽屉」，从左侧滑入、向左侧退出。
    //    早先统一按「深入从右」处理，点左上角时列表从右边弹出，与按钮在左边的
    //    视觉暗示相反，看起来像是打开了另一个更深的页面。
    // 2. 其余前向：从右侧轻推。
    // 3. 其余返回：从左侧轻推。
    //
    // 抽屉位移取半屏，子页取三分之一屏：位移太小在快速动画里会被看成单纯的淡入，
    // 看不出滑动方向。
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                // 方向由「入口按钮在哪条边缘」决定，返回是进入的反向。
                // 对话列表由聊天页左上角的按钮打开，按抽屉处理：从左侧滑入、
                // 退出时向左滑走，被盖住的聊天页只淡入淡出，不跟着平移。
                val drawer = targetState == Screen.LIST || initialState == Screen.LIST
                if (drawer) {
                    val entering = targetState == Screen.LIST
                    val enter = fadeIn(tween(200)) +
                        slideInHorizontally(tween(240)) { if (entering) -it / 2 else 0 }
                    val exit = fadeOut(tween(160)) +
                        slideOutHorizontally(tween(240)) { if (entering) 0 else -it }
                    enter togetherWith exit
                } else {
                    // 其余入口都在屏幕右侧（设置、已归档、用量的右上角或行尾箭头，
                    // 日志是设置里的行），因此深入从右滑入、返回向右退出。
                    val forward = parentOf(targetState, usageOrigin) == initialState
                    (fadeIn(tween(200)) +
                        slideInHorizontally(tween(240)) { if (forward) it / 3 else -it / 3 })
                        .togetherWith(
                            fadeOut(tween(160)) +
                                slideOutHorizontally(tween(240)) {
                                    if (forward) -it / 3 else it / 3
                                },
                        )
                }
            },
            label = "screen",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
        when (current) {
            Screen.LIST -> {
                // 列表页要显示收件箱未读角标，而角标只在进过收件箱后才有值；
                // 每次落到列表页拉一次，代价是一页事件（默认 30 条）。
                LaunchedEffect(Unit) { viewModel.inbox.refresh() }
                ChatListScreen(
                    chats = chatVmState.chats,
                    loading = chatVmState.loading,
                    compact = appState.compactList,
                    previews = chatVmState.previews,
                    archivedCount = chatVmState.archivedChats.size,
                    onBack = { screen = Screen.CHAT },
                    onOpen = { chatId ->
                        chatVm.openChat(chatId)
                        screen = Screen.CHAT
                    },
                    onCreate = {
                        chatVm.createChat()
                        screen = Screen.CHAT
                    },
                    onRename = chatVm::renameChat,
                    onDelete = chatVm::deleteChat,
                    onTogglePin = chatVm::togglePin,
                    onArchive = chatVm::archiveChat,
                    onBatchArchive = chatVm::batchArchive,
                    onBatchDelete = chatVm::batchDelete,
                    onOpenArchived = {
                        chatVm.refreshArchivedChats()
                        screen = Screen.ARCHIVED
                    },
                    onOpenTokenUsage = {
                        usageOrigin = Screen.LIST
                        viewModel.usage.ensureLoaded()
                        screen = Screen.TOKEN_USAGE
                    },
                    onOpenTerminal = {
                        terminalOrigin = Screen.LIST
                        screen = Screen.TERMINAL
                    },
                    onOpenInbox = {
                        // 进入前刷新一次：未读数与列表内容都要是当前的。
                        viewModel.inbox.refresh()
                        screen = Screen.INBOX
                    },
                    inboxUnread = inboxUnread,
                    onOpenSettings = { screen = Screen.SETTINGS },
                    showArchiveTip = archiveTipVisible,
                    onSuppressArchiveTip = {
                        viewModel.suppressArchiveTipToday()
                        archiveTipVisible = false
                    },
                    onRefresh = {
                        // 列表页要的是列表本身，不是当前对话：这里直接刷新对话列表。
                        chatVm.refreshChats()
                    },
                    groupMode = appState.groupMode,
                    onGroupMode = viewModel::setGroupMode,
                    groups = chatVmState.groups,
                    collapsed = appState.collapsedGroups,
                    onToggleCollapse = { key ->
                        viewModel.setGroupCollapsed(key, key !in appState.collapsedGroups)
                    },
                    onCreateGroup = { name ->
                        chatVm.createGroup(name) { ok ->
                            if (!ok) toast(context, context.getString(R.string.main_create_group_failed))
                        }
                    },
                    onRenameGroup = chatVm::renameGroup,
                    onDeleteGroup = chatVm::deleteGroup,
                    onReorderGroup = chatVm::moveGroup,
                    onMoveChatToGroup = chatVm::setChatGroup,
                    iconStore = app.chatIconStore,
                    iconVersion = iconVersion,
                    onPickIconImage = { chatId ->
                        pendingIconChatId = chatId
                        pickChatIcon.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onOpenGlobalSearch = run {
                        // 判定标准集中在这里：既要总开关打开，也要插件在 info 里声明 search。
                        val supportsSearch =
                            (appState.bridgeStatus as? io.qwenpaw.node.data.plugin.BridgeStatus.Ready)
                                ?.info?.supports("search") == true
                        if (appState.bridgeFeaturesEnabled && supportsSearch) {
                            { screen = Screen.SEARCH }
                        } else {
                            null
                        }
                    },
                )
            }

            Screen.SEARCH -> {
                SearchScreen(
                    state = searchState,
                    // 命中带的是 history.db 的 session_id，与对话 id 不是同一个值，
                    // 两种键都放进映射表，命中才能显示对话名。
                    chatNames = chatVmState.chats.associate { it.id to it.name } +
                        chatVmState.chats.associate { it.sessionId to it.name },
                    onBack = { screen = Screen.LIST },
                    onSearch = chatVm::search,
                    onLoadMore = chatVm::loadMoreSearch,
                    onOpenHit = { hit ->
                        chatVm.openChatAtMessage(hit.sessionId, hit.msgId)
                        screen = Screen.CHAT
                    },
                )
            }

            Screen.WORKSPACE -> {
                LaunchedEffect(Unit) { viewModel.workspace.ensureLoaded() }
                io.qwenpaw.node.ui.workspace.WorkspaceScreen(
                    state = workspaceState,
                    onBack = { screen = Screen.SETTINGS },
                    onRefresh = viewModel.workspace::refresh,
                    onLoadMore = viewModel.workspace::loadMore,
                    onOpenRoot = viewModel.workspace::openRoot,
                    onOpenGit = { screen = Screen.GIT },
                    onEnter = viewModel.workspace::enter,
                    onUp = viewModel.workspace::up,
                    onOpenFile = viewModel.workspace::openFile,
                    onClosePreview = viewModel.workspace::closePreview,
                    onLoadMoreContent = viewModel.workspace::loadMoreContent,
                    onRequestDownload = viewModel.workspace::requestDownload,
                    onRequestDirectoryDownload = viewModel.workspace::requestDirectoryDownload,
                    onPlanCancel = viewModel.workspace::cancelPlan,
                    onStartDownload = viewModel.workspace::startDownload,
                    onStartDirectoryDownload = viewModel.workspace::startDirectoryDownload,
                    onCancelDownload = viewModel.workspace::cancelDownload,
                    onDismissDownload = viewModel.workspace::dismissDownload,
                    write = io.qwenpaw.node.ui.workspace.WorkspaceWriteActions(
                        beginEdit = viewModel.workspace::beginEdit,
                        updateDraft = viewModel.workspace::updateDraft,
                        saveEdit = viewModel.workspace::saveEdit,
                        cancelEdit = viewModel.workspace::cancelEdit,
                        reloadConflict = viewModel.workspace::reloadAfterConflict,
                        forceOverwrite = viewModel.workspace::forceOverwrite,
                        dismissConflict = viewModel.workspace::dismissConflict,
                        beginUpload = viewModel.workspace::beginUpload,
                        upload = viewModel.workspace::upload,
                        resolveUploadConflict = viewModel.workspace::resolveUploadConflict,
                        cancelUpload = viewModel.workspace::cancelUpload,
                        dismissUpload = viewModel.workspace::dismissUpload,
                    ),
                )
            }

            Screen.BACKUP -> {
                LaunchedEffect(Unit) { viewModel.backup.ensureLoaded() }
                io.qwenpaw.node.ui.backup.BackupScreen(
                    state = backupState,
                    onBack = { screen = Screen.SETTINGS },
                    actions = io.qwenpaw.node.ui.backup.BackupActions(
                        refresh = viewModel.backup::refresh,
                        retryJob = viewModel.backup::refreshJob,
                        cancelJob = viewModel.backup::cancelJob,
                        setCreateName = viewModel.backup::setCreateName,
                        setCreateDescription = viewModel.backup::setCreateDescription,
                        toggleCreateAgent = viewModel.backup::toggleCreateAgent,
                        setCreateScope = viewModel.backup::setCreateScope,
                        startCreate = viewModel.backup::startCreate,
                        openDetail = viewModel.backup::openDetail,
                        dismissDetail = viewModel.backup::dismissDetail,
                        requestDelete = viewModel.backup::requestDelete,
                        dismissDelete = viewModel.backup::dismissDelete,
                        confirmDelete = viewModel.backup::confirmDelete,
                        export = viewModel.backup::export,
                        beginImport = viewModel.backup::beginImport,
                        reportImportProgress = viewModel.backup::reportImportProgress,
                        confirmImportOverwrite = viewModel.backup::confirmImportOverwrite,
                        trustImport = viewModel.backup::trustImportAndContinue,
                        dismissImport = viewModel.backup::dismissImport,
                        prepareRestore = viewModel.backup::prepareRestore,
                        updateRestore = viewModel.backup::updateRestore,
                        dismissRestore = viewModel.backup::dismissRestore,
                        confirmRestore = viewModel.backup::confirmRestore,
                        dismissNotice = viewModel.backup::dismissNotice,
                        dismissError = viewModel.backup::dismissError,
                        dismissExportError = viewModel.backup::dismissExportError,
                    ),
                )
            }

            Screen.CHECKPOINT -> {
                LaunchedEffect(Unit) { viewModel.checkpoint.ensureLoaded() }
                // 只读对话页是检查点的下一层：打开会话时切到 CHECKPOINT_CHAT，
                // 返回时由 parentOf 回到检查点页，而不是一路退回设置。
                LaunchedEffect(checkpointState.viewingSession?.sessionKey) {
                    if (checkpointState.viewingSession != null) screen = Screen.CHECKPOINT_CHAT
                }
                io.qwenpaw.node.ui.checkpoint.CheckpointScreen(
                    state = checkpointState,
                    onBack = { screen = Screen.SETTINGS },
                    actions = io.qwenpaw.node.ui.checkpoint.CheckpointActions(
                        refresh = viewModel.checkpoint::refresh,
                        toggleAuto = viewModel.checkpoint::toggleAuto,
                        openSnapshot = viewModel.checkpoint::openSnapshot,
                        closeSnapshot = viewModel.checkpoint::closeSnapshot,
                        setSnapshotName = viewModel.checkpoint::setSnapshotName,
                        dismissSnapshot = viewModel.checkpoint::dismissSnapshot,
                        submitSnapshot = viewModel.checkpoint::submitSnapshot,
                        openRestore = viewModel.checkpoint::openRestore,
                        dismissRestore = viewModel.checkpoint::dismissRestore,
                        setIncludeMemory = viewModel.checkpoint::setIncludeMemory,
                        setIncludeFiles = viewModel.checkpoint::setIncludeFiles,
                        toggleFile = viewModel.checkpoint::toggleFile,
                        selectAllFiles = viewModel.checkpoint::selectAllFiles,
                        clearFiles = viewModel.checkpoint::clearFiles,
                        loadPreview = viewModel.checkpoint::loadPreview,
                        applyRestore = viewModel.checkpoint::applyRestore,
                        updateGcDraft = viewModel.checkpoint::updateGcDraft,
                        saveGcSettings = viewModel.checkpoint::saveGcSettings,
                        previewGc = viewModel.checkpoint::previewGc,
                        applyGc = viewModel.checkpoint::applyGc,
                        requestReset = viewModel.checkpoint::requestReset,
                        dismissReset = viewModel.checkpoint::dismissReset,
                        confirmReset = viewModel.checkpoint::confirmReset,
                        dismissNotice = viewModel.checkpoint::dismissNotice,
                        dismissError = viewModel.checkpoint::dismissError,
                    ),
                )
            }

            Screen.CHECKPOINT_CHAT -> {
                val viewing = checkpointState.viewingSession
                if (viewing == null) {
                    // 快照刚创建成功或已返回：这一帧之后由状态驱动切回检查点页。
                    LaunchedEffect(Unit) { screen = Screen.CHECKPOINT }
                } else {
                    CheckpointChatPane(
                        viewing = viewing,
                        checkpointState = checkpointState,
                        chatState = chatVmState,
                        chatVm = chatVm,
                        imageLoader = imageLoader,
                        connection = connection,
                        appearance = messageAppearance,
                        assistantCollapse = appState.assistantCollapse,
                        renderUserMarkdown = appState.renderUserMarkdown,
                        renderAssistantMarkdown = appState.renderAssistantMarkdown,
                        onBack = {
                            viewModel.checkpoint.closeSnapshot()
                            screen = Screen.CHECKPOINT
                        },
                        snapshotName = viewModel.checkpoint::setSnapshotName,
                        submitSnapshot = viewModel.checkpoint::submitSnapshot,
                        dismissSnapshot = viewModel.checkpoint::dismissSnapshot,
                    )
                }
            }

            Screen.CHAT -> {
                ChatScreen(
                    state = chatVmState,
                    voiceState = voiceState,
                    voiceStartedAt = voiceStartedAt,
                    voicePartial = voicePartial,
                    // 可见性只看开关；服务端转写是否可用另行传入，用于点击时的提示与弱化显示。
                    voiceVisible = appState.voiceEnabled,
                    // 两个来源任一可用就不弱化：本地识别不看服务端配置。
                    voiceAvailable = appState.transcribeStatus.canRecord,
                    voiceLocalReady = appState.voiceSource == io.qwenpaw.node.ui.VoiceSource.LOCAL &&
                        localModel is io.qwenpaw.node.voice.LocalModelState.Ready,
                    imageLoader = imageLoader,
                    connection = connection,
                    // 左上角按钮打开对话列表（附加页），不是在页面栈里返回。
                    onBack = { screen = Screen.LIST },
                    voiceHoldMode = appState.voiceTrigger == io.qwenpaw.node.ui.VoiceTrigger.HOLD,
                    assistantCollapse = appState.assistantCollapse,
                    onDraftChange = chatVm::updateDraft,
                    onSend = { chatVm.send() },
                    commands = chatVmState.slashCommands,
                    onStop = chatVm::stop,
                    onAttach = { pickFiles.launch(Unit) },
                    onVoiceStart = { startVoice() },
                    onVoiceStop = { speech.stop() },
                    onVoiceCancel = { speech.resetToIdle() },
                    onRemoveAttachment = chatVm::removeAttachment,
                    onApprove = chatVm::approve,
                    onDeny = chatVm::deny,
                    onRetry = chatVm::retry,
                    onRename = { name ->
                        chatVmState.currentChatId?.let { chatVm.renameChat(it, name) }
                    },
                    onDelete = {
                        chatVmState.currentChatId?.let { chatVm.deleteChat(it) }
                        // 删掉后回到聊天页，ViewModel 会自动接上一个对话。
                        screen = Screen.CHAT
                    },
                    onTogglePin = {
                        chatVmState.currentChatId?.let { chatVm.togglePin(it) }
                    },
                    onArchive = {
                        chatVmState.currentChatId?.let { chatVm.archiveChat(it) }
                        screen = Screen.CHAT
                    },
                    onExport = {
                        val name = chatVmState.currentChatName
                            .ifBlank { context.getString(R.string.main_default_chat_name) }
                            .take(40)
                        shareText(context, "$name.md", chatVm.exportMarkdown())
                    },
                    onSetThinking = { level, budget -> chatVm.setThinking(level, budget) },
                    onSetGroup = { groupId ->
                        chatVmState.currentChatId?.let { chatVm.setChatGroup(it, groupId) }
                    },
                    onPickModel = { providerId, model -> chatVm.setModel(providerId, model) },
                    onPickApproval = { level ->
                        // 审批模式是智能体级配置，改动会影响该智能体的全部对话。
                        (connection?.agentId ?: "default").let { agentId ->
                            chatVm.setApprovalLevel(agentId, level)
                        }
                    },
                    onSetProjectDir = { path -> chatVm.setProjectDir(path) },
                    onDismissError = chatVm::clearError,
                    // 顶栏「连接中断，点击重连」走这条：重绑地址 + 重载当前对话。
                    onRefresh = { viewModel.refreshAll() },
                    appearance = messageAppearance,
                    renderUserMarkdown = appState.renderUserMarkdown,
                    renderAssistantMarkdown = appState.renderAssistantMarkdown,
                    onBrowseDirs = { path -> chatVm.browseProjectDirs(path) },
                    onToolOpen = { id ->
                        chatVm.refreshToolCalls()
                        chatVm.openToolStream(id)
                    },
                    onToolClose = chatVm::closeToolStream,
                    onToolDeadline = chatVm::refreshToolCalls,
                    onCancelTool = { id -> chatVm.cancelTool(id) },
                    onOffloadTool = chatVm::offloadTool,
                    onExtendTool = chatVm::extendTool,
                    onPendingScrollHandled = chatVm::clearPendingScroll,
                    onOpenSource = { text ->
                        sourceText = text
                        screen = Screen.MESSAGE_SOURCE
                    },
                )
            }

            // 一条消息的原始 Markdown，从「选择文本」的询问里进入。
            Screen.MESSAGE_SOURCE -> io.qwenpaw.node.ui.chat.MessageSourceScreen(
                text = sourceText.orEmpty(),
                onBack = { screen = Screen.CHAT },
            )

            Screen.ARCHIVED -> {
                io.qwenpaw.node.ui.chat.ArchivedScreen(
                    chats = chatVmState.archivedChats,
                    onBack = { screen = Screen.LIST },
                    onUnarchive = { chatId ->
                        chatVm.unarchiveChat(chatId)
                    },
                    onDelete = { chatId ->
                        chatVm.deleteChat(chatId)
                    },
                    onBatchUnarchive = { ids -> chatVm.batchUnarchive(ids) },
                    onBatchDelete = { ids -> chatVm.batchDelete(ids) },
                )
            }

            Screen.TOKEN_USAGE -> {
                io.qwenpaw.node.ui.stats.UsageScreen(
                    state = usageState,
                    onBack = {
                        parentOf(Screen.TOKEN_USAGE, usageOrigin)?.let { screen = it }
                    },
                    onRange = viewModel.usage::setRange,
                    onRetry = viewModel.usage::refresh,
                )
            }

            Screen.PORTABILITY -> {
                io.qwenpaw.node.ui.agents.PortabilityImportScreen(
                    state = portabilityState,
                    onBack = { screen = Screen.AGENTS },
                    onEnsureLoaded = viewModel.portability::load,
                    onToggleSource = viewModel.portability::toggleSource,
                    onCreateJob = viewModel.portability::createJob,
                    onToggleAsset = viewModel.portability::toggleAsset,
                    onSessions = viewModel.portability::setSessions,
                    onAllowPluginExecution = viewModel.portability::setAllowPluginExecution,
                    onStartImport = viewModel.portability::startImport,
                    onRetryFailed = viewModel.portability::retryFailed,
                    onCancel = viewModel.portability::cancel,
                    onStopPolling = viewModel.portability::stopPolling,
                    onDismissNotice = viewModel.portability::dismissNotice,
                )
            }

            Screen.APP_CENTER -> {
                val base = viewModel.activeBaseUrl()
                io.qwenpaw.node.ui.apps.AppCenterScreen(
                    state = appCenterState,
                    baseUrl = base,
                    onBack = { screen = Screen.SETTINGS },
                    onEnsureLoaded = { viewModel.appCenter.refresh() },
                    onOpenConsole = {
                        base?.let {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(it),
                                ),
                            )
                        }
                    },
                )
            }

            Screen.CHANNEL -> {
                io.qwenpaw.node.ui.channels.ChannelScreen(
                    state = channelState,
                    onBack = { screen = Screen.SETTINGS },
                    onEnsureLoaded = { viewModel.channels.refresh() },
                    onRefresh = { viewModel.channels.refresh(force = true) },
                    onOpenChannel = viewModel.channels::openChannel,
                    onInstallChannel = viewModel.channels::installChannel,
                    onRetry = viewModel.channels::retry,
                    onCloseAuth = viewModel.channels::closeAuth,
                    onStopPolling = viewModel.channels::stopPolling,
                    onConsumeEnabled = viewModel.channels::consumeEnabledChannel,
                    onConsumeNotice = viewModel.channels::consumeNotice,
                )
            }

            Screen.LOCAL_MODELS -> {
                io.qwenpaw.node.ui.localmodels.LocalModelsScreen(
                    state = localModelsState,
                    onBack = { screen = Screen.SETTINGS },
                    onEnsureLoaded = { viewModel.localModels.refresh() },
                    onStopPolling = viewModel.localModels::stopPolling,
                    onInstallRuntime = viewModel.localModels::installRuntime,
                    onDownloadModel = viewModel.localModels::downloadModel,
                    onCancel = viewModel.localModels::cancelActive,
                    onConsumeMessage = viewModel.localModels::consumeMessage,
                )
            }

            Screen.RUNTIME -> {
                LaunchedEffect(Unit) {
                    viewModel.runtime.loadAcp()
                    viewModel.runtime.loadHarness()
                }
                io.qwenpaw.node.ui.settings.RuntimeScreen(
                    acp = acpState,
                    harness = harnessState,
                    onBack = { screen = Screen.SETTINGS },
                    onEnsureLoaded = {},
                    onSaveNodePath = viewModel.runtime::saveNodePath,
                    onSaveAcpAgent = viewModel.runtime::saveAcpAgent,
                    onProbeHarness = viewModel.runtime::probe,
                    onLoginHarness = viewModel.runtime::login,
                    onLogoutHarness = viewModel.runtime::logout,
                    onOpenHarnessDetail = viewModel.runtime::openDetail,
                    onInstallHarness = { url ->
                        viewModel.market.openInstallUrlDialog(url)
                        screen = Screen.MARKET
                    },
                    onDismissHarnessLogin = viewModel.runtime::dismissLogin,
                    onDismissHarnessDetail = viewModel.runtime::dismissDetail,
                    onDismissAcpNotice = viewModel.runtime::dismissAcpNotice,
                    onDismissHarnessNotice = viewModel.runtime::dismissHarnessNotice,
                )
            }

            Screen.AGENTS -> {
                LaunchedEffect(Unit) {
                    viewModel.agents.setCurrentAgent(
                        instances.firstOrNull { it.id == activeId }?.agentId,
                    )
                    viewModel.agents.refresh()
                }
                val bridge = appState.bridgeStatus
                val portabilityReady = appState.bridgeFeaturesEnabled &&
                    bridge is io.qwenpaw.node.data.plugin.BridgeStatus.Ready &&
                    bridge.info.supports("portability")
                io.qwenpaw.node.ui.agents.AgentsScreen(
                    state = agentsState,
                    exportState = backupState.agentExport,
                    portabilityReady = portabilityReady,
                    onBack = { screen = Screen.SETTINGS },
                    onEnsureLoaded = {},
                    onCreate = viewModel.agents::create,
                    onCopy = viewModel.agents::copy,
                    onRename = viewModel.agents::rename,
                    onSetEnabled = viewModel.agents::setEnabled,
                    onSetPinned = viewModel.agents::setPinned,
                    onMove = viewModel.agents::move,
                    onDelete = viewModel.agents::delete,
                    onUseAsCurrent = viewModel.agents::useAsCurrent,
                    onExport = { agent, sink ->
                        viewModel.backup.startAgentExport(agent.id, agent.name, sink)
                    },
                    onDismissExport = viewModel.backup::dismissAgentExport,
                    onOpenPortability = { screen = Screen.PORTABILITY },
                    onDismissNotice = viewModel.agents::dismissNotice,
                    onSaveBackend = viewModel.agents::saveBackend,
                )
            }

            Screen.RUNNING_CONFIG -> {
                io.qwenpaw.node.ui.settings.RunningConfigScreen(
                    state = runningConfigState,
                    onBack = { screen = Screen.SETTINGS },
                    onEnsureLoaded = { viewModel.settingsConfig.loadRunningConfig() },
                    onSave = viewModel.settingsConfig::saveRunningConfig,
                    onDismissNotice = viewModel.settingsConfig::dismissRunningNotice,
                )
            }

            Screen.SECURITY -> {
                io.qwenpaw.node.ui.settings.SecurityScreen(
                    state = securityState,
                    onBack = { screen = Screen.SETTINGS },
                    onEnsureLoaded = { viewModel.settingsConfig.loadSecurity() },
                    onToolGuard = viewModel.settingsConfig::saveToolGuardEnabled,
                    onFileGuard = viewModel.settingsConfig::saveFileGuardEnabled,
                    onSandbox = viewModel.settingsConfig::saveSandboxEnabled,
                    onSkillScanner = viewModel.settingsConfig::saveSkillScannerMode,
                    onDismissNotice = viewModel.settingsConfig::dismissSecurityNotice,
                )
            }

            Screen.MODELS -> {
                // 每次进入重拉：默认模型可能在别处改过（聊天页切模型、Web 控制台），
                // 用 ensureLoaded 会一直显示上次进入时的值。
                LaunchedEffect(Unit) { viewModel.models.refresh() }
                io.qwenpaw.node.ui.settings.ModelsScreen(
                    state = modelsState,
                    onBack = { screen = Screen.SETTINGS },
                    onPickDefault = { scope, providerId, model ->
                        viewModel.models.setDefault(scope, providerId, model)
                    },
                    onTest = viewModel.models::test,
                    onRetry = viewModel.models::refresh,
                    onDismissNotice = viewModel.models::dismissNotice,
                )
            }

            Screen.SKILLS -> {
                // 每次进入重拉：技能可能在 Web 控制台改过，缓存的列表会停在旧状态。
                LaunchedEffect(Unit) { viewModel.skills.refresh() }
                io.qwenpaw.node.ui.skills.SkillsScreen(
                    state = skillsState,
                    onBack = { screen = Screen.SETTINGS },
                    onRefresh = viewModel.skills::refresh,
                    onOpenSkill = viewModel.skills::openSkill,
                    onOpenPoolSkill = viewModel.skills::openPoolSkill,
                    onCloseDetail = viewModel.skills::closeDetail,
                    onSetEnabled = viewModel.skills::setEnabled,
                    onBeginEdit = viewModel.skills::beginEdit,
                    onUpdateDraft = viewModel.skills::updateDraft,
                    onSaveEdit = viewModel.skills::saveEdit,
                    onCancelEdit = viewModel.skills::cancelEdit,
                    onCreate = viewModel.skills::create,
                    onDelete = { name -> viewModel.skills.delete(name) {} },
                    onDismissNotice = viewModel.skills::dismissNotice,
                    onImportZip = viewModel.skills::importZip,
                    onRetryImport = viewModel.skills::retryImport,
                    onDismissImportConflicts = viewModel.skills::dismissImportConflicts,
                    onOpenBroadcast = viewModel.skills::openBroadcast,
                    onToggleBroadcastTarget = viewModel.skills::toggleBroadcastTarget,
                    onRunBroadcast = viewModel.skills::runBroadcast,
                    onDismissBroadcast = viewModel.skills::dismissBroadcast,
                )
            }

            Screen.MCP -> {
                LaunchedEffect(Unit) { viewModel.mcp.refresh() }
                io.qwenpaw.node.ui.mcp.McpScreen(
                    state = mcpState,
                    onBack = { screen = Screen.SETTINGS },
                    onRefresh = viewModel.mcp::refresh,
                    onSelect = viewModel.mcp::select,
                    onCloseDetail = viewModel.mcp::closeDetail,
                    onToggle = viewModel.mcp::toggle,
                    onToggleTool = viewModel.mcp::toggleTool,
                    onSaveTools = viewModel.mcp::saveTools,
                    onSetDefaultEffect = viewModel.mcp::setDefaultEffect,
                    onSetToolEffect = viewModel.mcp::setToolEffect,
                    onSavePolicy = viewModel.mcp::savePolicy,
                    onRetryDetail = {
                        mcpState.selectedKey?.let(viewModel.mcp::select)
                    },
                    onDismissNotice = viewModel.mcp::dismissNotice,
                )
            }

            Screen.MARKET -> {
                io.qwenpaw.node.ui.market.MarketScreen(
                    state = marketState,
                    onBack = { screen = Screen.SETTINGS },
                    onEnsureLoaded = viewModel.market::ensureLoaded,
                    onSetTab = viewModel.market::setTab,
                    onQuery = viewModel.market::setQuery,
                    onCategory = viewModel.market::setCategory,
                    onSearch = viewModel.market::search,
                    onPluginQuery = viewModel.market::setPluginQuery,
                    onSearchPlugins = viewModel.market::searchPlugins,
                    onOpenResult = viewModel.market::openResult,
                    onInstallPluginFile = viewModel.market::installPluginFromFile,
                    onInstallPluginUrl = viewModel.market::installPluginFromUrl,
                    onForcePluginInstall = viewModel.market::forcePluginInstall,
                    onDismissPluginInstall = viewModel.market::dismissPluginInstall,
                    onImportSkill = viewModel.market::startHubInstall,
                    onCancelHubInstall = viewModel.market::cancelHubInstall,
                    onDismissHubInstall = viewModel.market::dismissHubInstall,
                    onDismissNotice = viewModel.market::dismissNotice,
                    onStopHubPolling = viewModel.market::stopHubPolling,
                    onConsumePrefill = viewModel.market::consumePrefill,
                )
            }

            Screen.ACL -> {
                LaunchedEffect(Unit) { viewModel.acl.refresh() }
                io.qwenpaw.node.ui.acl.AclScreen(
                    state = aclState,
                    onBack = { screen = Screen.SETTINGS },
                    onRefresh = viewModel.acl::refresh,
                    onSetTab = viewModel.acl::setTab,
                    onToggleSelected = viewModel.acl::toggleSelected,
                    onAct = viewModel.acl::act,
                    onRemark = viewModel.acl::remark,
                    onAddMember = { group, userId, remark, blacklist ->
                        if (blacklist) {
                            viewModel.acl.addBlacklist(group, userId, remark)
                        } else {
                            viewModel.acl.addMember(group, userId, remark)
                        }
                    },
                    onRemoveMember = viewModel.acl::removeMember,
                    onDismissNotice = viewModel.acl::dismissNotice,
                    currentAgentId = instances.firstOrNull { it.id == activeId }?.agentId
                        ?: "default",
                )
            }

            Screen.INBOX -> {
                io.qwenpaw.node.ui.inbox.InboxScreen(
                    state = inboxState,
                    onBack = { screen = Screen.LIST },
                    onRefresh = viewModel.inbox::refresh,
                    onLoadMore = viewModel.inbox::loadMore,
                    onMarkAllRead = viewModel.inbox::markAllRead,
                    onMarkRead = viewModel.inbox::markRead,
                    onDelete = viewModel.inbox::delete,
                    onDismissError = viewModel.inbox::dismissError,
                    onSourceFilter = viewModel.inbox::setSourceFilter,
                    onAgentFilter = viewModel.inbox::setAgentFilter,
                    onLoadAgents = viewModel.inbox::loadAgentOptions,
                )
            }

            Screen.TERMINAL -> {
                val terminalState by viewModel.terminal.state.collectAsState()
                io.qwenpaw.node.ui.terminal.TerminalScreen(
                    state = terminalState,
                    actions = io.qwenpaw.node.ui.terminal.TerminalActions(
                        open = viewModel.terminal::open,
                        chooseLocal = viewModel.terminal::chooseLocal,
                        chooseSsh = viewModel.terminal::chooseSsh,
                        chooseDirect = viewModel.terminal::chooseDirect,
                        switchSource = viewModel.terminal::switchSource,
                        dismissSource = viewModel.terminal::dismissSourceDialog,
                        newTab = viewModel.terminal::newTab,
                        selectTab = viewModel.terminal::selectTab,
                        closeActive = viewModel.terminal::closeActive,
                        onLeave = viewModel.terminal::onLeave,
                        respondHostKey = viewModel.terminal::respondHostKey,
                        clearHostKey = viewModel.terminal::clearHostKey,
                        dismissNotice = viewModel.terminal::dismissNotice,
                        dismissError = viewModel.terminal::dismissError,
                        reconnect = viewModel.terminal::reconnect,
                        selectShell = viewModel.terminal::selectShell,
                        setAsDefault = viewModel.terminal::setAsDefaultChecked,
                        setFontSize = viewModel.terminal::setFontSize,
                        claimPinchTip = viewModel.terminal::claimPinchTip,
                        renameTab = viewModel.terminal::renameTab,
                    ),
                    onBack = {
                        viewModel.terminal.onLeave()
                        screen = terminalOrigin
                    },
                )
            }

            Screen.GIT -> {
                val gitState by viewModel.git.state.collectAsState()
                io.qwenpaw.node.ui.git.GitScreen(
                    state = gitState,
                    actions = io.qwenpaw.node.ui.git.GitActions(
                        open = viewModel.git::open,
                        acknowledge = viewModel.git::acknowledgeNotice,
                        refresh = viewModel.git::load,
                        showDiff = viewModel.git::showDiff,
                        showCommitDiff = viewModel.git::showCommitDiff,
                        stage = viewModel.git::stage,
                        unstage = viewModel.git::unstage,
                        commit = viewModel.git::commit,
                        updateMessage = viewModel.git::updateMessage,
                        selectTab = viewModel.git::selectTab,
                        dismissDiff = viewModel.git::dismissDiff,
                        dismissError = viewModel.git::dismissError,
                        dismissNotice = viewModel.git::dismissNotice,
                    ),
                    onBack = { screen = Screen.WORKSPACE },
                )
            }

            Screen.SETTINGS -> settingsStateHolder.SaveableStateProvider("settings") {
                SettingsScreen(
                    instances = instances,
                    activeId = activeId,
                    themeMode = appState.themeMode,
                    uiScale = appState.uiScale,
                    chatScale = appState.chatScale,
                    useMiSans = appState.useMiSans,
                    compactList = appState.compactList,
                    keepScreenOn = appState.keepScreenOn,
                    appVersion = BuildConfig.VERSION_NAME,
                    onBack = { screen = Screen.LIST },
                    onOpenWorkspace = { screen = Screen.WORKSPACE },
                    onOpenTerminal = {
                        terminalOrigin = Screen.SETTINGS
                        screen = Screen.TERMINAL
                    },
                    onOpenBackup = { screen = Screen.BACKUP },
                    onOpenCheckpoint = { screen = Screen.CHECKPOINT },
                    onOpenModels = { screen = Screen.MODELS },
                    onOpenSkills = { screen = Screen.SKILLS },
                    onOpenMcp = { screen = Screen.MCP },
                    onOpenAccessControl = { screen = Screen.ACL },
                    onOpenMarket = { screen = Screen.MARKET },
                    onOpenUsage = {
                        usageOrigin = Screen.SETTINGS
                        viewModel.usage.ensureLoaded()
                        screen = Screen.TOKEN_USAGE
                    },
                    onOpenRunningConfig = { screen = Screen.RUNNING_CONFIG },
                    onOpenSecurity = { screen = Screen.SECURITY },
                    onOpenAgents = { screen = Screen.AGENTS },
                    onOpenRuntime = { screen = Screen.RUNTIME },
                    onOpenAppCenter = { screen = Screen.APP_CENTER },
                    onOpenChannels = { screen = Screen.CHANNEL },
                    onOpenLocalModels = { screen = Screen.LOCAL_MODELS },
                    onSwitch = { id -> viewModel.switchTo(id) },
                    onRemove = viewModel::remove,
                    onEditWithCredentials = viewModel::editInstanceWithCredentials,
                    onPickAgent = { pickingAgent = it },
                    addConnecting = appState.connecting,
                    addError = appState.connectError,
                    onLoadAgents = { hosts, user, pass, basic ->
                        viewModel.loadAgents(hosts, user, pass, basic)
                    },
                    onLoadAgentsFor = { viewModel.loadAgentsFor(it) },
                    scannedPair = appState.scannedPair,
                    onConsumeScannedPair = viewModel::consumeScannedPair,
                    onOpenScanner = { scanningPair = true },
                    onLoadAgentsWithToken = { hosts, token, basic ->
                        viewModel.loadAgentsWithToken(hosts, token, basic)
                    },
                    onVerifyHosts = { hosts, basic, onAttempt, token, instanceId ->
                        viewModel.verifyHosts(
                            hosts,
                            onAttempt,
                            instanceId = instanceId,
                            basicAuth = basic,
                            token = token,
                        )
                    },
                    onCheckAuth = { address -> viewModel.authRequired(address) },
                    onCheckLogin = { address, user, pass, basic ->
                        viewModel.checkLogin(address, user, pass, basic)
                    },
                    onChooseAddress = { id, url -> viewModel.chooseAddress(id, url) },
                    onChooseSessionAddress = { id, url ->
                        viewModel.chooseSessionAddress(id, url)
                    },
                    // 直接读 appState（由 collectAsState 提供）而不是调 ViewModel 上的普通函数：
                    // 后者返回的是当前值快照，Compose 观察不到它变化，用户点完「本次使用」
                    // 界面会停在「自动选择」。
                    sessionAddressOf = { id -> appState.sessionPicks[id] },
                    onVerifySingleAddress = { _, url ->
                        // 单条验证结果用 Toast 呈现：地址清单是设置页里的一个展开块，
                        // 没有位置放一行常驻结果，用 Toast 最不容易错过。
                        scope.launch {
                            val result = runCatching { viewModel.verifyHosts(listOf(url)) }.getOrNull()
                            toast(
                                context,
                                if (result == null) {
                                    context.getString(R.string.main_connect_failed_address)
                                } else {
                                    val version = result.version.takeIf { it.isNotBlank() }
                                    if (version == null) {
                                        context.getString(R.string.main_connect_ok)
                                    } else {
                                        context.getString(R.string.main_connect_ok_with_version, version)
                                    }
                                },
                            )
                        }
                    },
                    currentNetKind = netSnapshot.kind,
                    currentSsid = netSnapshot.ssid,
                    onAdd = { draft, onResult ->
                        viewModel.connect(draft, onResult)
                    },
                    onThemeMode = viewModel::setThemeMode,
                    onUiScale = viewModel::setUiScale,
                    onChatScale = viewModel::setChatScale,
                    onUseMiSans = viewModel::setUseMiSans,
                    onCompactList = viewModel::setCompactList,
                    onKeepScreenOn = viewModel::setKeepScreenOn,
                    appLanguage = appState.appLanguage ?: AppLanguage.ZH_CN,
                    onAppLanguage = { language ->
                        val changed = appState.appLanguage != language
                        viewModel.setAppLanguage(language)
                        if (changed) {
                            Texts.install(context)
                            (context as? android.app.Activity)?.recreate()
                        }
                    },
                    securityHintsEnabled = appState.securityHintsEnabled,
                    onSecurityHintsEnabled = viewModel::setSecurityHintsEnabled,
                    trustedHosts = appState.trustedHosts,
                    onAddTrustedHost = viewModel::addTrustedHost,
                    onRemoveTrustedHost = viewModel::removeTrustedHost,
                    isTrustedHost = viewModel::isTrustedHost,
                    sshTrustedHosts = appState.sshTrustedHosts,
                    onAddTrustedHostForSsh = viewModel::addTrustedHostForSsh,
                    onConfirmSshTrust = viewModel::confirmSshTrust,
                    bridgeStatus = appState.bridgeStatus,
                    onRefreshBridge = viewModel::refreshBridgeStatus,
                    bridgeFeaturesEnabled = appState.bridgeFeaturesEnabled,
                    onBridgeFeaturesEnabled = viewModel::setBridgeFeaturesEnabled,
                    onLogout = viewModel::logout,
                    voiceEnabled = appState.voiceEnabled,
                    onVoiceEnabled = { on ->
                        viewModel.setVoiceEnabled(on)
                        // 打开开关时先说明录音会上传到服务端，不再有模型下载这一步。
                        if (on) showVoiceIntro = true
                    },
                    voiceTrigger = appState.voiceTrigger,
                    onVoiceTrigger = viewModel::setVoiceTrigger,
                    voiceSource = appState.voiceSource,
                    onVoiceSource = { source ->
                        viewModel.setVoiceSource(source)
                        // 切到服务端转写时顺手探一次，切过去就能看到是否可用。
                        if (source == io.qwenpaw.node.ui.VoiceSource.SERVER) {
                            viewModel.refreshTranscribeStatus()
                        }
                    },
                    localAsrAvailable = localAsr.available,
                    localModel = localModel,
                    onDownloadModel = { scope.launch { localAsr.download() } },
                    onRemoveModel = { localAsr.remove() },
                    voiceAutoStop = appState.voiceAutoStop,
                    onVoiceAutoStop = viewModel::setVoiceAutoStop,
                    transcribeStatus = appState.transcribeStatus,
                    onRefreshTranscribe = { viewModel.refreshTranscribeStatus() },
                    assistantCollapse = appState.assistantCollapse,
                    onAssistantCollapse = viewModel::setAssistantCollapse,
                    fontState = fontState,
                    onDownloadFont = { viewModel.downloadFont() },
                    onRemoveFont = { viewModel.removeFont() },
                    logEnabled = appState.logEnabled,
                    onLogEnabled = viewModel::setLogEnabled,
                    logKinds = appState.logKinds,
                    onToggleLogKind = viewModel::toggleLogKind,
                    onOpenLogs = { screen = Screen.RUN_LOG },
                    onExportLogs = {
                        shareText(
                            context,
                            context.getString(R.string.main_export_log_file),
                            viewModel.exportLog(),
                        )
                    },
                    showMessageStats = appState.showMessageStats,
                    onShowMessageStats = viewModel::setShowMessageStats,
                    userName = appState.userName,
                    onUserName = viewModel::setUserName,
                    avatarStore = app.avatarStore,
                    avatarVersion = avatarVersion,
                    onPickAvatar = { kind ->
                        avatarTarget = kind
                        pickAvatar.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onRemoveAvatar = { kind ->
                        app.avatarStore.clear(kind)
                        toast(context, context.getString(R.string.main_avatar_removed))
                    },
                    useServerAvatar = appState.useServerAvatar,
                    onUseServerAvatar = viewModel::setUseServerAvatar,
                    serverAvatarFile = serverAvatarFile,
                    onRefreshServerAvatar = viewModel::refreshServerAvatar,
                    actionStyle = appState.actionStyle,
                    onActionStyle = viewModel::setActionStyle,
                    renderUserMarkdown = appState.renderUserMarkdown,
                    onRenderUserMarkdown = viewModel::setRenderUserMarkdown,
                    renderAssistantMarkdown = appState.renderAssistantMarkdown,
                    onRenderAssistantMarkdown = viewModel::setRenderAssistantMarkdown,
                    config = io.qwenpaw.node.ui.settings.SettingsConfig(
                        tools = toolsState,
                        envs = envsState,
                        offload = offloadState,
                        onLoadTools = { viewModel.settingsConfig.loadTools() },
                        onToggleTool = viewModel.settingsConfig::toggleTool,
                        onToolAsync = viewModel.settingsConfig::setToolAsync,
                        onSaveToolConfig = viewModel.settingsConfig::saveToolConfig,
                        onLoadEnvs = { viewModel.settingsConfig.loadEnvs() },
                        onSaveEnvs = viewModel.settingsConfig::saveEnvs,
                        onSaveAuthEnabled = viewModel.settingsConfig::saveAuthEnabled,
                        onDeleteEnv = viewModel.settingsConfig::deleteEnv,
                        onResetEnv = viewModel.settingsConfig::resetEnv,
                        onLoadOffload = { viewModel.settingsConfig.loadOffload() },
                        onSetOffload = viewModel.settingsConfig::setOffload,
                        runningConfig = runningConfigState,
                        security = securityState,
                        serverSettings = serverSettingsState,
                        onLoadRunningConfig = { viewModel.settingsConfig.loadRunningConfig() },
                        onLoadSecurity = { viewModel.settingsConfig.loadSecurity() },
                        onLoadServerSettings = { viewModel.settingsConfig.loadServerSettings() },
                        onSetServerLanguage = viewModel.settingsConfig::setServerLanguage,
                        cron = cronState,
                        onLoadCron = viewModel.cron::refresh,
                        onToggleCron = viewModel.cron::setEnabled,
                        onRunCron = viewModel.cron::runNow,
                        onPromoteCron = viewModel.cron::promote,
                        onDeleteCron = viewModel.cron::delete,
                        onLoadCronHistory = viewModel.cron::loadHistory,
                        dismissCronRowError = viewModel.cron::dismissRowError,
                        dismissCronError = viewModel.cron::dismissError,
                        onLoadCronTargets = { viewModel.cron.loadTargets() },
                        onEditCron = viewModel.cron::edit,
                        onSaveCron = viewModel.cron::save,
                    ),
                )
            }

            Screen.RUN_LOG -> {
                io.qwenpaw.node.ui.log.RunLogScreen(
                    entries = viewModel.logEntries,
                    enabled = appState.logEnabled,
                    kinds = appState.logKinds,
                    onToggleEnabled = viewModel::setLogEnabled,
                    onToggleKind = viewModel::toggleLogKind,
                    onClear = viewModel::clearLog,
                    onExport = {
                        shareText(
                            context,
                            context.getString(R.string.main_export_log_file),
                            viewModel.exportLog(),
                        )
                    },
                    onImport = { logImportLauncher.launch(arrayOf("text/plain", "*/*")) },
                    // 日志页从设置进入，返回也回设置，比直接跳聊天页更符合层级。
                    onBack = { screen = Screen.SETTINGS },
                )
            }
        }
        }

        // 撤销条浮在所有页面之上，且避开手势条。
        io.qwenpaw.node.ui.common.UndoBarHost(
            controller = undoController,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        )
    }

    // 启动时检测到公网地址未设防：弹一次，读过即止。
    //
    // 放在 Nav 层而不是聊天页，是因为它描述的是「这台服务器」而不是「这个对话」，
    // 用户切到列表或设置页时同样应该看到。
    appState.securityNotice?.let { notice ->
        io.qwenpaw.node.ui.settings.SecurityNoticeDialog(
            notice = notice,
            onDismiss = viewModel::dismissSecurityNotice,
        )
    }

    // 令牌模式下拿不到配对码：说明装什么、升级到什么版本，以及另一种取令牌的途径。
    appState.pairingNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPairingNotice,
            title = { Text(stringResource(R.string.pairing_notice_title)) },
            text = {
                Text(
                    when (notice.reason) {
                        io.qwenpaw.node.ui.PairingNotice.Reason.NO_BRIDGE ->
                            stringResource(R.string.pairing_notice_no_bridge)

                        io.qwenpaw.node.ui.PairingNotice.Reason.BRIDGE_TOO_OLD ->
                            stringResource(R.string.pairing_notice_too_old, notice.pluginVersion)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissPairingNotice()
                        viewModel.market.openInstallUrlDialog(BRIDGE_ZIP_URL)
                        screen = Screen.MARKET
                    },
                ) { Text(stringResource(R.string.pairing_notice_update)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPairingNotice) {
                    Text(stringResource(R.string.pairing_notice_dismiss))
                }
            },
        )
    }

    // 未装配套插件时的安装引导。跳市场并预填 URL，用户不必自己找下载地址。
    appState.bridgeGuide?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissBridgeGuide(mute = false) },
            title = { Text(stringResource(R.string.bridge_guide_title)) },
            text = { Text(stringResource(R.string.bridge_guide_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearBridgeGuide()
                        viewModel.market.openInstallUrlDialog(BRIDGE_ZIP_URL)
                        screen = Screen.MARKET
                    },
                ) { Text(stringResource(R.string.bridge_guide_install)) }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    if (prompt.askMute) {
                        TextButton(onClick = { viewModel.dismissBridgeGuide(mute = true) }) {
                            Text(stringResource(R.string.bridge_guide_mute))
                        }
                    }
                    TextButton(onClick = { viewModel.dismissBridgeGuide(mute = false) }) {
                        Text(stringResource(R.string.bridge_guide_later))
                    }
                }
            },
        )
    }

    pickingAgent?.let { instance ->
        AgentPickerDialog(
            currentAgentId = instance.agentId,
            // 用实例自带凭据拉列表，不必让用户重新输地址和密码。
            load = { viewModel.loadAgentsFor(instance) },
            onDismiss = { pickingAgent = null },
            onPick = { agentId ->
                viewModel.editInstance(instance.copy(agentId = agentId))
                pickingAgent = null
            },
        )
    }

    if (showVoiceIntro) {
        io.qwenpaw.node.ui.chat.VoiceIntroDialog(
            modelReady = localModel is io.qwenpaw.node.voice.LocalModelState.Ready,
            onDismiss = { showVoiceIntro = false },
            onDownload = {
                showVoiceIntro = false
                viewModel.setVoiceEnabled(true)
                scope.launch { localAsr.download() }
            },
            holdMode = appState.voiceTrigger == io.qwenpaw.node.ui.VoiceTrigger.HOLD,
        )
    }

    // 本地识别未下载模型：说明原因并引导去设置下载。
    if (showVoiceModelRequired) {
        io.qwenpaw.node.ui.chat.VoiceModelRequiredDialog(
            onGoToSettings = {
                showVoiceModelRequired = false
                screen = Screen.SETTINGS
            },
            onDismiss = {
                showVoiceModelRequired = false
                showHideVoiceButton = true
            },
        )
    }

    // 服务端转写不可用：说明原因。配置在服务端那一侧，本机没有可下载的东西。
    voiceUnavailable?.let { status ->
        io.qwenpaw.node.ui.chat.VoiceUnavailableDialog(
            status = status,
            onDismiss = {
                voiceUnavailable = null
                showHideVoiceButton = true
            },
        )
    }

    if (showHideVoiceButton) {
        io.qwenpaw.node.ui.chat.HideVoiceButtonDialog(
            onHide = {
                showHideVoiceButton = false
                viewModel.setVoiceEnabled(false)
            },
            onKeep = { showHideVoiceButton = false },
        )
    }
}

/**
 * 把选中的媒体复制到缓存目录并上传到服务端，返回服务端保存的路径。
 *
 * 文件名必须带扩展名。系统相册给出的 uri 末段是 `..._1000000158` 这类纯数字串，
 * 直接拿去上传，服务端也会原样返回一个没有扩展名的路径；而客户端随后要靠
 * `isImageName()` 按扩展名判断该发 `image` 块还是 `file` 块，判否就会把图片
 * 当成普通文件发出去，模型只能看到「一个文件」而看不到图像。
 *
 * 因此这里先向 ContentResolver 要真实显示名，拿不到再按 MIME 补扩展名。
 */
private suspend fun uploadAttachment(
    context: android.content.Context,
    app: QwenPawApp,
    uri: Uri,
): String? = withContext(Dispatchers.IO) {
    val instance = app.instanceRepository.active() ?: return@withContext null
    val conn = io.qwenpaw.node.data.remote.Connection(
        app.instanceRepository.resolvedBaseUrl(instance),
        instance.token,
        instance.agentId,
        instance.basicAuth,
    )
    val mime = context.contentResolver.getType(uri)
    val name = io.qwenpaw.node.ui.common.uploadFileName(context, uri)
    val temp = File(context.cacheDir, name.take(80))
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        app.api.upload(conn, temp, mime).url
    }.getOrNull().also { temp.delete() }
}

/** 轻量提示，避免为一次性反馈引入 Snackbar 状态机。 */
private fun toast(context: android.content.Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * 检查点的只读对话页。
 *
 * 单独抽成组件是因为页面里有两段 `LaunchedEffect`：一段拉历史，一段在
 * `viewingSession` 被清空时通知上层退出。放进 `when` 分支内联会与
 * `AnimatedContent` 的作用域纠缠，抽出来后焦点只有一个子节点，进出也更干净。
 */
@Composable
private fun CheckpointChatPane(
    viewing: io.qwenpaw.node.data.model.CheckpointSessionDto,
    checkpointState: io.qwenpaw.node.ui.checkpoint.CheckpointUiState,
    chatState: ChatUiState,
    chatVm: io.qwenpaw.node.ui.chat.ChatViewModel,
    imageLoader: io.qwenpaw.node.ui.chat.ChatImageLoader?,
    connection: io.qwenpaw.node.data.remote.Connection?,
    appearance: io.qwenpaw.node.ui.chat.MessageAppearance,
    assistantCollapse: io.qwenpaw.node.ui.chat.AssistantCollapse,
    renderUserMarkdown: Boolean,
    renderAssistantMarkdown: Boolean,
    onBack: () -> Unit,
    snapshotName: (String) -> Unit,
    submitSnapshot: () -> Unit,
    dismissSnapshot: () -> Unit,
) {
    val chats = chatState.chats + chatState.archivedChats
    // 检查点的会话标识来自 history.db，对话列表里的 id 是对话 id，两者可能不同。
    // 用会话标识与其 id 双路匹配，找不到时只显示提示，不阻止创建快照。
    val resolvedId = io.qwenpaw.node.ui.chat.resolveChatId(chats, viewing.sessionId)
    val found = chats.any { it.id == resolvedId || it.sessionId == viewing.sessionId }
    LaunchedEffect(resolvedId) {
        if (found) chatVm.openChat(resolvedId)
    }
    var snapshotDialogOpen by remember { mutableStateOf(false) }
    io.qwenpaw.node.ui.checkpoint.CheckpointChatScreen(
        state = chatState,
        sessionTitle = viewing.title.ifBlank { viewing.sessionKey.take(8) },
        chatMissing = !found,
        creating = checkpointState.snapshotPending,
        error = checkpointState.snapshotError,
        imageLoader = imageLoader,
        connection = connection,
        appearance = appearance,
        assistantCollapse = assistantCollapse,
        renderUserMarkdown = renderUserMarkdown,
        renderAssistantMarkdown = renderAssistantMarkdown,
        onBack = onBack,
        onSnapshot = { snapshotDialogOpen = true },
        onRefresh = { chatVm.openChat(resolvedId) },
    )
    val snapshotSession = checkpointState.snapshotSession
    if (snapshotDialogOpen && snapshotSession != null) {
        io.qwenpaw.node.ui.checkpoint.CheckpointSnapshotDialog(
            session = snapshotSession,
            name = checkpointState.snapshotName,
            pending = checkpointState.snapshotPending,
            error = checkpointState.snapshotError,
            onNameChange = snapshotName,
            onConfirm = submitSnapshot,
            onDismiss = {
                snapshotDialogOpen = false
                dismissSnapshot()
            },
        )
    }
}

/**
 * 通过系统分享发送纯文本。
 *
 * 分享的是内容而不是文件：不需要 FileProvider，也不会在用户存储里留下副本。
 * `EXTRA_SUBJECT` 给接收方一个可用的标题（如「某某对话.md」），
 * 用户仍能在分享面板里自行选择目标应用。
 */
private fun shareText(context: android.content.Context, subject: String, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        android.content.Intent.createChooser(send, context.getString(R.string.main_share_chooser)),
    )
}

/**
 * 页面的上一层。
 *
 * `usageOrigin` 只影响用量统计：它有两个入口（对话列表顶栏与设置页），
 * 固定回列表会让「设置 → 用量 → 返回」跳过设置页那一层。
 */
private fun parentOf(screen: Screen, usageOrigin: Screen): Screen? = when (screen) {
    Screen.CHAT -> null
    Screen.LIST -> Screen.CHAT
    Screen.SETTINGS, Screen.ARCHIVED, Screen.INBOX, Screen.SEARCH -> Screen.LIST
    Screen.TOKEN_USAGE -> usageOrigin
    Screen.RUN_LOG -> Screen.SETTINGS
    // 工作区文件、备份、检查点、模型与提供商、技能、MCP、扩展市场都从设置页进入，返回也回设置页。
    Screen.WORKSPACE, Screen.BACKUP, Screen.CHECKPOINT, Screen.MODELS,
    Screen.SKILLS, Screen.MCP, Screen.MARKET, Screen.RUNNING_CONFIG, Screen.SECURITY,
    Screen.AGENTS, Screen.RUNTIME, Screen.PORTABILITY, Screen.APP_CENTER,
    Screen.CHANNEL, Screen.LOCAL_MODELS,
    Screen.ACL -> Screen.SETTINGS
    Screen.TERMINAL -> Screen.SETTINGS
    // Git 从工作区文件页顶栏进入，返回也回工作区。
    Screen.GIT -> Screen.WORKSPACE
    // 检查点的只读对话页是检查点的下一层，返回直接回到检查点。
    Screen.CHECKPOINT_CHAT -> Screen.CHECKPOINT
    // 原文页从聊天页的气泡进入，返回也回聊天页。
    Screen.MESSAGE_SOURCE -> Screen.CHAT
}
