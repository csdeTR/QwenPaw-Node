package io.qwenpaw.node.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置环境变量的说明文案与枚举映射。
 *
 * `catalog` 在 2.2.2.post1 上固定返回 17 个内置项（`envs/registry.py:42-160`）。
 * 只读弹窗靠这张表给出说明与修改方式，缺一条就会露出「暂无说明」，
 * 因此这里逐条校验。
 */
class EnvCatalogCopyTest {

    private val builtinKeys = listOf(
        "QWENPAW_LLM_STREAM_FIRST_CONTENT_TIMEOUT",
        "QWENPAW_LLM_STREAM_IDLE_TIMEOUT",
        "QWENPAW_LLM_MAX_RETRIES",
        "QWENPAW_LLM_BACKOFF_BASE",
        "QWENPAW_LLM_BACKOFF_CAP",
        "QWENPAW_LLM_MAX_CONCURRENT",
        "QWENPAW_LLM_MAX_QPM",
        "QWENPAW_LLM_RATE_LIMIT_PAUSE",
        "QWENPAW_LLM_RATE_LIMIT_JITTER",
        "QWENPAW_LLM_ACQUIRE_TIMEOUT",
        "QWENPAW_WORKING_DIR",
        "QWENPAW_SECRET_DIR",
        "QWENPAW_CONFIG_FILE",
        "QWENPAW_JOBS_FILE",
        "QWENPAW_CHATS_FILE",
        "QWENPAW_OPENAPI_DOCS",
        "QWENPAW_RUNNING_IN_CONTAINER",
    )

    @Test
    fun `catalog exposes seventeen built-in keys`() {
        assertEquals(17, builtinKeys.size)
    }

    @Test
    fun `every built-in key has description and how-to-modify`() {
        builtinKeys.forEach { key ->
            val copy = envSpecCopy(key)
            assertNotNull("缺少说明：$key", copy)
            assertTrue("描述为空：$key", copy!!.description.isNotBlank())
            assertTrue("修改方式为空：$key", copy.howToModify.isNotBlank())
        }
    }

    @Test
    fun `copy table does not contain unknown keys`() {
        val extra = ENV_SPEC_COPY.keys - builtinKeys.toSet()
        assertTrue("表里有未知键：$extra", extra.isEmpty())
    }

    @Test
    fun `readonly reason codes map to chinese text`() {
        assertEquals("QwenPaw 启动时确定", readonlyReasonText("startup"))
        assertEquals("仅作为 Agent 配置的初始化默认值", readonlyReasonText("initial_default"))
        assertEquals("由各 Agent 配置管理", readonlyReasonText("agent_owned"))
        assertNull(readonlyReasonText(null))
        assertNull(readonlyReasonText("something_new"))
    }

    @Test
    fun `source values map to chinese labels`() {
        assertEquals("QwenPaw", envSourceText("user"))
        assertEquals("系统", envSourceText("system"))
        assertEquals("默认", envSourceText("default"))
        // 未知来源原样返回，不吞掉服务端的新取值。
        assertEquals("custom", envSourceText("custom"))
    }

    @Test
    fun `readonly hint matches the official wording`() {
        assertEquals("仅供查看，QwenPaw 运行期间不能修改。", READONLY_HINT)
    }
}
