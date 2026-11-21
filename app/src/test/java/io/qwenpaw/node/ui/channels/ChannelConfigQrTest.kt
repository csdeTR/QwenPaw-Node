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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 二维码授权搬进配置页后：打开取码、关闭回收会话。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelConfigQrTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: ChannelConfigViewModel
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
                    path == "/api/config/channels/schemas" -> MockResponse().setBody(
                        """{"wechat":{"label":"WeChat","qrcode_available":true,
                            "availability":"running",
                            "config_fields":[{"name":"enabled","label":"Enabled","type":"switch"}],
                            "defaults":{"enabled":true}}}""",
                    )

                    path == "/api/config/channels/wechat" -> MockResponse()
                        .setBody("""{"enabled":true}""")

                    path == "/api/config/channels/wechat/qrcode" && request.method == "DELETE" ->
                        MockResponse().setResponseCode(204)

                    path == "/api/config/channels/wechat/qrcode" -> MockResponse()
                        .setBody("""{"qrcode_img":"aGVsbG8=","poll_token":"p1"}""")

                    path == "/api/config/channels/wechat/qrcode/status" -> MockResponse()
                        .setBody("""{"status":"waiting","credentials":{}}""")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        viewModel = ChannelConfigViewModel(
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

    private fun waitUntil(name: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("$name not met in time", condition())
    }

    @Test
    fun `open qr fetches image and close reclaims the session`() {
        viewModel.open("wechat", "微信")
        waitUntil("loaded") { viewModel.state.value.loaded }
        assertTrue(viewModel.state.value.qrAvailable)

        viewModel.openQr()
        waitUntil("auth waiting") { viewModel.state.value.auth?.phase == ChannelAuthPhase.WAITING }
        assertNotNull(viewModel.state.value.auth)
        assertEquals("p1", viewModel.state.value.auth?.pollToken)

        viewModel.closeQr()
        waitUntil("qr close") {
            requests.any {
                it.method == "DELETE" &&
                    it.path.orEmpty().substringBefore('?') == "/api/config/channels/wechat/qrcode"
            }
        }
        assertNull(viewModel.state.value.auth)
    }
}