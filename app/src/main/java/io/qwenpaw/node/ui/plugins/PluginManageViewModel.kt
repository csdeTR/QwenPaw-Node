package io.qwenpaw.node.ui.plugins

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.ApiErrorDetailDto
import io.qwenpaw.node.data.model.PluginListItemDto
import io.qwenpaw.node.data.remote.ApiException
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

private const val EMPTY_CONFIG = "{}"

/** 需要带 `interrupt_tasks=true` 重试的动作。 */
sealed interface PendingPluginAction {
    data class SetEnabled(val enabled: Boolean) : PendingPluginAction
    data object Unload : PendingPluginAction
    data object Delete : PendingPluginAction
    data class SaveConfig(val config: JsonObject, val confirmLegacy: Boolean) : PendingPluginAction
}

/** 409「还有任务在跑」的确认框内容。 */
data class InterruptPrompt(
    val action: PendingPluginAction,
    /** 服务端 detail 里的原话，为空时界面只显示固定说明。 */
    val message: String,
)

/** 409 legacy 卸载钩子的确认框内容。 */
data class LegacyHooksPrompt(val hooks: List<String>)

/**
 * 配置编辑态。
 *
 * 服务端没有读取端点，因此「已保存文本」只能从本地记住：打开编辑器时是 `{}`，
 * 保存成功后是刚提交的那一段。`dirty` 只用来防止误退出，不代表服务端当前值。
 */
data class PluginConfigUi(
    val draft: String = EMPTY_CONFIG,
    val savedText: String = EMPTY_CONFIG,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** 语法检查失败的原因；非空时保存按钮不提交。 */
    val syntaxError: String? = null,
    /** 400 的逐条错误，按服务端顺序展示。 */
    val errors: List<String> = emptyList(),
) {
    val dirty: Boolean get() = draft != savedText
}

/** 插件详情页状态。 */
data class PluginManageUiState(
    val pluginId: String = "",
    val plugin: PluginListItemDto? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val loadError: String? = null,
    /** 任一次写操作进行中。 */
    val acting: Boolean = false,
    val actionError: String? = null,
    val notice: String? = null,
    /** 动作回了 404：当前服务端不支持该操作。 */
    val unsupported: Boolean = false,
    val interruptPrompt: InterruptPrompt? = null,
    val legacyPrompt: LegacyHooksPrompt? = null,
    val config: PluginConfigUi? = null,
    /** 依赖冲突转来的操作 id；界面消费后清空，交给调用方打开依赖升级页。 */
    val openDependencyOperation: String? = null,
    val deleted: Boolean = false,
)

/**
 * 插件详情：启停、卸载、unload、依赖修复与配置写入。
 *
 * 所有动作只作用于当前 [PluginManageUiState.pluginId]。动作成功后重新拉详情，
 * 因为服务端的 `enabled/loaded/status` 只有它说了算，本地改一个布尔值会和真实状态分叉。
 */
class PluginManageViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PluginManageUiState())
    val state: StateFlow<PluginManageUiState> = _state.asStateFlow()

    /** 导航入口：进入某个插件的管理页。换插件时重置全部状态，避免带着上一个插件的配置草稿。 */
    fun open(pluginId: String) {
        if (_state.value.pluginId == pluginId && (_state.value.loaded || _state.value.loading)) return
        _state.value = PluginManageUiState(pluginId = pluginId, loading = true)
        refresh()
    }

    /** 已经加载过就不再拉；供页面首次组合调用，重试入口直接调 [refresh]。 */
    fun ensureLoaded() {
        val current = _state.value
        if (current.pluginId.isBlank() || current.loaded || current.loading) return
        refresh()
    }

    fun refresh() {
        val pluginId = _state.value.pluginId
        if (pluginId.isBlank()) return
        val conn = connection() ?: run {
            _state.update {
                it.copy(
                    loading = false,
                    loaded = true,
                    loadError = Texts.get(R.string.plugin_admin_error_not_connected),
                )
            }
            return
        }
        _state.update { it.copy(loading = true, loadError = null) }
        scope.launch {
            val detail = runCatching { withContext(Dispatchers.IO) { api.pluginDetail(conn, pluginId) } }
            val resolved = detail.getOrNull() ?: detail.exceptionOrNull()
                ?.takeIf { it is ApiException && it.code == 404 }
                ?.let { loadFromList(conn, pluginId) }
            _state.update { current ->
                when {
                    resolved != null -> current.copy(
                        plugin = resolved,
                        loading = false,
                        loaded = true,
                        loadError = null,
                    )

                    else -> current.copy(
                        loading = false,
                        loaded = true,
                        loadError = ErrorText.of(detail.exceptionOrNull()!!),
                    )
                }
            }
        }
    }

    /**
     * 详情路由不存在时退回列表里那一条。
     *
     * `GET /api/plugins/{id}` 是 2.3.0.post1 才有的路由，旧版本回 404；列表端点一直都在，
     * 用列表数据仍能看到名称与启停，只是少了详情独有的字段。
     */
    private suspend fun loadFromList(conn: Connection, pluginId: String): PluginListItemDto? =
        runCatching {
            withContext(Dispatchers.IO) { api.plugins(conn) }
                .firstOrNull { it.id == pluginId }
        }.getOrNull()

    // ---------- 启停与卸载 ----------

    fun setEnabled(enabled: Boolean) = runAction(PendingPluginAction.SetEnabled(enabled))

    fun unload() = runAction(PendingPluginAction.Unload)

    fun delete() = runAction(PendingPluginAction.Delete)

    /** 确认「中断运行中任务后重试」：同一动作带 `interrupt_tasks=true` 再走一遍。 */
    fun confirmInterrupt() {
        val action = _state.value.interruptPrompt?.action ?: return
        _state.update { it.copy(interruptPrompt = null) }
        runAction(action, interruptTasks = true)
    }

    fun dismissInterrupt() {
        _state.update { it.copy(interruptPrompt = null) }
    }

    private fun runAction(action: PendingPluginAction, interruptTasks: Boolean = false) {
        if (_state.value.acting) return
        val pluginId = _state.value.pluginId
        if (pluginId.isBlank()) return
        val conn = connection() ?: return
        _state.update { it.copy(acting = true, actionError = null, unsupported = false) }
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    when (action) {
                        is PendingPluginAction.SetEnabled -> api.setPluginEnabled(
                            conn = conn,
                            pluginId = pluginId,
                            enabled = action.enabled,
                            interruptTasks = interruptTasks,
                        )

                        PendingPluginAction.Unload ->
                            api.unloadPlugin(conn, pluginId, interruptTasks)

                        PendingPluginAction.Delete ->
                            api.deletePlugin(conn, pluginId, interruptTasks)

                        is PendingPluginAction.SaveConfig -> api.updatePluginConfig(
                            conn = conn,
                            pluginId = pluginId,
                            config = action.config,
                            confirmLegacy = action.confirmLegacy,
                            interruptTasks = interruptTasks,
                        )
                    }
                }
            }
            _state.update { it.copy(acting = false) }
            outcome.onSuccess { handleSuccess(action) }
            outcome.onFailure { handleFailure(action, it) }
        }
    }

    private fun handleSuccess(action: PendingPluginAction) {
        when (action) {
            is PendingPluginAction.SetEnabled -> {
                _state.update {
                    it.copy(notice = Texts.get(R.string.plugin_admin_notice_enabled_changed))
                }
                refresh()
            }

            PendingPluginAction.Unload -> {
                _state.update { it.copy(notice = Texts.get(R.string.plugin_admin_notice_unloaded)) }
                refresh()
            }

            PendingPluginAction.Delete -> {
                // 删除后就地清空：再拉详情只会得到 404，看起来像插件「消失了」而不是「删掉了」。
                _state.update {
                    it.copy(
                        plugin = null,
                        deleted = true,
                        loaded = true,
                        config = null,
                        notice = Texts.get(R.string.plugin_admin_notice_deleted),
                    )
                }
            }

            is PendingPluginAction.SaveConfig -> {
                _state.update { current ->
                    val config = current.config ?: return@update current
                    current.copy(
                        config = config.copy(
                            saving = false,
                            saved = true,
                            savedText = config.draft,
                            syntaxError = null,
                            errors = emptyList(),
                        ),
                        notice = Texts.get(R.string.plugin_admin_notice_config_saved),
                    )
                }
            }
        }
    }

    private fun handleFailure(action: PendingPluginAction, error: Throwable) {
        if (error !is ApiException) {
            _state.update { it.copy(actionError = ErrorText.of(error)) }
            return
        }
        val detail = api.apiErrorDetailOf(error)
        when (error.code) {
            404 -> {
                // 配置未变化是 404 的正常分支，不能归成「不支持」。
                if (isConfigUnchanged(detail)) {
                    _state.update { current ->
                        val config = current.config ?: return@update current
                        current.copy(
                            config = config.copy(
                                saving = false,
                                saved = false,
                                savedText = config.draft,
                            ),
                            notice = Texts.get(R.string.plugin_admin_notice_config_unchanged),
                        )
                    }
                    return
                }
                _state.update {
                    it.copy(
                        unsupported = true,
                        actionError = null,
                        config = it.config?.copy(saving = false),
                    )
                }
            }

            400 -> {
                val errors = configErrorsOf(detail)
                _state.update { current ->
                    current.copy(
                        actionError = errors.firstOrNull() ?: ErrorText.of(error),
                        config = current.config?.copy(saving = false, errors = errors),
                    )
                }
            }

            409 -> handleConflict(action, detail, error)

            else -> _state.update {
                it.copy(
                    actionError = ErrorText.of(error),
                    config = it.config?.copy(saving = false),
                )
            }
        }
    }

    private fun handleConflict(
        action: PendingPluginAction,
        detail: ApiErrorDetailDto?,
        error: ApiException,
    ) {
        val message = detail?.message?.takeIf { it.isNotBlank() } ?: ErrorText.of(error)
        when (val conflict = pluginConflictOf(detail)) {
            is PluginConflict.DependencyUpgrade -> {
                if (conflict.operationId.isNotBlank()) {
                    _state.update {
                        it.copy(
                            openDependencyOperation = conflict.operationId,
                            config = it.config?.copy(saving = false),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            actionError = message,
                            config = it.config?.copy(saving = false),
                        )
                    }
                }
            }

            is PluginConflict.LegacyHooks -> _state.update {
                it.copy(
                    legacyPrompt = LegacyHooksPrompt(conflict.hooks),
                    config = it.config?.copy(saving = false),
                )
            }

            // 除依赖升级与 legacy 钩子之外，409 都按「还有任务在跑」处理：
            // 服务端在这条路径上回的 detail 只保证有 message，逐字段猜形状会漏掉重试入口。
            is PluginConflict.RunningTasks, is PluginConflict.Other -> _state.update {
                it.copy(
                    interruptPrompt = InterruptPrompt(action, message),
                    config = it.config?.copy(saving = false),
                )
            }
        }
    }

    /** 依赖修复。需要升级时服务端回 409 并在 detail 里给 operation，转给依赖升级页。 */
    fun repairDependencies() {
        if (_state.value.acting) return
        val pluginId = _state.value.pluginId
        if (pluginId.isBlank()) return
        val conn = connection() ?: return
        _state.update { it.copy(acting = true, actionError = null, unsupported = false) }
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { api.repairPluginDependencies(conn, pluginId) }
            }
            _state.update { it.copy(acting = false) }
            outcome.onSuccess {
                _state.update {
                    it.copy(notice = Texts.get(R.string.plugin_admin_notice_repair_requested))
                }
                refresh()
            }
            outcome.onFailure { error ->
                if (error is ApiException) {
                    val detail = api.apiErrorDetailOf(error)
                    val conflict = pluginConflictOf(detail)
                    val message = detail?.message?.takeIf { it.isNotBlank() }
                        ?: ErrorText.of(error)
                    when {
                        error.code == 404 -> _state.update { it.copy(unsupported = true) }
                        // 修复动作没有 interrupt_tasks 参数，只有依赖升级可以交给升级页。
                        error.code == 409 && conflict is PluginConflict.DependencyUpgrade &&
                            conflict.operationId.isNotBlank() -> _state.update {
                            it.copy(openDependencyOperation = conflict.operationId)
                        }

                        error.code == 409 -> _state.update { it.copy(actionError = message) }
                        else -> _state.update { it.copy(actionError = message) }
                    }
                } else {
                    _state.update { it.copy(actionError = ErrorText.of(error)) }
                }
            }
        }
    }

    /** 界面消费掉依赖操作 id 后调用，避免返回本页时重复跳转。 */
    fun consumeOpenDependency() {
        _state.update { it.copy(openDependencyOperation = null) }
    }

    // ---------- 配置编辑 ----------

    fun beginConfigEdit() {
        // 从上次提交的文本继续，而不是每次都退回 `{}`：服务端读不到，用户输入过的那份
        // 是唯一还能拿到的副本。
        _state.update { current ->
            val base = current.config?.savedText ?: EMPTY_CONFIG
            current.copy(config = PluginConfigUi(draft = base, savedText = base))
        }
    }

    fun updateConfigDraft(text: String) {
        _state.update { current ->
            val config = current.config ?: return@update current
            current.copy(
                config = config.copy(
                    draft = text,
                    syntaxError = if (isConfigDraftValid(text)) {
                        null
                    } else {
                        Texts.get(R.string.plugin_admin_error_config_syntax)
                    },
                    saved = false,
                    errors = emptyList(),
                ),
            )
        }
    }

    /** 保存：语法不过就不提交，返回 false 让界面别弹确认框。 */
    fun saveConfig(confirmLegacy: Boolean = false): Boolean {
        // 另一个动作还没结束时不排队：`runAction` 会直接返回，留着 saving 会卡死保存按钮。
        if (_state.value.acting) return false
        val config = _state.value.config ?: return false
        val parsed = configObjectOf(config.draft) ?: run {
            updateConfigDraft(config.draft)
            return false
        }
        _state.update { current ->
            current.copy(
                config = current.config?.copy(saving = true, saved = false, errors = emptyList()),
                legacyPrompt = null,
            )
        }
        runAction(PendingPluginAction.SaveConfig(parsed, confirmLegacy))
        return true
    }

    fun confirmLegacyHooks() {
        if (_state.value.legacyPrompt == null) return
        _state.update { it.copy(legacyPrompt = null) }
        if (_state.value.config == null) return
        saveConfig(confirmLegacy = true)
    }

    fun dismissLegacyHooks() {
        _state.update { it.copy(legacyPrompt = null) }
    }

    fun cancelConfigEdit() {
        _state.update { it.copy(config = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, actionError = null, unsupported = false) }
    }

    fun clear() {
        _state.value = PluginManageUiState()
    }

    companion object {
        /** 服务端没有读取端点，配置编辑从空对象开始。 */
        const val EMPTY_CONFIG = "{}"
    }
}
