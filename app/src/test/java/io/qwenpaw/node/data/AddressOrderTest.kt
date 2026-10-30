package io.qwenpaw.node.data

import io.qwenpaw.node.data.local.AddressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 地址列表的规范化与地址顺序解析。
 *
 * 这两处是「备用地址不生效」的症结所在：
 * 规范化决定列表里到底存了哪几条，顺序解析决定请求时先试哪一条。
 */
class AddressOrderTest {

    /** 单测里用恒等函数代替 `QwenPawApi.resolve`，避免依赖 OkHttp 的地址解析。 */
    private fun normalize(entries: List<AddressEntry>) =
        InstanceRepository.normalizeEntriesPure(entries) { it }

    // ---------------- 规范化 ----------------

    @Test
    fun `drops blank urls and trims alias`() {
        val result = normalize(
            listOf(
                AddressEntry(url = "  a:8088  ", alias = "  本地 "),
                AddressEntry(url = "   "),
                AddressEntry(url = "b:8088"),
            ),
        )
        assertEquals(listOf("a:8088", "b:8088"), result.map { it.url })
        assertEquals("本地", result[0].alias)
    }

    /** 空白别名等同于没填，存成 null 才能让界面回落到地址本身。 */
    @Test
    fun `blank alias becomes null`() {
        val result = normalize(listOf(AddressEntry(url = "a:8088", alias = "   ")))
        assertEquals(null, result.single().alias)
    }

    /**
     * 同一地址写两遍只保留第一条。
     *
     * 用户从别处复制粘贴时很容易重复；保留后者会把先写好的别名覆盖掉。
     */
    @Test
    fun `duplicates keep the first occurrence`() {
        val result = normalize(
            listOf(
                AddressEntry(url = "a:8088", alias = "本地"),
                AddressEntry(url = "a:8088", alias = "覆盖"),
                AddressEntry(url = "b:8088"),
            ),
        )
        assertEquals(2, result.size)
        assertEquals("本地", result[0].alias)
    }

    @Test
    fun `empty list is rejected`() {
        try {
            normalize(listOf(AddressEntry(url = ""), AddressEntry(url = "  ")))
            fail("应当抛出异常")
        } catch (e: IllegalArgumentException) {
            assertEquals("请填写服务器地址", e.message)
        }
    }

    /** 网络偏好必须原样保留，不能被规范化流程吃掉。 */
    @Test
    fun `network hint survives normalization`() {
        val hint = io.qwenpaw.node.data.local.NetworkHint(kind = "vpn")
        val result = normalize(listOf(AddressEntry(url = "a:8088", preferOn = hint)))
        assertEquals(hint, result.single().preferOn)
    }

    // ---------------- 顺序解析 ----------------

    /**
     * 解析顺序的四级优先：网络命中 > 手动指定 > 运行期命中 > 原始顺序。
     *
     * 这里只验证「原始顺序」这一档，其余三档需要 NetworkMonitor 与实例存储，
     * 属于设备行为的范畴，留给实测。
     */
    @Test
    fun `primary url is first address`() {
        val config = io.qwenpaw.node.data.local.InstanceConfig(
            id = "1",
            name = "n",
            agentId = "d",
            addresses = listOf(
                AddressEntry(url = "a:8088"),
                AddressEntry(url = "b:8088"),
            ),
        )
        assertEquals("a:8088", config.primaryBaseUrl)
        assertEquals(1, config.backupCount)
    }

    /** 带别名的地址在卡片上应当显示别名，而不是一串 IP。 */
    @Test
    fun `instance label prefers alias`() {
        val config = io.qwenpaw.node.data.local.InstanceConfig(
            id = "1",
            name = "n",
            agentId = "d",
            addresses = listOf(AddressEntry(url = "10.8.0.2:8088", alias = "EasyTier")),
        )
        assertEquals("EasyTier", config.labelOf(config.primaryBaseUrl))
    }

