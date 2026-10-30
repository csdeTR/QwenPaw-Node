package io.qwenpaw.node.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引导页「上一步」的目标步骤。
 *
 * 第 2 步在「服务端未开启登录验证」时不可停留：`LaunchedEffect` 会立刻把页面
 * 推回第 3 步，而该步又不渲染输入框。若此时从第 3 步返回落在第 2 步，
 * 用户会看到画面闪一下又被送回原处，且「下一步」永远不可点。
 */
class WizardStepTest {

    /** 未开登录：第 3 步直接回第 1 步，跳过不可停留的第 2 步。 */
    @Test
    fun `no login jumps from step three to step one`() {
        assertEquals(1, wizardBackStep(3, false))
    }

    /** 需要登录：第 3 步按正常递减回到第 2 步。 */
    @Test
    fun `login required goes back to step two`() {
        assertEquals(2, wizardBackStep(3, true))
    }

    /** 第 2 步回第 1 步，与是否登录无关。 */
    @Test
    fun `step two goes back to step one`() {
        assertEquals(1, wizardBackStep(2, true))
        assertEquals(1, wizardBackStep(2, false))
    }

    /** 尚未判定（null）时按普通递减处理：此时第 2 步的可停留性还没有结论。 */
    @Test
    fun `unknown login state behaves like a normal decrement`() {
        assertEquals(2, wizardBackStep(3, null))
        assertEquals(1, wizardBackStep(2, null))
    }

    /** 第 1 步不能再退，避免出现第 0 步这种非法状态。 */
    @Test
    fun `step one never goes below one`() {
        assertEquals(1, wizardBackStep(1, true))
        assertEquals(1, wizardBackStep(1, false))
        assertEquals(1, wizardBackStep(1, null))
    }
}