package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.ActiveModelDto
import io.qwenpaw.node.data.model.ModelsActiveDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.TestConnectionDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.chat.ModelChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 两个可切换的作用域。`effective` 只读，不设切换入口。 */
enum class ModelScope(val wire: String, val title: String) {
    GLOBAL("global", "全局默认"),
    AGENT("agent", "当前智能体默认"),
}

/**
 * 模型与提供商页的状态。
 *
 * `agentDefault` 与 `effective` 分开：前者是该智能体配置里**显式写下**的模型，
 * 没写过时服务端返回 `active_llm: null`；后者是实际生效的那个（会回落全局）。
 * 用 `effective` 冒充前者，界面会把「跟随全局」显示成一个已选中的模型。
 */
data class ModelsUiState(
    val providers: List<ProviderDto> = emptyList(),
    val choices: List<ModelChoice> = emptyList(),
    val globalDefault: ActiveModelDto? = null,
    val agentDefault: ActiveModelDto? = null,
    val effective: ActiveModelDto? = null,
    /** 正在测连通性的供应商 id。 */
    val testing: Set<String> = emptySet(),
    val testResults: Map<String, TestConnectionDto> = emptyMap(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    /** 某个作用域当前显示的名字。`null` 表示该作用域没有显式设置。 */
    fun labelOf(scope: ModelScope): String? = when (scope) {
        ModelScope.GLOBAL -> labelOf(globalDefault)
        ModelScope.AGENT -> labelOf(agentDefault)
    }

    private fun labelOf(slot: ActiveModelDto?): String? {
        val providerId = slot?.providerId ?: return null
        val model = slot.model ?: return null
        return choices.firstOrNull { it.providerId == providerId && it.modelId == model }?.modelName
            ?: model
    }
}

/**
 * 模型与提供商页的状态与动作。
 *
 * 只做列表、两个作用域的默认模型切换与供应商连通性测试。
 * 配置表单、模型增删、OAuth 流程都在 Web 控制台，手机上不做。
 */
class ModelsViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    // 名字避开 `scope`：`setDefault` 的入参也叫 scope（模型作用域），同名会遮蔽这个字段。
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    fun ensureLoaded() {
        if (_state.value.loaded || _state.value.loading) return
        refresh()
    }

    fun refresh() {
        val conn = connection() ?: run {
            _state.update { it.copy(loaded = true, loading = false, error = "尚未连接服务器") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        uiScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val providers = async { runCatching { api.providers(conn) } }
                    val global = async {
                        runCatching { api.activeModels(conn, ModelScope.GLOBAL.wire) }
                    }
                    val agent = async {
                        runCatching { api.activeModels(conn, ModelScope.AGENT.wire, conn.agentId) }
                    }
                    val effective = async {
                        runCatching { api.activeModels(conn, "effective") }
                    }
                    Fetched(
                        providers = providers.await(),
                        global = global.await(),
                        agent = agent.await(),
                        effective = effective.await(),
                    )
                }
            }
            _state.update { current ->
                val providers = fetched.providers.getOrNull() ?: current.providers
                current.copy(
                    providers = providers,
                    choices = modelChoicesOf(providers),
                    globalDefault = fetched.global.getOrNull()?.activeLlm,
                    agentDefault = fetched.agent.getOrNull()?.activeLlm,
                    effective = fetched.effective.getOrNull()?.activeLlm,
                    loading = false,
                    loaded = true,
                    // 默认模型与供应商列表任一取不到都算失败：两行与列表都在这页上。
                    error = (fetched.providers.exceptionOrNull()
                        ?: fetched.global.exceptionOrNull()
                        ?: fetched.agent.exceptionOrNull())?.let { ErrorText.of(it) },
                )
            }
        }
    }

    /**
     * 切换某个作用域的默认模型。
     *
     * **不做乐观更新**：服务端可能回 400（参数）或 404（供应商不存在），
     * 本地先改会让界面停留在一个并未生效的值上。成功后重拉两组默认模型——
     * agent 行会带动 `effective` 变化。
     */
    fun setDefault(scope: ModelScope, providerId: String, model: String) {
        val conn = connection() ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null, notice = null) }
        uiScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    api.setActiveModel(
                        conn = conn,
                        providerId = providerId,
                        model = model,
                        scope = scope.wire,
                        agentId = conn.agentId,
                    )
                }
            }
            val failure = saved.exceptionOrNull()
            if (failure != null) {
                _state.update { it.copy(saving = false, error = ErrorText.of(failure)) }
                return@launch
            }
            // 成功后重拉两组默认模型：写入 agent 行会带动 effective 变化。
            val fresh = withContext(Dispatchers.IO) {
                coroutineScope {
                    val global = async { runCatching { api.activeModels(conn, ModelScope.GLOBAL.wire) } }
                    val agent = async {
                        runCatching { api.activeModels(conn, ModelScope.AGENT.wire, conn.agentId) }
                    }
                    val effective = async { runCatching { api.activeModels(conn, "effective") } }
                    Triple(global.await(), agent.await(), effective.await())
                }
            }
            _state.update { current ->
                current.copy(
                    saving = false,
                    globalDefault = fresh.first.getOrNull()?.activeLlm ?: current.globalDefault,
                    agentDefault = fresh.second.getOrNull()?.activeLlm ?: current.agentDefault,
                    effective = fresh.third.getOrNull()?.activeLlm ?: current.effective,
                    notice = "${scope.title}已切换",
                )
            }
        }
    }

    /** 测一个供应商的连通性。失败不是 HTTP 错误，看返回里的 `success`。 */
    fun test(providerId: String) {
        val conn = connection() ?: return
        if (providerId in _state.value.testing) return
        _state.update { it.copy(testing = it.testing + providerId) }
        uiScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.testProvider(conn, providerId) }
            }
            _state.update { current ->
                current.copy(
                    testing = current.testing - providerId,
                    testResults = result.getOrNull()
                        ?.let { current.testResults + (providerId to it) }
                        ?: current.testResults,
                    error = result.exceptionOrNull()?.let { ErrorText.of(it) } ?: current.error,
                )
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clear() {
        _state.value = ModelsUiState()
    }

    private data class Fetched(
        val providers: Result<List<ProviderDto>>,
        val global: Result<ModelsActiveDto>,
        val agent: Result<ModelsActiveDto>,
        val effective: Result<ModelsActiveDto>,
    )
}
