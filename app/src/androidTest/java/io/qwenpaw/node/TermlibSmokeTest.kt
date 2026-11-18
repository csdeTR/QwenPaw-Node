package io.qwenpaw.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermlibSmokeTest {

    @Test
    fun emulatorLoadsWritesAndResizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var emulator: TerminalEmulator
        instrumentation.runOnMainSync {
            emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
            emulator.writeInput("hello".toByteArray())
            emulator.resize(30, 100)
        }
        assertEquals(30, emulator.dimensions.rows)
        assertEquals(100, emulator.dimensions.columns)
        instrumentation.runOnMainSync { emulator.close() }
    }
}
