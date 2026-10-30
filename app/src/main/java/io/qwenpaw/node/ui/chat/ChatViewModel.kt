package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.local.AppDatabase
import io.qwenpaw.node.data.local.CachedMessageEntity
import io.qwenpaw.node.data.local.DraftEntity
import io.qwenpaw.node.data.model.ApprovalDto
import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.data.model.ProjectDirDto
import io.qwenpaw.node.data.model.ThinkingViewDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.ChatStreamClient
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.stream.ChatEvent
import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.HistoryMapper
import io.qwenpaw.node.data.stream.Role
import io.qwenpaw.node.data.stream.StreamAccumulator
import io.qwenpaw.node.notify.ChatNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 聊天页的可变状态。 */
data class ChatUiState(
    val loading: Boolean = true,
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
    val draft: String = "",
    val attachments: List<String> = emptyList(),
    val contextUsage: Double = 0.0,
    /** 本轮上下文的已用 token 估算值，来自 `turn_usage`。 */
    val contextTokens: Long = 0,
    /** 当前模型的最大输入长度，来自 `turn_usage` / thinking 视图。 */
    val contextMax: Long = 0,
    val pendingApprovals: List<ApprovalDto> = emptyList(),
    val chats: List<ChatSpecDto> = emptyList(),
    /** 已归档会话，单独一页展示。 */
    val archivedChats: List<ChatSpecDto> = emptyList(),
    val groups: List<ChatGroupDto> = emptyList(),
    /** 每个会话的最新消息预览，key 为 chatId。 */
    val previews: Map<String, String> = emptyMap(),
    val currentChatId: String? = null,
    val currentChatName: String = "",
    val currentChatPinned: Boolean = false,
    val currentChatGroupId: String? = null,
    /** 当前会话的思考模式视图；null 表示尚未拉取。 */
    val thinking: ThinkingViewDto? = null,
    /** 可供当前会话切换的模型；已合并三个数组并过滤掉未启用的供应商。 */
    val modelChoices: List<ModelChoice> = emptyList(),
    /** 输入框上方胶囊显示的模型名，取自 thinking 视图。 */
    val currentModelLabel: String? = null,
    /** 智能体名称，用于说明审批模式的作用范围。 */
    val agentName: String = "",
    /** 智能体审批模式：STRICT / SMART / AUTO / OFF。null 表示尚未拉取。 */
    val approvalLevel: String? = null,
    /** 智能体的工作区目录；null 表示尚未拉取。 */
    val projectDir: ProjectDirDto? = null,
    /**
     * 与服务端之间的链路状态。
     *
     * 与 [running] 是两件不同的事：[running] 只表示「当前有没有生成流在跑」，
     * 断网时它同样是 false，所以不能用它推断连接是否可用。
     */
    val link: LinkState = LinkState.UNKNOWN,
    /** 当前实际绑定的服务器地址，用于顶栏显示「已通过 … 连接至 QwenPaw」。 */
    val boundUrl: String = "",
    /** 该地址的显示名：有别名用别名，否则是地址本身。 */
    val boundLabel: String = "",
    /**
     * 实例显示名（设置里的「服务器名称」）。
     *
     * 只用于顶栏「已通过 … 连接至「名称」」的末尾。为空时末尾仍写 QwenPaw。
     * 与 [boundLabel] 是两件事：那个描述「走哪条地址」，这个描述「连的是哪台」。
     */
    val boundServerName: String = "",
)

/**
 * 链路状态。
 *
 * [UNKNOWN] 是「还没验证过」：冷启动到第一次请求返回之间，或断网恢复后
 * 尚未有请求证实时。界面显示「正在连接…」——不能当成「已连接」，
 * 那正是「关掉网络仍显示已连接」的来源。
 *
 * 只有真实请求成功才转 [ONLINE]，真实失败或系统报告断网才转 [OFFLINE]。
 */
enum class LinkState { UNKNOWN, ONLINE, OFFLINE }

/**
 * 绑定新连接时是否要清空当前会话。
 *
 * 只有「从一台服务器换到另一台」才清空：旧会话 id 在新服务器上不存在。
 * 同一实例重连（顶栏重连、网络恢复、改地址）必须保留，否则下一次发送
 * 会因为 `currentChatId` 为空而新建会话——这正是「在所选会话里发消息，
 * 它却新建了一个会话」的根因。冷启动首次绑定（[current] 为空串）也不清空，
 * 那时本来就没有会话。
 */
internal fun shouldClearOnBind(current: String, next: String): Boolean =
    current.isNotBlank() && next.isNotBlank() && current != next

/**
 * 把「本轮之前的历史」与「本轮流式内容」拼成界面上的消息列表。
 *
 * 两者必须分开存放：历史来自服务端或本地缓存，是只读的；本轮内容由
 * [StreamAccumulator] 逐帧追加。此前界面直接写 `accumulator.snapshot()`，
 * 每来一帧就把历史整体丢掉，于是「发完消息之前的记录全都不见了」。
 *
 * 这里不做去重：历史与本地回显的 id 体系不同（服务端 id vs `local-user-N`），
 * 强行合并反而会把合法的重复内容吞掉。真正的对齐在回复结束后由
 * `reconcileWithServer` 用服务端历史整体替换一次。
 */
internal fun mergeHistory(
    history: List<ChatMessage>,
    live: List<ChatMessage>,
): List<ChatMessage> = when {
    history.isEmpty() -> live
    live.isEmpty() -> history
    else -> history + live
}

/**
 * 一个可切换的模型。
 *
 * 服务端把模型拆在三个数组里（配置 / 额外 / 探测），这里扁平化成一层，
 * 菜单只需按顺序铺开，不必关心来源。
 */
data class ModelChoice(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
) {
    /** 稳定 key，用于菜单与去重。 */
    val key: String get() = "$providerId:$modelId"
}

/**
 * 会话与聊天状态管理。
 *
 * 关键约束（服务端行为）：
 * - 同一 chat 并发发送返回 409，因此发送前必须串行化；
 * - 客户端断线不影响服务端继续执行，重连用 `reconnect: true`；
 * - 审批没有推送通道，只能轮询 `push-messages`。
 */
