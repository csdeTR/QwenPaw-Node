package io.qwenpaw.node.ui.localmodels

import io.qwenpaw.node.R
import io.qwenpaw.node.data.localmodel.isActiveDownload
import io.qwenpaw.node.data.model.LocalModelDto
import io.qwenpaw.node.data.model.LocalModelProgressDto
import io.qwenpaw.node.data.model.LocalModelServerDto
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

data class LocalModelsUiState(
    val server: LocalModelServerDto? = null,
    val models: List<LocalModelDto> = emptyList(),
    /** 模型下载进度；与 [serverProgress] 只有一个会在活动。 */
    val modelProgress: LocalModelProgressDto? = null,
    /** llama.cpp 运行时自身的下载进度。 */
    val serverProgress: LocalModelProgressDto? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** 触发动作期间禁用按钮，避免连点。 */
    val actionBusy: Boolean = false,
    val error: String? = null,
    /** 一次性成功提示；界面取走后调用 [LocalModelsViewModel.consumeMessage]。 */
    val messageRes: Int? = null,
) {
    /** 当前活动中的下载：运行时优先，其次是模型。 */
    val activeSource: DownloadSource?
        get() = when {
            isActiveDownload(serverProgress?.status.orEmpty()) -> DownloadSource.SERVER
            isActiveDownload(modelProgress?.status.orEmpty()) -> DownloadSource.MODEL
            else -> null
        }
}

enum class DownloadSource { SERVER, MODEL }

/**
 * 本地模型页的数据与轮询。
 *
 * 服务端的下载是「立即返回 + 轮询进度」，且没有 SSE。轮询只在一个任务处于
 * `pending/downloading/canceling` 时进行；任务结束后刷新一次列表与运行时状态，
 * 让「已下载」标记与 `installed` 跟上。
 */
class LocalModelsViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val pollIntervalMs: Long = 1_000L,
    private val maxPolls: Int = 1_800,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(LocalModelsUiState())
    val state: StateFlow<LocalModelsUiState> = _state.asStateFlow()
    private var pollJob: Job? = null

    fun refresh(force: Boolean = false) {
        if (!force && (_state.value.loaded || _state.value.loading)) return
        val conn = connection() ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val server = api.localModelServer(conn)
                    val models = api.localModels(conn)
                    val modelProgress = api.localModelDownloadProgress(conn)
                    val serverProgress = api.serverDownloadProgress(conn)
                    Quad(server, models, modelProgress, serverProgress)
                }
            }.onSuccess { data ->
                _state.update {
                    it.copy(
                        server = data.server,
                        models = data.models,
                        modelProgress = data.modelProgress,
                        serverProgress = data.serverProgress,
                        loading = false,
                        loaded = true,
                        error = null,
                    )
                }
                ensurePolling(conn)
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                }
            }
        }
    }

    private data class Quad(
        val server: LocalModelServerDto,
        val models: List<LocalModelDto>,
        val modelProgress: LocalModelProgressDto,
        val serverProgress: LocalModelProgressDto,
    )

    fun installRuntime() {
        val conn = connection() ?: return
        if (_state.value.actionBusy) return
        _state.update { it.copy(actionBusy = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.startServerDownload(conn) } }
                .onSuccess {
                    _state.update {
                        it.copy(actionBusy = false, messageRes = R.string.local_models_install_started)
                    }
                    refresh(force = true)
                }
                .onFailure { e -> _state.update { it.copy(actionBusy = false, error = actionError(e)) } }
        }
    }

    fun downloadModel(modelName: String) {
        val conn = connection() ?: return
        if (_state.value.actionBusy) return
        _state.update { it.copy(actionBusy = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.startLocalModelDownload(conn, modelName) } }
                .onSuccess {
                    _state.update {
                        it.copy(actionBusy = false, messageRes = R.string.local_models_download_started)
                    }
                    refresh(force = true)
                }
                .onFailure { e -> _state.update { it.copy(actionBusy = false, error = actionError(e)) } }
        }
    }

    /** 取消当前活动中的下载。运行时与模型共用一个入口，按活动来源分派。 */
    fun cancelActive() {
        val conn = connection() ?: return
        val source = _state.value.activeSource ?: return
        _state.update { it.copy(actionBusy = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (source) {
                        DownloadSource.SERVER -> api.cancelServerDownload(conn)
                        DownloadSource.MODEL -> api.cancelLocalModelDownload(conn)
                    }
                }
            }.onSuccess {
                _state.update { it.copy(actionBusy = false) }
                refresh(force = true)
            }.onFailure { e ->
                _state.update { it.copy(actionBusy = false, error = actionError(e)) }
            }
        }
    }

    private fun actionError(e: Throwable): String =
        if (e is ApiException && e.code == 409) {
            Texts.get(R.string.local_models_busy)
        } else {
            Texts.get(R.string.local_models_action_failed, ErrorText.of(e))
        }

    private fun ensurePolling(conn: Connection) {
        if (_state.value.activeSource == null) {
            stopPolling()
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var wasActive = true
            repeat(maxPolls) {
                delay(pollIntervalMs)
                val data = runCatching {
                    withContext(Dispatchers.IO) {
                        api.localModelDownloadProgress(conn) to api.serverDownloadProgress(conn)
                    }
                }.getOrElse { e ->
                    pollJob = null
                    _state.update { it.copy(error = ErrorText.of(e)) }
                    return@launch
                }
                _state.update {
                    it.copy(modelProgress = data.first, serverProgress = data.second)
                }
                val active = _state.value.activeSource != null
                if (!active) {
                    // 任务刚结束：刷新一次，让「已下载」与 installed 跟上。
                    if (wasActive) refresh(force = true)
                    return@launch
                }
                wasActive = active
            }
        }
    }

    /** 页面离开时调用。 */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null) }
    }

    fun clear() {
        stopPolling()
        _state.value = LocalModelsUiState()
    }
}