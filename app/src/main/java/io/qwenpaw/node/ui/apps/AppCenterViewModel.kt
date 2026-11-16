package io.qwenpaw.node.ui.apps

import io.qwenpaw.node.data.model.PawAppDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppCenterUiState(
    val apps: List<PawAppDto> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

/** PawApp 列表。查看与跳转都交给系统浏览器，客户端不嵌入 WebView。 */
class AppCenterViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AppCenterUiState())
    val state: StateFlow<AppCenterUiState> = _state.asStateFlow()

    fun refresh(force: Boolean = false) {
        if (!force && (_state.value.loaded || _state.value.loading)) return
        val conn = connection() ?: run {
            _state.update { it.copy(loading = false, loaded = true, error = "尚未连接服务器") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.pawApps(conn) } }
                .onSuccess { page ->
                    _state.value = AppCenterUiState(apps = page.apps, loaded = true)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, loaded = true, error = ErrorText.of(e))
                    }
                }
        }
    }

    fun clear() {
        _state.value = AppCenterUiState()
    }
}
