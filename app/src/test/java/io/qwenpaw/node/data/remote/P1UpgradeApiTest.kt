package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 0.7.6 P1 新增端点：默认思考强度、语音、拒绝路径、改密、时区与头像。 */
class P1UpgradeApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        conn = Connection(server.url("/").toString().trimEnd('/'), "tok", "default")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `thinking default omits model params when absent`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"control":{"mode":"levels"},"value":{"level":"medium"}}""",
            ),
        )
        api.chatThinkingDefault(conn)
        assertEquals("/api/chats/thinking-default", server.takeRequest().path)
    }

    @Test
    fun `thinking default passes provider and model`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        api.chatThinkingDefault(conn, "dashscope", "qwen-max")
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.startsWith("/api/chats/thinking-default?"))
        assertTrue(path.contains("provider_id=dashscope"))
        assertTrue(path.contains("model=qwen-max"))
    }

    @Test
    fun `audio mode put sends audio mode`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"audio_mode":"native"}"""))
        assertEquals("native", api.setAudioMode(conn, "native").audioMode)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/workspace/audio-mode", request.path)
        assertTrue(request.body.readUtf8().contains("\"audio_mode\":\"native\""))
    }

    @Test
    fun `transcription provider type provider and model writes`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"transcription_provider_type":"whisper_api"}"""),
        )
        api.setTranscriptionProviderType(conn, "whisper_api")
        assertTrue(
            server.takeRequest().body.readUtf8()
                .contains("\"transcription_provider_type\":\"whisper_api\""),
        )

        server.enqueue(MockResponse().setBody("""{"provider_id":"openai"}"""))
        api.setTranscriptionProvider(conn, "openai")
        assertEquals("/api/workspace/transcription-provider", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"transcription_model":"whisper-1"}"""))
        assertEquals("whisper-1", api.setTranscriptionModel(conn, "whisper-1").model)
    }

    @Test
    fun `deny paths round trip`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"active":false,"protected_paths":[],"failed_paths":[],
                   "platform_supported":false,"message":"only windows"}""",
            ),
        )
        val status = api.denyPathsProtection(conn)
        assertFalse(status.platformSupported)
        assertEquals("only windows", status.message)
        server.takeRequest() // consume GET

        server.enqueue(
            MockResponse().setBody(
                """{"active":true,"protected_paths":["C:/x"],"failed_paths":[],
                   "platform_supported":true}""",
            ),
        )
        val enabled = api.setDenyPathsProtection(conn, true)
        assertTrue(enabled.active)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"enabled\":true"))
    }

    @Test
    fun `update profile returns rotated token and omits empty password`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"token":"new-token","username":"alice2"}"""),
        )
        val token = api.updateProfile(conn, "old-pass", "alice2", null)
        assertEquals("new-token", token.token)
        val request = server.takeRequest()
        assertEquals("/api/auth/update-profile", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"current_password\":\"old-pass\""))
        assertTrue(body.contains("\"new_username\":\"alice2\""))
        assertFalse(body.contains("new_password"))
    }

    @Test
    fun `user timezone put sends timezone`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"timezone":"Asia/Shanghai"}"""))
        assertEquals("Asia/Shanghai", api.setUserTimezone(conn, "Asia/Shanghai"))
        val request = server.takeRequest()
        assertEquals("/api/config/user-timezone", request.path)
        assertTrue(request.body.readUtf8().contains("\"timezone\":\"Asia/Shanghai\""))
    }

    @Test
    fun `avatar select sends image id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"selected":"abc","history":[]}"""))
        api.selectAvatar(conn, "abc")
        val request = server.takeRequest()
        assertEquals("/api/profile/avatars/selection", request.path)
        assertTrue(request.body.readUtf8().contains("\"image_id\":\"abc\""))
    }

    @Test
    fun `avatar upload posts raw bytes`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"selected":"new","history":[]}"""))
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        api.uploadAvatar(conn, bytes, "image/png")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/profile/avatars", request.path)
        assertEquals("image/png", request.getHeader("Content-Type")?.substringBefore(";"))
        assertArrayEquals(bytes, request.body.readByteArray())
    }
}
