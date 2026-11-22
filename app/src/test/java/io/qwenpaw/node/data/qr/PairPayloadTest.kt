package io.qwenpaw.node.data.qr

import io.qwenpaw.node.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对码的编解码。
 *
 * 这里的样例串与 `docs/protocol.md` 第 37 节、以及插件 `test_pairing.py` 里的
 * 那条保持一致：三处任意一处改了格式，这里会先红。
 */
class PairPayloadTest {

    @Test
    fun `encode then parse round trips`() {
        val raw = PairQr.encode(
            listOf("http://10.10.0.211:8088", "https://a.example.com:8443"),
            "eyJzdWIiOiJkZW1vIn0.c2ln",
        )
        val parsed = PairQr.parse(raw)
        parsed as PairParse.Ok
        assertEquals(listOf("http://10.10.0.211:8088", "https://a.example.com:8443"), parsed.pair.addresses)
        assertEquals("eyJzdWIiOiJkZW1vIn0.c2ln", parsed.pair.token)
        assertEquals(1, parsed.pair.version)
    }

    @Test
    fun `documented sample parses`() {
        // 协议文档里的那条样例串，字面量写死——不是由 encode 生成的。
        val sample = "qwenpaw-node://pair?v=1" +
            "&addr=http%3A%2F%2F10.10.0.211%3A8088" +
            "&addr=https%3A%2F%2Fa.example.com%3A8443" +
            "&token=eyJzdWIiOiJkZW1vIn0.c2ln"
        val parsed = PairQr.parse(sample)
        parsed as PairParse.Ok
        assertEquals(2, parsed.pair.addresses.size)
        assertEquals("eyJzdWIiOiJkZW1vIn0.c2ln", parsed.pair.token)
    }

    @Test
    fun `address order is preserved`() {
        val raw = PairQr.encode(listOf("http://b:1", "http://a:2", "http://c:3"), "t")
        val parsed = PairQr.parse(raw) as PairParse.Ok
        assertEquals(listOf("http://b:1", "http://a:2", "http://c:3"), parsed.pair.addresses)
    }

    @Test
    fun `reserved characters survive the round trip`() {
        val token = "a+b%c?d&e=f 空格"
        val parsed = PairQr.parse(PairQr.encode(listOf("http://h:1"), token)) as PairParse.Ok
        assertEquals(token, parsed.pair.token)
    }

    @Test
    fun `address with query and port round trips`() {
        val address = "https://example.com:8443/base/path?a=1&b=2"
        val parsed = PairQr.parse(PairQr.encode(listOf(address), "t")) as PairParse.Ok
        assertEquals(listOf(address), parsed.pair.addresses)
    }

    @Test
    fun `wrapping quotes and whitespace are stripped`() {
        val raw = PairQr.encode(listOf("http://h:1"), "tok")
        for (wrapped in listOf("\"$raw\"", "’$raw’", "\n$raw\n", "  $raw  ")) {
            assertTrue("should parse $wrapped", PairQr.parse(wrapped) is PairParse.Ok)
        }
    }

    @Test
    fun `foreign qr codes are not treated as pair payload`() {
        assertEquals(PairParse.NotPairQr, PairQr.parse("https://example.com/whatever"))
        assertEquals(PairParse.NotPairQr, PairQr.parse("hello world"))
    }

    @Test
    fun `newer version is reported as unsupported`() {
        val parsed = PairQr.parse("qwenpaw-node://pair?v=2&addr=http%3A%2F%2Fh%3A1&token=t")
        parsed as PairParse.Unsupported
        assertEquals(2, parsed.version)
    }

    @Test
    fun `missing token is broken`() {
        val parsed = PairQr.parse("qwenpaw-node://pair?v=1&addr=http%3A%2F%2Fh%3A1")
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_no_token, parsed.reasonRes)
    }

    @Test
    fun `missing address is broken`() {
        val parsed = PairQr.parse("qwenpaw-node://pair?v=1&token=t")
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_no_address, parsed.reasonRes)
    }

    @Test
    fun `malformed percent escape is broken`() {
        val parsed = PairQr.parse("qwenpaw-node://pair?v=1&addr=http%3A%2F%2Fh&token=%zz")
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_malformed, parsed.reasonRes)
    }

    @Test
    fun `oversized payload is broken`() {
        val parsed = PairQr.parse("qwenpaw-node://pair?v=1&token=" + "a".repeat(PairQr.MAX_LEN))
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_too_long, parsed.reasonRes)
    }

    @Test
    fun `blank token value is broken`() {
        val parsed = PairQr.parse("qwenpaw-node://pair?v=1&addr=http%3A%2F%2Fh%3A1&token=%20")
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_no_token, parsed.reasonRes)
    }

    @Test
    fun `unknown parameters are ignored`() {
        val parsed = PairQr.parse(
            "qwenpaw-node://pair?v=1&addr=http%3A%2F%2Fh%3A1&token=t&extra=1",
        )
        assertTrue(parsed is PairParse.Ok)
    }

    @Test
    fun `non http scheme is rejected`() {
        val parsed = PairQr.parse(
            "qwenpaw-node://pair?v=1&addr=ftp%3A%2F%2Fh%2F1&token=t",
        )
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_bad_address, parsed.reasonRes)
    }

    @Test
    fun `userinfo is rejected`() {
        val parsed = PairQr.parse(
            "qwenpaw-node://pair?v=1&addr=http%3A%2F%2Fuser%3Apass%40h%3A1&token=t",
        )
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_bad_address, parsed.reasonRes)
    }

    @Test
    fun `fragment is rejected`() {
        val parsed = PairQr.parse(
            "qwenpaw-node://pair?v=1&addr=http%3A%2F%2Fh%3A1%2F%23frag&token=t",
        )
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_bad_address, parsed.reasonRes)
    }

    @Test
    fun `too many addresses are rejected`() {
        val addresses = (0 until PairQr.MAX_ADDRESSES + 1)
            .joinToString("&") { "addr=http%3A%2F%2Fh%3A${it + 1}" }
        val parsed = PairQr.parse("qwenpaw-node://pair?v=1&$addresses&token=t")
        parsed as PairParse.Broken
        assertEquals(R.string.pair_qr_bad_address, parsed.reasonRes)
    }
}