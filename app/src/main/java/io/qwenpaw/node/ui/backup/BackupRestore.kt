package io.qwenpaw.node.ui.backup

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.BackupDetailDto
import io.qwenpaw.node.data.model.RestoreBackupRequestDto
import io.qwenpaw.node.ui.i18n.Texts

/** 恢复方式。`full` 覆盖整包，`custom` 按勾选项恢复。 */
enum class RestoreMode(val apiValue: String, @StringRes val labelRes: Int) {
    FULL("full", R.string.backup_restore_mode_full),
    CUSTOM("custom", R.string.backup_restore_mode_custom),
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
            RestoreMode.FULL -> Texts.get(R.string.backup_restore_impact_mode_full)
            RestoreMode.CUSTOM -> Texts.get(R.string.backup_restore_impact_mode_custom)
        }
        val total = backup.workspaceStats.size
        lines += when {
            mode == RestoreMode.FULL ->
                Texts.quantity(R.plurals.backup_restore_impact_agents_all, total, total)
            agentIds.isEmpty() -> Texts.get(R.string.backup_restore_impact_agents_none)
            else -> Texts.quantity(
                R.plurals.backup_restore_impact_agents_selected,
                agentIds.size,
                agentIds.size,
                total,
            )
        }
        lines += if (includeGlobalConfig) {
            Texts.get(R.string.backup_restore_impact_global_overwrite)
        } else {
            Texts.get(R.string.backup_restore_impact_global_keep)
        }
        lines += when {
            !backup.scope.includeSecrets -> Texts.get(R.string.backup_restore_impact_secrets_absent)
            secretsEffective -> Texts.get(R.string.backup_restore_impact_secrets_overwrite)
            else -> Texts.get(R.string.backup_restore_impact_secrets_keep)
        }
        lines += if (includeSkillPool) {
            Texts.get(R.string.backup_restore_impact_skills_overwrite)
        } else {
            Texts.get(R.string.backup_restore_impact_skills_keep)
        }
        if (mode == RestoreMode.FULL) {
            lines += Texts.get(R.string.backup_restore_impact_new_agents)
        }
        if (trustCode != null) {
            lines += when (trustMode()) {
                null -> Texts.get(R.string.backup_restore_impact_signature_unknown)
                else -> Texts.get(R.string.backup_restore_impact_signature_foreign)
            }
        }
        lines += Texts.get(R.string.backup_restore_impact_snapshot)
        return lines
    }
}
