package io.qwenpaw.node.ui.localmodels

import io.qwenpaw.node.R
import io.qwenpaw.node.data.localmodel.isActiveDownload
import io.qwenpaw.node.data.model.LocalModelConfigDto
import io.qwenpaw.node.data.model.LocalModelDto
import io.qwenpaw.node.data.model.LocalModelProgressDto
import io.qwenpaw.node.data.model.LocalModelServerDto
import io.qwenpaw.node.data.model.LocalModelServerUpdateDto
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
    /** 配置草稿；`null` 表示尚未读取或读取失败。 */
    val configDraft: LocalModelConfigDraft? = null,
    val configLoading: Boolean = false,
    val savingConfig: Boolean = false,
    /** 按字段折叠后的校验错误，每个字段只显示第一条。 */
    val configFieldErrors: Map<LocalModelConfigField, LocalModelConfigError> = emptyMap(),
    val configError: String? = null,
    /** 运行时启停。与下载状态分开，避免一边启停一边把下载按钮也锁住。 */
    val runtimeBusy: Boolean = false,
    val runtimeError: String? = null,
    /** 正在删除的模型标识；`null` 表示没有删除请求在进行。 */
    val deletingModelId: String? = null,
    val deleteError: String? = null,
    /** 更新检查结果；`null` 表示本次进入页面后尚未检查。 */
    val update: LocalModelServerUpdateDto? = null,
    val checkingUpdate: Boolean = false,
    val updateError: String? = null,
) {
    /** 当前活动中的下载：运行时优先，其次是模型。 */
    val activeSource: DownloadSource?
        get() = when {
            isActiveDownload(serverProgress?.status.orEmpty()) -> DownloadSource.SERVER
            isActiveDownload(modelProgress?.status.orEmpty()) -> DownloadSource.MODEL
            else -> null
        }

    /** 已下载、可以用于启动运行时的模型。 */
    val downloadedModels: List<LocalModelDto>
        get() = models.filter { it.downloaded }
}

enum class DownloadSource { SERVER, MODEL }

