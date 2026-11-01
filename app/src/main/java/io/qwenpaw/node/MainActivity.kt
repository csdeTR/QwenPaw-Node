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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
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
private enum class Screen { CHAT, LIST, SETTINGS, ARCHIVED, TOKEN_USAGE, RUN_LOG, MESSAGE_SOURCE }

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
    // 原文页要显示的那条消息。与 Screen 一起构成页面状态，
    // 单独存一份而不是塞进 Screen 枚举的参数里，是为了让枚举本身保持无载荷。
    var sourceText by remember { mutableStateOf<String?>(null) }
    val lastChatId by viewModel.lastChatId.collectAsState()

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
    // 对话图标版本：换完图标返回列表，行据此重新读图。
    val iconVersion by app.chatIconStore.version.collectAsState()
    // 服务端头像的本地缓存文件，冷启动直接读盘，联网后再刷新。
    val serverAvatarFile by viewModel.serverAvatarFile.collectAsState()

    val messageAppearance = io.qwenpaw.node.ui.chat.MessageAppearance(
        avatarStore = app.avatarStore,
        avatarVersion = avatarVersion,
        userName = appState.userName,
        agentName = connection?.agentId ?: "智能体",
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
    val modelManager = remember(context) { io.qwenpaw.node.voice.SpeechModelManager(context) }
    val modelState by modelManager.state.collectAsState()

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
                toast(context, "无法读取该图片")
                return@launch
            }
            val decoded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.qwenpaw.node.avatar.AvatarIntake.decode(bytes)
            }
            if (decoded == null) {
                toast(context, "该图片无法解析")
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
    // 按层级后退而不是一律回聊天页：从列表进设置再返回时应当回到列表，
    // 否则「设置盖住列表」的层级关系在返回时被跳过，滑向也就无从推导。
    BackHandler(enabled = appState.connected && screen != Screen.CHAT) {
        parentOf(screen)?.let { screen = it }
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
            if (failed > 0) toast(context, "$failed 个附件上传失败，请重试")
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
                    if (!saved) "保存失败，请重试" else if (chatId != null) "图标已保存" else "头像已保存",
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
                    val forward = parentOf(targetState) == initialState
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
                            if (!ok) toast(context, "新建分组失败，请重试")
                        }
                    },
                    onRenameGroup = chatVm::renameGroup,
                    onDeleteGroup = chatVm::deleteGroup,
                    iconStore = app.chatIconStore,
                    iconVersion = iconVersion,
                    onPickIconImage = { chatId ->
                        pendingIconChatId = chatId
                        pickChatIcon.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
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
                    // 左上角按钮打开对话列表（附加页），不是在页面栈里返回。
                    onBack = { screen = Screen.LIST },
                    voiceHoldMode = appState.voiceTrigger == io.qwenpaw.node.ui.VoiceTrigger.HOLD,
                    voiceAutoStop = appState.voiceAutoStop,
                    assistantCollapse = appState.assistantCollapse,
                    onDraftChange = chatVm::updateDraft,
                    onSend = { chatVm.send() },
                    onStop = chatVm::stop,
                    onAttach = { pickFiles.launch(Unit) },
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
                        val name = chatVmState.currentChatName.ifBlank { "对话" }.take(40)
                        shareText(context, "$name.md", chatVm.exportMarkdown())
                    },
                    onSetThinking = { level -> chatVm.setThinking(level) },
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
                    onBack = { screen = Screen.LIST },
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
                        shareText(context, "QwenPaw 运行日志.txt", viewModel.exportLog())
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
                    useServerAvatar = appState.useServerAvatar,
                    onUseServerAvatar = viewModel::setUseServerAvatar,
                    serverAvatarFile = serverAvatarFile,
                    onRefreshServerAvatar = viewModel::refreshServerAvatar,
                    actionStyle = appState.actionStyle,
                    onActionStyle = viewModel::setActionStyle,
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
                        shareText(context, "QwenPaw 运行日志.txt", viewModel.exportLog())
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
    context.startActivity(android.content.Intent.createChooser(send, "分享到"))
}

/** 页面的上一层。null 表示已在主页面，再按返回应退出应用。 */
private fun parentOf(screen: Screen): Screen? = when (screen) {
    Screen.CHAT -> null
    Screen.LIST -> Screen.CHAT
    Screen.SETTINGS, Screen.ARCHIVED, Screen.TOKEN_USAGE -> Screen.LIST
    Screen.RUN_LOG -> Screen.SETTINGS
    // 原文页从聊天页的气泡进入，返回也回聊天页。
    Screen.MESSAGE_SOURCE -> Screen.CHAT
}
