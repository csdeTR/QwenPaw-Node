package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.EnvCatalogDto
import io.qwenpaw.node.data.model.EnvVarDto
import io.qwenpaw.node.data.model.OffloadPolicyDto
import io.qwenpaw.node.data.model.ToolDto
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
import kotlinx.serialization.json.JsonPrimitive

/** 工具页状态。 */
data class ToolsUiState(
    val tools: List<ToolDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** 环境变量页状态。`catalog` 用来区分内置变量（只能重置）与只读项。 */
data class EnvsUiState(
    val vars: List<EnvVarDto> = emptyList(),
    val catalog: List<EnvCatalogDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** 后台策略页状态。 */
data class OffloadUiState(
    val action: String = KEEP_FOREGROUND,
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * 设置页下三张配置表单的状态与动作。
 *
 * 三项都是「小表单」，放在一个对象里而不是三个 ViewModel：它们共用同一个连接，
 * 生命周期也一致（进入设置页时按需拉取）。页面只读状态、调动作，不自己发请求。
 */
class SettingsConfigViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tools = MutableStateFlow(ToolsUiState())
    val tools: StateFlow<ToolsUiState> = _tools.asStateFlow()

    private val _envs = MutableStateFlow(EnvsUiState())
    val envs: StateFlow<EnvsUiState> = _envs.asStateFlow()

    private val _offload = MutableStateFlow(OffloadUiState())
    val offload: StateFlow<OffloadUiState> = _offload.asStateFlow()

    // ---------------- 工具 ----------------

    fun loadTools(force: Boolean = false) {
        if (!force && (_tools.value.loaded || _tools.value.loading)) return
        val conn = connection() ?: return
        _tools.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.tools(conn) } }
                .onSuccess { list ->
                    _tools.value = ToolsUiState(tools = list, loading = false, loaded = true)
                }
                .onFailure { e ->
                    _tools.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
                }
        }
    }

    /**
     * 切换启用状态。
     *
     * 端点是「取反」语义且没有请求体，因此结果以服务端返回的对象为准，
     * 不在本地先行翻转——本地点两下会和真实状态错位。
     */
    fun toggleTool(name: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.toggleTool(conn, name) } }
                .onSuccess { updated -> replaceTool(updated) }
                .onFailure { e -> _tools.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    fun setToolAsync(name: String, enabled: Boolean) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setToolAsync(conn, name, enabled) } }
                .onSuccess { updated -> replaceTool(updated) }
                .onFailure { e -> _tools.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    /**
     * 保存工具配置。
     *
     * 表单给的是字符串，但服务端的配置值有数字与布尔类型，因此按字段声明的
     * `type` 转回对应 JSON 类型；无法转换时退回字符串，不静默丢值。
     */
    fun saveToolConfig(name: String, values: Map<String, String>) {
        val conn = connection() ?: return
        val fields = _tools.value.tools.firstOrNull { it.name == name }?.configFields.orEmpty()
            .associateBy { it.name }
        val payload = values.mapValues { (key, text) ->
            when (fields[key]?.type) {
                "number" -> text.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text)
                "boolean" -> JsonPrimitive(text.equals("true", ignoreCase = true))
                else -> JsonPrimitive(text)
            }
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.saveToolConfig(conn, name, payload) } }
                .onSuccess {
                    _tools.update { it.copy(error = null) }
                    // 保存后重拉一次：服务端会把密码字段重新打成 `***`，本地缓存的旧值要跟上。
                    loadTools(force = true)
                }
                .onFailure { e -> _tools.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    private fun replaceTool(updated: ToolDto) {
        _tools.update { s ->
            s.copy(tools = s.tools.map { if (it.name == updated.name) updated else it })
        }
    }

    // ---------------- 环境变量 ----------------

    fun loadEnvs(force: Boolean = false) {
        if (!force && (_envs.value.loaded || _envs.value.loading)) return
        val conn = connection() ?: return
        _envs.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val vars = api.envVars(conn)
                    // 规格表拿不到不影响读写，只影响「内置变量只能重置」的判定，
                    // 因此单独兜底，不让它把整页拖成错误态。
                    val catalog = runCatching { api.envCatalog(conn) }.getOrDefault(emptyList())
                    vars to catalog
                }
            }.onSuccess { (vars, catalog) ->
                _envs.value = EnvsUiState(vars = vars, catalog = catalog, loaded = true)
            }.onFailure { e ->
                _envs.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
            }
        }
    }

    /** 合并保存。PATCH 不删除未提交的项，因此只传改动的那几条。 */
    fun saveEnvs(values: Map<String, String>) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.patchEnvVars(conn, values) } }
                .onSuccess { vars -> _envs.update { it.copy(vars = vars, error = null) } }
                .onFailure { e -> _envs.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    fun deleteEnv(key: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteEnvVar(conn, key) } }
                .onSuccess { vars -> _envs.update { it.copy(vars = vars, error = null) } }
                .onFailure { e -> _envs.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    fun resetEnv(key: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.resetEnvVar(conn, key) } }
                .onSuccess { vars -> _envs.update { it.copy(vars = vars, error = null) } }
                .onFailure { e -> _envs.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    // ---------------- 后台策略 ----------------

    fun loadOffload(force: Boolean = false) {
        if (!force && (_offload.value.loaded || _offload.value.loading)) return
        val conn = connection() ?: return
        _offload.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.offloadPolicy(conn) } }
                .onSuccess { policy: OffloadPolicyDto ->
                    _offload.value = OffloadUiState(action = policy.defaultAction, loaded = true)
                }
                .onFailure { e ->
                    _offload.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
                }
        }
    }

    fun setOffload(action: String) {
        val conn = connection() ?: return
        _offload.update { it.copy(action = action, loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setOffloadPolicy(conn, action) } }
                .onSuccess { policy ->
                    _offload.value = OffloadUiState(action = policy.defaultAction, loaded = true)
                }
                .onFailure { e ->
                    // 写失败要把开关拨回去，否则界面显示的是未生效的值。
                    _offload.update { it.copy(loading = false, error = ErrorText.of(e)) }
                    loadOffload(force = true)
                }
        }
    }

    /** 换实例或换智能体后清空，下次进入重新拉取。 */
    fun clear() {
        _tools.value = ToolsUiState()
        _envs.value = EnvsUiState()
        _offload.value = OffloadUiState()
    }
}