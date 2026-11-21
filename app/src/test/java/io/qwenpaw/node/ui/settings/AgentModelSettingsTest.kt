package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.ActiveModelDto
import io.qwenpaw.node.data.model.ThinkingControlDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能体模型设置的读写形状。
 *
 * 这份快照是「整组提交」的：漏写一个键，服务端就保留原值，
 * 界面上的「清除子智能体模型」会静默失效。因此每个键的缺省形态都要固定下来。
 */
class AgentModelSettingsTest {

    @Test
    fun `parses model references and thinking fields`() {
        val json = buildJsonObject {
            putJsonObject("decision_model") {
                put("provider_id", "zhipu")
                put("model", "glm-4.6")
            }
            putJsonObject("subagent_model") {
                put("provider_id", "openai")
                put("model", "gpt-4o-mini")
            }
            putJsonArray("fallback_models") {
                add(
                    buildJsonObject {
                        put("provider_id", "openai")
                        put("model", "gpt-4o")
                    },
                )
                add(
                    buildJsonObject {
                        put("provider_id", "zhipu")
                        put("model", "glm-4.6-air")
                    },
                )
            }
            putJsonObject("fallback_policy") {
                put("enabled", true)
                put("target_scope", "configured")
            }
            put("thinking_level", "high")
            put("thinking_budget", 4096)
        }

        val settings = parseAgentModelSettings(json)

        assertEquals(ActiveModelDto("zhipu", "glm-4.6"), settings.decision)
        assertEquals(ActiveModelDto("openai", "gpt-4o-mini"), settings.subagent)
        assertEquals(2, settings.fallbacks.size)
        assertEquals("glm-4.6-air", settings.fallbacks[1].model)
        assertTrue(settings.fallbackEnabled)
        assertEquals("high", settings.thinkingLevel)
        assertEquals(4096L, settings.thinkingBudget)
    }

    @Test
    fun `inherit level counts as not set`() {
        val settings = parseAgentModelSettings(
            buildJsonObject {
                put("thinking_level", "inherit")
                put("thinking_budget", JsonNull)
            },
        )

        assertNull(settings.thinkingLevel)
        assertNull(settings.thinkingBudget)
    }

    @Test
    fun `empty payload yields defaults`() {
        val settings = parseAgentModelSettings(JsonObject(emptyMap()))

        assertNull(settings.decision)
        assertNull(settings.subagent)
        assertTrue(settings.fallbacks.isEmpty())
        assertTrue(!settings.fallbackEnabled)
        assertNull(settings.thinkingLevel)
        assertNull(settings.thinkingBudget)
    }

    @Test
    fun `fallback entries without a provider are skipped`() {
        val json = buildJsonObject {
            putJsonArray("fallback_models") {
                add(buildJsonObject { put("model", "only-model") })
                add(
                    buildJsonObject {
                        put("provider_id", "openai")
                        put("model", "gpt-4o")
                    },
                )
            }
        }

        val settings = parseAgentModelSettings(json)

        assertEquals(1, settings.fallbacks.size)
        assertEquals("openai", settings.fallbacks.first().providerId)
    }

    @Test
    fun `body always carries all six keys`() {
        val body = agentModelSettingsBody(
            decision = ActiveModelDto("zhipu", "glm-4.6"),
            subagent = null,
            fallbacks = listOf(ActiveModelDto("openai", "gpt-4o")),
            fallbackEnabled = true,
            thinkingLevel = null,
            thinkingBudget = null,
        )

        assertEquals(
            setOf(
                "fallback_models",
                "fallback_policy",
                "subagent_model",
                "decision_model",
                "thinking_level",
                "thinking_budget",
            ),
            body.keys,
        )
        assertEquals("zhipu", body["decision_model"]!!.jsonObject["provider_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["subagent_model"])
        assertEquals(JsonNull, body["thinking_level"])
        assertEquals(JsonNull, body["thinking_budget"])
        assertEquals(1, body["fallback_models"]!!.jsonArray.size)
        val policy = body["fallback_policy"]!!.jsonObject
        assertTrue(policy["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("configured", policy["target_scope"]!!.jsonPrimitive.content)
    }

    @Test
    fun `body writes thinking values when present`() {
        val body = agentModelSettingsBody(
            decision = null,
            subagent = ActiveModelDto("openai", "gpt-4o-mini"),
            fallbacks = emptyList(),
            fallbackEnabled = false,
            thinkingLevel = "budget",
            thinkingBudget = 2048,
        )

        assertEquals("budget", body["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("2048", body["thinking_budget"]!!.jsonPrimitive.content)
        assertTrue(body["fallback_models"]!!.jsonArray.isEmpty())
        assertEquals(JsonNull, body["decision_model"])
    }

    @Test
    fun `budget stops are distinct and sorted`() {
        val stops = thinkingBudgetStops(
            ThinkingControlDto(
                kind = "budget",
                budgetMin = 1024,
                budgetMax = 8192,
                budgetDefault = 4096,
            ),
        )

        assertEquals(listOf(1024L, 4096L, 8192L), stops)
        assertTrue(thinkingBudgetStops(null).isEmpty())
        assertTrue(thinkingBudgetStops(ThinkingControlDto(kind = "unknown")).isEmpty())
    }

    @Test
    fun `slot key separates provider and model`() {
        assertEquals("openai:gpt-4o", slotKey("openai", "gpt-4o"))
        assertEquals(":", slotKey(null, null))
    }
}
