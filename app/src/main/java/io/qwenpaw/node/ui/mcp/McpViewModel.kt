package io.qwenpaw.node.ui.mcp

import io.qwenpaw.node.data.model.McpClientDto
import io.qwenpaw.node.data.model.McpPolicyDto
import io.qwenpaw.node.data.model.McpToolDto
import io.qwenpaw.node.data.remote.ApiException
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

/**
 * MCP 页状态。
 *
 * [draftEnabled] 是白名单的本地勾选集合，[policy] 是策略的本地草稿：
 * 两者都只在点「保存」时提交。策略的编辑面只有默认效果与逐工具默认效果，
 * 其余字段由 [McpPolicyEdit] 原样带回。
 */
data class McpUiState(
    val clients: List<McpClientDto> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    /** 单行动作失败（启停）。键是客户端 key。 */
    val rowErrors: Map<String, String> = emptyMap(),
    val selectedKey: String? = null,
    val tools: List<McpToolDto> = emptyList(),
    val toolsLoading: Boolean = false,
    val toolsError: String? = null,
    val draftEnabled: Set<String> = emptySet(),
    val toolsDirty: Boolean = false,
    val savingTools: Boolean = false,
    val policy: McpPolicyDto? = null,
    val policyLoading: Boolean = false,
    val policyError: String? = null,
    val savingPolicy: Boolean = false,
    val notice: String? = null,
) {
    /** 当前选中的客户端。详情页各处都要用它判断是否已禁用。 */
    val selected: McpClientDto?
        get() = clients.firstOrNull { it.key == selectedKey }
}

/**
 * MCP：客户端列表、启停、工具白名单与访问策略。
 *
 * 工具列表与策略是两个独立请求：工具会实时连 MCP 服务端（可能 502），
 * 策略只读本地卡片文件。一个失败不该把另一个也打成错误。
 */
class McpViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    fun refresh() {
        val conn = connection() ?: run {
            _state.update { it.copy(loaded = true, loading = false, error = "尚未连接服务器") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.mcpClients(conn) } }
                .onSuccess { list ->
                    _state.update { current ->
                        current.copy(
                            clients = list,
                            loading = false,
                            loaded = true,
                            // 选中的那个可能已被删除，同步清掉详情，否则详情页会停在一个不存在的数据上。
                            selectedKey = current.selectedKey?.takeIf { key ->
                                list.any { it.key == key }
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
                }
        }
    }

    /** 打开某个客户端：工具与策略并行拉，各自独立记错误。 */
    fun select(key: String) {
        val conn = connection() ?: return
        // 卡片上的 `tools` 就是白名单：`null` 表示全部启用，此时草稿应先勾满，
        // 否则用户看到的是「一个都没勾」，一保存就把全部工具关掉了。
        val card = _state.value.clients.firstOrNull { it.key == key }
        _state.update {
            it.copy(
                selectedKey = key,
                tools = emptyList(),
                toolsError = null,
                toolsLoading = true,
                draftEnabled = emptySet(),
                toolsDirty = false,
                policy = null,
                policyError = null,
                policyLoading = true,
            )
        }
        scope.launch {
            val toolsJob = async {
                runCatching { withContext(Dispatchers.IO) { api.mcpTools(conn, key) } }
            }
            val policyJob = async {
                runCatching { withContext(Dispatchers.IO) { api.mcpPolicy(conn, key) } }
            }
            val tools = toolsJob.await()
            val policy = policyJob.await()
            _state.update { current ->
                if (current.selectedKey != key) return@update current
                val list = tools.getOrNull() ?: emptyList()
                val selectedTools = when {
                    card?.tools == null -> list.map { it.name }.toSet()
                    else -> list.filter { it.enabled }.map { it.name }.toSet()
                }
                current.copy(
                    tools = list,
                    toolsLoading = false,
                    // 已禁用的客户端服务端回空数组而不是报错，按客户端状态解释这个空列表。
                    toolsError = tools.exceptionOrNull()?.let { toolsFailureText(it) },
                    draftEnabled = selectedTools,
                    policy = policy.getOrNull(),
                    policyLoading = false,
                    policyError = policy.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
        }
    }

    fun closeDetail() {
        _state.update {
            it.copy(
                selectedKey = null, tools = emptyList(), policy = null,
                toolsError = null, policyError = null, notice = null,
            )
        }
    }

    /**
     * 切换启用状态。
     *
     * 端点取反且没有请求体，结果以返回的对象为准。禁用后工具列表会变空，
     * 此时把本地白名单草稿一并清掉，避免用户回到详情看到一份过时的勾选。
     */
    fun toggle(key: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.toggleMcpClient(conn, key) } }
                .onSuccess { updated ->
                    _state.update { current ->
                        current.copy(
                            clients = current.clients.map { if (it.key == key) updated else it },
                            rowErrors = current.rowErrors - key,
                            notice = if (updated.enabled) "已启用「${updated.name}」" else "已停用「${updated.name}」",
                            tools = if (!updated.enabled) emptyList() else current.tools,
                            draftEnabled = if (!updated.enabled) emptySet() else current.draftEnabled,
                            toolsDirty = false,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(rowErrors = it.rowErrors + (key to ErrorText.of(e))) }
                }
        }
    }

    /** 勾选或取消一个工具。只改本地草稿，点「保存」才提交。 */
    fun toggleTool(name: String) {
        _state.update { current ->
            val next = if (name in current.draftEnabled) {
                current.draftEnabled - name
            } else {
                current.draftEnabled + name
            }
            current.copy(draftEnabled = next, toolsDirty = true)
        }
    }

    /**
     * 提交白名单。
     *
     * `null` 与空列表的差别见 [McpPolicyEdit.whitelistSelection]：前者是「全部启用」，
     * 后者是「一个都不启用」。这里只调用那一个出口，不在页面里另算。
     */
    fun saveTools() {
        val current = _state.value
        val key = current.selectedKey ?: return
        if (current.savingTools) return
        val conn = connection() ?: return
        val payload = McpPolicyEdit.whitelistSelection(
            all = current.tools.map { it.name },
            enabled = current.draftEnabled,
        )
        _state.update { it.copy(savingTools = true, toolsError = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.saveMcpTools(conn, key, payload) } }
                .onSuccess { list ->
                    _state.update { state ->
                        state.copy(
                            tools = list,
                            draftEnabled = list.filter { it.enabled }.map { it.name }.toSet(),
                            toolsDirty = false,
                            savingTools = false,
                            notice = "工具白名单已保存",
                            // 客户端卡片上的 `tools` 也要同步，否则重进详情时勾选会回退。
                            clients = state.clients.map { client ->
                                if (client.key == key) client.copy(tools = payload) else client
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(savingTools = false, toolsError = toolsFailureText(e)) }
                }
        }
    }

    /** 改默认效果。只改草稿。 */
    fun setDefaultEffect(effect: String) {
        _state.update { current ->
            val policy = current.policy ?: return@update current
            current.copy(policy = McpPolicyEdit.withDefaultEffect(policy, effect))
        }
    }

    /**
     * 改某个工具的默认效果。
     *
     * 传空串表示移除该工具的单条设置，回到「跟随默认效果」。
     */
    fun setToolEffect(toolName: String, effect: String) {
        _state.update { current ->
            val policy = current.policy ?: return@update current
            current.copy(policy = McpPolicyEdit.withToolDefault(policy, toolName, effect))
        }
    }

    /**
     * 提交策略。
     *
     * 整份回写：`clientOverrides` 与 `toolOverrides` 会被原样带回，
     * 这是服务端重建托管规则的前提。
     */
    fun savePolicy() {
        val current = _state.value
        val key = current.selectedKey ?: return
        val policy = current.policy ?: return
        if (current.savingPolicy) return
        val conn = connection() ?: return
        _state.update { it.copy(savingPolicy = true, policyError = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.saveMcpPolicy(conn, key, policy) } }
                .onSuccess { saved ->
                    _state.update { state ->
                        state.copy(
                            policy = saved,
                            savingPolicy = false,
                            notice = "访问策略已保存",
                            clients = state.clients.map { client ->
                                if (client.key == key) {
                                    client.copy(
                                        accessSummary = client.accessSummary.copy(
                                            defaultEffect = saved.defaultEffect,
                                        ),
                                    )
                                } else {
                                    client
                                }
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(savingPolicy = false, policyError = ErrorText.of(e)) }
                }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clear() {
        _state.value = McpUiState()
    }

    /**
     * 工具列表失败的文案。
     *
     * 502 是「卡片写得对，但连不上那个 MCP 服务端」，与网络本身无关，
     * 因此不能说「无法连接服务器」——那会把用户引向检查手机网络。
     */
    private fun toolsFailureText(e: Throwable): String {
        val ex = e as? ApiException ?: return ErrorText.of(e)
        return when (ex.code) {
            502 -> "无法读取工具列表：MCP 服务端没有响应。请确认它在服务端可访问后重试。"
            else -> ErrorText.of(e)
        }
    }
}