/**
 * 本地模型页的数据与轮询。
 *
 * 服务端的下载是「立即返回 + 轮询进度」，且没有 SSE。轮询只在一个任务处于
 * `pending/downloading/canceling` 时进行；任务结束后刷新一次列表与运行时状态，
 * 让「已下载」标记与 `installed` 跟上。
 *
 * 0.7.3 起本页还负责配置、删除模型、运行时启停与更新检查。三类错误各自独立存放：
 * 配置保存失败不影响下载进度，启停失败也不该把配置卡打成错误。
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
                maybeLoadConfig()
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
                        it.copy(
                            actionBusy = false,
                            // 重新下载运行时同时兑现了「更新」：旧标记留着会让按钮停在可点状态。
                            update = null,
                            updateError = null,
                            messageRes = R.string.local_models_install_started,
                        )
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

    // ---------- 配置 ----------

    /**
     * 读取配置并生成草稿。
     *
     * 默认不覆盖有未保存改动的草稿：下载结束后的自动刷新也会走到这里，
     * 若无条件覆盖，用户填了一半的端口会被悄悄改回原值。[force] 只给保存后的回读用。
     */
    fun loadConfig(force: Boolean = false) {
        val current = _state.value
        if (current.configLoading) return
        if (!force && current.configDraft?.isDirty == true) return
        val conn = connection() ?: return
        _state.update { it.copy(configLoading = true, configError = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.localModelConfig(conn) } }
                .onSuccess { config ->
                    _state.update {
                        it.copy(
                            configDraft = LocalModelAdminEdit.from(config),
                            configLoading = false,
                            configError = null,
                            configFieldErrors = emptyMap(),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            configLoading = false,
                            configError = adminError(
                                e,
                                conflictRes = R.string.local_models_admin_error_conflict_config,
                                unsupportedRes = R.string.local_models_admin_error_unsupported_config,
                            ),
                        )
                    }
                }
        }
    }

    /** 页面首次加载时补一次配置读取；读失败后下次刷新会再试。 */
    private fun maybeLoadConfig() {
        val current = _state.value
        if (current.configDraft == null && !current.configLoading) loadConfig()
    }

    /** 输入变化。只清掉被改动字段的旧错误，其余字段的错误留到下次保存重算。 */
    fun updateConfigDraft(draft: LocalModelConfigDraft) {
        _state.update { current ->
            val previous = current.configDraft
            val edited = buildSet {
                if (previous == null || draft.contextText != previous.contextText) {
                    add(LocalModelConfigField.CONTEXT)
                }
                if (previous == null || draft.portText != previous.portText) {
                    add(LocalModelConfigField.PORT)
                }
                if (previous == null || draft.kwargsText != previous.kwargsText) {
                    add(LocalModelConfigField.KWARGS)
                }
            }
            current.copy(
                configDraft = draft,
                configError = null,
                configFieldErrors = current.configFieldErrors - edited,
            )
        }
    }

    /**
     * 保存配置。
     *
     * 先校验再比对：校验通过后才计算差异，没有差异就只提示不发文，
     * 避免一次空 `PUT` 把服务端的更新时间之类字段也改掉。提交成功后回读一次，
     * 用户看到的是服务端归一化之后的值，而不是自己刚敲进去的字符串。
     */
    fun saveConfig() {
        val draft = _state.value.configDraft ?: return
        if (_state.value.savingConfig) return
        val errors = LocalModelAdminEdit.validate(draft)
        if (errors.isNotEmpty()) {
            _state.update {
                it.copy(
                    configFieldErrors = errors.associateBy { error -> error.field },
                    configError = null,
                )
            }
            return
        }
        val changes = LocalModelAdminEdit.changesOf(draft)
        if (changes.isEmpty) {
            _state.update { it.copy(messageRes = R.string.local_models_admin_notice_config_unchanged) }
            return
        }
        val conn = connection() ?: run {
            _state.update {
                it.copy(configError = Texts.get(R.string.local_models_admin_error_not_connected))
            }
            return
        }
        _state.update { it.copy(savingConfig = true, configError = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.setLocalModelConfig(
                        conn,
                        LocalModelConfigDto(
                            maxContextLength = changes.maxContextLength,
                            port = changes.port,
                            generateKwargs = changes.generateKwargs,
                        ),
                        clearPort = changes.clearPort,
                    )
                }
            }
                .onSuccess {
                    _state.update {
                        it.copy(
                            savingConfig = false,
                            messageRes = R.string.local_models_admin_notice_config_saved,
                        )
                    }
                    loadConfig(force = true)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            savingConfig = false,
                            configError = adminError(
                                e,
                                conflictRes = R.string.local_models_admin_error_conflict_config,
                                unsupportedRes = R.string.local_models_admin_error_unsupported_config,
                            ),
                        )
                    }
                }
        }
    }

    // ---------- 删除模型 ----------

    /**
     * 删除一个已下载的模型。
     *
     * `modelId` 用模型标识而不是显示名：端点按路径取模型，名称相同的两个来源是两条记录。
     * 运行中的模型服务占着文件，服务端回 409，提示用户先停止服务。
     */
    fun deleteModel(modelId: String) {
        if (_state.value.deletingModelId != null) return
        val conn = connection() ?: run {
            _state.update {
                it.copy(deleteError = Texts.get(R.string.local_models_admin_error_not_connected))
            }
            return
        }
        _state.update { it.copy(deletingModelId = modelId, deleteError = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteLocalModel(conn, modelId) } }
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            models = current.models.filterNot {
                                it.id.ifBlank { it.name } == modelId
                            },
                            deletingModelId = null,
                            deleteError = null,
                            messageRes = R.string.local_models_admin_notice_deleted,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            deletingModelId = null,
                            deleteError = adminError(
                                e,
                                conflictRes = R.string.local_models_admin_error_conflict_delete,
                                unsupportedRes = R.string.local_models_admin_error_unsupported_delete,
                            ),
                        )
                    }
                }
        }
    }

    // ---------- 运行时启停 ----------

    /** 用已下载的模型启动运行时。端口取配置值，占用与内存压力由启动确认弹窗说明。 */
    fun startServer(modelId: String) {
        if (_state.value.runtimeBusy) return
        val conn = connection() ?: run {
            _state.update {
                it.copy(runtimeError = Texts.get(R.string.local_models_admin_error_not_connected))
            }
            return
        }
        _state.update { it.copy(runtimeBusy = true, runtimeError = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.startLocalModelServer(conn, modelId) }
            }
                .onSuccess {
                    _state.update {
                        it.copy(
                            runtimeBusy = false,
                            messageRes = R.string.local_models_admin_notice_server_started,
                        )
                    }
                    refresh(force = true)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            runtimeBusy = false,
                            runtimeError = adminError(
                                e,
                                conflictRes = R.string.local_models_admin_error_conflict_start,
                                unsupportedRes = R.string.local_models_admin_error_unsupported_server,
                            ),
                        )
                    }
                }
        }
    }

    fun stopServer() {
        if (_state.value.runtimeBusy) return
        val conn = connection() ?: run {
            _state.update {
                it.copy(runtimeError = Texts.get(R.string.local_models_admin_error_not_connected))
            }
            return
        }
        _state.update { it.copy(runtimeBusy = true, runtimeError = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.stopLocalModelServer(conn) } }
                .onSuccess {
                    _state.update {
                        it.copy(
                            runtimeBusy = false,
                            messageRes = R.string.local_models_admin_notice_server_stopped,
                        )
                    }
                    refresh(force = true)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            runtimeBusy = false,
                            runtimeError = adminError(
                                e,
                                conflictRes = R.string.local_models_admin_error_conflict_stop,
                                unsupportedRes = R.string.local_models_admin_error_unsupported_server,
                            ),
                        )
                    }
                }
        }
    }

    // ---------- 更新检查 ----------

    /** 检查运行时是否有新版本。结果留在 [LocalModelsUiState.update]，由页面决定是否显示更新按钮。 */
    fun checkUpdate() {
        if (_state.value.checkingUpdate) return
        val conn = connection() ?: run {
            _state.update {
                it.copy(updateError = Texts.get(R.string.local_models_admin_error_not_connected))
            }
            return
        }
        _state.update { it.copy(checkingUpdate = true, updateError = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.localModelServerUpdate(conn) } }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            checkingUpdate = false,
                            update = result,
                            updateError = null,
                            messageRes = if (result.hasUpdate) {
                                R.string.local_models_admin_notice_update_found
                            } else {
                                R.string.local_models_admin_notice_update_latest
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            checkingUpdate = false,
                            updateError = adminError(
                                e,
                                conflictRes = R.string.local_models_admin_error_conflict_update,
                                unsupportedRes = R.string.local_models_admin_error_unsupported_update,
                            ),
                        )
                    }
                }
        }
    }

    /** 更新运行时：与首次安装同一个端点，确认弹窗已说明会先停服务。 */
    fun updateRuntime() = installRuntime()

    private fun actionError(e: Throwable): String =
        if (e is ApiException && e.code == 409) {
            Texts.get(R.string.local_models_busy)
        } else {
            Texts.get(R.string.local_models_action_failed, ErrorText.of(e))
        }

    /**
     * 管理端动作失败的原因。
     *
     * 400 的 `detail` 由服务端给出，能贴就贴。409 一律以调用方给的动作文案为主
     * （「先停止服务」「服务已在运行」这类话只有本页知道），服务端的 `detail` 附在后面。
     * 401 与 404 的通用文案在本页会误导（说的是会话与令牌），因此 401 换成「无权修改设置」，
     * 404 换成「当前服务端不支持」——本地模型这几个端点是 2.3.0 之后才补齐的，旧版本直接 404。
     */
    private fun adminError(e: Throwable, conflictRes: Int, unsupportedRes: Int): String {
        val ex = e as? ApiException ?: return ErrorText.of(e)
        val detail = detailTextOf(ex)
        return when (ex.code) {
            400 -> if (detail.isNotBlank()) {
                Texts.get(R.string.local_models_admin_error_bad_request, detail)
            } else {
                Texts.get(R.string.local_models_admin_error_bad_request_plain)
            }

            401 -> Texts.get(R.string.local_models_admin_error_unauthorized)
            404 -> Texts.get(unsupportedRes)
            409 -> if (detail.isNotBlank()) {
                Texts.get(
                    R.string.local_models_admin_error_with_detail,
                    Texts.get(conflictRes),
                    detail,
                )
            } else {
                Texts.get(conflictRes)
            }

            else -> ErrorText.of(e)
        }
    }

    /** 服务端 `detail` 的可用部分。结构化响应取其中的 `message`，JSON 原文不直接贴给用户。 */
    private fun detailTextOf(ex: ApiException): String {
        val raw = ex.detail.orEmpty().trim()
        val structured = runCatching { api.apiErrorDetailOf(ex)?.message.orEmpty() }.getOrDefault("")
        return structured.ifBlank {
            if (raw.startsWith("{") || raw.startsWith("[")) "" else raw
        }
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
