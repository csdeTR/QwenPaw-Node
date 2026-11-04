package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.CronDispatchTargetItemDto
import io.qwenpaw.node.data.model.CronExecutionRecordDto
import io.qwenpaw.node.data.model.CronJobSpecDto
import io.qwenpaw.node.data.model.CronJobStateDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 定时任务页的状态。 */
data class CronUiState(
    val jobs: List<CronJobSpecDto> = emptyList(),
    /** job id → 运行状态。缺项表示该条状态没取到。 */
    val states: Map<String, CronJobStateDto> = emptyMap(),
    /** 单条操作的错误，按 job id 存放；与整页的 [error] 分开，避免一行的失败把整页打成错误态。 */
    val rowErrors: Map<String, String> = emptyMap(),
    val targets: List<CronDispatchTargetItemDto> = emptyList(),
    val targetChannels: List<String> = emptyList(),
    /** session_id → 会话名称。投递目标本身不带名称，这里从会话列表补齐。 */
    val chatNames: Map<String, String> = emptyMap(),
    /** 服务端配置的时区；null 表示没取到，界面不显示双时区对照。 */
    val serverTimezone: String? = null,
    /** 执行记录，按 job id 存放，打开记录页时按需拉取。 */
    val history: Map<String, List<CronExecutionRecordDto>> = emptyMap(),
    val historyLoading: Boolean = false,
    /** 编辑中的任务；null 表示新建。 */
    val draft: CronJobSpecDto? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * 定时任务。
 *
 * 列表与运行状态是两次请求：`GET /api/cron/jobs` 不带状态，只有 `/state` 有。
 * 状态按并发上限 4 逐条拉，单条失败只让该行显示「状态未知」，
 * 不把整页打成错误态——一个任务的 state 取不到，不影响其余任务的开关与操作。
 *
 * 所有动作都走 `X-Agent-Id`，由 [QwenPawApi] 统一注入，这里不做特判。
 */
class CronViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(CronUiState())
    val state: StateFlow<CronUiState> = _state.asStateFlow()

    private var targetsFetchedAt: Long = 0L

    /** 首次进入或手动刷新。列表与状态一起换，避免旧状态挂在新列表上。 */
    fun refresh() {
        val conn = connection() ?: return
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.cronJobs(conn) } }
                .onSuccess { jobs ->
                    _state.update { it.copy(jobs = jobs, loading = false, loaded = true, rowErrors = emptyMap()) }
                    loadStates(conn, jobs)
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
                }
            // 时区读失败保持 null，不拦页面：对照块是补充信息，不是这一页的前提。
            runCatching { withContext(Dispatchers.IO) { api.userTimezone(conn) } }
                .onSuccess { zone -> _state.update { it.copy(serverTimezone = zone) } }
        }
    }

    /** 并发上限 4 拉每条任务的 /state。 */
    private fun loadStates(conn: Connection, jobs: List<CronJobSpecDto>) {
        val ids = jobs.mapNotNull { it.id }
        if (ids.isEmpty()) return
        scope.launch {
            val fetched = coroutineScope {
                ids.chunked(4).flatMap { chunk ->
                    chunk.map { id ->
                        async(Dispatchers.IO) {
                            runCatching { api.cronJobState(conn, id) }.getOrNull()?.let { id to it }
                        }
                    }.awaitAll()
                }
            }.filterNotNull().toMap()
            _state.update { it.copy(states = it.states + fetched) }
        }
    }

    /** 单条重取状态，动作成功后用。 */
    fun refreshState(id: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.cronJobState(conn, id) } }
                .onSuccess { s -> _state.update { it.copy(states = it.states + (id to s)) } }
        }
    }

    /** 拉一次可投递目标。带 10 分钟内存缓存，编辑器反复打开不必每次重拉。 */
    fun loadTargets(force: Boolean = false) {
        val conn = connection() ?: return
        val now = System.currentTimeMillis()
        if (!force && targetsFetchedAt != 0L && now - targetsFetchedAt < TARGET_TTL_MS) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.cronDispatchTargets(conn) } }
                .onSuccess { result ->
                    targetsFetchedAt = System.currentTimeMillis()
                    // 投递目标只带 session_id，不带名称；名字要另外从会话列表取。
                    // 取不到时名称为空，界面退回显示 session_id，不拦保存。
                    val names = runCatching {
                        withContext(Dispatchers.IO) { api.chats(conn) }
                    }.getOrDefault(emptyList())
                        .mapNotNull { chat -> chat.sessionId.takeIf { it.isNotBlank() }?.let { it to chat.name } }
                        .toMap()
                    _state.update {
                        it.copy(
                            targets = result.items,
                            targetChannels = result.channels,
                            chatNames = names,
                        )
                    }
                }
        }
    }

    /** 执行记录。每次打开记录页都重拉，记录会随执行变化。 */
    fun loadHistory(id: String) {
        val conn = connection() ?: return
        _state.update { it.copy(historyLoading = true) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.cronJobHistory(conn, id) } }
                .onSuccess { records ->
                    _state.update {
                        it.copy(history = it.history + (id to records), historyLoading = false)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(historyLoading = false, rowErrors = it.rowErrors + (id to ErrorText.of(e))) }
                }
        }
    }

    /**
     * 打开编辑器。[job] 为 null 表示新建。
     *
     * 编辑时把原对象整份带上（含 `request` / `meta`），表单只替换已知键，
     * 避免整体替换时抹掉迁移审阅锁与模型覆盖。
     */
    fun edit(job: CronJobSpecDto?) {
        _state.update { it.copy(draft = job, error = null, rowErrors = emptyMap()) }
    }

    /** 新建或替换。`originalId` 为空表示新建；非空时按整体替换提交。 */
    fun save(spec: CronJobSpecDto, originalId: String?, onDone: (Boolean) -> Unit = {}) {
        val conn = connection() ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (originalId.isNullOrBlank()) {
                        api.createCronJob(conn, spec)
                    } else {
                        api.updateCronJob(conn, originalId, spec)
                    }
                }
            }.onSuccess { saved ->
                _state.update { s ->
                    val id = saved.id
                    val jobs = if (originalId.isNullOrBlank() || id == null) {
                        s.jobs
                    } else {
                        s.jobs.map { if (it.id == originalId) saved else it }
                    }
                    s.copy(jobs = jobs, saving = false)
                }
                refresh()
                onDone(true)
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = ErrorText.of(e)) }
                onDone(false)
            }
        }
    }

    /** 启用 / 暂停。整体替换原对象，`request` 与 `meta` 原样带回。 */
    fun setEnabled(id: String, enabled: Boolean) {
        val conn = connection() ?: return
        val spec = _state.value.jobs.firstOrNull { it.id == id } ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (enabled) api.resumeCronJob(conn, id) else api.pauseCronJob(conn, id)
                }
            }.onSuccess {
                _state.update { s ->
                    s.copy(
                        jobs = s.jobs.map { if (it.id == id) it.copy(enabled = enabled) else it },
                        rowErrors = s.rowErrors - id,
                    )
                }
                refreshState(id)
            }.onFailure { e -> _state.update { it.copy(rowErrors = it.rowErrors + (id to actionError(e))) } }
        }
    }

    /** 立即执行一次。 */
    fun runNow(id: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.runCronJob(conn, id) } }
                .onSuccess {
                    _state.update { it.copy(rowErrors = it.rowErrors - id) }
                    refreshState(id)
                }
                .onFailure { e -> _state.update { it.copy(rowErrors = it.rowErrors + (id to actionError(e))) } }
        }
    }

    /** 导入任务的审阅闸门。通过后任务仍是禁用状态，需要再启用。 */
    fun promote(id: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.promoteCronJob(conn, id) } }
                .onSuccess { saved ->
                    _state.update { s ->
                        s.copy(
                            jobs = s.jobs.map { if (it.id == id) saved else it },
                            rowErrors = s.rowErrors - id,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(rowErrors = it.rowErrors + (id to actionError(e))) } }
        }
    }

    /** 删除。服务端没有回收站，调用方必须先做二次确认。 */
    fun delete(id: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteCronJob(conn, id) } }
                .onSuccess {
                    _state.update { s ->
                        s.copy(
                            jobs = s.jobs.filterNot { it.id == id },
                            states = s.states - id,
                            rowErrors = s.rowErrors - id,
                            history = s.history - id,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(rowErrors = it.rowErrors + (id to actionError(e))) } }
        }
    }

    /**
     * 单条动作的错误文案。
     *
     * 409 有两种含义：导入任务未审核，与状态冲突（例如暂停已停止的任务）。
     * 前者要提示「需先确认」并露出审核入口，不能统一说成「操作失败」。
     */
    private fun actionError(e: Throwable): String {
        val failure = e as? ApiException
        if (failure?.code == 409 && failure.message.contains("promotion", ignoreCase = true)) {
            return "需先确认：该任务来自导入，审核通过后才能启用"
        }
        if (failure?.code == 409) return "任务状态已变化，请刷新后重试"
        return ErrorText.of(e)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissRowError(id: String) = _state.update { it.copy(rowErrors = it.rowErrors - id) }

    /** 换实例或换智能体时清空：任务归属上一个智能体，留着会误导。 */
    fun clear() {
        targetsFetchedAt = 0L
        _state.value = CronUiState()
    }

    private companion object {
        const val TARGET_TTL_MS = 10 * 60 * 1000L
    }
}