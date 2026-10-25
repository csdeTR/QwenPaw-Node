package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.local.AppDatabase
import io.qwenpaw.node.data.local.CachedMessageEntity
import io.qwenpaw.node.data.local.DraftEntity
import io.qwenpaw.node.data.model.ApprovalDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.ChatStreamClient
import io.qwenpaw.node.data.remote.Connection
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
    val pendingApprovals: List<ApprovalDto> = emptyList(),
    val chats: List<ChatSpecDto> = emptyList(),
    val currentChatId: String? = null,
    val currentChatName: String = "",
)

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
    private var saveDebounce: Job? = null

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notifications: SharedFlow<String> = _notifications.asSharedFlow()

    fun bind(instanceId: String, conn: Connection, userId: String) {
        this.instanceId = instanceId
        this.currentConnection = conn
        this.userId = userId
    }

    // ---------------- 列表 ----------------

    fun refreshChats(selectChatId: String? = null) {
        val conn = currentConnection ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.chats(conn) } }
                .onSuccess { chats ->
                    // 服务端不保证顺序：运行中的会话必须置顶，其余按最近活跃排序，
                    // 否则正在生成的会话会沉到列表底部，用户很难找到。
                    val ordered = chats.sortedWith(
                        compareByDescending<ChatSpecDto> { it.status == "running" }
                            .thenByDescending { it.updatedAt ?: "" },
                    )
                    val target = selectChatId ?: _state.value.currentChatId
                    _state.update { it.copy(chats = ordered, loading = false) }
                    when {
                        target != null -> openChat(target, reloadHistory = false)
                        // 冷启动不自动跳转：先停在列表页，由「运行中」置顶提示用户去接。
                        else -> _state.update { it.copy(loading = false, messages = emptyList()) }
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = friendly(e)) }
                }
        }
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
                it.copy(
                    currentChatName = it.chats.firstOrNull { chat -> chat.id == chatId }?.name
                        ?: it.currentChatName,
                )
            }
            return
        }
        streamJob?.cancel()
        _state.update { it.copy(currentChatId = chatId, loading = true, error = null) }

        scope.launch {
            if (chatId.isEmpty()) {
                // 新会话：不请求历史，直接进入可输入状态。
                accumulator = StreamAccumulator()
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
            val name = _state.value.chats.firstOrNull { it.id == chatId }?.name.orEmpty()

            if (mapped != null) {
                accumulator = StreamAccumulator()
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
                val cached = withContext(Dispatchers.IO) { db.chatDao().messagesOnce(chatId) }
                _state.update {
                    it.copy(
                        loading = false,
                        messages = cached.map { entity ->
                            ChatMessage(
                                id = entity.id,
                                role = io.qwenpaw.node.data.stream.Role.valueOf(entity.role),
                                kind = ChatMessage.Kind.valueOf(entity.kind),
                                text = entity.text,
                            )
                        },
                        currentChatName = name,
                        error = "无法连接服务器，显示本地缓存",
                    )
                }
                loadDraft(chatId)
            }
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

        scope.launch {
            if (chatId.isNullOrBlank()) {
                val sessionId = "mobile-" + UUID.randomUUID().toString().take(8)
                val created = runCatching {
                    withContext(Dispatchers.IO) { api.createChat(conn, trimmed.take(20), sessionId, userId) }
                }.getOrElse { e ->
                    _state.update { it.copy(error = friendly(e)) }
                    return@launch
                }
                chatId = created.id
                _state.update { it.copy(chats = listOf(created) + it.chats, currentChatId = created.id) }
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
        _state.update { it.copy(messages = accumulator.snapshot(), error = null) }
        send(text)
    }

    private fun startRun(conn: Connection, chatId: String, text: String, attachments: List<String>) {
        val parts = buildList {
            if (text.isNotBlank()) add(api.textPart(text))
            attachments.forEach { url ->
                val kind = if (url.contains(".png") || url.contains(".jpg") || url.contains(".jpeg") ||
                    url.contains(".webp") || url.contains(".gif")
                ) {
                    "image"
                } else {
                    "file"
                }
                add(api.mediaPart(kind, url))
            }
        }
        val body = api.buildChatBody(
            sessionId = _sessionIdOf(chatId),
            userId = userId,
            parts = parts,
        )

        accumulator.appendUser(text.ifBlank { "[附件]" })
        _state.update {
            it.copy(
                messages = accumulator.snapshot(),
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
                            _state.update { it.copy(contextUsage = event.ratio) }
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
                failure = friendly(e)
            } catch (e: Exception) {
                failure = friendly(e)
            }

            val reason: String? = failure
            if (reason != null) {
                accumulator.appendError(reason, retryText = text)
            }
            accumulator.finish()
            _state.update { it.copy(running = false, error = reason) }
            pushSnapshot()
            // 流结束（含断流）后都与服务端对齐一次，避免界面残留未落库的内容。
            reconcileWithServer(conn, chatId)
            finishRun(conn, chatId)
        }
    }

    private fun finishRun(conn: Connection, chatId: String) {
        cacheMessages(chatId, _state.value.messages)
        notifyIfBackground()
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
                    _state.update { it.copy(messages = mapped) }
                    cacheMessages(chatId, mapped)
                }
            }
    }

    /** 断线或离开页面后重新挂回同一 chat 的运行流。 */
    private fun attachRunningStream(chatId: String) {
        val conn = currentConnection ?: return
        val body = api.buildChatBody(_sessionIdOf(chatId), userId, emptyList(), reconnect = true)
        accumulator = StreamAccumulator()
        // 历史消息已渲染在界面上，重连只接收后续增量。
        streamJob?.cancel()
        streamJob = scope.launch {
            runCatching {
                streamClient.open(conn, body).collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> accumulator.onDelta(event)
                        is ChatEvent.MessageBlock -> accumulator.onMessageBlock(event)
                        is ChatEvent.ToolCall -> accumulator.onToolCall(event)
                        is ChatEvent.ToolOutput -> accumulator.onToolOutput(event)
                        is ChatEvent.TurnUsage -> _state.update { it.copy(contextUsage = event.ratio) }
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
            _state.update { it.copy(running = false) }
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
        _state.update { it.copy(running = false, messages = accumulator.snapshot()) }

        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.stopChat(conn, chatId) } }
                .onFailure { e ->
                    // 服务端已经很可能是 idle（任务自然结束），提示出来即可，
                    // 不要因此把界面重新标记为运行中。
                    _state.update { s -> s.copy(error = friendly(e)) }
                }
            reconcileWithServer(conn, chatId)
            finishRun(conn, chatId)
        }
    }

    // ---------------- 审批 ----------------

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val conn = currentConnection
                val chatId = _state.value.currentChatId
                if (conn != null) {
                    runCatching {
                        withContext(Dispatchers.IO) { api.pendingApprovals(conn, _sessionIdOf(chatId)) }
                    }.onSuccess { approvals ->
                        val previous = _state.value.pendingApprovals.map { it.requestId }.toSet()
                        val fresh = approvals.filterNot { it.requestId in previous }
                        if (fresh.isNotEmpty()) {
                            _notifications.tryEmit("有 ${fresh.size} 个操作等待审批")
                        }
                        _state.update { it.copy(pendingApprovals = approvals) }
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

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun detach() {
        streamJob?.cancel()
        pollJob?.cancel()
        pollJob = null
    }

    private fun pushSnapshot() {
        val snapshot = accumulator.snapshot()
        _state.update { it.copy(messages = snapshot) }
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
                            payload = message.tool?.let { "${it.name}\u0000${it.arguments}\u0000${it.output.orEmpty()}" },
                            createdAt = System.currentTimeMillis(),
                            position = index,
                        )
                    },
                )
            }
        }
    }

    private fun _sessionIdOf(chatId: String?): String {
        if (chatId.isNullOrBlank()) return ""
        return _state.value.chats.firstOrNull { it.id == chatId }?.sessionId
            ?: chatId
    }

    private fun friendly(e: Throwable): String = when (e) {
        is ApiException -> when (e.code) {
            401 -> "登录已失效，请重新登录"
            404 -> "会话不存在或已被删除"
            409 -> "该会话已有任务在运行，请先停止或等待完成"
            503 -> "Console 频道未启用，请在服务端开启"
            else -> e.message
        }

        // OkHttp 会把底层网络异常包成普通 IOException，这里按类型链和文案一并识别。
        else -> when {
            e.hasCause<java.net.UnknownHostException>() -> "找不到服务器，请检查地址与网络"
            e.hasCause<java.net.ConnectException>() || e.isConnectFailure() ->
                "无法连接服务器，请确认 QwenPaw 已启动且地址端口正确"

            e.hasCause<java.net.SocketTimeoutException>() -> "连接超时，请检查网络后重试"
            e is java.io.IOException -> "网络中断：请检查网络后重试（草稿已保存）"
            else -> e.message ?: "未知错误"
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.isConnectFailure(): Boolean {
        val text = message.orEmpty()
        // localhost 被防火墙丢包时 OkHttp 报 "failed to connect ... after 20000ms"，
        // 这是连接层面失败，应归到「连不上」而非「网络中断」。
        return text.contains("Failed to connect", ignoreCase = true) ||
            text.contains("Connection refused", ignoreCase = true) ||
            text.contains("Network is unreachable", ignoreCase = true)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2500L
    }
}
