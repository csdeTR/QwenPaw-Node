package io.qwenpaw.node.ui.stats

import io.qwenpaw.node.data.model.AgentStatsSummaryDto
import io.qwenpaw.node.data.model.LlmToolDailyDto
import io.qwenpaw.node.data.model.TokenUsageDto
import io.qwenpaw.node.data.model.TokenUsageRecordDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
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
import java.time.LocalDate

/**
 * 用量统计页的状态。
 *
 * 四块数据分别记错误：某一端点失败时另外三块照常显示，错误行指明是哪一块取不到。
 * 合成一个 `error` 字段会让「趋势取不到」看起来像「整页加载失败」。
 */
data class UsageUiState(
    val range: UsageRange = UsageRange.D30,
    /** 区间概览，来自 `GET /api/token-usage`。**全服务端**口径。 */
    val summary: TokenUsageDto? = null,
    /** 当前智能体汇总，来自 `GET /api/agent-stats`。**按 `X-Agent-Id` 过滤**。 */
    val agent: AgentStatsSummaryDto? = null,
    /** 全局调用趋势，来自 `GET /api/agent-stats/llm-tool-trend`。 */
    val trend: List<LlmToolDailyDto> = emptyList(),
    /** 按模型聚合后的明细，来自 `GET /api/token-usage/details`。 */
    val models: List<ModelUsageRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val summaryError: String? = null,
    val agentError: String? = null,
    val trendError: String? = null,
    val modelsError: String? = null,
) {
    /** 四块全部取不到时为 true，用于整页空态。 */
    val allFailed: Boolean
        get() = summary == null && agent == null && trend.isEmpty() && models.isEmpty() &&
            (summaryError != null || agentError != null || trendError != null || modelsError != null)
}

/**
 * 用量统计页的状态与动作。
 *
 * 四个端点并行请求：它们互不依赖，串行会把首屏时间叠成四倍。
 * 切换范围时用代次丢弃过期结果，避免先发的慢请求覆盖后发的快请求。
 */
class UsageViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    /** 「今天」由外部给，便于单测固定日期。 */
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    private var generation = 0

    fun ensureLoaded() {
        if (_state.value.loaded || _state.value.loading) return
        load()
    }

    /** 切换范围。同档重复点击不发请求。 */
    fun setRange(range: UsageRange) {
        if (range == _state.value.range && _state.value.loaded) return
        load(range)
    }

    fun refresh() = load(_state.value.range, force = true)

    fun load(range: UsageRange = _state.value.range, force: Boolean = false) {
        val conn = connection()
        if (conn == null) {
            if (!force && _state.value.loaded) return
            _state.update {
                it.copy(loaded = true, loading = false, summaryError = "尚未连接服务器")
            }
            return
        }
        val mine = ++generation
        val (start, end) = usageRange(range, today())
        val startText = start.toString()
        val endText = end.toString()
        _state.update { it.copy(range = range, loading = true) }
        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val summary = async { runCatching { api.tokenUsage(conn, startText, endText) } }
                    val agent = async { runCatching { api.agentStats(conn, startText, endText) } }
                    val trend = async { runCatching { api.agentLlmToolTrend(conn, startText, endText) } }
                    val details = async { runCatching { api.tokenUsageDetails(conn, startText, endText) } }
                    Fetched(
                        summary = summary.await(),
                        agent = agent.await(),
                        trend = trend.await(),
                        details = details.await(),
                    )
                }
            }
            // 切换范围后旧请求才回来时直接丢弃，否则慢的旧结果会覆盖新结果。
            if (mine != generation) return@launch
            _state.update { current ->
                current.copy(
                    loading = false,
                    loaded = true,
                    summary = fetched.summary.getOrNull() ?: current.summary,
                    agent = fetched.agent.getOrNull() ?: current.agent,
                    trend = fetched.trend.getOrNull()?.let { fillTrend(it, start, end) } ?: current.trend,
                    models = fetched.details.getOrNull()?.let { aggregateByModel(it) } ?: current.models,
                    summaryError = fetched.summary.exceptionOrNull()?.let { ErrorText.of(it) },
                    agentError = fetched.agent.exceptionOrNull()?.let { ErrorText.of(it) },
                    trendError = fetched.trend.exceptionOrNull()?.let { ErrorText.of(it) },
                    modelsError = fetched.details.exceptionOrNull()?.let { ErrorText.of(it) },
                )
            }
        }
    }

    /** 四个端点的一次并行取数结果。分开保存是为了让失败的那一块独立报错。 */
    private data class Fetched(
        val summary: Result<TokenUsageDto>,
        val agent: Result<AgentStatsSummaryDto>,
        val trend: Result<List<LlmToolDailyDto>>,
        val details: Result<List<TokenUsageRecordDto>>,
    )

    /** 换实例或换智能体时清空：统计口径已经变了。 */
    fun clear() {
        generation++
        _state.value = UsageUiState()
    }
}
