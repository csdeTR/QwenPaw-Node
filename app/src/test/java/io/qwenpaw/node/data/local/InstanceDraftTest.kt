package io.qwenpaw.node.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 实例显示名与地址别名的分离。
 *
 * 这一轮的根因是：实例名此前从第一个地址的别名推导，导致设置卡片
 * 第一行（实例名）与第二行（地址别名）显示同一个字符串，读起来像是
 * 「给实例设别名」。这里验证两者的取值互不影响。
 */
class InstanceDraftTest {

    @Test
    fun `explicit name wins`() {
        val draft = InstanceDraft(
            addresses = listOf(AddressEntry(url = "http://a:8088", alias = "局域网")),
            instanceName = "家里的机器",
        )
        assertEquals("家里的机器", draft.resolvedName())
    }

    /** 名称留空时回落到第一个地址，而不是第一个别名。 */
    @Test
    fun `blank name falls back to first url not alias`() {
        val draft = InstanceDraft(
            addresses = listOf(AddressEntry(url = "http://a:8088", alias = "局域网")),
            instanceName = "",
        )
        assertEquals("http://a:8088", draft.resolvedName())
    }

    /** 只有空格的名称等同于没填，不能让卡片标题变成一片空白。 */
    @Test
    fun `whitespace only name is treated as blank`() {
        val draft = InstanceDraft(
            addresses = listOf(AddressEntry(url = "http://a:8088")),
            instanceName = "   ",
        )
        assertEquals("http://a:8088", draft.resolvedName())
    }

    /** 首个地址是空行时，取第一条真正有地址的那条。 */
    @Test
    fun `falls back to first non blank address`() {
        val draft = InstanceDraft(
            addresses = listOf(
                AddressEntry(url = ""),
                AddressEntry(url = "http://b:8088", alias = "备用"),
            ),
        )
        assertEquals("http://b:8088", draft.resolvedName())
    }

    /** 完全没有地址时也不能返回空串，否则实例卡片没有标题。 */
    @Test
    fun `no address yields a readable fallback`() {
        assertEquals("本地实例", InstanceDraft(addresses = emptyList()).resolvedName())
        assertEquals("本地实例", InstanceDraft(addresses = listOf(AddressEntry(url = ""))).resolvedName())
    }

    /** 名称两端空白要去掉：用户复制粘贴时常带上空格。 */
    @Test
    fun `name is trimmed`() {
        val draft = InstanceDraft(
            addresses = listOf(AddressEntry(url = "http://a:8088")),
            instanceName = "  家里  ",
        )
        assertEquals("家里", draft.resolvedName())
    }

    /** 别名与名称是两件事：改别名不影响名称。 */
    @Test
    fun `alias does not affect resolved name`() {
        val withAlias = InstanceDraft(
            addresses = listOf(AddressEntry(url = "http://a:8088", alias = "EasyTier")),
            instanceName = "公司",
        )
        assertEquals("公司", withAlias.resolvedName())
        val renamed = withAlias.copy(
            addresses = listOf(AddressEntry(url = "http://a:8088", alias = "局域网")),
        )
        assertEquals("公司", renamed.resolvedName())
    }

    @Test
    fun `defaults are usable`() {
        val draft = InstanceDraft(addresses = listOf(AddressEntry(url = "http://a:8088")))
        assertEquals("default", draft.agentId)
        assertEquals("", draft.username)
        assertEquals("", draft.password)
    }
}