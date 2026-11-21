package io.qwenpaw.node.ui.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 频道版本门禁与「已有任务在运行」的判定。 */
class ChannelVersionGateTest {

    @Test
    fun `known versions below the minimum are unsupported`() {
        assertEquals(ChannelVersionGate.UNSUPPORTED, channelVersionGate("2.3.0"))
        assertEquals(ChannelVersionGate.UNSUPPORTED, channelVersionGate("2.3.0b2"))
        assertEquals(ChannelVersionGate.UNSUPPORTED, channelVersionGate("2.2.2"))
        assertEquals(ChannelVersionGate.UNSUPPORTED, channelVersionGate("v1.9"))
        assertEquals(ChannelVersionGate.UNSUPPORTED, channelVersionGate(" 2.2.9 "))
    }

    @Test
    fun `minimum and newer versions are supported`() {
        assertEquals(ChannelVersionGate.SUPPORTED, channelVersionGate("2.3.0.post1"))
        assertEquals(ChannelVersionGate.SUPPORTED, channelVersionGate("2.3.0.post2"))
        assertEquals(ChannelVersionGate.SUPPORTED, channelVersionGate("2.3.1"))
        assertEquals(ChannelVersionGate.SUPPORTED, channelVersionGate("v2.3.0.post1"))
        assertEquals(ChannelVersionGate.SUPPORTED, channelVersionGate("3.0"))
    }

    @Test
    fun `missing or unparseable version is unknown instead of unsupported`() {
        assertEquals(ChannelVersionGate.UNKNOWN, channelVersionGate(null))
        assertEquals(ChannelVersionGate.UNKNOWN, channelVersionGate(""))
        assertEquals(ChannelVersionGate.UNKNOWN, channelVersionGate("  "))
        assertEquals(ChannelVersionGate.UNKNOWN, channelVersionGate("dev"))
        assertEquals(ChannelVersionGate.UNKNOWN, channelVersionGate("2.x.y"))
    }

    @Test
    fun `task running messages are recognized`() {
        assertTrue(
            isPluginTaskRunning(
                "A plugin installation task is already running for this server.",
            ),
        )
        assertTrue(isPluginTaskRunning("plugin task already in progress"))
        assertTrue(isPluginTaskRunning("已有任务在运行，请稍后重试"))
    }

    @Test
    fun `other plugin errors are not treated as task running`() {
        assertFalse(isPluginTaskRunning(null))
        assertFalse(isPluginTaskRunning(""))
        assertFalse(isPluginTaskRunning("Plugin installation target already exists"))
        assertFalse(isPluginTaskRunning("网络中断，请稍后重试"))
    }
}
