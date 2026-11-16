package io.qwenpaw.node.ui.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.qwenpaw.node.ui.chat.APPROVAL_OPTIONS
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 运行配置的表单值。
 *
 * 数字一律用字符串承载：服务端返回的 22 字段里只有少数几项在界面上出现，
 * 其余必须原样回写。用字符串可以先接受草稿，再在保存时统一校验，
 * 避免 `TextField` 在输入途中把 `1.` 解析失败回写成 1。
 */
data class RunningConfigForm(
    val maxIters: String = "",
    val maxInputLength: String = "",
    val historyMaxLength: String = "",
    val shellCommandTimeout: String = "",
    val llmRetryEnabled: Boolean = true,
    val llmMaxRetries: String = "",
    val approvalLevel: String = "AUTO",
    val compactThresholdRatio: String = "",
)

/**
 * 运行配置的读取、修改与校验。
 *
 * 只碰界面上出现的字段，其余字段与嵌套结构（记忆后端、循环策略等）保持不变。
 * 这条约束由单测兜底：客户端认不出的字段一旦被抹掉，用户会在 Web 端看到配置丢失。
 */
internal object RunningConfigEdit {

    private val COMPACT_PATH = listOf(
        "light_context_config",
        "context_compact_config",
        "compact_threshold_ratio",
    )

    fun fromJson(raw: JsonObject): RunningConfigForm = RunningConfigForm(
        maxIters = raw.textAt("max_iters"),
        maxInputLength = raw.textAt("max_input_length"),
        historyMaxLength = raw.textAt("history_max_length"),
        shellCommandTimeout = raw.textAt("shell_command_timeout"),
        llmRetryEnabled = raw["llm_retry_enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
        llmMaxRetries = raw.textAt("llm_max_retries"),
        approvalLevel = raw["approval_level"]?.jsonPrimitive?.contentOrNull ?: "AUTO",
        compactThresholdRatio = raw.textAt(COMPACT_PATH),
    )

    /** 把表单写回原始 JSON。空字符串表示「本次不改这一项」。 */
    fun apply(raw: JsonObject, form: RunningConfigForm): JsonObject {
        var out = raw
        form.maxIters.trim().toIntOrNull()?.let { out = out.putPath(listOf("max_iters"), JsonPrimitive(it)) }
        form.maxInputLength.trim().toIntOrNull()?.let {
            out = out.putPath(listOf("max_input_length"), JsonPrimitive(it))
        }
        form.historyMaxLength.trim().toIntOrNull()?.let {
            out = out.putPath(listOf("history_max_length"), JsonPrimitive(it))
        }
        form.shellCommandTimeout.trim().toDoubleOrNull()?.let {
            out = out.putPath(listOf("shell_command_timeout"), JsonPrimitive(it))
        }
        out = out.putPath(listOf("llm_retry_enabled"), JsonPrimitive(form.llmRetryEnabled))
        form.llmMaxRetries.trim().toIntOrNull()?.let {
            out = out.putPath(listOf("llm_max_retries"), JsonPrimitive(it))
        }
        out = out.putPath(listOf("approval_level"), JsonPrimitive(form.approvalLevel))
        form.compactThresholdRatio.trim().toDoubleOrNull()?.let {
            out = out.putPath(COMPACT_PATH, JsonPrimitive(it))
        }
        return out
    }

    /** 返回需要展示给用户的错误；null 表示可以提交。 */
    fun validate(form: RunningConfigForm): String? {
        nonNegativeInt(form.maxIters, "最大迭代次数", allowZero = false)?.let { return it }
        nonNegativeInt(form.maxInputLength, "最大输入长度", allowZero = true)?.let { return it }
        nonNegativeInt(form.historyMaxLength, "历史保留长度", allowZero = true)?.let { return it }
        val timeout = form.shellCommandTimeout.trim()
        if (timeout.isNotEmpty()) {
            val value = timeout.toDoubleOrNull()
                ?: return "命令超时必须是数字"
            if (value <= 0) return "命令超时必须大于 0"
        }
        nonNegativeInt(form.llmMaxRetries, "重试次数", allowZero = true)?.let { return it }
        if (APPROVAL_OPTIONS.none { it.code == form.approvalLevel }) return "审批模式取值无效"
        val ratio = form.compactThresholdRatio.trim()
        if (ratio.isNotEmpty()) {
            val value = ratio.toDoubleOrNull() ?: return "上下文压缩阈值必须是数字"
            if (value < 0 || value > 1) return "上下文压缩阈值应在 0 到 1 之间"
        }
        return null
    }

    private fun nonNegativeInt(text: String, label: String, allowZero: Boolean): String? {
        val value = text.trim()
        if (value.isEmpty()) return null
        val parsed = value.toIntOrNull() ?: return "$label 必须是整数"
        val min = if (allowZero) 0 else 1
        if (parsed < min) return "$label 不能小于 $min"
        return null
    }

    private fun JsonObject.textAt(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.textAt(path: List<String>): String {
        var current: JsonElement = this
        for (key in path) {
            current = (current as? JsonObject)?.get(key) ?: return ""
        }
        return current.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    }

    private fun JsonObject.putPath(path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        if (path.size == 1) return JsonObject(this + (key to value))
        val child = (this[key] as? JsonObject) ?: JsonObject(emptyMap())
        return JsonObject(this + (key to child.putPath(path.drop(1), value)))
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = runCatching { jsonPrimitive }.getOrNull()
}
