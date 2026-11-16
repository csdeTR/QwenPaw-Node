package io.qwenpaw.node.ui.agents

import io.qwenpaw.node.data.model.PortabilityAssetDto
import io.qwenpaw.node.data.model.PortabilityJobDto
import io.qwenpaw.node.data.model.PortabilitySelectionDto
import io.qwenpaw.node.data.model.PortabilitySourceDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
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

enum class ImportStep { SOURCES, SCANNING, INVENTORY, RUNNING, RESULT }

data class PortabilityUiState(
    val step: ImportStep = ImportStep.SOURCES,
    val sources: List<PortabilitySourceDto> = emptyList(),
    val selectedSources: Set<String> = emptySet(),
    val job: PortabilityJobDto? = null,
    val selectedAssets: Set<String> = emptySet(),
    val sessions: Map<String, Boolean> = emptyMap(),
    val allowPluginExecution: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * 从其他客户端导入。
 *
 * 任务状态由插件转发核心的 `PortabilityImportJobManager`；客户端只轮询，
 * 不用 SSE。轮询挂在 ViewModel 上，页面离开时调用 [stopPolling]。
 */
class PortabilityViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PortabilityUiState())
    val state: StateFlow<PortabilityUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun load() {
        val conn = connection() ?: run {
            _state.update { it.copy(loading = false, error = "尚未连接服务器") }
            return
        }
        _state.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            try {
                val (sources, current) = withContext(Dispatchers.IO) {
                    val list = api.portabilitySources(conn)
                    val job = runCatching { api.portabilityCurrentJob(conn) }.getOrNull()
                    list to job
                }
                val detected = sources.filter { it.detected }.map { it.source }
                _state.update {
                    it.copy(
                        sources = sources,
                        selectedSources = if (it.selectedSources.isEmpty()) detected.toSet() else it.selectedSources,
                        job = current,
                        step = current?.let(::stepFor) ?: ImportStep.SOURCES,
                        loading = false,
                    )
                }
                if (current != null && !current.isTerminal) {
                    applyJob(current)
                    startPolling(current.jobId)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = ErrorText.of(e))
                }
            }
        }
    }

    fun toggleSource(source: String) {
        _state.update {
            val selected = if (source in it.selectedSources) {
                it.selectedSources - source
            } else {
                it.selectedSources + source
            }
            it.copy(selectedSources = selected)
        }
    }

    fun createJob() {
        val conn = connection() ?: return
        val sources = _state.value.selectedSources.toList()
        if (sources.isEmpty()) {
            _state.update { it.copy(error = "请至少选择一个来源") }
            return
        }
        _state.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            try {
                val job = withContext(Dispatchers.IO) { api.portabilityCreateJob(conn, sources) }
                _state.update {
                    it.copy(
                        step = ImportStep.SCANNING,
                        job = job,
                        loading = false,
                        selectedAssets = emptySet(),
                        sessions = emptyMap(),
                    )
                }
                startPolling(job.jobId)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun toggleAsset(key: String) {
        _state.update {
            val selected = if (key in it.selectedAssets) {
                it.selectedAssets - key
            } else {
                it.selectedAssets + key
            }
            it.copy(selectedAssets = selected)
        }
    }

    fun setSessions(source: String, enabled: Boolean) {
        _state.update { it.copy(sessions = it.sessions + (source to enabled)) }
    }

    fun setAllowPluginExecution(enabled: Boolean) {
        _state.update { it.copy(allowPluginExecution = enabled) }
    }

    fun startImport() {
        val conn = connection() ?: return
        val current = _state.value
        val job = current.job ?: return
        if (current.selectedAssets.isEmpty() && current.sessions.values.none { it }) {
            _state.update { it.copy(error = "请至少选择一项要导入的内容") }
            return
        }
        val selections = buildSelections(job, current.selectedAssets, current.sessions)
        _state.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    api.portabilityStartJob(conn, job.jobId, selections, current.allowPluginExecution)
                }
                _state.update {
                    it.copy(step = ImportStep.RUNNING, job = updated, loading = false)
                }
                startPolling(job.jobId)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun retryFailed() {
        val conn = connection() ?: return
        val current = _state.value
        val job = current.job ?: return
        val selections = buildSelections(job, current.selectedAssets, current.sessions)
        _state.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    api.portabilityRetryJob(conn, job.jobId, selections, current.allowPluginExecution)
                }
                _state.update {
                    it.copy(step = ImportStep.RUNNING, job = updated, loading = false)
                }
                startPolling(updated.jobId)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun cancel() {
        val conn = connection() ?: return
        val job = _state.value.job ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.portabilityCancelJob(conn, job.jobId) } }
                .onSuccess { updated -> applyJob(updated) }
                .onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    private fun startPolling(jobId: String) {
        val conn = connection() ?: return
        pollJob?.cancel()
        pollJob = scope.launch {
            repeat(400) {
                delay(1_500)
                val job = runCatching {
                    withContext(Dispatchers.IO) { api.portabilityJob(conn, jobId) }
                }.getOrNull() ?: return@repeat
                applyJob(job)
                if (job.isTerminal) return@launch
                if (job.state == "ready") return@launch
            }
        }
    }

    private fun applyJob(job: PortabilityJobDto) {
        _state.update { state ->
            val step = stepFor(job)
            val defaults = if (step == ImportStep.INVENTORY && state.selectedAssets.isEmpty()) {
                defaultAssetKeys(job)
            } else {
                state.selectedAssets
            }
            val sessions = if (step == ImportStep.INVENTORY && state.sessions.isEmpty()) {
                job.providers.associate { it.source to true }
            } else {
                state.sessions
            }
            state.copy(
                job = job,
                step = step,
                selectedAssets = defaults,
                sessions = sessions,
                notice = if (job.isTerminal) resultNotice(job) else state.notice,
            )
        }
        if (job.isTerminal) pollJob?.cancel()
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    fun reset() {
        stopPolling()
        _state.value = PortabilityUiState()
    }

    fun clear() = reset()

    fun dismissNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }
}

