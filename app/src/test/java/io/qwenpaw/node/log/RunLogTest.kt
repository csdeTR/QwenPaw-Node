package io.qwenpaw.node.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 运行日志与导入解析。
 *
 * 日志设施本身不应成为故障源：关闭时零开销、记录时脱敏、导入时宽容。
 * 这三条都在 JVM 里能验证，且都是真实会出问题的点。
 */
class RunLogTest {

    /**
     * 单例在用例之间共享，必须每例复位。
     *
     * 不复位的后果很具体：上一个用例打开日志并写了几条，
     * 下一个「关闭时不应记录」的用例会因为残留条目而误判失败。
     */
    @Before
    fun reset() {
        RunLog.clear()
        RunLog.setKinds(LogKind.entries.toSet())
        RunLog.disable()
    }

    @Test
    fun `disabled log records nothing`() {
        RunLog.disable()
        RunLog.d(LogKind.NETWORK, "T", "不应记录")
        assertTrue(RunLog.entries.value.isEmpty())
    }

    @Test
    fun `enable with clear starts from empty`() {
        RunLog.enable()
        RunLog.d(LogKind.UI, "T", "第一条")
        RunLog.enable(clear = true)
        assertTrue(RunLog.entries.value.isEmpty())
    }

    @Test
    fun `kind filter blocks unselected kinds`() {
        RunLog.setKinds(setOf(LogKind.NETWORK))
        RunLog.enable()
        RunLog.d(LogKind.NETWORK, "T", "网络事件")
        RunLog.d(LogKind.VOICE, "T", "语音事件")
        assertEquals(1, RunLog.entries.value.size)
        assertEquals(LogKind.NETWORK, RunLog.entries.value[0].kind)
    }

    /** 关闭只停止写入，已有条目要留着让用户导出。 */
    @Test
    fun `disable keeps existing entries`() {
        RunLog.enable()
        RunLog.d(LogKind.UI, "T", "已记录")
        RunLog.disable()
        RunLog.d(LogKind.UI, "T", "关闭后")
        assertEquals(1, RunLog.entries.value.size)
    }

    @Test
    fun `error entries keep class name`() {
        RunLog.enable()
        RunLog.e(LogKind.NETWORK, "T", java.net.ConnectException("refused"), "请求失败")
        val entry = RunLog.entries.value.single()
        assertEquals("ConnectException", entry.error)
        assertTrue(entry.message.contains("请求失败"))
        assertTrue(entry.message.contains("refused"))
    }

    // ---------------- 脱敏 ----------------

    @Test
    fun `redacts bearer token`() {
        assertEquals(
            "Authorization: Bearer ***",
            RunLog.redact("Authorization: Bearer abc.def-ghi_123"),
        )
    }

    @Test
    fun `redacts query token`() {
        assertEquals(
            "GET /api/x?token=***&other=1",
            RunLog.redact("GET /api/x?token=secret&other=1"),
        )
        assertEquals(
            "/api/x?access_token=***",
            RunLog.redact("/api/x?access_token=abc123"),
        )
    }

    /** 不脱敏的普通文本必须原样保留，过度替换会毁掉日志的诊断价值。 */
    @Test
    fun `leaves ordinary text untouched`() {
        val text = "调用 /api/chats 返回 200，耗时 42ms"
        assertEquals(text, RunLog.redact(text))
    }

    // ---------------- 导出与导入 ----------------

    @Test
    fun `export and import round trip`() {
        RunLog.enable()
        RunLog.d(LogKind.NETWORK, "API", "GET /api/version 200")
        RunLog.d(LogKind.STREAM, "ChatVM", "收到 12 帧")
        val text = RunLog.export()

        val parsed = LogImporter.parse(text)
        assertEquals(2, parsed.size)
        assertEquals(LogKind.NETWORK, parsed[0].kind)
        assertEquals("API", parsed[0].tag)
        assertEquals("GET /api/version 200", parsed[0].message)
        assertEquals(LogKind.STREAM, parsed[1].kind)
    }

    /**
     * 空导出给一句占位文案。
     *
     * 占位行不是注释、也不是标准格式，导入时会被当作一条普通内容保留下来。
     * 这是有意的：占位文案本身就是给人看的，导入后原样显示比悄悄丢掉更清楚。
     */
    @Test
    fun `empty export yields placeholder that imports as one line`() {
        RunLog.clear()
        val text = RunLog.export()
        assertTrue(text.contains("没有记录日志"))
        val parsed = LogImporter.parse(text)
        assertEquals(1, parsed.size)
        assertEquals(LogKind.UI, parsed.single().kind)
    }

    /** 认不出的行原样保留：用户可能导入被聊天软件截断或手工改过的文件。 */
    @Test
    fun `unknown lines are preserved as ui entries`() {
        val parsed = LogImporter.parse("这是一行没有格式的内容\n")
        assertEquals(1, parsed.size)
        assertEquals(LogKind.UI, parsed.single().kind)
        assertEquals("这是一行没有格式的内容", parsed.single().message)
    }

    @Test
    fun `comment lines are skipped`() {
        val parsed = LogImporter.parse("# QwenPaw Node 运行日志\n# 记录条数：0\n")
        assertTrue(parsed.isEmpty())
    }

    /** 导入是替换而不是追加：用户要看的是别人那份日志，不该和本次记录混在一起。 */
    @Test
    fun `import replaces buffer`() {
        RunLog.enable()
        RunLog.d(LogKind.UI, "T", "本次的记录")
        RunLog.replaceWith(LogImporter.parse("10:00:00.000 [网络] API · 导入的一条\n"))
        val entries = RunLog.entries.value
        assertEquals(1, entries.size)
        assertEquals("导入的一条", entries.single().message)
    }

    /** 超出上限时丢最旧的，长时间运行不会吃满内存。 */
    @Test
    fun `buffer drops oldest beyond limit`() {
        RunLog.enable()
        repeat(RunLog.MAX_ENTRIES + 10) { i ->
            RunLog.d(LogKind.UI, "T", "第 $i 条")
        }
        val entries = RunLog.entries.value
        assertEquals(RunLog.MAX_ENTRIES, entries.size)
        assertEquals("第 10 条", entries.first().message)
    }
}