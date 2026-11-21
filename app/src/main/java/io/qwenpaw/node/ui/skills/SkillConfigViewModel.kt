package io.qwenpaw.node.ui.skills

import io.qwenpaw.node.R
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.common.formatJsonObject
import io.qwenpaw.node.ui.common.normalizeTags
import io.qwenpaw.node.ui.common.parseJsonObjectOrNull
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
import kotlinx.serialization.json.JsonObject

data class SkillConfigUiState(
    val skillName: String = "",
    val label: String = "",
    val configText: String = "",
    val preload: Boolean = false,
    val tags: List<String> = emptyList(),
    val tagDraft: String = "",
    val channels: List<String> = emptyList(),
    val availableChannels: List<String> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val configInvalid: Boolean = false,
    val confirmClear: Boolean = false,
    val filePath: String = "",
    val fileContent: String = "",
    val fileLoading: Boolean = false,
    val fileError: String? = null,
)

/**
 * 工作区技能的配置表单。
 *
 * 配置是一个自由 JSON 对象，服务端不做字段级校验；保存前由客户端解析，
 * 避免把一段非法 JSON 发过去后得到一条难以理解的 422。
 */
class SkillConfigViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SkillConfigUiState())
    val state: StateFlow<SkillConfigUiState> = _state.asStateFlow()

    fun open(name: String) {
        val conn = connection() ?: return
        _state.value = SkillConfigUiState(skillName = name, label = name, loading = true)
        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val detail = async { runCatching { api.skill(conn, name) } }
                    val config = async { runCatching { api.skillConfig(conn, name) } }
                    val channels = async { runCatching { api.channels(conn).keys.toList() } }
                    Triple(detail.await(), config.await(), channels.await())
                }
            }
            val detail = fetched.first.getOrNull()
            if (detail == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        error = fetched.first.exceptionOrNull()?.let(ErrorText::of)
                            ?: Texts.get(R.string.skill_config_load_failed),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    label = detail.name.ifBlank { name },
                    configText = formatJsonObject(
                        fetched.second.getOrDefault(JsonObject(emptyMap())),
                    ),
                    preload = detail.preload,
                    tags = detail.tags,
                    channels = detail.channels,
                    availableChannels = fetched.third.getOrDefault(emptyList()),
                    loading = false,
                    loaded = true,
                )
            }
        }
    }

    fun setConfigText(value: String) {
        _state.update { it.copy(configText = value, configInvalid = false, error = null) }
    }

    fun setPreload(value: Boolean) {
        _state.update { it.copy(preload = value) }
    }

    fun setTagDraft(value: String) {
        _state.update { it.copy(tagDraft = value) }
    }

    fun commitTags() {
        _state.update {
            it.copy(tags = (it.tags + normalizeTags(it.tagDraft)).distinct(), tagDraft = "")
        }
    }

    fun removeTag(tag: String) {
        _state.update { it.copy(tags = it.tags - tag) }
    }

    fun toggleChannel(channel: String) {
        _state.update { current ->
            val next = when {
                channel == "all" -> listOf("all")
                channel in current.channels -> current.channels - channel
                else -> (current.channels - "all") + channel
            }
            current.copy(channels = next.ifEmpty { listOf("all") })
        }
    }

    fun save() {
        val conn = connection() ?: return
        val current = _state.value
        val config = parseJsonObjectOrNull(current.configText)
        if (config == null) {
            _state.update { it.copy(configInvalid = true) }
            return
        }
        _state.update { it.copy(saving = true, error = null, notice = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.saveSkillConfig(conn, current.skillName, config)
                    api.setSkillPreload(conn, current.skillName, current.preload)
                    api.setSkillTags(conn, current.skillName, current.tags)
                    api.setSkillChannels(conn, current.skillName, current.channels)
                }
            }
            _state.update {
                it.copy(
                    saving = false,
                    notice = if (result.isSuccess) {
                        Texts.get(R.string.skill_config_saved)
                    } else {
                        it.notice
                    },
                    error = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
        }
    }

    fun requestClear() {
        _state.update { it.copy(confirmClear = true) }
    }

    fun dismissClear() {
        _state.update { it.copy(confirmClear = false) }
    }

    fun confirmClear() {
        val conn = connection() ?: return
        val name = _state.value.skillName
        _state.update { it.copy(confirmClear = false, saving = true, error = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.clearSkillConfig(conn, name) }
            }
            _state.update {
                it.copy(
                    saving = false,
                    configText = if (result.isSuccess) "{}" else it.configText,
                    notice = if (result.isSuccess) {
                        Texts.get(R.string.skill_config_cleared)
                    } else {
                        it.notice
                    },
                    error = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
        }
    }

    fun setFilePath(value: String) {
        _state.update { it.copy(filePath = value, fileError = null) }
    }

    fun readFile() {
        val conn = connection() ?: return
        val current = _state.value
        if (current.filePath.isBlank()) return
        _state.update { it.copy(fileLoading = true, fileError = null, fileContent = "") }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.skillFile(conn, current.skillName, current.filePath.trim())
                }
            }
            _state.update {
                it.copy(
                    fileLoading = false,
                    fileContent = result.getOrDefault(""),
                    fileError = result.exceptionOrNull()?.let(ErrorText::of),
                )
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(error = null, notice = null) }
    }

    fun clear() {
        _state.value = SkillConfigUiState()
    }
}
