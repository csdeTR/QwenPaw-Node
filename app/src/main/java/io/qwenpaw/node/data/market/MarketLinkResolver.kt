package io.qwenpaw.node.data.market

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.MarketResultDto
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 技能市场里 QwenPaw 来源的详情页地址解析。
 *
 * 服务端 `market/providers/qwenpaw.py` 用稳定 id 拼 `source_url`
 * （`/skills/@user/name`），注释写明是为了让安装解析器能从 URL 里拆出 owner/name。
 * 但平台前端的详情路由只接受 **UUID**：页面内取详情走 `GET /api/v1/skills/{uuid}`，
 * 传 owner/name 一律 404，页面显示「未找到技能详情」。实测：
 *
 * | 地址 | 详情接口 |
 * | --- | --- |
 * | `/skills/@user/qwenpaw-docs-zh` | 404 |
 * | `/skills/qwenpaw-docs-zh` | 404 |
 * | `/skills/01a152e1-…-41aa77f1fd67` | 200 |
 *
 * 上游列表响应里本来带 `details_url`（UUID），但服务端映射成 `MarketResult` 时丢了，
 * 只留下 `slug`（`@owner/name`）与那条安装用 URL。客户端拿不到 UUID，只能自己再查一次：
 * 用 `slug` 的末段（技能 code）搜索，按 `id` 精确匹配取 `details_url`。
 *
 * **只在 QwenPaw 来源上做。** 其余三个来源的 `source_url` 是可浏览的真实页面，
 * 不值得为统一而让每次点开都多一次网络往返。
 */
class MarketLinkResolver(
    private val client: OkHttpClient = QwenPawApi.probeClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 解析结果。`fallback` 说明为什么没拿到直达地址，界面据此给提示。 */
    sealed class Outcome {
        data class Direct(val url: String) : Outcome()
        data class Fallback(val url: String, val reason: String) : Outcome()
    }

    /**
     * 换算一条市场结果的落地地址。
     *
     * QwenPaw 来源走 [resolveQwenPaw]；其余来源原样返回 [MarketResultDto.sourceUrl]。
     * 任何失败都不抛，最差退回平台技能列表页——点一行什么都不发生比跳错页更难理解。
     */
    suspend fun landingUrl(result: MarketResultDto): Outcome {
        if (result.source != QWENPAW_SOURCE) {
            val url = result.sourceUrl.trim()
            // 空地址不落到 QwenPaw 的列表页：那是另一个来源的条目，跳过去只会更困惑。
            return if (url.isEmpty()) {
                Outcome.Fallback("", Texts.get(R.string.marketlink_no_detail_url))
            } else {
                Outcome.Direct(url)
            }
        }
        // 服务端若已改成直出 UUID 详情页，就不必再查一次。将来服务端修好时，
        // 客户端无需再改一行：这条分支先认地址，只有地址不是 UUID 形式才解析。
        result.sourceUrl.trim().takeIf { UUID_DETAILURL.matches(it) }?.let {
            return Outcome.Direct(it)
        }
        return resolveQwenPaw(result.slug.ifBlank { skillCodeOf(result.sourceUrl) })
    }

    /**
     * 用技能 code 查一次 UUID。
     *
     * `search` 是模糊匹配，因此必须按 `id` 后缀精确比对：同一个关键词可能命中
     * 别人同名的技能，取第一条会把用户带到错误的技能页。
     */
    private suspend fun resolveQwenPaw(code: String): Outcome {
        val name = code.substringAfterLast('/').trim()
        if (name.isEmpty()) {
            return Outcome.Fallback(
                QWENPAW_SKILLS_LIST,
                Texts.get(R.string.marketlink_missing_skill_id),
            )
        }
        val url = SEARCH_ENDPOINT.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("search", name)
            ?.addQueryParameter("page_size", PAGE_SIZE.toString())
            ?.addQueryParameter("page_number", "1")
            ?.build()
            ?: return Outcome.Fallback(
                QWENPAW_SKILLS_LIST,
                Texts.get(R.string.marketlink_search_url_failed),
            )

        val body = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            }
        }
        return body
            .mapCatching { parseDirectUrl(it, code) }
            .fold(
                onSuccess = { direct ->
                    direct?.let { Outcome.Direct(it) }
                        ?: Outcome.Fallback(
                            QWENPAW_SKILLS_LIST,
                            Texts.get(R.string.marketlink_no_detail_from_platform),
                        )
                },
                onFailure = {
                    Outcome.Fallback(QWENPAW_SKILLS_LIST, Texts.get(R.string.marketlink_unreachable))
                },
            )
    }

    /**
     * 从搜索响应里取出这条技能的详情页地址。
     *
     * 响应形状是 `{data:{skills:[{id, details_url, …}]}}`。匹配用 `id` 与传入的
     * `code` 全等，而不是 `endsWith`：`id` 形如 `@owner/name`，只比末段会把
     * 别人的同名技能也算命中。
     */
    internal fun parseDirectUrl(body: String, code: String): String? {
        val skills = runCatching {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("skills")?.jsonArray
        }.getOrNull() ?: return null
        val wanted = code.trim()
        return skills.firstNotNullOfOrNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
            val id = runCatching { item["id"]?.jsonPrimitive?.content }.getOrNull()
            if (id != wanted) return@firstNotNullOfOrNull null
            runCatching { item["details_url"]?.jsonPrimitive?.content }
                .getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    /** 从 `/skills/@owner/name` 取出 `@owner/name`。 */
    internal fun skillCodeOf(sourceUrl: String): String {
        val parsed = sourceUrl.toHttpUrlOrNull() ?: return ""
        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        val index = segments.indexOf("skills")
        if (index < 0 || index + 2 >= segments.size) return ""
        return "${segments[index + 1]}/${segments[index + 2]}"
    }

    companion object {
        /** QwenPaw 来源的 key，与服务端 `market/providers/qwenpaw.py` 的 `key` 一致。 */
        const val QWENPAW_SOURCE = "qwenpaw"

        /** 平台的公开搜索接口，不需要鉴权。 */
        const val SEARCH_ENDPOINT = "https://platform.agentscope.io/openapi/v1/skills"

        /** 解析失败时的落地页：平台技能列表。 */
        const val QWENPAW_SKILLS_LIST = "https://platform.agentscope.io/skills"

        /** 已经是 UUID 详情页的地址，无需再解析。 */
        private val UUID_DETAILURL = Regex(
            """^https://platform\.agentscope\.io/skills/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""",
        )

        /**
         * 取多少条候选。
         *
         * 服务端 `search` 是模糊匹配，一次 20 条足够覆盖同名情况；取 50（上游上限）
         * 会把响应体放大到与收益不相称的程度。
         */
        private const val PAGE_SIZE = 20
    }
}