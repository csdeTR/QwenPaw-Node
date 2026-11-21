package io.qwenpaw.node.ui.settings

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.ActiveModelDto
import io.qwenpaw.node.data.model.ModelsActiveDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.ProviderScope
import io.qwenpaw.node.data.model.ProviderSections
import io.qwenpaw.node.data.model.configuredModelCount
import io.qwenpaw.node.data.model.groupProviders
import io.qwenpaw.node.data.model.isProviderConfigured
import io.qwenpaw.node.data.model.providerDisplayName
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.chat.ModelChoice
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 两个可切换的作用域。`effective` 只读，不设切换入口。 */
enum class ModelScope(val wire: String, @StringRes val titleRes: Int) {
    GLOBAL("global", R.string.models_scope_global),
    AGENT("agent", R.string.models_scope_agent),
}

/** 提供商卡片上的配置状态。停用优先于「未配置」：停用的提供商即便填过地址也不可用。 */
enum class ProviderStatus {
    CONFIGURED,
    DISABLED,
    UNCONFIGURED,
}

/**
 * 提供商当前是否已配置好。
 *
 * 判据只在 [isProviderConfigured] 一处：卡片标签、已配置区与可用区的划分都读它，
 * 各写一套会让同一个提供商同时出现在两个区里。
 */
fun providerStatus(provider: ProviderDto): ProviderStatus = when {
    !provider.enabled -> ProviderStatus.DISABLED
    isProviderConfigured(provider) -> ProviderStatus.CONFIGURED
    else -> ProviderStatus.UNCONFIGURED
}

/**
 * 凭据状态对应的文案 key；没有可说的返回 null。
 *
 * `require_api_key` 为 null（旧版服务端不带该字段）时不猜：既不说「无需密钥」，
 * 也不说「未设置密钥」，否则会给出与实际不符的结论。
 */
@StringRes
fun providerCredentialRes(provider: ProviderDto): Int? = when {
    provider.oauthConnected -> R.string.models_rework_credential_oauth
    provider.requireApiKey == true && provider.apiKey.isNullOrBlank() ->
        R.string.models_rework_credential_missing
    provider.requireApiKey == true -> R.string.models_rework_credential_set
    provider.requireApiKey == false -> R.string.models_rework_credential_none
    else -> null
}

/** 变体名：服务端给了 `provider_variant` 就用它，否则退回显示名。 */
@androidx.annotation.StringRes
fun variantLabelRes(variant: String?): Int? = when (variant) {
    "dashscope" -> R.string.models_variant_dashscope
    "chat_completions" -> R.string.models_variant_chat_completions
    "responses" -> R.string.models_variant_responses
    "open_platform" -> R.string.models_variant_open_platform
    "open_platform_cn" -> R.string.models_variant_open_platform_cn
    "open_platform_intl" -> R.string.models_variant_open_platform_intl
    "coding_plan" -> R.string.models_variant_coding_plan
    "coding_plan_cn" -> R.string.models_variant_coding_plan_cn
    "coding_plan_intl" -> R.string.models_variant_coding_plan_intl
    "token_plan" -> R.string.models_variant_token_plan
    "token_plan_intl" -> R.string.models_variant_token_plan_intl
    "china" -> R.string.models_variant_china
    "international" -> R.string.models_variant_international
    "bigmodel" -> R.string.models_variant_bigmodel
    "zai" -> R.string.models_variant_zai
    "chatgpt" -> R.string.models_variant_chatgpt
    else -> null
}

fun variantLabel(provider: ProviderDto): String {
    val res = variantLabelRes(provider.providerVariant)
    return when {
        res != null -> Texts.get(res)
        !provider.providerVariant.isNullOrBlank() -> provider.providerVariant
        else -> providerDisplayName(provider)
    }
}

/** 提供商属于哪个页签。与 [groupProviders] 的分组口径保持一致，避免数量与列表对不上。 */
fun providerScopeOf(provider: ProviderDto): ProviderScope =
    if (provider.isLocal || provider.isCustom) ProviderScope.LOCAL else ProviderScope.CLOUD

fun countProviders(providers: List<ProviderDto>, scope: ProviderScope): Int =
    providers.count { providerScopeOf(it) == scope }

/**
 * 搜索匹配。
 *
 * 命中 id、显示名、分组名与变体名四类：用户可能输入 `zhipu`（分组名）、
 * `bigmodel`（变体名）或 `Z.AI`（显示名），只匹配显示名会让搜索看起来没生效。
 */
fun matchesProviderQuery(provider: ProviderDto, query: String): Boolean {
    val text = query.trim()
    if (text.isEmpty()) return true
    return listOf(
        provider.id,
        provider.name,
        provider.providerGroupName,
        provider.providerVariant,
        provider.providerGroup,
    ).any { it != null && it.contains(text, ignoreCase = true) }
}

fun filterProviders(providers: List<ProviderDto>, query: String): List<ProviderDto> =
    if (query.isBlank()) providers else providers.filter { matchesProviderQuery(it, query) }

/** 卡片上的模型数量。只算已配置与额外添加的，探测到但未选的模型不算。 */
fun modelCountText(provider: ProviderDto): String {
    val count = configuredModelCount(provider)
    return if (count == 0) {
        Texts.get(R.string.models_rework_no_models)
    } else {
        Texts.get(R.string.models_rework_model_count, count)
    }
}

