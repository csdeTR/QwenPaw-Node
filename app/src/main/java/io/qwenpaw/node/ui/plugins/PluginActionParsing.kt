package io.qwenpaw.node.ui.plugins

import io.qwenpaw.node.data.model.ApiErrorDetailDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 插件动作遇到 409 时的归类。
 *
 * 服务端在同一状态码下回不同形状：安装时可能是依赖升级，配置写入时可能是
 * legacy 卸载钩子，启停与卸载时可能是「还有任务在跑」。三者对用户的处置完全不同，
 * 混成一句「冲突」会让用户去点「覆盖安装」，或反过来以为插件已经装上了。
 */
sealed interface PluginConflict {
    /** 需要先做依赖升级；[operationId] 非空时才能进入升级页。 */
    data class DependencyUpgrade(val operationId: String) : PluginConflict

    /** legacy 卸载钩子，确认后带 `confirm_legacy=true` 重试。 */
    data class LegacyHooks(val hooks: List<String>) : PluginConflict

    /** 运行中任务未结束，确认后带 `interrupt_tasks=true` 重试。 */
    data class RunningTasks(val message: String) : PluginConflict

    /** 读不懂的 409。调用方展示服务端原文，不臆造结论。 */
    data class Other(val message: String) : PluginConflict
}

