package io.qwenpaw.node.ui.checkpoint

import io.qwenpaw.node.data.model.CheckpointSessionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 会话选择器借用对话行排布时的字段映射。
 *
 * 选择器与对话列表共用 `ChatRowContent`，因此这里的映射一旦错位，
 * 出现在检查点页的就是「空白标题」或「时间串位」。
 */
class CheckpointSnapshotEntryTest {

    @Test
    fun `maps session to chat row fields`() {
        val session = CheckpointSessionDto(
            sessionKey = "k1",
            sessionId = "s1",
            userId = "u1",
            channel = "web",
            title = "项目讨论",
            archived = true,
        )

        val chat = session.asChatSpec(latestMs = 1762800000000L)

        assertEquals("s1", chat.id)
        assertEquals("s1", chat.sessionId)
        assertEquals("项目讨论", chat.name)
        assertEquals("web", chat.channel)
        assertEquals(true, chat.archived)
        // 检查点没有「最后更新时间」，用最新检查点的时间代替。
        assertEquals(Instant.ofEpochMilli(1762800000000L).toString(), chat.updatedAt)
    }

    /** 没有标题时用 `session_key` 前 8 位兜底，避免整行只剩一个图标。 */
    @Test
    fun `blank title falls back to session key prefix`() {
        val session = CheckpointSessionDto(sessionKey = "abcdef1234567890", title = "")
        assertEquals("abcdef12", session.asChatSpec().name)
    }

    /** 没有检查点的会话不带时间，`timeLabel` 会返回空串而不是当前时间。 */
    @Test
    fun `no checkpoint means no time`() {
        val session = CheckpointSessionDto(sessionKey = "k1", sessionId = "s1", title = "t")
        assertNull(session.asChatSpec(null).updatedAt)
        assertNull(session.asChatSpec(0L).updatedAt)
    }

    /** 时区不影响换算：`updatedAt` 存的是 UTC 的 ISO 串，格式化交给 `TimeLabels`。 */
    @Test
    fun `updated at is the same instant in any zone`() {
        val millis = 1762800000000L
        val session = CheckpointSessionDto(sessionKey = "k1", sessionId = "s1")
        val chat = session.asChatSpec(millis)
        assertEquals(
            Instant.parse(chat.updatedAt).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
            Instant.parse(chat.updatedAt),
        )
    }
}
