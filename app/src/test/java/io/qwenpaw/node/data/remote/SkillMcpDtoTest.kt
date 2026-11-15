package io.qwenpaw.node.data.remote

import io.qwenpaw.node.data.model.McpClientDto
import io.qwenpaw.node.data.model.PluginCatalogDto
import io.qwenpaw.node.data.model.SkillDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能、MCP 与目录三组 DTO 的反序列化单测。
 *
 * 这里钉的是三处「声明成非空集合就会让整份响应解析失败」的字段：
 * 服务端对 `tools` 给 `null`（表示全部启用）、对 `description_i18n` 可能给 `null`。
 * 这些用例的价值在于：真出现时，表现是「列表整页空白、响应看起来正常」。
 */
class SkillMcpDtoTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `mcp client with null tool whitelist`() {
        val raw = """
            {"key":"anysearch","name":"anysearch_mcp","enabled":false,
             "transport":"streamable_http","url":"https://api.anysearch.com/mcp",
             "tools":null,"oauth_status":null,
             "access_summary":{"default_effect":"deny","overrides_count":1}}
        """.trimIndent()
        val dto = json.decodeFromString(McpClientDto.serializer(), raw)
        assertEquals("anysearch", dto.key)
        assertNull(dto.tools)
        assertNull(dto.oauthStatus)
        assertEquals("deny", dto.accessSummary.defaultEffect)
        assertEquals(1, dto.accessSummary.overridesCount)
    }

    @Test
    fun `skill list element without requirements`() {
        // 列表元素没有 requirements；把它当成必填会让整页解析失败。
        val raw = """
            {"name":"QA_source_index","description":"desc","version_text":"1.3",
             "source":"builtin","emoji":"X","enabled":true,"channels":["all"],
             "preload":false,"tags":[],"last_updated":"2026-11-01T01:08:08.599528Z"}
        """.trimIndent()
        val dto = json.decodeFromString(SkillDto.serializer(), raw)
        assertEquals("QA_source_index", dto.name)
        assertTrue(dto.enabled)
        assertEquals(listOf("all"), dto.channels)
        assertEquals("2026-11-01T01:08:08.599528Z", dto.lastUpdated)
    }

    @Test
    fun `plugin catalog tolerates null translations and error`() {
        val raw = """
            {"updated_at":null,"error":"Failed to fetch plugin catalog index",
             "plugins":[{"id":"a-1","plugin_id":"a","name":"A","version":"1","author":"",
              "kind":"apps","size":"1 KB","install_url":"https://x/a.zip",
              "installed":false,"installed_version":null,"upgrade_available":false,
              "description_i18n":null}]}
        """.trimIndent()
        val dto = json.decodeFromString(PluginCatalogDto.serializer(), raw)
        assertEquals(1, dto.plugins.size)
        assertNull(dto.plugins.first().descriptionI18n)
        assertNull(dto.plugins.first().installedVersion)
        // CDN 失败时服务端仍回 200，界面要先看 error。
        assertEquals("Failed to fetch plugin catalog index", dto.error)
    }
}