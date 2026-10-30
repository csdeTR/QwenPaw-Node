package io.qwenpaw.node.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.qwenpaw.node.data.InstanceRepository
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.local.InstanceDraft
import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.ui.chat.ChatViewModel
import io.qwenpaw.node.ui.theme.FontScaling
import io.qwenpaw.node.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 语音输入的触发方式。 */
enum class VoiceTrigger {
    /** 点一下开始，再点「说完了」结束。默认方式。 */
    TAP,

    /** 按住说话，松手结束。 */
    HOLD,
}

/** 用户昵称的默认值。设置里清空输入框时回落到它。 */
const val DEFAULT_USER_NAME = "我"

/** 应用级状态：连接、实例切换、外观偏好。 */
data class AppState(
    val connecting: Boolean = false,
    val connectError: String? = null,
    val connected: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 整体界面缩放。 */
    val uiScale: Float = io.qwenpaw.node.ui.theme.FontScaling.DEFAULT,
    /** 聊天正文的额外缩放。 */
    val chatScale: Float = io.qwenpaw.node.ui.theme.FontScaling.DEFAULT,
    /**
     * 是否启用 MiSans。字体不再随 APK 打包，需先下载；
     * 这里只记录用户意愿，实际可用的字体族由 AppViewModel 的 miSansFamily 决定。
     */
    val useMiSans: Boolean = false,
    /** 会话列表是否用紧凑排版。 */
    val compactList: Boolean = false,
    /** 生成回复时保持屏幕常亮。 */
    val keepScreenOn: Boolean = false,
    /** 输入框是否显示语音按钮。默认开启；模型未下载时点击会提示下载。 */
    val voiceEnabled: Boolean = true,
    /** 语音触发方式，默认点一下。 */
    val voiceTrigger: VoiceTrigger = VoiceTrigger.TAP,
    /** 点一下触发时，识别到停顿是否自动结束。 */
    val voiceAutoStop: Boolean = true,
    /** 智能体输出的折叠方式，默认与官方一致：折叠结果。 */
    val assistantCollapse: io.qwenpaw.node.ui.chat.AssistantCollapse =
        io.qwenpaw.node.ui.chat.AssistantCollapse.RESULT_COLLAPSED,
    /**
     * 是否记录本次运行的日志。默认关闭。
     *
     * 关着的时候日志调用是空操作，不留任何内存占用；打开后才开始收集。
     */
    val logEnabled: Boolean = false,
    /** 记录哪些类别的日志。默认全部。 */
    val logKinds: Set<io.qwenpaw.node.log.LogKind> = io.qwenpaw.node.log.LogKind.entries.toSet(),
    /**
     * 是否在回复末尾显示本轮用量。
     *
     * 默认关闭：这是诊断性信息，多数用户不需要一直看到。
     */
    val showMessageStats: Boolean = false,
    /**
     * 用户自己的昵称，显示在自己发出的消息头部。
     *
     * 默认「我」：不改也能用，改过之后按 `KEY_USER_NAME` 记住。
     */
    val userName: String = DEFAULT_USER_NAME,
    /**
     * 会话列表的分组方式。
     *
     * 默认按时间，与 QwenPaw 网页版一致；选择会记住，下次进入沿用。
     */
    val groupMode: io.qwenpaw.node.ui.chat.GroupMode = io.qwenpaw.node.ui.chat.GroupMode.TIME,
    /**
     * 已折叠的分区 key。
     *
     * 持久化保存：折叠是用户偏好，冷启动就恢复是他期待的。
     * 与分组方式一样放在 `qwenpaw_ui` 里，不放进实例配置（不属于某台服务器）。
     */
    val collapsedGroups: Set<String> = emptySet(),
    /**
     * 本次运行手动指定的地址：实例 id → 地址。
     *
     * 放进 state 而不是让界面直接读仓库里的内存 map：后者不是可观察对象，
     * 用户点了「本次使用」之后 Compose 不会重组，界面会停在「自动选择」。
     */
    val sessionPicks: Map<String, String> = emptyMap(),
)

