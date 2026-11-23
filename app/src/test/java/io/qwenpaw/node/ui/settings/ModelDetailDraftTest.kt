package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.ModelSlotDto
import io.qwenpaw.node.data.model.PoolModelDto
import io.qwenpaw.node.data.model.ProviderDto
import io.qwenpaw.node.data.model.ThinkingControlDto
import io.qwenpaw.node.data.model.resolveModelDetail
import io.qwenpaw.node.data.model.toPoolModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `decision switch keeps existing capability and thinking settings`() {
        val model = PoolModelDto(
            id = "m",
            name = "Model",
            modelType = "chat",
            supportsImage = true,
            supportsAudio = true,
            supportsVideo = true,
            supportsToolCalling = true,
            maxInputLength = 128000,
            maxInputLengthConfigured = true,
            thinkingControl = ThinkingControlDto(kind = "effort", efforts = listOf("low", "high")),
            thinkingEnabled = true,
            reasoningEffort = "high",
        )
        val draft = ModelDetailDraft.from(model).copy(modelType = "decision")
        val body = draft.toConfigureBody(buildJsonObject {}, model)

        assertEquals("decision", body["model_type"]!!.jsonPrimitive.content)
        assertEquals(true, body["supports_image"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, body["supports_audio"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, body["supports_video"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, body["supports_tool_calling"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(128000L, body["max_input_length"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            "effort",
            body["thinking_control"]!!.jsonObject["kind"]!!.jsonPrimitive.content,
        )
        assertEquals(true, body["thinking_enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
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

    @Test
    fun `slot dto converts to pool model without losing config`() {
        val slot = ModelSlotDto(
            id = "m",
            name = "Model",
            modelType = "decision",
            supportsAudio = true,
            maxInputLength = 128000,
            maxInputLengthAutoDetected = 200000,
            maxInputLengthConfigured = true,
            contextLengthSource = "user",
            maxOutputLength = 8192,
            thinkingControl = ThinkingControlDto(kind = "budget", budgetMax = 4096),
            thinkingEnabled = true,
            thinkingBudget = 2048,
            relayReasoning = false,
        )
        val pool = slot.toPoolModel()
        assertEquals("Model", pool.name)
        assertEquals("decision", pool.modelType)
        assertEquals(true, pool.supportsAudio)
        assertEquals(128000L, pool.maxInputLength)
        assertEquals(200000L, pool.maxInputLengthAutoDetected)
        assertEquals(true, pool.maxInputLengthConfigured)
        assertEquals("user", pool.contextLengthSource)
        assertEquals(8192L, pool.maxOutputLength)
        assertEquals("budget", pool.thinkingControl?.kind)
        assertEquals(true, pool.thinkingEnabled)
        assertEquals(2048L, pool.thinkingBudget)
        assertEquals(false, pool.relayReasoning)
    }

    @Test
    fun `detail lookup falls back to provider models outside the pool page`() {
        val provider = ProviderDto(
            id = "p",
            models = listOf(ModelSlotDto(id = "extra", name = "Extra")),
        )
        val resolved = resolveModelDetail("extra", emptyList(), provider)
        assertNotNull(resolved)
        assertEquals("Extra", resolved?.name)

        val poolModel = PoolModelDto(id = "extra", name = "From Pool")
        assertEquals("From Pool", resolveModelDetail("extra", listOf(poolModel), provider)?.name)
    }

    @Test
    fun `switching thinking kind clears stale values`() {
        val effort = ModelDetailDraft(
            thinkingKind = "effort",
            thinkingWire = "compat_effort",
            thinkingEfforts = setOf("low", "high"),
            thinkingBudgetMin = "1",
            thinkingBudgetMax = "4096",
            thinkingBudget = "2048",
            reasoningEffort = "high",
        )
        val budget = effort.withThinkingKind("budget")
        assertEquals("", budget.thinkingWire)
        assertTrue(budget.thinkingEfforts.isEmpty())
        assertEquals("", budget.reasoningEffort)
        assertEquals("1", budget.thinkingBudgetMin)
        assertEquals("4096", budget.thinkingBudgetMax)

        val unsupported = budget.withThinkingKind("unsupported")
        assertEquals("", unsupported.thinkingWire)
        assertNull(unsupported.thinkingEnabled)
        assertEquals("", unsupported.thinkingBudget)
    }

    @Test
    fun `budget config only submits budget fields`() {
        val draft = ModelDetailDraft(
            thinkingKind = "budget",
            thinkingWire = "compat_budget",
            thinkingBudgetMin = "1",
            thinkingBudgetMax = "8192",
            thinkingBudget = "2048",
            thinkingEnabled = true,
            reasoningEffort = "high",
            maxOutput = "4096",
        )
        val body = draft.toConfigureBody(buildJsonObject {}, PoolModelDto(id = "m"))
        val control = body["thinking_control"]!!.jsonObject
        assertEquals("budget", control["kind"]!!.jsonPrimitive.content)
        assertEquals(2048L, body["thinking_budget"]!!.jsonPrimitive.content.toLong())
        assertEquals(JsonNull, body["reasoning_effort"])
        assertEquals(
            4096L,
            body["generate_kwargs"]!!.jsonObject["max_tokens"]!!.jsonPrimitive.content.toLong(),
        )
    }

    @Test
    fun `effort config omits budget and clears legacy control for unknown`() {
        val draft = ModelDetailDraft(
            thinkingKind = "effort",
            thinkingWire = "compat_effort",
            reasoningEffort = "high",
            thinkingEnabled = true,
            thinkingBudget = "2048",
        )
        val body = draft.toConfigureBody(buildJsonObject {}, PoolModelDto(id = "m"))
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["thinking_budget"])
        assertEquals("effort", body["thinking_control"]!!.jsonObject["kind"]!!.jsonPrimitive.content)

        val unknown = draft.withThinkingKind("unknown").toConfigureBody(
            buildJsonObject {},
            PoolModelDto(
                id = "m",
                thinkingControl = ThinkingControlDto(kind = "effort"),
            ),
        )
        assertEquals("unknown", unknown["thinking_control"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("unknown 不应提交旧的预算", JsonNull, unknown["thinking_budget"])
    }
}
