package io.qwenpaw.node.data.market

import io.qwenpaw.node.data.model.MarketResultDto
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QwenPaw 来源落地地址解析的单测。
 *
 * 防的是三类缺陷：把安装用的 `/skills/@owner/name` 当成可浏览地址、
 * 模糊搜索命中了别人的同名技能，以及服务端将来改好之后客户端反而退回列表页。
 */
class MarketLinkResolverTest {

    private val resolver = MarketLinkResolver(OkHttpClient())

    private fun body(vararg ids: Pair<String, String>): String {
        val items = ids.joinToString(",") { (id, url) ->
            """{"id":"$id","details_url":"$url"}"""
        }
        return """{"success":true,"message":"","data":{"total":${ids.size},"skills":[$items]}}"""
    }

    @Test
    fun `exact id match wins over a decoy`() {
        // 同名但属于别人的技能排在前面，只认 id 全等的那个。
        val json = body(
            "@someone-else/qwenpaw-docs-zh" to
                "https://platform.agentscope.io/skills/00000000-0000-0000-0000-000000000000",
            "@user/qwenpaw-docs-zh" to
                "https://platform.agentscope.io/skills/01a152e1-49ae-7f82-9cba-41aa77f1fd67",
        )
        assertEquals(
            "https://platform.agentscope.io/skills/01a152e1-49ae-7f82-9cba-41aa77f1fd67",
            resolver.parseDirectUrl(json, "@user/qwenpaw-docs-zh"),
        )
    }

    @Test
    fun `missing entry yields null`() {
        val json = body("@other/name" to "https://platform.agentscope.io/skills/uuid")
        assertNull(resolver.parseDirectUrl(json, "@user/qwenpaw-docs-zh"))
    }

    @Test
    fun `malformed body yields null instead of throwing`() {
        assertNull(resolver.parseDirectUrl("not json", "@user/name"))
        assertNull(resolver.parseDirectUrl("""{"data":{}}""", "@user/name"))
    }

    @Test
    fun `skill code is read from installation url`() {
        assertEquals(
            "@user/qwenpaw-docs-zh",
            resolver.skillCodeOf("https://platform.agentscope.io/skills/@user/qwenpaw-docs-zh"),
        )
    }

    @Test
    fun `skill code of unrelated url is empty`() {
        assertEquals("", resolver.skillCodeOf("https://platform.agentscope.io/plugins/x"))
        assertEquals("", resolver.skillCodeOf(""))
    }

    @Test
    fun `already uuid source url is opened directly without a lookup`() = runTest {
        // 服务端将来改好之后会给 UUID 详情页，届时不该再多查一次。
        // 这条路径不发网络请求，因此用任意 client 都能跑到。
        val url = "https://platform.agentscope.io/skills/01a152e1-49ae-7f82-9cba-41aa77f1fd67"
        val outcome = resolver.landingUrl(
            MarketResultDto(source = "qwenpaw", slug = "@user/name", sourceUrl = url),
        )
        assertTrue(outcome is MarketLinkResolver.Outcome.Direct)
        assertEquals(url, (outcome as MarketLinkResolver.Outcome.Direct).url)
    }

    @Test
    fun `other sources are opened as is`() = runTest {
        val outcome = resolver.landingUrl(
            MarketResultDto(
                source = "clawhub",
                slug = "x",
                sourceUrl = "https://clawhub.ai/skills/x",
            ),
        )
        assertEquals(
            "https://clawhub.ai/skills/x",
            (outcome as MarketLinkResolver.Outcome.Direct).url,
        )
    }
}