package io.qwenpaw.node.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 直连未配置时才追加平台/shell 设置位置。 */
class DirectAvailabilityTest {

    @Test
    fun `hint only appears for not configured reason`() {
        assertEquals(
            "platform hint",
            directHintFor("not configured", "not configured", "platform hint"),
        )
        assertNull(directHintFor("no key", "not configured", "platform hint"))
        assertNull(directHintFor(null, "not configured", "platform hint"))
    }
}
