package io.qwenpaw.node.ui.checkpoint

import io.qwenpaw.node.data.model.CheckpointGraphDto
import io.qwenpaw.node.data.model.CheckpointNodeDto
import io.qwenpaw.node.data.model.CheckpointSessionDto
import io.qwenpaw.node.data.model.CheckpointStatusDto
import io.qwenpaw.node.data.model.GcResultDto
import io.qwenpaw.node.data.model.GcSettingsDto
import io.qwenpaw.node.data.model.RestoreResultDto
import io.qwenpaw.node.data.model.SnapshotRequestDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 检查点页面状态。工作区不是 git 仓库时 [writesDisabled] 为真，页面只读。 */
data class CheckpointUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val writesDisabled: Boolean = false,
    val status: CheckpointStatusDto? = null,
    val graph: CheckpointGraphDto? = null,
    val graphLoading: Boolean = false,
    val graphError: String? = null,
    val groups: List<CheckpointGroup> = emptyList(),
    val autoEnabled: Boolean = false,
    val autoSaving: Boolean = false,
    val snapshotSession: CheckpointSessionDto? = null,
    /** 只读对话页当前打开的会话；null 表示停留在检查点页。 */
    val viewingSession: CheckpointSessionDto? = null,
    val snapshotName: String = "",
    val snapshotPending: Boolean = false,
    val snapshotError: String? = null,
    val gcSettings: GcSettingsDto? = null,
    val gcDraft: GcDraft = GcDraft(),
    val gcSaving: Boolean = false,
    val gcError: String? = null,
    val gcPreviewing: Boolean = false,
    val gcApplying: Boolean = false,
    val gcPreview: GcResultDto? = null,
    val resetConfirm: Boolean = false,
    val resetting: Boolean = false,
    val resetError: String? = null,
    val restore: CheckpointRestoreDraft? = null,
    val restorePreview: RestoreResultDto? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val restoreApplying: Boolean = false,
    val restoreError: String? = null,
    val notice: String? = null,
)

/**
 * 工作区检查点：自动开关、时间线、手动快照、回滚与清理。
 *
 * 设计取舍：
 * - 所有端点按智能体隔离，客户端靠 `X-Agent-Id` 命中当前工作区，因此
 *   ViewModel 换实例时必须 [clear]，不能把上一个智能体的时间线留在内存里。
 * - 回滚预览与执行都用节点上的完整 `commit`；服务端支持 `#序号` 与短前缀，
 *   但客户端不自行拼，避免与 `restore_index` 的语义混淆。
 * - 打开文件范围时预览不带 `files`，服务端返回完整候选列表；执行时必须
 *   至少选中一个路径，[CheckpointRestoreDraft.canApply] 统一这条规则。
 */
class CheckpointViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(CheckpointUiState())
    val state: StateFlow<CheckpointUiState> = _state.asStateFlow()

    private var generation = 0
    private var loadJob: Job? = null
    private var previewJob: Job? = null

    fun ensureLoaded() {
        if (_state.value.loaded || _state.value.loading) return
        refresh()
    }

    /** 依次读取状态、图谱与清理设置。状态失败时不再请求后两项。 */
    fun refresh() {
        val conn = connection() ?: run {
            _state.update {
                it.copy(loading = false, loaded = true, error = "尚未连接服务器", writesDisabled = true)
            }
            return
        }
        val gen = generation
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null, graphLoading = true, graphError = null) }
        loadJob = scope.launch {
            try {
                val status = withContext(Dispatchers.IO) { api.checkpointStatus(conn) }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        status = status,
                        autoEnabled = status.autoEnabled,
                        writesDisabled = false,
                    )
                }
                val (graph, gc) = withContext(Dispatchers.IO) {
                    val graphDeferred = async { api.checkpointGraph(conn) }
                    val gcDeferred = async { runCatching { api.checkpointGcSettings(conn) }.getOrNull() }
                    graphDeferred.await() to gcDeferred.await()
                }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        graphLoading = false,
                        graph = graph,
                        groups = groupCheckpointNodes(graph.nodes, graph.sessions),
                        gcSettings = gc,
                        gcDraft = gc?.let { settings -> GcDraft.from(settings) } ?: it.gcDraft,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                val message = checkpointError(e)
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        graphLoading = false,
                        status = null,
                        writesDisabled = true,
                        error = message,
                    )
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ---------- 自动开关 ----------

    fun toggleAuto(enabled: Boolean) {
        val conn = connection() ?: return
        if (_state.value.autoSaving) return
        val previous = _state.value.autoEnabled
        _state.update { it.copy(autoEnabled = enabled, autoSaving = true, notice = null) }
        scope.launch {
            try {
                val actual = withContext(Dispatchers.IO) { api.setCheckpointAuto(conn, enabled) }
                _state.update { it.copy(autoEnabled = actual, autoSaving = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(autoEnabled = previous, autoSaving = false, error = checkpointError(e))
                }
            }
        }
    }

    // ---------- 手动快照 ----------

    fun setSnapshotSession(sessionKey: String) {
        _state.update { state ->
            val session = state.graph?.sessions?.firstOrNull { it.sessionKey == sessionKey }
            state.copy(snapshotSession = session, snapshotName = "")
        }
    }

    /**
     * 打开某条会话的只读对话页。
     *
     * 名称对话框此时不弹，等用户在只读页里点「创建检查点」再弹，
     * 这样「看内容」与「确认创建」是两个独立动作，不会一进来就被对话框挡住。
     */
    fun openSnapshot(sessionKey: String) {
        _state.update { state ->
            val session = state.graph?.sessions?.firstOrNull { it.sessionKey == sessionKey }
            state.copy(
                viewingSession = session,
                snapshotSession = session,
                snapshotName = "",
                snapshotError = null,
            )
        }
    }

    /** 离开只读对话页，回到检查点页。命名对话框随之关闭。 */
    fun closeSnapshot() {
        _state.update {
            it.copy(viewingSession = null, snapshotSession = null, snapshotName = "", snapshotError = null)
        }
    }

    fun setSnapshotName(value: String) {
        _state.update { it.copy(snapshotName = value) }
    }

    /**
     * 清掉命名对话框的输入，但不离开只读页。
     *
     * [snapshotSession] 必须保留：它是「为哪条会话创建」的依据，
     * 清掉之后只读页上再点「创建检查点」就没有目标了。
     */
    fun dismissSnapshot() {
        _state.update { it.copy(snapshotName = "", snapshotError = null) }
    }

    fun submitSnapshot() {
        val conn = connection() ?: return
        val session = _state.value.snapshotSession ?: return
        if (_state.value.snapshotPending) return
        val request = SnapshotRequestDto(
            sessionId = session.sessionId,
            userId = session.userId,
            channel = session.channel.ifBlank { "console" },
            name = _state.value.snapshotName.trim(),
        )
        val gen = generation
        _state.update { it.copy(snapshotPending = true, snapshotError = null) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.createCheckpointSnapshot(conn, request) }
                if (gen != generation) return@launch
                _state.update {
                    // 成功后连带退出只读页：用户接下来要看的是时间线上新增的节点。
                    it.copy(
                        snapshotPending = false,
                        snapshotSession = null,
                        viewingSession = null,
                        snapshotName = "",
                        notice = "已创建检查点",
                    )
                }
                reloadGraph()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(snapshotPending = false, snapshotError = checkpointError(e)) }
            }
        }
    }

    // ---------- 回滚 ----------

    fun openRestore(node: CheckpointNodeDto) {
        _state.update {
            it.copy(
                restore = CheckpointRestoreDraft(node),
                restorePreview = null,
                previewError = null,
                restoreError = null,
            )
        }
        loadPreview()
    }

    fun dismissRestore() {
        previewJob?.cancel()
        _state.update {
            it.copy(restore = null, restorePreview = null, previewLoading = false, previewError = null, restoreError = null)
        }
    }

    fun setIncludeMemory(value: Boolean) {
        updateRestore { it.copy(includeMemory = value) }
        loadPreview()
    }

    fun setIncludeFiles(value: Boolean) {
        updateRestore { it.copy(includeFiles = value, selectedFiles = if (value) it.selectedFiles else emptySet()) }
        loadPreview()
    }

    fun toggleFile(path: String) {
        updateRestore { draft ->
            draft.copy(
                selectedFiles = if (path in draft.selectedFiles) {
                    draft.selectedFiles - path
                } else {
                    draft.selectedFiles + path
                },
            )
        }
    }

    fun selectAllFiles() {
        val files = _state.value.restorePreview?.filePaths.orEmpty().toSet()
        updateRestore { it.copy(selectedFiles = files) }
    }

    fun clearFiles() {
        updateRestore { it.copy(selectedFiles = emptySet()) }
    }

    fun loadPreview() {
        val conn = connection() ?: return
        val draft = _state.value.restore ?: return
        val gen = generation
        previewJob?.cancel()
        _state.update { it.copy(previewLoading = true, previewError = null) }
        previewJob = scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    api.previewCheckpointRestore(conn, draft.toPreviewRequest())
                }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(previewLoading = false, restorePreview = preview)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(previewLoading = false, previewError = checkpointError(e)) }
            }
        }
    }

    fun applyRestore() {
        val conn = connection() ?: return
        val draft = _state.value.restore ?: return
        if (!draft.canApply || _state.value.restoreApplying) return
        val gen = generation
        _state.update { it.copy(restoreApplying = true, restoreError = null) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.applyCheckpointRestore(conn, draft.toRequest())
                }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        restoreApplying = false,
                        restore = null,
                        restorePreview = null,
                        notice = "已回滚 ${result.restoredPaths.size} 个文件" +
                            if (result.includeMemory) "，会话记忆已恢复" else "",
                    )
                }
                reloadGraph()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(restoreApplying = false, restoreError = checkpointError(e)) }
            }
        }
    }

    // ---------- 清理 ----------

    fun updateGcDraft(draft: GcDraft) {
        _state.update { it.copy(gcDraft = draft, gcError = null) }
    }

    fun saveGcSettings() {
        val conn = connection() ?: return
        val draft = _state.value.gcDraft
        val error = draft.validationError()
        if (error != null) {
            _state.update { it.copy(gcError = error) }
            return
        }
        val settings = draft.toSettings() ?: return
        if (_state.value.gcSaving) return
        val gen = generation
        _state.update { it.copy(gcSaving = true, gcError = null) }
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { api.updateCheckpointGcSettings(conn, settings) }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(gcSaving = false, gcSettings = saved, gcDraft = GcDraft.from(saved), notice = "清理策略已保存")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(gcSaving = false, gcError = checkpointError(e)) }
            }
        }
    }

    fun previewGc() {
        val conn = connection() ?: return
        val settings = currentGcSettings() ?: return
        if (_state.value.gcPreviewing) return
        val gen = generation
        _state.update { it.copy(gcPreviewing = true, gcError = null) }
        scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    api.previewCheckpointGc(conn, _state.value.gcDraft.toRequest(settings))
                }
                if (gen != generation) return@launch
                _state.update { it.copy(gcPreviewing = false, gcPreview = preview) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(gcPreviewing = false, gcError = checkpointError(e)) }
            }
        }
    }

    fun applyGc() {
        val conn = connection() ?: return
        val settings = currentGcSettings() ?: return
        if (_state.value.gcApplying) return
        val gen = generation
        _state.update { it.copy(gcApplying = true, gcError = null) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.applyCheckpointGc(conn, _state.value.gcDraft.toRequest(settings))
                }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        gcApplying = false,
                        gcPreview = null,
                        notice = "已清理 ${result.deletedRefs.size} 个检查点",
                    )
                }
                reloadGraph()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(gcApplying = false, gcError = checkpointError(e)) }
            }
        }
    }

    private fun currentGcSettings(): GcSettingsDto? {
        val draft = _state.value.gcDraft
        val error = draft.validationError()
        if (error != null) {
            _state.update { it.copy(gcError = error) }
            return null
        }
        return draft.toSettings()
    }

    // ---------- 重置 ----------

    fun requestReset() {
        _state.update { it.copy(resetConfirm = true, resetError = null) }
    }

    fun dismissReset() {
        _state.update { it.copy(resetConfirm = false, resetError = null) }
    }

    fun confirmReset() {
        val conn = connection() ?: return
        if (_state.value.resetting) return
        val gen = generation
        _state.update { it.copy(resetting = true, resetError = null) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.resetCheckpoints(conn) }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        resetting = false,
                        resetConfirm = false,
                        autoEnabled = result.autoEnabled,
                        notice = "已删除全部检查点",
                    )
                }
                reloadGraph()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(resetting = false, resetError = checkpointError(e)) }
            }
        }
    }

    // ---------- 通用 ----------

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** 换实例或换智能体时清空：检查点归属上一个工作区。 */
    fun clear() {
        generation++
        loadJob?.cancel()
        previewJob?.cancel()
        loadJob = null
        previewJob = null
        _state.value = CheckpointUiState()
    }

    private fun updateRestore(block: (CheckpointRestoreDraft) -> CheckpointRestoreDraft) {
        _state.update { state ->
            val draft = state.restore ?: return@update state
            state.copy(restore = block(draft))
        }
    }

    private fun reloadGraph() {
        val conn = connection() ?: return
        val gen = generation
        _state.update { it.copy(graphLoading = true, graphError = null) }
        scope.launch {
            try {
                val graph = withContext(Dispatchers.IO) { api.checkpointGraph(conn) }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        graphLoading = false,
                        graph = graph,
                        groups = groupCheckpointNodes(graph.nodes, graph.sessions),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(graphLoading = false, graphError = checkpointError(e)) }
            }
        }
    }

    private fun checkpointError(e: Throwable): String = when {
        e is ApiException && e.code == 400 ->
            e.detail?.takeIf { it.isNotBlank() }?.take(200) ?: "请求参数无效"
        e is ApiException && e.code == 404 -> "检查点不存在，可能已被清理"
        e is ApiException && e.code == 409 -> "服务端已有恢复或清理在进行，请稍后重试"
        else -> ErrorText.of(e)
    }
}