    /** 规范化后的地址用于落盘，别名字段不该被 URL 补协议过程改动。 */
    @Test
    fun `alias is preserved when url gets normalized`() {
        val result = InstanceRepository.normalizeEntriesPure(
            listOf(AddressEntry(url = "10.8.0.2:8088", alias = "EasyTier")),
        ) { raw ->
            if (raw.startsWith("http")) raw else "http://$raw"
        }
        assertEquals("http://10.8.0.2:8088", result.single().url)
        assertEquals("EasyTier", result.single().alias)
    }

    /** 两个不同写法归一后相同的地址只留一条。 */
    @Test
    fun `distinct urls that normalize equal are deduped`() {
        val result = InstanceRepository.normalizeEntriesPure(
            listOf(
                AddressEntry(url = "a:8088", alias = "第一条"),
                AddressEntry(url = "http://a:8088"),
            ),
        ) { raw -> if (raw.startsWith("http")) raw else "http://$raw" }
        assertEquals(1, result.size)
        assertEquals("第一条", result.single().alias)
    }

    @Test
    fun `order is preserved`() {
        val result = normalize(
            listOf(
                AddressEntry(url = "c:8088"),
                AddressEntry(url = "a:8088"),
                AddressEntry(url = "b:8088"),
            ),
        )
        assertEquals(listOf("c:8088", "a:8088", "b:8088"), result.map { it.url })
        assertTrue(result.size == 3)
    }

    // ---------------- 五级优先级（纯函数） ----------------

    private val all = listOf("a:8088", "b:8088", "c:8088")

    /**
     * 本次手动指定必须排第一。
     *
     * 用户点了「本次使用这条」，结果请求仍走别的地址——这正是这一轮报上来的
     * 「手动切换连接地址不生效」的形态。
     */
    @Test
    fun `session pick comes first`() {
        val order = InstanceRepository.resolveOrderPure(
            addresses = all,
            sessionPick = "c:8088",
            preferUrl = "b:8088",
            preferredBaseUrl = "a:8088",
        )
        assertEquals(listOf("c:8088", "b:8088", "a:8088"), order)
    }

    /** 其余各档按声明顺序排列：网络命中 > 长期偏好 > 运行期命中。 */
    @Test
    fun `preference tiers follow declaration order`() {
        val order = InstanceRepository.resolveOrderPure(
            addresses = all,
            preferUrl = "b:8088",
            preferredBaseUrl = "c:8088",
            runtimeHit = "a:8088",
        )
        assertEquals(listOf("b:8088", "c:8088", "a:8088"), order)
    }

    /** 手动指定指向已删除的地址时被忽略，不能把整条顺序打乱或报错。 */
    @Test
    fun `stale session pick is ignored`() {
        val order = InstanceRepository.resolveOrderPure(
            addresses = all,
            sessionPick = "gone:8088",
        )
        assertEquals(all, order)
    }

    /** 空串与只有空白的指定同样视为「未指定」。 */
    @Test
    fun `blank session pick is ignored`() {
        assertEquals(all, InstanceRepository.resolveOrderPure(addresses = all, sessionPick = ""))
        assertEquals(all, InstanceRepository.resolveOrderPure(addresses = all, sessionPick = "   "))
    }

    /** 提优先不排他：所有地址都保留在后面，坏了一条还能回退。 */
    @Test
    fun `preferred addresses are not exclusive`() {
        val order = InstanceRepository.resolveOrderPure(
            addresses = all,
            sessionPick = "c:8088",
        )
        assertEquals(3, order.size)
        assertEquals("c:8088", order.first())
        assertEquals(all.toSet(), order.toSet())
    }

    /** 同一个地址同时命中多档时只出现一次，不会产生重复候选。 */
    @Test
    fun `duplicate preferences collapse`() {
        val order = InstanceRepository.resolveOrderPure(
            addresses = all,
            sessionPick = "b:8088",
            preferUrl = "b:8088",
            preferredBaseUrl = "b:8088",
        )
        assertEquals(listOf("b:8088", "a:8088", "c:8088"), order)
        assertEquals(3, order.size)
    }

    @Test
    fun `blank addresses yield empty order`() {
        assertTrue(
            InstanceRepository.resolveOrderPure(
                addresses = listOf("", "  "),
                sessionPick = "a:8088",
            ).isEmpty(),
        )
    }
}