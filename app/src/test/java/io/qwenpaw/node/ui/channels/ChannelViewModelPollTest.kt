package io.qwenpaw.node.ui.channels

import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import java.util.concurrent.ConcurrentHashMap
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 二维码轮询的启停。
 *
 * 页面离开后如果继续轮询，服务端会一直保留扫码会话直到 TTL 到期。
 * 这里钉住「关闭面板后不再发状态请求」，同时覆盖关闭时的会话回收。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelPollTest {

    private lateinit var server: MockWebServer
    private lateinit var api: QwenPawApi
    private lateinit var viewModel: ChannelViewModel
    private val counts = ConcurrentHashMap<String, Int>()

    private fun hit(method: String, path: String) {
        counts.merge("$method|$path", 1, Int::plus)
    }

    private fun count(method: String, path: String): Int = counts["$method|$path"] ?: 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                hit(request.method.orEmpty(), path)
                return when {
                    path == "/api/config/channels/schemas" -> MockResponse()
                        .setBody("""{"wechat":{"label":"WeChat","qrcode_available":true}}""")

                    path == "/api/config/channels/wechat/qrcode/status" -> MockResponse()
                        .setBody("""{"status":"waiting","credentials":{}}""")

                    path == "/api/config/channels/wechat/qrcode" && request.method == "DELETE" ->
                        MockResponse().setResponseCode(204)

                    path == "/api/config/channels/wechat/qrcode" -> MockResponse()
                        .setBody("""{"qrcode_img":"aGVsbG8=","poll_token":"p1"}""")

                    path == "/api/config/channels" -> MockResponse()
                        .setBody("""{"wechat":{"enabled":false,"isBuiltin":true}}""")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        api = QwenPawApi()
        viewModel = ChannelViewModel(
            api = api,
            connection = { Connection("http://${server.hostName}:${server.port}", "tk") },
            pollIntervalMs = 60,
        )
    }

    @After
    fun tearDown() {
        viewModel.clear()
        Dispatchers.resetMain()
        server.shutdown()
    }

    @Test
    fun `closing the panel stops polling and releases the session`() {
        viewModel.refresh()
        waitUntil { viewModel.state.value.loaded }
        assertNotNull(viewModel.state.value.channels.firstOrNull { it.key == "wechat" })

        viewModel.openChannel("wechat")
        waitUntil { viewModel.state.value.auth?.phase == ChannelAuthPhase.WAITING }

        // 至少轮询一次，证明循环确实在跑。
        val pollsBefore = count("GET", "/api/config/channels/wechat/qrcode/status")
        waitUntil(timeoutMs = 3_000) {
            count("GET", "/api/config/channels/wechat/qrcode/status") > pollsBefore
        }

        viewModel.closeAuth()
        waitUntil { viewModel.state.value.auth == null }
        // 关闭时会发一次 DELETE 回收会话；等它落地再取基线。
        waitUntil(timeoutMs = 3_000) {
            count("DELETE", "/api/config/channels/wechat/qrcode") >= 1
        }
        assertTrue("session should be released", count("DELETE", "/api/config/channels/wechat/qrcode") >= 1)

        val pollsAfterClose = count("GET", "/api/config/channels/wechat/qrcode/status")
        Thread.sleep(400)
        assertEquals(
            "polling must stop after closeAuth",
            pollsAfterClose,
            count("GET", "/api/config/channels/wechat/qrcode/status"),
        )
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}