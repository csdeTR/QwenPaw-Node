package io.qwenpaw.node

import android.Manifest
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.AppVisibility
import io.qwenpaw.node.ui.AppViewModel
import io.qwenpaw.node.ui.chat.ChatListScreen
import io.qwenpaw.node.ui.chat.ChatScreen
import io.qwenpaw.node.ui.chat.ChatUiState
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

            // 通知权限（Android 13+），用于后台回复完成提示。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
}

// 枚举顺序即页面层级：聊天页是主页面，其余页面按深度排在其后，
// 用于 AnimatedContent 判断进入方向是「深入」还是「返回」。
private enum class Screen { CHAT, LIST, SETTINGS, ARCHIVED, TOKEN_USAGE, RUN_LOG }

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatVmState = chatState
    var pickingAgent by remember { mutableStateOf<io.qwenpaw.node.data.local.InstanceConfig?>(null) }
    val lastChatId by viewModel.lastChatId.collectAsState()

    // 会话切换后记下来，下次启动恢复到这个会话。
    LaunchedEffect(chatVmState.currentChatId) {
        viewModel.rememberLastChat(chatVmState.currentChatId)
    }

    // ChatViewModel 的轻量提示（归档 / 删除 / 批量操作 / 新审批）。
    //
    // 这里必须有人订阅：notifications 是带缓冲的 SharedFlow，没有收集方时
    // tryEmit 的返回值被丢弃，用户点完归档只会看到会话消失、没有任何反馈。
    // 放在 Nav 层而不是某个页面里，是因为这些操作在列表、聊天页、已归档页都可能触发。
    LaunchedEffect(chatVm) {
        chatVm.notifications.collect { message -> toast(context, message) }
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
            )
        }
    }
    val imageLoader = remember { io.qwenpaw.node.ui.chat.ChatImageLoader() }
    val tokenUsage by viewModel.tokenUsage.collectAsState()
    val tokenUsageLoading by viewModel.tokenUsageLoading.collectAsState()
    val tokenUsageError by viewModel.tokenUsageError.collectAsState()
    val fontState by viewModel.fontState.collectAsState()
    // 当前网络类型与 WiFi 名称：地址编辑区据此提示「这条现在会被优先使用」。
    val netSnapshot by app.networkMonitor.current.collectAsState()
    // 头像文件版本：设置里换完头像返回聊天页，靠它触发重读。
    val avatarVersion by app.avatarStore.version.collectAsState()

    val messageAppearance = io.qwenpaw.node.ui.chat.MessageAppearance(
        avatarStore = app.avatarStore,
        avatarVersion = avatarVersion,
        userName = appState.userName,
        agentName = connection?.agentId ?: "智能体",
        showStats = appState.showMessageStats,
    )

    // 导出 Markdown：先让系统选择落盘位置，拿到 uri 后再写内容。
    //
    // 文本必须放进 rememberSaveable：选文件时系统会短暂把 Activity 推到后台，
    // 低内存下可能被杀掉重建，普通 remember 的文本会变成 null，
    // 回调里就会静默早退——用户看到的表现正是「点了导出什么都没发生」。
    var pendingExportText by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportName by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val text = pendingExportText
        pendingExportText = null
        val name = pendingExportName.orEmpty()
        pendingExportName = null
        if (uri == null) {
            toast(context, "已取消导出")
            return@rememberLauncherForActivityResult
        }
        if (text == null) {
            toast(context, "导出内容已丢失，请重新执行导出")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val error = writeTextToUri(context, uri, text)
            toast(
                context,
                if (error == null) {
                    "已导出 $name"
                } else {
                    "导出失败：$error"
                },
            )
        }
    }

    val speech = remember(context) { io.qwenpaw.node.voice.VoiceInput(context) }
    val modelManager = remember(context) { io.qwenpaw.node.voice.SpeechModelManager(context) }
    val modelState by modelManager.state.collectAsState()

    // 日志导出：与 Markdown 导出同一套「先选位置再写」的流程，
    // 文本同样必须放 rememberSaveable，否则 Activity 被重建后会静默丢失。
    var pendingLogText by rememberSaveable { mutableStateOf<String?>(null) }
    val logExportLauncher = rememberLauncherForActivityResult(
        // 用 text/plain：日志是纯文本，标成 markdown 会让部分文件管理器
        // 把它当文档类文件处理，用户不容易在「下载」里找到。
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingLogText
        pendingLogText = null
        if (uri == null) {
            toast(context, "已取消导出")
            return@rememberLauncherForActivityResult
        }
        if (text == null) {
            toast(context, "导出内容已丢失，请重新执行导出")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val error = writeTextToUri(context, uri, text)
            toast(context, if (error == null) "已导出运行日志" else "导出失败：$error")
        }
    }

    // 日志导入：读外部文本后替换日志页内容，用于查看别人分享的日志。
    val logImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            toast(context, "已取消导入")
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
                toast(context, "无法读取该文件")
            } else {
                viewModel.importLog(text)
                toast(context, "已载入日志")
            }
        }
    }
    val voiceState by speech.state.collectAsState()
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
                toast(context, "无法读取该图片")
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
                    if (saved) "动图已保存，头像中仅显示首帧" else "保存失败，请重试",
                )
                return@launch
            }
            val decoded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.qwenpaw.node.avatar.AvatarIntake.decode(bytes)
            }
            if (decoded == null) {
                toast(context, "该图片无法解析")
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
    // 归档提示按自然日抑制。用 state 而非每次直接读 prefs，
    // 勾选后当次就能立刻生效，不必等下一次重组。
    var archiveTipVisible by remember { mutableStateOf(viewModel.shouldShowArchiveTip()) }
    // 点击麦克风但模型未下载：第一级提示。
    var showVoiceModelRequired by remember { mutableStateOf(false) }
    // 第一级选了「关闭」后，第二级询问是否顺带隐藏按钮。
    var showHideVoiceButton by remember { mutableStateOf(false) }

    // 「点一下」模式且开了自动停止时才启用端点检测；按住说话松手即停，不需要。
    fun voiceAutoStopNow(): Boolean =
        appState.voiceTrigger == io.qwenpaw.node.ui.VoiceTrigger.TAP && appState.voiceAutoStop

    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            speech.start(
                model = modelManager.installedDir(),
                onPartial = {},
                onFinal = { text -> chatVm.updateDraft(text) },
                onError = {},
                autoStop = voiceAutoStopNow(),
            )
        }
    }

    fun startVoice() {
        // 模型是使用前提。未下载时先弹两级提示，不要静默失败。
        if (modelState !is io.qwenpaw.node.voice.ModelState.Ready) {
            showVoiceModelRequired = true
            return
        }
        if (!speech.hasPermission()) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        speech.start(
            model = modelManager.installedDir(),
            onPartial = {},
            onFinal = { text -> chatVm.updateDraft(text) },
            onError = {},
            autoStop = voiceAutoStopNow(),
        )
    }

    // 模型加载要几秒，提前在后台预热，按下麦克风时才能立即出声。
    // 端点检测会改变识别器配置，两种配置各预热一份，切换设置后不必等第一次加载。
    LaunchedEffect(appState.voiceEnabled, modelState, appState.voiceTrigger, appState.voiceAutoStop) {
        if (appState.voiceEnabled &&
            modelState is io.qwenpaw.node.voice.ModelState.Ready &&
            speech.hasPermission()
        ) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                io.qwenpaw.node.voice.StreamingRecognizer.create(
                    modelManager.installedDir(),
                    16_000,
                    endpointing = voiceAutoStopNow(),
                )
            }
        }
    }

    // 屏幕是 Compose 内部状态，系统返回键默认会直接退出 Activity。
    // 聊天页是主页面：从附加页返回聊天页，在聊天页再按一次才退出。
    BackHandler(enabled = appState.connected && screen != Screen.CHAT) {
        screen = Screen.CHAT
    }

    // 日志页是设置的子页面，返回应先回设置而不是直接跳聊天页。
    // 这个 BackHandler 声明在后，Compose 会优先分发给最后注册的那个。
    BackHandler(enabled = screen == Screen.RUN_LOG) {
        screen = Screen.SETTINGS
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val url = uploadAttachment(context, app, uri)
                if (url != null) chatVm.addAttachment(url)
            }
        }
    }

    if (!appState.connected) {
        ConnectScreen(
            connecting = appState.connecting,
            error = appState.connectError,
            onConnect = { draft ->
                viewModel.connect(draft)
            },
            onLoadAgents = { hosts, user, pass -> viewModel.loadAgents(hosts, user, pass) },
            onVerifyHosts = { hosts, onAttempt -> viewModel.verifyHosts(hosts, onAttempt) },
            onCheckAuth = { address -> viewModel.authRequired(address) },
            saved = instances,
            onUseSaved = { viewModel.useSaved(it) },
        )
        return
    }

    // 头像裁剪是一个全屏模态层：放在页面切换之前，盖住任何页面。
    // 用早返回而不是塞进 Screen 枚举，是因为裁剪属于「选图的后续」，
    // 不该出现在页面栈里，也不该被 AnimatedContent 的滑动动画带动。
    val pendingCrop = cropping
    if (pendingCrop?.bitmap != null) {
        val kind = croppingKind
        io.qwenpaw.node.avatar.AvatarCropScreen(
            bitmap = pendingCrop.bitmap!!,
            onCancel = {
                cropping = null
                croppingKind = null
            },
            onSave = { bitmap ->
                val saved = app.avatarStore.saveStatic(kind.orEmpty(), bitmap)
                cropping = null
                croppingKind = null
                toast(context, if (saved) "头像已保存" else "保存失败，请重试")
            },
        )
        return
    }

    // 页面切换：淡入 + 从右侧轻推进入，返回时从左侧轻推，
    // 比纯 Crossfade 的平面淡入淡出更有「层级方向」感。
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState.ordinal >= initialState.ordinal
            val enter = fadeIn(tween(200)) +
                slideInHorizontally(tween(240)) { it / (if (forward) 6 else -6) }
            val exit = fadeOut(tween(160)) +
                slideOutHorizontally(tween(240)) { it / (if (forward) -6 else 6) }
            enter togetherWith exit
        },
        label = "screen",
    ) { current ->
        when (current) {
            Screen.LIST -> {
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
                        viewModel.loadTokenUsage()
                        screen = Screen.TOKEN_USAGE
                    },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    showArchiveTip = archiveTipVisible,
                    onSuppressArchiveTip = {
                        viewModel.suppressArchiveTipToday()
                        archiveTipVisible = false
                    },
                    onRefresh = {
                        // 列表页要的是列表本身，不是当前会话：这里直接刷新会话列表。
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
                            if (!ok) toast(context, "新建分组失败，请重试")
                        }
                    },
                    onRenameGroup = chatVm::renameGroup,
                    onDeleteGroup = chatVm::deleteGroup,
                )
            }

            Screen.CHAT -> {
                ChatScreen(
                    state = chatVmState,
                    voiceState = voiceState,
                    voicePartial = voicePartial,
                    // 可见性只看开关；模型是否就绪另行传入，用于点击时的提示与弱化显示。
                    voiceVisible = appState.voiceEnabled,
                    voiceReady = modelState is io.qwenpaw.node.voice.ModelState.Ready,
                    imageLoader = imageLoader,
                    connection = connection,
                    // 左上角按钮打开会话列表（附加页），不是在页面栈里返回。
                    onBack = { screen = Screen.LIST },
                    voiceHoldMode = appState.voiceTrigger == io.qwenpaw.node.ui.VoiceTrigger.HOLD,
                    voiceAutoStop = appState.voiceAutoStop,
                    assistantCollapse = appState.assistantCollapse,
                    onDraftChange = chatVm::updateDraft,
                    onSend = { chatVm.send() },
                    onStop = chatVm::stop,
                    onAttach = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    },
                    onVoiceStart = { startVoice() },
                    onVoiceStop = { speech.stop() },
                    onVoiceCancel = { speech.stop() },
                    onRemoveAttachment = chatVm::removeAttachment,
                    onApprove = chatVm::approve,
                    onDeny = chatVm::deny,
                    onRetry = chatVm::retry,
                    onRename = { name ->
                        chatVmState.currentChatId?.let { chatVm.renameChat(it, name) }
                    },
                    onDelete = {
                        chatVmState.currentChatId?.let { chatVm.deleteChat(it) }
                        // 删掉后回到聊天页，ViewModel 会自动接上一个会话。
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
                        pendingExportText = chatVm.exportMarkdown()
                        pendingExportName =
                            "${chatVmState.currentChatName.ifBlank { "会话" }.take(40)}.md"
                        exportLauncher.launch(pendingExportName.orEmpty())
                    },
                    onSetThinking = { level -> chatVm.setThinking(level) },
                    onSetGroup = { groupId ->
                        chatVmState.currentChatId?.let { chatVm.setChatGroup(it, groupId) }
                    },
                    onPickModel = { providerId, model -> chatVm.setModel(providerId, model) },
                    onPickApproval = { level ->
                        // 审批模式是智能体级配置，改动会影响该智能体的全部会话。
                        (connection?.agentId ?: "default").let { agentId ->
                            chatVm.setApprovalLevel(agentId, level)
                        }
                    },
                    onSetProjectDir = { path -> chatVm.setProjectDir(path) },
                    onDismissError = chatVm::clearError,
                    // 顶栏「连接中断，点击重连」走这条：重绑地址 + 重载当前会话。
                    onRefresh = { viewModel.refreshAll() },
                    appearance = messageAppearance,
                )
            }

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
                )
            }

            Screen.TOKEN_USAGE -> {
                io.qwenpaw.node.ui.chat.TokenUsageScreen(
                    usage = tokenUsage,
                    loading = tokenUsageLoading,
                    error = tokenUsageError,
                    onBack = { screen = Screen.LIST },
                    onRetry = { viewModel.loadTokenUsage() },
                )
            }

            Screen.SETTINGS -> {
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
                    onBack = { screen = Screen.CHAT },
                    onSwitch = { id ->
                        viewModel.switchTo(id)
                        screen = Screen.CHAT
                    },
                    onRemove = viewModel::remove,
                    onEdit = viewModel::editInstance,
                    onPickAgent = { pickingAgent = it },
                    addConnecting = appState.connecting,
                    addError = appState.connectError,
                    onLoadAgents = { hosts, user, pass ->
                        viewModel.loadAgents(hosts, user, pass)
                    },
                    onLoadAgentsFor = { viewModel.loadAgentsFor(it) },
                    onVerifyHosts = { hosts, onAttempt -> viewModel.verifyHosts(hosts, onAttempt) },
                    onCheckAuth = { address -> viewModel.authRequired(address) },
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
                                    "无法连接该地址"
                                } else {
                                    "连接正常" + result.version.takeIf { it.isNotBlank() }
                                        ?.let { "，服务端 $it" }.orEmpty()
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
                    voiceEnabled = appState.voiceEnabled,
                    onVoiceEnabled = { on ->
                        viewModel.setVoiceEnabled(on)
                        // 打开开关但模型还没下载，先说明再引导下载。
                        if (on && modelState !is io.qwenpaw.node.voice.ModelState.Ready) {
                            showVoiceIntro = true
                        }
                    },
                    voiceTrigger = appState.voiceTrigger,
                    onVoiceTrigger = viewModel::setVoiceTrigger,
                    voiceAutoStop = appState.voiceAutoStop,
                    onVoiceAutoStop = viewModel::setVoiceAutoStop,
                    assistantCollapse = appState.assistantCollapse,
                    onAssistantCollapse = viewModel::setAssistantCollapse,
                    modelState = modelState,
                    onDownloadModel = {
                        scope.launch { modelManager.download() }
                    },
                    onRemoveModel = { modelManager.remove() },
                    fontState = fontState,
                    onDownloadFont = { viewModel.downloadFont() },
                    onRemoveFont = { viewModel.removeFont() },
                    logEnabled = appState.logEnabled,
                    onLogEnabled = viewModel::setLogEnabled,
                    logKinds = appState.logKinds,
                    onToggleLogKind = viewModel::toggleLogKind,
                    onOpenLogs = { screen = Screen.RUN_LOG },
                    onExportLogs = {
                        pendingLogText = viewModel.exportLog()
                        logExportLauncher.launch("qwenpaw-node-log.txt")
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
                        toast(context, "已移除头像")
                    },
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
                        pendingLogText = viewModel.exportLog()
                        logExportLauncher.launch("qwenpaw-node-log.txt")
                    },
                    onImport = { logImportLauncher.launch(arrayOf("text/plain", "*/*")) },
                    // 日志页从设置进入，返回也回设置，比直接跳聊天页更符合层级。
                    onBack = { screen = Screen.SETTINGS },
                )
            }
        }
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
            modelReady = modelState is io.qwenpaw.node.voice.ModelState.Ready,
            onDismiss = { showVoiceIntro = false },
            onDownload = {
                showVoiceIntro = false
                viewModel.setVoiceEnabled(true)
                scope.launch { modelManager.download() }
            },
            holdMode = appState.voiceTrigger == io.qwenpaw.node.ui.VoiceTrigger.HOLD,
        )
    }

    // 点击麦克风但模型未下载：第一级说明原因，并给出「去设置下载」。
    if (showVoiceModelRequired) {
        io.qwenpaw.node.ui.chat.VoiceModelRequiredDialog(
            onGoToSettings = {
                showVoiceModelRequired = false
                screen = Screen.SETTINGS
            },
            // 选「关闭」不是终点：紧接着问要不要顺带隐藏按钮，
            // 把「这次不用」与「以后都不显示」分开处理。
            onDismiss = {
                showVoiceModelRequired = false
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
    )
    val mime = context.contentResolver.getType(uri)
    val name = uploadFileName(context, uri, mime)
    val temp = File(context.cacheDir, name.take(80))
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        app.api.upload(conn, temp, mime).url
    }.getOrNull().also { temp.delete() }
}

/** 上传时使用的文件名，尽量保证带扩展名。 */
private fun uploadFileName(context: android.content.Context, uri: Uri, mime: String?): String {
    val display = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return io.qwenpaw.node.data.remote.UploadNames.resolve(
        displayName = display,
        uriLastSegment = uri.lastPathSegment,
        mime = mime,
    )
}

/** 轻量提示，避免为一次性反馈引入 Snackbar 状态机。 */
private fun toast(context: android.content.Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * 导出 Markdown 到用户选择的位置（由 CreateDocument launcher 真正落盘）。
 *
 * 成功返回 null，失败返回可读的原因。**不能只返回 Boolean**——用户之前报的
 * 「无论如何都弹失败」需要看到具体原因，否则无从判断是权限、空间还是 uri 问题。
 */
private suspend fun writeTextToUri(
    context: android.content.Context,
    uri: Uri,
    text: String,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val stream = context.contentResolver.openOutputStream(uri)
            ?: return@runCatching "无法打开所选位置"
        stream.use { output ->
            output.write(text.toByteArray())
            output.flush()
        }
        null
    }.getOrElse { e -> e.message ?: e::class.java.simpleName }
}
