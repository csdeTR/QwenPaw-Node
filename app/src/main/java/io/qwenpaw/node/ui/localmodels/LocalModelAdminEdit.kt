package io.qwenpaw.node.ui.localmodels

import io.qwenpaw.node.data.model.LocalModelConfigDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 配置表单里的错误落在哪个字段上。 */
enum class LocalModelConfigField { CONTEXT, PORT, KWARGS }

/**
 * 错误种类。
 *
 * 与 MCP 编辑页同一条规则：纯逻辑不持有资源，只给种类，文案由界面按种类映射。
 * 这样这一层可以在 JVM 单测里断言，不需要安装 [io.qwenpaw.node.ui.i18n.Texts]。
 */
enum class LocalModelConfigIssue {
    CONTEXT_NUMBER,
    CONTEXT_TOO_SMALL,
    PORT_NUMBER,
    PORT_RANGE,
    KWARGS_JSON,
    KWARGS_OBJECT,
}

/** 一条校验错误。 */
data class LocalModelConfigError(
    val field: LocalModelConfigField,
    val issue: LocalModelConfigIssue,
)

/**
 * 配置草稿。
 *
 * 文本字段保留用户原始输入，只在提交时解析，来回切换焦点不会改写用户写下的空格与换行。
 * [original] 是读取到的服务端配置，用于「只提交改动过的字段」与判断未保存更改。
 *
 * 端口留空表示自动端口：原值本来有端口时提交显式 `null`，原值就是自动时不提交。
 */
data class LocalModelConfigDraft(
    val contextText: String = "",
    val portText: String = "",
    val kwargsText: String = "",
    val original: LocalModelConfigDto? = null,
) {
    /** 是否有改动需要提交。文本写错也算有改动，否则保存按钮一直灰着，用户看不到错误。 */
    val isDirty: Boolean get() = LocalModelAdminEdit.hasChanges(this)
}

/**
 * 一次保存要提交的内容，全部为空表示没有改动。
 *
 * [clearPort] 与 `port = null` 必须分开：请求体里不写 `port` 表示「保持原值」，
 * 写 `null` 表示「改回自动端口」，两者只差一个字段，含义相反。
 */
data class LocalModelConfigChanges(
    val maxContextLength: Int? = null,
    val port: Int? = null,
    val clearPort: Boolean = false,
    val generateKwargs: JsonObject? = null,
) {
    val isEmpty: Boolean
        get() = maxContextLength == null && port == null && !clearPort && generateKwargs == null
}

/**
 * 本地模型配置的校验与差异计算。
 *
 * 边界只有三条：
 * - **上下文长度不得低于 [MIN_CONTEXT_LENGTH]**。运行时按这个值向 llama.cpp 申请 KV 缓存，
 *   小于 32768 时服务端会拒绝或与模型的实际上下文不匹配，留在客户端拦下比等服务端回 400 省一轮往返。
 * - **端口必须在 1–65535**。0 与负值在服务端会被当成非法端口，65536 以上越界。
 * - **生成参数必须是 JSON 对象**。`generate_kwargs` 是原样透传给生成请求的参数表，
 *   顶层是数组或标量时服务端无法按键取值。
 *
 * 留空的字段一律视为「未改动」：服务端的 `PUT` 是部分更新，漏写即保持原值。
 * 唯一无法表达的是「清空生成参数」——`null` 在序列化配置里会被省略，
 * 因此把高级项删空与不填等价，界面上要写明这一点。
 */
object LocalModelAdminEdit {

    /** 运行时的上下文下限。 */
    const val MIN_CONTEXT_LENGTH = 32_768

