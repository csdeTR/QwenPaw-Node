package io.qwenpaw.node.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 服务器地址归一化。 */
class InstanceRepositoryTest {

    @Test
    fun `adds scheme and keeps explicit port`() {
        assertEquals(
            "http://192.168.1.10:8088",
            InstanceRepository.normalizeBaseUrl("192.168.1.10:8088"),
        )
    }

    @Test
    fun `keeps https`() {
        assertEquals(
            "https://paw.example.com:443",
            InstanceRepository.normalizeBaseUrl("https://paw.example.com"),
        )
    }

    @Test
    fun `trims trailing slash and spaces`() {
        assertEquals(
            "http://10.0.2.2:8088",
            InstanceRepository.normalizeBaseUrl("  10.0.2.2:8088/  "),
        )
    }

    // 回归：中文输入法会把点/冒号打成全角，真实设备上出现过。
    @Test
    fun `folds full width punctuation`() {
        assertEquals(
            "http://192.168.1.10:8088",
            InstanceRepository.normalizeBaseUrl("192。168。1。10：8088"),
        )
    }
}
