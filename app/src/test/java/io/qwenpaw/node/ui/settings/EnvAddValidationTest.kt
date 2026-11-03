package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.EnvCatalogDto
import io.qwenpaw.node.data.model.EnvVarDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 新增环境变量时的名称校验。
 *
 * 每条规则都对应服务端的一处实现（`registry.py` / `envs.py`）；
 * 本地先拦是为了给出中文提示，不是为了替代服务端校验。
 * 因此这里的断言同时充当一份「本地与服务端口径一致」的记录。
 */
class EnvAddValidationTest {

    private val existing = listOf(EnvVarDto("DASHSCOPE_API_KEY", "sk-x"))
    private val catalog = listOf(
        EnvCatalogDto(
            key = "QWENPAW_LLM_MAX_RETRIES",
            editable = false,
            mutability = "startup_only",
            readonlyReasonCode = "initial_default",
        ),
        EnvCatalogDto(
            key = "QWENPAW_LLM_STREAM_IDLE_TIMEOUT",
            editable = true,
            mutability = "hot_runtime",
        ),
    )

    @Test
    fun `blank name is rejected`() {
        assertEquals("请输入变量名", validateNewEnvKey("", existing, catalog))
        assertEquals("请输入变量名", validateNewEnvKey("   ", existing, catalog))
    }

    @Test
    fun `illegal characters are rejected`() {
        listOf("1ABC", "HTTP-PROXY", "A B", "变量", "A.B").forEach {
            assertEquals(
                "变量名只能包含字母、数字和下划线，且不能以数字开头",
                validateNewEnvKey(it, existing, catalog),
            )
        }
    }

    /** 下划线开头合法：服务端正则的首字符是 `[A-Za-z_]`。 */
    @Test
    fun `leading underscore is allowed`() {
        assertNull(validateNewEnvKey("_PRIVATE", existing, catalog))
    }

    @Test
    fun `lowercase qwenpaw prefix is rejected`() {
        assertEquals(
            "QwenPaw 内置变量名必须全大写",
            validateNewEnvKey("qwenpaw_custom", existing, catalog),
        )
    }

    @Test
    fun `duplicate names are rejected case insensitively`() {
        assertEquals(
            "变量 DASHSCOPE_API_KEY 已存在，请直接编辑原值",
            validateNewEnvKey("DASHSCOPE_API_KEY", existing, catalog),
        )
        // 服务端按大小写不敏感查重，小写写法同样会 400。
        assertEquals(
            "变量 dashscope_api_key 已存在，请直接编辑原值",
            validateNewEnvKey("dashscope_api_key", existing, catalog),
        )
    }

    /** 内置项即使没有配置过，也不能通过「新增」覆盖只读项。 */
    @Test
    fun `read-only catalog entry is rejected`() {
        assertEquals(
            "该变量为服务端只读项，不能修改",
            validateNewEnvKey("QWENPAW_LLM_MAX_RETRIES", existing, catalog),
        )
    }

    /**
     * 未配置的内置可写项允许新增：这类项在 catalog 里、不在 vars 里，
     * 「新增」是覆盖其默认值的唯一入口。
     */
    @Test
    fun `writable catalog entry is allowed`() {
        assertNull(validateNewEnvKey("QWENPAW_LLM_STREAM_IDLE_TIMEOUT", existing, catalog))
    }

    /** 已配置的内置可写项仍按重名拦下：已有条目应当编辑，而不是加一条同名的。 */
    @Test
    fun `configured entry is rejected as duplicate`() {
        assertEquals(
            "变量 QWENPAW_LLM_STREAM_IDLE_TIMEOUT 已存在，请直接编辑原值",
            validateNewEnvKey(
                "QWENPAW_LLM_STREAM_IDLE_TIMEOUT",
                listOf(EnvVarDto("QWENPAW_LLM_STREAM_IDLE_TIMEOUT", "30")),
                catalog,
            ),
        )
    }

    @Test
    fun `new name is accepted`() {
        assertNull(validateNewEnvKey("HTTP_PROXY", existing, catalog))
    }
}