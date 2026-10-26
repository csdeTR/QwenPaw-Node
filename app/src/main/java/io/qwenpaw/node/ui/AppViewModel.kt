package io.qwenpaw.node.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.qwenpaw.node.data.InstanceRepository
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.ui.chat.ChatViewModel
import io.qwenpaw.node.ui.theme.FontScale
import io.qwenpaw.node.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /**
     * 是否启用 MiSans。字体不再随 APK 打包，需先下载；
     * 这里只记录用户意愿，实际可用的字体族由 AppViewModel 的 miSansFamily 决定。
     */
    val useMiSans: Boolean = false,
    /** 会话列表是否用紧凑排版。 */
    val compactList: Boolean = false,
    /** 生成回复时保持屏幕常亮。 */
    val keepScreenOn: Boolean = false,
    /** 输入框是否显示语音按钮。 */
    val voiceEnabled: Boolean = false,
)

class AppViewModel(
    private val repository: InstanceRepository,
    private val chatViewModel: ChatViewModel,
    private val prefs: android.content.SharedPreferences,
    private val api: io.qwenpaw.node.data.remote.QwenPawApi,
    private val fontManager: io.qwenpaw.node.font.FontManager,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        AppState(
            connected = repository.active() != null,
            themeMode = ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!),
            fontScale = FontScale.valueOf(prefs.getString(KEY_FONT, FontScale.NORMAL.name)!!),
            // 默认关闭：老用户升级后也不会突然多占 15MB，需要自己开启。
            useMiSans = prefs.getBoolean(KEY_MISANS, false),
            compactList = prefs.getBoolean(KEY_COMPACT, false),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_ON, false),
            voiceEnabled = prefs.getBoolean(KEY_VOICE, false),
        ),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** MiSans 字体的下载状态与可用字体族。 */
    val fontState: StateFlow<io.qwenpaw.node.font.FontState> = fontManager.state

    /**
     * 真正用于渲染的字体族。
     *
     * 只有「用户开启」且「字体已下载」时才返回 MiSans，否则 null 走系统字体。
     * 字体族里绑的是下载到本地的文件，下载完成后引用变化即触发主题重组。
     */
    val miSansFamily: StateFlow<androidx.compose.ui.text.font.FontFamily?> =
        fontManager.state
            .map { state ->
                if (state is io.qwenpaw.node.font.FontState.Ready) fontManager.family() else null
            }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, fontManager.family())

    fun downloadFont() {
        scope.launch { fontManager.download() }
    }

    fun removeFont() {
        fontManager.remove()
        setUseMiSans(false)
    }

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

    /** 修改实例的地址或智能体，保存后如果改的是当前实例就重新绑定。 */
    fun editInstance(instance: InstanceConfig) {
        repository.update(instance)
        if (repository.activeId.value == instance.id) {
            bind(instance)
            chatViewModel.refreshChats()
        }
    }

    /** 探测地址并列出可用智能体，不写入任何配置。 */
    suspend fun loadAgents(host: String, username: String, password: String): List<AgentDto> =
        withContext(Dispatchers.IO) {
            repository.probeAgents(host, username.ifBlank { null }, password.ifBlank { null })
        }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    fun setFontScale(scale: FontScale) {
        prefs.edit().putString(KEY_FONT, scale.name).apply()
        _state.update { it.copy(fontScale = scale) }
    }

    fun setUseMiSans(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MISANS, enabled).apply()
        _state.update { it.copy(useMiSans = enabled) }
    }

    fun setCompactList(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPACT, enabled).apply()
        _state.update { it.copy(compactList = enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_ON, enabled).apply()
        _state.update { it.copy(keepScreenOn = enabled) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
        _state.update { it.copy(voiceEnabled = enabled) }
    }

    fun chat(): ChatViewModel = chatViewModel

    // ---------------- Token 用量 ----------------

    private val _tokenUsage = MutableStateFlow<io.qwenpaw.node.data.model.TokenUsageDto?>(null)
    val tokenUsage: StateFlow<io.qwenpaw.node.data.model.TokenUsageDto?> = _tokenUsage.asStateFlow()

    private val _tokenUsageLoading = MutableStateFlow(false)
    val tokenUsageLoading: StateFlow<Boolean> = _tokenUsageLoading.asStateFlow()

    private val _tokenUsageError = MutableStateFlow<String?>(null)
    val tokenUsageError: StateFlow<String?> = _tokenUsageError.asStateFlow()

    fun loadTokenUsage() {
        val instance = repository.active() ?: return
        if (_tokenUsageLoading.value) return
        _tokenUsageLoading.value = true
        _tokenUsageError.value = null
        scope.launch {
            val conn = Connection(instance.baseUrl, instance.token, instance.agentId)
            runCatching { withContext(Dispatchers.IO) { api.tokenUsage(conn) } }
                .onSuccess { _tokenUsage.value = it }
                .onFailure { _tokenUsageError.value = it.message ?: "加载用量失败" }
            _tokenUsageLoading.value = false
        }
    }

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
        private const val KEY_MISANS = "use_misans"
        private const val KEY_COMPACT = "compact_list"
        private const val KEY_KEEP_ON = "keep_screen_on"
        private const val KEY_VOICE = "voice_enabled"

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
                        api = app.api,
                        fontManager = app.fontManager,
                    ) as T
                }
            }
    }
}
