package io.qwenpaw.node.ui.channels

import io.qwenpaw.node.R
import io.qwenpaw.node.data.channel.ChannelFieldError
import io.qwenpaw.node.data.channel.ChannelFormValues
import io.qwenpaw.node.data.channel.ChannelPayloadResult
import io.qwenpaw.node.data.channel.QrOutcome
import io.qwenpaw.node.data.channel.buildChannelPayload
import io.qwenpaw.node.data.channel.channelCapability
import io.qwenpaw.node.data.channel.channelDefaults
import io.qwenpaw.node.data.channel.channelDisplayNameRes
import io.qwenpaw.node.data.channel.initialChannelValues
import io.qwenpaw.node.data.channel.jsonString
import io.qwenpaw.node.data.channel.keepConsoleEnabled
import io.qwenpaw.node.data.channel.mergeChannelCredentials
import io.qwenpaw.node.data.channel.parseChannelFields
import io.qwenpaw.node.data.channel.qrOutcome
import io.qwenpaw.node.data.model.ChannelConflictDto
import io.qwenpaw.node.data.model.ChannelHealthDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.common.SecretDraft
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

data class ChannelConfigUiState(
    val channelKey: String = "",
    val label: String = "",
    val fields: List<io.qwenpaw.node.data.channel.ChannelField> = emptyList(),
    val values: ChannelFormValues = ChannelFormValues(),
    val original: JsonObject = JsonObject(emptyMap()),
    val availability: String? = null,
    val diagnostic: String? = null,
    val health: ChannelHealthDto? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val fieldErrors: Map<String, ChannelFieldError> = emptyMap(),
    val conflict: ChannelConflictDto? = null,
    val confirmingRestart: Boolean = false,
    /** 服务端声明可扫码。 */
    val qrAvailable: Boolean = false,
    /** `install` / `enable` / `repair`；运行中或 2.2.2 为 null。 */
    val repairAction: String? = null,
    val pluginId: String? = null,
    val repairSource: String? = null,
    /** 非空表示二维码面板打开。 */
    val auth: ChannelAuthState? = null,
    val repairing: Boolean = false,
    /** 插件没装或运行时缺失：显示安装面板，不显示通用错误。 */
    val missingPlugin: Boolean = false,
    /** 服务端版本低于门槛：整页提示并隐藏表单与保存。 */
    val versionUnsupported: Boolean = false,
    /** 无法确认服务端版本：显示提示但放行。 */
    val versionUnknown: Boolean = false,
    /** 服务端版本原文，用于「当前版本 x.y.z」这句提示。 */
    val serverVersionRaw: String? = null,
)

/**
 * 单个频道的配置表单。
 *
 * 服务端 `PUT /channels/{channel}` 是整段替换，因此表单以 GET 到的完整配置为底；
 * 密钥字段的原值只用于回写，不进入可渲染文本。
 *
 * 插件缺失时配置 GET 会失败（2.3.0 上回 404），此时状态切到 [ChannelConfigUiState.missingPlugin]，
 * 由安装面板引导安装，而不是把 404 的通用文案当作配置错误。
 */
class ChannelConfigViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    /** 当前实例记录的服务端版本；未接时返回 null，界面按「无法确认」处理。 */
    private val serverVersion: () -> String? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ChannelConfigUiState())
    val state: StateFlow<ChannelConfigUiState> = _state.asStateFlow()

    private var pendingSave: JsonObject? = null
    private var pollJob: Job? = null
    private var reloadJob: Job? = null
    private var repairJob: Job? = null
    private val pollIntervalMs = 2_000L
    private val maxPolls = 300

    fun open(channelKey: String, label: String? = null) {
        val conn = connection() ?: return
        val version = serverVersion()
        val gate = channelVersionGate(version)
        _state.value = ChannelConfigUiState(
            channelKey = channelKey,
            label = label.orEmpty(),
            loading = gate != ChannelVersionGate.UNSUPPORTED,
            loaded = gate == ChannelVersionGate.UNSUPPORTED,
            versionUnsupported = gate == ChannelVersionGate.UNSUPPORTED,
            versionUnknown = gate == ChannelVersionGate.UNKNOWN,
            serverVersionRaw = version,
        )
        if (gate == ChannelVersionGate.UNSUPPORTED) return
        scope.launch { load(conn) }
    }

    /**
     * 读取 schema、配置与运行状态。
     *
     * 配置读取失败时先看 schema 的行状态：缺插件就走安装面板，只有其余失败才交给 [ErrorText]。
     */
    private suspend fun load(conn: Connection) {
        val key = _state.value.channelKey
        val fetched = withContext(Dispatchers.IO) {
            coroutineScope {
                val schemas = async { runCatching { api.channelSchemas(conn) } }
                val config = async { runCatching { api.channel(conn, key) } }
                val health = async { runCatching { api.channelHealth(conn, key) } }
                Triple(schemas.await(), config.await(), health.await())
            }
        }
        val schema = fetched.first.getOrNull()?.get(key)
        val config = fetched.second.getOrNull()
        val capability = channelCapability(key, schema)
        if (config == null) {
            if (capability.missingPlugin) {
                _state.update {
                    it.copy(
                        label = it.label.ifBlank {
                            channelDisplayNameRes(key)?.let(Texts::get) ?: capability.label ?: key
                        },
                        loading = false,
                        loaded = true,
                        error = null,
                        missingPlugin = true,
                        availability = capability.availability,
                        diagnostic = capability.diagnostic,
                        qrAvailable = capability.qrAvailable,
                        repairAction = capability.repairAction,
                        pluginId = capability.pluginId,
                        repairSource = capability.repairSource,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        missingPlugin = false,
                        error = fetched.second.exceptionOrNull()?.let(ErrorText::of)
                            ?: Texts.get(R.string.channel_config_load_failed),
                    )
                }
            }
            return
        }
        val fields = parseChannelFields(schema)
        val defaults = channelDefaults(schema)
        _state.update {
            it.copy(
                label = it.label.ifBlank {
                    channelDisplayNameRes(key)?.let(Texts::get)
                        ?: capability.label
                        ?: key
                },
                fields = fields,
                values = initialChannelValues(fields, config, defaults),
                original = config,
                availability = capability.availability,
                diagnostic = capability.diagnostic,
                qrAvailable = capability.qrAvailable,
                repairAction = capability.repairAction,
                pluginId = capability.pluginId,
                repairSource = capability.repairSource,
                health = fetched.third.getOrNull() ?: it.health,
                loading = false,
                loaded = true,
                missingPlugin = false,
                error = if (fields.isEmpty()) {
                    Texts.get(R.string.channel_config_fields_missing)
                } else {
                    null
                },
            )
        }
    }

    fun setText(name: String, value: String) {
        _state.update {
            it.copy(
                values = it.values.copy(text = it.values.text + (name to value)),
                fieldErrors = it.fieldErrors - name,
            )
        }
    }

    fun setSwitch(name: String, value: Boolean) {
        _state.update {
            it.copy(values = it.values.copy(switches = it.values.switches + (name to value)))
        }
    }

    fun beginSecretReplace(name: String) {
        _state.update {
            val draft = it.values.secrets[name] ?: SecretDraft()
            it.copy(
                values = it.values.copy(
                    secrets = it.values.secrets + (
                        name to draft.copy(replacing = true, clearing = false, replacement = "")
                        ),
                ),
            )
        }
    }

    fun setSecretReplacement(name: String, value: String) {
        _state.update {
            val draft = it.values.secrets[name] ?: SecretDraft()
            it.copy(
                values = it.values.copy(
                    secrets = it.values.secrets + (
                        name to draft.copy(replacing = true, replacement = value)
                        ),
                ),
            )
        }
    }

    fun clearSecret(name: String) {
        _state.update {
            val draft = it.values.secrets[name] ?: SecretDraft()
            it.copy(
                values = it.values.copy(
                    secrets = it.values.secrets + (
                        name to draft.copy(replacing = false, clearing = true, replacement = "")
                        ),
                ),
            )
        }
    }

    fun cancelSecretChange(name: String) {
        _state.update {
            val draft = it.values.secrets[name] ?: SecretDraft()
            it.copy(
                values = it.values.copy(
                    secrets = it.values.secrets + (
                        name to draft.copy(replacing = false, clearing = false, replacement = "")
                        ),
                ),
            )
        }
    }

    fun save() {
        val conn = connection() ?: return
        val current = _state.value
        when (val payload = buildChannelPayload(current.original, current.fields, current.values)) {
            is ChannelPayloadResult.Invalid -> {
                _state.update { it.copy(fieldErrors = payload.errors, error = null) }
            }

            is ChannelPayloadResult.Ok -> {
                // console 停用后手机端就进不去配置，冲突检查与写入都必须用强制启用后的 body。
                checkConflictThenSave(conn, keepConsoleEnabled(current.channelKey, payload.body))
            }
        }
    }

    private fun checkConflictThenSave(conn: Connection, body: JsonObject) {
        val channel = _state.value.channelKey
        _state.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.channelConflictCheck(conn, channel, body) }
            }
            val conflict = result.getOrNull()
            if (conflict?.conflict == true) {
                pendingSave = body
                _state.update { it.copy(saving = false, conflict = conflict) }
                return@launch
            }
            if (result.isFailure) {
                // 冲突检查失败不阻断保存：它只是提示，真正的写入仍以 PUT 的结果为准。
            }
            persist(conn, body)
        }
    }

    fun confirmConflictSave() {
        val conn = connection() ?: return
        val body = pendingSave ?: return
        pendingSave = null
        _state.update { it.copy(conflict = null) }
        persist(conn, body)
    }

    fun dismissConflict() {
        pendingSave = null
        _state.update { it.copy(conflict = null, saving = false) }
    }

    private fun persist(conn: Connection, body: JsonObject) {
        val channel = _state.value.channelKey
        _state.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.updateChannel(conn, channel, body) }
            }
            result.onFailure { e ->
                _state.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
            if (result.isSuccess) {
                _state.update {
                    it.copy(
                        saving = false,
                        notice = Texts.get(R.string.channel_config_saved),
                    )
                }
                reload(conn)
            }
        }
    }

    /** 重新读取表单。安装、保存与二维码写回之后都走这里，判定规则只保留一份。 */
    private fun reload(conn: Connection) {
        reloadJob?.cancel()
        reloadJob = scope.launch { load(conn) }
    }

    fun refreshHealth() {
        val conn = connection() ?: return
        val key = _state.value.channelKey
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.channelHealth(conn, key) }
            }
            _state.update {
                it.copy(health = result.getOrNull() ?: it.health)
            }
        }
    }

    fun requestRestart() {
        _state.update { it.copy(confirmingRestart = true) }
    }

    fun dismissRestart() {
        _state.update { it.copy(confirmingRestart = false) }
    }

    fun confirmRestart() {
        val conn = connection() ?: return
        val key = _state.value.channelKey
        _state.update {
            it.copy(confirmingRestart = false, busy = true, error = null, notice = null)
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.restartChannel(conn, key) }
            }
            _state.update { it.copy(busy = false) }
            result.onFailure { e -> _state.update { it.copy(error = ErrorText.of(e)) } }
            if (result.isSuccess) {
                _state.update {
                    it.copy(notice = Texts.get(R.string.channel_config_restarted))
                }
                refreshHealth()
            }
        }
    }

    /** 打开二维码授权面板。只在 `qrcode_available` 时可用。 */
    fun openQr() {
        if (!_state.value.qrAvailable) return
        val conn = connection() ?: return
        val key = _state.value.channelKey
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(auth = ChannelAuthState(channel = key)) }
        scope.launch {
            val qr = runCatching {
                withContext(Dispatchers.IO) { api.channelQrcode(conn, key) }
            }.getOrElse { e ->
                _state.update {
                    it.copy(
                        auth = it.auth?.copy(
                            phase = ChannelAuthPhase.FAILED,
                            failure = Texts.get(
                                R.string.channel_qr_load_failed,
                                ErrorText.of(e),
                            ),
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

    fun retryQr() = openQr()

    private fun startPolling(conn: Connection, channel: String, pollToken: String) {
        pollJob = scope.launch {
            repeat(maxPolls) {
                delay(pollIntervalMs)
                val auth = _state.value.auth ?: return@launch
                if (auth.channel != channel) return@launch
                val status = runCatching {
                    withContext(Dispatchers.IO) {
                        api.channelQrStatus(conn, channel, pollToken)
                    }
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

    private suspend fun saveCredentials(
        conn: Connection,
        channel: String,
        auth: ChannelAuthState,
        credentials: JsonObject,
    ) {
        _state.update {
            it.copy(auth = auth.copy(phase = ChannelAuthPhase.SAVING))
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val current = api.channel(conn, channel)
                api.updateChannel(conn, channel, mergeChannelCredentials(current, credentials))
            }
        }.onSuccess {
            closeQrSessionQuietly(conn, channel, auth.pollToken)
            _state.update {
                it.copy(
                    auth = null,
                    notice = Texts.get(R.string.channel_qr_enabled, it.label.ifBlank { channel }),
                )
            }
            reload(conn)
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

    /** 关闭面板并停止轮询，同时回收服务端会话。 */
    fun closeQr() {
        val auth = _state.value.auth
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(auth = null) }
        if (auth != null && auth.pollToken.isNotBlank()) {
            val conn = connection() ?: return
            scope.launch { closeQrSessionQuietly(conn, auth.channel, auth.pollToken) }
        }
    }

    private suspend fun closeQrSessionQuietly(
        conn: Connection,
        channel: String,
        pollToken: String,
    ) {
        runCatching {
            withContext(Dispatchers.IO) { api.closeChannelQrcode(conn, channel, pollToken) }
        }
    }

    /** 按 `repair.action` 安装/启用/修复插件；成功后延时复查配置。 */
    fun repairChannel() {
        val current = _state.value
        val action = current.repairAction ?: return
        val conn = connection() ?: return
        if (current.repairing) return
        _state.update { it.copy(repairing = true, error = null, notice = null) }
        // 上一轮安装的复查还在等时重新发起，只保留最新一轮，避免两套复查交错刷新。
        repairJob?.cancel()
        repairJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    when (action) {
                        "install" -> api.installPlugin(
                            conn,
                            current.repairSource ?: error("missing install source"),
                            force = true,
                        )

                        "enable" -> api.setPluginEnabled(
                            conn,
                            current.pluginId ?: error("missing plugin id"),
                            enabled = true,
                        )

                        "repair" -> api.repairPluginDependencies(
                            conn,
                            current.pluginId ?: error("missing plugin id"),
                        )

                        else -> error("unknown repair action")
                    }
                }
            }
            val failure = result.exceptionOrNull()
            // 服务端同一时间只跑一个插件任务；「已有任务在运行」时保留原文并提示等待，
            // 不再进入后面的连续复查，否则只会反复拿到同一条 409。
            val taskRunning = isPluginTaskRunning(failure?.let(::pluginErrorRaw))
            _state.update {
                it.copy(
                    repairing = false,
                    notice = if (result.isSuccess) {
                        when (action) {
                            "install" -> Texts.get(R.string.channels_install_started)
                            "enable" -> Texts.get(R.string.channels_enable_started)
                            else -> Texts.get(R.string.channels_repair_started)
                        }
                    } else {
                        it.notice
                    },
                    error = when {
                        failure == null -> null
                        taskRunning -> repairTaskRunningText(pluginErrorRaw(failure))
                        else -> ErrorText.of(failure)
                    },
                )
            }
            if (result.isSuccess) {
                listOf(2_000L, 2_000L, 2_000L, 5_000L, 5_000L).forEach { wait ->
                    delay(wait)
                    reload(conn)
                }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(error = null, notice = null) }
    }

    fun clear() {
        pollJob?.cancel()
        reloadJob?.cancel()
        repairJob?.cancel()
        _state.value = ChannelConfigUiState()
        pendingSave = null
    }
}
