package io.qwenpaw.node.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SSE 帧解析。重点覆盖「未知事件忽略」与「坏 JSON 不抛」。 */
class BridgeEventsTest {

    @Test
    fun `ready frame decodes`() {
        val block = "event: ready\ndata: {\"protocol_version\":1}\n"
        assertEquals(BridgeEvent.Ready, parseBridgeEvent(block))
    }

    @Test
    fun `inbox frame carries unread count`() {
        val block = "event: inbox\ndata: {\"unread_count\":3}\n"
        val event = parseBridgeEvent(block)
        assertEquals(3, (event as BridgeEvent.Unread).unreadCount)
    }

    @Test
    fun `approval frame carries pending count`() {
        val block = "event: approval\ndata: {\"pending\":1}\n"
        val event = parseBridgeEvent(block)
        assertEquals(1, (event as BridgeEvent.PendingApproval).pending)
    }

    @Test
    fun `unknown event is ignored`() {
        val block = "event: cron\ndata: {\"running\":2}\n"
        assertNull(parseBridgeEvent(block))
    }

    @Test
    fun `malformed json does not throw`() {
        val block = "event: inbox\ndata: not-json\n"
        assertNull(parseBridgeEvent(block))
    }

    @Test
    fun `missing data is ignored`() {
        assertNull(parseBridgeEvent("event: inbox\n"))
    }
}
