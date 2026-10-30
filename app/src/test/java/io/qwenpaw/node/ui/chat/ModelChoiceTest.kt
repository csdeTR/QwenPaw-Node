package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ModelSlotDto
import io.qwenpaw.node.data.model.ProviderDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型列表的合并与去重。
 *
 * 服务端把可选模型拆在三个数组里（配置 / 额外 / 探测），客户端要合并成一层。
 * 顺序有意义：先配置、再额外、后探测，同名模型保留先出现的那个。
 */
class ModelChoiceTest {

    private fun provider(
        models: List<ModelSlotDto> = emptyList(),
        extra: List<ModelSlotDto> = emptyList(),
        discovered: List<ModelSlotDto> = emptyList(),
    ) = ProviderDto(
        id = "zhipu",
        name = "智谱",
        models = models,
        extraModels = extra,
        discoveredModels = discovered,
    )

    @Test
    fun `merges three arrays in order`() {
        val p = provider(
            models = listOf(ModelSlotDto(id = "a")),
            extra = listOf(ModelSlotDto(id = "b")),
            discovered = listOf(ModelSlotDto(id = "c")),
        )
        assertEquals(listOf("a", "b", "c"), p.allModels.map { it.id })
    }

    @Test
    fun `deduplicates by id keeping first occurrence`() {
        val p = provider(
            models = listOf(ModelSlotDto(id = "a", name = "配置里的")),
            extra = listOf(ModelSlotDto(id = "a", name = "额外里的"), ModelSlotDto(id = "b")),
        )
        assertEquals(listOf("a", "b"), p.allModels.map { it.id })
        // 保留第一次出现的那个，后面的重复项不会覆盖名称。
        assertEquals("配置里的", p.allModels.first().name)
    }

    @Test
    fun `skips blank ids`() {
        val p = provider(models = listOf(ModelSlotDto(id = ""), ModelSlotDto(id = "a")))
        assertEquals(listOf("a"), p.allModels.map { it.id })
    }

    /** 三个数组都空时得到空列表，菜单据此显示「未配置可用模型」。 */
    @Test
    fun `all empty yields nothing`() {
        assertTrue(provider().allModels.isEmpty())
    }

    @Test
    fun `choice key is unique across providers`() {
        val a = ModelChoice("p1", "供应商1", "m", "m")
        val b = ModelChoice("p2", "供应商2", "m", "m")
        assertEquals("p1:m", a.key)
        assertEquals("p2:m", b.key)
        // 同一个模型名在不同供应商下是两个选择，不能去重掉。
        assertTrue(a.key != b.key)
    }

    // ---------------- 审批模式 ----------------

    @Test
    fun `approval label maps all four levels`() {
        assertEquals("严格", approvalLabel("STRICT"))
        assertEquals("智能", approvalLabel("SMART"))
        assertEquals("自动", approvalLabel("AUTO"))
        assertEquals("关闭防护", approvalLabel("OFF"))
    }

    @Test
    fun `unknown approval level falls back to placeholder`() {
        assertEquals("未读取", approvalLabel(null))
        assertEquals("未读取", approvalLabel(""))
        assertEquals("未读取", approvalLabel("SOMETHING_NEW"))
    }

    /** 四个档位与 qwenpaw web 页一致，改动会直接影响用户对风险的预期。 */
    @Test
    fun `approval options cover the four server levels`() {
        assertEquals(
            listOf("STRICT", "SMART", "AUTO", "OFF"),
            APPROVAL_OPTIONS.map { it.code },
        )
        assertTrue(APPROVAL_OPTIONS.all { it.hint.isNotBlank() })
    }
}