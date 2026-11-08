package io.qwenpaw.node.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.qwenpaw.node.data.InstanceRepository
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.local.InstanceDraft
import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.plugin.bridgeEventStream
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
import kotlinx.coroutines.flow.collect
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
     * 是否使用 QwenPaw 服务端（网页端）设置的头像。
     *
     * 默认开启：用户在网页端设过头像后，手机上应该直接看到同一张图。
     * 本机头像优先于它，关掉这个开关不会影响本机头像。
     */
    val useServerAvatar: Boolean = true,
    /**
     * 消息下方操作行的呈现方式。
     *
     * 默认文字。「复制」与「复制为 Markdown」两项靠图形几乎无法区分，
     * 图标档是给「不想在消息下面看到文字」的用户准备的，不是更好的默认。
     */
    val actionStyle: io.qwenpaw.node.ui.chat.ActionStyle =
        io.qwenpaw.node.ui.chat.ActionStyle.TEXT,
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
     * 是否启用安全提示。
     *
     * 默认开启：公网地址没有登录或没有 HTTPS 时，等于把整台机器交出去，
     * 这个提醒值得每次启动出现一次。关闭是用户的显式选择，会二次确认。
     */
    val securityHintsEnabled: Boolean = true,
    /**
     * 本次运行检测到的安全风险；null 表示无需提示或已被用户关掉。
     *
     * 只在进程启动时填一次：把它做成持续状态会让用户每切一次页面就被弹一次。
     */
    val securityNotice: io.qwenpaw.node.data.SecurityNotice? = null,
    /**
     * 配套插件（QwenPaw Node Bridge）的探测结论。
     *
     * 设置页的插件行直接读它；后续依赖插件的功能只按 `capabilities` 开关，
     * 不比对版本号。
     */
    val bridgeStatus: io.qwenpaw.node.data.plugin.BridgeStatus =
        io.qwenpaw.node.data.plugin.BridgeStatus.Unknown,
    /**
     * 是否启用依赖配套插件的功能。
     *
     * 只是客户端侧的意愿；插件未安装时界面会禁用这个开关，
     * 因此这里的值可能为 true 但实际不生效——以 [bridgeStatus] 为准。
     */
    val bridgeFeaturesEnabled: Boolean = false,
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
    /** 服务端头像缓存。为 null 时（测试、预览）不显示服务端头像。 */
    private val serverAvatarClient: io.qwenpaw.node.data.remote.ServerAvatarClient? = null,
    /** 配套插件探测。为 null 时（测试、预览）插件行固定显示「尚未检测」。 */
    private val pluginProbe: io.qwenpaw.node.data.plugin.PluginProbe? = null,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 插件事件流的长连接。切实例、关开关或重探时取消，避免旧连接继续推数。 */
    private var eventsJob: kotlinx.coroutines.Job? = null
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
            // 默认开启：网页端设过头像后手机上应直接看到同一张图。
            useServerAvatar = prefs.getBoolean(KEY_SERVER_AVATAR, true),
            actionStyle = runCatching {
                io.qwenpaw.node.ui.chat.ActionStyle.valueOf(
                    prefs.getString(
                        KEY_ACTION_STYLE,
                        io.qwenpaw.node.ui.chat.ActionStyle.TEXT.name,
                    )!!,
                )
            }.getOrDefault(io.qwenpaw.node.ui.chat.ActionStyle.TEXT),
            groupMode = runCatching {
                io.qwenpaw.node.ui.chat.GroupMode.valueOf(
                    prefs.getString(KEY_GROUP_MODE, io.qwenpaw.node.ui.chat.GroupMode.TIME.name)!!,
                )
            }.getOrDefault(io.qwenpaw.node.ui.chat.GroupMode.TIME),
            collapsedGroups = readCollapsedGroups(),
            securityHintsEnabled = prefs.getBoolean(KEY_SECURITY_HINTS, true),
            bridgeFeaturesEnabled = prefs.getBoolean(KEY_BRIDGE_FEATURES, false),
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

    private val _serverAvatarFile =
        MutableStateFlow(runCatching { serverAvatarClient?.cached() }.getOrNull())

    /**
     * 服务端头像的本地缓存文件。
     *
     * 初值是磁盘上的缓存，因此冷启动与断网时都能显示上一次的头像；
     * [refreshServerAvatar] 联网核对后再更新。为 null 表示服务端没有设置头像。
     */
    val serverAvatarFile: kotlinx.coroutines.flow.StateFlow<java.io.File?> =
        _serverAvatarFile.asStateFlow()

    /**
     * 联网核对服务端头像。
     *
     * 开关关着时直接返回：下载一张永远不会显示的图没有意义。
     * 打开开关时再补一次，见 [setUseServerAvatar]。
     *
     * 失败时保留现有文件而不清空：网络抖动不该让头像忽然消失。
     * 只有服务端明确返回「没有头像」时才清空。
     */
    fun refreshServerAvatar() {
        val client = serverAvatarClient ?: return
        if (!_state.value.useServerAvatar) return
        val instance = repository.active() ?: return
        val conn = io.qwenpaw.node.data.remote.Connection(
            repository.resolvedBaseUrl(instance),
            instance.token,
            instance.agentId,
            instance.basicAuth,
        )
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { client.refresh(conn) }.getOrNull()
            }
            // 请求失败与「服务端确实没设头像」在这里都是 null，无法区分；
            // 因此仅在磁盘缓存也消失（refresh 内部已 clear）时才把界面置空。
            _serverAvatarFile.value = file ?: runCatching { client.cached() }.getOrNull()
        }
    }

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
            // 安全提示每次进程启动检查一次：公网地址不设防，等于把整台机器交出去。
            checkSecurityNoticeOnce(instance, boundUrl)
            // 插件探测由 bind() 末尾统一触发：插件路由不在鉴权公开路径里，
            // 没有 token 的时候探只会拿到 401。这里再调一次会让每次冷启动多发一遍请求。
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
            basicUser = draft.basicUser,
            basicPassword = draft.basicPassword,
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
        /** 反向代理的 HTTP Basic 凭据；两个都非空才生效。 */
        basicUser: String = "",
        basicPassword: String = "",
    ) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, connectError = null) }
        val basicAuth = io.qwenpaw.node.data.remote.BasicAuth(
            basicUser.trim(),
            basicPassword,
        ).takeIf { basicUser.isNotBlank() && basicPassword.isNotBlank() }
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
                        basicAuth = basicAuth,
                    )
                }
            }.onSuccess { config ->
                bind(config)
                _state.update { it.copy(connecting = false, connected = true, connectError = null) }
                chatViewModel.refreshChats()
                onResult(true)
            }.onFailure { e ->
                val hint = io.qwenpaw.node.data.remote.ErrorText.ofWithHint(
                    e,
                    io.qwenpaw.node.data.InstanceRepository.usesHttps(
                        addresses.firstOrNull()?.url.orEmpty(),
                    ),
                )
                // 401 且成因是「token 失效」时补一句去处：光说「登录已失效」，
                // 用户在连接页上找不到任何可以「重新登录」的入口。
                //
                // 失败原因不是 401（例如半开连接、DNS 抖动）时再向 `/auth/verify`
                // 问一次：token 真的被吊销了要引导重新登录，网络问题只需重试，
                // 两种情况混成同一句话，用户会反复重试一个永远不会成功的动作。
                val expired = e is io.qwenpaw.node.data.remote.ApiException &&
                    e.code == 401 &&
                    io.qwenpaw.node.data.remote.ErrorText.classify401(
                        e.detail ?: e.message,
                        e.wwwAuthenticate,
                        e.tokenSent,
                    ) == io.qwenpaw.node.data.remote.ErrorText.AuthFailure.TOKEN_EXPIRED
                val revoked = !expired && tokenStillValid() == false
                _state.update {
                    it.copy(
                        connecting = false,
                        connectError = if (expired || revoked) {
                            "$hint\n在「设置 → 服务器」里找到该实例，点「退出登录」，再重新输入账号密码"
                        } else {
                            hint
                        },
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
     * 退出登录：吊销当前 token 并清掉本地凭据。
     *
     * 顺序是「先吊销再清本地」：反过来的话本地没有 token 就发不出吊销请求，
     * 服务端那份凭据会一直留着，等于「在手机上登出了，但这张票还能用」。
     *
     * 服务端未启用登录时吊销接口返回 403，按成功处理并给一句说明——本来就没有
     * 可吊销的东西，报错只会让用户以为操作失败。
     */
    fun logout(instanceId: String) {
        val instance = instances.value.firstOrNull { it.id == instanceId } ?: return
        val conn = repository.connection(instance)
        scope.launch {
            var note: String? = null
            if (conn != null && !instance.token.isNullOrBlank()) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { api.revokeToken(conn, null) }
                }
                result.exceptionOrNull()?.let { e ->
                    note = if (e is io.qwenpaw.node.data.remote.ApiException && e.code == 403) {
                        "该实例未启用登录验证，本地凭据已清除"
                    } else {
                        // 网络失败不阻塞登出：本地凭据必须清掉，否则用户「退不出去」。
                        "吊销凭据失败：${io.qwenpaw.node.data.remote.ErrorText.of(e)}；本地凭据已清除"
                    }
                }
            }
            repository.logout(instanceId)
            val updated = instances.value.firstOrNull { it.id == instanceId }
            if (updated != null && repository.activeId.value == instanceId) {
                bind(updated)
                _state.update { it.copy(connected = true, connectError = note) }
                chatViewModel.refreshChats()
            } else {
                _state.update { it.copy(connectError = note) }
            }
            // 登出是一项用户主动发起的状态变更，无论成败都要说一声，
            // 否则点完没有任何反应。通知通道沿用 ChatViewModel 的轻提示，
            // Nav 层已经在订阅它并转成 Toast。
            chatViewModel.notify(note ?: "已退出登录，重新连接需再次输入账号密码")
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
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth? = null,
    ): List<AgentDto> = withContext(Dispatchers.IO) {
        repository.probeAgents(
            hosts,
            username.ifBlank { null },
            password.ifBlank { null },
            basicAuth,
        )
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
        /** 反向代理凭据；非空时验证的是「反代之后」的真实状态。 */
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth? = null,
        /** 已保存实例的 token；带它才能读到配套插件版本。 */
        token: String? = null,
    ): io.qwenpaw.node.data.VerifyResult = withContext(Dispatchers.IO) {
        repository.verify(hosts, { index, total, url ->
            onAttempt(io.qwenpaw.node.ui.settings.VerifyAttempt(index, total, url))
        }, instanceId, basicAuth, token)
    }

    /** 服务端是否要求登录。用于引导页第 2 步判断要不要填账号。 */
    suspend fun authRequired(address: String): Boolean = withContext(Dispatchers.IO) {
        repository.authRequired(address)
    }

    /** 试登录一次。返回 null 表示账号密码可用，否则是给界面显示的原因。 */
    suspend fun checkLogin(
        address: String,
        username: String,
        password: String,
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth? = null,
    ): String? = withContext(Dispatchers.IO) {
        repository.checkLogin(address, username, password, basicAuth)
    }

    /**
     * 当前实例的 token 是否仍有效。
     *
     * 用于区分「token 失效」与「网络不通」：连接失败时先调它，前者要重新输入密码，
     * 后者只需重试。返回 null 表示问不出来（没有实例、没有 token、或连不上）。
     */
    suspend fun tokenStillValid(): Boolean? {
        val instance = repository.active() ?: return null
        if (instance.token.isNullOrBlank()) return null
        val conn = repository.connection(instance) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { api.verifyToken(conn) }.getOrNull()
        }
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

    /**
     * 记录「是否启用依赖配套插件的功能」。
     *
     * 插件是否真的可用由界面按 [AppState.bridgeStatus] 判定：未安装时开关不可点，
     * 因此这里不做拦截，只负责落盘与状态同步。
     */
    fun setBridgeFeaturesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BRIDGE_FEATURES, enabled).apply()
        _state.update { it.copy(bridgeFeaturesEnabled = enabled) }
        // 开启时先连上事件流；关闭时立即断开，不等下一次重探。
        if (enabled) refreshBridgeStatus() else stopBridgeEvents()
    }

    /**
     * 开关安全提示。
     *
     * 关闭后不再在启动时检查风险。二次确认由设置页负责——这里只管落盘，
     * 避免同一条确认逻辑在 ViewModel 与界面里各写一份。
     */
    fun setSecurityHintsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SECURITY_HINTS, enabled).apply()
        _state.update {
            it.copy(
                securityHintsEnabled = enabled,
                // 关掉的当下就把已弹出的提示收起来，否则它会悬在屏幕上
                // 与「已关闭提示」这个选择互相矛盾。
                securityNotice = if (enabled) it.securityNotice else null,
            )
        }
    }

    /** 用户读完启动时的安全提示。 */
    fun dismissSecurityNotice() {
        _state.update { it.copy(securityNotice = null) }
    }

    /**
     * 启动时检查一次当前地址的安全风险。
     *
     * 只查 `GET /api/auth/status`（公开路径，带反代凭据即可），不拉业务数据：
     * 这个检查不该因为某个业务接口失败而漏掉。
     *
     * 结果只在「开关开着 + 确有风险」时写入 state，且进程内只写一次——
     * 之后的重连、网络恢复都不再重复弹。
     */
    private fun checkSecurityNoticeOnce(instance: InstanceConfig, url: String) {
        if (!_state.value.securityHintsEnabled) return
        if (_state.value.securityNotice != null) return
        scope.launch {
            val needsLogin = withContext(Dispatchers.IO) {
                runCatching {
                    io.qwenpaw.node.data.InstanceRepository.loginRequired(
                        api.authStatus(url, instance.basicAuth),
                    )
                }.getOrNull()
            }
            // 查不到就当「不需要登录」，与其它四处的判定口径一致；
            // 但这里会因此倾向于提示，属于可接受的保守方向。
            val notice = io.qwenpaw.node.data.assessSecurity(
                url = url,
                loginRequired = needsLogin ?: false,
            )
            if (!notice.any) return@launch
            if (!_state.value.securityHintsEnabled) return@launch
            _state.update { it.copy(securityNotice = notice) }
        }
    }

    /**
     * 探测配套插件。
     *
     * 时机：绑定连接后、以及用户回到前台时。前者覆盖「刚登录拿到 token」，
     * 后者覆盖「服务端热加载插件」——服务端不必重启，用户切出去再回来就能看到。
     *
     * 401 记为「待重探」而不是「未安装」，见 [io.qwenpaw.node.data.plugin.PluginProbe]。
     */
    fun refreshBridgeStatus() {
        val probe = pluginProbe ?: return
        val instance = repository.active() ?: return
        val conn = repository.connection(instance) ?: return
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                probe.probe(instance.id, conn)
            }
            _state.update { it.copy(bridgeStatus = status) }
            restartBridgeEvents(status, conn)
        }
    }

    /**
     * 按插件能力与总开关决定事件流要不要连。
     *
     * 每次重探都先取消旧连接：插件版本可能变了（比如从 0.1.1 升到 0.1.2 才有
     * `sse`），旧连接的语义不一定还成立。
     */
    private fun restartBridgeEvents(
        status: io.qwenpaw.node.data.plugin.BridgeStatus,
        conn: Connection,
    ) {
        eventsJob?.cancel()
        eventsJob = null
        if (!_state.value.bridgeFeaturesEnabled) return
        val info = (status as? io.qwenpaw.node.data.plugin.BridgeStatus.Ready)?.info ?: return
        if (!info.supports("sse")) return
        eventsJob = scope.launch {
            runCatching {
                api.bridgeEventStream(conn).collect { event ->
                    when (event) {
                        io.qwenpaw.node.data.plugin.BridgeEvent.Ready -> Unit
                        is io.qwenpaw.node.data.plugin.BridgeEvent.Unread ->
                            inbox.applyUnreadCount(event.unreadCount)
                        // 待审批数由对话页的 `push-messages` 轮询负责，那里还要拿
                        // 审批内容本身；这里只更新角标没有意义。
                        is io.qwenpaw.node.data.plugin.BridgeEvent.PendingApproval -> Unit
                    }
                }
            }.onFailure { failure ->
                // 主动取消（切实例、关开关）不是故障，不写日志。
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                // 长连接断了就回到「进入页面时刷新」，不自动重连：反复重连只会在
                // 服务端不可用时刷满日志，而用户能感知的差异只有角标延迟。
                io.qwenpaw.node.log.RunLog.e(
                    io.qwenpaw.node.log.LogKind.NETWORK,
                    "BridgeEvents",
                    failure,
                    "事件流结束，回落到按需刷新",
                )
            }
        }
    }

    /** 关掉事件流。总开关关闭或退出登录时调用。 */
    private fun stopBridgeEvents() {
        eventsJob?.cancel()
        eventsJob = null
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

    /** 开关服务端头像。关掉只是不显示，不删本地缓存——再打开时不必重新下载。 */
    fun setUseServerAvatar(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVER_AVATAR, enabled).apply()
        _state.update { it.copy(useServerAvatar = enabled) }
        // 之前关着时跳过了下载，打开后要补一次；没有缓存的话界面会一直是首字。
        if (enabled) refreshServerAvatar()
    }

    /** 切换消息底部操作行样式。 */
    fun setActionStyle(style: io.qwenpaw.node.ui.chat.ActionStyle) {
        prefs.edit().putString(KEY_ACTION_STYLE, style.name).apply()
        _state.update { it.copy(actionStyle = style) }
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

    // ---------------- 收件箱 ----------------

    // 用显式可空字段而不是 `by lazy`/普通 val：`init` 里就会调 `bind`，
    // 那时这些属性（以及 lazy 的委托字段）都还没完成初始化。
    private var inboxHolder: io.qwenpaw.node.ui.inbox.InboxViewModel? = null

    /** 收件箱。连接在调用时现取，可能在切换实例之后才第一次被访问。 */
    val inbox: io.qwenpaw.node.ui.inbox.InboxViewModel
        get() = inboxHolder ?: io.qwenpaw.node.ui.inbox.InboxViewModel(
            api = api,
            connection = ::activeConnection,
            // 智能体筛选的选项。失败时 InboxViewModel 会静默退化为「全部智能体」，不阻塞列表。
            loadAgents = {
                repository.active()?.let { repository.agentsFor(it) } ?: emptyList()
            },
        ).also { inboxHolder = it }

    /** 工具、环境变量与后台策略共用同一个连接来源。 */
    private var configHolder: io.qwenpaw.node.ui.settings.SettingsConfigViewModel? = null

    val settingsConfig: io.qwenpaw.node.ui.settings.SettingsConfigViewModel
        get() = configHolder ?: io.qwenpaw.node.ui.settings.SettingsConfigViewModel(
            api = api,
            connection = ::activeConnection,
        ).also { configHolder = it }

    /** 定时任务。任务归属当前智能体，切换实例或智能体时一并清空。 */
    private var cronHolder: io.qwenpaw.node.ui.settings.CronViewModel? = null

    val cron: io.qwenpaw.node.ui.settings.CronViewModel
        get() = cronHolder ?: io.qwenpaw.node.ui.settings.CronViewModel(
            api = api,
            connection = ::activeConnection,
        ).also { cronHolder = it }

    private fun activeConnection(): io.qwenpaw.node.data.remote.Connection? =
        repository.active()?.let { instance ->
            io.qwenpaw.node.data.remote.Connection(
                repository.resolvedBaseUrl(instance),
                instance.token,
                instance.agentId,
                instance.basicAuth,
            )
        }

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
            val conn = Connection(
                repository.resolvedBaseUrl(instance),
                instance.token,
                instance.agentId,
                instance.basicAuth,
            )
            runCatching { withContext(Dispatchers.IO) { api.tokenUsage(conn) } }
                .onSuccess { _tokenUsage.value = it }
                .onFailure { _tokenUsageError.value = it.message ?: "加载用量失败" }
            _tokenUsageLoading.value = false
        }
    }

    private fun bind(instance: InstanceConfig) {
        // 用仓库解析出的地址：本次运行已探明能连通的优先，否则用列表第一个。
        val url = repository.resolvedBaseUrl(instance)
        val conn = Connection(url, instance.token, instance.agentId, instance.basicAuth)
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
        // 换实例或换智能体时收件箱里的事件归属已经变了，必须清空。
        // `bind` 每次重绑都会走一遍，因此只在标识真的变化时清。
        val key = "${instance.id}|${instance.agentId}"
        val previous = inboxKey
        inboxKey = key
        // 只在「真的换过」时清：首次绑定没有旧数据可清，也不必为此提前创建两个 ViewModel。
        if (previous != null && previous != key) {
            inboxHolder?.clear()
            configHolder?.clear()
            cronHolder?.clear()
        }
        // 每次重绑都核对一次服务端头像：绑定本身已经发生过一次网络请求，
        // 这一次开销很小；换实例后旧头像属于别的服务器，必须换掉。
        refreshServerAvatar()
        // 插件结论同样按实例区分，换实例后要重探；同一实例的重绑也会重探，
        // 这样服务端热加载插件后，用户切一次页面就能看到。
        refreshBridgeStatus()
    }

    private var inboxKey: String? = null

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
        /** 是否启用公网地址的安全提示。默认开启。 */
        private const val KEY_SECURITY_HINTS = "security_hints_enabled"
        /** 是否启用依赖配套插件的功能。默认关闭。 */
        private const val KEY_BRIDGE_FEATURES = "bridge_features_enabled"
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
        /** 是否使用服务端（网页端）头像。 */
        private const val KEY_SERVER_AVATAR = "use_server_avatar"
        /** 消息底部操作行样式。 */
        private const val KEY_ACTION_STYLE = "message_action_style"
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
                        toolStreamClient = app.toolStreamClient,
                        db = app.database,
                        appContext = appContext,
                        isAppVisible = { AppVisibility.visibleCount > 0 },
                        chatIconStore = app.chatIconStore,
                    )
                    return AppViewModel(
                        repository = app.instanceRepository,
                        chatViewModel = chatVm,
                        prefs = context.getSharedPreferences("qwenpaw_ui", Context.MODE_PRIVATE),
                        api = app.api,
                        fontManager = app.fontManager,
                        network = app.networkMonitor,
                        serverAvatarClient = app.serverAvatar,
                        pluginProbe = app.pluginProbe,
                    ) as T
                }
            }
    }
}
