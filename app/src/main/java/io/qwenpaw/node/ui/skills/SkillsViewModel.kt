package io.qwenpaw.node.ui.skills

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.model.BroadcastConflictDto
import io.qwenpaw.node.data.model.SkillImportConflictDto
import io.qwenpaw.node.data.model.SkillImportResultDto
import io.qwenpaw.node.data.model.PoolSkillDetailDto
import io.qwenpaw.node.data.model.PoolSkillDto
import io.qwenpaw.node.data.model.SkillDetailDto
import io.qwenpaw.node.data.model.SkillDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.remote.SafZipSource
import io.qwenpaw.node.ui.i18n.Texts
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 技能编辑态。
 *
 * `savedText` 是打开编辑器那一刻的正文，`draft` 是当前输入，两者之差就是「脏」。
 * 技能没有版本号，因此不存在工作区那种 `etag` 冲突，只需要判断是否同于已保存文本。
 */
data class SkillEditState(
    val name: String,
    val draft: String,
    val savedText: String,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val optimizing: Boolean = false,
    val error: String? = null,
) {
    val dirty: Boolean get() = draft != savedText
}

/**
 * 技能页状态。
 *
 * 工作区技能与技能池是两段互不影响的数据：池拉失败只让池那一段显示错误，
 * 不把整页打成错误态——用户打开这一页多半是为了开关工作区技能。
 */
data class SkillsUiState(
    val skills: List<SkillDto> = emptyList(),
    val pool: List<PoolSkillDto> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** 列表整页错误（工作区技能拉取失败）。 */
    val error: String? = null,
    /** 技能池单独的错误，与 [error] 分开显示在池那一段。 */
    val poolError: String? = null,
    val detail: SkillDetailDto? = null,
    val poolDetail: PoolSkillDetailDto? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val edit: SkillEditState? = null,
    /** 单行动作失败：启停、删除。键是技能名。 */
    val rowErrors: Map<String, String> = emptyMap(),
    /** 一次性提示，例如「已启用」或「扫描拦截」。 */
    val notice: String? = null,
    val importing: Boolean = false,
    val importConflicts: List<SkillImportConflictDto> = emptyList(),
    val broadcast: BroadcastUi? = null,
    val agentOptions: List<AgentDto> = emptyList(),
    /** 批量操作对话框正在执行。 */
    val batchBusy: Boolean = false,
)

/** 技能池广播的状态。冲突时保留选择，便于用户改选或覆盖重试。 */
data class BroadcastUi(
    val skillName: String,
    val targets: Set<String> = emptySet(),
    val running: Boolean = false,
    val conflicts: List<BroadcastConflictDto> = emptyList(),
    val error: String? = null,
)

/**
 * 技能：列表、启停、正文编辑、新建与删除。
 *
 * 技能池只读。池内技能没有 `enabled` 字段，开关的对象是「某工作区里的某个技能」，
 * 属于把技能分发到工作区，本版留 Web。
 */
class SkillsViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    private var pendingImportSource: SafZipSource? = null
    private var pendingImportToPool = false
    private var optimizeJob: Job? = null

    /** 拉列表。两个请求并行，池失败不影响主体。 */
    fun refresh() {
        val conn = connection() ?: run {
            _state.update {
                it.copy(
                    loaded = true,
                    loading = false,
                    error = Texts.get(R.string.skills_error_not_connected),
                )
            }
            return
        }
        _state.update { it.copy(loading = true, error = null, poolError = null) }
        scope.launch {
            val skillsJob = async {
                runCatching { withContext(Dispatchers.IO) { api.skills(conn) } }
            }
            val poolJob = async {
                runCatching { withContext(Dispatchers.IO) { api.poolSkills(conn) } }
            }
            val skills = skillsJob.await()
            val pool = poolJob.await()
            _state.update { current ->
                current.copy(
                    skills = skills.getOrNull() ?: current.skills,
                    pool = pool.getOrNull() ?: current.pool,
                    loading = false,
                    loaded = true,
                    error = skills.exceptionOrNull()?.let { ErrorText.of(it) },
                    poolError = pool.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
        }
    }

    fun openSkill(name: String) {
        val conn = connection() ?: return
        _state.update {
            it.copy(detail = null, poolDetail = null, edit = null, detailLoading = true, detailError = null)
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.skill(conn, name) } }
                .onSuccess { detail ->
                    _state.update { it.copy(detail = detail, detailLoading = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(detailLoading = false, detailError = ErrorText.of(e)) }
                }
        }
    }

    fun openPoolSkill(name: String) {
        val conn = connection() ?: return
        _state.update {
            it.copy(detail = null, poolDetail = null, edit = null, detailLoading = true, detailError = null)
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.poolSkill(conn, name) } }
                .onSuccess { detail ->
                    _state.update { it.copy(poolDetail = detail, detailLoading = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(detailLoading = false, detailError = ErrorText.of(e)) }
                }
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null, poolDetail = null, edit = null, detailError = null) }
    }

    /**
     * 启用或停用。
     *
     * 端点没有请求体、也没有目标状态，只有「切换」；因此按用户意图选端点，
     * 结果一律以列表重拉为准，不本地翻转——本地翻转后若服务端因为扫描拦截而没有生效，
     * 界面会显示一个不存在的状态。
     */
    fun setEnabled(name: String, enabled: Boolean) {
        val conn = connection() ?: return
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    if (enabled) api.enableSkill(conn, name) else api.disableSkill(conn, name)
                }
            }
            outcome
                .onSuccess {
                    clearRowError(name)
                    _state.update {
                        it.copy(skills = it.skills.map { s ->
                            if (s.name == name) s.copy(enabled = enabled) else s
                        })
                    }
                    // 详情页的开关也要跟着走。
                    _state.update { current ->
                        val detail = current.detail
                        if (detail != null && detail.name == name) {
                            current.copy(detail = detail.copy(enabled = enabled))
                        } else {
                            current
                        }
                    }
                }
                .onFailure { e -> setRowError(name, enableFailureText(e)) }
        }
    }

    fun beginEdit() {
        val detail = _state.value.detail ?: return
        _state.update {
            it.copy(
                edit = SkillEditState(
                    name = detail.name,
                    draft = detail.content,
                    savedText = detail.content,
                ),
            )
        }
    }

    fun updateDraft(text: String) {
        _state.update { current ->
            current.edit?.let { current.copy(edit = it.copy(draft = text, saved = false)) } ?: current
        }
    }

    /** AI 优化当前草稿：结果流式追加到草稿，不自动保存。 */
    fun optimizeEdit() {
        val conn = connection() ?: return
        val edit = _state.value.edit ?: return
        if (optimizeJob?.isActive == true || edit.saving) return
        val base = edit.draft
        _state.update { state ->
            state.edit?.let { state.copy(edit = it.copy(optimizing = true, error = null)) } ?: state
        }
        optimizeJob = scope.launch {
            val builder = StringBuilder()
            var failure: String? = null
            try {
                api.optimizeSkillFrames(conn, base).collect { frame ->
                    val obj = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(frame).jsonObject
                    }.getOrNull() ?: return@collect
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull
                    if (!error.isNullOrBlank()) {
                        failure = error
                        return@collect
                    }
                    val delta = obj["text"]?.jsonPrimitive?.contentOrNull ?: return@collect
                    builder.append(delta)
                    _state.update { state ->
                        state.edit?.let {
                            state.copy(edit = it.copy(draft = base + builder.toString()))
                        } ?: state
                    }
                }
            } finally {
                _state.update { state ->
                    state.edit?.let {
                        val message = failure
                            ?: if (builder.isEmpty()) {
                                Texts.get(R.string.skills_ai_empty)
                            } else {
                                it.error
                            }
                        state.copy(edit = it.copy(optimizing = false, error = message))
                    } ?: state
                }
            }
        }
    }

    fun saveEdit() {
        optimizeJob?.cancel()
        val edit = _state.value.edit ?: return
        if (!edit.dirty || edit.saving) return
        val conn = connection() ?: return
        _state.update { it.copy(edit = edit.copy(saving = true, error = null)) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.saveSkill(conn, edit.name, edit.draft) } }
                .onSuccess { result ->
                    _state.update { current ->
                        val next = current.edit ?: return@update current
                        current.copy(
                            edit = next.copy(
                                saving = false,
                                saved = true,
                                savedText = next.draft,
                            ),
                            // 详情页立刻反映新正文，用户返回时看到的就是刚保存的内容。
                            detail = current.detail?.takeIf { it.name == edit.name }?.copy(
                                content = next.draft,
                            ) ?: current.detail,
                            notice = if (result.mode == "noop") {
                                Texts.get(R.string.skills_notice_noop)
                            } else {
                                Texts.get(R.string.skills_notice_saved)
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { current ->
                        val next = current.edit ?: return@update current
                        current.copy(edit = next.copy(saving = false, error = editFailureText(e)))
                    }
                }
        }
    }

    fun cancelEdit() {
        optimizeJob?.cancel()
        _state.update { it.copy(edit = null) }
    }

    /**
     * 新建技能。
     *
     * 重名时服务端回 409 且 `detail` 是对象，里面带一个 `suggested_name`
     * （带时间戳后缀）。把它原样给用户，比让用户自己再想一个名字有用。
     */
    fun create(name: String, content: String, onResult: (Boolean) -> Unit) {
        val conn = connection() ?: run { onResult(false); return }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.createSkill(conn, name, content) } }
                .onSuccess { created ->
                    _state.update {
                        it.copy(notice = Texts.get(R.string.skills_notice_created, created))
                    }
                    refresh()
                    onResult(true)
                }
                .onFailure { e ->
                    val suggested = (e as? ApiException)?.let { api.skillConflictOf(it) }
                    val text = if (suggested != null) {
                        Texts.get(R.string.skills_notice_conflict, suggested.suggestedName)
                    } else {
                        creationFailureText(e)
                    }
                    _state.update { it.copy(notice = text) }
                    onResult(false)
                }
        }
    }

    fun delete(name: String, onResult: (Boolean) -> Unit) {
        val conn = connection() ?: run { onResult(false); return }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteSkill(conn, name) } }
                .onSuccess {
                    clearRowError(name)
                    _state.update { current ->
                        current.copy(
                            skills = current.skills.filter { it.name != name },
                            detail = current.detail?.takeIf { it.name != name },
                            edit = current.edit?.takeIf { it.name != name },
                            notice = Texts.get(R.string.skills_notice_deleted, name),
                        )
                    }
                    onResult(true)
                }
                .onFailure { e ->
                    setRowError(name, deleteFailureText(e))
                    onResult(false)
                }
        }
    }

    fun batchEnable(names: List<String>) = batchAction(names, "batch-enable")

    fun batchDisable(names: List<String>) = batchAction(names, "batch-disable")

    fun batchDelete(names: List<String>) = batchAction(names, "batch-delete")

    private fun batchAction(names: List<String>, action: String) {
        val conn = connection() ?: return
        val selected = names.filter { it.isNotBlank() }.distinct()
        if (selected.isEmpty() || _state.value.batchBusy) return
        _state.update { it.copy(batchBusy = true, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (action) {
                        "batch-enable" -> api.batchEnableSkills(conn, selected)
                        "batch-disable" -> api.batchDisableSkills(conn, selected)
                        else -> api.batchDeleteSkills(conn, selected)
                    }
                }
            }.onSuccess { result ->
                val failed = result.results.filterValues { !it.success }
                _state.update { current ->
                    current.copy(
                        batchBusy = false,
                        notice = if (failed.isEmpty()) {
                            Texts.get(R.string.skills_batch_done, result.results.size)
                        } else {
                            Texts.get(R.string.skills_batch_partial, failed.size)
                        },
                        rowErrors = failed.mapValues { (_, value) ->
                            value.reason ?: Texts.get(R.string.skills_batch_failed)
                        },
                    )
                }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(batchBusy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun dismissRowError(name: String) = clearRowError(name)

    /** 换实例或换智能体后清空，下次进入重新拉取。 */
    // ---------- zip 导入 ----------

    fun importZip(toPool: Boolean, source: SafZipSource) {
        val conn = connection() ?: return
        pendingImportSource = source
        pendingImportToPool = toPool
        _state.update { it.copy(importing = true, importConflicts = emptyList(), notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.uploadSkillZip(conn, toPool, source)
                }
            }.onSuccess { result ->
                if (result.conflicts.isNotEmpty()) {
                    _state.update {
                        it.copy(importing = false, importConflicts = result.conflicts)
                    }
                } else {
                    source.cleanup()
                    pendingImportSource = null
                    _state.update {
                        it.copy(
                            importing = false,
                            notice = if (result.imported.isEmpty()) {
                                Texts.get(R.string.skills_notice_import_none)
                            } else {
                                Texts.get(
                                    R.string.skills_notice_imported,
                                    result.imported.joinToString(),
                                )
                            },
                        )
                    }
                    refresh()
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        importing = false,
                        notice = Texts.get(R.string.skills_notice_import_failed, ErrorText.of(e)),
                    )
                }
            }
        }
    }

    /** 按冲突项的建议名或用户输入改名后重试。 */
    fun retryImport(renameMap: Map<String, String>) {
        val conn = connection() ?: return
        val source = pendingImportSource ?: return
        _state.update { it.copy(importing = true, importConflicts = emptyList()) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.uploadSkillZip(
                        conn = conn,
                        toPool = pendingImportToPool,
                        source = source,
                        renameMap = renameMap,
                    )
                }
            }.onSuccess { result ->
                if (result.conflicts.isNotEmpty()) {
                    _state.update {
                        it.copy(importing = false, importConflicts = result.conflicts)
                    }
                } else {
                    source.cleanup()
                    pendingImportSource = null
                    _state.update {
                        it.copy(
                            importing = false,
                            notice = Texts.get(
                                R.string.skills_notice_imported,
                                result.imported.joinToString(),
                            ),
                        )
                    }
                    refresh()
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        importing = false,
                        notice = Texts.get(R.string.skills_notice_import_failed, ErrorText.of(e)),
                    )
                }
            }
        }
    }

    fun dismissImportConflicts() {
        pendingImportSource?.cleanup()
        pendingImportSource = null
        _state.update { it.copy(importConflicts = emptyList(), importing = false) }
    }

    // ---------- 广播 ----------

    fun loadAgentOptions() {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.agents(conn) } }
                .onSuccess { list -> _state.update { it.copy(agentOptions = list) } }
        }
    }

    fun openBroadcast(skillName: String) {
        _state.update {
            it.copy(broadcast = BroadcastUi(skillName = skillName))
        }
        loadAgentOptions()
    }

    fun toggleBroadcastTarget(agentId: String) {
        _state.update { state ->
            val current = state.broadcast ?: return@update state
            val targets = if (agentId in current.targets) {
                current.targets - agentId
            } else {
                current.targets + agentId
            }
            state.copy(broadcast = current.copy(targets = targets, conflicts = emptyList(), error = null))
        }
    }

    fun runBroadcast(overwrite: Boolean = false) {
        val conn = connection() ?: return
        val current = _state.value.broadcast ?: return
        if (current.targets.isEmpty()) {
            _state.update {
                it.copy(broadcast = current.copy(error = Texts.get(R.string.skills_error_no_target)))
            }
            return
        }
        _state.update { it.copy(broadcast = current.copy(running = true, error = null)) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.broadcastSkill(
                        conn = conn,
                        skillName = current.skillName,
                        targets = current.targets.toList(),
                        overwrite = overwrite,
                    )
                }
            }.onSuccess { result ->
                if (result.conflicts.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            broadcast = it.broadcast?.copy(
                                running = false,
                                conflicts = result.conflicts,
                            ),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            broadcast = null,
                            notice = Texts.quantity(
                                R.plurals.skills_notice_broadcast,
                                result.downloaded.size,
                                result.downloaded.size,
                            ),
                        )
                    }
                    refresh()
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        broadcast = it.broadcast?.copy(
                            running = false,
                            error = ErrorText.of(e),
                        ),
                    )
                }
            }
        }
    }

    fun dismissBroadcast() {
        _state.update { it.copy(broadcast = null) }
    }

    fun clear() {
        pendingImportSource?.cleanup()
        pendingImportSource = null
        _state.value = SkillsUiState()
    }

    private fun setRowError(name: String, text: String) {
        _state.update { it.copy(rowErrors = it.rowErrors + (name to text)) }
    }

    private fun clearRowError(name: String) {
        _state.update { it.copy(rowErrors = it.rowErrors - name) }
    }

    /**
     * 启停失败的文案。
     *
     * 422 不是普通校验错误，是技能安全扫描没通过；服务端 body 是结构化对象，
     * `detail` 已被 [QwenPawApi] 取成字符串，这里补一句人话再带上原文。
     */
    private fun enableFailureText(e: Throwable): String {
        val ex = e as? ApiException ?: return ErrorText.of(e)
        return when (ex.code) {
            422 -> SkillCopy.scanBlockedText(ex.detail ?: ex.message, severityOf(ex.detail))
            else -> ErrorText.of(e)
        }
    }

    private fun editFailureText(e: Throwable): String {
        val ex = e as? ApiException ?: return ErrorText.of(e)
        return when (ex.code) {
            422 -> SkillCopy.scanBlockedText(ex.detail ?: ex.message, severityOf(ex.detail))
            400 -> ex.detail ?: Texts.get(R.string.skills_error_body_invalid)
            else -> ErrorText.of(e)
        }
    }

    private fun creationFailureText(e: Throwable): String {
        val ex = e as? ApiException ?: return ErrorText.of(e)
        return when (ex.code) {
            422 -> SkillCopy.scanBlockedText(ex.detail ?: ex.message, severityOf(ex.detail))
            400 -> ex.detail ?: Texts.get(R.string.skills_error_body_invalid)
            else -> ErrorText.of(e)
        }
    }

    private fun deleteFailureText(e: Throwable): String {
        val ex = e as? ApiException ?: return ErrorText.of(e)
        return when (ex.code) {
            409 -> Texts.get(R.string.skills_error_delete_conflict)
            else -> ErrorText.of(e)
        }
    }

    /**
     * 从 422 的 detail 里挑出最高等级。
     *
     * `extractDetail` 只保留了 `detail` 字段（一句英文），`max_severity` 在 body 顶层
     * 已经丢了；这里不做二次请求，能拿到的只有那句话本身。等级留空时
     * [SkillCopy.scanBlockedText] 用不带等级的说法。
     */
    private fun severityOf(detail: String?): String? {
        val text = detail?.lowercase() ?: return null
        return listOf("critical", "high", "medium", "low").firstOrNull { it in text }
    }
}