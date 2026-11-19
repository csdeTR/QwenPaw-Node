package io.qwenpaw.node.data

import io.qwenpaw.node.TestTexts
import io.qwenpaw.node.data.remote.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ConnectException

/** 并行验证的地址分类、超时档位与失败汇总。 */
class AddressProbePlanTest {

    @Before
    fun setUp() {
        TestTexts.install()
    }

    @Test
    fun `classifies private and public hosts`() {
        assertEquals(AddressKind.LAN, AddressProbePlan.kindOf("http://192.168.1.10:8088"))
        assertEquals(AddressKind.LAN, AddressProbePlan.kindOf("http://10.0.2.2:8088"))
        assertEquals(AddressKind.LAN, AddressProbePlan.kindOf("http://172.16.0.9:8088"))
        assertEquals(AddressKind.LAN, AddressProbePlan.kindOf("http://127.0.0.1:8088"))
        assertEquals(AddressKind.LAN, AddressProbePlan.kindOf("http://localhost:8088"))
        assertEquals(AddressKind.LAN, AddressProbePlan.kindOf("http://nas.local:8088"))
        assertEquals(AddressKind.LAN, AddressProbePlan.kindOf("http://[::1]:8088"))
        assertEquals(AddressKind.WAN, AddressProbePlan.kindOf("http://172.32.0.9:8088"))
        assertEquals(AddressKind.WAN, AddressProbePlan.kindOf("https://example.com"))
        assertEquals(AddressKind.WAN, AddressProbePlan.kindOf("8.8.8.8:8088"))
    }

    @Test
    fun `lan policy is shorter than wan`() {
        assertEquals(AddressProbePlan.LAN, AddressProbePlan.policyOf("192.168.1.2:8088"))
        assertEquals(AddressProbePlan.WAN, AddressProbePlan.policyOf("qwenpaw.example.com:8088"))
        assertTrue(AddressProbePlan.LAN.callMillis < AddressProbePlan.WAN.callMillis)
    }

    @Test
    fun `parallel returns first success`() = runBlocking {
        val result = AddressFallback.firstReachableParallel(
            listOf("a", "b", "c"),
            staggerMillis = 0,
            totalBudgetMillis = 2_000,
        ) { url ->
            if (url == "c") {
                "ok"
            } else {
                delay(200)
                throw ConnectException("Failed to connect")
            }
        }
        assertEquals("c", result.first)
        assertEquals("ok", result.second)
    }

    @Test
    fun `parallel stops on business error`() = runBlocking {
        val business = ApiException(401, "unauthorized")
        try {
            AddressFallback.firstReachableParallel(
                listOf("a", "b"),
                staggerMillis = 0,
                totalBudgetMillis = 2_000,
            ) { url ->
                if (url == "a") throw business
                delay(300)
                "ok"
            }
            fail("business error must win")
        } catch (e: ApiException) {
            assertSame(business, e)
        }
    }

    @Test
    fun `parallel aggregates every connection failure`() = runBlocking {
        try {
            AddressFallback.firstReachableParallel(
                listOf("a", "b", "c"),
                staggerMillis = 0,
                totalBudgetMillis = 2_000,
            ) { throw ConnectException("Failed to connect") }
            fail("all addresses failed")
        } catch (e: MultiAddressFailure) {
            assertEquals(setOf("a", "b", "c"), e.failures.map { it.url }.toSet())
        }
    }

    @Test
    fun `budget turns pending addresses into timeout failures`() = runBlocking {
        try {
            AddressFallback.firstReachableParallel(
                listOf("a"),
                staggerMillis = 0,
                totalBudgetMillis = 100,
            ) {
                delay(500)
                "late"
            }
            fail("budget should expire")
        } catch (e: MultiAddressFailure) {
            assertEquals(1, e.failures.size)
            assertTrue(e.failures.single().cause is java.net.SocketTimeoutException)
        }
    }

    @Test
    fun `onAttempt reports each started address`() = runBlocking {
        val seen = mutableListOf<String>()
        AddressFallback.firstReachableParallel(
            listOf("a", "b"),
            staggerMillis = 0,
            totalBudgetMillis = 2_000,
            onAttempt = { _, _, url -> synchronized(seen) { seen += url } },
        ) { url ->
            if (url == "a") {
                delay(50)
                "ok"
            } else {
                delay(200)
                "late"
            }
        }
        assertEquals(listOf("a", "b"), seen.sorted())
    }
}
