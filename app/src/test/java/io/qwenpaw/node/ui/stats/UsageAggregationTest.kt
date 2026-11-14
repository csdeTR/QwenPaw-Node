package io.qwenpaw.node.ui.stats

import io.qwenpaw.node.data.model.LlmToolDailyDto
import io.qwenpaw.node.data.model.TokenUsageRecordDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 用量页的纯逻辑：模型聚合、趋势补齐、区间换算。
 *
 * 全部传入固定的 `LocalDate`，不读 `LocalDate.now()`：本机是 `Asia/Shanghai`，
 * 依赖「今天」的断言会在跨日与不同时区上飘。
 */
class UsageAggregationTest {

    // ---------------- 区间换算 ----------------

    @Test
    fun `range is inclusive on both ends`() {
        val today = LocalDate.of(2026, 11, 14)

        val (start7, end7) = usageRange(UsageRange.D7, today)
        assertEquals(LocalDate.of(2026, 11, 8), start7)
        assertEquals(today, end7)
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(start7, end7) + 1)

        val (start30, _) = usageRange(UsageRange.D30, today)
        assertEquals(LocalDate.of(2026, 10, 16), start30)

        val (start90, _) = usageRange(UsageRange.D90, today)
        assertEquals(LocalDate.of(2026, 8, 17), start90)
    }

    @Test
    fun `range labels are written language`() {
        assertEquals(listOf("近 7 天", "近 30 天", "近 90 天"), UsageRange.entries.map { it.label })
    }

    // ---------------- 按模型聚合 ----------------

    @Test
    fun `aggregates by provider and model`() {
        val rows = aggregateByModel(
            listOf(
                record("a2a", "m1", date = "2026-11-01", prompt = 100, completion = 10, calls = 2),
                record("a2a", "m1", date = "2026-11-02", prompt = 50, completion = 5, calls = 1),
                record("dashscope", "m2", date = "2026-11-02", prompt = 10, completion = 1, calls = 1),
            ),
        )

        assertEquals(2, rows.size)
        // 按总 token 降序：m1 有 165，m2 只有 11。
        assertEquals("m1", rows.first().model)
        assertEquals(150L, rows.first().promptTokens)
        assertEquals(15L, rows.first().completionTokens)
        assertEquals(3, rows.first().callCount)
        assertEquals(165L, rows.first().totalTokens)
        assertEquals("m2", rows.last().model)
    }

    /** 历史行的 `agent_id` 为 null，照样计入：丢掉会让总量与概览对不上。 */
    @Test
    fun `null agent id rows are kept`() {
        val rows = aggregateByModel(
            listOf(record("a2a", "m1", date = "2026-11-01", prompt = 7, completion = 3, calls = 1).copy(agentId = null)),
        )

        assertEquals(1, rows.size)
        assertEquals(7L, rows.first().promptTokens)
    }

    @Test
    fun `same model name under two providers stays separate`() {
        val rows = aggregateByModel(
            listOf(
                record("p1", "same", date = "2026-11-01", prompt = 1, completion = 1, calls = 1),
                record("p2", "same", date = "2026-11-01", prompt = 2, completion = 2, calls = 1),
            ),
        )

        assertEquals(2, rows.size)
        assertEquals(setOf("p1", "p2"), rows.map { it.providerId }.toSet())
    }

    @Test
    fun `empty records yield nothing`() {
        assertTrue(aggregateByModel(emptyList()).isEmpty())
    }

    // ---------------- 趋势补齐 ----------------

    @Test
    fun `fills missing days with zero`() {
        val filled = fillTrend(
            trend = listOf(LlmToolDailyDto(date = "2026-11-10", agentLlmCalls = 3, toolCalls = 4)),
            start = LocalDate.of(2026, 11, 9),
            end = LocalDate.of(2026, 11, 12),
        )

        assertEquals(
            listOf("2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12"),
            filled.map { it.date },
        )
        assertEquals(0, filled.first().agentLlmCalls)
        assertEquals(3, filled[1].agentLlmCalls)
        assertEquals(4, filled[1].toolCalls)
        assertEquals(0, filled.last().toolCalls)
    }

    /** 服务端实测已补齐整个区间，归一化必须幂等：条数与取值都不变。 */
    @Test
    fun `already complete series is unchanged`() {
        val complete = listOf(
            LlmToolDailyDto(date = "2026-11-09", agentLlmCalls = 0, toolCalls = 0),
            LlmToolDailyDto(date = "2026-11-10", agentLlmCalls = 5, toolCalls = 2),
        )

        val filled = fillTrend(complete, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10))

        assertEquals(complete, filled)
    }

    @Test
    fun `reversed range yields nothing`() {
        val filled = fillTrend(
            trend = emptyList(),
            start = LocalDate.of(2026, 11, 12),
            end = LocalDate.of(2026, 11, 10),
        )

        assertTrue(filled.isEmpty())
    }

    private fun record(
        providerId: String,
        model: String,
        date: String,
        prompt: Long,
        completion: Long,
        calls: Int,
    ) = TokenUsageRecordDto(
        date = date,
        providerId = providerId,
        model = model,
        agentId = "default",
        promptTokens = prompt,
        completionTokens = completion,
        callCount = calls,
    )
}
