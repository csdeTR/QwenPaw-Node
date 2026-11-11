package io.qwenpaw.node.ui.checkpoint

import io.qwenpaw.node.data.model.CheckpointNodeDto
import io.qwenpaw.node.data.model.CheckpointSessionDto
import io.qwenpaw.node.data.model.GcRequestDto
import io.qwenpaw.node.data.model.GcSettingsDto
import io.qwenpaw.node.data.model.RestoreCheckpointRequestDto
import io.qwenpaw.node.data.model.RestoreResultDto

/** 时间线分组。组内节点按时间倒序，最多保留 [CHECKPOINT_GROUP_LIMIT] 条。 */
data class CheckpointGroup(
    val sessionKey: String,
    val title: String,
    val nodes: List<CheckpointNodeDto>,
    val archived: Boolean = false,
)

/** 每组默认展示的节点数。超出部分在界面上以一条「仅显示最近 N 条」说明带过。 */
const val CHECKPOINT_GROUP_LIMIT = 5

/**
 * 把扁平的图谱节点按会话分组。
 *
 * 节点与 sessions 由服务端分别给出：sessions 里有而节点里没有的会话不生成空组，
 * 节点里没有对应 session 条目时退回节点自带的 `session_title`，再退回 `session_key`。
 * 组之间按最新节点的时间倒序，保证刚产生变更的会话排在前面。
 */
fun groupCheckpointNodes(
    nodes: List<CheckpointNodeDto>,
    sessions: List<CheckpointSessionDto>,
    limit: Int = CHECKPOINT_GROUP_LIMIT,
): List<CheckpointGroup> {
    val sessionByKey = sessions.associateBy { it.sessionKey }
    return nodes
        .groupBy { it.sessionKey }
        .map { (key, groupNodes) ->
            val sorted = groupNodes.sortedByDescending { it.timestampMs }.take(limit)
            val session = sessionByKey[key]
            val fallback = sorted.firstOrNull()?.sessionTitle?.takeIf { it.isNotBlank() }
                ?: (session?.sessionId ?: sorted.firstOrNull()?.sessionId)
                    ?.takeIf { it.isNotBlank() }
                    ?.take(8)
                ?: key
            CheckpointGroup(
                sessionKey = key,
                title = session?.title?.takeIf { it.isNotBlank() } ?: fallback,
                nodes = sorted,
                archived = session?.archived == true,
            )
        }
        .sortedByDescending { group -> group.nodes.maxOfOrNull { it.timestampMs } ?: 0L }
}

/** 节点类型的中文标签。未知类型原样显示，便于排查服务端新增的类型。 */
fun checkpointKindLabel(kind: String): String = when (kind) {
    "auto" -> "自动"
    "snap" -> "手动"
    "pre-restore" -> "恢复前"
    "sha" -> "提交"
    else -> kind
}

/**
 * 回滚编辑态。
 *
 * 服务端要求 `include_files=true` 时 `files` 非空，因此勾选文件后仍要至少选中
 * 一个路径；[canApply] 把这条规则收在一处，界面只读结果。
 */
data class CheckpointRestoreDraft(
    val node: CheckpointNodeDto,
    val includeMemory: Boolean = false,
    val includeFiles: Boolean = false,
    val selectedFiles: Set<String> = emptySet(),
) {

    val canApply: Boolean
        get() = (includeMemory || includeFiles) && (!includeFiles || selectedFiles.isNotEmpty())

    fun toRequest(): RestoreCheckpointRequestDto = RestoreCheckpointRequestDto(
        commit = node.commit,
        sessionId = node.sessionId,
        userId = node.userId,
        channel = node.channel,
        includeMemory = includeMemory,
        includeFiles = includeFiles,
        files = if (includeFiles) selectedFiles.toList() else emptyList(),
    )

    /** 预览用：打开文件时不下发具体路径，让服务端返回完整候选列表。 */
    fun toPreviewRequest(): RestoreCheckpointRequestDto = toRequest().copy(files = emptyList())

    /**
     * 确认框逐条列出的影响。
     *
     * [preview] 为 null 表示预览还没返回；此时只说明目标与范围，不承诺文件数量。
     */
    fun impacts(preview: RestoreResultDto? = null): List<String> {
        val lines = mutableListOf<String>()
        val name = node.name.takeIf { it.isNotBlank() }
            ?: node.subject.takeIf { it.isNotBlank() }
            ?: node.sha.take(7)
        lines += "回滚目标：$name（${node.sha.take(7)}）"
        lines += if (includeMemory) {
            "会话记忆：恢复到该检查点"
        } else {
            "会话记忆：保持不变"
        }
        lines += if (!includeFiles) {
            "工作区文件：保持不变"
        } else if (selectedFiles.isEmpty()) {
            "工作区文件：尚未选择"
        } else {
            "工作区文件：恢复 ${selectedFiles.size} 个"
        }
        if (preview != null) {
            if (preview.deletedPaths.isNotEmpty()) {
                lines += "将删除该检查点之后新增的 ${preview.deletedPaths.size} 个文件。"
            }
            if (preview.preRestoreRef != null) {
                lines += "服务端会先创建恢复前快照，可从检查点回滚本次操作。"
            }
        }
        if (!canApply) {
            lines += "至少选择会话记忆或工作区文件之一。"
        }
        return lines
    }
}

/**
 * 清理策略编辑态。
 *
 * 输入框以字符串保存，提交前统一校验范围；服务端三项都是必填，
 * 任一非法就整体拦截，不做部分下发。
 */
data class GcDraft(
    val keepCount: String = "20",
    val keepDays: String = "30",
    val preRestoreDays: String = "7",
    val compact: Boolean = false,
) {

    fun toSettings(): GcSettingsDto? {
        val count = keepCount.trim().toIntOrNull() ?: return null
        val days = keepDays.trim().toIntOrNull() ?: return null
        val preRestore = preRestoreDays.trim().toIntOrNull() ?: return null
        if (count !in 0..1_000_000 || days !in 0..36_500 || preRestore !in 0..36_500) return null
        return GcSettingsDto(count, days, preRestore)
    }

    fun toRequest(settings: GcSettingsDto): GcRequestDto = GcRequestDto(
        compact = compact,
        keepCount = settings.gcKeepCount,
        keepDays = settings.gcKeepDays,
        preRestoreDays = settings.preRestoreRetentionDays,
    )

    /** 校验失败时的说明；合法时返回 null。 */
    fun validationError(): String? {
        if (keepCount.trim().toIntOrNull() == null) return "保留数量需为整数"
        if (keepDays.trim().toIntOrNull() == null) return "保留天数需为整数"
        if (preRestoreDays.trim().toIntOrNull() == null) return "恢复前快照保留天数需为整数"
        if (keepCount.trim().toInt() !in 0..1_000_000) return "保留数量需在 0 到 1000000 之间"
        if (keepDays.trim().toInt() !in 0..36_500) return "保留天数需在 0 到 36500 之间"
        if (preRestoreDays.trim().toInt() !in 0..36_500) return "恢复前快照保留天数需在 0 到 36500 之间"
        return null
    }

    companion object {
        fun from(settings: GcSettingsDto): GcDraft = GcDraft(
            keepCount = settings.gcKeepCount.toString(),
            keepDays = settings.gcKeepDays.toString(),
            preRestoreDays = settings.preRestoreRetentionDays.toString(),
        )
    }
}
