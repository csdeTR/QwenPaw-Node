package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.ActiveModelDto
import io.qwenpaw.node.data.model.ModelSlotDto
import io.qwenpaw.node.data.model.ModelsActiveDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.ThinkingControlDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.chat.ModelChoice
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 默认模型页的两个页签，与 Web 控制台一致。 */
enum class DefaultsTab {
    GLOBAL,
    AGENT,
}

/** 备用模型的生效范围。本版固定「已配置的提供商」，不提供其它取值。 */
const val FALLBACK_TARGET_SCOPE = "configured"

/**
 * 智能体模型设置里本页负责的部分。
 *
 * 主模型不在这里：它走 `PUT /api/models/active`，与这份快照的写入通道不同，
 * 混在一起会让「只改了主模型」也触发一次 PATCH。
 */
data class AgentModelSettings(
    val decision: ActiveModelDto? = null,
    val subagent: ActiveModelDto? = null,
    val fallbacks: List<ActiveModelDto> = emptyList(),
    val fallbackEnabled: Boolean = false,
    val thinkingLevel: String? = null,
    val thinkingBudget: Long? = null,
)

/** 一个模型键；提供商与模型都可能重复，两者拼起来才唯一。 */
fun slotKey(providerId: String?, model: String?): String =
    "${providerId.orEmpty()}:${model.orEmpty()}"

