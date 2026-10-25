package io.qwenpaw.node.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件解析测试。
 *
 * 用例里的 JSON 全部取自本机 QwenPaw 2.2.1 的真实 SSE 抓包，保证与实际协议一致。
 */
class ChatEventParserTest {

    @Test
    fun `parses response created frame`() {
        val raw = """{"id":"response_8934","output":[],"status":"created","created_at":"2026-10-25T16:15:42+00:00","completed_at":null,"metadata":null,"object":"response","session_id":"probe-1","sequence_number":1}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.ResponseStatus
        assertEquals("created", event.status)
        assertEquals(1, event.sequence)
    }

    @Test
    fun `parses response completed frame`() {
        val raw = """{"id":"response_1","output":[],"status":"completed","object":"response","session_id":"s","sequence_number":171}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.ResponseStatus
        assertEquals("completed", event.status)
        assertEquals(171, event.sequence)
    }

    @Test
    fun `parses message in progress frame`() {
        val raw = """{"id":"msg_17f1","type":"reasoning","role":"assistant","content":[],"status":"in_progress","metadata":null,"name":"assistant","object":"message","sequence_number":3}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.MessageBlock
        assertEquals("msg_17f1", event.msgId)
        assertEquals("reasoning", event.kind)
        assertEquals("in_progress", event.status)
    }

    @Test
    fun `parses text delta with spaces in payload`() {
        val raw = """{"type": "text", "delta": true, "index": 0, "status": null, "object": "content", "msg_id": "msg_17f1", "text": "收到", "sequence_number": 4}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.TextDelta
        assertEquals("msg_17f1", event.msgId)
        assertEquals("收到", event.text)
        assertEquals(0, event.index)
    }

    @Test
    fun `ignores non delta content snapshot`() {
        val raw = """{"type":"text","delta":false,"index":0,"status":null,"object":"content","msg_id":"msg_1","text":"完整快照","sequence_number":17}"""
        assertEquals(ChatEvent.Ignored, ChatEventParser.parse(raw))
    }

    @Test
    fun `parses plugin_call into tool call`() {
        val raw = """{"id":"msg_f135","type":"plugin_call","role":"assistant","content":[{"type":"data","delta":false,"index":null,"status":"completed","object":"content","msg_id":null,"data":{"call_id":"call_ea73","name":"execute_shell_command","arguments":"{\"command\": \"ls -la\"}"},"sequence_number":27}],"status":"completed","metadata":null,"name":"assistant","object":"message","sequence_number":28}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.ToolCall
        assertEquals("call_ea73", event.callId)
        assertEquals("execute_shell_command", event.name)
        assertTrue(event.arguments.contains("ls -la"))
    }

    @Test
    fun `parses plugin_call_output into tool output`() {
        val raw = """{"id":"msg_5848","type":"plugin_call_output","role":"tool","content":[{"type":"data","delta":false,"index":0,"status":"completed","object":"content","msg_id":null,"data":{"call_id":"call_ea73","name":"execute_shell_command","output":"总计 328"},"sequence_number":29}],"status":"completed","name":"assistant","object":"message","sequence_number":30}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.ToolOutput
        assertEquals("call_ea73", event.callId)
        assertEquals("总计 328", event.output)
    }

    @Test
    fun `parses turn usage`() {
        val raw = """{"type": "turn_usage", "session_id": "probe-tool", "usage": {"prompt_tokens": 28581}, "context_usage": {"estimated_tokens": 959, "max_input_length": 131073, "context_usage_ratio": 0.7316}}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.TurnUsage
        assertEquals(959L, event.estimatedTokens)
        assertEquals(131073L, event.maxInputLength)
        assertEquals(0.7316, event.ratio, 0.0001)
    }

    @Test
    fun `parses rate limited with alternatives`() {
        val raw = """{"type":"rate_limited","error":"quota exceeded","alternatives":["qwen3.8-flash","qwen-max"]}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.RateLimited
        assertEquals("quota exceeded", event.message)
        assertEquals(listOf("qwen3.8-flash", "qwen-max"), event.alternatives)
    }

    @Test
    fun `parses error object`() {
        val raw = """{"error":{"message":"MODEL_EXECUTION_FAILED"}}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.Failure
        assertEquals("MODEL_EXECUTION_FAILED", event.message)
    }

    @Test
    fun `parses error string`() {
        val raw = """{"error":"stream broken"}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.Failure
        assertEquals("stream broken", event.message)
    }

    @Test
    fun `malformed json is ignored instead of crashing`() {
        assertEquals(ChatEvent.Ignored, ChatEventParser.parse("not a json"))
        assertEquals(ChatEvent.Ignored, ChatEventParser.parse("[1,2,3]"))
        assertEquals(ChatEvent.Ignored, ChatEventParser.parse(""))
    }

    @Test
    fun `unknown object is ignored`() {
        val raw = """{"object":"something_new","sequence_number":5}"""
        assertEquals(ChatEvent.Ignored, ChatEventParser.parse(raw))
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val raw = """{"object":"response","status":"in_progress","sequence_number":2,"brand_new_field":{"x":1}}"""
        val event = ChatEventParser.parse(raw) as ChatEvent.ResponseStatus
        assertEquals("in_progress", event.status)
    }
}
