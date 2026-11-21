package io.qwenpaw.node.ui.settings

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import io.qwenpaw.node.data.BrowserUrlCheck
import io.qwenpaw.node.data.validateBrowserUrl
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 自定义提供商的协议。
 *
 * [wire] 是服务端 `chat_model` 接受的三个值，写错会被当成未知协议、调用直接失败，
 * 因此不在界面上给自由输入。
 */
enum class ProviderProtocol(val wire: String, @StringRes val labelRes: Int) {
    OPENAI_CHAT("OpenAIChatModel", R.string.models_rework_protocol_chat),
    OPENAI_RESPONSES("OpenAIResponseModel", R.string.models_rework_protocol_response),
    ANTHROPIC("AnthropicChatModel", R.string.models_rework_protocol_anthropic),
}

/** 添加提供商表单的校验结果。 */
enum class ProviderFormError(@StringRes val messageRes: Int) {
    ID_EMPTY(R.string.models_rework_error_id_empty),
    ID_INVALID(R.string.models_rework_error_id_invalid),
    BASE_URL_EMPTY(R.string.models_rework_error_base_url_empty),
    BASE_URL_INVALID(R.string.models_rework_error_base_url_invalid),
}

/** 服务端与客户端共用这个形状：小写字母开头，最多 64 位。 */
private val PROVIDER_ID_PATTERN = Regex("^[a-z][a-z0-9_-]{0,63}$")

/**
 * 校验提供商 ID。
 *
 * 创建后不可改，因此这里比服务端更严格：服务端只要求非空，
 * 但带大写字母或空格的 id 会让后续的 URL 路径与配置键都变得难以阅读。
 */
fun validateProviderId(raw: String): ProviderFormError? {
    val text = raw.trim()
    if (text.isEmpty()) return ProviderFormError.ID_EMPTY
    if (!PROVIDER_ID_PATTERN.matches(text)) return ProviderFormError.ID_INVALID
    return null
}

/** 校验 Base URL。只放行 http 与 https，规则与外部浏览器跳转同一份。 */
fun validateProviderBaseUrl(raw: String): ProviderFormError? {
    val text = raw.trim()
    if (text.isEmpty()) return ProviderFormError.BASE_URL_EMPTY
    val check: BrowserUrlCheck = validateBrowserUrl(text)
    return if (check.ok) null else ProviderFormError.BASE_URL_INVALID
}

/**
 * `POST /api/models/custom-providers` 的请求体。
 *
 * 只带创建必需的四项与 `chat_model`：模型清单留空，让用户创建后再进模型池挑选，
 * 一次提交同时填提供商与模型会把失败原因混在一起。
 */
fun providerCreateBody(
    id: String,
    name: String,
    baseUrl: String,
    apiKey: String,
    protocol: ProviderProtocol,
): JsonObject {
    val trimmedId = id.trim()
    return buildJsonObject {
        put("id", trimmedId)
        put("name", name.trim().ifBlank { trimmedId })
        put("default_base_url", baseUrl.trim())
        if (apiKey.isNotBlank()) put("api_key", apiKey.trim())
        put("chat_model", protocol.wire)
    }
}

data class ProviderAddUiState(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_CHAT,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    /** 用户已经动过 ID 输入框，之后才显示校验结论，避免一进来就报错。 */
    val idTouched: Boolean = false,
) {
    /** 当前 ID 的问题；未动过输入框时不报。 */
    val idError: ProviderFormError?
        get() = if (idTouched && id.isNotEmpty()) validateProviderId(id) else null
}

/**
 * 添加自定义提供商。
 *
 * 只负责创建：协议与地址之外的一切（密钥、模型、OAuth）都在提供商详情页，
 * 这一页的成功判据只有「服务端返回了创建成功的响应」。
 */
class ProviderAddViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ProviderAddUiState())
    val state: StateFlow<ProviderAddUiState> = _state.asStateFlow()

    fun setId(value: String) = _state.update { it.copy(id = value, idTouched = true) }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setBaseUrl(value: String) = _state.update { it.copy(baseUrl = value) }

    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value) }

    fun setProtocol(value: ProviderProtocol) = _state.update { it.copy(protocol = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** 每次进入页面时调用，清掉上一次的表单与结论。 */
    fun begin() {
        _state.value = ProviderAddUiState()
    }

    fun clear() {
        _state.value = ProviderAddUiState()
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val problem = validateProviderId(current.id)
            ?: validateProviderBaseUrl(current.baseUrl)
        if (problem != null) {
            _state.update { it.copy(error = Texts.get(problem.messageRes), idTouched = true) }
            return
        }
        val conn = connection() ?: run {
            _state.update { it.copy(error = Texts.get(R.string.models_error_not_connected)) }
            return
        }
        val body = providerCreateBody(
            id = current.id,
            name = current.name,
            baseUrl = current.baseUrl,
            apiKey = current.apiKey,
            protocol = current.protocol,
        )
        _state.update { it.copy(saving = true, error = null, saved = false) }
        uiScope.launch {
            val created = runCatching {
                withContext(Dispatchers.IO) { api.createCustomProvider(conn, body) }
            }
            val failure = created.exceptionOrNull()
            _state.update {
                if (failure == null) {
                    it.copy(saving = false, saved = true, error = null)
                } else {
                    it.copy(saving = false, error = ErrorText.of(failure))
                }
            }
        }
    }
}