/** `{provider_id, model}` 形状的模型引用；两个字段缺一就返回 null。 */
fun activeModelOf(obj: JsonObject?): ActiveModelDto? {
    if (obj == null) return null
    val providerId = (obj["provider_id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val model = (obj["model"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (providerId.isEmpty() || model.isEmpty()) return null
    return ActiveModelDto(providerId, model)
}

fun activeModelJson(slot: ActiveModelDto): JsonObject = buildJsonObject {
    put("provider_id", slot.providerId.orEmpty())
    put("model", slot.model.orEmpty())
}

/**
 * 解析 `GET /api/agents/{id}` 里与模型相关的字段。
 *
 * `thinking_level` 的 `inherit` 与空串都归成「未设置」：服务端把两者都当成跟随默认，
 * 界面若把 `inherit` 当成一个具体档位显示，会出现一个用户从未选过的选项。
 */
fun parseAgentModelSettings(json: JsonObject): AgentModelSettings {
    val fallbacks: List<ActiveModelDto> = (json["fallback_models"] as? JsonArray)
        ?.mapNotNull { element -> activeModelOf(element as? JsonObject) }
        .orEmpty()
    val policy = json["fallback_policy"] as? JsonObject
    val enabled = (policy?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: false
    val level = (json["thinking_level"] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "inherit" }
    val budget = (json["thinking_budget"] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()
    return AgentModelSettings(
        decision = activeModelOf(json["decision_model"] as? JsonObject),
        subagent = activeModelOf(json["subagent_model"] as? JsonObject),
        fallbacks = fallbacks,
        fallbackEnabled = enabled,
        thinkingLevel = level,
        thinkingBudget = budget,
    )
}

/**
 * `PATCH /api/agents/{id}/model-settings` 的完整快照。
 *
 * 服务端按提交的键覆盖，缺键即保留原值；这里把六项全部写出（未设置显式给 null），
 * 才能让「清除子智能体模型」「恢复跟随默认」这类操作真正生效。
 */
fun agentModelSettingsBody(
    decision: ActiveModelDto?,
    subagent: ActiveModelDto?,
    fallbacks: List<ActiveModelDto>,
    fallbackEnabled: Boolean,
    thinkingLevel: String?,
    thinkingBudget: Long?,
): JsonObject = buildJsonObject {
    putJsonArray("fallback_models") {
        fallbacks.forEach { add(activeModelJson(it)) }
    }
    putJsonObject("fallback_policy") {
        put("enabled", fallbackEnabled)
        put("target_scope", FALLBACK_TARGET_SCOPE)
    }
    put("subagent_model", subagent?.let(::activeModelJson) ?: JsonNull)
    put("decision_model", decision?.let(::activeModelJson) ?: JsonNull)
    val level = thinkingLevel?.takeIf { it.isNotBlank() }
    put("thinking_level", level?.let(::JsonPrimitive) ?: JsonNull)
    if (thinkingBudget != null) {
        put("thinking_budget", thinkingBudget)
    } else {
        put("thinking_budget", JsonNull)
    }
}

/**
 * 思考预算的三个候选值。
 *
 * 预算型模型没有离散档位，滑块在手机上又很难停准；给出下限、默认与上限三个按钮，
 * 覆盖绝大多数使用场景，也不会误选区间外的值。
 */
fun thinkingBudgetStops(control: ThinkingControlDto?): List<Long> {
    if (control == null) return emptyList()
    return listOfNotNull(control.budgetMin, control.budgetDefault, control.budgetMax)
        .distinct()
        .sorted()
}

data class ModelDefaultsUiState(
    val tab: DefaultsTab = DefaultsTab.GLOBAL,
    val choices: List<ModelChoice> = emptyList(),
    val slots: Map<String, ModelSlotDto> = emptyMap(),
    val agentId: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** 全局对话模型，读自 `scope=global`。 */
    val globalChat: ActiveModelDto? = null,
    /**
     * 全局决策模型。
     *
     * `GET /api/models/active` 的客户端方法不带 `model_type`，读不到服务端的决策模型，
     * 因此这里只保存本次页面内写入的值；未写入过就按「未设置」显示。
     */
    val globalDecision: ActiveModelDto? = null,
    /** 智能体的主模型；保存成功后才会由服务端值覆盖。 */
    val agentChat: ActiveModelDto? = null,
    val primaryDirty: Boolean = false,
    val agentDecision: ActiveModelDto? = null,
    val agentSubagent: ActiveModelDto? = null,
    val fallbackEnabled: Boolean = false,
    val fallbacks: List<ActiveModelDto> = emptyList(),
    val thinkingLevel: String? = null,
    val thinkingBudget: Long? = null,
) {
    fun labelOf(slot: ActiveModelDto?): String? {
        val providerId = slot?.providerId ?: return null
        val model = slot.model ?: return null
        return choices.firstOrNull { it.providerId == providerId && it.modelId == model }?.modelName
            ?: model
    }

    /** 主模型对应的能力声明；用于决定是否显示思考强度。 */
    val primarySlot: ModelSlotDto?
        get() = slots[slotKey(agentChat?.providerId, agentChat?.model)]

    val thinkingControl: ThinkingControlDto?
        get() = primarySlot?.thinkingControl
}

/**
 * 默认模型页的状态与动作。
 *
 * 全局与智能体是两条写入通道：全局走 `PUT /api/models/active`，智能体主模型同样走它
 * （`scope=agent`），其余智能体字段走 `PATCH /api/agents/{id}/model-settings`。
 */
class ModelDefaultsViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ModelDefaultsUiState())
    val state: StateFlow<ModelDefaultsUiState> = _state.asStateFlow()

    fun ensureLoaded() {
        if (_state.value.loaded || _state.value.loading) return
        refresh()
    }

    fun selectTab(tab: DefaultsTab) = _state.update { it.copy(tab = tab) }

    fun dismissNotice() = _state.update { it.copy(notice = null, error = null) }

    fun clear() {
        _state.value = ModelDefaultsUiState()
    }

    fun refresh() {
        val conn = connection() ?: run {
            _state.update {
                it.copy(
                    loaded = true,
                    loading = false,
                    error = Texts.get(R.string.models_error_not_connected),
                )
            }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        uiScope.launch {
            val fetched = withContext(Dispatchers.IO) { fetch(conn) }
            _state.update { current ->
                val providers = fetched.providers.getOrNull()
                val slots = providers.orEmpty()
                    .filter { it.enabled }
                    .flatMap { provider ->
                        provider.allModels.map { slot -> slotKey(provider.id, slot.id) to slot }
                    }
                    .toMap()
                val settings = fetched.agentSettings
                current.copy(
                    choices = providers?.let { modelChoicesOf(it) } ?: current.choices,
                    slots = slots,
                    agentId = conn.agentId,
                    loading = false,
                    loaded = true,
                    globalChat = fetched.global.getOrNull()?.activeLlm ?: current.globalChat,
                    agentChat = fetched.agentActive.getOrNull()?.activeLlm ?: current.agentChat,
                    primaryDirty = false,
                    agentDecision = settings?.decision ?: current.agentDecision,
                    agentSubagent = settings?.subagent,
                    fallbackEnabled = settings?.fallbackEnabled ?: current.fallbackEnabled,
                    fallbacks = settings?.fallbacks ?: current.fallbacks,
                    thinkingLevel = settings?.thinkingLevel,
                    thinkingBudget = settings?.thinkingBudget,
                    error = fetched.providers.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
        }
    }

    /** 全局对话模型：选中即保存，不需要额外的保存动作。 */
    fun setGlobalChat(providerId: String, model: String) = saveGlobal(providerId, model, decision = false)

    /** 全局决策模型。 */
    fun setGlobalDecision(providerId: String, model: String) =
        saveGlobal(providerId, model, decision = true)

    fun setAgentPrimary(providerId: String, model: String) = _state.update {
        it.copy(agentChat = ActiveModelDto(providerId, model), primaryDirty = true)
    }

    fun setAgentDecision(slot: ActiveModelDto?) = _state.update { it.copy(agentDecision = slot) }

    fun setAgentSubagent(slot: ActiveModelDto?) = _state.update { it.copy(agentSubagent = slot) }

    fun setFallbackEnabled(enabled: Boolean) = _state.update { it.copy(fallbackEnabled = enabled) }

    fun addFallback(providerId: String, model: String) = _state.update { current ->
        val slot = ActiveModelDto(providerId, model)
        if (current.fallbacks.any { it.providerId == providerId && it.model == model }) {
            current
        } else {
            current.copy(fallbacks = current.fallbacks + slot)
        }
    }

    fun removeFallback(index: Int) = _state.update { current ->
        if (index !in current.fallbacks.indices) {
            current
        } else {
            current.copy(fallbacks = current.fallbacks.filterIndexed { i, _ -> i != index })
        }
    }

    fun setThinking(level: String?, budget: Long?) =
        _state.update { it.copy(thinkingLevel = level, thinkingBudget = budget) }

    /**
     * 保存智能体页签。
     *
     * 顺序固定：先写主模型（另一条通道），再提交快照。反过来的话快照里的决策模型
     * 可能指向一个还没生效的提供商，服务端会按当时的配置拒绝整份请求。
     */
    fun saveAgent() {
        val conn = connection() ?: return
        val current = _state.value
        val agentId = current.agentId ?: return
        if (current.saving) return
        _state.update { it.copy(saving = true, error = null, notice = null) }
        uiScope.launch {
            // 主模型改动走另一条通道：先写它，再提交同一通道里的其余字段。
            if (current.primaryDirty) {
                val providerId = current.agentChat?.providerId
                val model = current.agentChat?.model
                if (!providerId.isNullOrBlank() && !model.isNullOrBlank()) {
                    val primaryFailure = runCatching {
                        withContext(Dispatchers.IO) {
                            api.setActiveModel(
                                conn = conn,
                                providerId = providerId,
                                model = model,
                                scope = ModelScope.AGENT.wire,
                                agentId = agentId,
                                modelType = "chat",
                            )
                        }
                    }.exceptionOrNull()
                    if (primaryFailure != null) {
                        _state.update {
                            it.copy(saving = false, error = ErrorText.of(primaryFailure))
                        }
                        return@launch
                    }
                }
            }

            val body = agentModelSettingsBody(
                decision = current.agentDecision,
                subagent = current.agentSubagent,
                fallbacks = current.fallbacks,
                fallbackEnabled = current.fallbackEnabled,
                thinkingLevel = current.thinkingLevel,
                thinkingBudget = current.thinkingBudget,
            )
            val patched = runCatching {
                withContext(Dispatchers.IO) { api.updateAgentModelSettings(conn, agentId, body) }
            }
            val failure = patched.exceptionOrNull()
            if (failure != null) {
                _state.update { it.copy(saving = false, error = ErrorText.of(failure)) }
                return@launch
            }

            // 成功后按服务端的结论重新读一遍：PATCH 可能只接受部分字段，
            // 界面要显示真正写进去的值，而不是提交上去的那一套。
            val refreshed = withContext(Dispatchers.IO) { fetchAgent(conn, agentId) }
            val settings = refreshed.settings ?: parseAgentModelSettings(patched.getOrThrow())
            _state.update {
                it.copy(
                    saving = false,
                    primaryDirty = false,
                    agentChat = refreshed.active?.activeLlm ?: it.agentChat,
                    agentDecision = settings.decision,
                    agentSubagent = settings.subagent,
                    fallbackEnabled = settings.fallbackEnabled,
                    fallbacks = settings.fallbacks,
                    thinkingLevel = settings.thinkingLevel,
                    thinkingBudget = settings.thinkingBudget,
                    notice = Texts.get(R.string.models_rework_saved),
                )
            }
        }
    }

    private fun saveGlobal(providerId: String, model: String, decision: Boolean) {
        val conn = connection() ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null, notice = null) }
        uiScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    api.setActiveModel(
                        conn = conn,
                        providerId = providerId,
                        model = model,
                        scope = ModelScope.GLOBAL.wire,
                        modelType = if (decision) "decision" else "chat",
                    )
                }
            }
            val failure = saved.exceptionOrNull()
            if (failure != null) {
                _state.update { it.copy(saving = false, error = ErrorText.of(failure)) }
                return@launch
            }
            val slot = ActiveModelDto(providerId, model)
            _state.update {
                it.copy(
                    saving = false,
                    globalChat = if (decision) it.globalChat else slot,
                    globalDecision = if (decision) slot else it.globalDecision,
                    notice = Texts.get(R.string.models_rework_saved),
                )
            }
        }
    }

    private data class Fetched(
        val providers: Result<List<ProviderDto>>,
        val global: Result<ModelsActiveDto>,
        val agentActive: Result<ModelsActiveDto>,
        val agentSettings: AgentModelSettings?,
    )

    private data class AgentFetched(
        val active: ModelsActiveDto?,
        val settings: AgentModelSettings?,
    )

    private fun fetch(conn: Connection): Fetched {
        val providers = runCatching { api.providers(conn) }
        val global = runCatching { api.activeModels(conn, ModelScope.GLOBAL.wire) }
        // 连接一定带智能体（缺省为 default），两条请求都不能省：
        // 主模型与决策模型的读取通道不同，少一条就会把另一条的值当成默认值。
        val agentActive = runCatching {
            api.activeModels(conn, ModelScope.AGENT.wire, conn.agentId)
        }
        val agentSettings = runCatching { api.agentModelSettings(conn, conn.agentId) }
            .getOrNull()
            ?.let(::parseAgentModelSettings)
        return Fetched(
            providers = providers,
            global = global,
            agentActive = agentActive,
            agentSettings = agentSettings,
        )
    }

    private fun fetchAgent(conn: Connection, agentId: String): AgentFetched = AgentFetched(
        active = runCatching { api.activeModels(conn, ModelScope.AGENT.wire, agentId) }
            .getOrNull(),
        settings = runCatching { api.agentModelSettings(conn, agentId) }
            .getOrNull()
            ?.let(::parseAgentModelSettings),
    )
}
