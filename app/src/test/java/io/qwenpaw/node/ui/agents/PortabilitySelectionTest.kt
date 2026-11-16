package io.qwenpaw.node.ui.agents

import io.qwenpaw.node.data.model.PortabilityAssetDto
import io.qwenpaw.node.data.model.PortabilityJobDto
import io.qwenpaw.node.data.model.PortabilityProviderDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入向导的步骤推导与选择组装。 */
class PortabilitySelectionTest {

    private fun asset(type: String, id: String, state: String = "ready", blocked: String = "") =
        PortabilityAssetDto(
            assetType = type,
            sourceId = id,
            name = id,
            state = state,
            blockedReason = blocked,
        )

    private fun job(vararg providers: PortabilityProviderDto) =
        PortabilityJobDto(jobId = "j1", providers = providers.toList())

    private val codex = PortabilityProviderDto(
        source = "codex",
        state = "ready",
        sessionsTotal = 3,
        assets = listOf(
            asset("memory", "m1"),
            asset("skills", "s1"),
            asset("plugins", "p1"),
            asset("mcp", "existing", state = "existing"),
            asset("cron", "bad", blocked = "需要先导入会话"),
        ),
    )

    @Test
    fun `step follows job state`() {
        assertEquals(ImportStep.SCANNING, stepFor(job().copy(state = "scanning")))
        assertEquals(ImportStep.INVENTORY, stepFor(job().copy(state = "ready")))
        assertEquals(ImportStep.RUNNING, stepFor(job().copy(state = "running")))
        assertEquals(ImportStep.RESULT, stepFor(job().copy(state = "completed")))
        assertEquals(ImportStep.RESULT, stepFor(job().copy(state = "interrupted")))
    }

    @Test
    fun `default selection excludes existing blocked and failed`() {
        val keys = defaultAssetKeys(job(codex))
        assertEquals(3, keys.size)
        assertTrue("codex|memory|m1" in keys)
        assertFalse("codex|mcp|existing" in keys)
        assertFalse("codex|cron|bad" in keys)
    }

    @Test
    fun `server singular asset types map to plural selection fields`() {
        val provider = PortabilityProviderDto(
            source = "codex",
            state = "ready",
            assets = listOf(
                asset("memory", "m2"),
                asset("scheduled_task", "c2"),
                asset("skill", "s2"),
                asset("mcp", "mcp2"),
                asset("plugin", "p2"),
            ),
        )
        val job = job(provider)
        val selections = buildSelections(job, defaultAssetKeys(job), mapOf("codex" to true))
        val selection = selections.getValue("codex")
        assertEquals(listOf("m2"), selection.memory)
        assertEquals(listOf("c2"), selection.cron)
        assertEquals(listOf("s2"), selection.skills)
        assertEquals(listOf("mcp2"), selection.mcp)
        assertEquals(listOf("p2"), selection.plugins)
    }

    @Test
    fun `selection field accepts both singular and plural asset types`() {
        assertEquals("skills", selectionField("skill"))
        assertEquals("skills", selectionField("skills"))
        assertEquals("plugins", selectionField("plugin"))
        assertEquals("plugins", selectionField("plugins"))
        assertEquals("cron", selectionField("scheduled_task"))
        assertEquals("cron", selectionField("cron"))
        assertEquals(null, selectionField("unknown"))
    }

    @Test
    fun `build selections groups by asset type`() {
        val keys = defaultAssetKeys(job(codex))
        val selections = buildSelections(job(codex), keys, mapOf("codex" to false))
        val selection = selections.getValue("codex")
        assertFalse(selection.sessions)
        assertEquals(listOf("m1"), selection.memory)
        assertEquals(listOf("s1"), selection.skills)
        assertEquals(listOf("p1"), selection.plugins)
        assertTrue(selection.mcp.isEmpty())
        assertTrue(selection.cron.isEmpty())
    }
}
