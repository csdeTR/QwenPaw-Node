package io.qwenpaw.node.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶栏「已连接」时的完整描述。
 *
 * 只说「已连接」看不出连的是哪条路：一台机器可能同时存着局域网地址与隧道地址。
 * 这里验证别名与地址的两种写法不会互相污染，也不会出现双重引号。
 */
class ConnectedLabelTest {

    @Test
    fun `alias is quoted when it differs from url`() {
        assertEquals(
            "已通过「EasyTier」连接至 QwenPaw",
            connectedLabel("http://10.8.0.2:8088", "EasyTier"),
        )
    }

    /** 别名与地址相同时直接写地址，不能出现「「192.168.1.10:8088」」。 */
    @Test
    fun `url is used unquoted when label equals it`() {
        assertEquals(
            "已通过 http://192.168.1.10:8088 连接至 QwenPaw",
            connectedLabel("http://192.168.1.10:8088", "http://192.168.1.10:8088"),
        )
    }

    /** 没有别名时回落地址，同样不加引号。 */
    @Test
    fun `blank label falls back to url`() {
        assertEquals(
            "已通过 http://192.168.1.10:8088 连接至 QwenPaw",
            connectedLabel("http://192.168.1.10:8088", ""),
        )
    }

    /** 两者都缺失时退回最简写法，不产出「已通过  连接至 QwenPaw」这种空洞句子。 */
    @Test
    fun `both blank yields short form`() {
        assertEquals("已连接", connectedLabel("", ""))
        assertEquals("已连接", connectedLabel("   ", "   "))
    }

    /** 只有别名时也要可用：地址可能尚未绑定完。 */
    @Test
    fun `label only still renders`() {
        assertEquals("已通过「家里」连接至 QwenPaw", connectedLabel("", "家里"))
    }

    /** 两端空白要去掉，否则会在句子中间出现双空格。 */
    @Test
    fun `values are trimmed`() {
        assertEquals(
            "已通过 http://a:8088 连接至 QwenPaw",
            connectedLabel("  http://a:8088  ", "  http://a:8088  "),
        )
    }

    // ---------------- 服务器名称 ----------------

    /** 设置过服务器名称时，末尾换成「名称」。 */
    @Test
    fun `server name replaces product name at the tail`() {
        assertEquals(
            "已通过「EasyTier」连接至「家里的机器」",
            connectedLabel("http://10.8.0.2:8088", "EasyTier", "家里的机器"),
        )
    }

    /** 没有别名时，地址在前、名称在后。 */
    @Test
    fun `server name works without alias`() {
        assertEquals(
            "已通过 http://a:8088 连接至「家里的机器」",
            connectedLabel("http://a:8088", "", "家里的机器"),
        )
    }

    /**
     * 名称只由空白组成等同于没设置，末尾仍是产品名。
     *
     * 「名称其实是回落出来的地址」这一种由 `InstanceConfig.serverDisplayName`
     * 在传入前过滤掉，见 `InstanceConfigTest`。
     */
    @Test
    fun `blank server name falls back to product name`() {
        assertEquals(
            "已通过「EasyTier」连接至 QwenPaw",
            connectedLabel("http://a:8088", "EasyTier", "   "),
        )
    }

    /** 地址与别名都缺失，但设过名称时仍要说清连的是哪台。 */
    @Test
    fun `server name alone still renders`() {
        assertEquals("已连接至「家里的机器」", connectedLabel("", "", "家里的机器"))
        assertEquals("已连接", connectedLabel("", "", ""))
    }
}