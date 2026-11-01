package io.qwenpaw.node.ui.chat

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 多行环境的内容宽度约束。
 *
 * 这条断言守的是一个真实崩溃：块级公式处在横向滚动容器里，父约束宽度是
 * `Constraints.Infinity`，早先实现直接减去两侧定界符占位再构造 `Constraints`，
 * 抛出 `IllegalArgumentException: Can't represent a width of 2147483615 and height of 0`，
 * 应用当场退出。数值就取当时崩溃里的 2147483615（`Infinity - 32`）。
 */
class LatexLayoutTest {

    /** 无上界时必须原样返回，不能再减。 */
    @Test
    fun `unbounded width is passed through`() {
        assertEquals(Constraints.Infinity, envContentMaxWidth(Constraints.Infinity, 32))
        assertEquals(Constraints.Infinity, envContentMaxWidth(Constraints.Infinity, 1))
    }

    /** 有上界时正常扣除两侧定界符占位。 */
    @Test
    fun `bounded width subtracts the delimiters`() {
        assertEquals(1000 - 64, envContentMaxWidth(1000, 64))
    }

    /** 内容区不足两侧占位时收成 0，不能出现负值。 */
    @Test
    fun `narrow width never goes negative`() {
        assertEquals(0, envContentMaxWidth(10, 64))
    }

    /** 实证 Compose 不接受 `Infinity - n`，这正是上面那条规则存在的理由。 */
    @Test
    fun `infinity minus reserved is rejected by compose`() {
        assertThrows(IllegalArgumentException::class.java) {
            Constraints(maxWidth = Constraints.Infinity - 32)
        }
    }
}