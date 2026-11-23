package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.AcpNodeRuntimeDto
import io.qwenpaw.node.data.model.HarnessProviderDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** ACP 单张表单。保留原始对象，保存时只替换七个字段。 */
data class AcpAgentForm(
    val name: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
    val enabled: Boolean = false,
    val command: String = "",
    val argsText: String = "",
    val env: List<Pair<String, String>> = emptyList(),
    val trusted: Boolean = true,
    val toolParseMode: String = "call_title",
    val bufferMb: String = "50",
)

data class AcpUiState(
    val nodeRuntime: AcpNodeRuntimeDto? = null,
    val nodePath: String = "",
    val agents: List<AcpAgentForm> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/** 登录返回的展示字段。`url` 非空时界面提供「打开浏览器」。 */
data class HarnessLoginUi(
    val providerId: String,
    val title: String,
    val fields: List<Pair<String, String>> = emptyList(),
    val url: String? = null,
)

data class HarnessDetailUi(
    val title: String,
    val lines: List<String>,
)

data class HarnessUiState(
    val providers: List<HarnessProviderDto> = emptyList(),
    /** 插件目录里存在、但服务端尚未注册的后端。 */
    val installable: List<io.qwenpaw.node.data.model.PluginCatalogEntryDto> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val login: HarnessLoginUi? = null,
    val detail: HarnessDetailUi? = null,
    /** 正在等待登录完成的 provider；非空时界面显示「等待授权」。 */
    val waitingLogin: String? = null,
)

/** 智能体附加设置：系统提示文件、MD 语言、编码模式与顾问模式。 */
data class AgentExtrasUiState(
    val promptFiles: List<String> = emptyList(),
    val availableFiles: List<String> = emptyList(),
    val language: String = "",
    val codingEnabled: Boolean = false,
    val advisor: JsonObject? = null,
    val loops: kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(emptyList()),
    val customLoops: kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(emptyList()),
    /** 自定义循环的 JSON 编辑草稿；null 表示编辑器关闭。 */
    val customLoopDraft: String? = null,
    val customLoopEditingId: String? = null,
    val customLoopSaving: Boolean = false,
    val customLoopError: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * 运行时：ACP 表单与 Harness 登录。
 *
 * 两个页签共享一个连接与生命周期，因此放在一个对象里；ACP 的每个 agent 与
 * Harness 的每个 provider 都独立保存，互不影响。
 */
class RuntimeConfigViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _acp = MutableStateFlow(AcpUiState())
    val acp: StateFlow<AcpUiState> = _acp.asStateFlow()

    private val _harness = MutableStateFlow(HarnessUiState())
    val harness: StateFlow<HarnessUiState> = _harness.asStateFlow()

    private val _agentExtras = MutableStateFlow(AgentExtrasUiState())
    val agentExtras: StateFlow<AgentExtrasUiState> = _agentExtras.asStateFlow()

    private var loginPoll: Job? = null

    // ---------------- ACP ----------------

    fun loadAcp(force: Boolean = false) {
        if (!force && (_acp.value.loaded || _acp.value.loading)) return
        val conn = connection() ?: return
        _acp.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val config = api.acpConfig(conn)
                    val runtime = runCatching { api.acpNodeRuntime(conn) }.getOrNull()
                    config to runtime
                }
            }.onSuccess { (config, runtime) ->
                val agents = (config["agents"] as? JsonObject)
                    ?.map { (name, element) -> element.toAcpForm(name) }
                    .orEmpty()
                _acp.value = AcpUiState(
                    nodeRuntime = runtime,
                    nodePath = runtime?.nodePath
                        ?: (config["node_path"]?.jsonPrimitive?.contentOrNull ?: ""),
                    agents = agents,
                    loaded = true,
                )
            }.onFailure { e ->
                _acp.update {
                    it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                }
            }
        }
    }

    fun saveNodePath(path: String) {
        val conn = connection() ?: return
        _acp.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.saveAcpNodeRuntime(conn, path.trim()) }
            }.onSuccess { runtime ->
                _acp.update {
                    it.copy(
                        nodeRuntime = runtime,
                        nodePath = runtime.nodePath,
                        saving = false,
                        notice = Texts.get(R.string.runtime_notice_node_path_saved),
                    )
                }
            }.onFailure { e ->
                _acp.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun saveAcpAgent(form: AcpAgentForm) {
        val conn = connection() ?: return
        _acp.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val body = form.raw.applyAcpForm(form)
                    api.saveAcpAgentConfig(conn, form.name, body)
                }
            }.onSuccess { saved ->
                val updated = saved.toAcpForm(form.name)
                _acp.update { state ->
                    state.copy(
                        agents = state.agents.map { if (it.name == form.name) updated else it },
                        saving = false,
                        notice = Texts.get(R.string.runtime_notice_agent_saved, form.name),
                    )
                }
            }.onFailure { e ->
                _acp.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun dismissAcpNotice() {
        _acp.update { it.copy(notice = null, error = null) }
    }

    // ---------------- 智能体附加设置 ----------------

    fun loadAgentExtras(force: Boolean = false) {
        if (!force && (_agentExtras.value.loaded || _agentExtras.value.loading)) return
        val conn = connection() ?: return
        _agentExtras.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val prompts = api.systemPromptFiles(conn)
                    val files = runCatching { api.workspaceWorkingFiles(conn) }
                        .getOrDefault(emptyList())
                    val language = api.workspaceLanguage(conn)
                    val coding = api.codingMode(conn)
                    val advisor = runCatching { api.advisorMode(conn) }.getOrNull()
                    val loops = runCatching { api.loops(conn) }
                        .getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
                    val customLoops = runCatching { api.customLoops(conn) }
                        .getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
                    AgentExtrasSnapshot(
                        promptFiles = prompts,
                        availableFiles = files.map { it.filename }.filter { it.isNotBlank() },
                        language = language,
                        codingEnabled = coding.enabled,
                        advisor = advisor,
                        loops = loops,
                        customLoops = customLoops,
                    )
                }
            }.onSuccess { snap ->
                _agentExtras.value = AgentExtrasUiState(
                    promptFiles = snap.promptFiles,
                    availableFiles = snap.availableFiles,
                    language = snap.language,
                    codingEnabled = snap.codingEnabled,
                    advisor = snap.advisor,
                    loops = snap.loops,
                    customLoops = snap.customLoops,
                    loaded = true,
                )
            }.onFailure { e ->
                _agentExtras.update {
                    it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                }
            }
        }
    }

    /** 勾选/取消一个系统提示文件；整份列表回写。 */
    fun setPromptFile(file: String, enabled: Boolean) {
        val conn = connection() ?: return
        val current = _agentExtras.value
        val next = if (enabled) {
            (current.promptFiles + file).distinct()
        } else {
            current.promptFiles - file
        }
        _agentExtras.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setSystemPromptFiles(conn, next) }
            }.onSuccess { saved ->
                _agentExtras.update { it.copy(promptFiles = saved, saving = false) }
            }.onFailure { e ->
                _agentExtras.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun setAgentLanguage(language: String) {
        val conn = connection() ?: return
        val trimmed = language.trim()
        if (trimmed.isEmpty()) return
        _agentExtras.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setWorkspaceLanguage(conn, trimmed) }
            }.onSuccess { saved ->
                _agentExtras.update {
                    it.copy(
                        language = saved,
                        saving = false,
                        notice = Texts.get(R.string.runtime_notice_language_saved),
                    )
                }
            }.onFailure { e ->
                _agentExtras.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun setCodingEnabled(enabled: Boolean) {
        val conn = connection() ?: return
        _agentExtras.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setCodingMode(conn, enabled) }
            }.onSuccess { saved ->
                _agentExtras.update { it.copy(codingEnabled = saved.enabled, saving = false) }
            }.onFailure { e ->
                _agentExtras.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun setAdvisorEnabled(enabled: Boolean) {
        val conn = connection() ?: return
        _agentExtras.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.updateAdvisorMode(conn, buildJsonObject { put("enabled", enabled) })
                }
            }.onSuccess { saved ->
                _agentExtras.update { it.copy(advisor = saved, saving = false) }
            }.onFailure { e ->
                _agentExtras.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    /** 顾问模式的单项开关：plan_enabled / followup_enabled / on_demand_enabled。 */
    fun setAdvisorFlag(field: String, enabled: Boolean) {
        val conn = connection() ?: return
        _agentExtras.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.updateAdvisorMode(conn, buildJsonObject { put(field, enabled) })
                }
            }.onSuccess { saved ->
                _agentExtras.update { it.copy(advisor = saved, saving = false) }
            }.onFailure { e ->
                _agentExtras.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    /** 顾问模式的数值项：最大咨询次数与参考最近消息数。 */
    fun setAdvisorNumbers(maxConsults: Int?, recentMessages: Int?) {
        val conn = connection() ?: return
        val body = buildJsonObject {
            maxConsults?.let { put("max_consults", it) }
            recentMessages?.let { put("recent_messages", it) }
        }
        if (body.isEmpty()) return
        _agentExtras.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.updateAdvisorMode(conn, body) }
            }.onSuccess { saved ->
                _agentExtras.update { it.copy(advisor = saved, saving = false) }
            }.onFailure { e ->
                _agentExtras.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun dismissAgentExtrasNotice() {
        _agentExtras.update { it.copy(notice = null, error = null) }
    }

    // ---------------- 自定义循环 ----------------

    private val prettyJson = kotlinx.serialization.json.Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun openCreateCustomLoop() {
        val template = buildJsonObject {
            put("id", "")
            put("name", "")
            put("description", "")
            put("slash_command", "")
            put("enabled", false)
            putJsonArray("gates") { }
        }
        _agentExtras.update {
            it.copy(
                customLoopDraft = prettyJson.encodeToString(
                    JsonObject.serializer(),
                    template,
                ),
                customLoopEditingId = null,
                customLoopError = null,
            )
        }
    }

    fun openEditCustomLoop(id: String) {
        val target = _agentExtras.value.customLoops.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id
        }?.jsonObject ?: return
        _agentExtras.update {
            it.copy(
                customLoopDraft = prettyJson.encodeToString(JsonObject.serializer(), target),
                customLoopEditingId = id,
                customLoopError = null,
            )
        }
    }

    fun setCustomLoopDraft(value: String) {
        _agentExtras.update { it.copy(customLoopDraft = value) }
    }

    fun closeCustomLoopEditor() {
        _agentExtras.update {
            it.copy(customLoopDraft = null, customLoopEditingId = null, customLoopError = null)
        }
    }

    /** 保存自定义循环；服务端校验失败时保留草稿并在编辑器内显示原文。 */
    fun saveCustomLoop() {
        val conn = connection() ?: return
        val current = _agentExtras.value
        val draft = current.customLoopDraft ?: return
        val body = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(draft).jsonObject
        }.getOrNull()
        if (body == null) {
            _agentExtras.update { it.copy(customLoopError = Texts.get(R.string.runtime_loop_json_invalid)) }
            return
        }
        _agentExtras.update { it.copy(customLoopSaving = true, customLoopError = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val editingId = current.customLoopEditingId
                    if (editingId == null) {
                        api.createCustomLoop(conn, body)
                    } else {
                        api.updateCustomLoop(conn, editingId, body)
                    }
                }
            }
            result.onSuccess {
                _agentExtras.update {
                    it.copy(
                        customLoopSaving = false,
                        customLoopDraft = null,
                        customLoopEditingId = null,
                        notice = Texts.get(R.string.runtime_loop_saved),
                    )
                }
                loadAgentExtras(force = true)
            }.onFailure { e ->
                _agentExtras.update {
                    it.copy(
                        customLoopSaving = false,
                        customLoopError = ErrorText.of(e),
                    )
                }
            }
        }
    }

    fun deleteCustomLoop(id: String) {
        val conn = connection() ?: return
        _agentExtras.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.deleteCustomLoop(conn, id) }
            }.onSuccess {
                _agentExtras.update {
                    it.copy(saving = false, notice = Texts.get(R.string.runtime_loop_deleted))
                }
                loadAgentExtras(force = true)
            }.onFailure { e ->
                _agentExtras.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun duplicateCustomLoop(id: String) {
        val conn = connection() ?: return
        _agentExtras.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.duplicateCustomLoop(conn, id) }
            }.onSuccess {
                _agentExtras.update {
                    it.copy(saving = false, notice = Texts.get(R.string.runtime_loop_duplicated))
                }
                loadAgentExtras(force = true)
            }.onFailure { e ->
                _agentExtras.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    // ---------------- Harness ----------------

    fun loadHarness(force: Boolean = false) {
        if (!force && (_harness.value.loaded || _harness.value.loading)) return
        val conn = connection() ?: return
        _harness.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val providers = api.harnesses(conn)
                    val catalog = runCatching { api.pluginCatalog(conn) }
                        .getOrNull()?.plugins.orEmpty()
                    providers to harnessInstallable(providers, catalog)
                }
            }.onSuccess { (providers, installable) ->
                _harness.update {
                    it.copy(
                        providers = providers,
                        installable = installable,
                        loading = false,
                        loaded = true,
                    )
                }
            }.onFailure { e ->
                _harness.update {
                    it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                }
            }
        }
    }

    fun probe(providerId: String) {
        val conn = connection() ?: return
        _harness.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.harnessStatus(conn, providerId) }
            }.onSuccess { provider ->
                _harness.update { state ->
                    state.copy(
                        providers = state.providers.map {
                            if (it.id == provider.id) provider else it
                        },
                        saving = false,
                        notice = Texts.get(
                            R.string.runtime_notice_provider_refreshed,
                            provider.name.ifBlank { provider.id },
                        ),
                    )
                }
            }.onFailure { e ->
                _harness.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun login(providerId: String) {
        val conn = connection() ?: return
        _harness.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.harnessLogin(conn, providerId) }
            }.onSuccess { result ->
                _harness.update {
                    it.copy(
                        saving = false,
                        login = result.toLoginUi(providerId),
                        waitingLogin = providerId,
                    )
                }
                startLoginPolling(providerId)
            }.onFailure { e ->
                _harness.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    /** 登录发起后每 5 秒重拉一次，最多 2 分钟；完成后停止。 */
    private fun startLoginPolling(providerId: String) {
        val conn = connection() ?: return
        loginPoll?.cancel()
        loginPoll = scope.launch {
            repeat(24) {
                delay(5_000)
                val list = runCatching {
                    withContext(Dispatchers.IO) { api.harnesses(conn) }
                }.getOrNull() ?: return@repeat
                _harness.update { it.copy(providers = list) }
                val provider = list.firstOrNull { it.id == providerId }
                if (provider?.authenticated == true) {
                    _harness.update {
                        it.copy(
                            waitingLogin = null,
                            login = null,
                            notice = Texts.get(
                                R.string.runtime_notice_logged_in,
                                provider.name.ifBlank { provider.id },
                            ),
                        )
                    }
                    return@launch
                }
            }
            _harness.update { it.copy(waitingLogin = null) }
        }
    }

    fun logout(providerId: String) {
        val conn = connection() ?: return
        _harness.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.harnessLogout(conn, providerId) } }
                .onSuccess {
                    _harness.update {
                        it.copy(
                            saving = false,
                            notice = Texts.get(R.string.runtime_notice_logged_out),
                        )
                    }
                    loadHarness(force = true)
                }
                .onFailure { e ->
                    _harness.update { it.copy(saving = false, error = ErrorText.of(e)) }
                }
        }
    }

    fun openDetail(providerId: String, kind: String) {
        val conn = connection() ?: return
        val provider = _harness.value.providers.firstOrNull { it.id == providerId }
        val displayName = provider?.name?.ifBlank { providerId } ?: providerId
        val kindLabel = when (kind) {
            "models" -> Texts.get(R.string.runtime_tab_models)
            "mcp" -> "MCP"
            else -> Texts.get(R.string.runtime_tab_skills)
        }
        val title = Texts.get(R.string.runtime_pair, displayName, kindLabel)
        _harness.update { it.copy(saving = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (kind) {
                        "models" -> api.harnessModels(conn, providerId)
                        "mcp" -> api.harnessMcp(conn, providerId)
                        else -> api.harnessSkills(conn, providerId)
                    }
                }
            }.onSuccess { obj ->
                _harness.update {
                    it.copy(
                        saving = false,
                        detail = HarnessDetailUi(title, harnessLines(kind, obj)),
                    )
                }
            }.onFailure { e ->
                _harness.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun dismissLogin() {
        loginPoll?.cancel()
        _harness.update { it.copy(login = null, waitingLogin = null) }
    }

    fun dismissDetail() {
        _harness.update { it.copy(detail = null) }
    }

    fun dismissHarnessNotice() {
        _harness.update { it.copy(notice = null, error = null) }
    }

    fun clear() {
        loginPoll?.cancel()
        _acp.value = AcpUiState()
        _harness.value = HarnessUiState()
    }
}

private fun JsonElement.toAcpForm(name: String): AcpAgentForm {
    val obj = asObj()
    val envObj = obj["env"] as? JsonObject
    return AcpAgentForm(
        name = name,
        raw = obj,
        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
        command = obj["command"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        argsText = (obj["args"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.joinToString("\n")
            .orEmpty(),
        env = envObj?.map { (k, v) -> k to (v.jsonPrimitive.contentOrNull.orEmpty()) }.orEmpty(),
        trusted = obj["trusted"]?.jsonPrimitive?.booleanOrNull ?: true,
        toolParseMode = obj["tool_parse_mode"]?.jsonPrimitive?.contentOrNull ?: "call_title",
        bufferMb = ((obj["stdio_buffer_limit_bytes"]?.jsonPrimitive?.intOrNull ?: (50 * 1024 * 1024)) /
            (1024 * 1024)).toString(),
    )
}

private fun JsonObject.applyAcpForm(form: AcpAgentForm): JsonObject {
    val args = form.argsText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    val env = JsonObject(form.env.filter { it.first.isNotBlank() }.associate { it.first to JsonPrimitive(it.second) })
    val buffer = (form.bufferMb.trim().toLongOrNull() ?: 50L) * 1024 * 1024
    val out = toMutableMap()
    out["enabled"] = JsonPrimitive(form.enabled)
    out["command"] = JsonPrimitive(form.command)
    out["args"] = kotlinx.serialization.json.JsonArray(args.map { JsonPrimitive(it) })
    out["env"] = env
    out["trusted"] = JsonPrimitive(form.trusted)
    out["tool_parse_mode"] = JsonPrimitive(form.toolParseMode)
    out["stdio_buffer_limit_bytes"] = JsonPrimitive(buffer)
    return JsonObject(out)
}

private fun JsonElement.asObj(): JsonObject = runCatching { jsonObject }.getOrDefault(JsonObject(emptyMap()))

/** 把各 provider 的能力清单压成可读行；拿不到结构时保留原文。 */
internal fun harnessLines(kind: String, obj: JsonObject): List<String> {
    val message = obj["message"]?.jsonPrimitive?.contentOrNull
    if (!message.isNullOrBlank()) return listOf(message)
    val key = when (kind) {
        "models" -> "models"
        "mcp" -> "servers"
        else -> "skills"
    }
    val array = obj[key] as? kotlinx.serialization.json.JsonArray
        ?: return listOf(Texts.get(R.string.runtime_detail_empty))
    if (array.isEmpty()) return listOf(Texts.get(R.string.runtime_detail_list_empty))
    return array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val label = item["name"]?.jsonPrimitive?.contentOrNull
            ?: item["id"]?.jsonPrimitive?.contentOrNull
            ?: item["title"]?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        val detail = item["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (detail.isBlank()) label else Texts.get(R.string.runtime_pair, label, detail)
    }
}

/** 登录返回的展示信息。字段名不统一，按已知键取值并保留其余键。 */
internal fun JsonObject.toLoginUi(providerId: String): HarnessLoginUi {
    val fields = mutableListOf<Pair<String, String>>()
    var url: String? = null
    for ((key, value) in this) {
        val text = value.jsonPrimitiveOrNull()?.contentOrNull ?: continue
        if (text.isBlank()) continue
        fields += key to text
        if (text.startsWith("http://") || text.startsWith("https://")) url = text
    }
    val title = when (providerId) {
        "codex" -> Texts.get(R.string.runtime_login_title_codex)
        "qoder" -> Texts.get(R.string.runtime_login_title_qoder)
        else -> Texts.get(R.string.runtime_login_title_generic, providerId)
    }
    return HarnessLoginUi(providerId, title, fields, url)
}

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? =
    runCatching { jsonPrimitive }.getOrNull()

private data class AgentExtrasSnapshot(
    val promptFiles: List<String>,
    val availableFiles: List<String>,
    val language: String,
    val codingEnabled: Boolean,
    val advisor: JsonObject?,
    val loops: kotlinx.serialization.json.JsonArray,
    val customLoops: kotlinx.serialization.json.JsonArray,
)
