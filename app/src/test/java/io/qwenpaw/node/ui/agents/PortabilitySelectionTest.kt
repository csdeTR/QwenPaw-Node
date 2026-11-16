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
