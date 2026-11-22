package io.qwenpaw.node.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** 更新入口只能指向本仓库的 Release。 */
class UpdateCheckerUrlTest {

    @Test
    fun `keeps own release url`() {
        val url = "https://github.com/csdeTR/QwenPaw-Node/releases/tag/v0.7.5"
        assertEquals(url, UpdateChecker.safeReleaseUrl(url))
    }

    @Test
    fun `rejects foreign host`() {
        assertEquals(
            UpdateChecker.REPO_RELEASES_URL,
            UpdateChecker.safeReleaseUrl("https://example.com/csdeTR/QwenPaw-Node/releases"),
        )
    }

    @Test
    fun `rejects lookalike path`() {
        assertEquals(
            UpdateChecker.REPO_RELEASES_URL,
            UpdateChecker.safeReleaseUrl("https://github.com/other/repo/releases/tag/v1"),
        )
    }

    @Test
    fun `rejects non https`() {
        assertEquals(
            UpdateChecker.REPO_RELEASES_URL,
            UpdateChecker.safeReleaseUrl("http://github.com/csdeTR/QwenPaw-Node/releases"),
        )
    }
}
