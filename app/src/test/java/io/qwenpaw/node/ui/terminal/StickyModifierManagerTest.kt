package io.qwenpaw.node.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyModifierManagerTest {

    @Test
    fun `mask follows toggle state and clears`() {
        val manager = StickyModifierManager()
        assertEquals(0, manager.mask)

        manager.toggleCtrl()
        assertEquals(StickyModifierManager.CTRL, manager.mask)
        assertTrue(manager.isCtrlActive())

        manager.toggleAlt()
        manager.toggleShift()
        assertEquals(
            StickyModifierManager.CTRL or
                StickyModifierManager.ALT or
                StickyModifierManager.SHIFT,
            manager.mask,
        )

        manager.clearTransients()
        assertEquals(0, manager.mask)
        assertFalse(manager.isCtrlActive())
        assertFalse(manager.isAltActive())
        assertFalse(manager.isShiftActive())
    }
}
