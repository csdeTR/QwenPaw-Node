package io.qwenpaw.node.ui.acl

import io.qwenpaw.node.data.model.AclActionEntryDto
import io.qwenpaw.node.data.model.AclChannelDto
import io.qwenpaw.node.data.model.AclPendingDto
import io.qwenpaw.node.data.model.MailAclActionEntryDto
import io.qwenpaw.node.data.model.MailAclPauseDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AclTab { CHAT, MAIL }

/** 访问控制页状态。 */
data class AclUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val tab: AclTab = AclTab.CHAT,
    val chatChannels: Map<String, AclChannelDto> = emptyMap(),
    val chatPending: List<AclPendingDto> = emptyList(),
    val mailChannels: Map<String, AclChannelDto> = emptyMap(),
    val mailPending: List<AclPendingDto> = emptyList(),
    val mailPendingCount: Int = 0,
    val mailAgents: List<String> = emptyList(),
    val mailPauses: List<MailAclPauseDto> = emptyList(),
    /** 邮件 tab 的智能体筛选；null 表示全部。 */
    val mailFilter: String? = null,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
) {
    val pending: List<AclPendingDto>
        get() = if (tab == AclTab.CHAT) chatPending else mailPending

    val visibleMailChannels: Map<String, AclChannelDto>
        get() = mailFilter?.let { filter ->
            mailChannels.filterKeys { it == filter }
        } ?: mailChannels
}

/**
 * 聊天渠道与邮件的待审批队列、名单增删。
 *
 * 两个 tab 的数据路径不同：聊天按 `channel` 分组，邮件按 `agent_id` 分组；
 * 动作 body 也各有一套字段，因此不合并成同一组 DTO。
 */
class AclViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AclUiState())
    val state: StateFlow<AclUiState> = _state.asStateFlow()

    fun refresh() {
        val conn = connection() ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val chatOverview = async(Dispatchers.IO) { runCatching { api.aclOverview(conn) } }
            val chatPending = async(Dispatchers.IO) { runCatching { api.aclPending(conn) } }
            val mailOverview = async(Dispatchers.IO) { runCatching { api.mailAclOverview(conn) } }
            val mailPending = async(Dispatchers.IO) { runCatching { api.mailAclPending(conn) } }
            val mailCount = async(Dispatchers.IO) { runCatching { api.mailAclPendingCount(conn) } }
            val mailAgents = async(Dispatchers.IO) { runCatching { api.mailAclAgents(conn) } }
            val mailPauses = async(Dispatchers.IO) { runCatching { api.mailAclPauses(conn) } }
            val failure = listOf(chatOverview, chatPending, mailOverview, mailPending, mailCount)
                .firstNotNullOfOrNull { it.await().exceptionOrNull() }
            _state.update {
                it.copy(
                    loading = false,
                    error = failure?.let(ErrorText::of),
                    chatChannels = chatOverview.await().getOrDefault(emptyMap()),
                    chatPending = chatPending.await().getOrDefault(emptyList()),
                    mailChannels = mailOverview.await().getOrDefault(emptyMap()),
                    mailPending = mailPending.await().getOrDefault(emptyList()),
                    mailPendingCount = mailCount.await().getOrDefault(0),
                    // 两个邮件辅助端点失败不把整页打成错误：名单与待审批仍可用。
                    mailAgents = mailAgents.await().getOrDefault(emptyList()),
                    mailPauses = mailPauses.await().getOrDefault(emptyList()),
                )
            }
        }
    }

    fun setTab(tab: AclTab) {
        _state.update { it.copy(tab = tab, selected = emptySet()) }
    }

    fun setMailFilter(agentId: String?) {
        _state.update { it.copy(mailFilter = agentId) }
    }

    /**
     * 完整名单中的条目编辑。
     *
     * 聊天与邮件各有一套端点：备注改的是「任意名单里的这一条」，
     * 用户名只有聊天渠道有；邮件的新增显示名只能在加入时写一次。
     */
    fun editEntry(
        group: String,
        userId: String,
        remark: String,
        username: String,
        blacklist: Boolean,
        moveToOther: Boolean,
        displayName: String = "",
    ) {
        val conn = connection() ?: return
        val mail = _state.value.tab == AclTab.MAIL
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (!mail) {
                        api.aclListRemark(conn, group, userId, remark)
                        if (username.isNotBlank()) {
                            api.aclUsername(conn, group, userId, username)
                        }
                        if (moveToOther) {
                            val target = if (blacklist) "whitelist/add" else "blacklist/add"
                            val source = if (blacklist) "blacklist/remove" else "whitelist/remove"
                            val entry = AclActionEntryDto(
                                channel = group,
                                userId = userId,
                                remark = remark,
                                username = username,
                            )
                            api.aclAction(conn, target, listOf(entry))
                            api.aclAction(conn, source, listOf(entry))
                        }
                    } else {
                        api.mailAclListRemark(conn, group, userId, remark)
                        if (moveToOther) {
                            val target = if (blacklist) "whitelist/add" else "blacklist/add"
                            val source = if (blacklist) "blacklist/remove" else "whitelist/remove"
                            val entry = MailAclActionEntryDto(
                                agentId = group,
                                address = userId,
                                remark = remark.takeIf { it.isNotBlank() },
                                displayName = displayName.takeIf { it.isNotBlank() },
                            )
                            api.mailAclAction(conn, target, listOf(entry))
                            api.mailAclAction(conn, source, listOf(entry))
                        }
                    }
                }
            }
            _state.update { it.copy(busy = false) }
            result.onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
            if (result.isSuccess) refresh()
        }
    }

    /** 新增条目；[broadcast] 只对邮件生效，`agent_id=""` 表示全部邮件智能体。 */
    fun addMemberFull(
        group: String,
        userId: String,
        remark: String,
        username: String,
        blacklist: Boolean,
        broadcast: Boolean,
    ) {
        val conn = connection() ?: return
        if (userId.isBlank()) return
        val mail = _state.value.tab == AclTab.MAIL
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val action = if (blacklist) "blacklist/add" else "whitelist/add"
                    if (!mail) {
                        api.aclAction(
                            conn,
                            action,
                            listOf(
                                AclActionEntryDto(
                                    channel = group,
                                    userId = userId,
                                    remark = remark,
                                    username = username,
                                ),
                            ),
                        )
                    } else {
                        api.mailAclAction(
                            conn,
                            action,
                            listOf(
                                MailAclActionEntryDto(
                                    agentId = if (broadcast) "" else group,
                                    address = userId,
                                    remark = remark.takeIf { it.isNotBlank() },
                                    displayName = username.takeIf { it.isNotBlank() },
                                ),
                            ),
                        )
                    }
                }
            }
            _state.update { it.copy(busy = false) }
            result.onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
            if (result.isSuccess) refresh()
        }
    }

    fun resumeProcessing(agentId: String, pauseId: String) {
        val conn = connection() ?: return
        if (pauseId.isBlank()) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.mailAclResume(conn, agentId, pauseId) }
            }
            _state.update { it.copy(busy = false) }
            result.onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
            if (result.isSuccess) refresh()
        }
    }

    fun toggleSelected(key: String) {
        _state.update {
            val next = if (key in it.selected) it.selected - key else it.selected + key
            it.copy(selected = next)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    /** action 取 `pending/approve`、`pending/deny`、`pending/dismiss`。 */
    fun act(action: String, remark: String = "") {
        val conn = connection() ?: return
        val current = _state.value
        val entries = current.pending.filter { it.key in current.selected }
        if (entries.isEmpty()) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (current.tab == AclTab.CHAT) {
                        api.aclAction(
                            conn,
                            action,
                            entries.map {
                                AclActionEntryDto(
                                    channel = it.channel,
                                    userId = it.userId,
                                    remark = remark,
                                    username = it.username,
                                )
                            },
                        )
                    } else {
                        api.mailAclAction(
                            conn,
                            action,
                            entries.map {
                                MailAclActionEntryDto(
                                    agentId = it.agentId,
                                    address = it.effectiveAddress,
                                    remark = remark.takeIf { value -> value.isNotBlank() },
                                    displayName = it.displayName.takeIf { value -> value.isNotBlank() },
                                )
                            },
                        )
                    }
                }
            }
            _state.update { it.copy(busy = false, selected = emptySet()) }
            result.onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
            if (result.isSuccess) refresh()
        }
    }

    fun remark(key: String, remark: String) {
        val conn = connection() ?: return
        val current = _state.value
        val entry = current.pending.firstOrNull { it.key == key } ?: return
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (current.tab == AclTab.CHAT) {
                        api.aclRemark(conn, entry.channel, entry.userId, remark)
                    } else {
                        api.mailAclRemark(conn, entry.agentId, entry.effectiveAddress, remark)
                    }
                }
            }
            _state.update { it.copy(busy = false) }
            result.onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
            if (result.isSuccess) refresh()
        }
    }

    /** 单条名单增删。[group] 在聊天里是 channel，在邮件里是 agent_id。 */
    fun addMember(group: String, userId: String, remark: String) {
        mutateList("whitelist/add", group, userId, remark)
    }

    fun addBlacklist(group: String, userId: String, remark: String) {
        mutateList("blacklist/add", group, userId, remark)
    }

    fun removeMember(group: String, userId: String, blacklist: Boolean) {
        mutateList(if (blacklist) "blacklist/remove" else "whitelist/remove", group, userId, "")
    }

    private fun mutateList(action: String, group: String, userId: String, remark: String) {
        val conn = connection() ?: return
        val tab = _state.value.tab
        if (userId.isBlank()) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (tab == AclTab.CHAT) {
                        api.aclAction(
                            conn,
                            action,
                            listOf(AclActionEntryDto(channel = group, userId = userId, remark = remark)),
                        )
                    } else {
                        api.mailAclAction(
                            conn,
                            action,
                            listOf(
                                MailAclActionEntryDto(
                                    agentId = group,
                                    address = userId,
                                    remark = remark.takeIf { it.isNotBlank() },
                                ),
                            ),
                        )
                    }
                }
            }
            _state.update { it.copy(busy = false) }
            result.onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
            if (result.isSuccess) refresh()
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** 切实例或智能体时清空：待审批与名单都按实例/智能体隔离。 */
    fun clear() {
        _state.value = AclUiState()
    }
}