/** `ready` 表示扫描完成、等待选择；其余非终态按运行中处理。 */
internal fun stepFor(job: PortabilityJobDto): ImportStep = when {
    job.isTerminal -> ImportStep.RESULT
    job.state == "ready" -> ImportStep.INVENTORY
    job.state == "scanning" -> ImportStep.SCANNING
    else -> ImportStep.RUNNING
}

internal fun assetKey(source: String, asset: PortabilityAssetDto): String =
    "$source|${asset.assetType}|${asset.sourceId}"

/** 默认勾选可导入且尚未存在的资产；已存在、失败与被阻止的默认不选。 */
internal fun defaultAssetKeys(job: PortabilityJobDto): Set<String> =
    job.providers.flatMap { provider ->
        provider.assets.filter { asset ->
            asset.blockedReason.isBlank() &&
                asset.state != "existing" &&
                asset.state != "failed" &&
                asset.state != "succeeded"
        }.map { assetKey(provider.source, it) }
    }.toSet()

internal fun buildSelections(
    job: PortabilityJobDto,
    selected: Set<String>,
    sessions: Map<String, Boolean>,
): Map<String, PortabilitySelectionDto> {
    return job.providers.associate { provider ->
        val assets = provider.assets.filter { assetKey(provider.source, it) in selected }
        fun ids(type: String): List<String> =
            assets.filter { it.assetType == type }.map { it.sourceId }
        provider.source to PortabilitySelectionDto(
            sessions = sessions[provider.source] ?: true,
            memory = ids("memory"),
            cron = ids("cron"),
            skills = ids("skills"),
            mcp = ids("mcp"),
            plugins = ids("plugins"),
        )
    }
}

internal fun resultNotice(job: PortabilityJobDto): String = when (job.state) {
    "completed" -> "导入完成"
    "completed_with_issues" -> "导入完成，部分项目未成功"
    "failed" -> "导入失败"
    "interrupted" -> "导入被服务端中断"
    "cancelled" -> "导入已取消"
    else -> ""
}
