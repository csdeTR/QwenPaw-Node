package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ThinkingControlDto
import io.qwenpaw.node.data.model.ThinkingValueDto
import kotlin.math.roundToLong

/**
 * 思考强度的滑块取值与文案。
 *
 * 取值规则对齐 qwenpaw 官方 Web 端（`ThinkingControl` 组件）：
 *
 * - `kind = "effort"`：滑块停靠点就是 `control.efforts`，支持关闭时在最左再加一格。
 * - `kind = "budget"`：滑块是连续区间的 token 数，区间为 `budget_min..budget_max`；
 *   支持关闭时把 `budget_min` 往下让出一段「关闭带」，宽度为区间的 12%。
 *
 * 所有计算都是纯函数，不碰 UI，便于单测。
 */
internal data class ThinkingScale(
    val kind: String,
    val stops: List<String>,
    val budgetMin: Long,
    val budgetMax: Long,
    val budgetDefault: Long?,
    val supportsOff: Boolean,
) {

    val isEffort: Boolean get() = kind == "effort"
    val isBudget: Boolean get() = kind == "budget"

    /** 滑块是否可用。只有一个档位的 effort 模型等于不可调。 */
    val adjustable: Boolean
        get() = (isEffort && stops.size > 1) || (isBudget && budgetMax > budgetMin)

    /** 关闭带的左端：`budget_min` 之下再让出 12% 的区间。 */
    val offFloor: Long
        get() {
            val width = ((budgetMax - budgetMin) * OFF_BAND_RATIO).roundToLong().coerceAtLeast(1L)
            return (budgetMin - width).coerceAtLeast(0L)
        }

    /** 滑块左端。支持关闭时向左扩到关闭带。 */
    val sliderMin: Long get() = if (supportsOff) offFloor else budgetMin

    /** 左侧这一段属于「关闭」，与官方 Web 端同一判据：取关闭带与 `budget_min` 的中点。 */
    val offThreshold: Double get() = (budgetMin + offFloor) / 2.0

    /** effort 滑块当前停在第几格；未知档位落到最右一格。 */
    fun indexOf(level: String?): Int {
        val i = stops.indexOf(level)
        return if (i >= 0) i else stops.lastIndex.coerceAtLeast(0)
    }

    /** 当前档位的展示名。budget 档由 token 数换算成强度描述。 */
    fun label(value: ThinkingValueDto?): String = when {
        value == null || value.level.isBlank() || value.level == "inherit" -> "跟随默认"
        value.level == "off" -> "关闭思考"
        isBudget && value.level == "budget" -> budgetLabel(value.budgetTokens)
        else -> thinkingLevelLabel(value.level)
    }

    /** budget 档的强度描述，按占区间上限的比例分四段。 */
    fun budgetLabel(tokens: Long?): String {
        val v = (tokens ?: budgetMin).coerceIn(budgetMin, budgetMax)
        val ratio = if (budgetMax > budgetMin) {
            (v - budgetMin).toDouble() / (budgetMax - budgetMin)
        } else {
            1.0
        }
        return when {
            ratio < 0.25 -> "轻量思考"
            ratio < 0.60 -> "均衡思考"
            ratio < 0.85 -> "深入思考"
            else -> "充分思考"
        }
    }

    /**
     * 滑块初值。
     *
     * 优先用已显式设过的值；没有设过时用服务端算出的 `effective`——用户打开对话框
     * 看到的应当是「当前实际生效的预算」，而不是区间下限。两者都没有时才用
     * `budget_default`，最后才退回中点。
     *
     * 直接取 `budget_min` 是错的：实测 Qwen3.8-Flash 的 `budget_min = 1`，
     * 滑块会停在最左端并显示「1 tokens」，与「跟随默认」的真实语义相反。
     */
    fun startTokens(value: ThinkingValueDto?, effective: ThinkingValueDto?): Long {
        val explicit = value?.takeIf { it.level == "budget" }?.budgetTokens
        val resolved = effective?.takeIf { it.level == "budget" }?.budgetTokens
        val fallback = explicit ?: resolved ?: budgetDefault ?: (budgetMin + budgetMax) / 2
        return fallback.coerceIn(sliderMin, budgetMax)
    }

    companion object {
        private const val OFF_BAND_RATIO = 0.12

        /** 从服务端响应构造；模型不支持调节时返回 null。 */
        fun of(control: ThinkingControlDto?): ThinkingScale? {
            if (control == null) return null
            val stops = if (control.supportsOff) {
                listOf("off") + control.efforts
            } else {
                control.efforts
            }
            val scale = ThinkingScale(
                kind = control.kind,
                stops = stops,
                budgetMin = (control.budgetMin ?: 0L).coerceAtLeast(0L),
                budgetMax = (control.budgetMax ?: 0L).coerceAtLeast(0L),
                budgetDefault = control.budgetDefault,
                supportsOff = control.supportsOff,
            )
            return scale.takeIf { it.adjustable }
        }
    }
}
