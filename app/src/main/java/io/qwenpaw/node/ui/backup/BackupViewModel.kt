package io.qwenpaw.node.ui.backup

import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.model.BackupDetailDto
import io.qwenpaw.node.data.model.BackupJobSnapshotDto
import io.qwenpaw.node.data.model.BackupMetaDto
import io.qwenpaw.node.data.model.BackupScopeDto
import io.qwenpaw.node.data.model.CreateBackupRequestDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.BackupImportConflictException
import io.qwenpaw.node.data.remote.BackupTrustException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.DownloadClient
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.stream.BackupJobStreamClient
import io.qwenpaw.node.ui.workspace.DownloadSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody

/** 导出进度。`total` 来自响应的 `Content-Length`，未知时为 null。 */
data class BackupTransfer(val read: Long = 0, val total: Long? = null)

/** 导入流程的阶段。冲突与信任确认都保留已上传文件的上下文以便重试。 */
sealed interface ImportState {

    data object Idle : ImportState

    data class Uploading(
        val fileName: String = "",
        val read: Long = 0,
        val total: Long? = null,
    ) : ImportState

    /** 目标 id 已存在。确认后只带 `pendingToken` 重发，不再上传文件。 */
    data class Conflict(
        val existing: BackupMetaDto,
        val pendingToken: String,
        val fileName: String,
    ) : ImportState

    /** 签名不可信。`code` 决定是否提供「信任并导入」。 */
    data class TrustRequired(
        val code: String,
        val message: String,
        val fileName: String,
    ) : ImportState {

        /** 只有前两种签名问题可以显式信任；未知方案没有通路。 */
        val canTrust: Boolean
            get() = code == "backup_legacy_unsigned" || code == "backup_signature_mismatch"

        val trustMode: String?
            get() = when (code) {
                "backup_legacy_unsigned" -> "legacy"
                "backup_signature_mismatch" -> "foreign"
                else -> null
            }
    }

    data class Saving(val fileName: String = "") : ImportState

    data class Done(val backup: BackupMetaDto) : ImportState
}

/** 备份页面的全部状态。任务卡与列表分开，避免刷新列表时打断进度。 */
data class BackupUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val backups: List<BackupMetaDto> = emptyList(),
    val agents: List<AgentDto> = emptyList(),
    val job: BackupJobSnapshotDto? = null,
    val jobStreaming: Boolean = false,
    val jobError: String? = null,
    val creating: Boolean = false,
    val createName: String = "手动备份",
    val createDescription: String = "",
    val createScope: BackupScopeDto = BackupScopeDto(),
    val createAgentIds: Set<String> = emptySet(),
    val createError: String? = null,
    val detail: BackupDetailDto? = null,
    val detailLoadingId: String? = null,
    val detailError: String? = null,
    val deleteTarget: BackupMetaDto? = null,
    val deleting: Boolean = false,
    val exportId: String? = null,
    val export: BackupTransfer? = null,
    val exportError: String? = null,
    val import: ImportState = ImportState.Idle,
    val restore: RestoreDraft? = null,
    val restoreApplying: Boolean = false,
    val restoreError: String? = null,
    val notice: String? = null,
)

/**
 * 备份列表、创建任务、导出、导入与恢复。
 *
 * 设计取舍：
 * - 任务进度走 SSE，每一帧都是完整快照，直接替换；断流不自动重连，
 *   由 [refreshJob] 改取 `GET /jobs/{id}`。任务只存在服务端内存，重启后 404，
 *   此时清空任务卡而不是反复重试。
 * - 导入的请求体由界面按需创建，重试（覆盖、信任）会重新上传文件；
 *   ViewModel 只保留「怎么再创建一次」的工厂，不持有 `Uri`。
 * - 恢复的影响范围在 [RestoreDraft.impacts] 里逐条列出，确认框直接渲染，
 *   ViewModel 不拼提示文案。
 */
class BackupViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val downloadClient: DownloadClient,
    private val jobStream: BackupJobStreamClient = BackupJobStreamClient(api, QwenPawApi.streamClient()),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var generation = 0
    private var loadJob: Job? = null
    private var streamJob: Job? = null
    private var exportJob: Job? = null
    private var importJob: Job? = null
    private var pendingImportBody: (() -> RequestBody)? = null

    fun ensureLoaded() {
        if (_state.value.loaded || _state.value.loading) return
        refresh()
    }

    /** 重新读取列表、智能体与活动任务。 */
    fun refresh() {
        val conn = connection() ?: run {
            _state.update { it.copy(loading = false, loaded = true, error = "尚未连接服务器") }
            return
        }
        val gen = generation
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val backups = api.backups(conn)
                    val agents = runCatching { api.agents(conn) }.getOrDefault(emptyList())
                    val active = runCatching { api.activeBackupJob(conn) }.getOrNull()
                    Triple(backups, agents, active)
                }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        error = null,
                        backups = result.first,
                        agents = result.second,
                        // 任务结束后服务端不再返回 active，保留终态快照让任务卡停在「已完成」。
                        job = result.third ?: it.job?.takeIf { job -> job.isTerminal },
                        createAgentIds = if (it.createAgentIds.isEmpty()) {
                            result.second.map { agent -> agent.id }.toSet()
                        } else {
                            it.createAgentIds
                        },
                    )
                }
                result.third?.takeIf { !it.isTerminal }?.let { startJobStream(it.jobId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(loading = false, loaded = true, error = backupError(e))
                }
            }
        }
    }

    // ---------- 创建 ----------

    fun setCreateName(value: String) {
        _state.update { it.copy(createName = value) }
    }

    fun setCreateDescription(value: String) {
        _state.update { it.copy(createDescription = value) }
    }

    fun toggleCreateAgent(id: String) {
        _state.update { state ->
            val ids = if (id in state.createAgentIds) {
                state.createAgentIds - id
            } else {
                state.createAgentIds + id
            }
            state.copy(createAgentIds = ids)
        }
    }

    fun setCreateScope(scope: BackupScopeDto) {
        _state.update { it.copy(createScope = scope) }
    }

    /** 创建备份。`agents` 必须显式下发，即使范围里不包含工作区。 */
    fun startCreate() {
        val conn = connection() ?: return
        val current = _state.value
        if (current.creating) return
        val name = current.createName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(createError = "请填写备份名称") }
            return
        }
        if (current.createScope.includeAgents && current.createAgentIds.isEmpty()) {
            _state.update { it.copy(createError = "请至少选择一个智能体") }
            return
        }
        val request = CreateBackupRequestDto(
            name = name,
            description = current.createDescription.trim(),
            scope = current.createScope,
            agents = current.createAgentIds.toList(),
        )
        val gen = generation
        _state.update { it.copy(creating = true, createError = null) }
        scope.launch {
            try {
                val job = withContext(Dispatchers.IO) { api.startBackupJob(conn, request) }
                if (gen != generation) return@launch
                _state.update { it.copy(creating = false, job = job, jobError = null) }
                if (!job.isTerminal) startJobStream(job.jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        creating = false,
                        createError = if (e is ApiException && e.code == 409) {
                            "已有备份任务或恢复正在进行，请等待结束后重试"
                        } else {
                            backupError(e)
                        },
                    )
                }
            }
        }
    }

    // ---------- 任务 ----------

    /** 断流或回到页面时取一次权威状态。任务不存在（服务端重启）时清空任务卡。 */
    fun refreshJob() {
        val conn = connection() ?: return
        val jobId = _state.value.job?.jobId?.takeIf { it.isNotBlank() } ?: return
        val gen = generation
        scope.launch {
            try {
                val job = withContext(Dispatchers.IO) { api.backupJob(conn, jobId) }
                if (gen != generation) return@launch
                _state.update { it.copy(job = job, jobError = null) }
                if (!job.isTerminal) startJobStream(job.jobId) else refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                if (e is ApiException && e.code == 404) {
                    streamJob?.cancel()
                    _state.update {
                        it.copy(job = null, jobStreaming = false, jobError = "备份任务已结束，进度不可再查询")
                    }
                    refresh()
                } else {
                    _state.update { it.copy(jobError = backupError(e)) }
                }
            }
        }
    }

    fun cancelJob() {
        val conn = connection() ?: return
        val jobId = _state.value.job?.jobId?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            try {
                val job = withContext(Dispatchers.IO) { api.cancelBackupJob(conn, jobId) }
                _state.update { it.copy(job = job) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(jobError = backupError(e)) }
            }
        }
    }

    private fun startJobStream(jobId: String) {
        val conn = connection() ?: return
        val gen = generation
        streamJob?.cancel()
        _state.update { it.copy(jobStreaming = true, jobError = null) }
        streamJob = scope.launch {
            var terminal = false
            try {
                jobStream.open(conn, jobId).collect { snapshot ->
                    if (gen != generation) return@collect
                    val notice = if (snapshot.isTerminal) {
                        when (snapshot.status) {
                            "completed" -> "备份已完成"
                            "cancelled" -> "备份已取消"
                            "failed" -> null
                            else -> null
                        }
                    } else {
                        null
                    }
                    _state.update { it.copy(job = snapshot, notice = notice ?: it.notice) }
                    if (snapshot.isTerminal) terminal = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(jobError = backupError(e)) }
            } finally {
                if (gen == generation) {
                    _state.update { it.copy(jobStreaming = false) }
                    if (terminal) refresh()
                }
            }
        }
    }

    // ---------- 详情 ----------

    fun openDetail(id: String) {
        val conn = connection() ?: return
        val gen = generation
        _state.update { it.copy(detailLoadingId = id, detailError = null) }
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { api.backupDetail(conn, id) }
                if (gen != generation) return@launch
                _state.update { it.copy(detail = detail, detailLoadingId = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(detailLoadingId = null, detailError = backupError(e)) }
            }
        }
    }

    fun dismissDetail() {
        _state.update { it.copy(detail = null, detailError = null) }
    }

    // ---------- 删除 ----------

    fun requestDelete(backup: BackupMetaDto) {
        _state.update { it.copy(deleteTarget = backup) }
    }

    fun dismissDelete() {
        _state.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val conn = connection() ?: return
        val target = _state.value.deleteTarget ?: return
        if (_state.value.deleting) return
        val gen = generation
        _state.update { it.copy(deleting = true) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.deleteBackups(conn, listOf(target.id)) }
                if (gen != generation) return@launch
                val failed = result.failed.firstOrNull()
                _state.update {
                    it.copy(
                        deleting = false,
                        deleteTarget = if (failed == null) null else it.deleteTarget,
                        notice = if (failed == null) "已删除备份「${target.name}」" else null,
                        error = failed?.let { item ->
                            "无法删除备份：${item.error.orEmpty().ifBlank { "服务端未返回原因" }}"
                        },
                    )
                }
                if (failed == null) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(deleting = false, error = backupError(e)) }
            }
        }
    }

    // ---------- 导出 ----------

    fun export(id: String, sink: DownloadSink) {
        val conn = connection() ?: return
        if (exportJob?.isActive == true) return
        val gen = generation
        _state.update { it.copy(exportId = id, export = BackupTransfer(), exportError = null) }
        exportJob = scope.launch {
            val out = try {
                sink.open()
            } catch (e: Exception) {
                _state.update { it.copy(exportId = null, exportError = backupError(e)) }
                return@launch
            }
            if (out == null) {
                _state.update { it.copy(exportId = null, exportError = "无法写入所选位置，请换一个目录") }
                return@launch
            }
            try {
                out.use { stream ->
                    downloadClient.download(api.exportBackupRequest(conn, id), stream) { read, total ->
                        if (gen != generation) return@download
                        _state.update { it.copy(export = BackupTransfer(read, total)) }
                    }
                }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(exportId = null, export = null, notice = "备份已导出到所选位置")
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { sink.discard() } }
                if (gen == generation) _state.update { it.copy(exportId = null, export = null) }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) { runCatching { sink.discard() } }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(exportId = null, export = null, exportError = "导出失败：${backupError(e)}")
                }
            }
        }
    }

    fun dismissExportError() {
        _state.update { it.copy(exportError = null) }
    }

    // ---------- 导入 ----------

    /**
     * 开始导入。
     *
     * [newBody] 每次调用都要返回一个新的请求体：覆盖确认与信任确认都会重新
     * 上传同一个文件。传入的进度回调由界面接到 [reportImportProgress]。
     */
    fun beginImport(fileName: String, newBody: () -> RequestBody) {
        if (importJob?.isActive == true) return
        pendingImportBody = newBody
        uploadImport(fileName, newBody(), pendingToken = null, trustMode = null)
    }

    fun reportImportProgress(read: Long, total: Long?) {
        _state.update { state ->
            val importing = state.import as? ImportState.Uploading ?: return@update state
            state.copy(import = importing.copy(read = read, total = total ?: importing.total))
        }
    }

    /** 覆盖同 id 的备份：只带 `pending_token`，不再上传文件。 */
    fun confirmImportOverwrite() {
        val conflict = _state.value.import as? ImportState.Conflict ?: return
        if (importJob?.isActive == true) return
        uploadImport(conflict.fileName, null, conflict.pendingToken, trustMode = null)
    }

    /** 信任非本机签名的备份后重新上传并导入。 */
    fun trustImportAndContinue() {
        val trust = _state.value.import as? ImportState.TrustRequired ?: return
        val mode = trust.trustMode ?: return
        val factory = pendingImportBody ?: return
        if (importJob?.isActive == true) return
        uploadImport(trust.fileName, factory(), pendingToken = null, trustMode = mode)
    }

    fun dismissImport() {
        importJob?.cancel()
        pendingImportBody = null
        _state.update { it.copy(import = ImportState.Idle) }
    }

    private fun uploadImport(
        fileName: String,
        body: RequestBody?,
        pendingToken: String?,
        trustMode: String?,
    ) {
        val conn = connection() ?: return
        val gen = generation
        _state.update {
            it.copy(
                import = if (body != null) ImportState.Uploading(fileName) else ImportState.Saving(fileName),
            )
        }
        importJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.importBackup(conn, body, fileName, pendingToken, trustMode)
                }
                if (gen != generation) return@launch
                pendingImportBody = null
                _state.update { it.copy(import = ImportState.Done(result)) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupImportConflictException) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(import = ImportState.Conflict(e.existing, e.pendingToken, fileName))
                }
            } catch (e: BackupTrustException) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(import = ImportState.TrustRequired(e.code, e.serverMessage, fileName))
                }
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(import = ImportState.Idle, error = "导入失败：${backupError(e)}")
                }
            }
        }
    }

    // ---------- 恢复 ----------

    fun prepareRestore(id: String) {
        val conn = connection() ?: return
        val cached = _state.value.detail?.takeIf { it.id == id }
        if (cached != null) {
            _state.update { it.copy(restore = RestoreDraft(cached), restoreError = null) }
            return
        }
        val gen = generation
        _state.update { it.copy(detailLoadingId = id, detailError = null) }
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { api.backupDetail(conn, id) }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        detail = detail,
                        detailLoadingId = null,
                        restore = RestoreDraft(detail),
                        restoreError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(detailLoadingId = null, restoreError = backupError(e)) }
            }
        }
    }

    fun updateRestore(draft: RestoreDraft) {
        _state.update { it.copy(restore = draft) }
    }

    fun dismissRestore() {
        _state.update { it.copy(restore = null, restoreError = null) }
    }

    /** 执行恢复。签名不可信时把 code 写回草稿，界面据此提供「信任并恢复」。 */
    fun confirmRestore() {
        val conn = connection() ?: return
        val draft = _state.value.restore ?: return
        if (_state.value.restoreApplying) return
        val gen = generation
        _state.update { it.copy(restoreApplying = true, restoreError = null) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.restoreBackup(conn, draft.backup.id, draft.toRequest())
                }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        restoreApplying = false,
                        restore = null,
                        notice = if (result.preservedLocalKeys.isEmpty()) {
                            "备份已恢复"
                        } else {
                            "备份已恢复，本机受保护的密钥保持不变"
                        },
                    )
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupTrustException) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        restoreApplying = false,
                        restore = draft.copy(trustCode = e.code),
                        restoreError = e.serverMessage,
                    )
                }
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(restoreApplying = false, restoreError = backupError(e)) }
            }
        }
    }

    // ---------- 提示 ----------

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** 换实例或换智能体时清空。备份任务与备份列表不属于某个智能体，但连接已换。 */
    fun clear() {
        generation++
        loadJob?.cancel()
        streamJob?.cancel()
        exportJob?.cancel()
        importJob?.cancel()
        loadJob = null
        streamJob = null
        exportJob = null
        importJob = null
        pendingImportBody = null
        _state.value = BackupUiState()
    }

    private fun backupError(e: Throwable): String = when {
        e is ApiException && e.code == 401 -> ErrorText.of(e)
        e is ApiException && e.code == 404 -> "备份不存在，可能已被删除"
        e is ApiException && e.code == 409 -> "服务端正忙，请稍后重试"
        e is ApiException && e.code == 400 ->
            e.detail?.takeIf { it.isNotBlank() }?.take(200) ?: "请求参数无效"
        else -> ErrorText.of(e)
    }
}
