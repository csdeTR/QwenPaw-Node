package io.qwenpaw.node.ui.mcp

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.McpClientDto
import io.qwenpaw.node.data.model.McpClientWriteDto
import io.qwenpaw.node.data.model.McpOAuthStartDto
import io.qwenpaw.node.data.model.McpOAuthStatusDto
import io.qwenpaw.node.data.model.McpPolicyDto
import io.qwenpaw.node.data.model.McpToolDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑页状态。
 *
 * [draft] 与 [initial] 都是完整草稿：前者随输入变化，后者是打开编辑页时的快照，
 * 只在 [dirty] 里用一次。保存失败时保留草稿与字段错误，用户改完可以直接再提交。
 */
data class McpEditorState(
    val draft: McpDraftState,
    val initial: McpDraftState,
    val saving: Boolean = false,
    val error: String? = null,
    /** 按字段折叠后的校验错误，每个字段只显示第一条。 */
    val fieldErrors: Map<McpDraftField, McpDraftError> = emptyMap(),
) {
    val isNew: Boolean get() = draft.isNew
    val dirty: Boolean get() = draft != initial
}

/** OAuth 授权的页内状态。轮询期间 [polling] 为真，[status] 是最近一次读到的服务端状态。 */
data class McpOAuthUi(
    /** 启动授权拿到的浏览器地址。Screen 打开后调用 consumeOAuthUrl 清掉，避免重复打开。 */
    val authUrl: String? = null,
    val starting: Boolean = false,
    val polling: Boolean = false,
    val status: McpOAuthStatusDto? = null,
    val error: String? = null,
)

