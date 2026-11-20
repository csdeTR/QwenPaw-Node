package io.qwenpaw.node.ui.plugins

import io.qwenpaw.node.R
import io.qwenpaw.node.data.remote.ApiException
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
import kotlinx.serialization.json.JsonObject

/** 依赖升级页状态。 */
data class DependencyUpgradeUiState(
    val operationId: String = "",
    val operation: DependencyUpgradeState? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val loadError: String? = null,
    /** 应用或取消进行中。 */
    val acting: Boolean = false,
    /** 应用/取消失败的服务端原话。 */
    val actionError: String? = null,
    /** 404：当前服务端不支持该操作。 */
    val unsupported: Boolean = false,
)

/**
 * 依赖升级：读取、轮询、应用与取消。
 *
 * 操作 id 只能从 409 的 `operation` 字段拿到，没有列表端点，因此本类没有「空列表」状态：
 * id 为空时页面直接说明没有可显示的操作，不渲染假入口。
 *
 * `waiting_restart` 之后不再自动轮询——状态要等服务端重启才会变，
 * 继续每秒请求既没有结果，也会让人误以为还在进行。
 */
class DependencyUpgradeViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(DependencyUpgradeUiState())
    val state: StateFlow<DependencyUpgradeUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** 导航入口。换操作时重置状态并停掉上一个操作的轮询。 */
    fun open(operationId: String) {
        if (_state.value.operationId == operationId && (_state.value.loaded || _state.value.loading)) return
        pollJob?.cancel()
        _state.value = DependencyUpgradeUiState(operationId = operationId, loading = true)
        refresh()
    }

    fun ensureStarted() {
        val current = _state.value
        if (current.operationId.isBlank() || current.loaded || current.loading) return
        refresh()
    }

    fun refresh() {
        val operationId = _state.value.operationId
        if (operationId.isBlank()) return
        val conn = connection() ?: run {
            _state.update {
                it.copy(
                    loading = false,
                    loaded = true,
                    loadError = Texts.get(R.string.plugin_admin_error_not_connected),
                )
            }
            return
        }
        _state.update { it.copy(loading = true, loadError = null) }
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { api.dependencyUpgrade(conn, operationId) }
            }
            outcome.onSuccess { raw ->
                val parsed = dependencyStateOf(operationId, raw)
                _state.update {
                    it.copy(
                        operation = parsed,
                        loading = false,
                        loaded = true,
                        loadError = null,
                    )
                }
                schedulePolling(conn, parsed)
            }
            outcome.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        // 404 单独标出来：旧版本没有这个端点，不能显示成「升级失败」。
                        unsupported = error is ApiException && error.code == 404,
                        loadError = if (error is ApiException && error.code == 404) {
                            null
                        } else {
                            Texts.get(
                                R.string.plugin_admin_dep_error_load_failed,
                                ErrorText.of(error),
                            )
                        },
                    )
                }
            }
        }
    }

    /** 应用。仅在 `needs_confirmation` 阶段显示，服务端拒绝时原样展示它的说法。 */
    fun apply() {
        runControl { conn, operationId -> api.applyDependencyUpgrade(conn, operationId) }
    }

    /** 取消。服务端不允许时回 409，同样原样展示。 */
    fun cancel() {
        runControl { conn, operationId -> api.cancelDependencyUpgrade(conn, operationId) }
    }

    private fun runControl(call: (Connection, String) -> JsonObject) {
        if (_state.value.acting) return
        val operationId = _state.value.operationId
        if (operationId.isBlank()) return
        val conn = connection() ?: return
        _state.update { it.copy(acting = true, actionError = null, unsupported = false) }
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { call(conn, operationId) }
            }
            _state.update { it.copy(acting = false) }
            outcome.onSuccess { raw ->
                if (raw.isEmpty()) {
                    refresh()
                } else {
                    val parsed = dependencyStateOf(operationId, raw)
                    _state.update {
                        it.copy(operation = parsed, loaded = true, unsupported = false)
                    }
                    schedulePolling(conn, parsed)
                }
            }
            outcome.onFailure { error ->
                val detail = (error as? ApiException)?.let { api.apiErrorDetailOf(it) }
                _state.update {
                    it.copy(
                        unsupported = (error as? ApiException)?.code == 404,
                        actionError = if ((error as? ApiException)?.code == 404) {
                            null
                        } else {
                            Texts.get(
                                R.string.plugin_admin_dep_error_action_failed,
                                detail?.message?.takeIf { message -> message.isNotBlank() }
                                    ?: ErrorText.of(error),
                            )
                        },
                    )
                }
            }
        }
    }

    private fun schedulePolling(conn: Connection, operation: DependencyUpgradeState) {
        pollJob?.cancel()
        if (!operation.shouldPoll) return
        pollJob = scope.launch {
            repeat(POLL_LIMIT) {
                delay(POLL_INTERVAL_MS)
                val raw = runCatching {
                    withContext(Dispatchers.IO) { api.dependencyUpgrade(conn, operation.operationId) }
                }.getOrNull() ?: return@repeat
                val parsed = dependencyStateOf(operation.operationId, raw)
                _state.update {
                    it.copy(operation = parsed, loadError = null)
                }
                if (!parsed.shouldPoll) return@launch
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    fun dismissNotice() {
        _state.update { it.copy(actionError = null, unsupported = false) }
    }

    fun clear() {
        pollJob?.cancel()
        _state.value = DependencyUpgradeUiState()
    }

    companion object {
        /** 轮询间隔。升级本身以分钟计，1 秒一次只是白烧请求。 */
        const val POLL_INTERVAL_MS = 2_000L

        /** 最多轮询 10 分钟；之后交给用户点「重新检查」。 */
        const val POLL_LIMIT = 300
    }
}
