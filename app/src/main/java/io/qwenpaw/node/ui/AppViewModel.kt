package io.qwenpaw.node.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.qwenpaw.node.data.InstanceRepository
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.ui.chat.ChatViewModel
import io.qwenpaw.node.ui.theme.FontScale
import io.qwenpaw.node.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用级状态：连接、实例切换、外观偏好。 */
data class AppState(
    val connecting: Boolean = false,
    val connectError: String? = null,
    val connected: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: FontScale = FontScale.NORMAL,
)

class AppViewModel(
    private val repository: InstanceRepository,
    private val chatViewModel: ChatViewModel,
    private val prefs: android.content.SharedPreferences,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        AppState(
            connected = repository.active() != null,
            themeMode = ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!),
            fontScale = FontScale.valueOf(prefs.getString(KEY_FONT, FontScale.NORMAL.name)!!),
        ),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    val instances: StateFlow<List<InstanceConfig>> = repository.instances
    val activeId: StateFlow<String?> = repository.activeId

    init {
        // 冷启动时如果已有实例，直接绑定并拉取会话。
        repository.active()?.let { instance ->
            bind(instance)
            chatViewModel.refreshChats()
        }
    }

    fun connect(host: String, username: String, password: String, agentId: String) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, connectError = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.connect(
                        name = host.ifBlank { "本地实例" },
                        baseUrl = host,
                        username = username.takeIf { it.isNotBlank() },
                        password = password.takeIf { it.isNotBlank() },
                        agentId = agentId.ifBlank { "default" },
                    )
                }
            }.onSuccess { config ->
                bind(config)
                _state.update { it.copy(connecting = false, connected = true, connectError = null) }
                chatViewModel.refreshChats()
            }.onFailure { e ->
                _state.update {
                    it.copy(connecting = false, connectError = e.message ?: "连接失败")
                }
            }
        }
    }

    fun useSaved(instance: InstanceConfig) {
        bind(instance)
        _state.update { it.copy(connected = true, connectError = null) }
        chatViewModel.refreshChats()
    }

    fun switchTo(id: String) {
        val instance = instances.value.firstOrNull { it.id == id } ?: return
        repository.setActive(id)
        useSaved(instance)
    }

    fun remove(id: String) {
        repository.remove(id)
        if (repository.active() == null) {
            _state.update { it.copy(connected = false) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    fun setFontScale(scale: FontScale) {
        prefs.edit().putString(KEY_FONT, scale.name).apply()
        _state.update { it.copy(fontScale = scale) }
    }

    fun chat(): ChatViewModel = chatViewModel

    private fun bind(instance: InstanceConfig) {
        val conn = Connection(instance.baseUrl, instance.token, instance.agentId)
        chatViewModel.bind(instance.id, conn, instance.username ?: "mobile-user")
    }

    override fun onCleared() {
        chatViewModel.detach()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT = "font_scale"

        fun factory(context: Context, app: io.qwenpaw.node.QwenPawApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    val chatVm = ChatViewModel(
                        api = app.api,
                        streamClient = app.streamClient,
                        db = app.database,
                        appContext = appContext,
                        isAppVisible = { AppVisibility.visibleCount > 0 },
                    )
                    return AppViewModel(
                        repository = app.instanceRepository,
                        chatViewModel = chatVm,
                        prefs = context.getSharedPreferences("qwenpaw_ui", Context.MODE_PRIVATE),
                    ) as T
                }
            }
    }
}