/** 列表页显示用的状态文案。 */
fun providerStatusText(provider: ProviderDto): String = when (providerStatus(provider)) {
    ProviderStatus.CONFIGURED -> Texts.get(R.string.models_rework_status_configured)
    ProviderStatus.DISABLED -> Texts.get(R.string.models_rework_status_disabled)
    ProviderStatus.UNCONFIGURED -> Texts.get(R.string.models_rework_status_unconfigured)
}

/**
 * 模型与提供商页的状态。
 *
 * `agentDefault` 与 `effective` 分开：前者是该智能体配置里**显式写下**的模型，
 * 没写过时服务端返回 `active_llm: null`；后者是实际生效的那个（会回落全局）。
 * 用 `effective` 冒充前者，界面会把「跟随全局」显示成一个已选中的模型。
 */
data class ModelsUiState(
    val providers: List<ProviderDto> = emptyList(),
    val choices: List<ModelChoice> = emptyList(),
    val globalDefault: ActiveModelDto? = null,
    val agentDefault: ActiveModelDto? = null,
    val effective: ActiveModelDto? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    /** 某个作用域当前显示的名字。`null` 表示该作用域没有显式设置。 */
    fun labelOf(scope: ModelScope): String? = when (scope) {
        ModelScope.GLOBAL -> labelOf(globalDefault)
        ModelScope.AGENT -> labelOf(agentDefault)
    }

    private fun labelOf(slot: ActiveModelDto?): String? {
        val providerId = slot?.providerId ?: return null
        val model = slot.model ?: return null
        return choices.firstOrNull { it.providerId == providerId && it.modelId == model }?.modelName
            ?: model
    }

    /**
     * 按页签与搜索词切出「已配置 / 可用」两段。
     *
     * 搜索在分组之前做：先分组再过滤会把「Zhipu 四版本命中一个」渲染成整组，
     * 用户搜 `z.ai` 却看到 BigModel 卡。
     */
    fun sections(scope: ProviderScope, query: String): ProviderSections =
        groupProviders(filterProviders(providers, query), scope)
}

/**
 * 模型与提供商页的状态与动作。
 *
 * 只负责提供商列表与两处默认模型的摘要；具体配置进入提供商详情页，
 * 默认模型切换进入默认模型页。
 */
class ModelsViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    // 名字避开 `scope`：`setDefault` 的入参也叫 scope（模型作用域），同名会遮蔽这个字段。
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    fun ensureLoaded() {
        if (_state.value.loaded || _state.value.loading) return
        refresh()
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
            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val providers = async { runCatching { api.providers(conn) } }
                    val global = async {
                        runCatching { api.activeModels(conn, ModelScope.GLOBAL.wire) }
                    }
                    val agent = async {
                        runCatching { api.activeModels(conn, ModelScope.AGENT.wire, conn.agentId) }
                    }
                    val effective = async {
                        runCatching { api.activeModels(conn, "effective") }
                    }
                    Fetched(
                        providers = providers.await(),
                        global = global.await(),
                        agent = agent.await(),
                        effective = effective.await(),
                    )
                }
            }
            _state.update { current ->
                val providers = fetched.providers.getOrNull() ?: current.providers
                current.copy(
                    providers = providers,
                    choices = modelChoicesOf(providers),
                    globalDefault = fetched.global.getOrNull()?.activeLlm,
                    agentDefault = fetched.agent.getOrNull()?.activeLlm,
                    effective = fetched.effective.getOrNull()?.activeLlm,
                    loading = false,
                    loaded = true,
                    // 提供商列表取不到就是失败：这页的主体内容就是它。
                    error = fetched.providers.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
        }
    }

    /**
     * 切换某个作用域的默认模型。
     *
     * **不做乐观更新**：服务端可能回 400（参数）或 404（提供商不存在），
     * 本地先改会让界面停留在一个并未生效的值上。成功后重拉三组默认模型。
     */
    fun setDefault(scope: ModelScope, providerId: String, model: String) {
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
                        scope = scope.wire,
                        agentId = conn.agentId,
                    )
                }
            }
            val failure = saved.exceptionOrNull()
            if (failure != null) {
                _state.update { it.copy(saving = false, error = ErrorText.of(failure)) }
                return@launch
            }
            val fresh = withContext(Dispatchers.IO) {
                coroutineScope {
                    val global = async { runCatching { api.activeModels(conn, ModelScope.GLOBAL.wire) } }
                    val agent = async {
                        runCatching { api.activeModels(conn, ModelScope.AGENT.wire, conn.agentId) }
                    }
                    val effective = async { runCatching { api.activeModels(conn, "effective") } }
                    Triple(global.await(), agent.await(), effective.await())
                }
            }
            _state.update { current ->
                current.copy(
                    saving = false,
                    globalDefault = fresh.first.getOrNull()?.activeLlm ?: current.globalDefault,
                    agentDefault = fresh.second.getOrNull()?.activeLlm ?: current.agentDefault,
                    effective = fresh.third.getOrNull()?.activeLlm ?: current.effective,
                    notice = Texts.get(R.string.models_rework_saved),
                )
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clear() {
        _state.value = ModelsUiState()
    }

    private data class Fetched(
        val providers: Result<List<ProviderDto>>,
        val global: Result<ModelsActiveDto>,
        val agent: Result<ModelsActiveDto>,
        val effective: Result<ModelsActiveDto>,
    )
}
