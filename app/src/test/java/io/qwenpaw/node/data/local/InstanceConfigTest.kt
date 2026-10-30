package io.qwenpaw.node.data.local

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实例配置的地址列表与旧字段迁移。
 *
 * 地址模型改过两次形态：`baseUrl`（单个）→ `baseUrls`（字符串列表）→
 * `addresses`（带别名与网络偏好）。每次升级都必须让老用户的地址自动保留，
 * 否则一次升级就会「实例还在但连不上」。
 */
class InstanceConfigTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy single url migrates into addresses`() {
        val legacy = json.decodeFromString<InstanceConfig>(
            """{"id":"1","name":"家里","agentId":"default","baseUrl":"http://192.168.1.10:8088"}""",
        )
        val migrated = legacy.migrated()
        assertEquals(
            listOf("http://192.168.1.10:8088"),
            migrated.addresses.map { it.url },
        )
        assertNull(migrated.legacyBaseUrl)
        assertTrue(migrated.baseUrls.isEmpty())
    }

    /** 上一版的纯地址列表要转成 AddressEntry，且顺序不变。 */
    @Test
    fun `baseUrls list migrates into addresses`() {
        val old = json.decodeFromString<InstanceConfig>(
            """{"id":"1","name":"n","agentId":"default",
               "baseUrls":["http://new-a:8088","http://new-b:8088"]}""",
        )
        val migrated = old.migrated()
        assertEquals(
            listOf("http://new-a:8088", "http://new-b:8088"),
            migrated.addresses.map { it.url },
        )
        assertTrue(migrated.baseUrls.isEmpty())
    }

    /** 新版列表已存在时，旧字段不能覆盖它。 */
    @Test
    fun `existing addresses are not overwritten by legacy fields`() {
        val config = InstanceConfig(
            id = "1",
            name = "n",
            agentId = "default",
            legacyBaseUrl = "http://old:8088",
            baseUrls = listOf("http://older-a:8088"),
            addresses = listOf(AddressEntry(url = "http://new-a:8088", alias = "本地")),
        )
        val migrated = config.migrated()
        assertEquals(listOf("http://new-a:8088"), migrated.addresses.map { it.url })
        assertEquals("本地", migrated.addresses.first().alias)
        assertNull(migrated.legacyBaseUrl)
        assertTrue(migrated.baseUrls.isEmpty())
    }

    /** 落盘只写 addresses，两个旧字段必须清掉，否则下次读出来又会被当成有效值。 */
    @Test
    fun `serialized json has no legacy fields`() {
        val config = InstanceConfig(
            id = "1",
            name = "n",
            agentId = "default",
            legacyBaseUrl = "http://old:8088",
            baseUrls = listOf("http://new:8088"),
            addresses = listOf(AddressEntry(url = "http://new:8088")),
        )
        val text = json.encodeToString(InstanceConfig.serializer(), config.migrated())
        assertTrue(!text.contains("\"baseUrl\""))
        assertTrue(!text.contains("\"baseUrls\""))
        assertTrue(text.contains("\"addresses\""))
    }

    @Test
    fun `primary url and backup count`() {
        val single = InstanceConfig(
            id = "1",
            name = "n",
            agentId = "d",
            addresses = listOf(AddressEntry(url = "a")),
        )
        assertEquals("a", single.primaryBaseUrl)
        assertEquals(0, single.backupCount)

        val multi = single.copy(
            addresses = listOf(
                AddressEntry(url = "a"),
                AddressEntry(url = "b"),
                AddressEntry(url = "c"),
            ),
        )
        assertEquals("a", multi.primaryBaseUrl)
        assertEquals(2, multi.backupCount)

        // 空列表不该让 primaryBaseUrl 抛异常，调用方靠空串判断配置是否可用。
        assertEquals("", single.copy(addresses = emptyList()).primaryBaseUrl)
    }

    @Test
    fun `migration is idempotent`() {
        val once = InstanceConfig(id = "1", name = "n", agentId = "d", legacyBaseUrl = "a").migrated()
        assertEquals(once, once.migrated())
    }

    // ---------------- 别名与网络偏好 ----------------

    @Test
    fun `label falls back to url when alias blank`() {
        assertEquals("http://a:8088", AddressEntry(url = "http://a:8088").label)
        // 只有空白的别名等于没填，不能让卡片显示一片空白。
        assertEquals("http://a:8088", AddressEntry(url = "http://a:8088", alias = "   ").label)
        assertEquals("EasyTier", AddressEntry(url = "http://a:8088", alias = "EasyTier").label)
    }

    @Test
    fun `labelOf looks up by url`() {
        val config = InstanceConfig(
            id = "1",
            name = "n",
            agentId = "d",
            addresses = listOf(
                AddressEntry(url = "http://a:8088", alias = "本地"),
                AddressEntry(url = "http://b:8088"),
            ),
        )
        assertEquals("本地", config.labelOf("http://a:8088"))
        assertEquals("http://b:8088", config.labelOf("http://b:8088"))
        // 未登记的地址原样返回，不抛异常。
        assertEquals("http://c:8088", config.labelOf("http://c:8088"))
    }

    // ---------------- 顶栏末尾的服务器名 ----------------

    /** 用户起过名字时原样返回，与地址无关。 */
    @Test
    fun `server display name keeps a real name`() {
        val config = InstanceConfig(
            id = "1",
            name = "家里的机器",
            agentId = "d",
            addresses = listOf(AddressEntry(url = "http://a:8088", alias = "本地")),
        )
        assertEquals("家里的机器", config.serverDisplayName)
    }

    /** 名称是空白时返回空串，让顶栏回落到产品名。 */
    @Test
    fun `blank name yields empty display name`() {
        val config = InstanceConfig(id = "1", name = "   ", agentId = "d")
        assertEquals("", config.serverDisplayName)
    }

    /**
     * 名称只是表单留空时回落出来的地址时，必须当成没起名字。
     *
     * 否则顶栏会出现「已通过「EasyTier」连接至「10.0.2.9:8088」」——
     * 同一个地址在句子里出现两次，且实际绑定另一条地址时还会指错地方。
     */
    @Test
    fun `name that is just an address counts as unset`() {
        val config = InstanceConfig(
            id = "1",
            name = "10.0.2.9:8088",
            agentId = "d",
            addresses = listOf(
                AddressEntry(url = "http://10.0.2.9:8088"),
                AddressEntry(url = "http://10.8.0.2:8088", alias = "EasyTier"),
            ),
        )
        assertEquals("", config.serverDisplayName)
    }

    /** 比较时忽略协议前缀与结尾斜杠：回落值存的是用户原文，列表里是规范化结果。 */
    @Test
    fun `address comparison ignores scheme and trailing slash`() {
        val config = InstanceConfig(
            id = "1",
            name = "10.0.2.9:8088",
            agentId = "d",
            addresses = listOf(AddressEntry(url = "http://10.0.2.9:8088/")),
        )
        assertEquals("", config.serverDisplayName)
    }

    /** 名字像地址但不是任何一条已知地址时仍照常显示，不能只按形状猜。 */
    @Test
    fun `name resembling another address is kept`() {
        val config = InstanceConfig(
            id = "1",
            name = "192.168.1.99:8088",
            agentId = "d",
            addresses = listOf(AddressEntry(url = "http://10.0.2.9:8088")),
        )
        assertEquals("192.168.1.99:8088", config.serverDisplayName)
    }

    @Test
    fun `vpn hint matches by kind only`() {
        val hint = NetworkHint(kind = "vpn")
        assertTrue(hint.matches("vpn", null))
        assertTrue(hint.matches("VPN", "Whatever"))
        // 类型不符时即便 SSID 相同也不算命中。
        assertTrue(!hint.matches("wifi", null))
    }

    @Test
    fun `wifi hint matches ssid case insensitively`() {
        val hint = NetworkHint(kind = "wifi", ssid = "MyHome")
        assertTrue(hint.matches("wifi", "myhome"))
        assertTrue(hint.matches("wifi", " MyHome "))
        // 指定了 SSID 就不是「任意 WiFi」。
        assertTrue(!hint.matches("wifi", "OtherNet"))
        // 拿不到 SSID 时不算命中，避免在没授权定位时误选地址。
        assertTrue(!hint.matches("wifi", null))
    }

    /** 不填 SSID 表示「任意 WiFi 都优先」，这是用户手填时最常用的形态。 */
    @Test
    fun `wifi hint without ssid matches any wifi`() {
        val hint = NetworkHint(kind = "wifi")
        assertTrue(hint.matches("wifi", "Any"))
        assertTrue(hint.matches("wifi", null))
        assertTrue(!hint.matches("vpn", "Any"))
    }

    // ---------------- 实例名与地址别名互不影响 ----------------

    /**
     * 实例名与第一条地址的别名可以不同，序列化后各自保留。
     *
     * 上一版把实例名从别名推导，两者的值永远相同，卡片读起来像
     * 「给实例设别名」。分开之后这两个字段必须能独立落盘。
     */
    @Test
    fun `name and alias are independent through serialization`() {
        val config = InstanceConfig(
            id = "1",
            name = "家里的机器",
            agentId = "default",
            addresses = listOf(AddressEntry(url = "http://a:8088", alias = "局域网")),
        )
        val text = json.encodeToString(InstanceConfig.serializer(), config.migrated())
        val back = json.decodeFromString<InstanceConfig>(text)
        assertEquals("家里的机器", back.name)
        assertEquals("局域网", back.addresses.single().alias)
        assertEquals("局域网", back.labelOf("http://a:8088"))
    }

    /** 迁移不能把实例名改成地址别名——那等于静默重命名用户的实例。 */
    @Test
    fun `migration keeps instance name`() {
        val legacy = json.decodeFromString<InstanceConfig>(
            """{"id":"1","name":"家里","agentId":"default","baseUrl":"http://192.168.1.10:8088"}""",
        )
        assertEquals("家里", legacy.migrated().name)
    }

    /** 没有别名的地址，labelOf 返回地址本身，不会回落到实例名。 */
    @Test
    fun `labelOf never falls back to instance name`() {
        val config = InstanceConfig(
            id = "1",
            name = "家里",
            agentId = "default",
            addresses = listOf(AddressEntry(url = "http://a:8088")),
        )
        assertEquals("http://a:8088", config.labelOf("http://a:8088"))
    }
}