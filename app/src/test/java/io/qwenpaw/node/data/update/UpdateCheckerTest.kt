package io.qwenpaw.node.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 版本比较、发布过滤与每日判定。
 *
 * 版本号是用户能直接看到的文案，「0.7.10 该不该说比 0.7.9 新」这类判断错一次就会被当成
 * 应用算错了版本；每日判定则决定启动时发不发请求。两者都写成纯函数后用这里钉住。
 *
 * 时区显式写成 `Asia/Shanghai` 而不是读系统默认值：runner 是 UTC，本机是东八区，
 * 依赖默认值时同一份断言在两边结论不同。
 */
class UpdateCheckerTest {

    // ---------------- 版本比较 ----------------

    @Test
    fun `numeric segments compare by value not by string`() {
        assertEquals(1, UpdateChecker.compareVersions("0.7.10", "0.7.9"))
        assertEquals(-1, UpdateChecker.compareVersions("0.7.9", "0.7.10"))
        assertEquals(0, UpdateChecker.compareVersions("0.7.3", "0.7.3"))
    }

    @Test
    fun `v prefix and variant suffix are ignored`() {
        assertEquals(0, UpdateChecker.compareVersions("0.7.3", "v0.7.3"))
        assertEquals(0, UpdateChecker.compareVersions("0.7.3-no-sherpa", "v0.7.3"))
        assertEquals(0, UpdateChecker.compareVersions("0.7.3", "v0.7.3-i"))
        assertEquals(-1, UpdateChecker.compareVersions("v0.7.2", "0.7.3-no-sherpa"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertEquals(0, UpdateChecker.compareVersions("0.7", "0.7.0"))
        assertEquals(-1, UpdateChecker.compareVersions("0.7", "0.7.1"))
        assertEquals(1, UpdateChecker.compareVersions("1", "0.9.9"))
    }

    @Test
    fun `build metadata does not affect the comparison`() {
        assertEquals(0, UpdateChecker.compareVersions("0.7.3+24", "0.7.3+25"))
    }

    @Test
    fun `display version drops only the v prefix`() {
        assertEquals("0.7.3", UpdateChecker.displayVersion("v0.7.3"))
        assertEquals("0.7.3-no-sherpa", UpdateChecker.displayVersion("0.7.3-no-sherpa"))
    }

    // ---------------- GitHub 发布过滤 ----------------

    private fun releaseJson(
        tag: String = "v0.8.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = """
        {"tag_name":"$tag","html_url":"https://example.invalid/r/0.8.0",
         "published_at":"2026-11-20T00:00:00Z","body":"notes",
         "draft":$draft,"prerelease":$prerelease}
    """.trimIndent()

    @Test
    fun `normal release is usable`() {
        val release = UpdateChecker.usableRelease(releaseJson())
        assertEquals("v0.8.0", release?.tagName)
        assertEquals("notes", release?.body)
    }

    @Test
    fun `draft and prerelease are skipped`() {
        assertNull(UpdateChecker.usableRelease(releaseJson(draft = true)))
        assertNull(UpdateChecker.usableRelease(releaseJson(prerelease = true)))
    }

    @Test
    fun `release without tag or invalid json is skipped`() {
        assertNull(UpdateChecker.usableRelease(releaseJson(tag = "")))
        assertNull(UpdateChecker.usableRelease("not json"))
    }

    // ---------------- 每日判定 ----------------

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `today stamp uses the given zone`() {
        assertEquals(LocalDate.now(shanghai).toString(), todayStamp(shanghai))
    }

    @Test
    fun `daily check runs when never checked or date differs`() {
        val today = todayStamp(shanghai)
        assertTrue(shouldCheckDaily(null, today))
        assertTrue(shouldCheckDaily("2026-11-18", today))
        assertFalse(shouldCheckDaily(today, today))
    }

    @Test
    fun `blank saved date is treated as never checked`() {
        assertTrue(shouldCheckDaily("  ", "2026-11-20"))
    }

    // ---------------- 插件检查冷却 ----------------

    @Test
    fun `plugin check is due immediately when never checked`() {
        assertTrue(isPluginUpdateCheckDue(0L, 1_000L))
    }

    @Test
    fun `plugin check waits six hours`() {
        val last = 1_000_000L
        assertFalse(isPluginUpdateCheckDue(last, last + 6 * 60 * 60 * 1000L - 1))
        assertTrue(isPluginUpdateCheckDue(last, last + 6 * 60 * 60 * 1000L))
    }

    @Test
    fun `clock going backwards still allows a check`() {
        assertTrue(isPluginUpdateCheckDue(2_000L, 1_000L))
    }
}
