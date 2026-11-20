package io.qwenpaw.node.ui.channels

import io.qwenpaw.node.R
import io.qwenpaw.node.data.channel.ChannelCapability
import io.qwenpaw.node.data.channel.QR_CAPABLE_CHANNELS
import io.qwenpaw.node.data.channel.QrOutcome
import io.qwenpaw.node.data.channel.channelCapability
import io.qwenpaw.node.data.channel.jsonBoolean
import io.qwenpaw.node.data.channel.mergeChannelCredentials
import io.qwenpaw.node.data.channel.qrOutcome
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
import kotlinx.serialization.json.JsonObject

/** 频道列表的一行。 */
data class ChannelRow(
    val key: String,
    val enabled: Boolean,
    /** 服务端声明可扫码（2.3.0 由插件的 `qrcode_available` 决定）。 */
    val qrAvailable: Boolean,
    /** 服务端显示名；为空时由界面按内置名单翻译，再不行显示 key。 */
    val label: String?,
    /** `missing_code` / `failed` / `validated` / `running`；2.2.2 为 null。 */
    val availability: String?,
    /** `repair.action == "install"` 时的安装来源，可直接交给插件安装接口。 */
    val installSource: String?,
    /** 失败诊断原文。 */
    val diagnostic: String?,
) {
    /** 插件未安装，但服务端给出了可安装来源。 */
    val installable: Boolean get() = installSource != null
}

/** 二维码授权面板的阶段。 */
enum class ChannelAuthPhase {
    LOADING,
    WAITING,
    SAVING,
    EXPIRED,
    FAILED,
}

/** 二维码授权面板的状态。非空即表示面板打开。 */
data class ChannelAuthState(
    val channel: String,
    val phase: ChannelAuthPhase = ChannelAuthPhase.LOADING,
    /** 不带 `data:` 前缀的 base64 PNG。 */
    val qrBase64: String = "",
    /** 轮询与主动回收都要用它。 */
    val pollToken: String = "",
    /** 服务端最近一次的原始状态词，用于区分「等待扫码」与「已扫码待确认」。 */
    val rawStatus: String = "",
    /** 失败原因或保存失败说明。 */
    val failure: String? = null,
)

data class ChannelUiState(
    val channels: List<ChannelRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val auth: ChannelAuthState? = null,
    /** 授权成功的频道键；界面取它弹一次提示后调用 [ChannelViewModel.consumeEnabledChannel]。 */
    val enabledChannel: String? = null,
    /** 正在安装频道插件的频道键。 */
    val installing: String? = null,
    /** 一次性提示；界面取走后调用 [ChannelViewModel.consumeNotice]。 */
    val noticeRes: Int? = null,
)

/**
 * 频道列表与二维码授权。
 *
 * 2.3.0 起频道实现插件化：能否扫码由 `channels/schemas` 的 `qrcode_available` 声明，
 * 未安装的频道带 `repair` 可以直接装。2.2.2 没有这些字段，回落到内置五频道名单。
 * 轮询挂在 ViewModel 上，页面或面板关闭时调用 [stopPolling]，避免离开后仍在打服务端。
 */
class ChannelViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val pollIntervalMs: Long = 2_000L,
    /** 轮询次数上限：2 秒一次、上限 300 次约十分钟，避免面板忘了关时无限打。 */
    private val maxPolls: Int = 300,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()
    private var pollJob: Job? = null

    fun refresh(force: Boolean = false) {
        if (!force && (_state.value.loaded || _state.value.loading)) return
        val conn = connection() ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val channels = api.channels(conn)
                    // schemas 是能力来源；2.2.2 上失败或字段缺失都不能挡住列表。
                    val schemas = runCatching { api.channelSchemas(conn) }.getOrDefault(emptyMap())
                    channels to schemas
                }
            }.onSuccess { (channels, schemas) ->
                _state.update {
                    it.copy(
                        channels = toRows(channels, schemas),
                        loading = false,
                        loaded = true,
                        error = null,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                }
            }
        }
    }

    /**
     * 可扫码频道置顶并按固定顺序排列；其余频道已启用的在前，再按显示名排序。
     *
     * 不按服务端返回顺序：那是字典序，未配置的频道会散在中间。
     */
    private fun toRows(
        channels: Map<String, JsonObject>,
        schemas: Map<String, JsonObject>,
    ): List<ChannelRow> {
        val rows = channels.map { (key, obj) ->
            val capability: ChannelCapability = channelCapability(key, schemas[key])
            ChannelRow(
                key = key,
                enabled = jsonBoolean(obj, "enabled"),
                qrAvailable = capability.qrAvailable,
                label = capability.label,
                availability = capability.availability,
                installSource = capability.installSource,
                diagnostic = capability.diagnostic,
            )
        }
        val order = QR_CAPABLE_CHANNELS.withIndex().associate { (index, key) -> key to index }
        return rows.sortedWith(
            compareBy(
                { if (it.qrAvailable) 0 else 1 },
                { order[it.key] ?: Int.MAX_VALUE },
                { if (it.enabled) 0 else 1 },
                { it.label ?: it.key },
            ),
        )
    }

    /** 打开某个频道的授权面板。不可扫码的频道不应调用到这里。 */
    fun openChannel(key: String) {
        val row = _state.value.channels.firstOrNull { it.key == key } ?: return
        if (!row.qrAvailable) return
        val conn = connection() ?: return
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(auth = ChannelAuthState(channel = key)) }
        scope.launch {
            val qr = runCatching { withContext(Dispatchers.IO) { api.channelQrcode(conn, key) } }
                .getOrElse { e ->
                    _state.update {
                        it.copy(
                            auth = it.auth?.copy(
                                phase = ChannelAuthPhase.FAILED,
                                failure = Texts.get(R.string.channel_qr_load_failed, ErrorText.of(e)),
                            ),
                        )
                    }
                    return@launch
                }
            if (qr.qrcodeImg.isBlank() || qr.pollToken.isBlank()) {
                _state.update {
                    it.copy(
                        auth = it.auth?.copy(
                            phase = ChannelAuthPhase.FAILED,
                            failure = Texts.get(R.string.channel_qr_load_failed, ""),
                        ),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    auth = ChannelAuthState(
                        channel = key,
                        phase = ChannelAuthPhase.WAITING,
                        qrBase64 = qr.qrcodeImg,
                        pollToken = qr.pollToken,
                        rawStatus = "waiting",
                    ),
                )
            }
            startPolling(conn, key, qr.pollToken)
        }
    }

    /** 二维码过期或失败后重新取一张。 */
    fun retry() {
        val channel = _state.value.auth?.channel ?: return
        openChannel(channel)
    }

    /**
     * 安装频道插件。
     *
     * 2.3.0 起内置频道也以插件形式分发，未安装时 `schemas` 会给 `repair.action=install`
     * 与 `source`。复用 0.6.0 已有的插件安装接口，装完刷新一次再延迟重试一次，
     * 给服务端留出加载插件的时间。
     */
    fun installChannel(key: String) {
        val row = _state.value.channels.firstOrNull { it.key == key } ?: return
        val source = row.installSource ?: return
        val conn = connection() ?: return
        if (_state.value.installing != null) return
        _state.update { it.copy(installing = key, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.installPlugin(conn, source) } }
                .onSuccess {
                    _state.update {
                        it.copy(installing = null, noticeRes = R.string.channels_install_started)
                    }
                    refresh(force = true)
                    // 实测安装要下载官方频道代码并探测 worker，约一分钟；轮询到能力转正为止。
                    scope.launch {
                        repeat(12) {
                            delay(5_000)
                            refresh(force = true)
                            if (_state.value.channels.firstOrNull { it.key == key }?.qrAvailable == true) {
                                return@launch
                            }
                        }
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(installing = null, error = ErrorText.of(e)) }
                }
        }
    }

    private fun startPolling(conn: Connection, channel: String, pollToken: String) {
        pollJob = scope.launch {
            repeat(maxPolls) {
                delay(pollIntervalMs)
                val auth = _state.value.auth ?: return@launch
                if (auth.channel != channel) return@launch

                val status = runCatching {
                    withContext(Dispatchers.IO) { api.channelQrStatus(conn, channel, pollToken) }
                }.getOrElse { e ->
                    _state.update {
                        it.copy(
                            auth = auth.copy(
                                phase = ChannelAuthPhase.FAILED,
                                failure = ErrorText.of(e),
                            ),
                        )
                    }
                    return@launch
                }

                when (val outcome = qrOutcome(status.status, status.credentials)) {
                    QrOutcome.Pending -> _state.update {
                        it.copy(
                            auth = auth.copy(
                                phase = ChannelAuthPhase.WAITING,
                                rawStatus = status.status,
                                failure = null,
                            ),
                        )
                    }

                    QrOutcome.Expired -> {
                        _state.update { it.copy(auth = auth.copy(phase = ChannelAuthPhase.EXPIRED)) }
                        return@launch
                    }

                    is QrOutcome.Failed -> {
                        _state.update {
                            it.copy(
                                auth = auth.copy(
                                    phase = ChannelAuthPhase.FAILED,
                                    failure = outcome.reason,
                                ),
                            )
                        }
                        return@launch
                    }

                    is QrOutcome.Success -> {
                        saveCredentials(conn, channel, auth, outcome.credentials)
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * 授权通过后写回。
     *
     * `PUT /api/config/channels/{channel}` 整段替换，因此先读当前配置再合并；
     * 只提交凭据会把 `enabled` 与其它策略字段冲回默认值。
     */
    private suspend fun saveCredentials(
        conn: Connection,
        channel: String,
        auth: ChannelAuthState,
        credentials: JsonObject,
    ) {
        _state.update { it.copy(auth = auth.copy(phase = ChannelAuthPhase.SAVING)) }
        runCatching {
            withContext(Dispatchers.IO) {
                val current = api.channel(conn, channel)
                api.updateChannel(conn, channel, mergeChannelCredentials(current, credentials))
            }
        }.onSuccess {
            closeAuthSessionQuietly(conn, channel, auth.pollToken)
            _state.update { it.copy(auth = null, enabledChannel = channel) }
            refresh(force = true)
        }.onFailure { e ->
            _state.update {
                it.copy(
                    auth = auth.copy(
                        phase = ChannelAuthPhase.FAILED,
                        failure = Texts.get(R.string.channel_qr_save_failed, ErrorText.of(e)),
                    ),
                )
            }
        }
    }

    /** 关闭授权面板并停止轮询，同时回收服务端会话。页面离开时也调用它。 */
    fun closeAuth() {
        val auth = _state.value.auth
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(auth = null) }
        if (auth != null && auth.pollToken.isNotBlank()) {
            val conn = connection() ?: return
            scope.launch { closeAuthSessionQuietly(conn, auth.channel, auth.pollToken) }
        }
    }

    /** 2.2.2 没有 DELETE 路由，回收失败不影响用户。 */
    private suspend fun closeAuthSessionQuietly(conn: Connection, channel: String, pollToken: String) {
        runCatching {
            withContext(Dispatchers.IO) { api.closeChannelQrcode(conn, channel, pollToken) }
        }
    }

    /** 只停轮询、保留面板（例如页面短暂失焦）。 */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun consumeEnabledChannel() {
        _state.update { it.copy(enabledChannel = null) }
    }

    fun consumeNotice() {
        _state.update { it.copy(noticeRes = null) }
    }

    fun clear() {
        stopPolling()
        _state.value = ChannelUiState()
    }
}