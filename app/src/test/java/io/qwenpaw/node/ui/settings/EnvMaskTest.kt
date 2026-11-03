package io.qwenpaw.node.ui.settings

import io.qwenpaw.node.data.model.EnvCatalogDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境变量的展示规则。
 *
 * `GET /api/envs` 在局域网内直接返回明文密钥，客户端必须默认遮罩；
 * 遮罩长度固定，不跟随原值——圆点个数会泄露长度。
 */
class EnvMaskTest {

    @Test
    fun `mask hides the value with a fixed number of dots`() {
        assertEquals("••••••••", maskValue("s3cr3t"))
        // 长度不同但遮罩相同，避免从圆点数量反推原值长度。
        assertEquals(maskValue("a"), maskValue("a-much-longer-value"))
    }

    @Test
    fun `empty value is shown explicitly`() {
        assertEquals("（空）", maskValue(""))
    }

    /** 规格表里标为只读的项不允许编辑；缺规格时按可编辑处理。 */
    @Test
    fun `editability follows the catalog`() {
        assertTrue(isEnvEditable(null))
        assertTrue(isEnvEditable(EnvCatalogDto(key = "A", editable = true)))
        assertFalse(isEnvEditable(EnvCatalogDto(key = "B", editable = false)))
    }

    /** 后台策略只有两个合法取值，界面按这两个值决定开关状态。 */
    @Test
    fun `offload constants match the server contract`() {
        assertEquals("offload", OFFLOAD)
        assertEquals("keep_foreground", KEEP_FOREGROUND)
    }
}