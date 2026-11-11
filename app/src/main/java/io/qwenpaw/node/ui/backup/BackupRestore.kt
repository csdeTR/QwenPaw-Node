package io.qwenpaw.node.ui.backup

import io.qwenpaw.node.data.model.BackupDetailDto
import io.qwenpaw.node.data.model.RestoreBackupRequestDto

/** 恢复方式。`full` 覆盖整包，`custom` 按勾选项恢复。 */
enum class RestoreMode(val apiValue: String, val label: String) {
    FULL("full", "整包恢复"),
    CUSTOM("custom", "选择性恢复"),
}

/**
 * 备份恢复的编辑态。
 *
 * 默认值从备份自身推导：备份没有密钥时不允许打开密钥开关，避免界面承诺
 * 一个包里并不存在的内容。`trustCode` 由服务端 400 回填，非空时界面把
 * 「恢复」换成「信任并恢复」。
 */
data class RestoreDraft(
    val backup: BackupDetailDto,
    val mode: RestoreMode = RestoreMode.CUSTOM,
    val agentIds: Set<String> = backup.workspaceStats.keys,
    val includeGlobalConfig: Boolean = backup.scope.includeGlobalConfig,
    val includeSecrets: Boolean = false,
    val includeSkillPool: Boolean = backup.scope.includeSkillPool,
    val trustCode: String? = null,
) {

    /** `full` 与「custom 且选了智能体」才真正恢复工作区。 */
    val includeAgents: Boolean
        get() = mode == RestoreMode.FULL || agentIds.isNotEmpty()

    /** 备份不含密钥时，开关即使被打开也不下发。 */
    val secretsEffective: Boolean
        get() = includeSecrets && backup.scope.includeSecrets

    /** 密钥开关是否可操作。 */
    val secretsAvailable: Boolean
        get() = backup.scope.includeSecrets

    fun toggleAgent(id: String): RestoreDraft = copy(
        agentIds = if (id in agentIds) agentIds - id else agentIds + id,
    )

    fun withMode(mode: RestoreMode): RestoreDraft = copy(mode = mode)

    /**
     * `trust_mode` 只在服务端明确给出可信任的签名错误时才有值。
     * `backup_unknown_signature_scheme` 没有信任通路，这里返回 null。
     */
    fun trustMode(): String? = when (trustCode) {
        "backup_legacy_unsigned" -> "legacy"
        "backup_signature_mismatch" -> "foreign"
        else -> null
    }

    fun toRequest(): RestoreBackupRequestDto = RestoreBackupRequestDto(
        mode = mode.apiValue,
        includeAgents = includeAgents,
        agentIds = if (mode == RestoreMode.FULL) emptyList() else agentIds.toList(),
        includeGlobalConfig = includeGlobalConfig,
        includeSecrets = secretsEffective,
        includeSkillPool = includeSkillPool,
        trustMode = trustMode(),
    )

    /**
     * 确认框逐条列出的影响。
     *
     * 顺序按「影响范围从大到小」排：恢复方式、智能体、全局配置、密钥、技能池，
     * 最后才是不可逆提示。文案只写用户能感知的结果，不复述字段名。
     */
    fun impacts(): List<String> {
        val lines = mutableListOf<String>()
        lines += when (mode) {
            RestoreMode.FULL -> "恢复方式：整包替换服务端现有数据"
            RestoreMode.CUSTOM -> "恢复方式：仅恢复勾选的内容"
        }
        val total = backup.workspaceStats.size
        lines += when {
            mode == RestoreMode.FULL -> "智能体工作区：全部 $total 个"
            agentIds.isEmpty() -> "智能体工作区：不恢复"
            else -> "智能体工作区：已选 ${agentIds.size} 个，共 $total 个"
        }
        lines += if (includeGlobalConfig) {
            "全局配置：覆盖服务端当前配置"
        } else {
            "全局配置：保留服务端当前配置"
        }
        lines += when {
            !backup.scope.includeSecrets -> "密钥：该备份未包含密钥，服务端现有密钥保持不变"
            secretsEffective -> "密钥：覆盖服务端当前密钥"
            else -> "密钥：保留服务端当前密钥"
        }
        lines += if (includeSkillPool) {
            "技能池：覆盖服务端当前技能池"
        } else {
            "技能池：保留服务端当前技能池"
        }
        if (mode == RestoreMode.FULL) {
            lines += "备份之后新建的智能体会从注册表中移除。"
        }
        if (trustCode != null) {
            lines += when (trustMode()) {
                null -> "该备份的签名方式无法验证，不能继续恢复。"
                else -> "该备份不是本机签名，继续将信任其内容并以本机密钥重新签名。"
            }
        }
        lines += "服务端会在恢复前自动创建安全快照，可用于回滚本次恢复。"
        return lines
    }
}