/** 依赖升级操作的阶段。取值来自服务端 `status` / `state`。 */
enum class DependencyStage {
    PREPARING,
    NEEDS_CONFIRMATION,
    APPLYING,
    WAITING_RESTART,
    REPAIR_REQUIRED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

/**
 * 依赖升级操作的可读状态。
 *
 * 服务端没有 DTO，响应形状由实现决定，因此这里只按已知键读取，缺失就留空，
 * 不替服务端补默认结论。界面据此决定显示「应用」还是「等待重启」，两者不能互换。
 */
data class DependencyUpgradeState(
    val operationId: String,
    val stage: DependencyStage = DependencyStage.UNKNOWN,
    /** 服务端原始阶段字符串，未知阶段时直接展示。 */
    val statusText: String = "",
    val message: String = "",
    val error: String = "",
    val needsConfirmation: Boolean = false,
    val canCancel: Boolean = false,
    /** `waiting_restart` 时需要在服务端执行的命令，界面逐条列出。 */
    val maintenanceCommands: List<String> = emptyList(),
) {
    val terminal: Boolean
        get() = stage == DependencyStage.SUCCEEDED ||
            stage == DependencyStage.FAILED ||
            stage == DependencyStage.CANCELLED

    /**
     * 是否继续自动轮询。
     *
     * `waiting_restart` 之后状态要等服务端重启才会变，继续每秒请求只是空转；
     * 交给用户点「重新检查」更诚实。
     */
    val shouldPoll: Boolean
        get() = !terminal && stage != DependencyStage.WAITING_RESTART
}

/** 从 `operation` 对象里取操作 id。已知键都试一遍，取不到就返回 null。 */
fun dependencyOperationIdOf(operation: JsonObject?): String? {
    if (operation == null) return null
    return OPERATION_ID_KEYS.firstNotNullOfOrNull { key ->
        operation.textAt(key)?.takeIf { it.isNotBlank() }
    }
}

/**
 * 把 409 的 detail 归成一类。
 *
 * 判定顺序固定：依赖升级 → legacy 钩子 → 运行中任务 → 其它。
 * 依赖升级排第一是因为它决定了用户该去哪个页面，误判成「同名冲突」会引导用户覆盖安装。
 */
fun pluginConflictOf(detail: ApiErrorDetailDto?): PluginConflict {
    if (detail == null) return PluginConflict.Other("")
    val code = detail.code.lowercase()
    if (code == DEPENDENCY_UPGRADE_REQUIRED || detail.operation != null) {
        return PluginConflict.DependencyUpgrade(dependencyOperationIdOf(detail.operation).orEmpty())
    }
    if (detail.legacyHooks.isNotEmpty() || code.contains("legacy")) {
        return PluginConflict.LegacyHooks(detail.legacyHooks)
    }
    if (!detail.quiescent || detail.requiresConfirmation || code.contains("task")) {
        return PluginConflict.RunningTasks(detail.message)
    }
    return PluginConflict.Other(detail.message)
}

/** 配置写入的 404 且 `unchanged=true`：服务端认为提交内容与原值一致。 */
fun isConfigUnchanged(detail: ApiErrorDetailDto?): Boolean = detail?.unchanged == true

/** 从 detail 里取 400 的逐条错误。空串与重复项去掉，界面逐行展示。 */
fun configErrorsOf(detail: ApiErrorDetailDto?): List<String> =
    detail?.errors.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * 解析 `GET /api/plugins/dependency-upgrades/{id}` 的响应。
 *
 * 已知键：`status` / `state` / `message` / `error` / `needs_confirmation` /
 * `needs_restart` / `can_cancel` / `maintenance_commands`。缺失的键跳过，
 * 不假设服务端一定给。
 */
fun dependencyStateOf(operationId: String, raw: JsonObject): DependencyUpgradeState {
    val statusText = raw.textAt("status") ?: raw.textAt("state") ?: ""
    val needsConfirmation = raw.booleanAt("needs_confirmation")
        ?: raw.booleanAt("requires_confirmation")
        ?: false
    val needsRestart = raw.booleanAt("needs_restart") ?: false
    var stage = stageOf(statusText)
    if (stage == DependencyStage.UNKNOWN && needsRestart) stage = DependencyStage.WAITING_RESTART
    // waiting_restart / repair_required 是已经越过确认点的阶段；服务端若同时回
    // needs_confirmation=true，不能把阶段折回确认态，否则会重新开始轮询且永远不结束。
    if (
        stage != DependencyStage.UNKNOWN &&
        !isTerminalStage(stage) &&
        stage != DependencyStage.WAITING_RESTART &&
        stage != DependencyStage.REPAIR_REQUIRED &&
        needsConfirmation
    ) {
        stage = DependencyStage.NEEDS_CONFIRMATION
    }
    return DependencyUpgradeState(
        operationId = operationId,
        stage = stage,
        statusText = statusText,
        message = raw.textAt("message").orEmpty(),
        error = raw.textAt("error").orEmpty(),
        needsConfirmation = needsConfirmation,
        canCancel = raw.booleanAt("can_cancel") ?: false,
        maintenanceCommands = raw.commandsAt("maintenance_commands"),
    )
}

/** 阶段字符串到枚举。未知值返回 UNKNOWN，由界面显示原文。 */
fun stageOf(status: String): DependencyStage = when (status.trim().lowercase()) {
    "preparing", "pending", "planning", "resolving" -> DependencyStage.PREPARING
    "needs_confirmation", "awaiting_confirmation", "awaiting_approval" ->
        DependencyStage.NEEDS_CONFIRMATION
    "applying", "installing", "upgrading", "running", "in_progress" -> DependencyStage.APPLYING
    "waiting_restart", "needs_restart", "restart_required" -> DependencyStage.WAITING_RESTART
    "repair_required", "repairs_required" -> DependencyStage.REPAIR_REQUIRED
    "succeeded", "success", "completed", "done", "applied" -> DependencyStage.SUCCEEDED
    "failed", "error" -> DependencyStage.FAILED
    "cancelled", "canceled" -> DependencyStage.CANCELLED
    else -> DependencyStage.UNKNOWN
}

/**
 * 配置文本是否可以提交。
 *
 * 只认对象：服务端要的是 `config.plugins.<id>` 这一段，顶层数组或标量写进去
 * 必然被拒，提前在这里拦下比提交后读 400 更省事。
 */
fun isConfigDraftValid(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    return runCatching { jsonParser.parseToJsonElement(trimmed) }.getOrNull() is JsonObject
}

/** 配置文本解析成对象；语法错误或不是对象时返回 null。 */
fun configObjectOf(text: String): JsonObject? =
    runCatching { jsonParser.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull()

private val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun isTerminalStage(stage: DependencyStage): Boolean =
    stage == DependencyStage.SUCCEEDED ||
        stage == DependencyStage.FAILED ||
        stage == DependencyStage.CANCELLED

/** 字符串或标量取值。`null`、对象与数组都返回 null：那是另一个形状，读进来只会误判。 */
private fun JsonObject.textAt(key: String): String? = when (val element = this[key]) {
    is JsonNull, null -> null
    is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
    else -> null
}

private fun JsonObject.booleanAt(key: String): Boolean? = when (val element = this[key]) {
    is JsonPrimitive -> when (element.content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}

/** 命令列表。元素可能是纯字符串，也可能是 `{command: "..."}`。 */
private fun JsonObject.commandsAt(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element -> commandOf(element) }.filter { it.isNotBlank() }
}

private fun commandOf(element: JsonElement): String? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
    is JsonObject -> COMMAND_KEYS.firstNotNullOfOrNull { key ->
        (element[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }
    else -> null
}

private const val DEPENDENCY_UPGRADE_REQUIRED = "dependency_upgrade_required"

/** `operation` 对象的 id 键。服务端未定型，已知写法都试一遍。 */
private val OPERATION_ID_KEYS = listOf("id", "operation_id", "operationId", "upgrade_id")

/** 命令对象的取值键。 */
private val COMMAND_KEYS = listOf("command", "cmd", "line")
