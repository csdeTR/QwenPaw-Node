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
                fontScale = appState.fontScale,
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

private enum class Screen { LIST, CHAT, SETTINGS, ARCHIVED, TOKEN_USAGE }

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
    var screen by remember { mutableStateOf(Screen.LIST) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatVmState = chatState
    var pickingAgent by remember { mutableStateOf<io.qwenpaw.node.data.local.InstanceConfig?>(null) }

    // 当前实例对应的连接，图片加载与导出都要用。
    val connection = remember(instances, activeId) {
        instances.firstOrNull { it.id == activeId }?.let {
            io.qwenpaw.node.data.remote.Connection(it.baseUrl, it.token, it.agentId)
        }
    }
    val imageLoader = remember { io.qwenpaw.node.ui.chat.ChatImageLoader() }
    val tokenUsage by viewModel.tokenUsage.collectAsState()
    val tokenUsageLoading by viewModel.tokenUsageLoading.collectAsState()
    val tokenUsageError by viewModel.tokenUsageError.collectAsState()
    val fontState by viewModel.fontState.collectAsState()

    // 导出 Markdown：先让系统选择落盘位置，拿到 uri 后再写内容。
    var pendingExportText by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val text = pendingExportText
        pendingExportText = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val done = writeTextToUri(context, uri, text)
            toast(context, if (done) "已导出 Markdown" else "导出失败")
        }
    }

    val speech = remember(context) { io.qwenpaw.node.voice.VoiceInput(context) }
    val modelManager = remember(context) { io.qwenpaw.node.voice.SpeechModelManager(context) }
    val modelState by modelManager.state.collectAsState()
    val voiceState by speech.state.collectAsState()
    val voicePartial by speech.partial.collectAsState()
    var showVoiceIntro by remember { mutableStateOf(false) }

    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val dir = modelManager.installedDir()
            speech.start(
                model = dir,
                onPartial = {},
                onFinal = { text -> chatVm.updateDraft(text) },
                onError = {},
            )
        }
    }

    fun startVoice() {
        if (!speech.hasPermission()) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        speech.start(
            model = modelManager.installedDir(),
            onPartial = {},
            onFinal = { text -> chatVm.updateDraft(text) },
            onError = {},
        )
    }

    // 模型加载要几秒，提前在后台预热，按下麦克风时才能立即出声。
    LaunchedEffect(appState.voiceEnabled, modelState) {
        if (appState.voiceEnabled &&
            modelState is io.qwenpaw.node.voice.ModelState.Ready &&
            speech.hasPermission()
        ) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                io.qwenpaw.node.voice.StreamingRecognizer.create(
                    modelManager.installedDir(),
                    16_000,
                )
            }
        }
    }

    // 屏幕是 Compose 内部状态，系统返回键默认会直接退出 Activity。
    // 这里接管：聊天页/设置页返回列表，列表页才允许退出。
    BackHandler(enabled = appState.connected && screen != Screen.LIST) {
        screen = Screen.LIST
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
            onConnect = { host, user, pass, agent ->
                viewModel.connect(host, user, pass, agent)
            },
            onLoadAgents = { host, user, pass -> viewModel.loadAgents(host, user, pass) },
            saved = instances,
            onUseSaved = { viewModel.useSaved(it) },
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
                    onCopyAll = { chatId ->
                        scope.launch {
                            val text = copyChatToClipboard(context, chatVm, app, chatId)
                            if (text != null) {
                                toast(context, "已复制全部内容")
                            } else {
                                toast(context, "复制失败")
                            }
                        }
                    },
                    onOpenArchived = {
                        chatVm.refreshArchivedChats()
                        screen = Screen.ARCHIVED
                    },
                    onOpenTokenUsage = {
                        viewModel.loadTokenUsage()
                        screen = Screen.TOKEN_USAGE
                    },
                    onOpenSettings = { screen = Screen.SETTINGS },
                )
            }

            Screen.CHAT -> {
                ChatScreen(
                    state = chatVmState,
                    voiceState = voiceState,
                    voicePartial = voicePartial,
                    voiceAvailable = appState.voiceEnabled && modelState is io.qwenpaw.node.voice.ModelState.Ready,
                    imageLoader = imageLoader,
                    connection = connection,
                    onBack = { screen = Screen.LIST },
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
                        screen = Screen.LIST
                    },
                    onTogglePin = {
                        chatVmState.currentChatId?.let { chatVm.togglePin(it) }
                    },
                    onArchive = {
                        chatVmState.currentChatId?.let { chatVm.archiveChat(it) }
                        screen = Screen.LIST
                    },
                    onExport = {
                        pendingExportText = chatVm.exportMarkdown()
                        exportLauncher.launch(
                            "${chatVmState.currentChatName.ifBlank { "会话" }.take(40)}.md",
                        )
                    },
                    onSetThinking = { level -> chatVm.setThinking(level) },
                    onSetGroup = { groupId ->
                        chatVmState.currentChatId?.let { chatVm.setChatGroup(it, groupId) }
                    },
                    onDismissError = chatVm::clearError,
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
                    fontScale = appState.fontScale,
                    useMiSans = appState.useMiSans,
                    compactList = appState.compactList,
                    keepScreenOn = appState.keepScreenOn,
                    appVersion = BuildConfig.VERSION_NAME,
                    onBack = { screen = Screen.LIST },
                    onSwitch = { id ->
                        viewModel.switchTo(id)
                        screen = Screen.LIST
                    },
                    onRemove = viewModel::remove,
                    onEdit = viewModel::editInstance,
                    onPickAgent = { pickingAgent = it },
                    onAdd = { host, user, pass, agent ->
                        viewModel.connect(host, user, pass, agent)
                    },
                    onThemeMode = viewModel::setThemeMode,
                    onFontScale = viewModel::setFontScale,
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
                    modelState = modelState,
                    onDownloadModel = {
                        scope.launch { modelManager.download() }
                    },
                    onRemoveModel = { modelManager.remove() },
                    fontState = fontState,
                    onDownloadFont = { viewModel.downloadFont() },
                    onRemoveFont = { viewModel.removeFont() },
                )
            }
        }
    }

    pickingAgent?.let { instance ->
        AgentPickerDialog(
            currentAgentId = instance.agentId,
            load = {
                viewModel.loadAgents(
                    host = instance.baseUrl,
                    username = "",
                    password = "",
                )
            },
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
        )
    }
}

