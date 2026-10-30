package io.qwenpaw.node.data.stream

/**
 * 一轮回复的用量统计。
 *
 * 服务端把整轮用量挂在该轮的**最后一条** assistant 消息上
 * （`metadata.qwenpaw_turn_usage.usage`），因此只有每轮最后一条消息会带这个结构。
 * 中间消息的统计为空属于正常情况，界面不应为它们显示「0 tokens」。
 */
data class TurnStats(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    /**
     * 缓存命中率，0..1 的小数。
     *
     * 服务端给的是**百分数**（实测 `74.99633806943021` 表示 75%），
     * 解析时统一除以 100，避免下游两处各折一次。
     */
    val cacheHitRate: Double = 0.0,
    /** 本轮实际使用的模型名，例如 `qwen3-max`。 */
    val model: String? = null,
    /** 模型提供方 id，例如 `dashscope`。 */
    val providerId: String? = null,
) {
    /** 是否存在可展示的数据。全零且无模型名时视为没有统计。 */
    val hasAny: Boolean
        get() = promptTokens > 0 || completionTokens > 0 || !model.isNullOrBlank()
}