package io.qwenpaw.node.ui.agents

import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.model.AgentStatusDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表里的一行：智能体本身加上它的运行时状态。 */
data class AgentRow(
    val agent: AgentDto,
    val status: AgentStatusDto? = null,
)

data class AgentsUiState(
    val rows: List<AgentRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** 当前实例绑定的智能体；用于列表里标出「当前」。 */
    val currentAgentId: String? = null,
)

/**
 * 智能体管理：列表、增删改、启停、置顶、排序与复制。
 *
 * 每次写操作后整表重拉。智能体数量在个位数，乐观更新带来的状态回滚反而更容易
 * 出现「界面已变、服务端没变」的错位；重拉一次最省心。
 */
class AgentsViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val onAgentDeleted: (String) -> Unit,
    private val onUseAgent: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = _state.asStateFlow()

    fun setCurrentAgent(agentId: String?) {
        _state.update { it.copy(currentAgentId = agentId) }
    }

    fun refresh() {
        val conn = connection() ?: run {
            _state.update { it.copy(loading = false, loaded = true, error = "尚未连接服务器") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    val agents = api.agents(conn)
                    // 逐个取状态：数量很少，顺序请求比并发更容易读日志，
                    // 也不会在弱网下同时挂起一堆请求。
                    agents.map { agent ->
                        val status = runCatching { api.agentStatus(conn, agent.id) }.getOrNull()
                        AgentRow(agent, status)
                    }
                }
                _state.update { it.copy(rows = rows, loading = false, loaded = true, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                }
            }
        }
    }

    fun create(name: String, description: String, id: String, onResult: (Boolean) -> Unit) {
        val conn = connection() ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.createAgent(conn, name.trim(), description.trim(), id.trim().ifBlank { null })
                }
            }.onSuccess {
                _state.update { it.copy(busy = false, notice = "已创建智能体「${name.trim()}」") }
                refresh()
                onResult(true)
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
                onResult(false)
            }
        }
    }

    fun copy(
        sourceId: String,
        name: String,
        copySkills: Boolean,
        copyJobs: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        val conn = connection() ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.copyAgent(
                        conn = conn,
                        sourceId = sourceId,
                        name = name.trim().ifBlank { null },
                        copySkills = copySkills,
                        copyJobs = copyJobs,
                    )
                }
            }.onSuccess {
                _state.update { it.copy(busy = false, notice = "已复制智能体") }
                refresh()
                onResult(true)
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
                onResult(false)
            }
        }
    }

    fun rename(agentId: String, name: String, description: String, onResult: (Boolean) -> Unit) {
        val conn = connection() ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.updateAgentProfile(conn, agentId, name.trim(), description.trim())
                }
            }.onSuccess {
                _state.update { it.copy(busy = false, notice = "已保存") }
                refresh()
                onResult(true)
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
                onResult(false)
            }
        }
    }

    fun setEnabled(agentId: String, enabled: Boolean) {
        val conn = connection() ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setAgentEnabled(conn, agentId, enabled) }
            }.onSuccess {
                _state.update {
                    it.copy(busy = false, notice = if (enabled) "已启用" else "已禁用")
                }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun setPinned(agentId: String, pinned: Boolean) {
        val conn = connection() ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setAgentPinned(conn, agentId, pinned) }
            }.onSuccess {
                _state.update {
                    it.copy(busy = false, notice = if (pinned) "已置顶" else "已取消置顶")
                }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun move(agentId: String, delta: Int) {
        val conn = connection() ?: return
        val ids = AgentOrdering.move(_state.value.rows.map { it.agent }, agentId, delta) ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.reorderAgents(conn, ids) }
            }.onSuccess {
                _state.update { it.copy(busy = false) }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
                refresh()
            }
        }
    }

    fun delete(agentId: String) {
        val conn = connection() ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.deleteAgent(conn, agentId) }
            }.onSuccess {
                _state.update { it.copy(busy = false, notice = "已删除智能体") }
                onAgentDeleted(agentId)
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun useAsCurrent(agentId: String) {
        onUseAgent(agentId)
        _state.update { it.copy(currentAgentId = agentId, notice = "已切换当前实例的智能体") }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }

    fun clear() {
        _state.value = AgentsUiState()
    }
}

/** 智能体排序规则：default 固定第一，置顶块与未置顶块互不跨越。 */
internal object AgentOrdering {

    /** 返回新的完整 id 列表；无法移动时返回 null。 */
    fun move(agents: List<AgentDto>, agentId: String, delta: Int): List<String>? {
        if (agentId == "default") return null
        val index = agents.indexOfFirst { it.id == agentId }
        if (index < 0) return null
        val target = index + delta
        if (target <= 0 || target >= agents.size) return null
        val moving = agents[index]
        val other = agents[target]
        if (other.id == "default") return null
        if (moving.pinned != other.pinned) return null
        val reordered = agents.toMutableList()
        reordered[index] = other
        reordered[target] = moving
        return reordered.map { it.id }
    }
}

/** 删除智能体后，实例应当回退到 default。 */
internal fun fallbackAgentId(current: String, deletedId: String): String =
    if (current == deletedId) "default" else current
