package io.qwenpaw.node.ui.settings

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.PoolModelDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@StringRes
fun thinkingKindLabelRes(kind: String): Int = when (kind) {
    "unsupported" -> R.string.models_model_thinking_unsupported
    "effort" -> R.string.models_model_thinking_effort
    "budget" -> R.string.models_model_thinking_budget
    else -> R.string.models_model_thinking_unknown
}

@StringRes
fun thinkingWireLabelRes(wire: String?): Int? = when (wire) {
    "native" -> R.string.models_wire_native
    "compat_budget" -> R.string.models_wire_compat_budget
    "compat_effort" -> R.string.models_wire_compat_effort
    "anthropic_budget" -> R.string.models_wire_anthropic_budget
    "anthropic_adaptive" -> R.string.models_wire_anthropic_adaptive
    "gemini_budget" -> R.string.models_wire_gemini_budget
    "gemini_level" -> R.string.models_wire_gemini_level
    else -> null
}

@StringRes
fun effortLabelRes(effort: String?): Int? = when (effort) {
    "minimal" -> R.string.models_effort_minimal
    "low" -> R.string.models_effort_low
    "medium" -> R.string.models_effort_medium
    "high" -> R.string.models_effort_high
    "xhigh" -> R.string.models_effort_xhigh
    "max" -> R.string.models_effort_max
    else -> null
}

@StringRes
fun contextSourceLabelRes(source: String?): Int? = when (source?.lowercase()) {
    "api" -> R.string.models_model_source_api
    "user" -> R.string.models_model_source_user
    "automatic" -> R.string.models_model_source_automatic
    "metadata", "meta" -> R.string.models_model_source_meta
    "provider", "adapter" -> R.string.models_model_source_provider
    "model", "template" -> R.string.models_model_source_model
    "default" -> R.string.models_model_source_default
    "unknown" -> R.string.models_model_source_unknown
    else -> null
}

/** 切换思考类型时清掉不属于新类型的值，避免把预算带到档位或反过来。 */
fun ModelDetailDraft.withThinkingKind(kind: String): ModelDetailDraft = copy(
    thinkingKind = kind,
    // effort 与 budget 的 wire 取值族不同，切换类型时不能沿用。
    thinkingWire = if (kind == thinkingKind) thinkingWire else "",
    thinkingEfforts = if (kind == "effort") thinkingEfforts else emptySet(),
    thinkingBudgetMin = if (kind == "budget") thinkingBudgetMin else "",
    thinkingBudgetMax = if (kind == "budget") thinkingBudgetMax else "",
    thinkingEnabled = if (kind == "unknown" || kind == "unsupported") null else thinkingEnabled,
    thinkingBudget = if (kind == "budget") thinkingBudget else "",
    reasoningEffort = if (kind == "effort") reasoningEffort else "",
)

/**
 * 模型配置的提交体。
 *
 * [kwargs] 由调用方先校验为 JSON 对象；[existing] 用来决定是否要显式清空
 * 原本存在的思考声明。非对应类型不再提交残留的 budget/effort。
 */
fun ModelDetailDraft.toConfigureBody(kwargs: JsonObject, existing: PoolModelDto): JsonObject {
    val maxInput = maxInput.trim()
    val maxOutput = maxOutput.trim().toLongOrNull()
    val finalKwargs = JsonObject(
        kwargs.toMutableMap().apply {
            if (maxOutput != null) {
                put("max_tokens", JsonPrimitive(maxOutput))
            } else {
                remove("max_tokens")
            }
        },
    )
    val thinkingControl = buildJsonObject {
        put("kind", thinkingKind)
        if (thinkingWire.isNotBlank()) put("wire", thinkingWire)
        if (thinkingKind == "effort") {
            put("efforts", JsonArray(thinkingEfforts.map { JsonPrimitive(it) }))
        }
        if (thinkingKind == "budget") {
            thinkingBudgetMin.trim().toLongOrNull()?.let { put("budget_min", it) }
            thinkingBudgetMax.trim().toLongOrNull()?.let { put("budget_max", it) }
        }
        put("supports_off", thinkingSupportsOff)
    }
    return buildJsonObject {
        put("model_type", modelType)
        put("supports_image", supportsImage?.let { JsonPrimitive(it) } ?: JsonNull)
        put("supports_audio", supportsAudio?.let { JsonPrimitive(it) } ?: JsonNull)
        put("supports_video", supportsVideo?.let { JsonPrimitive(it) } ?: JsonNull)
        put(
            "supports_tool_calling",
            supportsToolCalling?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        put(
            "max_input_length",
            maxInput.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        put("generate_kwargs", finalKwargs)
        put("relay_reasoning", relayReasoning)
        put("thinking_enabled", thinkingEnabled?.let { JsonPrimitive(it) } ?: JsonNull)
        put(
            "thinking_budget",
            if (thinkingKind == "budget") {
                thinkingBudget.trim().toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
            } else {
                JsonNull
            },
        )
        put(
            "reasoning_effort",
            if (thinkingKind == "effort") {
                reasoningEffort.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull
            } else {
                JsonNull
            },
        )
        if (thinkingKind == "effort" || thinkingKind == "budget" || existing.thinkingControl != null) {
            put("thinking_control", thinkingControl)
        }
    }
}
