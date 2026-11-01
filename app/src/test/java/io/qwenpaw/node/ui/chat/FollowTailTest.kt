package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成时的「跟随末尾」状态机。
 *
 * 抽成纯对象就是为了让这几条转移能在 JVM 里断言：
 * 它们对应四个用户可见的行为，靠模拟器拖列表验证成本太高。
 */
class FollowTailTest {

    @Test
    fun `starts following by default`() {
        assertTrue(FollowTail().value)
        assertFalse(FollowTail(initial = false).value)
    }

    /**
     * 手指一按下就停止跟随。
     *
     * 这是本轮修的核心问题：此前只在松手后采样，按住上滑的几秒里
     * 流式内容仍会把列表拉回底部，表现为「拖不动」。
     */
    @Test
    fun `drag start stops following immediately`() {
        val state = FollowTail()
        state.onDragStart()
        assertFalse(state.value)
    }

    /** 松手且惯性停下后按落点决定：滑回底部恢复跟随。 */
    @Test
    fun `settling at bottom resumes following`() {
        val state = FollowTail()
        state.onDragStart()
        state.onDragSettled(atBottom = true)
        assertTrue(state.value)
    }

    /** 停在中间保持停止，否则新内容会把正在读的位置顶走。 */
    @Test
    fun `settling away from bottom keeps following off`() {
        val state = FollowTail()
        state.onDragStart()
        state.onDragSettled(atBottom = false)
        assertFalse(state.value)
    }

    /** 发送消息强制恢复：点发送本身就是「我要看这条」。 */
    @Test
    fun `send forces following`() {
        val state = FollowTail(initial = false)
        state.onSend()
        assertTrue(state.value)
    }

    /** 「跳到最新」强制恢复。 */
    @Test
    fun `jump to latest forces following`() {
        val state = FollowTail(initial = false)
        state.onJumpToLatest()
        assertTrue(state.value)
    }

    /** 拖动结束后若落在底部，应当恢复——不依赖上一次的状态。 */
    @Test
    fun `settle result is absolute not relative`() {
        val cold = FollowTail(initial = false)
        cold.onDragSettled(atBottom = true)
        assertTrue(cold.value)

        val warm = FollowTail(initial = true)
        warm.onDragSettled(atBottom = false)
        assertFalse(warm.value)
    }
}