package io.qwenpaw.node.data

import io.qwenpaw.node.TestTexts
import io.qwenpaw.node.data.local.AddressEntry
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.local.InstanceStorage
import io.qwenpaw.node.data.remote.QwenPawApi
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

/** 内存存储：仓库的令牌路径要落在「保存了什么」，不需要 Keystore。 */
private class MemoryStore : InstanceStorage {
    private var list: List<InstanceConfig> = emptyList()
    private var active: String? = null

    override fun load(): List<InstanceConfig> = list
    override fun save(instances: List<InstanceConfig>) {
        list = instances
    }

    override fun activeId(): String? = active
    override fun setActiveId(id: String?) {
        active = id
    }
}

/**
 * 令牌模式的两条路径：试一遍不落盘、以及建立实例后落盘。
 *
 * 用按路径应答的 [Dispatcher]，不用队列：连接成功后会读一次头像算实例指纹，
 * 队列少排一条就会一直等下去（曾把整套门禁卡死）。
 */
class InstanceRepositoryTokenTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: InstanceRepository
    private var authEnabled = true
    private var verifyOk = true

    @Before
    fun setUp() {
        // 失败路径会取「令牌无效」的文案，先把资源绑上。
        TestTexts.install()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return when (path) {
                    "/api/auth/status" -> MockResponse().setBody(
                        if (authEnabled) {
                            """{"enabled":true,"has_users":true}"""
                        } else {
                            """{"enabled":false,"has_users":false}"""
                        },
                    )

                    "/api/auth/verify" -> when {
                        !verifyOk -> MockResponse().setResponseCode(401)
                            .setBody("""{"detail":"Invalid or expired token"}""")

                        // 未启用登录时服务端返回 valid 但用户名为空串。
                        !authEnabled -> MockResponse()
                            .setBody("""{"valid":true,"username":""}""")

                        else -> MockResponse()
                            .setBody("""{"valid":true,"username":"alice"}""")
                    }

                    "/api/version" -> MockResponse().setBody("""{"version":"2.3.0"}""")
                    "/api/agents" -> MockResponse()
                        .setBody("""{"agents":[{"id":"default","name":"default"}]}""")

                    "/api/profile/avatars" -> MockResponse()
                        .setBody("""{"selected":null,"history":[]}""")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        repository = InstanceRepository(MemoryStore(), QwenPawApi())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = "http://${server.hostName}:${server.port}"

    @Test
    fun `checkToken reports auth disabled without probing the token`() {
        authEnabled = false
        val result = runBlockingSuspend { repository.checkToken(listOf(url()), "tk") }
        assertTrue(result is TokenCheck.AuthDisabled)
    }

    @Test
    fun `checkToken reports valid with server username`() {
        val result = runBlockingSuspend { repository.checkToken(listOf(url()), "tk") }
        result as TokenCheck.Valid
        assertEquals("alice", result.username)
    }

    @Test
    fun `checkToken reports invalid on 401`() {
        verifyOk = false
        val result = runBlockingSuspend { repository.checkToken(listOf(url()), "tk") }
        assertTrue(result is TokenCheck.Invalid)
    }

    @Test
    fun `connectWithToken persists token username and mode`() {
        val config = runBlockingSuspend {
            repository.connectWithToken(
                name = "home",
                addresses = listOf(AddressEntry(url())),
                token = "tk",
                agentId = "default",
            )
        }.instance

        assertEquals("tk", config.token)
        assertEquals("alice", config.username)
        assertEquals(InstanceConfig.CREDENTIAL_MODE_TOKEN, config.credentialMode)
        assertTrue(config.usesTokenCredential)
        assertTrue(repository.active()?.id == config.id)
    }

    @Test
    fun `connectWithToken leaves storage untouched on rejected token`() {
        verifyOk = false
        val threw = runCatching {
            runBlockingSuspend {
                repository.connectWithToken(
                    name = "home",
                    addresses = listOf(AddressEntry(url())),
                    token = "bad",
                    agentId = "default",
                )
            }
        }.isFailure
        assertTrue(threw)
        assertNull(repository.active())
        assertTrue(repository.instances.value.isEmpty())
    }

    @Test
    fun `connectWithToken flags auth disabled but still saves`() {
        authEnabled = false
        val result = runBlockingSuspend {
            repository.connectWithToken(
                name = "home",
                addresses = listOf(AddressEntry(url())),
                token = "tk",
                agentId = "default",
            )
        }
        assertTrue(result.authDisabled)
        assertNull(result.username)
        assertEquals("tk", result.instance.token)
    }

    @Test
    fun `old instance without credential mode reads as password`() {
        val legacy = InstanceConfig(id = "a", name = "a", agentId = "default")
        assertFalse(legacy.usesTokenCredential)
        assertEquals(InstanceConfig.CREDENTIAL_MODE_PASSWORD, legacy.credentialMode)
    }
}

/** 仓库的方法是 suspend 的；单测里用同一个线程跑完即可。 */
private fun <T> runBlockingSuspend(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }