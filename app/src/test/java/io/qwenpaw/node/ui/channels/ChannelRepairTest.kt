package io.qwenpaw.node.ui.channels

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 频道排序与插件修复三态的分派。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRepairTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: ChannelViewModel
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path == "/api/config/channels" -> MockResponse().setBody(
                        """{"wechat":{"enabled":true},"console":{"enabled":true},
                           "telegram":{"enabled":false},"dingtalk":{"enabled":false},"feishu":{"enabled":false}}""",
                    )

                    path == "/api/config/channels/schemas" -> MockResponse().setBody(
                        """{
                          "wechat":{"label":"WeChat","qrcode_available":true,"availability":"running"},
                          "console":{"label":"console","qrcode_available":false,"availability":"running"},
                          "telegram":{"label":"Telegram","availability":"disabled",
                            "repair":{"action":"enable","plugin_id":"telegram-plugin"}},
                          "dingtalk":{"label":"DingTalk","availability":"missing_code",
                            "repair":{"action":"install","plugin_id":"dingtalk-plugin",
                                      "source":"bundled:dingtalk-plugin"}},
                          "feishu":{"label":"Feishu","availability":"validation_pending",
                            "repair":{"action":"repair","plugin_id":"feishu-plugin"}}
                        }""",
                    )

                    else -> MockResponse().setBody("""{"ok":true}""")
                }
            }
        }
        server.start()
        viewModel = ChannelViewModel(
            api = QwenPawApi(),
            connection = { Connection("http://${server.hostName}:${server.port}", "tk") },
        )
    }

    @After
    fun tearDown() {
        viewModel.clear()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("condition not met in time", condition())
    }

    @Test
    fun `enabled channels come first and repair channels follow`() {
        viewModel.refresh()
        waitUntil { viewModel.state.value.loaded }

        val keys = viewModel.state.value.channels.map { it.key }
        assertEquals(listOf("wechat", "console", "dingtalk", "feishu", "telegram"), keys)
        assertEquals("install", viewModel.state.value.channels[2].repairAction)
    }

    @Test
    fun `install action calls plugin install with bundled source`() {
        viewModel.refresh()
        waitUntil { viewModel.state.value.loaded }

        viewModel.repairChannel("dingtalk")
        waitUntil {
            requests.any { it.method == "POST" && it.path == "/api/plugins/install" }
        }

        val request = requests.first { it.method == "POST" && it.path == "/api/plugins/install" }
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"source\":\"bundled:dingtalk-plugin\""))
        assertTrue(body.contains("\"force\":true"))
    }

    @Test
    fun `enable action enables the plugin instead of installing`() {
        viewModel.refresh()
        waitUntil { viewModel.state.value.loaded }

        viewModel.repairChannel("telegram")
        waitUntil {
            requests.any {
                it.method == "POST" && it.path == "/api/plugins/telegram-plugin/enabled"
            }
        }

        val request = requests.first {
            it.method == "POST" && it.path == "/api/plugins/telegram-plugin/enabled"
        }
        assertTrue(request.body.readUtf8().contains("\"enabled\":true"))
    }

    @Test
    fun `repair action calls repair-dependencies`() {
        viewModel.refresh()
        waitUntil { viewModel.state.value.loaded }

        viewModel.repairChannel("feishu")
        waitUntil {
            requests.any {
                it.method == "POST" && it.path == "/api/plugins/feishu-plugin/repair-dependencies"
            }
        }
    }

    @Test
    fun `unsupported version hides channels and sends no request`() {
        val gated = ChannelViewModel(
            api = QwenPawApi(),
            connection = { Connection("http://${server.hostName}:${server.port}", "tk") },
            serverVersion = { "2.2.2" },
        )
        gated.refresh()

        assertTrue(gated.state.value.versionUnsupported)
        assertFalse(gated.state.value.versionUnknown)
        assertEquals("2.2.2", gated.state.value.serverVersionRaw)
        assertTrue(gated.state.value.channels.isEmpty())
        assertTrue("低版本不应发起频道请求", requests.isEmpty())
        gated.clear()
    }

    @Test
    fun `unknown version warns but still loads`() {
        val gated = ChannelViewModel(
            api = QwenPawApi(),
            connection = { Connection("http://${server.hostName}:${server.port}", "tk") },
            serverVersion = { null },
        )
        gated.refresh()
        waitUntil { gated.state.value.loaded }

        assertTrue(gated.state.value.versionUnknown)
        assertFalse(gated.state.value.versionUnsupported)
        assertTrue(gated.state.value.channels.isNotEmpty())
        gated.clear()
    }
}