class AppViewModel(
    private val repository: InstanceRepository,
    private val chatViewModel: ChatViewModel,
    private val prefs: android.content.SharedPreferences,
    private val api: io.qwenpaw.node.data.remote.QwenPawApi,
    private val fontManager: io.qwenpaw.node.font.FontManager,
    private val network: io.qwenpaw.node.data.NetworkMonitor? = null,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        AppState(
            connected = repository.active() != null,
            themeMode = ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!),
            uiScale = prefs.getFloat(KEY_UI_SCALE, io.qwenpaw.node.ui.theme.FontScaling.DEFAULT),
            chatScale = prefs.getFloat(KEY_CHAT_SCALE, io.qwenpaw.node.ui.theme.FontScaling.DEFAULT),
            // 默认关闭：老用户升级后也不会突然多占 15MB，需要自己开启。
            useMiSans = prefs.getBoolean(KEY_MISANS, false),
            compactList = prefs.getBoolean(KEY_COMPACT, false),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_ON, false),
            // 语音按钮默认开启。老用户若已显式关闭过，保存的 false 会继续生效。
            voiceEnabled = prefs.getBoolean(KEY_VOICE, true),
            voiceTrigger = runCatching {
                VoiceTrigger.valueOf(prefs.getString(KEY_VOICE_TRIGGER, VoiceTrigger.TAP.name)!!)
            }.getOrDefault(VoiceTrigger.TAP),
            voiceAutoStop = prefs.getBoolean(KEY_VOICE_AUTO_STOP, true),
            assistantCollapse = runCatching {
                io.qwenpaw.node.ui.chat.AssistantCollapse.valueOf(
                    prefs.getString(
                        KEY_ASSISTANT_COLLAPSE,
                        io.qwenpaw.node.ui.chat.AssistantCollapse.RESULT_COLLAPSED.name,
                    )!!,
                )
            }.getOrDefault(io.qwenpaw.node.ui.chat.AssistantCollapse.RESULT_COLLAPSED),
            // 日志默认关闭：关着时所有日志调用都是空操作，不占内存也不影响性能。
            logEnabled = prefs.getBoolean(KEY_LOG_ENABLED, false),
            logKinds = readLogKinds(),
            showMessageStats = prefs.getBoolean(KEY_SHOW_STATS, false),
            userName = prefs.getString(KEY_USER_NAME, DEFAULT_USER_NAME)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_USER_NAME,
            groupMode = runCatching {
                io.qwenpaw.node.ui.chat.GroupMode.valueOf(
                    prefs.getString(KEY_GROUP_MODE, io.qwenpaw.node.ui.chat.GroupMode.TIME.name)!!,
                )
            }.getOrDefault(io.qwenpaw.node.ui.chat.GroupMode.TIME),
            collapsedGroups = readCollapsedGroups(),
        ),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 运行日志的订阅源。日志页直接消费它。 */
    val logEntries: StateFlow<List<io.qwenpaw.node.log.LogEntry>> =
        io.qwenpaw.node.log.RunLog.entries

    init {
        // 冷启动时恢复上次的日志开关；进程重启后缓冲自然是空的，
        // 因此这里不需要 clear，打开即等于「从这次运行开始记录」。
        if (prefs.getBoolean(KEY_LOG_ENABLED, false)) {
            io.qwenpaw.node.log.RunLog.setKinds(readLogKinds())
            io.qwenpaw.node.log.RunLog.enable(clear = true)
        }
    }

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

    /**
     * 上次打开的会话 id。
     *
     * 主页面是聊天页，冷启动时直接恢复到上次的会话，而不是先停在会话列表。
     * 会话列表降级为「附加页面」，从聊天页的返回或菜单进入。
     */
    private val _lastChatId = MutableStateFlow(prefs.getString(KEY_LAST_CHAT, null))
    val lastChatId: StateFlow<String?> = _lastChatId.asStateFlow()

    fun rememberLastChat(chatId: String?) {
        prefs.edit().apply {
            if (chatId.isNullOrBlank()) remove(KEY_LAST_CHAT) else putString(KEY_LAST_CHAT, chatId)
        }.apply()
        _lastChatId.value = chatId
    }

    init {
        // 冷启动时如果已有实例，直接绑定并拉取会话。
        // 主页面是聊天页，这里顺带把上次用的会话恢复出来。
        repository.active()?.let { instance ->
            val boundUrl = repository.resolvedBaseUrl(instance)
            bind(instance)
            chatViewModel.refreshChats(restoreChatId = _lastChatId.value)
            // 运行期缓存是空的，此时绑的地址只是「排序后第一个」，不一定可达。
            // 后台探一次：若备用地址才是通的，换过去再重载，用户不必手动刷新。
            probeAndRebind(boundUrl)
        }
    }

    /** 探测当前实例的地址，返回第一个可达的。全部失败返回 null。 */
    private suspend fun probeActive(): String? {
        val instance = repository.active() ?: return null
        return repository.probeReachable(instance)
    }

    /**
     * 后台探测地址，命中的地址与 [boundUrl] 不同时重绑并重载。
     *
     * 只比「与当前绑定地址是否相同」而不是「与排序第一位是否相同」：
     * 探测成功会把命中写进运行期缓存，之后 `resolvedBaseUrl` 就已经是命中值，
     * 用它比较会把「需要换地址」误判成「无需变化」。
     */
    private fun probeAndRebind(boundUrl: String) {
        scope.launch {
            val hit = withContext(Dispatchers.IO) { probeActive() } ?: return@launch
            if (hit == boundUrl) return@launch
            val instance = repository.active() ?: return@launch
            io.qwenpaw.node.log.RunLog.d(
                io.qwenpaw.node.log.LogKind.NETWORK,
                "AppVM",
                "地址从 $boundUrl 切换到 $hit",
            )
            bind(instance)
            chatViewModel.refreshCurrent()
        }
    }

    init {
        // 断网恢复后自动重载。
        //
        // 用户的原话是「1 分钟前没有网络，1 分钟后恢复，现在必须重启应用才能恢复连接」。
        // 只加手动刷新不够：用户不会盯着顶栏去点那一行。这里订阅联通状态，
        // 只在 false → true 的跳变时动作（关闭网络时不要触发，否则连串失败还会多打一轮请求）。
        network?.let { monitor ->
            // 冷启动就可能处于无网状态：drop(1) 会把当前值丢掉，
            // 这里先按当前值同步一次，否则顶栏会一直停在「正在连接…」或旧的「已连接」。
            chatViewModel.setNetworkState(monitor.online.value)
            scope.launch {
                // drop(1) 丢掉订阅瞬间的当前值：冷启动本来就绑过一次、拉过一次，
                // 不丢会立刻重复拉一轮会话列表。
                monitor.online.drop(1).collect { online ->
                    // 断网时也要通知聊天页：那一行状态若继续显示「已连接」，
                    // 与用户眼前的事实相反，正是这一轮报上来的问题。
                    chatViewModel.setNetworkState(online)
                    if (!online) return@collect
                    // 网络变了，地址解析结果也跟着变：先重绑，再重载。
                    refreshAll()
                }
            }
        }
    }

    /**
     * 重新绑定当前实例的连接并重载会话。
     *
     * 抽出来给三个入口共用：顶栏点「连接中断」、网络恢复、设置里改地址。
     * 地址解析依赖当前网络，所以每次都重新 bind 一次不重复。
     */
    fun refreshAll() {
        val instance = repository.active() ?: return
        bind(instance)
        chatViewModel.refreshCurrent()
    }

    fun connect(
        draft: InstanceDraft,
        /** 连接结束后的回调，参数是是否成功。添加实例时用来关闭临时页面。 */
        onResult: (Boolean) -> Unit = {},
    ) {
        connect(
            addresses = draft.addresses,
            instanceName = draft.instanceName,
            username = draft.username,
            password = draft.password,
            agentId = draft.agentId,
            onResult = onResult,
        )
    }

    fun connect(
        addresses: List<io.qwenpaw.node.data.local.AddressEntry>,
        username: String,
        password: String,
        agentId: String,
        /** 连接结束后的回调，参数是是否成功。添加实例时用来关闭临时页面。 */
        onResult: (Boolean) -> Unit = {},
        /**
         * 实例显示名。
         *
         * 与以前的关键差别：不再从第一个地址的别名推导。此前那套推导让实例卡片
         * 的标题与副标题显示同一个字符串，看起来像「给实例设置别名」。
         * 传空串时由 [InstanceDraft.resolvedName] 的同一规则回落到地址。
         */
        instanceName: String = "",
    ) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, connectError = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.connect(
                        name = InstanceDraft(
                            addresses = addresses,
                            instanceName = instanceName,
                        ).resolvedName(),
                        addresses = addresses,
                        username = username.takeIf { it.isNotBlank() },
                        password = password.takeIf { it.isNotBlank() },
                        agentId = agentId.ifBlank { "default" },
                    )
                }
            }.onSuccess { config ->
                bind(config)
                _state.update { it.copy(connecting = false, connected = true, connectError = null) }
                chatViewModel.refreshChats()
                onResult(true)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        connecting = false,
                        connectError = io.qwenpaw.node.data.remote.ErrorText.ofWithHint(
                            e,
                            io.qwenpaw.node.data.InstanceRepository.usesHttps(
                                addresses.firstOrNull()?.url.orEmpty(),
                            ),
                        ),
                    )
                }
                onResult(false)
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
        // 仓库已清掉这次的临时选择，界面这份镜像也要一起清，
        // 否则重新添加同 id 的实例时会显示一条早已不存在的「本次使用」。
        _state.update { it.copy(sessionPicks = it.sessionPicks - id) }
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

    /**
     * 手动指定实例优先使用的地址，传 null 恢复自动。**写入配置并落盘**，长期有效。
     *
     * 无论指定哪条都要重新绑定：绑定时的连接地址取自 resolvedBaseUrl，
     * 不重绑会让图片加载、导出这些旁路继续用旧地址。
     */
    fun chooseAddress(instanceId: String, url: String?) {
        repository.chooseAddress(instanceId, url)
        val updated = instances.value.firstOrNull { it.id == instanceId } ?: return
        if (repository.activeId.value == instanceId) {
            bind(updated)
        }
    }

    /**
     * 指定**本次运行**使用的地址，不落盘，重启后回到自动。
     *
     * 与 [chooseAddress] 的区别只在这一句：那个写进配置，这个只写内存。
     * 用户需要一个「我现在就想走这条路，但别记住」的开关——比如临时接了另一条
     * 隧道，试完还要回去。
     */
    fun chooseSessionAddress(instanceId: String, url: String?) {
        repository.chooseSessionAddress(instanceId, url)
        // 同步进 state：仓库里的是普通 map，Compose 观察不到它的增删。
        // 只用 repository.sessionAddress() 读的话，用户点完「本次使用」界面不会变，
        // 看起来像没生效。
        val picked = repository.sessionAddress(instanceId)
        _state.update { s ->
            s.copy(
                sessionPicks = if (picked == null) {
                    s.sessionPicks - instanceId
                } else {
                    s.sessionPicks + (instanceId to picked)
                },
            )
        }
        val updated = instances.value.firstOrNull { it.id == instanceId } ?: return
        if (repository.activeId.value == instanceId) {
            bind(updated)
        }
    }

    /** 本次运行指定的地址；未指定为 null。 */
    fun sessionAddress(instanceId: String): String? = _state.value.sessionPicks[instanceId]

    /** 探测地址并列出可用智能体，不写入任何配置。按地址列表顺序回退。 */
    suspend fun loadAgents(
        hosts: List<String>,
        username: String,
        password: String,
    ): List<AgentDto> = withContext(Dispatchers.IO) {
        repository.probeAgents(hosts, username.ifBlank { null }, password.ifBlank { null })
    }

    /**
     * 轻量验证地址：只查版本，返回命中的地址与版本描述。
     *
     * [instanceId] 非空时会把命中的地址写进运行期缓存，避免「验证里看到回退成功、
     * 真正连接时又从头试一遍」。
     */
    suspend fun verifyHosts(
        hosts: List<String>,
        onAttempt: (io.qwenpaw.node.ui.settings.VerifyAttempt) -> Unit = {},
        instanceId: String? = null,
    ): io.qwenpaw.node.data.VerifyResult = withContext(Dispatchers.IO) {
        repository.verify(hosts, { index, total, url ->
            onAttempt(io.qwenpaw.node.ui.settings.VerifyAttempt(index, total, url))
        }, instanceId)
    }

    /** 服务端是否要求登录。用于引导页第 2 步判断要不要填账号。 */
    suspend fun authRequired(address: String): Boolean = withContext(Dispatchers.IO) {
        repository.authRequired(address)
    }

    /** 当前网络类型与 WiFi 名称，供地址编辑区提示。 */
    fun networkKind(): String = repository.networkKind()

    fun networkSsid(): String? = repository.networkSsid()

    /**
     * 用已保存实例的凭据重新拉取智能体列表。
     *
     * 实例编辑页切换智能体时用：实例可能带 token，直接复用比让用户重新输入密码方便。
     */
    suspend fun loadAgentsFor(instance: InstanceConfig): List<AgentDto> = withContext(Dispatchers.IO) {
        repository.agentsFor(instance)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    fun setUiScale(scale: Float) {
        val clamped = io.qwenpaw.node.ui.theme.FontScaling.ui(scale)
        prefs.edit().putFloat(KEY_UI_SCALE, clamped).apply()
        _state.update { it.copy(uiScale = clamped) }
    }

    fun setChatScale(scale: Float) {
        val clamped = io.qwenpaw.node.ui.theme.FontScaling.chat(scale)
        prefs.edit().putFloat(KEY_CHAT_SCALE, clamped).apply()
        _state.update { it.copy(chatScale = clamped) }
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

    /**
     * 今天是否还需要弹归档确认框。
     *
     * 按自然日判断：用户勾「今日不再提示」后当天不再弹，跨天自动恢复。
     * 存日期字符串而不是布尔值，避免需要额外的定时重置。
     */
    fun shouldShowArchiveTip(): Boolean =
        ArchiveTip.shouldShow(prefs.getString(KEY_ARCHIVE_TIP_DATE, null))

    /** 勾选「今日不再提示」后调用。 */
    fun suppressArchiveTipToday() {
        prefs.edit().putString(KEY_ARCHIVE_TIP_DATE, java.time.LocalDate.now().toString()).apply()
    }

    fun setVoiceTrigger(trigger: VoiceTrigger) {
        prefs.edit().putString(KEY_VOICE_TRIGGER, trigger.name).apply()
        _state.update { it.copy(voiceTrigger = trigger) }
    }

    fun setVoiceAutoStop(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_AUTO_STOP, enabled).apply()
        _state.update { it.copy(voiceAutoStop = enabled) }
    }

    fun setAssistantCollapse(mode: io.qwenpaw.node.ui.chat.AssistantCollapse) {
        prefs.edit().putString(KEY_ASSISTANT_COLLAPSE, mode.name).apply()
        _state.update { it.copy(assistantCollapse = mode) }
    }

    // ---------------- 运行日志 ----------------

    /**
     * 开关运行日志。
     *
     * 打开时清空缓冲：让每次开启都从干净状态开始，避免把上一次的残留
     * 当成这次问题的现场。关闭只停止写入，已有条目保留以便导出。
     */
    fun setLogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOG_ENABLED, enabled).apply()
        if (enabled) {
            io.qwenpaw.node.log.RunLog.setKinds(_state.value.logKinds)
            io.qwenpaw.node.log.RunLog.enable(clear = true)
        } else {
            io.qwenpaw.node.log.RunLog.disable()
        }
        _state.update { it.copy(logEnabled = enabled) }
    }

    /** 切换某个类别的开关。至少保留一个类别，全关等于变相关闭日志。 */
    fun toggleLogKind(kind: io.qwenpaw.node.log.LogKind) {
        val current = _state.value.logKinds
        val next = if (kind in current) current - kind else current + kind
        prefs.edit()
            .putStringSet(KEY_LOG_KINDS, next.map { it.name }.toSet())
            .apply()
        io.qwenpaw.node.log.RunLog.setKinds(next)
        _state.update { it.copy(logKinds = next) }
    }

    fun clearLog() {
        io.qwenpaw.node.log.RunLog.clear()
    }

    fun exportLog(): String = io.qwenpaw.node.log.RunLog.export()

    /** 把外部日志文本载入日志页查看。只读展示，不并入本次缓冲。 */
    fun importLog(text: String) {
        val entries = io.qwenpaw.node.log.LogImporter.parse(text)
        io.qwenpaw.node.log.RunLog.replaceWith(entries)
    }

    fun setShowMessageStats(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_STATS, enabled).apply()
        _state.update { it.copy(showMessageStats = enabled) }
    }

    /** 改显示名。空白输入回落到默认值，避免消息头部出现空行。 */
    fun setUserName(name: String) {
        val trimmed = name.trim()
        val value = trimmed.ifBlank { DEFAULT_USER_NAME }
        prefs.edit().putString(KEY_USER_NAME, value).apply()
        _state.update { it.copy(userName = value) }
    }

    /** 切换会话列表分组方式。单击循环切换，与网页版一致。 */
    fun setGroupMode(mode: io.qwenpaw.node.ui.chat.GroupMode) {
        prefs.edit().putString(KEY_GROUP_MODE, mode.name).apply()
        _state.update { it.copy(groupMode = mode) }
    }

    /**
     * 折叠或展开一个分区。
     *
     * 折叠状态按分区 key 存盘：key 由分组方式决定（`today`、`g:abc`、`ungrouped`…），
     * 因此切换分组方式不会互相覆盖，切回来时之前的折叠状态还在。
     */
    fun setGroupCollapsed(key: String, collapsed: Boolean) {
        val current = _state.value.collapsedGroups
        val next = if (collapsed) current + key else current - key
        prefs.edit().putStringSet(KEY_COLLAPSED_GROUPS, next).apply()
        _state.update { it.copy(collapsedGroups = next) }
    }

    /** 读折叠集合。首次启动没有记录，返回空集即全部展开。 */
    private fun readCollapsedGroups(): Set<String> =
        prefs.getStringSet(KEY_COLLAPSED_GROUPS, null)?.toSet().orEmpty()

    /** 从 prefs 读类别集合。缺失或全部无效时回退为全部类别。 */
    private fun readLogKinds(): Set<io.qwenpaw.node.log.LogKind> {
        val raw = prefs.getStringSet(KEY_LOG_KINDS, null) ?: return io.qwenpaw.node.log.LogKind.entries.toSet()
        val parsed = raw.mapNotNull { name ->
            runCatching { io.qwenpaw.node.log.LogKind.valueOf(name) }.getOrNull()
        }.toSet()
        return parsed.ifEmpty { io.qwenpaw.node.log.LogKind.entries.toSet() }
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
            val conn = Connection(repository.resolvedBaseUrl(instance), instance.token, instance.agentId)
            runCatching { withContext(Dispatchers.IO) { api.tokenUsage(conn) } }
                .onSuccess { _tokenUsage.value = it }
                .onFailure { _tokenUsageError.value = it.message ?: "加载用量失败" }
            _tokenUsageLoading.value = false
        }
    }

    private fun bind(instance: InstanceConfig) {
        // 用仓库解析出的地址：本次运行已探明能连通的优先，否则用列表第一个。
        val url = repository.resolvedBaseUrl(instance)
        val conn = Connection(url, instance.token, instance.agentId)
        // 顶栏要显示「已通过「EasyTier」连接至 QwenPaw」：优先用该地址的别名，
        // 没有别名时退回地址本身。不再用实例名——那样反而看不出连的是哪条路。
        // 实例名单独传给末尾的归属名。用 serverDisplayName 而不是 name：
        // 名称是表单留空时回落出来的地址，那种情况下顶栏不该把地址再说一遍。
        chatViewModel.bind(
            instanceId = instance.id,
            conn = conn,
            userId = instance.username ?: "mobile-user",
            instanceLabel = instance.labelOf(url),
            instanceName = instance.serverDisplayName,
        )
    }

    override fun onCleared() {
        chatViewModel.detach()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val KEY_CHAT_SCALE = "chat_scale"
        private const val KEY_MISANS = "use_misans"
        private const val KEY_COMPACT = "compact_list"
        private const val KEY_KEEP_ON = "keep_screen_on"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_VOICE_TRIGGER = "voice_trigger"
        private const val KEY_VOICE_AUTO_STOP = "voice_auto_stop"
        /** 归档提示被抑制到的日期（yyyy-MM-dd）。当天再次归档不再弹确认框。 */
        private const val KEY_ARCHIVE_TIP_DATE = "archive_tip_hidden_date"
        private const val KEY_ASSISTANT_COLLAPSE = "assistant_collapse"
        private const val KEY_LAST_CHAT = "last_chat_id"
        /** 是否记录本次运行日志。默认关闭。 */
        private const val KEY_LOG_ENABLED = "log_enabled"
        /** 记录哪些类别，存 LogKind 名称集合。 */
        private const val KEY_LOG_KINDS = "log_kinds"
        /** 是否在回复末尾显示本轮用量。 */
        private const val KEY_SHOW_STATS = "show_message_stats"
        /** 用户昵称。 */
        private const val KEY_USER_NAME = "user_name"
        /** 会话列表分组方式。 */
        private const val KEY_GROUP_MODE = "group_mode"
        /** 已折叠的分区 key 集合。 */
        private const val KEY_COLLAPSED_GROUPS = "collapsed_groups"

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
                        network = app.networkMonitor,
                    ) as T
                }
            }
    }
}
