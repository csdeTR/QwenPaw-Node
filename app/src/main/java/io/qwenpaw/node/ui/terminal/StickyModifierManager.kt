package io.qwenpaw.node.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.connectbot.terminal.ModifierManager

/**
 * 键栏的粘滞修饰键。Termlib 的 KeyboardHandler 会在每次按键后调用 [clearTransients]，
 * 因此软键盘输入也能带上 CTRL/ALT/SHIFT，与硬件键盘一致。
 */
class StickyModifierManager : ModifierManager {

    var ctrl by mutableStateOf(false)
        private set
    var alt by mutableStateOf(false)
        private set
    var shift by mutableStateOf(false)
        private set

    val mask: Int
        get() = (if (shift) SHIFT else 0) or
            (if (alt) ALT else 0) or
            (if (ctrl) CTRL else 0)

    fun toggleCtrl() { ctrl = !ctrl }
    fun toggleAlt() { alt = !alt }
    fun toggleShift() { shift = !shift }

    fun clearAll() {
        ctrl = false
        alt = false
        shift = false
    }

    override fun isCtrlActive(): Boolean = ctrl
    override fun isAltActive(): Boolean = alt
    override fun isShiftActive(): Boolean = shift

    override fun clearTransients() {
        clearAll()
    }

    companion object {
        const val SHIFT = 1
        const val ALT = 2
        const val CTRL = 4
    }
}
