package io.qwenpaw.node.notify

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalSessionNotifierTest {

    @Test
    fun `open request before collection is replayed once and then consumed`() = runTest {
        TerminalSessionNotifier.consumeOpen()

        TerminalSessionNotifier.requestOpen()
        // 冷启动时通知先于界面订阅到达：replay 保证收集方仍能收到。
        val received = withTimeoutOrNull(1_000) {
            TerminalSessionNotifier.openRequests.first()
        }

        assertNotNull("open request should be replayed to a late collector", received)

        TerminalSessionNotifier.consumeOpen()
        val repeated = withTimeoutOrNull(100) {
            TerminalSessionNotifier.openRequests.first()
        }
        assertNull("consumed request must not replay after rotation", repeated)
    }
}
