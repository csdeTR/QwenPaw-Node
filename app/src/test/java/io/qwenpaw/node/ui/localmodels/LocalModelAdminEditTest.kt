package io.qwenpaw.node.ui.localmodels

import io.qwenpaw.node.data.model.LocalModelConfigDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地模型配置的校验与差异计算。
 *
 * 三条边界：上下文不得低于运行时下限、端口在 1–65535、生成参数必须是 JSON 对象。
 * 差异计算只带回改动过的字段；「清空端口」与「不提交端口」必须分开，
 * 前者写 `null`（改回自动端口），后者表示保持原值。
 */
class LocalModelAdminEditTest {

    private fun kwargs(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val original = LocalModelConfigDto(
        maxContextLength = 65_536,
        port = 8_080,
        generateKwargs = kwargs("""{"temperature":0.7,"stop":["<eos>"]}"""),
    )

    // ---------- 校验 ----------

    @Test
    fun `context below the runtime minimum is rejected`() {
        assertEquals(32_768, LocalModelAdminEdit.MIN_CONTEXT_LENGTH)
        assertTrue(
            LocalModelAdminEdit.validate(LocalModelConfigDraft(contextText = "32768")).isEmpty(),
        )
        assertTrue(
            LocalModelAdminEdit.validate(LocalModelConfigDraft(contextText = " 32768 ")).isEmpty(),
        )
        assertEquals(
            listOf(LocalModelConfigError(LocalModelConfigField.CONTEXT, LocalModelConfigIssue.CONTEXT_TOO_SMALL)),
            LocalModelAdminEdit.validate(LocalModelConfigDraft(contextText = "32767")),
        )
        assertEquals(
            listOf(LocalModelConfigError(LocalModelConfigField.CONTEXT, LocalModelConfigIssue.CONTEXT_TOO_SMALL)),
            LocalModelAdminEdit.validate(LocalModelConfigDraft(contextText = "-1")),
        )
        assertEquals(
            listOf(LocalModelConfigError(LocalModelConfigField.CONTEXT, LocalModelConfigIssue.CONTEXT_NUMBER)),
            LocalModelAdminEdit.validate(LocalModelConfigDraft(contextText = "lots")),
        )
        assertTrue(
            LocalModelAdminEdit.validate(LocalModelConfigDraft(contextText = "   ")).isEmpty(),
        )
    }

    @Test
    fun `port must stay inside one to six five five three five`() {
        assertEquals(1, LocalModelAdminEdit.PORT_MIN)
        assertEquals(65_535, LocalModelAdminEdit.PORT_MAX)
        listOf("1", "80", " 65535 ").forEach { text ->
            assertTrue(
                text,
                LocalModelAdminEdit.validate(LocalModelConfigDraft(portText = text)).isEmpty(),
            )
        }
        listOf("0", "65536", "-1").forEach { text ->
            assertEquals(
                text,
                listOf(LocalModelConfigError(LocalModelConfigField.PORT, LocalModelConfigIssue.PORT_RANGE)),
                LocalModelAdminEdit.validate(LocalModelConfigDraft(portText = text)),
            )
        }
        assertEquals(
            listOf(LocalModelConfigError(LocalModelConfigField.PORT, LocalModelConfigIssue.PORT_NUMBER)),
            LocalModelAdminEdit.validate(LocalModelConfigDraft(portText = "80a")),
        )
    }

    @Test
    fun `generate kwargs must be a json object`() {
        assertTrue(LocalModelAdminEdit.validate(LocalModelConfigDraft(kwargsText = "")).isEmpty())
        assertTrue(
            LocalModelAdminEdit.validate(LocalModelConfigDraft(kwargsText = "   ")).isEmpty(),
        )
        assertTrue(
            LocalModelAdminEdit
                .validate(LocalModelConfigDraft(kwargsText = """{"temperature":0.7}"""))
                .isEmpty(),
        )
        assertEquals(
            listOf(LocalModelConfigError(LocalModelConfigField.KWARGS, LocalModelConfigIssue.KWARGS_JSON)),
            LocalModelAdminEdit.validate(LocalModelConfigDraft(kwargsText = "{broken")),
        )
        listOf("[1,2]", "1", "\"text\"", "true").forEach { text ->
            assertEquals(
                text,
                listOf(LocalModelConfigError(LocalModelConfigField.KWARGS, LocalModelConfigIssue.KWARGS_OBJECT)),
                LocalModelAdminEdit.validate(LocalModelConfigDraft(kwargsText = text)),
            )
        }
    }

    @Test
    fun `each bad field reports its own error`() {
        val errors = LocalModelAdminEdit.validate(
            LocalModelConfigDraft(contextText = "1024", portText = "70000", kwargsText = "[1]"),
        )
        assertEquals(3, errors.size)
        assertEquals(
            listOf(
                LocalModelConfigField.CONTEXT,
                LocalModelConfigField.PORT,
                LocalModelConfigField.KWARGS,
            ),
            errors.map { it.field },
        )
    }

    // ---------- 差异计算 ----------

    @Test
    fun `unchanged draft produces no changes`() {
        val draft = LocalModelAdminEdit.from(original)
        assertEquals(LocalModelConfigChanges(), LocalModelAdminEdit.changesOf(draft))
        assertTrue(LocalModelAdminEdit.changesOf(draft).isEmpty)
        assertFalse(draft.isDirty)
    }

    @Test
    fun `only the modified field is sent`() {
        val contextOnly = LocalModelAdminEdit.changesOf(
            LocalModelAdminEdit.from(original).copy(contextText = "131072"),
        )
        assertEquals(131_072, contextOnly.maxContextLength)
        assertNull(contextOnly.port)
        assertFalse(contextOnly.clearPort)
        assertNull(contextOnly.generateKwargs)

        val portOnly = LocalModelAdminEdit.changesOf(
            LocalModelAdminEdit.from(original).copy(portText = "9090"),
        )
        assertNull(portOnly.maxContextLength)
        assertEquals(9_090, portOnly.port)
        assertNull(portOnly.generateKwargs)

        val kwargsOnly = LocalModelAdminEdit.changesOf(
            LocalModelAdminEdit.from(original).copy(kwargsText = """{"temperature":0.9}"""),
        )
        assertNull(kwargsOnly.maxContextLength)
        assertNull(kwargsOnly.port)
        assertEquals(kwargs("""{"temperature":0.9}"""), kwargsOnly.generateKwargs)
    }

    @Test
    fun `clearing the port asks for an explicit null`() {
        val cleared = LocalModelAdminEdit.changesOf(
            LocalModelAdminEdit.from(original).copy(portText = ""),
        )
        assertNull(cleared.port)
        assertTrue(cleared.clearPort)
        assertFalse(cleared.isEmpty)

        val whitespace = LocalModelAdminEdit.changesOf(
            LocalModelAdminEdit.from(original).copy(portText = "   "),
        )
        assertTrue(whitespace.clearPort)

        // 原值本来就是自动端口时，留空不算改动，不能再写一次 null。
        val alreadyAuto = LocalModelAdminEdit.from(
            LocalModelConfigDto(maxContextLength = 65_536, port = null),
        )
        assertEquals(
            LocalModelConfigChanges(),
            LocalModelAdminEdit.changesOf(alreadyAuto.copy(portText = "")),
        )
        assertEquals(
            8_080,
            LocalModelAdminEdit
                .changesOf(alreadyAuto.copy(portText = "8080"))
                .port,
        )
    }

    @Test
    fun `blank kwargs cannot clear existing generate kwargs`() {
        // null 在序列化配置里被省略，清空与不填等价；界面要写明这一点。
        val draft = LocalModelAdminEdit.from(original).copy(kwargsText = "")
        val changes = LocalModelAdminEdit.changesOf(draft)
        assertTrue(changes.isEmpty)
        assertNull(changes.generateKwargs)
        assertFalse(draft.isDirty)
    }

    @Test
    fun `invalid text counts as dirty but not as a change`() {
        val context = LocalModelAdminEdit.from(original).copy(contextText = "lots")
        assertTrue(LocalModelAdminEdit.changesOf(context).isEmpty)
        assertTrue(context.isDirty)

        val port = LocalModelAdminEdit.from(original).copy(portText = "80a")
        assertTrue(LocalModelAdminEdit.changesOf(port).isEmpty)
        assertTrue(port.isDirty)

        val kwargsDraft = LocalModelAdminEdit.from(original).copy(kwargsText = "{broken")
        assertTrue(LocalModelAdminEdit.changesOf(kwargsDraft).isEmpty)
        assertTrue(kwargsDraft.isDirty)
    }

    @Test
    fun `formatting differences are not changes`() {
        val draft = LocalModelAdminEdit.from(original).copy(
            contextText = " 65536 ",
            portText = " 8080 ",
            kwargsText = """ { "temperature" : 0.7 , "stop" : [ "<eos>" ] } """,
        )
        assertTrue(LocalModelAdminEdit.changesOf(draft).isEmpty)
        assertFalse(draft.isDirty)
    }

    @Test
    fun `new config sends every filled field`() {
        val draft = LocalModelConfigDraft(
            contextText = "131072",
            portText = "9000",
            kwargsText = """{"temperature":0.5}""",
            original = null,
        )
        val changes = LocalModelAdminEdit.changesOf(draft)
        assertEquals(131_072, changes.maxContextLength)
        assertEquals(9_000, changes.port)
        assertFalse(changes.clearPort)
        assertEquals(kwargs("""{"temperature":0.5}"""), changes.generateKwargs)
        assertFalse(changes.isEmpty)

        assertTrue(LocalModelAdminEdit.changesOf(LocalModelConfigDraft()).isEmpty)
    }

    @Test
    fun `draft from server config formats every field`() {
        val draft = LocalModelAdminEdit.from(original)
        assertEquals("65536", draft.contextText)
        assertEquals("8080", draft.portText)
        assertEquals(original.generateKwargs, LocalModelAdminEdit.parseKwargs(draft.kwargsText))
        assertEquals(original, draft.original)

        val empty = LocalModelAdminEdit.from(LocalModelConfigDto())
        assertEquals("", empty.contextText)
        assertEquals("", empty.portText)
        assertEquals("", empty.kwargsText)

        // 空对象按「没有生成参数」处理，高级项里不出现一对空花括号。
        val emptyObject = LocalModelAdminEdit.from(
            LocalModelConfigDto(generateKwargs = JsonObject(emptyMap())),
        )
        assertEquals("", emptyObject.kwargsText)
    }

    @Test
    fun `numeric fields are parsed with surrounding spaces`() {
        assertEquals(65_536, LocalModelAdminEdit.parseContext(" 65536 "))
        assertEquals(8_080, LocalModelAdminEdit.parsePort(" 8080 "))
        assertNull(LocalModelAdminEdit.parseContext("abc"))
        assertNull(LocalModelAdminEdit.parsePort(""))
        assertEquals("2", LocalModelAdminEdit.parseKwargs(""" {"a":2} """)?.get("a")?.jsonPrimitive?.content)
    }
}
