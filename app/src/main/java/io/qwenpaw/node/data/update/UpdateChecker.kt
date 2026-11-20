package io.qwenpaw.node.data.update

import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 一次版本检查的结论。
 *
 * 只有 [Available] 带载荷；[UpToDate] 与 [Failed] 是给手动检查用的瞬时结果，
 * 都由界面以 Toast 呈现，不进入持久状态。
 */
sealed interface UpdateResult {

    /** 当前版本不低于最新发布。 */
    data object UpToDate : UpdateResult

    /** 有新版本可用。[version] 已去掉 `v` 前缀，可直接显示。 */
    data class Available(
        val version: String,
        val url: String,
        val notes: String,
    ) : UpdateResult

    /** 检查没做成。[message] 是给用户看的原因，已按当前语言取好。 */
    data class Failed(val message: String) : UpdateResult
}

/**
 * GitHub Release 的最小字段集。
 *
 * 只取展示与判定要用的字段：`draft` / `prerelease` 用来过滤，
 * `html_url` 是「去下载」的目标，`body` 作为更新说明的原文。
 */
@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

/**
 * APK 更新检查。
 *
 * 数据源是仓库的 GitHub Release：客户端不内置版本清单，发版只需要打 tag。
 * **静默检查与手动检查走同一条路径**，区别只在结果如何呈现——静默时失败不打扰人，
 * 手动时把「已是最新」或失败原因说出来。
 */
class UpdateChecker(
    private val http: OkHttpClient = defaultClient(),
) {

    /**
     * 查一次最新发布。
     *
     * [currentVersion] 是 `BuildConfig.VERSION_NAME`，形如 `0.7.3` 或 `0.7.3-no-sherpa`；
     * 变体后缀不参与比较，见 [compareVersions]。
     */
    fun check(currentVersion: String): UpdateResult {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 404) {
                    // 仓库还没有发布任何 release。静默检查时这不该报错，
                    // 但手动检查需要给一句能解释「为什么什么都没有」的话。
                    return UpdateResult.Failed(Texts.get(R.string.update_check_no_release))
                }
                if (!response.isSuccessful) {
                    return UpdateResult.Failed(
                        Texts.get(R.string.update_check_http_error, response.code),
                    )
                }
                val release = usableRelease(text)
                    ?: return UpdateResult.Failed(Texts.get(R.string.update_check_parse_error))
                if (compareVersions(currentVersion, release.tagName) >= 0) {
                    UpdateResult.UpToDate
                } else {
                    UpdateResult.Available(
                        version = displayVersion(release.tagName),
                        url = release.htmlUrl.ifBlank { REPO_RELEASES_URL },
                        notes = release.body.orEmpty(),
                    )
                }
            }
        } catch (e: IOException) {
            UpdateResult.Failed(Texts.get(R.string.update_check_network_error))
        } catch (e: kotlinx.serialization.SerializationException) {
            UpdateResult.Failed(Texts.get(R.string.update_check_parse_error))
        }
    }

    companion object {
        /** 仓库的 latest release 端点。 */
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/csdeTR/QwenPaw-Node/releases/latest"

        /** 没有正文、没有地址时的兜底入口。 */
        const val REPO_RELEASES_URL = "https://github.com/csdeTR/QwenPaw-Node/releases/latest"

        /** GitHub 要求带 User-Agent，缺省会被 403 拒绝。 */
        private const val USER_AGENT = "QwenPawNode-Android"

        /** 不写这个 Accept 也能拿到 JSON，但显式声明后返回形状才不会随默认值变化。 */
        private const val ACCEPT = "application/vnd.github+json"

        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        /**
         * 解析发布报文，过滤掉不该提示的条目。
         *
         * 即使 `releases/latest` 本身不会返回草稿与预发布，这里仍要显式判定：
         * 该端点的行为是 GitHub 的约定而不是客户端能保证的事实，误把预发布当成正式版
         * 会直接把用户推到未验完的构建上。无法解析时返回 null。
         */
        internal fun usableRelease(body: String): GitHubReleaseDto? {
            val release = runCatching {
                json.decodeFromString(GitHubReleaseDto.serializer(), body)
            }.getOrNull() ?: return null
            if (release.draft || release.prerelease) return null
            return release.takeIf { it.tagName.isNotBlank() }
        }

        /** 显示用的版本号：去掉 `v` 前缀，变体后缀保持原样。 */
        internal fun displayVersion(tagName: String): String =
            tagName.trim().removePrefix("v").removePrefix("V")

        /**
         * 比较两个版本号，返回 -1（前者旧）/ 0（相同）/ 1（前者新）。
         *
         * 去掉 `v` 前缀、构建元数据（`+` 之后）与变体后缀（`-no-sherpa`、`-i`），
         * 再按点分数字段比较；缺失的段按 0 处理，因此 `0.7` 与 `0.7.0` 相等。
         * 一段里混入非数字（如 `3-rc1` 已被 `-` 截断）时取前缀数字，取不到按 0。
         */
        fun compareVersions(current: String, latest: String): Int {
            val left = versionNumbers(current)
            val right = versionNumbers(latest)
            for (index in 0 until maxOf(left.size, right.size)) {
                val diff = (left.getOrNull(index) ?: 0) - (right.getOrNull(index) ?: 0)
                if (diff != 0) return if (diff > 0) 1 else -1
            }
            return 0
        }

        internal fun versionNumbers(raw: String): List<Int> {
            var text = raw.trim()
            if (text.startsWith("v", ignoreCase = true)) text = text.substring(1)
            text = text.substringBefore('+').substringBefore('-')
            return text.split('.').map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
        }
    }
}

/**
 * 每日一次判定的比较值：`yyyy-MM-dd`。
 *
 * 显式收 [java.time.ZoneId] 而不是读系统默认值，测试才能固定时区跑；
 * 调用方传 `ZoneId.systemDefault()`。
 */
fun todayStamp(zone: java.time.ZoneId): String = java.time.LocalDate.now(zone).toString()

/**
 * 今天是否还没检查过。
 *
 * 只比日期字符串：跨时区、改系统时间都不会让判定变复杂，最坏是多查一次。
 */
fun shouldCheckDaily(savedDate: String?, today: String): Boolean =
    savedDate?.trim() != today

/** 插件更新检查的最小间隔：6 小时。 */
const val PLUGIN_UPDATE_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

/**
 * 距上次插件更新检查是否已过冷却期。
 *
 * `lastCheckAt` 为 0 表示从未查过；时间倒退（用户改时钟）也按「该查」处理，
 * 否则一次回拨会让提示永久停摆。
 */
fun isPluginUpdateCheckDue(lastCheckAt: Long, now: Long): Boolean =
    lastCheckAt <= 0L || now < lastCheckAt || now - lastCheckAt >= PLUGIN_UPDATE_INTERVAL_MS
