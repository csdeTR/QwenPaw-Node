package io.qwenpaw.node.ui.market

import io.qwenpaw.node.R
import io.qwenpaw.node.data.market.MarketLinkResolver
import io.qwenpaw.node.data.model.MarketCategoryDto
import io.qwenpaw.node.data.model.MarketProviderDto
import io.qwenpaw.node.data.model.MarketResultDto
import io.qwenpaw.node.data.model.PluginCatalogDto
import io.qwenpaw.node.data.model.PluginMarketEntryDto
import io.qwenpaw.node.data.remote.Connection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.model.SkillImportResultDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.remote.SafZipSource
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 市场页的两段。 */
enum class MarketTab { SKILLS, PLUGINS }

/** 插件安装的界面状态。`conflictSource` 非空时展示「覆盖 / 取消」。 */
data class PluginInstallUi(
    val label: String,
    val stage: String = "installing",
    val read: Long = 0,
    val total: Long? = null,
    val error: String? = null,
    val conflictSource: String? = null,
)

/** 技能 URL 导入的异步任务。 */
data class HubInstallUi(
    val taskId: String,
    val bundleUrl: String,
    val providerKey: String? = null,
    val version: String = "",
    val status: String = "pending",
    val error: String? = null,
    val result: SkillImportResultDto? = null,
) {
    val isTerminal: Boolean
        get() = status == "completed" || status == "failed" || status == "cancelled"
}

/**
 * 扩展市场页状态。
 *
 * 三段数据分别独立：技能市场的来源与分类、技能搜索结果、插件目录与在线插件市场。
 * 任何一段失败都只在自己那段显示错误，因为用户打开这一页可能只想看其中一段。
 */
data class MarketUiState(
    val tab: MarketTab = MarketTab.SKILLS,
    val providers: List<MarketProviderDto> = emptyList(),
    val categories: List<MarketCategoryDto> = emptyList(),
    val metaLoading: Boolean = false,
    val metaLoaded: Boolean = false,
    val metaError: String? = null,
    val query: String = "",
    val selectedCategory: String? = null,
    val results: List<MarketResultDto> = emptyList(),
    val resultErrors: List<String> = emptyList(),
    /** 有来源返回了更多结果，界面提示换更具体的关键词而不是翻页。 */
    val truncated: Boolean = false,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val searchError: String? = null,
    val catalog: PluginCatalogDto? = null,
    val catalogLoading: Boolean = false,
    val catalogLoaded: Boolean = false,
    val catalogError: String? = null,
    val pluginQuery: String = "",
    val pluginResults: List<PluginMarketEntryDto> = emptyList(),
    val pluginSearching: Boolean = false,
    val pluginSearched: Boolean = false,
    val pluginError: String? = null,
    /** 正在解析落地地址的那条结果（QwenPaw 来源需要先换出 UUID）。 */
    val resolvingSource: String? = null,
    val pluginInstall: PluginInstallUi? = null,
    val hubInstall: HubInstallUi? = null,
    /** 从其他页面跳来时预填并自动打开的插件 URL；消费后清空。 */
    val prefillInstallUrl: String? = null,
    val notice: String? = null,
) {
    /** 可参与搜索的来源。不可用的来源不能出现在 `provider_pages` 里，否则服务端回 400。 */
    val searchableProviders: List<MarketProviderDto>
        get() = providers.filter { it.available }
}

/**
 * 扩展市场：技能市场与插件目录，都只读。
 *
 * 搜索在本版只有两个动作：打开结果对应的网页，和不搜。
 * 安装、下载、配置表单都在 Web 控制台。
 */
class MarketViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val linkResolver: MarketLinkResolver = MarketLinkResolver(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(MarketUiState())
    val state: StateFlow<MarketUiState> = _state.asStateFlow()

    private var pendingPluginSource: SafZipSource? = null
    private var pendingPluginUrl: String? = null
    private var hubPollJob: Job? = null

    /** 三个基础请求一起拉：来源、分类、插件目录。任一段失败只影响自己。 */
    fun ensureLoaded() {
        val current = _state.value
        if (current.metaLoaded || current.metaLoading) {
            if (current.catalogLoaded || current.catalogLoading) return
        }
        val conn = connection() ?: run {
            _state.update {
                it.copy(
                    metaLoaded = true,
                    catalogLoaded = true,
                    metaError = Texts.get(R.string.market_error_not_connected),
                )
            }
            return
        }
        _state.update {
            it.copy(
                metaLoading = !it.metaLoaded,
                catalogLoading = !it.catalogLoaded,
                metaError = if (it.metaLoaded) it.metaError else null,
                catalogError = if (it.catalogLoaded) it.catalogError else null,
            )
        }
        scope.launch {
            val providersJob = async {
                runCatching { withContext(Dispatchers.IO) { api.marketProviders(conn) } }
            }
            val categoriesJob = async {
                runCatching { withContext(Dispatchers.IO) { api.marketCategories(conn) } }
            }
            val catalogJob = async {
                runCatching { withContext(Dispatchers.IO) { api.pluginCatalog(conn) } }
            }
            val providers = providersJob.await()
            val categories = categoriesJob.await()
            val catalog = catalogJob.await()
            _state.update { state ->
                val metaFailure = providers.exceptionOrNull() ?: categories.exceptionOrNull()
                val catalogValue = catalog.getOrNull()
                state.copy(
                    providers = providers.getOrNull() ?: state.providers,
                    categories = categories.getOrNull() ?: state.categories,
                    metaLoading = false,
                    metaLoaded = true,
                    metaError = metaFailure?.let { ErrorText.of(it) },
                    catalog = catalogValue ?: state.catalog,
                    catalogLoading = false,
                    catalogLoaded = true,
                    // 目录「成功了但 error 非空」表示 CDN 拉不到，服务端仍回 200。
                    catalogError = catalog.exceptionOrNull()?.let { ErrorText.of(it) }
                        ?: catalogValue?.error?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    fun setTab(tab: MarketTab) {
        _state.update { it.copy(tab = tab) }
    }

    /** 从设置或运行时页跳来安装：切到插件 Tab 并预填 URL。 */
    fun openInstallUrlDialog(url: String) {
        _state.update { it.copy(tab = MarketTab.PLUGINS, prefillInstallUrl = url) }
    }

    /** 对话框已经打开，清掉一次性预填值。 */
    fun consumePrefill() {
        _state.update { it.copy(prefillInstallUrl = null) }
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
    }

    fun setCategory(id: String?) {
        _state.update { it.copy(selectedCategory = id) }
    }

    fun setPluginQuery(text: String) {
        _state.update { it.copy(pluginQuery = text) }
    }

    /**
     * 搜索技能市场。
     *
     * `provider_pages` 只填可用来源，每个都是第 1 页。填一个未注册或不可用的 key
     * 会被服务端判成 400，而界面上看不出是这个原因。
     */
    fun search() {
        val current = _state.value
        if (current.searching) return
        val conn = connection() ?: return
        val pages = current.searchableProviders.associate { it.key to 1 }
        if (pages.isEmpty()) {
            _state.update { it.copy(searchError = Texts.get(R.string.market_error_no_provider)) }
            return
        }
        _state.update { it.copy(searching = true, searchError = null, resultErrors = emptyList()) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.marketSearch(
                        conn = conn,
                        query = current.query.trim(),
                        providerPages = pages,
                        limit = SEARCH_LIMIT,
                        lang = "zh",
                        category = current.selectedCategory,
                    )
                }
            }
                .onSuccess { response ->
                    val labelOf = current.providers.associate { it.key to it.label }
                    _state.update {
                        it.copy(
                            results = response.results,
                            // 来源名换成中文标签：服务端给的是 key，界面上出现英文 key 没有意义。
                            resultErrors = response.errors.map { error ->
                                val label = labelOf[error.provider] ?: error.provider
                                Texts.get(
                                    R.string.market_error_provider_unavailable,
                                    label,
                                    error.message,
                                )
                            },
                            truncated = response.byProvider.values.any { page -> page.hasMore },
                            searching = false,
                            searched = true,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(searching = false, searched = true, searchError = ErrorText.of(e)) }
                }
        }
    }

    /** 搜索在线插件市场。只读浏览，结果点开是浏览器。 */
    fun searchPlugins() {
        val current = _state.value
        if (current.pluginSearching) return
        val conn = connection() ?: return
        _state.update { it.copy(pluginSearching = true, pluginError = null) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.pluginMarketSearch(conn, current.pluginQuery.trim(), pageSize = PLUGIN_PAGE_SIZE)
                }
            }
                .onSuccess { response ->
                    _state.update {
                        it.copy(
                            pluginResults = response.data.plugins,
                            pluginSearching = false,
                            pluginSearched = true,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            pluginSearching = false,
                            pluginSearched = true,
                            pluginError = Texts.get(
                                R.string.market_error_online_plugins,
                                ErrorText.of(e),
                            ),
                        )
                    }
                }
        }
    }

    /**
     * 换算一条市场结果的落地地址，并把它交给 [onOpen]。
     *
     * QwenPaw 来源的 `source_url` 是安装用地址（`/skills/@owner/name`），
     * 平台前端只认 UUID，直接打开会落到「未找到技能详情」。这里先换一次；
     * 换不到就退回平台技能列表并在页面上说明原因。
     *
     * 解析期间记下这条结果的 `source`，界面据此把这一行的点击禁掉，
     * 避免连点产生多次并发解析。
     */
    fun openResult(result: MarketResultDto, onOpen: (String) -> Unit, onNote: (String) -> Unit) {
        if (_state.value.resolvingSource != null) return
        if (result.source != MarketLinkResolver.QWENPAW_SOURCE) {
            onOpen(normalizeUrl(result.sourceUrl))
            return
        }
        _state.update { it.copy(resolvingSource = result.source) }
        scope.launch {
            val outcome = linkResolver.landingUrl(result)
            _state.update { it.copy(resolvingSource = null) }
            when (outcome) {
                is MarketLinkResolver.Outcome.Direct -> onOpen(outcome.url)
                is MarketLinkResolver.Outcome.Fallback -> {
                    onNote(outcome.reason)
                    // 空地址表示这条结果没有可打开的地方，此时连提示都不必给跳转。
                    if (outcome.url.isNotBlank()) onOpen(outcome.url)
                }
            }
        }
    }

    // ---------- 安装与导入 ----------

    fun installPluginFromFile(source: SafZipSource) {
        pendingPluginSource = source
        pendingPluginUrl = null
        doPluginInstall(force = false)
    }

    fun installPluginFromUrl(source: String) {
        val url = source.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update {
                it.copy(notice = Texts.get(R.string.market_error_install_url))
            }
            return
        }
        pendingPluginSource = null
        pendingPluginUrl = url
        doPluginInstall(force = false)
    }

    fun forcePluginInstall() {
        doPluginInstall(force = true)
    }

    private fun doPluginInstall(force: Boolean) {
        val conn = connection() ?: return
        val source = pendingPluginSource
        val url = pendingPluginUrl
        if (source == null && url.isNullOrBlank()) return
        val label = source?.fileName ?: url.orEmpty()
        _state.update {
            it.copy(
                pluginInstall = PluginInstallUi(label = label),
                notice = null,
            )
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (source != null) {
                        api.uploadPlugin(conn, source, force) { read, total ->
                            _state.update { state ->
                                state.copy(
                                    pluginInstall = state.pluginInstall?.copy(read = read, total = total),
                                )
                            }
                        }
                    } else {
                        api.installPlugin(conn, url.orEmpty(), force)
                    }
                }
                source?.cleanup()
                pendingPluginSource = null
                pendingPluginUrl = null
                _state.update {
                    it.copy(
                        pluginInstall = it.pluginInstall?.copy(stage = "done"),
                        notice = if (result.loaded) {
                            Texts.get(
                                R.string.market_notice_plugin_installed,
                                result.name.ifBlank { result.id },
                            )
                        } else {
                            Texts.get(
                                R.string.market_notice_plugin_installed_restart,
                                result.name.ifBlank { result.id },
                            )
                        },
                        catalogLoaded = false,
                    )
                }
                ensureLoaded()
            } catch (e: Exception) {
                if (e is ApiException && e.code == 409) {
                    _state.update {
                        it.copy(pluginInstall = it.pluginInstall?.copy(conflictSource = label))
                    }
                } else {
                    val message = ErrorText.of(e)
                    _state.update {
                        it.copy(
                            pluginInstall = it.pluginInstall?.copy(
                                stage = "failed",
                                error = message,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun dismissPluginInstall() {
        pendingPluginSource?.cleanup()
        pendingPluginSource = null
        pendingPluginUrl = null
        _state.update { it.copy(pluginInstall = null) }
    }

    /** 从技能市场导入当前智能体。异步任务，1 秒轮询，离开页面时调用 [stopHubPolling]。 */
    fun startHubInstall(result: MarketResultDto) {
        val conn = connection() ?: return
        val url = result.sourceUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(notice = Texts.get(R.string.market_error_no_import_url)) }
            return
        }
        scope.launch {
            try {
                val task = withContext(Dispatchers.IO) {
                    api.hubInstallStart(
                        conn = conn,
                        bundleUrl = url,
                        providerKey = result.source,
                        version = result.version.orEmpty(),
                    )
                }
                _state.update {
                    it.copy(
                        hubInstall = HubInstallUi(
                            taskId = task.taskId,
                            bundleUrl = url,
                            providerKey = result.source,
                            version = result.version.orEmpty(),
                            status = task.status,
                            error = task.error,
                        ),
                    )
                }
                pollHubInstall(task.taskId)
            } catch (e: Exception) {
                _state.update {
                    it.copy(notice = Texts.get(R.string.market_error_import, ErrorText.of(e)))
                }
            }
        }
    }

    fun cancelHubInstall() {
        val conn = connection() ?: return
        val taskId = _state.value.hubInstall?.taskId ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.hubInstallCancel(conn, taskId) } }
        }
    }

    fun dismissHubInstall() {
        hubPollJob?.cancel()
        _state.update { it.copy(hubInstall = null) }
    }

    fun stopHubPolling() {
        hubPollJob?.cancel()
    }

    private fun pollHubInstall(taskId: String) {
        val conn = connection() ?: return
        hubPollJob?.cancel()
        hubPollJob = scope.launch {
            repeat(600) {
                delay(1_000)
                val task = runCatching {
                    withContext(Dispatchers.IO) { api.hubInstallStatus(conn, taskId) }
                }.getOrNull() ?: return@repeat
                _state.update {
                    it.copy(
                        hubInstall = it.hubInstall?.copy(
                            status = task.status,
                            error = task.error,
                            result = task.result,
                        ),
                    )
                }
                if (task.isTerminal) return@launch
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clear() {
        hubPollJob?.cancel()
        pendingPluginSource?.cleanup()
        pendingPluginSource = null
        pendingPluginUrl = null
        _state.value = MarketUiState()
    }

    /** 地址两侧的空白会让 `Uri.parse` 得出一个打不开的 URL。 */
    private fun normalizeUrl(url: String): String = url.trim()

    companion object {
        /** 每个来源取的条数。服务端上限 50，取 20 是为了让三个来源加起来仍能一屏看完。 */
        const val SEARCH_LIMIT = 20

        /** 在线插件市场的每页条数。 */
        const val PLUGIN_PAGE_SIZE = 20
    }
}