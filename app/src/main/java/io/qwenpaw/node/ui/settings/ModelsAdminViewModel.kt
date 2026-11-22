package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.BaseUrlOptionDto
import io.qwenpaw.node.data.model.DiscoverModelsResultDto
import io.qwenpaw.node.data.model.ModelPoolPageDto
import io.qwenpaw.node.data.model.ModelSlotDto
import io.qwenpaw.node.data.model.ModelTemplateDto
import io.qwenpaw.node.data.model.OAuthStartDto
import io.qwenpaw.node.data.model.OAuthStatusDto
import io.qwenpaw.node.data.model.PoolModelDto
import io.qwenpaw.node.data.model.ProbeMultimodalDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.TestConnectionDto
import io.qwenpaw.node.data.model.resolveModelDetail
import io.qwenpaw.node.data.model.selectedModelIds as selectedIdsOf
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.common.SecretDraft
import io.qwenpaw.node.ui.common.formatJsonObject
import io.qwenpaw.node.ui.common.parseJsonObjectOrNull
import io.qwenpaw.node.ui.common.secretWireValue
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class ModelsAdminTab { CONFIG, MODELS, POOL }

data class ModelEditState(
    val id: String = "",
    val name: String = "",
    val isFree: Boolean = false,
    val supportsImage: Boolean = false,
    val supportsVideo: Boolean = false,
    val modelType: String = "chat",
    val templateId: String? = null,
    val hidden: Boolean = false,
    val generating: Boolean = false,
)

data class OAuthUi(
    val state: String = "",
    val authorizeUrl: String = "",
    val status: String = "pending",
    val error: String? = null,
)

/** 模型详情表单。可空能力用 null 表示「自动」。 */
data class ModelDetailDraft(
    val modelType: String = "chat",
    val supportsImage: Boolean? = null,
    val supportsAudio: Boolean? = null,
    val supportsVideo: Boolean? = null,
    val supportsToolCalling: Boolean? = null,
    val maxInput: String = "",
    val maxOutput: String = "",
    val thinkingKind: String = "unknown",
    val thinkingWire: String = "",
    val thinkingEfforts: Set<String> = emptySet(),
    val thinkingSupportsOff: Boolean = false,
    val thinkingBudgetMin: String = "",
    val thinkingBudgetMax: String = "",
    val thinkingEnabled: Boolean? = null,
    val thinkingBudget: String = "",
    val reasoningEffort: String = "",
    val relayReasoning: Boolean = true,
    val generateKwargs: String = "{}",
) {
    companion object {
        fun from(model: PoolModelDto): ModelDetailDraft {
            val control = model.thinkingControl
            val kwargs = model.generateKwargs ?: JsonObject(emptyMap())
            val maxTokens = (kwargs["max_tokens"] as? JsonPrimitive)?.contentOrNull
            val withoutMaxTokens = JsonObject(kwargs.filterKeys { it != "max_tokens" })
            return ModelDetailDraft(
                modelType = model.modelType.ifBlank { "chat" },
                supportsImage = model.supportsImage,
                supportsAudio = model.supportsAudio,
                supportsVideo = model.supportsVideo,
                supportsToolCalling = model.supportsToolCalling,
                maxInput = if (model.maxInputLengthConfigured) {
                    (model.maxInputLength ?: model.effectiveMaxInputLength)?.toString().orEmpty()
                } else {
                    ""
                },
                maxOutput = maxTokens.orEmpty(),
                thinkingKind = control?.kind ?: "unknown",
                thinkingWire = control?.wire.orEmpty(),
                thinkingEfforts = control?.efforts?.toSet().orEmpty(),
                thinkingSupportsOff = control?.supportsOff ?: false,
                thinkingBudgetMin = control?.budgetMin?.toString().orEmpty(),
                thinkingBudgetMax = control?.budgetMax?.toString().orEmpty(),
                thinkingEnabled = model.thinkingEnabled,
                thinkingBudget = model.thinkingBudget?.toString().orEmpty(),
                reasoningEffort = model.reasoningEffort.orEmpty(),
                relayReasoning = model.relayReasoning ?: true,
                generateKwargs = formatJsonObject(withoutMaxTokens),
            )
        }
    }
}

