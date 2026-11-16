package io.qwenpaw.node.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行配置的局部修改。
 *
 * 服务端要求整份回写，客户端只认其中几项；这里守的是「其余字段一个都不能丢」，
 * 以及数字输入的校验在提交前就把明显错误拦住。
 */
class RunningConfigEditTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `reads editable fields including nested ratio`() {
        val raw = obj(
            """{"max_iters":80,"max_input_length":64000,"history_max_length":30,
               "shell_command_timeout":120.5,"llm_retry_enabled":false,"llm_max_retries":5,
               "approval_level":"SMART",
               "light_context_config":{"context_compact_config":{"compact_threshold_ratio":0.65}}}""",
        )
        val form = RunningConfigEdit.fromJson(raw)
        assertEquals("80", form.maxIters)
        assertEquals("64000", form.maxInputLength)
        assertEquals("30", form.historyMaxLength)
        assertEquals("120.5", form.shellCommandTimeout)
        assertEquals(false, form.llmRetryEnabled)
        assertEquals("5", form.llmMaxRetries)
        assertEquals("SMART", form.approvalLevel)
        assertEquals("0.65", form.compactThresholdRatio)
    }

    @Test
    fun `apply keeps unknown and nested fields`() {
        val raw = obj(
            """{"max_iters":80,"future":{"a":1},
               "light_context_config":{"context_compact_config":{"compact_threshold_ratio":0.65,"x":2},
                 "other":{"y":3}},
               "memory_backend_configs":{"remelight":{"api_key":"***"}}}""",
        )
        val form = RunningConfigEdit.fromJson(raw).copy(
            maxIters = "120",
            approvalLevel = "OFF",
            compactThresholdRatio = "0.5",
        )
        val out = RunningConfigEdit.apply(raw, form)
        assertEquals("120", out["max_iters"]?.jsonPrimitive?.content)
        assertEquals("OFF", out["approval_level"]?.jsonPrimitive?.content)
        assertNotNull(out["future"])
        assertNotNull(out["memory_backend_configs"])
        val light = out["light_context_config"]?.jsonObject
        assertEquals("3", light?.get("other")?.jsonObject?.get("y")?.jsonPrimitive?.content)
        val compact = light?.get("context_compact_config")?.jsonObject
        assertEquals("2", compact?.get("x")?.jsonPrimitive?.content)
        assertEquals("0.5", compact?.get("compact_threshold_ratio")?.jsonPrimitive?.content)
    }

    @Test
    fun `blank numeric does not overwrite existing value`() {
        val raw = obj("""{"max_iters":80,"shell_command_timeout":30}""")
        val form = RunningConfigEdit.fromJson(raw).copy(
            maxIters = "",
            shellCommandTimeout = "",
        )
        val out = RunningConfigEdit.apply(raw, form)
        assertEquals("80", out["max_iters"]?.jsonPrimitive?.content)
        assertEquals("30", out["shell_command_timeout"]?.jsonPrimitive?.content)
    }

    @Test
    fun `validate rejects out of range values`() {
        val base = RunningConfigForm()
        assertNotNull(RunningConfigEdit.validate(base.copy(maxIters = "0")))
        assertNotNull(RunningConfigEdit.validate(base.copy(maxIters = "abc")))
        assertNotNull(RunningConfigEdit.validate(base.copy(shellCommandTimeout = "0")))
        assertNotNull(RunningConfigEdit.validate(base.copy(compactThresholdRatio = "1.2")))
        assertNotNull(RunningConfigEdit.validate(base.copy(approvalLevel = "NOPE")))
        assertNull(RunningConfigEdit.validate(base.copy(maxIters = "5", compactThresholdRatio = "1")))
    }

    @Test
    fun `apply writes booleans and integers`() {
        val raw = obj("""{"llm_retry_enabled":true,"llm_max_retries":3}""")
        val out = RunningConfigEdit.apply(
            raw,
            RunningConfigEdit.fromJson(raw).copy(llmRetryEnabled = false, llmMaxRetries = "0"),
        )
        assertEquals("false", out["llm_retry_enabled"]?.jsonPrimitive?.content)
        assertEquals("0", out["llm_max_retries"]?.jsonPrimitive?.content)
        assertTrue(out.containsKey("llm_retry_enabled"))
    }
}