/**
 * 把选中的媒体复制到缓存目录并上传到服务端，返回服务端保存的路径。
 *
 * 服务端 `/api/console/upload` 返回的是服务器本地绝对路径，
 * 直接作为 message content 的 `source.url` 使用。
 */
private suspend fun uploadAttachment(
    context: android.content.Context,
    app: QwenPawApp,
    uri: Uri,
): String? = withContext(Dispatchers.IO) {
    val instance = app.instanceRepository.active() ?: return@withContext null
    val conn = io.qwenpaw.node.data.remote.Connection(
        instance.baseUrl,
        instance.token,
        instance.agentId,
    )
    val mime = context.contentResolver.getType(uri)
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
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
 * 复制某个会话的全部内容到剪贴板。
 *
 * 列表页没有渲染消息，因此先拉一次历史再拼 Markdown。返回文本，失败返回 null。
 */
private suspend fun copyChatToClipboard(
    context: android.content.Context,
    chatVm: io.qwenpaw.node.ui.chat.ChatViewModel,
    app: QwenPawApp,
    chatId: String,
): String? = withContext(Dispatchers.IO) {
    val instance = app.instanceRepository.active() ?: return@withContext null
    val conn = io.qwenpaw.node.data.remote.Connection(
        instance.baseUrl,
        instance.token,
        instance.agentId,
    )
    val history = runCatching { app.api.chat(conn, chatId) }.getOrNull() ?: return@withContext null
    val name = runCatching { app.api.chats(conn) }.getOrNull()
        ?.firstOrNull { it.id == chatId }?.name.orEmpty()
    val messages = io.qwenpaw.node.data.stream.HistoryMapper.map(history.messages)
    val text = chatVm.markdownOf(name, messages)
    withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(name.ifBlank { "会话" }, text))
    }
    text
}

/** 导出 Markdown 到用户选择的位置（由 CreateDocument launcher 真正落盘）。 */
private suspend fun writeTextToUri(
    context: android.content.Context,
    uri: Uri,
    text: String,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray())
        }
        true
    }.getOrDefault(false)
}
