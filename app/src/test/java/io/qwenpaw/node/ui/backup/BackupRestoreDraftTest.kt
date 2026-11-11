package io.qwenpaw.node.ui.backup

import io.qwenpaw.node.data.model.BackupAgentStatsDto
import io.qwenpaw.node.data.model.BackupDetailDto
import io.qwenpaw.node.data.model.BackupScopeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复草稿的影响文案与请求映射。
 *
 * 这里覆盖的都是「界面承诺」与「实际下发」可能脱节的地方：
 * 备份不含密钥时不能把开关值带进请求；full 模式必须提示会移除新建智能体。
 */
class BackupRestoreDraftTest {

    private fun detail(scope: BackupScopeDto = BackupScopeDto()) = BackupDetailDto(
        id = "b1",
        name = "夜间",
        scope = scope,
        agentCount = 2,
        workspaceStats = mapOf(
            "a1" to BackupAgentStatsDto(files = 3, size = 100),
            "a2" to BackupAgentStatsDto(files = 1, size = 20),
        ),
    )

    @Test
    fun `custom default selects every agent in backup`() {
        val draft = RestoreDraft(detail())
        assertTrue(draft.includeAgents)
        assertTrue(draft.impacts().any { it.contains("已选 2 个，共 2 个") })
        assertTrue(draft.impacts().none { it.contains("新建智能体") })
    }

    @Test
    fun `removing all agents turns workspace restore off`() {
        val draft = RestoreDraft(detail()).copy(agentIds = emptySet())
        assertFalse(draft.includeAgents)
        assertTrue(draft.impacts().any { it.contains("智能体工作区：不恢复") })
        assertFalse(draft.toRequest().includeAgents)
    }

    @Test
    fun `full mode warns about removing new agents`() {
        val draft = RestoreDraft(detail()).withMode(RestoreMode.FULL)
        val impacts = draft.impacts()
        assertTrue(impacts.any { it.contains("整包替换") })
        assertTrue(impacts.any { it.contains("全部 2 个") })
        assertTrue(impacts.any { it.contains("备份之后新建的智能体会从注册表中移除") })
        val request = draft.toRequest()
        assertEquals("full", request.mode)
        assertTrue(request.includeAgents)
        assertTrue(request.agentIds.isEmpty())
    }

    @Test
    fun `secrets stay off when backup has none`() {
        val draft = RestoreDraft(detail()).copy(includeSecrets = true)
        val request = draft.toRequest()
        assertFalse(request.includeSecrets)
        assertTrue(draft.impacts().any { it.contains("该备份未包含密钥") })
    }

    @Test
    fun `secrets request only when backup includes them`() {
        val draft = RestoreDraft(detail(BackupScopeDto(includeSecrets = true)))
            .copy(includeSecrets = true)
        assertTrue(draft.toRequest().includeSecrets)
        assertTrue(draft.impacts().any { it.contains("覆盖服务端当前密钥") })
    }

    @Test
    fun `trust mode maps only known signature failures`() {
        assertEquals(
            "legacy",
            RestoreDraft(detail()).copy(trustCode = "backup_legacy_unsigned").trustMode(),
        )
        assertEquals(
            "foreign",
            RestoreDraft(detail()).copy(trustCode = "backup_signature_mismatch").trustMode(),
        )
        assertNull(
            RestoreDraft(detail()).copy(trustCode = "backup_unknown_signature_scheme").trustMode(),
        )
        val unknown = RestoreDraft(detail()).copy(trustCode = "backup_unknown_signature_scheme")
        assertNull(unknown.toRequest().trustMode)
        assertTrue(unknown.impacts().any { it.contains("无法验证") })
    }

    @Test
    fun `skills and global config follow the switches`() {
        val draft = RestoreDraft(detail()).copy(
            includeGlobalConfig = false,
            includeSkillPool = false,
        )
        val request = draft.toRequest()
        assertFalse(request.includeGlobalConfig)
        assertFalse(request.includeSkillPool)
        assertTrue(draft.impacts().any { it.contains("全局配置：保留") })
        assertTrue(draft.impacts().any { it.contains("技能池：保留") })
    }
}
