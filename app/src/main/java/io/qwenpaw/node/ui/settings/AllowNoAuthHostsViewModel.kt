package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.R
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

/**
 * 免鉴权地址页的状态。
 *
 * [authEnabled] 是可空三态：`null` 表示尚未读到或读取失败，界面此时既不提示
 * 「名单不生效」，也不断言登录已开启。把读失败当成已开启会让用户以为名单正在
 * 起作用，而实际上服务端根本没有登录这一层。
 *
 * [removedIpLoopbacks] 是相对本次读取结果的差值，只用于保存确认弹窗的额外提示；
 * 保存成功后清空，因为那时新名单已经成为基线。
 */
data class AllowNoAuthHostsUiState(
    val hosts: List<String> = emptyList(),
    val draftInput: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val confirming: Boolean = false,
    val authEnabled: Boolean? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val removedIpLoopbacks: List<String> = emptyList(),
)

/**
 * 免鉴权地址名单的数据与保存。
 *
 * 名单是一个整体：`PUT` 的 body 就是全量列表，逐条增删在本地完成，点保存时
 * 一次性提交。客户端只做 trim、去重与空值过滤，IPv4/IPv6 的规范化由服务端负责；
 * 保存后以服务端返回的列表为准，避免界面停留在未规范化的写法上。
 */
class AllowNoAuthHostsViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AllowNoAuthHostsUiState())
    val state: StateFlow<AllowNoAuthHostsUiState> = _state.asStateFlow()

    /** 本次进入页面时读到的名单，用来算「删掉了哪些回环地址」。 */
    private var baseline: List<String> = emptyList()

    /** 首次进入与失败重试共用；已加载或正在加载时直接返回。 */
    fun ensureLoaded() {
        val current = _state.value
        if (current.loaded || current.loading) return
        val conn = connection() ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // 登录状态读失败不阻断名单：两者分开展示，名单本身仍可修改。
                    val auth = runCatching { api.authStatus(conn.baseUrl, conn.basicAuth) }.getOrNull()
                    auth to api.allowNoAuthHosts(conn).hosts
                }
            }.onSuccess { (auth, hosts) ->
                baseline = hosts
                _state.update {
                    it.copy(
                        hosts = hosts,
                        authEnabled = auth?.enabled,
                        loading = false,
                        loaded = true,
                        error = null,
                        removedIpLoopbacks = emptyList(),
                    )
                }
            }.onFailure { e ->
                // 保持 loaded = false，界面据此给出重试入口。
                _state.update { it.copy(loading = false, loaded = false, error = loadError(e)) }
            }
        }
    }

    fun onDraftChange(text: String) {
        _state.update { it.copy(draftInput = text, error = null) }
    }

    fun add() {
        if (_state.value.saving) return
        val current = _state.value
        val next = AllowNoAuthHostsEdit.add(current.hosts, current.draftInput)
        if (next == current.hosts) {
            _state.update {
                if (current.draftInput.isBlank()) {
                    it.copy(draftInput = "")
                } else {
                    it.copy(error = Texts.get(R.string.allow_hosts_duplicate))
                }
            }
            return
        }
        _state.update {
            it.copy(
                hosts = next,
                draftInput = "",
                error = null,
                removedIpLoopbacks = AllowNoAuthHostsEdit.droppedIpLoopbacks(baseline, next),
            )
        }
    }

    fun remove(host: String) {
        if (_state.value.saving) return
        val next = AllowNoAuthHostsEdit.remove(_state.value.hosts, host)
        _state.update {
            it.copy(
                hosts = next,
                error = null,
                removedIpLoopbacks = AllowNoAuthHostsEdit.droppedIpLoopbacks(baseline, next),
            )
        }
    }

    /** 保存前进入确认；名单未读到时不允许提交，否则会用空名单覆盖服务端配置。 */
    fun requestSave() {
        val current = _state.value
        if (!current.loaded || current.saving) return
        _state.update { it.copy(confirming = true, error = null) }
    }

    fun confirmSave() {
        val current = _state.value
        if (!current.loaded || current.saving) return
        val conn = connection() ?: return
        val hosts = current.hosts
        _state.update { it.copy(saving = true, confirming = false, error = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setAllowNoAuthHosts(conn, hosts).hosts }
            }.onSuccess { saved ->
                baseline = saved
                _state.update {
                    it.copy(
                        hosts = saved,
                        saving = false,
                        error = null,
                        notice = Texts.get(R.string.allow_hosts_saved),
                        removedIpLoopbacks = emptyList(),
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = saveError(e)) }
            }
        }
    }

    fun dismissSave() {
        _state.update { it.copy(confirming = false) }
    }

    fun dismissNotice() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** 离开页面时调用，避免下次进入沿用上一次的名单与输入。 */
    fun clear() {
        baseline = emptyList()
        _state.value = AllowNoAuthHostsUiState()
    }

    /** 400 的成因是具体地址写错了，只有服务端原话能说清哪一条不合法。 */
    private fun saveError(e: Throwable): String = when {
        e is ApiException && e.code == 400 ->
            e.detail?.takeIf { it.isNotBlank() }?.take(400) ?: ErrorText.of(e)

        e is ApiException && e.code == 404 -> Texts.get(R.string.allow_hosts_unsupported)
        else -> ErrorText.of(e)
    }

    /** 端点缺失说明服务端版本不支持，不能让它落到 404 的默认文案上。 */
    private fun loadError(e: Throwable): String = when {
        e is ApiException && e.code == 404 -> Texts.get(R.string.allow_hosts_unsupported)
        else -> ErrorText.of(e)
    }
}
