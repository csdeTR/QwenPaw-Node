package io.qwenpaw.node.ui.skills

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.BuiltinImportConflictDto
import io.qwenpaw.node.data.model.BuiltinNoticeDto
import io.qwenpaw.node.data.model.BuiltinSourceDto
import io.qwenpaw.node.data.model.PoolDownloadJobDto
import io.qwenpaw.node.data.model.PoolSkillDetailDto
import io.qwenpaw.node.data.model.PoolSkillDto
import io.qwenpaw.node.data.model.SkillWorkspaceDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.remote.SafZipSource
import io.qwenpaw.node.ui.common.formatJsonObject
import io.qwenpaw.node.ui.common.normalizeTags
import io.qwenpaw.node.ui.common.parseJsonObjectOrNull
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject

/** 池技能编辑器：新建与编辑共用；`renameFrom` 非空表示编辑态。 */
data class PoolEditState(
    val renameFrom: String? = null,
    val name: String = "",
    val content: String = "",
    val configText: String = "",
    /** 打开页面时的基线，用于判断是否有未保存修改。 */
    val savedName: String = "",
    val savedContent: String = "",
    val savedConfigText: String = "",
    val tags: List<String> = emptyList(),
    val tagDraft: String = "",
    val autoUpdate: Boolean = false,
    val autoSync: Boolean = false,
    val targets: Set<String> = emptySet(),
    val builtin: Boolean = false,
    val builtinLanguage: String = "",
    val availableLanguages: List<String> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val suggestedName: String? = null,
)

data class PoolDownloadUi(
    val skillName: String = "",
    val jobId: String? = null,
    val state: String = "",
    val error: String? = null,
    val targets: Set<String> = emptySet(),
    val allWorkspaces: Boolean = false,
    val overwrite: Boolean = false,
    val conflicts: List<String> = emptyList(),
)

data class SkillPoolUiState(
    val pool: List<PoolSkillDto> = emptyList(),
    val workspaces: List<SkillWorkspaceDto> = emptyList(),
    val notice: BuiltinNoticeDto? = null,
    val builtinSources: List<BuiltinSourceDto> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val editor: PoolEditState? = null,
    val builtinOpen: Boolean = false,
    val builtinSelection: Set<String> = emptySet(),
    /** 每个待导入内置技能各自的语言；缺省用它的 current_language 或 en。 */
    val builtinLanguages: Map<String, String> = emptyMap(),
    val builtinConflicts: List<BuiltinImportConflictDto> = emptyList(),
    val builtinSaving: Boolean = false,
    val distributeOpen: String? = null,
    val distributeTargets: Set<String> = emptySet(),
    val distributeAll: Boolean = false,
    val distributeOverwrite: Boolean = false,
    val distributeConflicts: List<String> = emptyList(),
    val download: PoolDownloadUi? = null,
    val batchSelecting: Boolean = false,
    val batchSelected: Set<String> = emptySet(),
    val uploading: Boolean = false,
    val zipImporting: Boolean = false,
    /** URL 导入（bundle_url）进行中。 */
    val urlImporting: Boolean = false,
    /** 旧的弹窗开关；导入改为独立页面后仍保留，便于宿主判断导入流程是否在进行。 */
    val uploadOpen: Boolean = false,
    val uploadWorkspace: String = "",
    /** 导入页第二步多选的技能；切换工作区与导入成功后清空，失败项保留。 */
    val uploadSkills: Set<String> = emptySet(),
    val uploadWorkspaceQuery: String = "",
    val uploadSkillQuery: String = "",
    val uploadOverwrite: Boolean = false,
    val reconciling: Boolean = false,
)

/** 导入页第一步：按搜索词过滤工作区，名称与 ID 都参与匹配。 */
internal fun SkillPoolUiState.filteredUploadWorkspaces(): List<SkillWorkspaceDto> =
    workspaces.filter { SkillSelection.matches(uploadWorkspaceQuery, it.agentName, it.agentId) }

/** 导入页第二步：所选工作区的技能，再按搜索词过滤；顺序沿用服务端返回。 */
internal fun SkillPoolUiState.uploadSkillCandidates(): List<String> =
    SkillSelection.filterNames(
        workspaces.firstOrNull { it.agentId == uploadWorkspace }?.skillNames.orEmpty(),
        uploadSkillQuery,
    )

/**
 * 技能池管理与自动化。
 *
 * 异步分发只有四态没有进度，轮询挂在 ViewModel，页面离开或清空时停止。
 */
class SkillPoolViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SkillPoolUiState())
    val state: StateFlow<SkillPoolUiState> = _state.asStateFlow()
    private var pollJob: Job? = null

    fun refresh() {
        val conn = connection() ?: run {
            _state.update {
                it.copy(loaded = true, loading = false, error = Texts.get(R.string.skills_error_not_connected))
            }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val pool = runCatching { withContext(Dispatchers.IO) { api.poolSkills(conn) } }
            val workspaces = runCatching { withContext(Dispatchers.IO) { api.skillWorkspaces(conn) } }
            val notice = runCatching { withContext(Dispatchers.IO) { api.poolBuiltinNotice(conn) } }
            _state.update { current ->
                current.copy(
                    pool = pool.getOrNull() ?: current.pool,
                    workspaces = workspaces.getOrNull() ?: current.workspaces,
                    notice = notice.getOrNull(),
                    loading = false,
                    loaded = true,
                    error = pool.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
        }
    }

    fun openBuiltinLibrary() {
        val conn = connection() ?: return
        _state.update { it.copy(builtinOpen = true, builtinSelection = emptySet(), builtinConflicts = emptyList()) }
        scope.launch {
            val sources = runCatching {
                withContext(Dispatchers.IO) { api.poolBuiltinSources(conn) }
            }
            _state.update {
                it.copy(
                    builtinSources = sources.getOrNull() ?: it.builtinSources,
                    error = sources.exceptionOrNull()?.let(ErrorText::of) ?: it.error,
                )
            }
        }
    }

    fun closeBuiltinLibrary() {
        _state.update { it.copy(builtinOpen = false, builtinSelection = emptySet(), builtinConflicts = emptyList()) }
    }

    fun toggleBuiltinSelection(name: String) {
        _state.update {
            val next = if (name in it.builtinSelection) it.builtinSelection - name else it.builtinSelection + name
            it.copy(builtinSelection = next)
        }
    }

    fun setBuiltinLanguage(name: String, language: String) {
        _state.update { it.copy(builtinLanguages = it.builtinLanguages + (name to language)) }
    }

    fun confirmBuiltinImport(overwrite: Boolean) {
        val conn = connection() ?: return
        val current = _state.value
        val imports = current.builtinSelection.map { name ->
            val source = current.builtinSources.firstOrNull { it.name == name }
            name to (
                current.builtinLanguages[name]
                    ?: source?.currentLanguage?.takeIf { it.isNotBlank() }
                    ?: "en"
                )
        }
        if (imports.isEmpty()) return
        _state.update { it.copy(builtinSaving = true, builtinConflicts = emptyList(), error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.importBuiltinSkills(conn, imports, overwrite) }
            }
            val conflicts = result.exceptionOrNull()?.let { builtinConflictsOf(it) }.orEmpty()
            if (conflicts.isNotEmpty() && !overwrite) {
                _state.update { it.copy(builtinSaving = false, builtinConflicts = conflicts) }
                return@launch
            }
            _state.update {
                it.copy(
                    builtinSaving = false,
                    builtinOpen = false,
                    builtinSelection = emptySet(),
                    builtinConflicts = emptyList(),
                    message = if (result.isSuccess) {
                        Texts.get(R.string.skill_pool_builtin_imported)
                    } else {
                        it.message
                    },
                    error = result.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
            if (result.isSuccess) refresh()
        }
    }

    fun updateBuiltin(name: String, language: String) {
        val conn = connection() ?: return
        _state.update { it.copy(error = null, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.updateBuiltinSkill(conn, name, language) }
            }
            _state.update {
                it.copy(
                    message = if (result.isSuccess) {
                        Texts.get(R.string.skill_pool_builtin_updated, name)
                    } else {
                        it.message
                    },
                    error = result.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
            if (result.isSuccess) refresh()
        }
    }

    fun beginCreate() {
        _state.update {
            it.copy(editor = PoolEditState())
        }
    }

    fun beginEdit(name: String) {
        val conn = connection() ?: return
        _state.update { it.copy(editor = PoolEditState(renameFrom = name, name = name, savedName = name, saving = false)) }
        scope.launch {
            val detail = runCatching {
                withContext(Dispatchers.IO) { api.poolSkill(conn, name) }
            }
            _state.update { current ->
                val d = detail.getOrNull()
                if (d == null) {
                    current.copy(editor = current.editor?.copy(error = ErrorText.of(detail.exceptionOrNull()!!)))
                } else {
                    current.copy(editor = editorStateOf(d))
                }
            }
        }
    }

    private fun editorStateOf(d: PoolSkillDetailDto): PoolEditState = PoolEditState(
        renameFrom = d.name,
        name = d.name,
        content = d.content,
        configText = formatJsonObject(d.config),
        savedName = d.name,
        savedContent = d.content,
        savedConfigText = formatJsonObject(d.config),
        tags = d.tags,
        autoUpdate = d.autoUpdate,
        autoSync = d.autoSync,
        targets = d.autoSyncTargets.orEmpty().toSet(),
        builtin = d.source == "builtin",
        builtinLanguage = d.builtinLanguage,
        availableLanguages = d.availableBuiltinLanguages,
    )

    fun setEditorName(value: String) {
        _state.update { it.copy(editor = it.editor?.copy(name = value, suggestedName = null)) }
    }

    fun setEditorContent(value: String) {
        _state.update { it.copy(editor = it.editor?.copy(content = value)) }
    }

    fun setEditorConfig(value: String) {
        _state.update { it.copy(editor = it.editor?.copy(configText = value)) }
    }

    fun setEditorTagDraft(value: String) {
        _state.update { it.copy(editor = it.editor?.copy(tagDraft = value)) }
    }

    fun commitEditorTags() {
        _state.update {
            it.copy(editor = it.editor?.copy(tags = (it.editor.tags + normalizeTags(it.editor.tagDraft)).distinct(), tagDraft = ""))
        }
    }

    fun removeEditorTag(tag: String) {
        _state.update { it.copy(editor = it.editor?.copy(tags = it.editor.tags - tag)) }
    }

    fun setEditorAutoUpdate(value: Boolean) {
        _state.update { it.copy(editor = it.editor?.copy(autoUpdate = value)) }
    }

    fun setEditorAutoSync(value: Boolean) {
        _state.update { it.copy(editor = it.editor?.copy(autoSync = value)) }
    }

    fun toggleEditorTarget(agentId: String) {
        _state.update {
            val editor = it.editor ?: return@update it
            val next = if (agentId in editor.targets) editor.targets - agentId else editor.targets + agentId
            it.copy(editor = editor.copy(targets = next))
        }
    }

    fun cancelEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun saveEditor(overwrite: Boolean, onDone: (Boolean) -> Unit = {}) {
        val conn = connection() ?: return
        val editor = _state.value.editor ?: return
        val name = editor.name.trim()
        if (name.isEmpty() || editor.content.isBlank()) return
        val config = parseJsonObjectOrNull(editor.configText)
        if (config == null) {
            _state.update { it.copy(editor = editor.copy(error = Texts.get(R.string.skills_config_invalid_json))) }
            return
        }
        _state.update { it.copy(editor = editor.copy(saving = true, error = null)) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val createdName = if (editor.renameFrom == null) {
                        api.createPoolSkill(conn, name, editor.content, config)
                    } else {
                        api.savePoolSkill(
                            conn,
                            name,
                            editor.content,
                            editor.renameFrom.takeIf { it != name },
                            config,
                            overwrite,
                        )
                    }
                    if (editor.tags.isNotEmpty()) {
                        api.setPoolSkillTags(conn, createdName, editor.tags)
                    }
                    if (editor.renameFrom != null && (editor.autoUpdate || editor.autoSync)) {
                        api.setPoolSkillAutomation(
                            conn,
                            createdName,
                            if (editor.builtin) editor.autoUpdate else null,
                            editor.autoSync,
                            if (editor.autoSync) editor.targets.toList() else null,
                        )
                    }
                    createdName
                }
            }
            val suggested = result.exceptionOrNull()?.let { suggestedNameOf(it) }
            if (suggested != null && !overwrite) {
                _state.update { it.copy(editor = it.editor?.copy(saving = false, suggestedName = suggested)) }
                return@launch
            }
            _state.update { current ->
                if (result.isSuccess) {
                    current.copy(
                        editor = null,
                        message = Texts.get(R.string.skill_pool_saved),
                        error = null,
                    )
                } else {
                    current.copy(
                        editor = current.editor?.copy(saving = false),
                        error = ErrorText.of(result.exceptionOrNull()!!),
                    )
                }
            }
            if (result.isSuccess) {
                refresh()
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    /** 导入技能 zip 到技能池。冲突由服务端 409 原文提示，不静默覆盖。 */
    /** URL 导入：服务端从 `bundle_url` 拉取技能包。 */
    fun importPoolSkillFromUrl(url: String) {
        val conn = connection() ?: return
        val trimmed = url.trim()
        if (trimmed.isEmpty() || _state.value.urlImporting) return
        _state.update { it.copy(urlImporting = true, error = null, message = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.importPoolSkill(conn, trimmed) }
            }.onSuccess {
                _state.update {
                    it.copy(
                        urlImporting = false,
                        message = Texts.get(R.string.skill_pool_url_imported),
                    )
                }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(urlImporting = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun importZip(source: SafZipSource) {        val conn = connection() ?: return
        if (_state.value.zipImporting) return
        _state.update { it.copy(zipImporting = true, error = null, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.uploadSkillZip(conn, toPool = true, source = source)
                }
            }
            source.cleanup()
            _state.update {
                it.copy(
                    zipImporting = false,
                    message = result.getOrNull()?.let { imported ->
                        Texts.get(R.string.skill_pool_zip_imported, imported.count)
                    } ?: it.message,
                    error = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
            if (result.isSuccess) refresh()
        }
    }

    fun deletePoolSkill(name: String) {
        val conn = connection() ?: return
        _state.update { it.copy(error = null, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.deletePoolSkill(conn, name) }
            }
            _state.update {
                it.copy(
                    message = if (result.isSuccess) Texts.get(R.string.skill_pool_deleted, name) else it.message,
                    error = result.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
            if (result.isSuccess) refresh()
        }
    }

    fun toggleBatchMode() {
        _state.update { it.copy(batchSelecting = !it.batchSelecting, batchSelected = emptySet()) }
    }

    fun toggleBatchSelected(name: String) {
        _state.update {
            val next = if (name in it.batchSelected) it.batchSelected - name else it.batchSelected + name
            it.copy(batchSelected = next)
        }
    }

    /** 全选当前池内全部技能。 */
    fun selectAllSkills() {
        _state.update {
            it.copy(batchSelected = SkillSelection.selectAll(it.pool.map { skill -> skill.name }))
        }
    }

    /** 反选：池内未选的选中、已选的取消；池内已不存在的历史选择被丢弃。 */
    fun invertSelection() {
        _state.update {
            it.copy(
                batchSelected = SkillSelection.invert(
                    it.batchSelected,
                    it.pool.map { skill -> skill.name },
                ),
            )
        }
    }

    /** 全不选。 */
    fun clearSelection() {
        _state.update { it.copy(batchSelected = SkillSelection.clear()) }
    }

    fun batchDelete() {
        val conn = connection() ?: return
        val names = _state.value.batchSelected.toList()
        if (names.isEmpty()) return
        _state.update { it.copy(error = null, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.batchDeletePoolSkills(conn, names) }
            }
            _state.update {
                it.copy(
                    batchSelecting = false,
                    batchSelected = emptySet(),
                    message = if (result.isSuccess) {
                        Texts.get(R.string.skill_pool_batch_deleted, names.size)
                    } else {
                        it.message
                    },
                    error = result.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
            if (result.isSuccess) refresh()
        }
    }

    fun openDistribute(name: String) {
        _state.update {
            it.copy(
                distributeOpen = name,
                distributeTargets = emptySet(),
                distributeAll = false,
                distributeOverwrite = false,
                distributeConflicts = emptyList(),
                download = null,
            )
        }
        if (_state.value.workspaces.isEmpty()) refresh()
    }

    fun closeDistribute() {
        _state.update { it.copy(distributeOpen = null, distributeConflicts = emptyList()) }
    }

    fun toggleDistributeTarget(agentId: String) {
        _state.update {
            val next = if (agentId in it.distributeTargets) it.distributeTargets - agentId else it.distributeTargets + agentId
            it.copy(distributeTargets = next)
        }
    }

    fun setDistributeAll(value: Boolean) {
        _state.update { it.copy(distributeAll = value) }
    }

    fun setDistributeOverwrite(value: Boolean) {
        _state.update { it.copy(distributeOverwrite = value) }
    }

    fun startDistribute() {
        val conn = connection() ?: return
        val current = _state.value
        val skillName = current.distributeOpen ?: return
        val targets = current.distributeTargets.toList()
        if (targets.isEmpty() && !current.distributeAll) return
        _state.update { it.copy(distributeConflicts = emptyList(), error = null) }
        scope.launch {
            val preview = runCatching {
                withContext(Dispatchers.IO) {
                    api.previewPoolDownload(conn, skillName, targets, current.distributeAll)
                }
            }
            val conflicts = preview.getOrNull()?.let { distributeConflictsOf(it) }.orEmpty()
            if (conflicts.isNotEmpty() && !current.distributeOverwrite) {
                _state.update { it.copy(distributeConflicts = conflicts) }
                return@launch
            }
            launchDownload(conn, skillName, targets, current.distributeAll, current.distributeOverwrite)
        }
    }

    private fun launchDownload(
        conn: Connection,
        skillName: String,
        targets: List<String>,
        all: Boolean,
        overwrite: Boolean,
    ) {
        _state.update {
            it.copy(download = PoolDownloadUi(skillName = skillName, state = "running", targets = targets.toSet(), allWorkspaces = all, overwrite = overwrite))
        }
        scope.launch {
            val started = runCatching {
                withContext(Dispatchers.IO) {
                    api.startPoolDownload(conn, skillName, targets, all, overwrite)
                }
            }
            val job = started.getOrNull()
            if (job == null) {
                _state.update {
                    it.copy(
                        download = null,
                        error = started.exceptionOrNull()?.let { ErrorText.of(it) },
                    )
                }
                return@launch
            }
            _state.update { it.copy(download = it.download?.copy(jobId = job.jobId, state = job.state)) }
            pollDownload(conn, job.jobId)
        }
    }

    private fun pollDownload(conn: Connection, jobId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var backoffMs = 1_000L
            while (true) {
                delay(backoffMs)
                val result = runCatching {
                    withContext(Dispatchers.IO) { api.poolDownloadStatus(conn, jobId) }
                }
                val job = result.getOrNull()
                val terminal = job?.state in setOf("completed", "failed", "cancelled")
                _state.update { current ->
                    current.copy(
                        download = job?.let {
                            PoolDownloadUi(
                                skillName = current.download?.skillName ?: "",
                                jobId = job.jobId,
                                state = job.state,
                                error = job.error,
                            )
                        } ?: current.download,
                    )
                }
                if (result.isFailure || terminal) break
                backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
            }
            val done = _state.value.download
            if (done != null && done.state in setOf("completed", "failed", "cancelled")) {
                _state.update {
                    it.copy(
                        message = when (done.state) {
                            "completed" -> Texts.get(R.string.skill_pool_download_done, done.skillName)
                            "failed" -> done.error ?: Texts.get(R.string.skill_pool_download_failed)
                            else -> Texts.get(R.string.skill_pool_download_cancelled)
                        },
                        download = null,
                    )
                }
                refresh()
            }
        }
    }

    fun cancelDownload() {
        val conn = connection() ?: return
        val jobId = _state.value.download?.jobId ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.cancelPoolDownload(conn, jobId) } }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    /** 进入「从工作区导入」：清空上一次的工作区、选择与搜索词，入口由此固定落在第一步。 */
    fun openUpload() {
        _state.update {
            it.copy(
                uploadOpen = true,
                uploadWorkspace = "",
                uploadSkills = emptySet(),
                uploadWorkspaceQuery = "",
                uploadSkillQuery = "",
                uploadOverwrite = false,
                error = null,
                message = null,
            )
        }
        if (_state.value.workspaces.isEmpty()) refresh()
    }

    fun closeUpload() {
        _state.update {
            it.copy(
                uploadOpen = false,
                uploadWorkspace = "",
                uploadSkills = emptySet(),
                uploadWorkspaceQuery = "",
                uploadSkillQuery = "",
                uploadOverwrite = false,
            )
        }
    }

    /** 选中工作区时清空技能选择与搜索词，避免把上一个工作区的技能名带进来。 */
    fun setUploadWorkspace(agentId: String) {
        _state.update {
            it.copy(uploadWorkspace = agentId, uploadSkills = emptySet(), uploadSkillQuery = "")
        }
    }

    fun setUploadWorkspaceQuery(value: String) {
        _state.update { it.copy(uploadWorkspaceQuery = value) }
    }

    fun setUploadSkillQuery(value: String) {
        _state.update { it.copy(uploadSkillQuery = value) }
    }

    fun toggleUploadSkill(skillName: String) {
        _state.update {
            val next = if (skillName in it.uploadSkills) {
                it.uploadSkills - skillName
            } else {
                it.uploadSkills + skillName
            }
            it.copy(uploadSkills = next)
        }
    }

    /** 旧的单选入口：清空后只选中这一项。 */
    fun setUploadSkill(skillName: String) {
        _state.update {
            it.copy(uploadSkills = if (skillName.isBlank()) emptySet() else setOf(skillName))
        }
    }

    /** 全选当前可见（已按搜索词过滤）的技能。 */
    fun selectAllUploadSkills() {
        _state.update {
            it.copy(uploadSkills = SkillSelection.selectAll(it.uploadSkillCandidates()))
        }
    }

    fun clearUploadSkills() {
        _state.update { it.copy(uploadSkills = SkillSelection.clear()) }
    }

    fun setUploadOverwrite(value: Boolean) {
        _state.update { it.copy(uploadOverwrite = value) }
    }

    /**
     * 逐个上传所选技能。
     *
     * `POST /api/skills/pool/upload` 一次只接受一个技能名，因此按工作区清单的顺序串行调用。
     * 全部成功时清空选择并回调 [onImported]；部分失败时失败项留在选中集里便于重试，
     * 由宿主决定是否停留在导入页。
     */
    fun confirmUpload(onImported: () -> Unit = {}) {
        val conn = connection() ?: return
        val current = _state.value
        val workspace = current.uploadWorkspace
        val ordered = current.workspaces
            .firstOrNull { it.agentId == workspace }
            ?.skillNames
            ?.filter { it in current.uploadSkills }
            .orEmpty()
        val names = ordered.ifEmpty { current.uploadSkills.toList() }
        if (workspace.isBlank() || names.isEmpty() || current.uploading) return
        _state.update { it.copy(uploading = true, error = null, message = null) }
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                names.map { name ->
                    name to runCatching {
                        api.uploadPoolSkill(conn, workspace, name, current.uploadOverwrite)
                    }
                }
            }
            val succeeded = results.filter { it.second.isSuccess }.map { it.first }
            val failed = results.filter { it.second.isFailure }.map { it.first }
            val message = when {
                succeeded.isEmpty() -> _state.value.message
                succeeded.size == 1 -> Texts.get(R.string.skill_pool_upload_done, succeeded.first())
                else -> Texts.get(R.string.skill_pool_upload_done_many, succeeded.size)
            }
            _state.update {
                it.copy(
                    uploading = false,
                    uploadSkills = failed.toSet(),
                    message = message,
                    error = results.firstNotNullOfOrNull { pair -> pair.second.exceptionOrNull() }
                        ?.let(ErrorText::of),
                )
            }
            if (failed.isEmpty()) {
                refresh()
                onImported()
            } else if (succeeded.isNotEmpty()) {
                refresh()
            }
        }
    }

    /** `POST /api/skills/pool/refresh` 强制重建池内条目，返回最新列表。 */
    fun reconcile() {
        val conn = connection() ?: return
        _state.update { it.copy(reconciling = true, error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.refreshPoolSkills(conn) }
            }
            _state.update {
                it.copy(
                    pool = result.getOrNull() ?: it.pool,
                    reconciling = false,
                    message = if (result.isSuccess) Texts.get(R.string.skill_pool_reconciled) else it.message,
                    error = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
        }
    }

    fun clear() {
        pollJob?.cancel()
        _state.value = SkillPoolUiState()
    }

    private fun builtinConflictsOf(e: Throwable): List<BuiltinImportConflictDto> {
        val ex = e as? ApiException ?: return emptyList()
        val detail = ex.detail ?: return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(detail).jsonObject
            val conflicts = root["conflicts"] ?: return@runCatching emptyList()
            Json.decodeFromString(
                ListSerializer(BuiltinImportConflictDto.serializer()),
                conflicts.toString(),
            )
        }.getOrDefault(emptyList())
    }

    private fun distributeConflictsOf(body: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            val detail = root["detail"] as? JsonObject ?: root
            val conflicts = detail["conflicts"] ?: return@runCatching emptyList()
            if (conflicts is JsonArray) {
                conflicts.map { conflict ->
                    val obj = conflict as? JsonObject
                    val name = obj?.get("workspace_name")?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: obj?.get("workspace_id")?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: conflict.toString()
                    name
                }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun suggestedNameOf(e: Throwable): String? {
        val ex = e as? ApiException ?: return null
        return runCatching {
            Json.parseToJsonElement(ex.detail.orEmpty()).jsonObject["suggested_name"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        }.getOrNull()
    }
}
