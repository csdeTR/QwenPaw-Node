package io.qwenpaw.node.ui.channels

import io.qwenpaw.node.TestTexts
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 缺插件时的安装面板与 console 强制启用。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelConfigLoadTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: ChannelConfigViewModel
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** 安装完成前配置 GET 一律 404，模拟插件未装的服务端。 */
    @Volatile
    private var configAvailable = false

    @Before
    fun setUp() {
        TestTexts.install()
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path == "/api/plugins/install" -> {
                        configAvailable = true
                        MockResponse().setBody("""{"ok":true}""")
                    }

                    path == "/api/config/channels/schemas" -> MockResponse().setBody(
                        """{
                          "dingtalk":{"label":"DingTalk","availability":"missing_code",
                            "config_fields":[
                              {"name":"enabled","label":"Enabled","type":"switch"},
                              {"name":"bot_prefix","label":"Bot Prefix","type":"text"}],
                            "defaults":{"enabled":true,"bot_prefix":""},
                            "repair":{"action":"install","plugin_id":"dingtalk",
                                      "source":"bundled:dingtalk"}},
                          "console":{"label":"console","availability":"running",
                            "config_fields":[
                              {"name":"enabled","label":"Enabled","type":"switch"},
                              {"name":"bot_prefix","label":"Bot Prefix","type":"text"}],
                            "defaults":{"enabled":true,"bot_prefix":""}}
                        }""",
                    )

                    path == "/api/config/channels/dingtalk" && configAvailable -> MockResponse()
                        .setBody("""{"enabled":true,"bot_prefix":"qp"}""")

                    path == "/api/config/channels/dingtalk" -> MockResponse()
                        .setResponseCode(404)
                        .setBody("""{"detail":"Not Found"}""")

                    path == "/api/config/channels/console" && request.method == "PUT" ->
                        MockResponse().setBody("""{"ok":true}""")

                    path == "/api/config/channels/console" -> MockResponse()
                        .setBody("""{"enabled":false,"bot_prefix":"qp"}""")

                    path == "/api/config/channels/console/conflict-check" -> MockResponse()
                        .setBody("""{"conflict":false,"agents":[]}""")

                    else -> MockResponse().setBody("""{"ok":true}""")
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

    private fun waitUntil(name: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("$name not met in time", condition())
    }

    @Test
    fun `missing plugin opens the install panel instead of a generic error`() {
        viewModel.open("dingtalk", "钉钉")
        waitUntil("loaded") { viewModel.state.value.loaded }

        val state = viewModel.state.value
        assertTrue(state.missingPlugin)
        assertNull(state.error)
        assertEquals("missing_code", state.availability)
        assertEquals("install", state.repairAction)
        assertEquals("bundled:dingtalk", state.repairSource)
        assertTrue(state.fields.isEmpty())
    }

    @Test
    fun `install from the panel reloads the configuration`() {
        viewModel.open("dingtalk", "钉钉")
        waitUntil("missing plugin") { viewModel.state.value.missingPlugin }

        viewModel.repairChannel()
        waitUntil("install request") {
            requests.any { it.method == "POST" && it.path == "/api/plugins/install" }
        }
        val install = requests.first {
            it.method == "POST" && it.path == "/api/plugins/install"
        }
        assertTrue(install.body.readUtf8().contains("\"source\":\"bundled:dingtalk\""))

        // 安装成功后第一次复查在 2 秒后，插件就绪即回到正常表单。
        waitUntil("reloaded", timeoutMs = 9_000) {
            !viewModel.state.value.missingPlugin
        }
        assertTrue(viewModel.state.value.fields.isNotEmpty())
    }

    @Test
    fun `console save always writes enabled true`() {
        viewModel.open("console", "控制台")
        waitUntil("loaded") { !viewModel.state.value.loading && viewModel.state.value.loaded }
        assertTrue(viewModel.state.value.fields.any { it.name == "enabled" })

        // 用户即使把开关关掉，保存也必须写 true。
        viewModel.setSwitch("enabled", false)
        viewModel.save()

        waitUntil("put request") {
            requests.any { it.method == "PUT" && it.path == "/api/config/channels/console" }
        }
        val put = requests.first {
            it.method == "PUT" && it.path == "/api/config/channels/console"
        }
        val body = put.body.readUtf8()
        assertTrue(body, body.contains("\"enabled\":true"))
        assertFalse(body.contains("\"enabled\":false"))
    }
}
