package io.qwenpaw.node.ui.channels

import io.qwenpaw.node.R
import io.qwenpaw.node.data.channel.ChannelCapability
import io.qwenpaw.node.data.channel.channelCapability
import io.qwenpaw.node.data.channel.jsonBoolean
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
    /** `missing_code` / `disabled` / `validation_pending` / `validated` / `running`；2.2.2 为 null。 */
    val availability: String?,
    /** `install` / `enable` / `repair`；2.2.2 与运行中的频道为 null。 */
    val repairAction: String?,
    val pluginId: String?,
    val repairSource: String?,
    /** 失败诊断原文。 */
    val diagnostic: String?,
) {
    /** 需要安装/启用/修复时才显示动作按钮。 */
    val needsRepair: Boolean get() = repairAction != null
}

data class ChannelUiState(
    val channels: List<ChannelRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    /** 正在执行安装/启用/修复的频道键。 */
    val busyChannel: String? = null,
    /** 一次性提示；界面取走后调用 [ChannelViewModel.consumeNotice]。 */
    val noticeRes: Int? = null,
)

/**
 * 频道列表与插件安装/启用/修复。
 *
 * 2.3.0 起频道实现插件化：`repair.action` 分三态——`install`（未安装）、
 * `enable`（已安装但停用）、`repair`（已安装但校验未通过）。三种动作分别走
 * 插件安装、启停与依赖修复，不能一律当作安装。
 *
 * 二维码授权在频道配置页（[ChannelConfigViewModel]），列表页不再提供入口。
 */
class ChannelViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()
    private var retryJob: Job? = null

    fun refresh(force: Boolean = false) {
        if (!force && (_state.value.loaded || _state.value.loading)) return
        val conn = connection() ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    val channels = api.channels(conn)
                    // schemas 是能力来源；2.2.2 上失败或字段缺失都不能挡住列表。
                    val schemas = runCatching { api.channelSchemas(conn) }.getOrDefault(emptyMap())
                    channels to schemas
                }
            }
            fetched.onSuccess { (channels, schemas) ->
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
     * 排序：已启用 → 需要修复（未安装/停用）→ 可扫码 → 其余，再按显示名。
     * 已启用的频道是用户最常看的，不再沉在字典序里。
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
                repairAction = capability.repairAction,
                pluginId = capability.pluginId,
                repairSource = capability.repairSource,
                diagnostic = capability.diagnostic,
            )
        }
        return rows.sortedWith(
            compareBy(
                { if (it.enabled) 0 else 1 },
                { if (it.needsRepair) 0 else 1 },
                { if (it.qrAvailable) 0 else 1 },
                { (it.label ?: it.key).lowercase() },
            ),
        )
    }

    /**
     * 按 `repair.action` 执行安装/启用/修复。
     *
     * 安装成功不代表频道立即可用：服务端还要下载插件、校验配置并注册频道，
     * 因此成功后做 2s×3 + 5s×2 的复查，直到状态离开待处理集合。
     */
    fun repairChannel(key: String) {
        val row = _state.value.channels.firstOrNull { it.key == key } ?: return
        val action = row.repairAction ?: return
        val conn = connection() ?: return
        if (_state.value.busyChannel != null) return
        _state.update { it.copy(busyChannel = key, error = null, noticeRes = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    when (action) {
                        "install" -> api.installPlugin(
                            conn,
                            row.repairSource ?: error("missing install source"),
                            force = true,
                        )

                        "enable" -> api.setPluginEnabled(
                            conn,
                            row.pluginId ?: error("missing plugin id"),
                            enabled = true,
                        )

                        "repair" -> api.repairPluginDependencies(
                            conn,
                            row.pluginId ?: error("missing plugin id"),
                        )

                        else -> error("unknown repair action")
                    }
                }
            }
            result.onFailure { e ->
                _state.update {
                    it.copy(busyChannel = null, error = ErrorText.of(e))
                }
            }
            if (result.isSuccess) {
                _state.update {
                    it.copy(
                        busyChannel = null,
                        noticeRes = when (action) {
                            "install" -> R.string.channels_install_started
                            "enable" -> R.string.channels_enable_started
                            else -> R.string.channels_repair_started
                        },
                    )
                }
                refresh(force = true)
                retryUntilSettled(key)
            }
        }
    }

    private fun retryUntilSettled(key: String) {
        retryJob?.cancel()
        retryJob = scope.launch {
            val delays = listOf(2_000L, 2_000L, 2_000L, 5_000L, 5_000L)
            delays.forEach { wait ->
                delay(wait)
                refresh(force = true)
                val row = _state.value.channels.firstOrNull { it.key == key } ?: return@launch
                val pending = row.availability in
                    setOf("missing_code", "disabled", "validation_pending", "failed")
                if (!pending) return@launch
            }
        }
    }

    fun consumeNotice() {
        _state.update { it.copy(noticeRes = null) }
    }

    fun clear() {
        retryJob?.cancel()
        _state.value = ChannelUiState()
    }
}