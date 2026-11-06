package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史前缀与本轮流式内容的拼接。
 *
 * 这组断言对应两个实际观感问题：
 * 1. 发消息后聊天页「空荡荡」——历史被 `accumulator.snapshot()` 整屏冲掉；
 * 2. 回复完毕才看得到旧消息——直到 `reconcileWithServer` 用服务端历史覆盖回来。
 * 两者是同一个根因，判据都是「发送之后历史还在不在」。
 */
class MergeHistoryTest {

    private fun user(id: String, text: String) = ChatMessage(
        id = id,
        role = Role.USER,
        kind = ChatMessage.Kind.TEXT,
        text = text,
    )

    private fun assistant(id: String, text: String, streaming: Boolean = false) = ChatMessage(
        id = id,
        role = Role.ASSISTANT,
        kind = ChatMessage.Kind.TEXT,
        text = text,
        streaming = streaming,
    )

    /** 首轮：没有历史时结果就是本轮内容，不能凭空多出条目。 */
    @Test
    fun `empty history returns live only`() {
        val live = listOf(user("local-user-1", "你好"))
        assertEquals(live, mergeHistory(emptyList(), live))
    }

    /** 本轮还没产生任何内容时（例如刚打开会话），结果是历史本身。 */
    @Test
    fun `empty live returns history only`() {
        val history = listOf(user("u1", "上一条"), assistant("a1", "上一条回复"))
        assertEquals(history, mergeHistory(history, emptyList()))
    }

    /** 关键用例：发送后历史必须仍在，且排在本轮内容之前。 */
    @Test
    fun `history stays in front of live content`() {
        val history = listOf(user("u1", "上一条"), assistant("a1", "上一条回复"))
        val live = listOf(user("local-user-1", "这一条"))
        val merged = mergeHistory(history, live)

        assertEquals(3, merged.size)
        assertEquals("u1", merged[0].id)
        assertEquals("a1", merged[1].id)
        assertEquals("local-user-1", merged[2].id)
    }

    /** 流式内容陆续追加时，历史不能因此缩短。 */
    @Test
    fun `streaming growth never drops history`() {
        val history = listOf(user("u1", "上一条"))
        var live = listOf(user("local-user-1", "这一条"))
        val afterFirstToken = mergeHistory(history, live + assistant("a2", "你", streaming = true))
        live = live + assistant("a2", "你好", streaming = true)

        assertEquals(3, afterFirstToken.size)
        assertEquals(3, mergeHistory(history, live).size)
        assertEquals("u1", mergeHistory(history, live)[0].id)
    }

    /**
     * 运行中的对话：服务端返回空历史时，必须保留本地视图。
     * 这是「退出后重进，滑到顶看不到自己发的消息」的直接回归用例。
     */
    @Test
    fun `empty server history keeps local view`() {
        val local = listOf(user("local-user-1", "执行一下 sleep 120"), assistant("a1", "处理中", streaming = true))
        // 运行中只保留到这条用户消息为止，半截回复由重连流重发。
        assertEquals(
            listOf(user("local-user-1", "执行一下 sleep 120")),
            historyOnReopen(emptyList(), local, emptyList(), running = true),
        )
    }

    /** 进程被杀后重开：进程内视图为空，回退到持久化缓存。 */
    @Test
    fun `empty server and local view falls back to cache`() {
        val cached = listOf(user("local-user-1", "执行一下 sleep 120"))
        assertEquals(
            cached,
            historyOnReopen(emptyList(), emptyList(), cached, running = true),
        )
    }

    /** 服务端有历史时以服务端为准，缓存中的旧内容不能覆盖它。 */
    @Test
    fun `server history wins over local copies`() {
        val server = listOf(user("u1", "服务端版本"))
        val local = listOf(user("local-user-1", "本地版本"))
        assertEquals(server, historyOnReopen(server, local, local, running = false))
    }

    /**
     * 关键回归：服务端已经落库了前几轮，本轮的用户消息还没落库。
     * 只取服务端历史会让这条消息消失，必须把本地尾部接回去。
     */
    @Test
    fun `running chat keeps the newest user message after server history`() {
        val server = listOf(user("u1", "第一轮"), assistant("a1", "第一轮回复"))
        val local = server + listOf(
            user("local-user-2", "第二轮"),
            assistant("a2", "正在写", streaming = true),
        )

        val merged = historyOnReopen(server, local, emptyList(), running = true)

        assertEquals(3, merged.size)
        assertEquals("u1", merged[0].id)
        assertEquals("a1", merged[1].id)
        assertEquals("local-user-2", merged[2].id)
    }

    /** 本地与服务端对不上时宁可不追加，避免拼出重复气泡。 */
    @Test
    fun `mismatched prefix does not append local tail`() {
        val server = listOf(user("u1", "服务端版本"))
        val local = listOf(user("local-user-1", "本地版本"), user("local-user-2", "第二轮"))
        assertEquals(server, historyOnReopen(server, local, emptyList(), running = true))
    }

    /**
     * 切换对话时只有同一个对话才沿用当前视图。
     *
     * 新建对话时若提前把 `currentChatId` 改成新 id，上一个对话的内容会被当成
     * 新对话的本地视图，界面停在原对话上，缓存也被写脏。
     */
    @Test
    fun `switch to another chat drops the previous view`() {
        val previous = listOf(user("u1", "原来的对话"))
        assertEquals(emptyList<ChatMessage>(), localViewForSwitch("old", "new", previous))
        assertEquals(emptyList<ChatMessage>(), localViewForSwitch(null, "new", previous))
        assertEquals(previous, localViewForSwitch("same", "same", previous))
    }
}