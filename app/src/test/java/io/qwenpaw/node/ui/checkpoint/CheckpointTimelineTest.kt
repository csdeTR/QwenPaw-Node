package io.qwenpaw.node.ui.checkpoint

import io.qwenpaw.node.data.model.CheckpointNodeDto
import io.qwenpaw.node.data.model.CheckpointSessionDto
import io.qwenpaw.node.data.model.RestoreResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线分组与回滚草稿。
 *
 * 组内倒序、组上限与标题回落都在这里钉住：图谱节点最多 1000 条，
 * 逐条平铺会让页面失去可读性。
 */
class CheckpointTimelineTest {

    private fun node(
        commit: String,
        session: String,
        millis: Long,
        title: String = "",
        sessionId: String = "",
        head: Boolean = false,
        kind: String = "auto",
    ) = CheckpointNodeDto(
        ref = "refs/checkpoints/$commit",
        kind = kind,
        sessionKey = session,
        commit = commit,
        sha = commit.take(12),
        timestampMs = millis,
        sessionTitle = title,
        sessionId = sessionId,
        userId = "u1",
        channel = "web",
        isHead = head,
    )

    @Test
    fun `groups by session newest first and trims each group`() {
        val nodes = (1..7).map { node("c$it", "k1", it * 1000L) } +
            node("other", "k2", 50_000L)
        val groups = groupCheckpointNodes(nodes, emptyList())

        assertEquals(2, groups.size)
        // k2 的最新节点更晚，应排在最前。
        assertEquals("k2", groups[0].sessionKey)
        assertEquals(CHECKPOINT_GROUP_LIMIT, groups[1].nodes.size)
        assertEquals("c7", groups[1].nodes.first().commit)
        assertEquals("c3", groups[1].nodes.last().commit)
    }

    @Test
    fun `title falls back to session then node then id prefix`() {
        val nodes = listOf(
            node("c1", "k1", 1000, title = "节点标题"),
            node("c2", "k2", 2000, sessionId = "abcdef123456", title = ""),
        )
        val sessions = listOf(
            CheckpointSessionDto(sessionKey = "k1", sessionId = "s1", title = "会话标题"),
            CheckpointSessionDto(sessionKey = "k2", sessionId = "abcdef123456", title = ""),
        )
        val groups = groupCheckpointNodes(nodes, sessions)

        assertEquals("会话标题", groups.first { it.sessionKey == "k1" }.title)
        assertEquals("abcdef12", groups.first { it.sessionKey == "k2" }.title)
    }

    @Test
    fun `archived flag comes from session`() {
        val groups = groupCheckpointNodes(
            listOf(node("c1", "k1", 1000)),
            listOf(CheckpointSessionDto(sessionKey = "k1", title = "旧会话", archived = true)),
        )
        assertTrue(groups.single().archived)
    }

    @Test
    fun `kind labels are human readable`() {
        assertEquals("自动", checkpointKindLabel("auto"))
        assertEquals("手动", checkpointKindLabel("snap"))
        assertEquals("恢复前", checkpointKindLabel("pre-restore"))
        assertEquals("新类型", checkpointKindLabel("新类型"))
    }

    @Test
    fun `restore requires at least one file when files selected`() {
        val draft = CheckpointRestoreDraft(node("abcdef1234567890", "k1", 1000, sessionId = "s1"))
        assertFalse(draft.canApply)

        val memoryOnly = draft.copy(includeMemory = true)
        assertTrue(memoryOnly.canApply)

        val filesWithoutSelection = draft.copy(includeFiles = true)
        assertFalse(filesWithoutSelection.canApply)

        val filesSelected = filesWithoutSelection.copy(selectedFiles = setOf("a.md"))
        assertTrue(filesSelected.canApply)
    }

    @Test
    fun `request sends commit files and session context`() {
        val draft = CheckpointRestoreDraft(node("abcdef1234567890", "k1", 1000, sessionId = "s1"))
            .copy(includeFiles = true, selectedFiles = setOf("b.md", "a.md"))
        val request = draft.toRequest()

        assertEquals("abcdef1234567890", request.commit)
        assertEquals("s1", request.sessionId)
        assertEquals("u1", request.userId)
        assertEquals("web", request.channel)
        assertEquals(setOf("b.md", "a.md"), request.files.toSet())
        // 预览不带具体路径，先拿到完整候选列表。
        assertTrue(draft.toPreviewRequest().files.isEmpty())
    }

    @Test
    fun `impacts mention deletions and safety snapshot`() {
        val draft = CheckpointRestoreDraft(node("abcdef1234567890", "k1", 1000, sessionId = "s1"))
            .copy(includeFiles = true, selectedFiles = setOf("a.md"))
        val preview = RestoreResultDto(
            deletedPaths = listOf("new.md"),
            preRestoreRef = "refs/checkpoints/pre-restore/x",
        )
        val impacts = draft.impacts(preview)
        assertTrue(impacts.any { it.contains("恢复 1 个") })
        assertTrue(impacts.any { it.contains("将删除") && it.contains("1 个") })
        assertTrue(impacts.any { it.contains("恢复前快照") })
    }
}