/**
 * MCP 页状态。
 *
 * [draftEnabled] 是白名单的本地勾选集合，[policy] 是策略的本地草稿：
 * 两者都只在点「保存」时提交。策略的编辑面只有默认效果与逐工具默认效果，
 * 其余字段由 [McpPolicyEdit] 原样带回。
 *
 * [editor] 非空即处于编辑态：页面层据此整页切换到 [McpEditorScreen]，
 * 保存成功或取消后置回 `null`，页面自然回到出发处。
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
    val editor: McpEditorState? = null,
    val deleting: Boolean = false,
    val deleteError: String? = null,
    val oauth: McpOAuthUi = McpOAuthUi(),
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

    /** OAuth 轮询间隔与次数上限：2 秒一次、30 次，共 60 秒。 */
    private val pollIntervalMs = 2_000L
    private val pollTimes = 30

    fun refresh() {
        val conn = connection() ?: run {
            _state.update {
                it.copy(
                    loaded = true,
                    loading = false,
                    error = Texts.get(R.string.mcp_error_not_connected),
                )
            }
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
                oauth = McpOAuthUi(),
                deleteError = null,
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
                oauth = McpOAuthUi(), deleteError = null,
            )
        }
    }

    /** 打开新建表单。 [McpDraftState] 默认就是本机进程、创建后启用。 */
    fun startCreate() {
        val draft = McpEditDraft.blank()
        _state.update {
            it.copy(editor = McpEditorState(draft = draft, initial = draft), deleteError = null)
        }
    }

    /** 打开当前选中客户端的编辑表单。与新建共用同一个编辑器。 */
    fun startEdit() {
        val client = _state.value.selected ?: return
        val draft = McpEditDraft.from(client)
        _state.update {
            it.copy(editor = McpEditorState(draft = draft, initial = draft), deleteError = null)
        }
    }

    /** 输入变化。校验错误在下次保存时重算，不在按键过程中打断用户。 */
    fun updateDraft(draft: McpDraftState) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = editor.copy(draft = draft, error = null))
        }
    }

    /** 放弃编辑。未保存的更改由编辑页自己确认，这里只负责清状态。 */
    fun cancelEdit() {
        _state.update { it.copy(editor = null) }
    }

    /**
     * 提交编辑。
     *
     * 新建走 `POST /api/mcp`（body 的 `client_key` 由 API 层拼），编辑走部分更新：
     * 只发与原值不同的字段，没有差异时直接关闭并提示「没有需要保存的改动」，
     * 避免一次空 PUT 把服务端的 `updated_at` 之类的字段也改掉。
     */
    fun saveDraft() {
        val editor = _state.value.editor ?: return
        if (editor.saving) return
        val draft = editor.draft
        val errors = McpEditDraft.validate(draft)
        if (errors.isNotEmpty()) {
            _state.update {
                it.copy(
                    editor = editor.copy(fieldErrors = errors.associateBy { error -> error.field }),
                )
            }
            return
        }
        val conn = connection() ?: run {
            _state.update {
                it.copy(
                    editor = editor.copy(error = Texts.get(R.string.mcp_admin_error_not_connected)),
                )
            }
            return
        }
        val original = draft.original
        val key = draft.key.trim()
        val body = if (original == null) McpEditDraft.newClientBody(draft) else null
        val changes = original?.let { McpEditDraft.changes(draft, it) }
        if (changes != null && !McpEditDraft.hasChanges(changes)) {
            _state.update {
                it.copy(
                    editor = null,
                    notice = Texts.get(R.string.mcp_admin_notice_unchanged),
                )
            }
            return
        }
        _state.update {
            it.copy(editor = editor.copy(saving = true, error = null, fieldErrors = emptyMap()))
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (original == null) {
                        api.createMcpClient(conn, key, body ?: McpClientWriteDto())
                    } else {
                        api.updateMcpClient(conn, key, changes ?: McpClientWriteDto())
                    }
                }
            }
                .onSuccess { saved ->
                    _state.update { current ->
                        val existing = current.clients.any { it.key == saved.key }
                        current.copy(
                            editor = null,
                            clients = if (existing) {
                                current.clients.map { if (it.key == saved.key) saved else it }
                            } else {
                                current.clients + saved
                            },
                            notice = if (original == null) {
                                Texts.get(R.string.mcp_admin_notice_created, saved.name.ifBlank { saved.key })
                            } else {
                                Texts.get(R.string.mcp_admin_notice_updated, saved.name.ifBlank { saved.key })
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(editor = editor.copy(saving = false, error = adminFailureText(e)))
                    }
                }
        }
    }

    /**
     * 删除当前选中的客户端。
     *
     * 端点会同时删掉客户端的连接凭据与 OAuth 令牌，因此删除后把工具、策略与 OAuth
     * 状态一并清空——留着会让详情页停在一份已不存在的数据上。
     */
    fun deleteSelected() {
        val key = _state.value.selectedKey ?: return
        val name = _state.value.selected?.name?.ifBlank { key } ?: key
        if (_state.value.deleting) return
        val conn = connection() ?: return
        _state.update { it.copy(deleting = true, deleteError = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteMcpClient(conn, key) } }
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            clients = current.clients.filterNot { it.key == key },
                            selectedKey = null,
                            tools = emptyList(),
                            draftEnabled = emptySet(),
                            toolsDirty = false,
                            policy = null,
                            oauth = McpOAuthUi(),
                            deleting = false,
                            deleteError = null,
                            notice = Texts.get(R.string.mcp_admin_notice_deleted, name),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            deleting = false,
                            deleteError = Texts.get(
                                R.string.mcp_admin_delete_failed,
                                adminFailureText(e),
                            ),
                        )
                    }
                }
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
                            notice = if (updated.enabled) {
                                Texts.get(R.string.mcp_notice_enabled, updated.name)
                            } else {
                                Texts.get(R.string.mcp_notice_disabled, updated.name)
                            },
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
                            notice = Texts.get(R.string.mcp_notice_tools_saved),
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
                            notice = Texts.get(R.string.mcp_notice_policy_saved),
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

    /**
     * 启动 OAuth 2.1 PKCE 流程。
     *
     * 服务端只接受客户端自己的 `url` 作为授权目标，本机进程没有可授权的服务端，直接拒绝。
     * 拿到 `auth_url` 后交给页面打开浏览器，并开始 [pollIntervalMs] 一次的轮询，
     * 最多 [pollTimes] 次即 60 秒；轮询超时不报错，只把「等待中」停掉，用户可以手动再查一次。
     */
    fun startOAuth() {
        val current = _state.value
        val client = current.selected ?: return
        val remote = McpEditDraft.isRemote(client.transport)
        if (!remote) {
            _state.update {
                it.copy(oauth = it.oauth.copy(error = Texts.get(R.string.mcp_admin_oauth_error_stdio)))
            }
            return
        }
        val url = client.url.trim()
        if (url.isEmpty()) {
            _state.update {
                it.copy(oauth = it.oauth.copy(error = Texts.get(R.string.mcp_admin_oauth_error_url)))
            }
            return
        }
        if (current.oauth.starting) return
        val conn = connection() ?: return
        val key = client.key
        _state.update { it.copy(oauth = it.oauth.copy(starting = true, error = null)) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.mcpOAuthStart(conn, key, McpOAuthStartDto(url = url))
                }
            }
                .onSuccess { result ->
                    _state.update { state ->
                        if (state.selectedKey != key) return@update state
                        state.copy(
                            oauth = state.oauth.copy(
                                starting = false,
                                polling = true,
                                authUrl = result.authUrl.takeIf { it.isNotBlank() },
                                error = null,
                            ),
                        )
                    }
                    waitForOAuth(conn, key)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            oauth = it.oauth.copy(
                                starting = false,
                                error = Texts.get(
                                    R.string.mcp_admin_oauth_error_start,
                                    adminFailureText(e),
                                ),
                            ),
                        )
                    }
                }
        }
    }

    /** 手动读一次授权状态。轮询结束后用它确认结果，不必重开授权页。 */
    fun refreshOAuth() {
        val current = _state.value
        val client = current.selected ?: return
        if (!McpEditDraft.isRemote(client.transport)) return
        val conn = connection() ?: return
        val key = client.key
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.mcpOAuthStatus(conn, key) } }
                .onSuccess { status -> applyOAuthStatus(key, status) }
                .onFailure { e ->
                    _state.update { state ->
                        if (state.selectedKey != key) return@update state
                        state.copy(
                            oauth = state.oauth.copy(
                                error = Texts.get(
                                    R.string.mcp_admin_oauth_error_status,
                                    adminFailureText(e),
                                ),
                            ),
                        )
                    }
                }
        }
    }

    /** 撤销授权并清掉服务端保存的令牌。撤销后工具列表会加载失败，属于预期现象。 */
    fun revokeOAuth() {
        val client = _state.value.selected ?: return
        val conn = connection() ?: return
        val key = client.key
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.mcpOAuthRevoke(conn, key) } }
                .onSuccess {
                    _state.update { state ->
                        if (state.selectedKey != key) return@update state
                        val cleared = McpOAuthStatusDto(authorized = false)
                        state.copy(
                            oauth = state.oauth.copy(
                                status = cleared,
                                polling = false,
                                authUrl = null,
                                error = null,
                            ),
                            clients = state.clients.map { client ->
                                if (client.key == key) client.copy(oauthStatus = cleared) else client
                            },
                            notice = Texts.get(R.string.mcp_admin_oauth_notice_revoked),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { state ->
                        if (state.selectedKey != key) return@update state
                        state.copy(
                            oauth = state.oauth.copy(
                                error = Texts.get(
                                    R.string.mcp_admin_oauth_error_revoke,
                                    adminFailureText(e),
                                ),
                            ),
                        )
                    }
                }
        }
    }

    /** 浏览器地址已经交给系统打开，清掉以免重组时重复打开。 */
    fun consumeOAuthUrl() {
        _state.update { it.copy(oauth = it.oauth.copy(authUrl = null)) }
    }

    /**
     * 轮询授权结果。
     *
     * 不判断 App 是否在前台：浏览器打开时页面并不卸载，回来时状态已经是最新的；
     * 用户长时间不回来则由次数上限兜底，不会留下常驻定时器。
     */
    private fun waitForOAuth(conn: Connection, key: String) {
        scope.launch {
            repeat(pollTimes) { attempt ->
                delay(pollIntervalMs)
                val status = runCatching {
                    withContext(Dispatchers.IO) { api.mcpOAuthStatus(conn, key) }
                }.getOrNull()
                if (status != null && applyOAuthStatus(key, status)) return@launch
                if (attempt == pollTimes - 1) {
                    _state.update { state ->
                        if (state.selectedKey != key) return@update state
                        state.copy(oauth = state.oauth.copy(polling = false))
                    }
                }
            }
        }
    }

    /** 把一次状态读入页内状态；返回是否已授权。客户端卡片上的 `oauth_status` 同步更新。 */
    private fun applyOAuthStatus(key: String, status: McpOAuthStatusDto): Boolean {
        _state.update { state ->
            if (state.selectedKey != key) return@update state
            state.copy(
                oauth = state.oauth.copy(
                    status = status,
                    polling = if (status.authorized) false else state.oauth.polling,
                    error = null,
                ),
                clients = state.clients.map { client ->
                    if (client.key == key) client.copy(oauthStatus = status) else client
                },
                notice = if (status.authorized) {
                    Texts.get(R.string.mcp_admin_oauth_notice_authorized)
                } else {
                    state.notice
                },
            )
        }
        return status.authorized
    }

    fun clear() {
        _state.value = McpUiState()
    }

    /**
     * 管理端动作失败的原因。
     *
     * 400 与 409 的 `detail` 由服务端给出，能贴就贴：新建时「键已存在」「名称不合法」
     * 这类原因只有原文说得清。401 与 404 的通用文案在管理页会误导（说的是会话与令牌），
     * 因此在这里换成「无权修改配置」与「客户端不存在」。
     */
    private fun adminFailureText(e: Throwable): String {
        val ex = e as? ApiException ?: return ErrorText.of(e)
        val raw = ex.detail.orEmpty().trim()
        val structured = runCatching { api.apiErrorDetailOf(ex)?.message.orEmpty() }.getOrDefault("")
        val detail = structured.ifBlank {
            if (raw.startsWith("{") || raw.startsWith("[")) "" else raw
        }
        return when (ex.code) {
            400 -> if (detail.isNotBlank()) {
                Texts.get(R.string.mcp_admin_error_bad_request, detail)
            } else {
                Texts.get(R.string.mcp_admin_error_bad_request_plain)
            }

            401 -> Texts.get(R.string.mcp_admin_error_unauthorized)
            404 -> Texts.get(R.string.mcp_admin_error_not_found)
            409 -> if (detail.isNotBlank()) {
                Texts.get(R.string.mcp_admin_error_conflict, detail)
            } else {
                ErrorText.of(e)
            }

            else -> ErrorText.of(e)
        }
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
            502 -> Texts.get(R.string.mcp_error_tools_unreachable)
            else -> ErrorText.of(e)
        }
    }
}