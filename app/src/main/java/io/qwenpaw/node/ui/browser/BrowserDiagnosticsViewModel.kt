package io.qwenpaw.node.ui.browser

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.BrowserChromeStatusDto
import io.qwenpaw.node.data.model.BrowserSelfTestDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览器控制诊断页的状态。
 *
 * [unsupported] 与 [error] 互斥：前者是服务端根本没有这个端点（404），
 * 属于版本差异而不是故障，不能按网络错误展示；后者只放真正的读取失败。
 *
 * [notice] 是一次性说明（自检请求失败、修复结果），界面关闭后调用
 * [BrowserDiagnosticsViewModel.consumeMessage] 清空。逐项检查未通过不写进这里：
 * 那是自检结论的一部分，随 [selfTest] 一起展示。
 */
data class BrowserDiagnosticsUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val unsupported: Boolean = false,
    val status: BrowserChromeStatusDto? = null,
    val selfTest: BrowserSelfTestDto? = null,
    val selfTestRunning: Boolean = false,
    /** 日志行；null 表示本页还没读过。 */
    val logs: List<String>? = null,
    val logsLoading: Boolean = false,
    val logsError: String? = null,
    val repairing: Boolean = false,
    val notice: String? = null,
)

/**
 * 浏览器控制链路诊断的数据层。
 *
 * 只有状态查询会在进入页面时自动执行；自检、读日志与修复都由用户触发，
 * 因为它们都会在服务端产生副作用（自检写 last_self_test，修复会改动插件配置）。
 * 四个请求都是阻塞调用，统一切到 IO 线程。
 */
class BrowserDiagnosticsViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BrowserDiagnosticsUiState())
    val state: StateFlow<BrowserDiagnosticsUiState> = _state.asStateFlow()

    /**
     * 进入页面时调用；失败后界面上的「重试」也走这里。
     *
     * 已经成功读到状态时不重复请求，避免每次重组都打一次服务端；
     * 上次失败或服务端不支持（[BrowserDiagnosticsUiState.unsupported]）时重新请求，
     * 这样升级服务端后重新进入本页就能拿到新结论。
     */
    fun ensureLoaded() {
        val current = _state.value
        if (current.loading || current.selfTestRunning) return
        if (current.loaded && current.status != null) return
        loadStatus()
    }

    private fun loadStatus() {
        val conn = connection() ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.browserChromeStatus(conn) } }
                .onSuccess { status ->
                    _state.update {
                        it.copy(
                            loading = false,
                            loaded = true,
                            unsupported = false,
                            status = status,
                            error = null,
                        )
                    }
                }
                .onFailure { e ->
                    if (e.isMissingEndpoint()) {
                        _state.update {
                            it.copy(
                                loading = false,
                                loaded = true,
                                unsupported = true,
                                status = null,
                                error = null,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                loading = false,
                                loaded = true,
                                unsupported = false,
                                error = Texts.get(
                                    R.string.browser_load_failed,
                                    ErrorText.of(e),
                                ),
                            )
                        }
                    }
                }
        }
    }

    /**
     * 重新检测。
     *
     * 检查项未通过属于正常返回，不设 error；只有请求本身失败才给出说明。
     * 成功后把结果回填到 [BrowserChromeStatusDto.lastSelfTest]，
     * 状态卡里的「最近一次自检」不必再单独刷新一次状态。
     */
    fun selfTest() {
        val conn = connection() ?: return
        if (_state.value.selfTestRunning || _state.value.unsupported) return
        _state.update { it.copy(selfTestRunning = true, notice = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.browserSelfTest(conn) } }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            selfTestRunning = false,
                            selfTest = result,
                            status = it.status?.copy(lastSelfTest = result),
                            notice = null,
                        )
                    }
                }
                .onFailure { e ->
                    if (e.isMissingEndpoint()) {
                        _state.update {
                            it.copy(
                                selfTestRunning = false,
                                loaded = true,
                                unsupported = true,
                                status = null,
                                error = null,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                selfTestRunning = false,
                                notice = Texts.get(
                                    R.string.browser_self_test_failed,
                                    ErrorText.of(e),
                                ),
                            )
                        }
                    }
                }
        }
    }

    /** 读取 NM host 日志。200 行与服务端默认保留量一致，不做分页。 */
    fun readLogs() {
        val conn = connection() ?: return
        if (_state.value.logsLoading) return
        _state.update { it.copy(logsLoading = true, logs = null, logsError = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.bridgeBrowserLogs(conn, lines = 200) } }
                .onSuccess { result ->
                    _state.update { it.copy(logsLoading = false, logs = result.lines) }
                }
                .onFailure { e ->
                    val detail = (e as? io.qwenpaw.node.data.remote.ApiException)
                        ?.let(api::apiErrorDetailOf)
                    val text = when {
                        // 404 在这里有两种含义：插件端点没挂上，或插件已装但 NM host
                        // 还没写过日志。后者不是故障，不能报成「插件未安装」。
                        detail?.code == "log_not_found" ->
                            Texts.get(R.string.browser_logs_not_found)

                        e.isMissingEndpoint() ->
                            Texts.get(R.string.browser_logs_plugin_missing)

                        else -> Texts.get(R.string.browser_logs_failed, ErrorText.of(e))
                    }
                    _state.update { it.copy(logsLoading = false, logsError = text) }
                }
        }
    }

    /** 尝试修复。二次确认由界面负责，这里只管请求与结果。 */
    fun repair() {
        val conn = connection() ?: return
        if (_state.value.repairing) return
        _state.update { it.copy(repairing = true, notice = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.bridgeBrowserRepair(conn) } }
                .onSuccess {
                    _state.update {
                        it.copy(repairing = false, notice = Texts.get(R.string.browser_repair_done))
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(repairing = false, notice = repairFailureText(e)) }
                }
        }
    }

    /** 用户确认看过一次性提示。自检与修复的结果、以及上次读取失败都从这里清掉。 */
    fun consumeMessage() {
        _state.update { it.copy(notice = null, error = null) }
    }

    /** 切换实例/智能体时清空，避免旧实例的诊断结论留在页面上。 */
    fun clear() {
        _state.value = BrowserDiagnosticsUiState()
    }

    /**
     * 修复失败的一句话。
     *
     * 服务端原文优先：`chrome_plugin_missing` 这类结论只能从原文看出来，
     * 换成通用文案会让用户以为修复接口坏了。插件端点未安装时回的是 404，
     * 不能交给 [ErrorText.of]（它会说成「不存在」），这里单独给插件提示。
     */
    private fun repairFailureText(e: Throwable): String {
        if (e is ApiException) {
            val detail = api.apiErrorDetailOf(e)
            val code = detail?.code.orEmpty()
            val body = detail?.message?.takeIf { it.isNotBlank() }
                ?: e.detail?.takeIf { it.isNotBlank() }
                ?: e.message
            val base = when {
                e.code == 404 && detail == null -> Texts.get(R.string.browser_repair_plugin_missing)
                code.isNotBlank() -> Texts.get(R.string.browser_repair_failed_code, body, code)
                else -> Texts.get(R.string.browser_repair_failed, body)
            }
            val missing = code == CODE_EXTENSION_MISSING || code == CODE_PLUGIN_MISSING
            return if (missing) {
                base + "\n" + Texts.get(R.string.browser_repair_plugin_missing_hint)
            } else {
                base
            }
        }
        return Texts.get(R.string.browser_repair_failed, ErrorText.of(e))
    }

    private companion object {
        /** 服务端报「浏览器扩展没装」。 */
        const val CODE_EXTENSION_MISSING = "chrome_extension_missing"

        /** 服务端报「插件没装」。 */
        const val CODE_PLUGIN_MISSING = "chrome_plugin_missing"
    }
}

/** 端点不存在：服务端版本低或插件未安装，属于能力差异而非网络故障。 */
private fun Throwable.isMissingEndpoint(): Boolean = this is ApiException && code == 404
