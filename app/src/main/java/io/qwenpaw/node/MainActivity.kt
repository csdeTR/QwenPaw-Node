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

            QwenPawTheme(mode = appState.themeMode, fontScale = appState.fontScale) {
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

private enum class Screen { LIST, CHAT, SETTINGS }

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
            saved = instances,
            onUseSaved = { viewModel.useSaved(it) },
        )
        return
    }

    when (screen) {
        Screen.LIST -> {
            ChatListScreen(
                chats = chatVmState.chats,
                loading = chatVmState.loading,
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
                onOpenSettings = { screen = Screen.SETTINGS },
            )
        }

        Screen.CHAT -> {
            ChatScreen(
                state = chatVmState,
                onBack = { screen = Screen.LIST },
                onDraftChange = chatVm::updateDraft,
                onSend = { chatVm.send() },
                onStop = chatVm::stop,
                onAttach = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
                onRemoveAttachment = chatVm::removeAttachment,
                onApprove = chatVm::approve,
                onDeny = chatVm::deny,
                onRetry = chatVm::retry,
            )
        }

        Screen.SETTINGS -> {
            SettingsScreen(
                instances = instances,
                activeId = activeId,
                themeMode = appState.themeMode,
                fontScale = appState.fontScale,
                onBack = { screen = Screen.LIST },
                onSwitch = { id ->
                    viewModel.switchTo(id)
                    screen = Screen.LIST
                },
                onRemove = viewModel::remove,
                onAdd = { host, user, pass, agent ->
                    viewModel.connect(host, user, pass, agent)
                },
                onThemeMode = viewModel::setThemeMode,
                onFontScale = viewModel::setFontScale,
            )
        }
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
