package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.DenyPathsProtectionDto
import io.qwenpaw.node.data.model.EnvCatalogDto
import io.qwenpaw.node.data.model.EnvVarDto
import io.qwenpaw.node.data.model.FileGuardDto
import io.qwenpaw.node.data.model.OffloadPolicyDto
import io.qwenpaw.node.data.model.SandboxStatusDto
import io.qwenpaw.node.data.model.ToolDto
import io.qwenpaw.node.data.model.ToolGuardRuleDto
import io.qwenpaw.node.data.model.TranscriptionProviderDto
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 工具页状态。 */
data class ToolsUiState(
    val tools: List<ToolDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

private const val AUTH_ENABLED_KEY = "QWENPAW_AUTH_ENABLED"

/** 环境变量页状态。`catalog` 用来区分内置变量（只能重置）与只读项。 */
data class EnvsUiState(
    val vars: List<EnvVarDto> = emptyList(),
    val catalog: List<EnvCatalogDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** 后台策略页状态。 */
data class OffloadUiState(
    val action: String = KEEP_FOREGROUND,
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** 运行配置页状态。原始 JSON 必须保留，保存时整份回写。 */
data class RunningConfigUiState(
    val raw: JsonObject? = null,
    val form: RunningConfigForm = RunningConfigForm(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val loaded: Boolean = false,
)

/** 安全页状态。四组配置各自保留原始形状，只改开关或模式。 */
data class SecurityUiState(
    val toolGuard: JsonObject? = null,
    val toolGuardEnabled: Boolean = false,
    val rules: List<ToolGuardRuleDto> = emptyList(),
    val fileGuard: FileGuardDto? = null,
    val sandbox: SandboxStatusDto? = null,
    val denyPaths: DenyPathsProtectionDto? = null,
    val skillScanner: JsonObject? = null,
    val skillScannerMode: String = SKILL_SCANNER_WARN,
    /** 技能扫描拦截历史。 */
    val blockedHistory: kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(emptyList()),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val loaded: Boolean = false,
)

/** 服务端级设置：Web 控制台语言与上传上限。 */
data class ServerSettingsUiState(
    val language: String = "en",
    val uploadLimitMb: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** 语音服务端设置：入站音频模式、转写档位、provider 与模型名。 */
data class VoiceServerUiState(
    val audioMode: String = "auto",
    val providerType: String = "disabled",
    val configuredProviderId: String = "",
    val providers: List<TranscriptionProviderDto> = emptyList(),
    val model: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val loaded: Boolean = false,
)

internal const val SKILL_SCANNER_BLOCK = "block"
internal const val SKILL_SCANNER_WARN = "warn"
internal const val SKILL_SCANNER_OFF = "off"

/**
 * 设置页下三张配置表单的状态与动作。
 *
 * 三项都是「小表单」，放在一个对象里而不是三个 ViewModel：它们共用同一个连接，
 * 生命周期也一致（进入设置页时按需拉取）。页面只读状态、调动作，不自己发请求。
 */
class SettingsConfigViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tools = MutableStateFlow(ToolsUiState())
    val tools: StateFlow<ToolsUiState> = _tools.asStateFlow()

    private val _envs = MutableStateFlow(EnvsUiState())
    val envs: StateFlow<EnvsUiState> = _envs.asStateFlow()

    private val _offload = MutableStateFlow(OffloadUiState())
    val offload: StateFlow<OffloadUiState> = _offload.asStateFlow()

    private val _runningConfig = MutableStateFlow(RunningConfigUiState())
    val runningConfig: StateFlow<RunningConfigUiState> = _runningConfig.asStateFlow()

    private val _security = MutableStateFlow(SecurityUiState())
    val security: StateFlow<SecurityUiState> = _security.asStateFlow()

    private val _serverSettings = MutableStateFlow(ServerSettingsUiState())
    val serverSettings: StateFlow<ServerSettingsUiState> = _serverSettings.asStateFlow()

    private val _voice = MutableStateFlow(VoiceServerUiState())
    val voice: StateFlow<VoiceServerUiState> = _voice.asStateFlow()

    // ---------------- 工具 ----------------

    fun loadTools(force: Boolean = false) {
        if (!force && (_tools.value.loaded || _tools.value.loading)) return
        val conn = connection() ?: return
        _tools.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.tools(conn) } }
                .onSuccess { list ->
                    _tools.value = ToolsUiState(tools = list, loading = false, loaded = true)
                }
                .onFailure { e ->
                    _tools.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
                }
        }
    }

    /**
     * 切换启用状态。
     *
     * 端点是「取反」语义且没有请求体，因此结果以服务端返回的对象为准，
     * 不在本地先行翻转——本地点两下会和真实状态错位。
     */
    fun toggleTool(name: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.toggleTool(conn, name) } }
                .onSuccess { updated -> replaceTool(updated) }
                .onFailure { e -> _tools.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    fun setToolAsync(name: String, enabled: Boolean) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setToolAsync(conn, name, enabled) } }
                .onSuccess { updated -> replaceTool(updated) }
                .onFailure { e -> _tools.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    /**
     * 保存工具配置。
     *
     * 表单给的是字符串，但服务端的配置值有数字与布尔类型，因此按字段声明的
     * `type` 转回对应 JSON 类型；无法转换时退回字符串，不静默丢值。
     */
    fun saveToolConfig(name: String, values: Map<String, String>) {
        val conn = connection() ?: return
        val fields = _tools.value.tools.firstOrNull { it.name == name }?.configFields.orEmpty()
            .associateBy { it.name }
        val payload = values.mapValues { (key, text) ->
            when (fields[key]?.type) {
                "number" -> text.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text)
                "boolean" -> JsonPrimitive(text.equals("true", ignoreCase = true))
                else -> JsonPrimitive(text)
            }
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.saveToolConfig(conn, name, payload) } }
                .onSuccess {
                    _tools.update { it.copy(error = null) }
                    // 保存后重拉一次：服务端会把密码字段重新打成 `***`，本地缓存的旧值要跟上。
                    loadTools(force = true)
                }
                .onFailure { e -> _tools.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    private fun replaceTool(updated: ToolDto) {
        _tools.update { s ->
            s.copy(tools = s.tools.map { if (it.name == updated.name) updated else it })
        }
    }

    // ---------------- 环境变量 ----------------

    fun loadEnvs(force: Boolean = false) {
        if (!force && (_envs.value.loaded || _envs.value.loading)) return
        val conn = connection() ?: return
        _envs.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val vars = api.envVars(conn)
                    // 规格表拿不到不影响读写，只影响「内置变量只能重置」的判定，
                    // 因此单独兜底，不让它把整页拖成错误态。
                    val catalog = runCatching { api.envCatalog(conn) }.getOrDefault(emptyList())
                    vars to catalog
                }
            }.onSuccess { (vars, catalog) ->
                _envs.value = EnvsUiState(vars = vars, catalog = catalog, loaded = true)
            }.onFailure { e ->
                _envs.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
            }
        }
    }

    /** 合并保存。PATCH 不删除未提交的项，因此只传改动的那几条。 */
    fun saveEnvs(values: Map<String, String>) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.patchEnvVars(conn, values) } }
                .onSuccess { vars -> _envs.update { it.copy(vars = vars, error = null) } }
                .onFailure { e -> _envs.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    /**
     * 保存 `QWENPAW_AUTH_ENABLED`。
     *
     * 该键不在 catalog 里，PUT 是实测可行的写入路径；PUT 会整体替换，
     * 因此把当前全部变量合并后再提交。空值表示删除。
     */
    fun saveAuthEnabled(value: String) {
        val conn = connection() ?: return
        // 未加载完成时 vars 为空，PUT 会删掉其他变量；此时不保存。
        if (!_envs.value.loaded) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (value.isBlank()) {
                        api.deleteEnvVar(conn, AUTH_ENABLED_KEY)
                    } else {
                        val current = _envs.value.vars.associate { it.key to it.value }
                        api.putEnvVars(conn, current + (AUTH_ENABLED_KEY to value))
                    }
                }
            }.onSuccess { vars -> _envs.update { it.copy(vars = vars, error = null) } }
                .onFailure { e -> _envs.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    fun deleteEnv(key: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.deleteEnvVar(conn, key) } }
                .onSuccess { vars -> _envs.update { it.copy(vars = vars, error = null) } }
                .onFailure { e -> _envs.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    fun resetEnv(key: String) {
        val conn = connection() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.resetEnvVar(conn, key) } }
                .onSuccess { vars -> _envs.update { it.copy(vars = vars, error = null) } }
                .onFailure { e -> _envs.update { it.copy(error = ErrorText.of(e)) } }
        }
    }

    // ---------------- 后台策略 ----------------

    fun loadOffload(force: Boolean = false) {
        if (!force && (_offload.value.loaded || _offload.value.loading)) return
        val conn = connection() ?: return
        _offload.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.offloadPolicy(conn) } }
                .onSuccess { policy: OffloadPolicyDto ->
                    _offload.value = OffloadUiState(action = policy.defaultAction, loaded = true)
                }
                .onFailure { e ->
                    _offload.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
                }
        }
    }

    fun setOffload(action: String) {
        val conn = connection() ?: return
        _offload.update { it.copy(action = action, loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setOffloadPolicy(conn, action) } }
                .onSuccess { policy ->
                    _offload.value = OffloadUiState(action = policy.defaultAction, loaded = true)
                }
                .onFailure { e ->
                    // 写失败要把开关拨回去，否则界面显示的是未生效的值。
                    _offload.update { it.copy(loading = false, error = ErrorText.of(e)) }
                    loadOffload(force = true)
                }
        }
    }

    // ---------------- 运行配置 ----------------

    fun loadRunningConfig(force: Boolean = false) {
        if (!force && (_runningConfig.value.loaded || _runningConfig.value.loading)) return
        val conn = connection() ?: return
        _runningConfig.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.runningConfig(conn) } }
                .onSuccess { raw ->
                    _runningConfig.value = RunningConfigUiState(
                        raw = raw,
                        form = RunningConfigEdit.fromJson(raw),
                        loaded = true,
                    )
                }
                .onFailure { e ->
                    _runningConfig.update {
                        it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                    }
                }
        }
    }

    fun saveRunningConfig(form: RunningConfigForm) {
        RunningConfigEdit.validate(form)?.let { message ->
            _runningConfig.update { it.copy(error = message, notice = null) }
            return
        }
        val conn = connection() ?: return
        val raw = _runningConfig.value.raw ?: return
        _runningConfig.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.saveRunningConfig(conn, RunningConfigEdit.apply(raw, form))
                }
            }.onSuccess { saved ->
                _runningConfig.value = RunningConfigUiState(
                    raw = saved,
                    form = RunningConfigEdit.fromJson(saved),
                    loaded = true,
                    notice = Texts.get(R.string.cfgvm_saved),
                )
            }.onFailure { e ->
                _runningConfig.update {
                    it.copy(saving = false, error = ErrorText.of(e))
                }
            }
        }
    }

    fun dismissRunningNotice() {
        _runningConfig.update { it.copy(notice = null, error = null) }
    }

    // ---------------- 安全 ----------------

    fun loadSecurity(force: Boolean = false) {
        if (!force && (_security.value.loaded || _security.value.loading)) return
        val conn = connection() ?: return
        _security.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val toolGuard = api.toolGuard(conn)
                    // 规则表拿不到不影响开关；单独兜底，避免整页变成错误态。
                    val rules = runCatching { api.toolGuardBuiltinRules(conn) }
                        .getOrDefault(emptyList<io.qwenpaw.node.data.model.ToolGuardRuleDto>())
                    SecuritySnapshot(
                        toolGuard = toolGuard,
                        rules = rules,
                        fileGuard = api.fileGuard(conn),
                        sandbox = api.sandboxStatus(conn),
                        // 2.2.2 没有这个端点；取不到只影响这一行，不拖垮整页安全配置。
                        denyPaths = runCatching { api.denyPathsProtection(conn) }.getOrNull(),
                        skillScanner = api.skillScanner(conn),
                        blockedHistory = runCatching { api.blockedSkillHistory(conn) }
                            .getOrDefault(kotlinx.serialization.json.JsonArray(emptyList())),
                    )
                }
            }.onSuccess { snap ->
                _security.value = SecurityUiState(
                    toolGuard = snap.toolGuard,
                    toolGuardEnabled = snap.toolGuard["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    rules = snap.rules,
                    fileGuard = snap.fileGuard,
                    sandbox = snap.sandbox,
                    denyPaths = snap.denyPaths,
                    skillScanner = snap.skillScanner,
                    blockedHistory = snap.blockedHistory,
                    skillScannerMode = snap.skillScanner["mode"]?.jsonPrimitive?.contentOrNull
                        ?: SKILL_SCANNER_WARN,
                    loaded = true,
                )
            }.onFailure { e ->
                _security.update {
                    it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                }
            }
        }
    }

    fun saveToolGuardEnabled(enabled: Boolean) {
        val conn = connection() ?: return
        val raw = _security.value.toolGuard ?: return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.saveToolGuard(conn, raw.with("enabled", JsonPrimitive(enabled)))
                }
            }.onSuccess { saved ->
                _security.update {
                    it.copy(
                        toolGuard = saved,
                        toolGuardEnabled = saved["enabled"]?.jsonPrimitive?.booleanOrNull ?: enabled,
                        saving = false,
                        notice = if (enabled) {
                            Texts.get(R.string.cfgvm_tool_guard_on)
                        } else {
                            Texts.get(R.string.cfgvm_tool_guard_off)
                        },
                    )
                }
            }.onFailure { e ->
                _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun saveFileGuardEnabled(enabled: Boolean) {
        val conn = connection() ?: return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setFileGuardEnabled(conn, enabled) } }
                .onSuccess { guard ->
                    _security.update {
                        it.copy(
                            fileGuard = guard,
                            saving = false,
                            notice = if (enabled) {
                                Texts.get(R.string.cfgvm_file_guard_on)
                            } else {
                                Texts.get(R.string.cfgvm_file_guard_off)
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
                }
        }
    }

    fun saveSandboxEnabled(enabled: Boolean) {
        val conn = connection() ?: return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setSandboxEnabled(conn, enabled) } }
                .onSuccess { status ->
                    _security.update {
                        it.copy(
                            sandbox = status,
                            saving = false,
                            notice = if (enabled) {
                                Texts.get(R.string.cfgvm_sandbox_on)
                            } else {
                                Texts.get(R.string.cfgvm_sandbox_off)
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
                }
        }
    }

    /** 拒绝路径保护依赖 Windows ACL；其他平台保存会原样返回 `platformSupported=false`。 */
    fun saveDenyPathsProtection(enabled: Boolean) {
        val conn = connection() ?: return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setDenyPathsProtection(conn, enabled) }
            }.onSuccess { status ->
                _security.update {
                    it.copy(
                        denyPaths = status,
                        saving = false,
                        notice = Texts.get(
                            if (enabled) {
                                R.string.cfgvm_deny_paths_on
                            } else {
                                R.string.cfgvm_deny_paths_off
                            },
                        ),
                    )
                }
            }.onFailure { e ->
                _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun saveSkillScannerMode(mode: String) {
        val conn = connection() ?: return
        val raw = _security.value.skillScanner ?: return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.saveSkillScanner(conn, raw.with("mode", JsonPrimitive(mode)))
                }
            }.onSuccess { saved ->
                _security.update {
                    it.copy(
                        skillScanner = saved,
                        skillScannerMode = saved["mode"]?.jsonPrimitive?.contentOrNull ?: mode,
                        saving = false,
                        notice = Texts.get(R.string.cfgvm_skill_scanner_updated),
                    )
                }
            }.onFailure { e ->
                _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun dismissSecurityNotice() {
        _security.update { it.copy(notice = null, error = null) }
    }

    fun clearBlockedSkillHistory() {
        val conn = connection() ?: return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.clearBlockedSkillHistory(conn) }
            }.onSuccess {
                _security.update {
                    it.copy(
                        blockedHistory = kotlinx.serialization.json.JsonArray(emptyList()),
                        saving = false,
                        notice = Texts.get(R.string.security_scanner_history_cleared),
                    )
                }
            }.onFailure { e ->
                _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun removeBlockedSkillEntry(index: Int) {
        val conn = connection() ?: return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.removeBlockedSkillEntry(conn, index) }
            }.onSuccess {
                val history = _security.value.blockedHistory
                    .filterIndexed { i, _ -> i != index }
                _security.update {
                    it.copy(
                        blockedHistory = kotlinx.serialization.json.JsonArray(history),
                        saving = false,
                    )
                }
            }.onFailure { e ->
                _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun addSkillWhitelist(name: String) {
        val conn = connection() ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.addSkillWhitelist(conn, trimmed) }
            }.onSuccess {
                val scanner = runCatching {
                    withContext(Dispatchers.IO) { api.skillScanner(conn) }
                }.getOrDefault(_security.value.skillScanner ?: JsonObject(emptyMap()))
                _security.update {
                    it.copy(
                        skillScanner = scanner,
                        saving = false,
                        notice = Texts.get(R.string.security_scanner_whitelist_added, trimmed),
                    )
                }
            }.onFailure { e ->
                _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun removeSkillWhitelist(name: String) {
        val conn = connection() ?: return
        _security.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.removeSkillWhitelist(conn, name) }
            }.onSuccess {
                val scanner = runCatching {
                    withContext(Dispatchers.IO) { api.skillScanner(conn) }
                }.getOrDefault(_security.value.skillScanner ?: JsonObject(emptyMap()))
                _security.update {
                    it.copy(
                        skillScanner = scanner,
                        saving = false,
                        notice = Texts.get(R.string.security_scanner_whitelist_removed, name),
                    )
                }
            }.onFailure { e ->
                _security.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    // ---------------- 语音（服务端） ----------------

    /**
     * 入站音频模式、转写档位、provider 与模型名。
     *
     * 读取四项彼此独立；任一项失败就整块显示错误，避免用户以为另外几项保存成功。
     */
    fun loadVoiceSettings(force: Boolean = false) {
        if (!force && (_voice.value.loaded || _voice.value.loading)) return
        val conn = connection() ?: return
        _voice.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val audio = api.audioMode(conn)
                    val type = api.transcriptionProviderType(conn)
                    val providers = api.transcriptionProviders(conn)
                    val model = api.transcriptionModel(conn)
                    VoiceSnapshot(audio.audioMode, type.providerType, providers, model.model)
                }
            }.onSuccess { snap ->
                _voice.value = VoiceServerUiState(
                    audioMode = snap.audioMode,
                    providerType = snap.providerType,
                    configuredProviderId = snap.providers.configuredProviderId,
                    providers = snap.providers.providers,
                    model = snap.model,
                    loaded = true,
                )
            }.onFailure { e ->
                _voice.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
            }
        }
    }

    fun setAudioMode(mode: String) {
        val conn = connection() ?: return
        _voice.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setAudioMode(conn, mode) } }
                .onSuccess { saved ->
                    _voice.update { it.copy(audioMode = saved.audioMode, saving = false) }
                }
                .onFailure { e -> _voice.update { it.copy(saving = false, error = ErrorText.of(e)) } }
        }
    }

    fun setTranscriptionProviderType(type: String) {
        val conn = connection() ?: return
        _voice.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setTranscriptionProviderType(conn, type) }
            }.onSuccess { saved ->
                _voice.update { it.copy(providerType = saved.providerType, saving = false) }
            }.onFailure { e ->
                _voice.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun setTranscriptionProvider(providerId: String) {
        val conn = connection() ?: return
        _voice.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setTranscriptionProvider(conn, providerId) }
            }.onSuccess {
                _voice.update { it.copy(configuredProviderId = providerId, saving = false) }
            }.onFailure { e ->
                _voice.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun setTranscriptionModel(model: String) {
        val conn = connection() ?: return
        _voice.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setTranscriptionModel(conn, model) }
            }.onSuccess { saved ->
                _voice.update { it.copy(model = saved.model, saving = false) }
            }.onFailure { e ->
                _voice.update { it.copy(saving = false, error = ErrorText.of(e)) }
            }
        }
    }

    // ---------------- 服务端设置（语言 / 上传上限） ----------------

    fun loadServerSettings(force: Boolean = false) {
        if (!force && (_serverSettings.value.loaded || _serverSettings.value.loading)) return
        val conn = connection() ?: return
        _serverSettings.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val language = api.serverLanguage(conn)
                    // 上传上限可能为 null（不限制），与「取不到」共用同一个返回值，
                    // 因此单独取一次并记录成功标志。
                    val limit = api.workspaceUploadLimit(conn)
                    language to limit
                }
            }.onSuccess { (language, limit) ->
                _serverSettings.value = ServerSettingsUiState(
                    language = language,
                    uploadLimitMb = limit,
                    loaded = true,
                )
            }.onFailure { e ->
                _serverSettings.update { it.copy(loading = false, loaded = true, error = ErrorText.of(e)) }
            }
        }
    }

    fun setServerLanguage(language: String) {
        val conn = connection() ?: return
        _serverSettings.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.setServerLanguage(conn, language) } }
                .onSuccess { saved ->
                    _serverSettings.update { it.copy(language = saved, loading = false) }
                }
                .onFailure { e ->
                    _serverSettings.update { it.copy(loading = false, error = ErrorText.of(e)) }
                }
        }
    }

    private data class SecuritySnapshot(
        val toolGuard: JsonObject,
        val rules: List<ToolGuardRuleDto>,
        val fileGuard: FileGuardDto,
        val sandbox: SandboxStatusDto,
        val denyPaths: DenyPathsProtectionDto?,
        val skillScanner: JsonObject,
        val blockedHistory: kotlinx.serialization.json.JsonArray,
    )

    private data class VoiceSnapshot(
        val audioMode: String,
        val providerType: String,
        val providers: io.qwenpaw.node.data.model.TranscriptionProvidersDto,
        val model: String,
    )

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject =
        JsonObject(this + (key to value))

    /** 换实例或换智能体后清空，下次进入重新拉取。 */
    fun clear() {
        _tools.value = ToolsUiState()
        _envs.value = EnvsUiState()
        _offload.value = OffloadUiState()
        _runningConfig.value = RunningConfigUiState()
        _security.value = SecurityUiState()
        _serverSettings.value = ServerSettingsUiState()
        _voice.value = VoiceServerUiState()
    }
}