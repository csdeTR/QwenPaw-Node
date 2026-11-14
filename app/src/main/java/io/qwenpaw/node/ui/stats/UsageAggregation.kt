package io.qwenpaw.node.ui.stats

import io.qwenpaw.node.data.model.LlmToolDailyDto
import io.qwenpaw.node.data.model.TokenUsageRecordDto
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 用量页的时间范围。三档都含首尾两天，与服务端的 `start_date` / `end_date` 语义一致。 */
enum class UsageRange(val days: Long, val label: String) {
    D7(7, "近 7 天"),
    D30(30, "近 30 天"),
    D90(90, "近 90 天"),
}

/** 按模型聚合后的一行。`key` 为 `providerId:model`，与 `by_model` 的键同一形态。 */
data class ModelUsageRow(
    val providerId: String,
    val model: String,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val callCount: Int = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/**
 * 把区间换算成起止日期，**两端都含**。
 *
 * `today` 由调用方传入而不是这里取 `LocalDate.now()`：时区与「今天」的定义
 * 属于展示层，纯函数不该读系统时钟，否则单测在不同的执行日会得到不同结果。
 */
fun usageRange(range: UsageRange, today: LocalDate): Pair<LocalDate, LocalDate> =
    today.minusDays(range.days - 1) to today

/**
 * 按 `providerId:model` 聚合原始用量记录，按总 token 降序。
 *
 * `agent_id` 为 null 的行照样计入：那是服务端接入智能体归属之前的历史数据，
 * 丢掉会让总量与概览对不上。
 */
fun aggregateByModel(records: List<TokenUsageRecordDto>): List<ModelUsageRow> {
    if (records.isEmpty()) return emptyList()
    val merged = LinkedHashMap<String, ModelUsageRow>()
    records.forEach { record ->
        val key = "${record.providerId}:${record.model}"
        val previous = merged[key]
        merged[key] = if (previous == null) {
            ModelUsageRow(
                providerId = record.providerId,
                model = record.model,
                promptTokens = record.promptTokens,
                completionTokens = record.completionTokens,
                cacheReadTokens = record.cacheReadTokens,
                callCount = record.callCount,
            )
        } else {
            previous.copy(
                promptTokens = previous.promptTokens + record.promptTokens,
                completionTokens = previous.completionTokens + record.completionTokens,
                cacheReadTokens = previous.cacheReadTokens + record.cacheReadTokens,
                callCount = previous.callCount + record.callCount,
            )
        }
    }
    return merged.values.sortedByDescending { it.totalTokens }
}

/**
 * 把趋势补齐成 `start..end` 的连续日期序列，缺失日期记为零值。
 *
 * 服务端实测已补齐整个区间（93 天返回 93 条），因此这一步在当前版本上是幂等的；
 * 保留它是因为「只返回有数据的日期」是常见实现，缺日时折线会跨空档连成直线，
 * 看上去像「那天有量」。
 */
fun fillTrend(
    trend: List<LlmToolDailyDto>,
    start: LocalDate,
    end: LocalDate,
): List<LlmToolDailyDto> {
    if (end.isBefore(start)) return emptyList()
    val known = trend.associateBy { it.date }
    val out = ArrayList<LlmToolDailyDto>(ChronoUnit.DAYS.between(start, end).toInt() + 1)
    var day = start
    while (!day.isAfter(end)) {
        val key = day.toString()
        out += known[key] ?: LlmToolDailyDto(date = key)
        day = day.plusDays(1)
    }
    return out
}