data class ModelsAdminUiState(
    val providerId: String = "",
    val providerName: String = "",
    val provider: ProviderDto? = null,
    val tab: ModelsAdminTab = ModelsAdminTab.CONFIG,
    val enabled: Boolean = true,
    val name: String = "",
    val baseUrl: String = "",
    val freezeUrl: Boolean = false,
    val baseUrlOptions: List<BaseUrlOptionDto> = emptyList(),
    val isQwenPawLocal: Boolean = false,
    val apiKey: SecretDraft = SecretDraft(),
    val authMode: String = "api_key",
    val chatModel: String = "",
    val customHeaders: String = "",
    val generateKwargs: String = "",
    val supportsOauth: Boolean = false,
    val oauthConnected: Boolean = false,
    val oauth: OAuthUi? = null,
    val templates: List<ModelTemplateDto> = emptyList(),
    val pool: ModelPoolPageDto = ModelPoolPageDto(),
    val poolSearch: String = "",
    val poolFamily: String = "all",
    val poolTab: String = "all",
    val poolLoading: Boolean = false,
    val selectedModelIds: Set<String> = emptySet(),
    val detail: PoolModelDto? = null,
    val detailDraft: ModelDetailDraft? = null,
    val editing: ModelEditState? = null,
    val adding: Boolean = false,
    val discover: DiscoverModelsResultDto? = null,
    val probes: Map<String, ProbeMultimodalDto> = emptyMap(),
    val testingModels: Set<String> = emptySet(),
    val modelTestResults: Map<String, TestConnectionDto> = emptyMap(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val isCustom: Boolean get() = provider?.isCustom == true
}

/**
 * 供应商详情：配置、模型增删改、模型池、OAuth。
 *
 * 密钥只写不读：GET 里的 api_key 已脱敏，直接回写会破坏原值，
 * 因此用 [SecretDraft] 区分「未改（省略）」「替换」「清除」。
 */
class ModelsAdminViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ModelsAdminUiState())
    val state: StateFlow<ModelsAdminUiState> = _state.asStateFlow()
    private var oauthJob: Job? = null
    private var poolReloadJob: Job? = null

    fun open(providerId: String) {
        val conn = connection() ?: return
        _state.value = ModelsAdminUiState(providerId = providerId, loading = true)
        scope.launch {
            load(conn, providerId)
        }
    }

    private suspend fun load(conn: Connection, providerId: String) {
        val providers = runCatching { withContext(Dispatchers.IO) { api.providers(conn) } }
        val templates = runCatching { withContext(Dispatchers.IO) { api.modelTemplates(conn) } }
        val provider = providers.getOrNull()?.firstOrNull { it.id == providerId }
        _state.update { current ->
            current.copy(
                providerId = providerId,
                provider = provider,
                providerName = provider?.name ?: providerId,
                templates = templates.getOrDefault(emptyList()),
                enabled = provider?.enabled ?: true,
                name = provider?.name.orEmpty(),
                baseUrl = provider?.baseUrl.orEmpty(),
                freezeUrl = provider?.freezeUrl == true,
                baseUrlOptions = provider?.meta?.baseUrlOptions.orEmpty(),
                isQwenPawLocal = providerId == "qwenpaw-local" || providerId == "copaw-local",
                selectedModelIds = provider?.let(::selectedIdsOf).orEmpty(),
                apiKey = SecretDraft(original = ""),
                authMode = provider?.authMode ?: "api_key",
                chatModel = provider?.chatModel.orEmpty(),
                customHeaders = formatJsonObject(headersToJson(provider?.customHeaders)),
                generateKwargs = formatJsonObject(
                    provider?.generateKwargs ?: JsonObject(emptyMap()),
                ),
                supportsOauth = provider?.supportsOauth == true,
                oauthConnected = provider?.oauthConnected == true,
                loading = false,
                error = if (provider == null) {
                    Texts.get(R.string.models_admin_not_found)
                } else {
                    null
                },
            )
        }
        if (provider != null) loadPool(conn, providerId)
    }

    private fun loadPool(conn: Connection, providerId: String) {
        _state.update { it.copy(poolLoading = true) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.modelPool(
                        conn,
                        providerId,
                        tab = _state.value.poolTab,
                        search = _state.value.poolSearch,
                        family = _state.value.poolFamily,
                    )
                }
            }
            _state.update {
                it.copy(
                    pool = result.getOrDefault(ModelPoolPageDto()),
                    poolLoading = false,
                    error = result.exceptionOrNull()?.let(ErrorText::of) ?: it.error,
                )
            }
        }
    }

    fun setTab(tab: ModelsAdminTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun setEnabled(value: Boolean) = _state.update { it.copy(enabled = value) }
    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setBaseUrl(value: String) = _state.update { it.copy(baseUrl = value) }
    fun setAuthMode(value: String) = _state.update { it.copy(authMode = value) }
    fun setChatModel(value: String) = _state.update { it.copy(chatModel = value) }
    fun setCustomHeaders(value: String) = _state.update { it.copy(customHeaders = value) }
    fun setGenerateKwargs(value: String) = _state.update { it.copy(generateKwargs = value) }

    fun beginSecretReplace() = _state.update {
        it.copy(apiKey = it.apiKey.copy(replacing = true, clearing = false, replacement = ""))
    }

    fun setSecretReplacement(value: String) = _state.update {
        it.copy(apiKey = it.apiKey.copy(replacing = true, replacement = value))
    }

    fun clearSecret() = _state.update {
        it.copy(apiKey = it.apiKey.copy(replacing = false, clearing = true, replacement = ""))
    }

    fun cancelSecretChange() = _state.update {
        it.copy(apiKey = it.apiKey.copy(replacing = false, clearing = false, replacement = ""))
    }

    fun saveProvider() {
        val conn = connection() ?: return
        val current = _state.value
        val headers = parseJsonObjectOrNull(current.customHeaders)
        val kwargs = parseJsonObjectOrNull(current.generateKwargs)
        if (headers == null || kwargs == null) {
            _state.update { it.copy(error = Texts.get(R.string.models_admin_invalid_json)) }
            return
        }
        val provider = current.provider
        val baselineHeaders = headersToJson(provider?.customHeaders)
        val baselineKwargs = provider?.generateKwargs ?: JsonObject(emptyMap())
        val baseUrlChanged = !current.freezeUrl && current.baseUrl != provider?.baseUrl.orEmpty()
        val autoDiscover = baseUrlChanged || secretWireValue(current.apiKey) != null
        val body = buildJsonObject {
            // QwenPaw Local 不能停用；其余提供商只在真的改过时提交 enabled。
            if (!current.isQwenPawLocal && current.enabled != (provider?.enabled ?: true)) {
                put("enabled", current.enabled)
            }
            if (current.isCustom && current.name != provider?.name.orEmpty()) {
                put("name", current.name)
            }
            if (baseUrlChanged) put("base_url", current.baseUrl)
            if (current.authMode != (provider?.authMode ?: "api_key")) {
                put("auth_mode", current.authMode)
            }
            if (current.isCustom && current.chatModel != provider?.chatModel.orEmpty()) {
                put("chat_model", current.chatModel)
            }
            secretWireValue(current.apiKey)?.let { put("api_key", it) }
            if (headers != baselineHeaders) put("custom_headers", headers)
            if (kwargs != baselineKwargs) put("generate_kwargs", kwargs)
            if (autoDiscover) put("auto_discover", true)
        }
        if (body.isEmpty()) {
            _state.update { it.copy(notice = Texts.get(R.string.models_admin_no_changes)) }
            return
        }
        _state.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.configureProvider(conn, current.providerId, body) }
            }
            _state.update {
                it.copy(
                    saving = false,
                    notice = if (result.isSuccess) Texts.get(R.string.models_admin_saved) else it.notice,
                    error = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
            if (result.isSuccess) load(conn, current.providerId)
        }
    }

    /** 模型级测试。每个模型单独运行，结果按模型 id 保存。 */
    fun testModel(modelId: String) {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        if (modelId in _state.value.testingModels) return
        _state.update {
            it.copy(testingModels = it.testingModels + modelId, error = null)
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.testModel(conn, providerId, modelId) }
            }
            _state.update { current ->
                current.copy(
                    testingModels = current.testingModels - modelId,
                    modelTestResults = result.getOrNull()?.let {
                        current.modelTestResults + (modelId to it)
                    } ?: current.modelTestResults,
                    error = result.exceptionOrNull()?.let(ErrorText::of) ?: current.error,
                )
            }
        }
    }

    fun discover() {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        _state.update { it.copy(busy = true, discover = null, error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.discoverProviderModels(conn, providerId, null) }
            }
            _state.update {
                it.copy(busy = false, discover = result.getOrNull(), error = result.exceptionOrNull()?.let(ErrorText::of))
            }
            if (result.isSuccess) load(conn, providerId)
        }
    }

    fun deleteProvider() {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.deleteCustomProvider(conn, providerId) }
            }
            _state.update {
                it.copy(
                    busy = false,
                    notice = if (result.isSuccess) Texts.get(R.string.models_admin_deleted) else it.notice,
                    error = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
        }
    }

    fun beginAddModel() = _state.update { it.copy(editing = ModelEditState(), adding = true) }

    fun beginEditModel(modelId: String, modelName: String) = _state.update {
        it.copy(editing = ModelEditState(id = modelId, name = modelName), adding = false)
    }

    fun cancelEditModel() = _state.update { it.copy(editing = null, adding = false) }

    fun setEditId(value: String) = _state.update { it.copy(editing = it.editing?.copy(id = value)) }
    fun setEditName(value: String) = _state.update { it.copy(editing = it.editing?.copy(name = value)) }
    fun setEditFree(value: Boolean) = _state.update { it.copy(editing = it.editing?.copy(isFree = value)) }
    fun setEditImage(value: Boolean) = _state.update { it.copy(editing = it.editing?.copy(supportsImage = value)) }
    fun setEditVideo(value: Boolean) = _state.update { it.copy(editing = it.editing?.copy(supportsVideo = value)) }
    fun setEditType(value: String) = _state.update { it.copy(editing = it.editing?.copy(modelType = value)) }

    fun saveModel() {
        val conn = connection() ?: return
        val current = _state.value
        val editing = current.editing ?: return
        val body = buildJsonObject {
            put("id", editing.id)
            put("name", editing.name)
            put("is_free", editing.isFree)
            put("model_type", editing.modelType)
            put("supports_image", editing.supportsImage)
            put("supports_video", editing.supportsVideo)
        }
        _state.update { it.copy(editing = editing.copy(generating = true), error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.addModel(conn, current.providerId, body) }
            }
            _state.update {
                it.copy(
                    editing = null,
                    adding = false,
                    notice = if (result.isSuccess) Texts.get(R.string.models_admin_model_added) else it.notice,
                    error = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
            if (result.isSuccess) load(conn, current.providerId)
        }
    }

    fun deleteModel(modelId: String) {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.removeModel(conn, providerId, modelId) }
            }
            _state.update { it.copy(busy = false, error = result.exceptionOrNull()?.let(ErrorText::of)) }
            if (result.isSuccess) load(conn, providerId)
        }
    }

    fun toggleModelVisibility(modelId: String, hidden: Boolean) {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.setModelVisibility(conn, providerId, modelId, hidden) }
            }
            if (result.isSuccess) load(conn, providerId)
        }
    }

    fun probeModel(modelId: String) {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.probeModelMultimodal(conn, providerId, modelId) }
            }
            _state.update {
                it.copy(probes = result.getOrNull()?.let { p -> it.probes + (modelId to p) } ?: it.probes)
            }
        }
    }

    fun setPoolSearch(value: String) {
        _state.update { it.copy(poolSearch = value) }
        schedulePoolReload()
    }

    fun setPoolFamily(value: String) {
        _state.update { it.copy(poolFamily = value) }
        schedulePoolReload()
    }

    fun setPoolTab(value: String) {
        _state.update { it.copy(poolTab = value) }
        schedulePoolReload()
    }

    /** 筛选变化立刻重新请求；250ms 防抖避免输入每个字都打一次接口。 */
    private fun schedulePoolReload() {
        poolReloadJob?.cancel()
        poolReloadJob = scope.launch {
            delay(250)
            loadPoolPage(0)
        }
    }

    fun loadPoolPage(offset: Int) {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        _state.update { it.copy(poolLoading = true) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.modelPool(
                        conn,
                        providerId,
                        tab = _state.value.poolTab,
                        search = _state.value.poolSearch,
                        family = _state.value.poolFamily,
                        offset = offset,
                    )
                }
            }
            _state.update {
                it.copy(
                    pool = result.getOrDefault(ModelPoolPageDto()),
                    poolLoading = false,
                    error = result.exceptionOrNull()?.let(ErrorText::of) ?: it.error,
                )
            }
        }
    }

    fun selectAllPool(selected: Boolean) {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.selectAllPoolModels(conn, providerId, selected) }
            }
            if (result.isSuccess) {
                refreshProvider(conn, providerId)
                loadPoolPage(0)
            } else {
                _state.update {
                    it.copy(error = result.exceptionOrNull()?.let(ErrorText::of))
                }
            }
        }
    }

    fun selectPoolModel(modelId: String, selected: Boolean) {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        val before = _state.value.selectedModelIds
        _state.update {
            it.copy(
                selectedModelIds = if (selected) it.selectedModelIds + modelId
                else it.selectedModelIds - modelId,
                error = null,
            )
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.selectPoolModel(conn, providerId, modelId, selected) }
            }
            if (result.isSuccess) {
                refreshProvider(conn, providerId)
                loadPoolPage(_state.value.pool.offset)
            } else {
                _state.update {
                    it.copy(
                        selectedModelIds = before,
                        error = result.exceptionOrNull()?.let(ErrorText::of),
                    )
                }
            }
        }
    }

    /** 选择后只刷新 provider 与已选集合，不重置配置表单里正在编辑的内容。 */
    private fun refreshProvider(conn: Connection, providerId: String) {
        scope.launch {
            val providers = runCatching {
                withContext(Dispatchers.IO) { api.providers(conn) }
            }.getOrNull() ?: return@launch
            val provider = providers.firstOrNull { it.id == providerId } ?: return@launch
            _state.update {
                it.copy(
                    provider = provider,
                    providerName = provider.name.ifBlank { providerId },
                    selectedModelIds = selectedIdsOf(provider),
                )
            }
        }
    }

    fun openModelDetail(model: PoolModelDto) {
        _state.update {
            it.copy(detail = model, detailDraft = ModelDetailDraft.from(model), error = null)
        }
    }

    /**
     * 「模型」页签的行来自 provider.allModels，可能不在当前池分页/筛选里。
     * 先查池页，再回落到 provider 条目，避免「设置」点了没反应。
     */
    fun openModelDetailById(modelId: String) {
        val current = _state.value
        resolveModelDetail(modelId, current.pool.models, current.provider)
            ?.let(::openModelDetail)
    }

    fun closeModelDetail() = _state.update { it.copy(detail = null, detailDraft = null) }

    fun updateModelDetail(transform: (ModelDetailDraft) -> ModelDetailDraft) = _state.update {
        val draft = it.detailDraft ?: return@update it
        it.copy(detailDraft = transform(draft))
    }

    fun saveModelDetail() {
        val conn = connection() ?: return
        val current = _state.value
        val model = current.detail ?: return
        val draft = current.detailDraft ?: return
        val kwargs = parseJsonObjectOrNull(draft.generateKwargs)
        if (kwargs == null) {
            _state.update { it.copy(error = Texts.get(R.string.models_admin_invalid_json)) }
            return
        }
        val maxInput = draft.maxInput.trim()
        if (maxInput.isNotEmpty() && (maxInput.toLongOrNull() == null || maxInput.toLong() < 1000)) {
            _state.update { it.copy(error = Texts.get(R.string.models_model_max_input_invalid)) }
            return
        }
        val body = draft.toConfigureBody(kwargs, model)
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.configureModel(conn, current.providerId, model.id, body) }
            }
            _state.update {
                it.copy(
                    busy = false,
                    detail = if (result.isSuccess) null else it.detail,
                    detailDraft = if (result.isSuccess) null else it.detailDraft,
                    notice = if (result.isSuccess) {
                        Texts.get(
                            R.string.models_model_saved,
                            model.name.ifBlank { model.id },
                        )
                    } else {
                        it.notice
                    },
                    error = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
            if (result.isSuccess) {
                refreshProvider(conn, current.providerId)
                loadPoolPage(_state.value.pool.offset)
            }
        }
    }

    fun startOAuth(onOpenUrl: (String) -> Unit) {
        val conn = connection() ?: return
        val providerId = _state.value.providerId
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.oauthStart(conn, providerId) }
            }
            val start = result.getOrNull()
            _state.update {
                it.copy(busy = false, error = result.exceptionOrNull()?.let(ErrorText::of))
            }
            if (start != null) {
                onOpenUrl(start.authorizeUrl)
                _state.update { it.copy(oauth = OAuthUi(state = start.state, authorizeUrl = start.authorizeUrl)) }
                pollOAuth(conn, providerId, start.state)
            }
        }
    }

    private fun pollOAuth(conn: Connection, providerId: String, stateToken: String) {
        oauthJob?.cancel()
        oauthJob = scope.launch {
            repeat(150) {
                delay(2_000)
                val result = runCatching {
                    withContext(Dispatchers.IO) { api.oauthStatus(conn, providerId, stateToken) }
                }
                val status = result.getOrNull()
                if (status != null) {
                    _state.update { it.copy(oauth = it.oauth?.copy(status = status.status, error = status.error)) }
                    if (status.status == "completed" || status.status == "failed") {
                        if (status.status == "completed") load(conn, providerId)
                        return@launch
                    }
                }
            }
            _state.update { it.copy(oauth = it.oauth?.copy(status = "failed", error = Texts.get(R.string.models_admin_oauth_expired))) }
        }
    }

    fun dismissOAuth() {
        oauthJob?.cancel()
        _state.update { it.copy(oauth = null) }
    }

    fun dismissNotice() = _state.update { it.copy(error = null, notice = null) }

    fun clear() {
        oauthJob?.cancel()
        _state.value = ModelsAdminUiState()
    }

    private fun headersToJson(headers: Map<String, String>?): JsonObject =
        JsonObject(headers.orEmpty().mapValues { JsonPrimitive(it.value) })
}
