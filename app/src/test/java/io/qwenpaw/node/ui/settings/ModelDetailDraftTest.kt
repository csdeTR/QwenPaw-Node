package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.ModelSlotDto
import io.qwenpaw.node.data.model.PoolModelDto
import io.qwenpaw.node.data.model.ThinkingControlDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDetailDraftTest {

    @Test
    fun `max tokens is split out of generate kwargs`() {
        val model = PoolModelDto(
            id = "m",
            generateKwargs = buildJsonObject {
                put("temperature", JsonPrimitive(0.2))
                put("max_tokens", JsonPrimitive(4096))
            },
        )
        val draft = ModelDetailDraft.from(model)
        assertEquals("4096", draft.maxOutput)
        assertFalse(draft.generateKwargs.contains("max_tokens"))
        assertTrue(draft.generateKwargs.contains("temperature"))
    }

    @Test
    fun `null capabilities stay automatic`() {
        val draft = ModelDetailDraft.from(PoolModelDto(id = "m"))
        assertNull(draft.supportsImage)
        assertNull(draft.supportsAudio)
        assertNull(draft.supportsVideo)
        assertNull(draft.supportsToolCalling)
        assertNull(draft.thinkingEnabled)
    }

    @Test
    fun `configured max input is prefilled otherwise empty`() {
        val configured = ModelDetailDraft.from(
            PoolModelDto(
                id = "m",
                maxInputLength = 64000,
                maxInputLengthConfigured = true,
            ),
        )
        assertEquals("64000", configured.maxInput)

        val automatic = ModelDetailDraft.from(
            PoolModelDto(
                id = "m",
                maxInputLength = 64000,
                maxInputLengthConfigured = false,
            ),
        )
        assertEquals("", automatic.maxInput)
    }

    @Test
    fun `thinking control is copied`() {
        val draft = ModelDetailDraft.from(
            PoolModelDto(
                id = "m",
                thinkingControl = ThinkingControlDto(
                    kind = "effort",
                    efforts = listOf("low", "high"),
                    supportsOff = true,
                    wire = "compat_effort",
                ),
                thinkingEnabled = true,
                thinkingBudget = 2048,
                reasoningEffort = "high",
            ),
        )
        assertEquals("effort", draft.thinkingKind)
        assertEquals(setOf("low", "high"), draft.thinkingEfforts)
        assertTrue(draft.thinkingSupportsOff)
        assertEquals("compat_effort", draft.thinkingWire)
        assertEquals(true, draft.thinkingEnabled)
        assertEquals("2048", draft.thinkingBudget)
        assertEquals("high", draft.reasoningEffort)
    }

    @Test
    fun `slot dto keeps new config fields`() {
        val slot = ModelSlotDto(
            id = "m",
            modelType = "decision",
            supportsToolCalling = true,
            maxOutputLength = 8192,
        )
        assertEquals("decision", slot.modelType)
        assertEquals(true, slot.supportsToolCalling)
        assertEquals(8192L, slot.maxOutputLength)
    }
}