    const val PORT_MIN = 1
    const val PORT_MAX = 65_535

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true }

    /** 用服务端配置生成草稿。空对象按「没有生成参数」处理，避免高级项里出现一对空花括号。 */
    fun from(config: LocalModelConfigDto): LocalModelConfigDraft = LocalModelConfigDraft(
        contextText = config.maxContextLength?.toString().orEmpty(),
        portText = config.port?.toString().orEmpty(),
        kwargsText = config.generateKwargs?.takeIf { it.isNotEmpty() }?.let(::format).orEmpty(),
        original = config,
    )

    private fun format(value: JsonObject): String =
        runCatching { prettyJson.encodeToString(JsonObject.serializer(), value) }.getOrDefault("")

    fun parseContext(text: String): Int? = text.trim().toIntOrNull()

    fun parsePort(text: String): Int? = text.trim().toIntOrNull()

    /** 高级项解析；留空、语法错误或顶层不是对象时返回 null。 */
    fun parseKwargs(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull()

    fun validate(draft: LocalModelConfigDraft): List<LocalModelConfigError> {
        val errors = mutableListOf<LocalModelConfigError>()

        val contextText = draft.contextText.trim()
        if (contextText.isNotEmpty()) {
            val value = parseContext(contextText)
            when {
                value == null -> errors += LocalModelConfigError(
                    LocalModelConfigField.CONTEXT,
                    LocalModelConfigIssue.CONTEXT_NUMBER,
                )

                value < MIN_CONTEXT_LENGTH -> errors += LocalModelConfigError(
                    LocalModelConfigField.CONTEXT,
                    LocalModelConfigIssue.CONTEXT_TOO_SMALL,
                )
            }
        }

        val portText = draft.portText.trim()
        if (portText.isNotEmpty()) {
            val value = parsePort(portText)
            when {
                value == null -> errors += LocalModelConfigError(
                    LocalModelConfigField.PORT,
                    LocalModelConfigIssue.PORT_NUMBER,
                )

                value !in PORT_MIN..PORT_MAX -> errors += LocalModelConfigError(
                    LocalModelConfigField.PORT,
                    LocalModelConfigIssue.PORT_RANGE,
                )
            }
        }

        val kwargsText = draft.kwargsText.trim()
        if (kwargsText.isNotEmpty()) {
            val element = runCatching { json.parseToJsonElement(kwargsText) }.getOrNull()
            when {
                element == null -> errors += LocalModelConfigError(
                    LocalModelConfigField.KWARGS,
                    LocalModelConfigIssue.KWARGS_JSON,
                )

                element !is JsonObject -> errors += LocalModelConfigError(
                    LocalModelConfigField.KWARGS,
                    LocalModelConfigIssue.KWARGS_OBJECT,
                )
            }
        }
        return errors
    }

    /**
     * 与原值逐字段比对，只带回改动过的字段。
     *
     * 解析不出来的文本一律当作没改：调用方先跑 [validate]，这条路径只处理已经合法的草稿。
     */
    fun changesOf(draft: LocalModelConfigDraft): LocalModelConfigChanges {
        val original = draft.original
        var out = LocalModelConfigChanges()

        val contextText = draft.contextText.trim()
        val context = if (contextText.isEmpty()) null else parseContext(contextText)
        if (context != null && context != original?.maxContextLength) {
            out = out.copy(maxContextLength = context)
        }

        val portText = draft.portText.trim()
        if (portText.isEmpty()) {
            if (original?.port != null) out = out.copy(clearPort = true)
        } else {
            val port = parsePort(portText)
            if (port != null && port != original?.port) out = out.copy(port = port)
        }

        val kwargs = parseKwargs(draft.kwargsText)
        if (kwargs != null && kwargs != original?.generateKwargs) {
            out = out.copy(generateKwargs = kwargs)
        }
        return out
    }

    /** 是否存在需要提交的改动。语法错误也算，理由见 [LocalModelConfigDraft.isDirty]。 */
    fun hasChanges(draft: LocalModelConfigDraft): Boolean {
        if (!changesOf(draft).isEmpty) return true
        val original = draft.original
        if (draft.contextText.trim() != original?.maxContextLength?.toString().orEmpty()) return true
        if (draft.portText.trim() != original?.port?.toString().orEmpty()) return true
        val kwargsText = draft.kwargsText.trim()
        if (kwargsText.isEmpty()) return false
        return parseKwargs(kwargsText) == null
    }
}
