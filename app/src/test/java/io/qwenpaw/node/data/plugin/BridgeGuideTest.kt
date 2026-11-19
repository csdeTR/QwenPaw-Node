package io.qwenpaw.node.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 安装引导的触发时机与 30% 概率。 */
class BridgeGuideTest {

    @Test
    fun `first connect always prompts when absent`() {
        val decision = bridgeGuideDecision(
            trigger = BridgeGuideTrigger.FIRST_CONNECT,
            status = BridgeStatus.Absent,
            cancelledCount = 0,
            muted = false,
            random = 0.99,
        )
        assertEquals(BridgeGuideDecision.Show(askMute = false), decision)
    }

    @Test
    fun `second cancel offers mute`() {
        val decision = bridgeGuideDecision(
            trigger = BridgeGuideTrigger.FIRST_CONNECT,
            status = BridgeStatus.Absent,
            cancelledCount = 1,
            muted = false,
            random = 0.99,
        )
        assertEquals(BridgeGuideDecision.Show(askMute = true), decision)
    }

    @Test
    fun `launch prompts only under thirty percent`() {
        val show = bridgeGuideDecision(
            trigger = BridgeGuideTrigger.LAUNCH,
            status = BridgeStatus.Absent,
            cancelledCount = 0,
            muted = false,
            random = 0.29,
        )
        val skip = bridgeGuideDecision(
            trigger = BridgeGuideTrigger.LAUNCH,
            status = BridgeStatus.Absent,
            cancelledCount = 0,
            muted = false,
            random = 0.31,
        )
        assertTrue(show is BridgeGuideDecision.Show)
        assertEquals(BridgeGuideDecision.Skip, skip)
    }

    @Test
    fun `ready or muted never prompts`() {
        assertEquals(
            BridgeGuideDecision.Skip,
            bridgeGuideDecision(
                trigger = BridgeGuideTrigger.FIRST_CONNECT,
                status = BridgeStatus.Ready(
                    io.qwenpaw.node.data.model.BridgeInfoDto(
                        pluginVersion = "0.4.0",
                        protocolVersion = 1,
                        serverVersion = "2.3.0.post1",
                        capabilities = listOf("info"),
                    ),
                ),
                cancelledCount = 0,
                muted = false,
                random = 0.0,
            ),
        )
        assertEquals(
            BridgeGuideDecision.Skip,
            bridgeGuideDecision(
                trigger = BridgeGuideTrigger.FIRST_CONNECT,
                status = BridgeStatus.Absent,
                cancelledCount = 3,
                muted = true,
                random = 0.0,
            ),
        )
    }
}