class ChatViewModel(
    private val api: QwenPawApi,
    private val streamClient: ChatStreamClient,
    private val db: AppDatabase,
    private val appContext: android.content.Context,
    /** 由 Activity 维护的「是否在前台」判断，用于决定要不要发本地通知。 */
    private val isAppVisible: () -> Boolean = { false },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamJob: Job? = null
    private var pollJob: Job? = null
    private var currentConnection: Connection? = null
    private var instanceId: String = ""
    private var userId: String = ""
    private var accumulator = StreamAccumulator()

    /**
     * 本轮之前的历史。
     *
     * [accumulator] 只承载「从打开会话那一刻起新增的内容」，历史**不进** accumulator：
     * 它的 id 来自服务端或者上一轮的本地拼接，把两者混在一个可变的折叠器里，
     * 每来一个 token 都要整体重排一遍。
     *
     * 界面上的消息永远是「历史 + 本轮增量」。此前两处直接写
     * `messages = accumulator.snapshot()`，而 accumulator 在 `openChat` 时刚被清空，
     * 于是每发一条消息，之前的所有消息都被整屏冲掉；要等流式内容陆续到达才重新有东西，
     * 并且看起来像「新建了一个会话」。这就是「发送后页面空荡荡」「回复完毕才看到历史」的同
     * 一个根因。
     */
    private var historyPrefix: List<ChatMessage> = emptyList()

    private var saveDebounce: Job? = null

    /** 历史前缀与本轮流式内容拼成界面上要显示的全部消息。 */
    private fun mergedMessages(): List<ChatMessage> = mergeHistory(historyPrefix, accumulator.snapshot())

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 预览缓存的刷新戳，key 为 chatId，值为生成预览时的 `updated_at`。 */
    private val _previewStamps = mutableMapOf<String, String?>()

    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notifications: SharedFlow<String> = _notifications.asSharedFlow()

    /**
     * 更新链路状态。
     *
     * 只在真的变化时写 state，避免每次轮询成功都触发一次整页重组。
     */
    private fun updateLink(next: LinkState) {
        if (_state.value.link == next) return
        _state.update { it.copy(link = next) }
    }

    /**
     * 交给系统网络状态覆盖链路状态。
     *
     * 顶栏的「已连接」此前只由请求成败推断，而请求在无网时也会走缓存、
     * 界面上仍留着上一次的 ONLINE。关掉网络后没有任何请求立刻失败，
     * 那一行就会继续显示「已连接」，与实际相反。
     *
     * 断网直接置 OFFLINE；恢复时不能直接置 ONLINE——那只是「可能连上了」，
     * 真正是否通要由随后的请求来证明，因此置 UNKNOWN（显示「正在连接…」）。
     */
    fun setNetworkState(online: Boolean) {
        if (!online) {
            updateLink(LinkState.OFFLINE)
            return
        }
        if (_state.value.link == LinkState.OFFLINE && !_state.value.running) {
            updateLink(LinkState.UNKNOWN)
        }
    }

    /**
     * 只替换连接地址，不动会话状态。
     *
     * 与 [bind] 的区别：换实例要清空当前会话（旧 id 属于另一台服务器），
     * 而同一个实例换地址只是换了条路到同一台服务器，会话应当保留。
     */
    fun rebindConnection(conn: Connection) {
        this.currentConnection = conn
    }

    /**
     * 重新载入当前会话。
     *
     * 这是断网恢复后的主要入口：`openChat` 会重拉历史、用服务端的 status
     * 校对 running、必要时重挂 SSE，并刷新 thinking / 模型与轮询。
     * 若当前没有会话，则退化为刷新会话列表。
     */
    fun refreshCurrent() {
        val chatId = _state.value.currentChatId
        if (chatId.isNullOrBlank()) {
            refreshChats()
        } else {
            openChat(chatId)
        }
    }

    /**
     * 绑定连接。
     *
     * **只有真正换到另一台服务器时才清空当前会话。**
     *
     * 此前是无条件清空（只判 `currentChatId != null`），而 [AppViewModel.refreshAll]
     * 也用这个方法做「重连 / 网络恢复 / 改地址」，于是同一实例重连一次就把
     * `currentChatId` 置成 null，用户接着发消息时 `send()` 看到会话为空便新建一个，
     * 表现为「我明明在这个会话里发消息，它却新建了会话」。
     *
     * 冷启动首次绑定（[instanceId] 为空串）同样不清空：那时本来就没有会话可清。
     */
    fun bind(
        instanceId: String,
        conn: Connection,
        userId: String,
        /** 实例显示名，用于顶栏「已通过 … 连接至 QwenPaw」。 */
        instanceLabel: String = "",
        /** 服务器名称，用于顶栏末尾的归属名；为空时仍写 QwenPaw。 */
        instanceName: String = "",
    ) {
        val switching = shouldClearOnBind(this.instanceId, instanceId)
        this.instanceId = instanceId
        this.currentConnection = conn
        this.userId = userId
        _state.update {
            it.copy(
                boundUrl = conn.baseUrl,
                boundLabel = instanceLabel.ifBlank { conn.baseUrl },
                boundServerName = instanceName,
            )
        }
        if (switching) {
            // 换实例：旧会话 id 在新服务器上不存在，历史前缀必须一起清掉，
            // 否则发第一条消息时会看到上一条服务器的残留。
            accumulator = StreamAccumulator()
            historyPrefix = emptyList()
            _state.update {
                it.copy(
                    currentChatId = null,
                    currentChatName = "",
                    messages = emptyList(),
                    draft = "",
                    attachments = emptyList(),
                    pendingApprovals = emptyList(),
                    link = LinkState.UNKNOWN,
                )
            }
        }
        // 审批模式与工作区目录都挂在智能体上，换实例就要重新读一遍。
        refreshAgent(conn.agentId)
        refreshProjectDir()
    }

    // ---------------- 列表 ----------------

    /**
     * 拉取会话列表。
     *
     * [selectChatId] 是明确的跳转目标（用户点了某个会话）；
     * [restoreChatId] 是冷启动时想恢复的会话，找不到就退回最近活跃的那个。
     * 两者都没有时自动打开列表里的第一个——主页面是聊天页，不能停在空列表上。
     */
    fun refreshChats(selectChatId: String? = null, restoreChatId: String? = null) {
        val conn = currentConnection ?: return
        refreshGroups()
        refreshArchivedChats()
        // 下拉刷新的转圈由 loading 驱动，所以这里也要先置位；
        // 只有列表为空时才会铺满整页，非空列表只是顶部转一圈。
        _state.update { it.copy(loading = true) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.chats(conn) } }
                .onSuccess { chats ->
                    val ordered = orderChats(chats)
                    val live = ordered.map { it.id }.toSet()
                    val target = selectChatId?.takeIf { it in live }
                        ?: _state.value.currentChatId?.takeIf { it in live }
                        ?: restoreChatId?.takeIf { it in live }
                        ?: ordered.firstOrNull()?.id
                    _state.update { it.copy(chats = ordered, loading = false) }
                    if (target != null) {
                        openChat(target, reloadHistory = false)
                    } else {
                        // 一个会话都没有：留在聊天页的空态，发消息时会自动建新会话。
                        _state.update {
                            it.copy(loading = false, messages = emptyList(), currentChatId = null)
                        }
                    }
                    loadPreviews(conn, ordered)
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = friendly(e)) }
                }
        }
    }

    /**
     * 服务端不保证顺序，客户端自己排：
     * 置顶优先 → 运行中 → 最近活跃。
     */
    private fun orderChats(chats: List<ChatSpecDto>): List<ChatSpecDto> =
        chats.sortedWith(
            compareByDescending<ChatSpecDto> { it.pinned }
                .thenByDescending { it.status == "running" }
                .thenByDescending { it.updatedAt ?: "" },
        )

    /**
     * 为列表生成消息预览。
     *
     * 服务端没有 summary 字段，只能对最近的会话各取一次详情，摘出最后一条
     * 有内容的消息。只做最近 [PREVIEW_LIMIT] 个，且已缓存且 `updated_at` 未变
     * 的会话会跳过，避免每次刷新都打一堆请求。
     */
    private suspend fun loadPreviews(conn: Connection, chats: List<ChatSpecDto>) {
        val previous = _state.value.previews
        val stampKey = _previewStamps
        val targets = chats.take(PREVIEW_LIMIT).filter { chat ->
            stampKey[chat.id] != chat.updatedAt ||
                (chat.id !in previous && chat.updatedAt != null) ||
                (chat.id !in previous && stampKey[chat.id] == null)
        }
        if (targets.isEmpty()) return
        val fresh = mutableMapOf<String, String>()
        val freshStamps = mutableMapOf<String, String?>()
        for (chat in targets) {
            val text = runCatching {
                withContext(Dispatchers.IO) { api.chat(conn, chat.id) }
            }.getOrNull()?.let { previewOf(HistoryMapper.map(it.messages)) }
            if (text != null) fresh[chat.id] = text
            freshStamps[chat.id] = chat.updatedAt
        }
        if (fresh.isEmpty() && freshStamps.isEmpty()) return
        _previewStamps.putAll(freshStamps)
        // 清掉已被删除会话的缓存，避免 map 无限增长。
        val liveIds = chats.map { it.id }.toSet()
        _previewStamps.keys.retainAll(liveIds)
        _state.update { s ->
            val merged = (s.previews + fresh).filterKeys { it in liveIds }
            // 内容没变就不要换引用，否则每次刷新都会让整个列表重组一次。
            if (merged == s.previews) s else s.copy(previews = merged)
        }
    }

    /** 从映射后的消息里摘出一句预览文案。 */
    private fun previewOf(messages: List<ChatMessage>): String? {
        for (message in messages.asReversed()) {
            when (message.kind) {
                ChatMessage.Kind.TEXT -> {
                    val line = message.text.lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        ?.trim()
                    if (line.isNullOrBlank()) continue
                    // 服务端会给带附件的消息注入「用户上传文件，已经下载到 <路径>」，
                    // 这既不是用户原话也不是助手回复，出现在预览里只是一串路径噪声。
                    if (line.startsWith(UPLOAD_NOTICE_PREFIX)) continue
                    val prefix = if (message.role == Role.USER) "你：" else ""
                    return (prefix + line).take(60)
                }

                ChatMessage.Kind.IMAGE -> return "[图片]"
                ChatMessage.Kind.TOOL -> {
                    val name = message.tool?.name
                    if (!name.isNullOrBlank()) return "[工具] $name"
                }

                ChatMessage.Kind.REASONING -> Unit
            }
        }
        return null
    }

    // ---------------- 归档 ----------------

    fun refreshArchivedChats() {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.chats(conn, archived = true) } }
                .onSuccess { archived ->
                    _state.update { it.copy(archivedChats = orderChats(archived)) }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    fun archiveChat(chatId: String) {
        val conn = currentConnection ?: return
        // 名字必须在过滤之前取：onSuccess 里先把这条从 chats 移除，
        // 之后再按 id 去 chats 里查就永远查不到，提示会固定退化成「会话」。
        val name = _state.value.chats.firstOrNull { it.id == chatId }?.name
            ?: _state.value.archivedChats.firstOrNull { it.id == chatId }?.name
            ?: "会话"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.archiveChat(conn, chatId) } }
                .onSuccess {
                    val wasCurrent = _state.value.currentChatId == chatId
                    if (wasCurrent) {
                        // 当前会话被归档：界面消息清空，历史前缀也得一起清，
                        // 否则之后在这个空会话里发消息，会先把已归档的内容重新拼回来。
                        accumulator = StreamAccumulator()
                        historyPrefix = emptyList()
                    }
                    _state.update { s ->
                        val chat = s.chats.firstOrNull { it.id == chatId }
                        s.copy(
                            chats = s.chats.filterNot { it.id == chatId },
                            archivedChats = orderChats(
                                (listOfNotNull(chat?.copy(archived = true)) + s.archivedChats)
                                    .distinctBy { it.id },
                            ),
                            currentChatId = if (s.currentChatId == chatId) null else s.currentChatId,
                            messages = if (s.currentChatId == chatId) emptyList() else s.messages,
                        )
                    }
                    _notifications.tryEmit("已归档「$name」")
                }
                .onFailure { e ->
                    // 运行中的会话服务端会拒绝归档（409），这里给一句更直白的提示。
                    val msg = if (e is ApiException && e.code == 409) {
                        "会话正在运行，无法归档，请先停止"
                    } else {
                        friendly(e)
                    }
                    _state.update { it.copy(error = msg) }
                }
        }
    }

    fun unarchiveChat(chatId: String) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.unarchiveChat(conn, chatId) } }
                .onSuccess { restored ->
                    _state.update { s ->
                        s.copy(
                            archivedChats = s.archivedChats.filterNot { it.id == chatId },
                            chats = orderChats(
                                (listOf(restored) + s.chats.filterNot { it.id == chatId })
                                    .distinctBy { it.id },
                            ),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    // ---------------- 批量操作 ----------------

    /** 批量归档。运行中的会话会被服务端跳过，结果里体现为 failed。 */
    fun batchArchive(chatIds: List<String>) {
        val conn = currentConnection ?: return
        if (chatIds.isEmpty()) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.batchArchiveChats(conn, chatIds) } }
                .onSuccess { result ->
                    val okIds = result.succeeded.toSet()
                    if (_state.value.currentChatId in okIds) {
                        accumulator = StreamAccumulator()
                        historyPrefix = emptyList()
                    }
                    _state.update { s ->
                        val moved = s.chats.filter { it.id in okIds }
                        s.copy(
                            chats = s.chats.filterNot { it.id in okIds },
                            archivedChats = orderChats(
                                (moved.map { it.copy(archived = true) } + s.archivedChats)
                                    .distinctBy { it.id },
                            ),
                        )
                    }
                    val skipped = result.failed.size
                    val msg = buildString {
                        append("已归档 ${okIds.size} 个会话")
                        if (skipped > 0) append("，$skipped 个正在运行已跳过")
                    }
                    _notifications.tryEmit(msg)
                }
                .onFailure { e ->
                    val msg = if (e is ApiException && e.code == 409) {
                        "部分会话正在运行，无法归档"
                    } else {
                        friendly(e)
                    }
                    _state.update { it.copy(error = msg) }
                }
        }
    }

    /** 批量删除。 */
    fun batchDelete(chatIds: List<String>) {
        val conn = currentConnection ?: return
        if (chatIds.isEmpty()) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.batchDeleteChats(conn, chatIds) } }
                .onSuccess {
                    val removed = chatIds.toSet()
                    if (_state.value.currentChatId in removed) {
                        accumulator = StreamAccumulator()
                        historyPrefix = emptyList()
                    }
                    _state.update { s ->
                        s.copy(
                            chats = s.chats.filterNot { it.id in removed },
                            // 当前会话在被删之列就一起清空。
                            currentChatId = s.currentChatId?.takeUnless { it in removed },
                            messages = if (s.currentChatId in removed) emptyList() else s.messages,
                        )
                    }
                    withContext(Dispatchers.IO) {
                        chatIds.forEach { db.chatDao().clearMessages(it) }
                    }
                    _notifications.tryEmit("已删除 ${chatIds.size} 个会话")
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    // ---------------- 置顶 ----------------

    fun togglePin(chatId: String) {
        val conn = currentConnection ?: return
        val current = _state.value.chats.firstOrNull { it.id == chatId }
            ?: _state.value.archivedChats.firstOrNull { it.id == chatId }
            ?: return
        val next = !current.pinned
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setPinned(conn, chatId, next) } }
                .onSuccess { updated ->
                    _state.update { s ->
                        s.copy(
                            chats = orderChats(s.chats.map { if (it.id == updated.id) updated else it }),
                            archivedChats = orderChats(
                                s.archivedChats.map { if (it.id == updated.id) updated else it },
                            ),
                            currentChatPinned =
                            if (s.currentChatId == updated.id) updated.pinned else s.currentChatPinned,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    // ---------------- 分组 ----------------

    fun refreshGroups() {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.chatGroups(conn) } }
                .onSuccess { groups ->
                    _state.update { it.copy(groups = groups) }
                }
                .onFailure { e ->
                    // 失败也记一条：顶栏「移动到分组」的入口依赖 groups 非空，
                    // 这里静默失败会让那个菜单项凭空消失，排查时无从下手。
                    io.qwenpaw.node.log.RunLog.e(
                        io.qwenpaw.node.log.LogKind.NETWORK,
                        "ChatVM",
                        e,
                        "读取分组列表失败",
                    )
                }
        }
    }

    fun setChatGroup(chatId: String, groupId: String?) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setChatGroup(conn, chatId, groupId) } }
                .onSuccess { updated ->
                    _state.update { s ->
                        s.copy(
                            chats = s.chats.map { if (it.id == updated.id) updated else it },
                            currentChatGroupId =
                            if (s.currentChatId == updated.id) updated.groupId else s.currentChatGroupId,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    /**
     * 新建分组。
     *
     * 成功后重新拉一次列表而不是本地追加：`order` 由服务端排，
     * 自己插一条会与真实顺序不一致，下次刷新又跳位置。
     */
    fun createGroup(name: String, onDone: (Boolean) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.createChatGroup(conn, trimmed) } }
                .onSuccess { created ->
                    _state.update { s ->
                        s.copy(
                            groups = s.groups + created,
                            error = "已创建分组「${created.name.ifBlank { created.id }}」",
                        )
                    }
                    refreshGroups()
                    onDone(true)
                }
                .onFailure { e ->
                    _state.update { it.copy(error = friendly(e)) }
                    onDone(false)
                }
        }
    }

    /**
     * 重命名分组。
     *
     * 内置分组（`default` / `cron` / `subagents`）由服务端保护，客户端先拦一次：
     * 服务端会报错，但报错文案不会解释「这是内置分组」。
     */
    fun renameGroup(groupId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (!isCustomGroup(groupId)) {
            _state.update { it.copy(error = "内置分组不可重命名") }
            return
        }
        val conn = currentConnection ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.updateChatGroup(conn, groupId, name = trimmed) }
            }
                .onSuccess { refreshGroups() }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    /**
     * 删除分组。组内会话由服务端重新归位到默认分组，因此删除后要重拉会话列表。
     */
    fun deleteGroup(groupId: String) {
        if (!isCustomGroup(groupId)) {
            _state.update { it.copy(error = "内置分组不可删除") }
            return
        }
        val conn = currentConnection ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.deleteChatGroup(conn, groupId) }
            }
                .onSuccess {
                    refreshGroups()
                    refreshChats()
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    /** 是否为可编辑的自定义分组。 */
    private fun isCustomGroup(groupId: String): Boolean {
        val group = _state.value.groups.firstOrNull { it.id == groupId }
        // 查不到时按自定义处理：宁可让服务端报错，也不要错误地拦住用户的新分组。
        return group == null || group.kind == null || group.kind == CUSTOM_KIND
    }

    // ---------------- 思考模式 / 模型 ----------------

    fun refreshThinking() {
        val conn = currentConnection ?: return
        val chatId = _state.value.currentChatId ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.chatThinking(conn, chatId) } }
                .onSuccess { view ->
                    _state.update { s ->
                        s.copy(
                            thinking = view,
                            contextMax = view.effectiveMaxInputLength ?: s.contextMax,
                            currentModelLabel = view.modelName ?: view.model,
                        )
                    }
                }
        }
    }

    /**
     * 拉取可切换的模型列表。
     *
     * 只用 `GET /api/models` 的 `models` / `extra_models` / `discovered_models`
     * 三个数组，不调 `/api/models/{id}/pool`——本地部署下它返回空数组。
     * 未启用的供应商直接跳过：它们的模型即便列出来也用不了。
     */
    fun refreshModels() {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.providers(conn) } }
                .onSuccess { providers ->
                    val choices = providers
                        .filter { it.enabled }
                        .flatMap { provider ->
                            provider.allModels.map { slot ->
                                ModelChoice(
                                    providerId = provider.id,
                                    providerName = provider.name.ifBlank { provider.id },
                                    modelId = slot.id,
                                    modelName = slot.name.ifBlank { slot.id },
                                )
                            }
                        }
                        .distinctBy { it.key }
                    _state.update { it.copy(modelChoices = choices) }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    /**
     * 为当前会话切换模型。
     *
     * 传 null 表示「跟随智能体默认」。**实测服务端不接受 null**：
     * `PUT /model` 带 `provider_id: null` 会返回 400（`Input should be a valid string`），
     * 带空串则报 `Model provider is unavailable`。因此恢复默认的做法是先读
     * `GET /api/models/active` 拿到全局默认，再显式 `PUT` 回去。
     */
    fun setModel(providerId: String?, model: String?) {
        val conn = currentConnection ?: return
        val chatId = _state.value.currentChatId ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val (p, m) = if (providerId.isNullOrBlank() || model.isNullOrBlank()) {
                        val active = api.activeModel(conn).activeLlm
                        active?.providerId to active?.model
                    } else {
                        providerId to model
                    }
                    if (p.isNullOrBlank() || m.isNullOrBlank()) {
                        throw IllegalStateException("服务端没有配置默认模型，无法恢复默认")
                    }
                    api.setChatModel(conn, chatId, p, m)
                }
            }.onSuccess { view ->
                _state.update {
                    it.copy(
                        thinking = view,
                        contextMax = view.effectiveMaxInputLength ?: it.contextMax,
                        currentModelLabel = view.modelName ?: view.model,
                        error = null,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    // ---------------- 智能体配置 / 工作区 ----------------

    /** 读取智能体信息（名称与审批模式）。`approvalLevel` 是智能体级配置。 */
    fun refreshAgent(agentId: String) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.agent(conn, agentId) } }
                .onSuccess { profile ->
                    _state.update {
                        it.copy(agentName = profile.name, approvalLevel = profile.approvalLevel)
                    }
                }
        }
    }

    /**
     * 修改智能体审批模式。
     *
     * 影响**该智能体的全部会话**，不只是当前会话，因此界面上的说明要讲清楚范围。
     */
    fun setApprovalLevel(agentId: String, level: String) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setApprovalLevel(conn, agentId, level) }
            }.onSuccess { profile ->
                _state.update { it.copy(approvalLevel = profile.approvalLevel, error = null) }
            }.onFailure { e ->
                _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    /** 读取当前工作区目录。 */
    fun refreshProjectDir() {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.projectDirectory(conn) } }
                .onSuccess { dir -> _state.update { it.copy(projectDir = dir) } }
        }
    }

    /** 切换工作区目录。`path` 为 null 时恢复工作区默认目录。 */
    fun setProjectDir(path: String?) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setProjectDirectory(conn, path) }
            }.onSuccess { dir ->
                _state.update { it.copy(projectDir = dir, error = null) }
            }.onFailure { e ->
                // 服务端对不存在的路径返回 400，原文是英文，这里给出更直白的说明。
                val msg = if (e is ApiException && e.code == 400) {
                    "目录不存在或不可用，请确认这是服务端上的绝对路径"
                } else {
                    friendly(e)
                }
                _state.update { it.copy(error = msg) }
            }
        }
    }

    fun setThinking(level: String, budgetTokens: Long? = null) {
        val conn = currentConnection ?: return
        val chatId = _state.value.currentChatId ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setChatThinking(conn, chatId, level, budgetTokens) }
            }.onSuccess { view ->
                _state.update { it.copy(thinking = view, error = null) }
            }.onFailure { e ->
                val msg = if (e is ApiException && e.code == 422) {
                    "当前模型不支持这个思考档位"
                } else {
                    friendly(e)
                }
                _state.update { it.copy(error = msg) }
            }
        }
    }

    // ---------------- 导出 / 复制 ----------------

    /** 把整段会话拼成 Markdown 文本，供复制或导出。 */
    fun exportMarkdown(): String =
        markdownOf(_state.value.currentChatName, _state.value.messages)

    /** 把给定标题与消息列表拼成 Markdown（列表页复制全部也复用）。 */
    fun markdownOf(title: String, messages: List<ChatMessage>): String = buildString {
        append("# ").append(title.ifBlank { "会话" }).append("\n\n")
        messages.forEach { message ->
            when (message.kind) {
                ChatMessage.Kind.TEXT -> {
                    append(if (message.role == Role.USER) "**你**" else "**助手**")
                    append("\n\n").append(message.text).append("\n\n")
                }

                ChatMessage.Kind.IMAGE -> append("![图片](")
                    .append(message.imageUrl.orEmpty()).append(")\n\n")

                ChatMessage.Kind.TOOL -> {
                    append("> 工具 `").append(message.tool?.name.orEmpty()).append("`")
                    append(if (message.tool?.done == true) "（完成）" else "（运行中）")
                    append("\n\n")
                }

                ChatMessage.Kind.REASONING -> {
                    append("<details><summary>思考过程</summary>\n\n")
                    append(message.text).append("\n\n</details>\n\n")
                }
            }
        }
    }

    // ---------------- 工具函数 ----------------

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * 打开会话。
     *
     * [reloadHistory] 为 false 时只同步标题与运行状态，不再重复拉历史，
     * 避免刷新列表时把消息列表清空重建。
     */
    fun openChat(chatId: String, reloadHistory: Boolean = true) {
        val conn = currentConnection ?: return
        if (!reloadHistory && chatId == _state.value.currentChatId) {
            _state.update {
                val chat = it.chats.firstOrNull { c -> c.id == chatId }
                it.copy(
                    currentChatName = chat?.name ?: it.currentChatName,
                    currentChatPinned = chat?.pinned ?: it.currentChatPinned,
                    currentChatGroupId = chat?.groupId ?: it.currentChatGroupId,
                )
            }
            return
        }
        streamJob?.cancel()
        val known = _state.value.chats.firstOrNull { it.id == chatId }
            ?: _state.value.archivedChats.firstOrNull { it.id == chatId }
        _state.update {
            it.copy(
                currentChatId = chatId,
                loading = true,
                error = null,
                currentChatPinned = known?.pinned ?: false,
                currentChatGroupId = known?.groupId,
                thinking = null,
            )
        }

        scope.launch {
            if (chatId.isEmpty()) {
                // 新会话：不请求历史，直接进入可输入状态。
                accumulator = StreamAccumulator()
                historyPrefix = emptyList()
                _state.update {
                    it.copy(
                        loading = false,
                        messages = emptyList(),
                        running = false,
                        currentChatName = "",
                        pendingApprovals = emptyList(),
                    )
                }
                startPolling()
                return@launch
            }

            val history = runCatching { withContext(Dispatchers.IO) { api.chat(conn, chatId) } }
            val mapped = history.getOrNull()?.let { HistoryMapper.map(it.messages) }
            val status = history.getOrNull()?.status
            val name = known?.name.orEmpty()

            if (mapped != null) {
                accumulator = StreamAccumulator()
                // 历史留在独立的前缀里，后续流式增量只追在它后面。
                historyPrefix = mapped
                updateLink(LinkState.ONLINE)
                _state.update {
                    it.copy(
                        loading = false,
                        messages = mapped,
                        currentChatName = name,
                        running = status == "running",
                    )
                }
                cacheMessages(chatId, mapped)
                loadDraft(chatId)
                if (status == "running") {
                    attachRunningStream(chatId)
                }
            } else {
                // 离线：回退到本地缓存。
                history.exceptionOrNull()?.let {
                    io.qwenpaw.node.log.RunLog.e(
                        io.qwenpaw.node.log.LogKind.NETWORK,
                        "ChatVM",
                        it,
                        "拉取历史失败 chat=$chatId",
                    )
                }
                updateLink(LinkState.OFFLINE)
                val cached = withContext(Dispatchers.IO) { db.chatDao().messagesOnce(chatId) }
                val cachedMessages = cached.map { entity ->
                    ChatMessage(
                        id = entity.id,
                        role = io.qwenpaw.node.data.stream.Role.valueOf(entity.role),
                        kind = ChatMessage.Kind.valueOf(entity.kind),
                        text = entity.text,
                        imageUrl = entity.payload?.takeIf {
                            ChatMessage.Kind.valueOf(entity.kind) == ChatMessage.Kind.IMAGE
                        },
                    )
                }
                // 缓存同样是「已有历史」，之后发消息不能把它冲掉。
                historyPrefix = cachedMessages
                _state.update {
                    it.copy(
                        loading = false,
                        messages = cachedMessages,
                        currentChatName = name,
                        error = "无法连接服务器，显示本地缓存",
                    )
                }
                loadDraft(chatId)
            }
            refreshThinking()
            refreshModels()
            startPolling()
        }
    }

    fun createChat() {
        val conn = currentConnection ?: return
        scope.launch {
            val sessionId = "mobile-" + UUID.randomUUID().toString().take(8)
            runCatching { withContext(Dispatchers.IO) { api.createChat(conn, "新会话", sessionId, userId) } }
                .onSuccess { chat ->
                    _state.update { it.copy(chats = listOf(chat) + it.chats, currentChatId = chat.id) }
                    openChat(chat.id)
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    fun renameChat(chatId: String, name: String) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.renameChat(conn, chatId, name) } }
                .onSuccess { updated ->
                    _state.update { s ->
                        s.copy(
                            chats = s.chats.map { if (it.id == updated.id) updated else it },
                            currentChatName = if (s.currentChatId == updated.id) updated.name else s.currentChatName,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    fun deleteChat(chatId: String) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteChat(conn, chatId) } }
                .onSuccess {
                    _state.update { s ->
                        val remaining = s.chats.filterNot { it.id == chatId }
                        s.copy(chats = remaining)
                    }
                    withContext(Dispatchers.IO) { db.chatDao().clearMessages(chatId) }
                    val next = _state.value.chats.firstOrNull()
                    if (next != null) openChat(next.id) else {
                        accumulator = StreamAccumulator()
                        historyPrefix = emptyList()
                        _state.update { it.copy(currentChatId = null, messages = emptyList()) }
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    // ---------------- 草稿 ----------------

    fun updateDraft(text: String) {
        _state.update { it.copy(draft = text) }
        val chatId = _state.value.currentChatId ?: return
        saveDebounce?.cancel()
        saveDebounce = scope.launch {
            delay(400)
            withContext(Dispatchers.IO) {
                if (text.isBlank()) {
                    db.chatDao().deleteDraft(instanceId, chatId)
                } else {
                    db.chatDao().saveDraft(
                        DraftEntity(
                            key = "$instanceId:$chatId",
                            instanceId = instanceId,
                            chatId = chatId,
                            text = text,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    private fun loadDraft(chatId: String) {
        if (chatId.isBlank()) {
            _state.update { it.copy(draft = "") }
            return
        }
        scope.launch {
            val draft = withContext(Dispatchers.IO) { db.chatDao().draft(instanceId, chatId) }
            _state.update { it.copy(draft = draft?.text.orEmpty()) }
        }
    }

    // ---------------- 发送 ----------------

    fun addAttachment(url: String) {
        _state.update { it.copy(attachments = it.attachments + url) }
    }

    fun removeAttachment(url: String) {
        _state.update { it.copy(attachments = it.attachments - url) }
    }

    fun send(text: String = _state.value.draft) {
        val trimmed = text.trim()
        val attachments = _state.value.attachments
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (_state.value.running) {
            _state.update { it.copy(error = "当前会话有回复正在生成，请稍候或先停止") }
            return
        }
        val conn = currentConnection ?: return
        var chatId = _state.value.currentChatId

        // 先把用户自己要发的内容画到界面上，再去请求网络。
        //
        // 此前这一步发生在 `startRun` 内部，而 `startRun` 之前还要先等 `createChat`
        // 往返一次；没有当前会话时，这段时间界面完全不响应，看起来像发送失败。
        // 回显放在这里，点下「发送」的同一帧就能看到自己的消息。
        val echoed = echoUser(text = trimmed, attachments = attachments)

        scope.launch {
            if (chatId.isNullOrBlank()) {
                val sessionId = "mobile-" + UUID.randomUUID().toString().take(8)
                val created = runCatching {
                    withContext(Dispatchers.IO) { api.createChat(conn, trimmed.take(20), sessionId, userId) }
                }.getOrElse { e ->
                    // 建会话失败：请求从未离开设备，撤回刚才的回显，改用错误气泡承载原文，
                    // 由它提供「重试」。留着回显会让用户以为消息已送达。
                    accumulator.rollback(echoed)
                    accumulator.appendError(friendly(e), retryText = trimmed)
                    _state.update {
                        it.copy(messages = mergedMessages(), error = friendly(e), running = false)
                    }
                    return@launch
                }
                chatId = created.id
                _state.update { it.copy(chats = listOf(created) + it.chats, currentChatId = created.id) }
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "ChatVM",
                    "本次没有当前会话，已新建 ${created.id}",
                )
            } else {
                // 复用路径也记一条：这条日志是判断「发消息为何新建会话」现场的直接依据。
                io.qwenpaw.node.log.RunLog.d(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "ChatVM",
                    "复用当前会话 $chatId",
                )
            }
            startRun(conn, chatId, trimmed, attachments)
        }
    }

    /**
     * 重试一条发送失败的消息。
     *
     * 失败气泡里保存了原始输入；重试时先移除这条错误，再用同样的文本重发。
     * 服务端没收到过这条消息（连接失败）或已落库（任务中断）两种情况都适用：
     * 前者是全新一轮，后者会作为新一轮继续对话。
     */
    fun retry(message: ChatMessage) {
        val text = message.retryText ?: return
        if (_state.value.running) {
            _state.update { it.copy(error = "当前会话有回复正在生成，请稍候或先停止") }
            return
        }
        accumulator.removeError(message.id)
        _state.update { it.copy(messages = mergedMessages(), error = null) }
        send(text)
    }

    /**
     * 把用户刚发出的内容画到界面上，并立刻清空输入框。
     *
     * 与 `startRun` 分开的原因是时序：`send()` 可能要先建会话，那是一次网络往返。
     * 回显若留到 `startRun` 里，这段往返期间界面不会有任何变化，
     * 用户看到的是「点了发送没反应」。
     *
     * 返回这次回显产生的 id，建会话失败时按它们撤回。
     */
    private fun echoUser(text: String, attachments: List<String>): List<String> {
        val ids = mutableListOf<String>()
        if (text.isNotBlank()) ids += accumulator.appendUser(text).id
        // 图片附件发送后立即回显，服务端历史里也会带回来，但重连前用户能先看到。
        val imageAttachments = attachments.filter { isImageName(it.substringAfterLast('/')) }
        imageAttachments.forEach { url ->
            ids += accumulator.appendUserImage(url, url.substringAfterLast('/')).id
        }
        // 非图片附件没有可渲染的缩略图，补一行文字说明。
        // 判据是「没有任何用户侧内容」而不是「文本与附件都为空」：
        // 后者在 send() 里已被提前 return，写在这里等于这段永远不执行，
        // 只发附件时界面顶部会直接是「处理中」，用户会以为没发出去。
        filePlaceholders(attachments, text, ::isImageName).forEach {
            ids += accumulator.appendUser(it).id
        }
        _state.update {
            it.copy(
                messages = mergedMessages(),
                draft = "",
                attachments = emptyList(),
                // 立即置 running：等待行与「停止」按钮都靠它出现。
                // 首个 token 到达前界面上要一直有「正在生成」，不能等到请求回来才变。
                running = true,
                error = null,
            )
        }
        return ids
    }

    private fun startRun(conn: Connection, chatId: String, text: String, attachments: List<String>) {
        val parts = buildList {
            if (text.isNotBlank()) add(api.textPart(text))
            attachments.forEach { url ->
                val name = url.substringAfterLast('/')
                val kind = if (isImageName(name)) "image" else "file"
                add(api.mediaPart(kind, url, name))
            }
        }
        val body = api.buildChatBody(
            sessionId = _sessionIdOf(chatId),
            userId = userId,
            parts = parts,
        )

        // 用户消息已在 send() 里回显，这里不再重复追加。
        _state.update {
            it.copy(
                // 历史 + 本轮新增。不能只写 accumulator 快照，那会把历史整屏冲掉。
                messages = mergedMessages(),
                draft = "",
                attachments = emptyList(),
                running = true,
                error = null,
            )
        }
        // 立即清掉持久化草稿：updateDraft 带 400ms 防抖，
        // 若只靠它更新状态，发送成功后再次进入会话会看到过期草稿。
        saveDebounce?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) { db.chatDao().deleteDraft(instanceId, chatId) }
        }

        streamJob?.cancel()
        streamJob = scope.launch {
            var failure: String? = null
            try {
                streamClient.open(conn, body).collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> {
                            accumulator.onDelta(event)
                            pushSnapshot()
                        }

                        is ChatEvent.MessageBlock -> {
                            accumulator.onMessageBlock(event)
                            pushSnapshot()
                        }

                        is ChatEvent.ToolCall -> {
                            accumulator.onToolCall(event)
                            pushSnapshot()
                        }

                        is ChatEvent.ToolOutput -> {
                            accumulator.onToolOutput(event)
                            pushSnapshot()
                        }

                        is ChatEvent.TurnUsage -> {
                            event.stats?.let { accumulator.applyTurnStats(it) }
                            _state.update {
                                it.copy(
                                    contextUsage = event.ratio,
                                    contextTokens = event.estimatedTokens,
                                    contextMax = event.maxInputLength,
                                )
                            }
                        }

                        is ChatEvent.ResponseStatus -> {
                            if (event.status == "completed" || event.status == "failed") {
                                accumulator.finish()
                                pushSnapshot()
                            }
                        }

                        is ChatEvent.RateLimited -> {
                            failure = buildString {
                                append("模型限流：")
                                append(event.message)
                                if (event.alternatives.isNotEmpty()) {
                                    append("\n可用备选：")
                                    append(event.alternatives.joinToString("、"))
                                }
                            }
                        }

                        is ChatEvent.Failure -> {
                            failure = event.message
                        }

                        ChatEvent.Ignored -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // 业务错误也是断流的一种现场，记下来才分得清是网络问题还是服务端拒绝。
                io.qwenpaw.node.log.RunLog.e(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "ChatVM",
                    e,
                    "发送失败 chat=$chatId",
                )
                failure = friendly(e)
            } catch (e: Exception) {
                io.qwenpaw.node.log.RunLog.e(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "ChatVM",
                    e,
                    "发送异常 chat=$chatId",
                )
                failure = friendly(e)
            }

            val reason: String? = failure
            if (reason != null) {
                accumulator.appendError(reason, retryText = text)
                // 这里是「请求真的失败了」的第一现场。此前只写错误文案，
                // 顶栏的链路状态不变，于是断网时错误条与「已连接」同时出现。
                updateLink(LinkState.OFFLINE)
            }
            accumulator.finish()
            _state.update { it.copy(running = false, error = reason) }
            pushSnapshot()
            // 流结束（含断流）后都与服务端对齐一次，避免界面残留未落库的内容。
            reconcileWithServer(conn, chatId)
            finishRun(conn, chatId)
        }
    }

    /**
     * 收尾：缓存消息、按需发通知、刷新列表。
     *
     * [notify] 为 false 时不发「回复已完成」。用户主动点「停止」属于中断，
     * 不是服务端把回复跑完了；此时再弹一条「回复已完成」会让人以为任务正常结束。
     */
    private fun finishRun(conn: Connection, chatId: String, notify: Boolean = true) {
        cacheMessages(chatId, _state.value.messages)
        if (notify) notifyIfBackground()
        // 服务端会在首轮自动生成会话标题，结束后刷新一次列表。
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.chats(conn) } }
                .onSuccess { chats ->
                    _state.update { s ->
                        s.copy(
                            chats = chats,
                            currentChatName = chats.firstOrNull { it.id == chatId }?.name ?: s.currentChatName,
                        )
                    }
                }
        }
    }

    /**
     * 回复结束时如果 App 不在前台，发一条本地通知。
     *
     * QwenPaw 没有推送通道，这里只是「本机已经拿到结果」的提醒：
     * 用户切走时流仍在接收，结束时立刻提示，比下次打开 App 再发现要快。
     */
    private fun notifyIfBackground() {
        if (isAppVisible()) return
        val title = _state.value.currentChatName.ifBlank { "会话" }
        val reply = _state.value.messages.lastOrNull { it.role == Role.ASSISTANT && it.kind == ChatMessage.Kind.TEXT }
        val preview = reply?.text?.takeIf { it.isNotBlank() }?.let { body ->
            body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80)
        } ?: "服务端回复已完成"
        ChatNotifier.notifyDone(
            appContext,
            appContext.getString(io.qwenpaw.node.R.string.notify_done_title, title),
            preview,
        )
    }

    /**
     * 用服务端历史覆盖本地视图。
     *
     * 用于「停止生成」「断线重连」之后：服务端会保存用户消息与已生成的部分内容，
     * 本地折叠结果可能缺帧或与落库版本不同，必须对齐一次避免消息丢失。
     */
    private suspend fun reconcileWithServer(conn: Connection, chatId: String) {
        runCatching { withContext(Dispatchers.IO) { api.chat(conn, chatId) } }
            .onSuccess { history ->
                val mapped = HistoryMapper.map(history.messages)
                if (mapped.isNotEmpty()) {
                    accumulator = StreamAccumulator()
                    // 服务端历史成为新的前缀，本轮的流式增量已被它覆盖。
                    historyPrefix = mapped
                    _state.update { it.copy(messages = mapped) }
                    cacheMessages(chatId, mapped)
                }
            }
    }

    /**
     * 断线或离开页面后重新挂回同一 chat 的运行流。
     *
     * 这里有一处容易漏掉的状态：服务端在这一轮跑完之后会关掉 SSE，
     * 客户端看到的只是「流结束了」，而 `collect` 正常返回时并不会有人替我们
     * 把 `running` 复位成 false。若只依赖增量帧，界面就会永远停在
     * 「处理中 / 运行中」——用户只能退出重进。因此流一旦结束，先复位状态，
     * 再用服务端历史覆盖一次本地视图，把流式标记和最终正文一起对齐。
     */
    private fun attachRunningStream(chatId: String) {
        val conn = currentConnection ?: return
        val body = api.buildChatBody(_sessionIdOf(chatId), userId, emptyList(), reconnect = true)
        accumulator = StreamAccumulator()
        // 历史消息已渲染在界面上，重连只接收后续增量。
        streamJob?.cancel()
        streamJob = scope.launch {
            io.qwenpaw.node.log.RunLog.d(
                io.qwenpaw.node.log.LogKind.STREAM,
                "ChatVM",
                "重连既有流 chat=$chatId",
            )
            val collected = runCatching {
                streamClient.open(conn, body).collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> accumulator.onDelta(event)
                        is ChatEvent.MessageBlock -> accumulator.onMessageBlock(event)
                        is ChatEvent.ToolCall -> accumulator.onToolCall(event)
                        is ChatEvent.ToolOutput -> accumulator.onToolOutput(event)
                        is ChatEvent.TurnUsage -> _state.update {
                            it.copy(
                                contextUsage = event.ratio,
                                contextTokens = event.estimatedTokens,
                                contextMax = event.maxInputLength,
                            )
                        }
                        is ChatEvent.ResponseStatus -> if (event.status == "completed") accumulator.finish()
                        else -> Unit
                    }
                    val appended = accumulator.snapshot()
                    if (appended.isNotEmpty()) {
                        // 按 id 合并：同一 msg_id 的增量应替换而非叠加，
                        // 重复 key 会让 LazyColumn 直接抛异常。
                        _state.update { s ->
                            val merged = (s.messages + appended)
                                .distinctBy { it.id }
                            s.copy(messages = HistoryMapper.uniqueIds(merged))
                        }
                    }
                }
            }
            // 重连本身失败等于链路断了：不写这一笔，顶栏会一直停在上一状态。
            // 正常结束（服务端已完成、流自然收尾）不算断线，因此只在异常时置 OFFLINE。
            // 主动停止会让 `stop()` 取消这个协程，那时抛的是 CancellationException，
            // 它不是网络故障，必须排除，否则点一次「停止」顶栏就显示断线。
            collected.exceptionOrNull()?.takeIf { it !is CancellationException }?.let { e ->
                io.qwenpaw.node.log.RunLog.e(
                    io.qwenpaw.node.log.LogKind.STREAM,
                    "ChatVM",
                    e,
                    "重连流失败 chat=$chatId",
                )
                updateLink(LinkState.OFFLINE)
            }
            // 无论正常结束还是中途断开，都要把界面从「进行中」拉回来。
            // 先清掉 state 里的流式标记：reconcileWithServer 在离线时会失败，
            // 那时界面仍用本地合并结果，若不清标记就还停在「运行中」。
            accumulator.finish()
            val finalized = accumulator.snapshot()
            _state.update { s ->
                // 收尾快照里带着统计与 turnEnd 标记，必须并回界面，
                // 否则重连路径下这一轮的数字永远不显示。
                val merged = if (finalized.isEmpty()) {
                    s.messages.map { it.copy(streaming = false) }
                } else {
                    s.messages.map { it.copy(streaming = false) }
                        .filterNot { old -> finalized.any { it.id == old.id } } + finalized
                }
                s.copy(running = false, messages = HistoryMapper.uniqueIds(merged))
            }
            reconcileWithServer(conn, chatId)
            finishRun(conn, chatId)
        }
    }

    fun stop() {
        val conn = currentConnection ?: return
        val chatId = _state.value.currentChatId ?: return

        // 先在本地立刻断开流并复位 UI，避免用户等待网络往返。
        // 取消 streamJob 会抛 CancellationException，因此收尾必须放在
        // 新的协程里，不能继续沿用被取消的那个。
        streamJob?.cancel()
        accumulator.finish()
        _state.update { it.copy(running = false, messages = mergedMessages()) }

        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.stopChat(conn, chatId) } }
                .onFailure { e ->
                    // 服务端已经很可能是 idle（任务自然结束），提示出来即可，
                    // 不要因此把界面重新标记为运行中。
                    _state.update { s -> s.copy(error = friendly(e)) }
                }
            reconcileWithServer(conn, chatId)
            // 主动停止不是「服务端把回复跑完」，不再发完成提醒。
            finishRun(conn, chatId, notify = false)
        }
    }

    // ---------------- 审批 ----------------

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            // 轮询每 2.5 秒一次，断网期间会持续失败。只每满 10 次记一条，
            // 否则一分钟就能刷出二十多条相同内容，把真正有用的现场挤掉。
            var failures = 0
            while (true) {
                val conn = currentConnection
                val chatId = _state.value.currentChatId
                if (conn != null) {
                    runCatching {
                        withContext(Dispatchers.IO) { api.pendingApprovals(conn, _sessionIdOf(chatId)) }
                    }.onSuccess { approvals ->
                        if (failures >= 10) {
                            io.qwenpaw.node.log.RunLog.d(
                                io.qwenpaw.node.log.LogKind.NETWORK,
                                "Poll",
                                "审批轮询恢复，此前连续失败 $failures 次",
                            )
                        }
                        failures = 0
                        updateLink(LinkState.ONLINE)
                        val previous = _state.value.pendingApprovals
                        // 审批列表通常没变化，避免每次轮询都产生新状态导致整页重组。
                        if (approvals == previous) return@onSuccess
                        val previousIds = previous.map { it.requestId }.toSet()
                        val fresh = approvals.filterNot { it.requestId in previousIds }
                        if (fresh.isNotEmpty()) {
                            _notifications.tryEmit("有 ${fresh.size} 个操作等待审批")
                        }
                        _state.update { it.copy(pendingApprovals = approvals) }
                    }.onFailure { e ->
                        failures++
                        // 轮询是唯一持续进行的请求，它的成败最能反映链路状态。
                        updateLink(LinkState.OFFLINE)
                        if (failures == 1 || failures % 10 == 0) {
                            io.qwenpaw.node.log.RunLog.e(
                                io.qwenpaw.node.log.LogKind.NETWORK,
                                "Poll",
                                e,
                                "审批轮询失败，已连续 $failures 次",
                            )
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun approve(approval: ApprovalDto) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.approve(conn, approval.requestId, approval.sessionId, userId)
                }
            }.onSuccess {
                _state.update { s ->
                    s.copy(pendingApprovals = s.pendingApprovals.filterNot { it.requestId == approval.requestId })
                }
            }.onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    fun deny(approval: ApprovalDto) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.deny(conn, approval.requestId, approval.sessionId, "用户从手机端拒绝")
                }
            }.onSuccess {
                _state.update { s ->
                    s.copy(pendingApprovals = s.pendingApprovals.filterNot { it.requestId == approval.requestId })
                }
            }.onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    // ---------------- 工具 ----------------

    fun detach() {
        streamJob?.cancel()
        pollJob?.cancel()
        pollJob = null
    }

    private fun pushSnapshot() {
        _state.update { it.copy(messages = mergedMessages()) }
    }

    private fun cacheMessages(chatId: String, messages: List<ChatMessage>) {
        if (chatId.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                db.chatDao().saveMessages(
                    messages.mapIndexed { index, message ->
                        CachedMessageEntity(
                            id = message.id,
                            chatId = chatId,
                            instanceId = instanceId,
                            role = message.role.name,
                            kind = message.kind.name,
                            text = message.text,
                            // 图片消息的 url 存在 payload 里，工具卡片沿用旧格式。
                            payload = when {
                                message.kind == ChatMessage.Kind.IMAGE -> message.imageUrl
                                else -> message.tool?.let {
                                    "${it.name}\u0000${it.arguments}\u0000${it.output.orEmpty()}"
                                }
                            },
                            createdAt = System.currentTimeMillis(),
                            position = index,
                        )
                    },
                )
            }
        }
    }

    private fun isImageName(name: String): Boolean {
        val lower = name.lowercase()
        return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun _sessionIdOf(chatId: String?): String {
        if (chatId.isNullOrBlank()) return ""
        return _state.value.chats.firstOrNull { it.id == chatId }?.sessionId
            ?: chatId
    }

    private fun friendly(e: Throwable): String = ErrorText.of(e)

    companion object {
        private const val POLL_INTERVAL_MS = 2500L

        /** 只为最近的这么多个会话生成列表预览，避免刷新时打太多详情请求。 */
        private const val PREVIEW_LIMIT = 20

        private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")

        /** 服务端为附件消息注入的提示前缀，预览时要跳过。 */
        private const val UPLOAD_NOTICE_PREFIX = "用户上传文件，已经下载到"
    }
}
