package io.qwenpaw.node.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 终端与 Git 端点的请求体与响应形状。 */
class TerminalGitApiTest {

    private lateinit var server: MockWebServer
    private val api = QwenPawApi()
    private lateinit var conn: Connection

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        conn = Connection(server.url("/").toString().trimEnd('/'), null, "agent-1")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `terminal output decodes reset and exited with cursor query`() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":"\u001b[32mok\u001b[0m","cursor":42,"reset":true,
                   "exited":true,"exit_code":7}""",
            ),
        )

        val output = api.terminalOutput(conn, "group-1", "term-1", after = 41)

        assertTrue(output.reset)
        assertTrue(output.exited)
        assertEquals(7, output.exitCode)
        assertEquals(42, output.cursor)
        assertEquals("\u001b[32mok\u001b[0m", output.data)
        val request = server.takeRequest()
        assertEquals("/api/terminals/group-1/term-1/output", request.requestUrl!!.encodedPath)
        assertEquals("41", request.requestUrl!!.queryParameter("after"))
        assertEquals("agent-1", request.getHeader("X-Agent-Id"))
    }

    @Test
    fun `terminal status and create use the documented paths`() {
        server.enqueue(MockResponse().setBody("""{"enabled":false,"reason":"pywinpty missing"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"term-1","title":"bash","cwd":"/w","exited":false}""",
            ),
        )

        val status = api.terminalStatus(conn)
        assertFalse(status.enabled)
        assertEquals("pywinpty missing", status.reason)

        val created = api.createTerminal(conn, "group-1")
        assertEquals("term-1", created.id)
        assertEquals("/w", created.cwd)
        assertNull(created.exitCode)

        val statusRequest = server.takeRequest()
        assertEquals("GET", statusRequest.method)
        assertEquals("/api/terminals/status", statusRequest.requestUrl!!.encodedPath)

        val createRequest = server.takeRequest()
        assertEquals("POST", createRequest.method)
        assertEquals("/api/terminals/group-1", createRequest.requestUrl!!.encodedPath)
        assertEquals(0L, createRequest.bodySize)
    }

    @Test
    fun `git status parses changes and counters`() {
        server.enqueue(
            MockResponse().setBody(
                """{"branch":"master","ahead":2,"behind":1,"changes":[
                     {"path":"a.txt","status":"M","staged":true},
                     {"path":"b.txt","status":"??","staged":false}
                   ]}""",
            ),
        )

        val status = api.gitStatus(conn)

        assertEquals("master", status.branch)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
        assertEquals(2, status.changes.size)
        assertTrue(status.changes[0].staged)
        assertEquals("??", status.changes[1].status)
        assertEquals("/api/workspace/git/status", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `git stage unstage and commit send json bodies`() {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"ok":true}""")) }

        api.gitStage(conn, listOf("a.txt", "b.txt"))
        api.gitUnstage(conn, emptyList())
        api.gitCommit(conn, "test: 提交")

        val stage = server.takeRequest()
        assertEquals("/api/workspace/git/stage", stage.requestUrl!!.encodedPath)
        assertEquals("""{"paths":["a.txt","b.txt"]}""", stage.body.readUtf8())

        val unstage = server.takeRequest()
        assertEquals("/api/workspace/git/unstage", unstage.requestUrl!!.encodedPath)
        assertEquals("""{"paths":[]}""", unstage.body.readUtf8())

        val commit = server.takeRequest()
        assertEquals("/api/workspace/git/commit", commit.requestUrl!!.encodedPath)
        assertEquals("""{"message":"test: 提交"}""", commit.body.readUtf8())
    }

    @Test
    fun `git diff and commit diff carry query parameters`() {
        server.enqueue(MockResponse().setBody("""{"diff":"diff --git a/a b/a"}"""))
        server.enqueue(MockResponse().setBody("""{"diff":"commit diff","hash":"abc1234"}"""))

        val diff = api.gitDiff(conn, path = "a.txt", staged = true, untracked = false)
        assertEquals("diff --git a/a b/a", diff.diff)

        val commit = api.gitCommitDiff(conn, "abc1234")
        assertEquals("commit diff", commit.diff)
        assertEquals("abc1234", commit.hash)

        val diffRequest = server.takeRequest()
        assertEquals("/api/workspace/git/diff", diffRequest.requestUrl!!.encodedPath)
        assertEquals("a.txt", diffRequest.requestUrl!!.queryParameter("path"))
        assertEquals("true", diffRequest.requestUrl!!.queryParameter("staged"))
        assertNull(diffRequest.requestUrl!!.queryParameter("untracked"))

        val commitRequest = server.takeRequest()
        assertEquals("/api/workspace/git/commit-diff", commitRequest.requestUrl!!.encodedPath)
        assertEquals("abc1234", commitRequest.requestUrl!!.queryParameter("commit_hash"))
    }

    @Test
    fun `git checkout sends branch and create flag`() {
        server.enqueue(MockResponse().setBody("""{"branch":"feature/x"}"""))

        val result = api.gitCheckout(conn, "feature/x", create = true)

        assertEquals("feature/x", result.branch)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/workspace/git/checkout", request.requestUrl!!.encodedPath)
        assertEquals("""{"branch":"feature/x","create":true}""", request.body.readUtf8())
    }

    @Test
    fun `git discard sends paths and parses discarded list`() {
        server.enqueue(MockResponse().setBody("""{"discarded":["a.txt","b.txt"]}"""))

        val result = api.gitDiscard(conn, listOf("a.txt", "b.txt"))

        assertEquals(listOf("a.txt", "b.txt"), result.discarded)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/workspace/git/discard", request.requestUrl!!.encodedPath)
        assertEquals("""{"paths":["a.txt","b.txt"]}""", request.body.readUtf8())
    }

    @Test
    fun `git revert sends commit hash and parses output`() {
        server.enqueue(
            MockResponse().setBody(
                """{"reverted":"abc1234","output":"[master 1234567] Revert"}""",
            ),
        )

        val result = api.gitRevert(conn, "abc1234")

        assertEquals("abc1234", result.reverted)
        assertEquals("[master 1234567] Revert", result.output)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/workspace/git/revert", request.requestUrl!!.encodedPath)
        assertEquals("""{"commit_hash":"abc1234"}""", request.body.readUtf8())
    }
}
