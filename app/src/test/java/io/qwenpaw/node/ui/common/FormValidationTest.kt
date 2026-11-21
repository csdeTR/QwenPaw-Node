package io.qwenpaw.node.ui.common

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormValidationTest {

    @Test
    fun `secret draft keeps original when untouched`() {
        val draft = SecretDraft(original = "sk-old")

        assertTrue(draft.hasOriginal)
        assertFalse(draft.changed)
        assertNull(secretWireValue(draft))
        assertEquals("sk-old", secretMergeValue(draft))
    }

    @Test
    fun `secret draft replacement only counts with non blank value`() {
        val opened = SecretDraft(original = "sk-old", replacing = true)
        assertFalse(opened.changed)
        assertNull(secretWireValue(opened))

        val typed = opened.copy(replacement = "sk-new")
        assertTrue(typed.changed)
        assertEquals("sk-new", secretWireValue(typed))
        assertEquals("sk-new", secretMergeValue(typed))
    }

    @Test
    fun `secret draft clearing sends empty string`() {
        val draft = SecretDraft(original = "sk-old", clearing = true)

        assertTrue(draft.changed)
        assertEquals("", secretWireValue(draft))
        assertEquals("", secretMergeValue(draft))
    }

    @Test
    fun `tags split on both commas and newlines`() {
        assertEquals(
            listOf("a", "b", "c", "d"),
            normalizeTags("a, b，c\nd\na"),
        )
        assertEquals(emptyList<String>(), normalizeTags(" , ， \n "))
    }

    @Test
    fun `json object parse accepts blank as empty and rejects invalid`() {
        assertEquals(0, parseJsonObjectOrNull("   ")!!.size)
        assertEquals(
            "1",
            parseJsonObjectOrNull("""{"a":1}""")!!["a"]!!.jsonPrimitiveContent(),
        )
        assertNull(parseJsonObjectOrNull("{bad"))
        assertNull(parseJsonObjectOrNull("[1,2]"))
    }

    @Test
    fun `format json object produces pretty text that parses back`() {
        val value = parseJsonObjectOrNull("""{"a":1,"b":{"c":true}}""")!!
        val text = formatJsonObject(value)

        assertTrue(text.contains("\n"))
        assertEquals(value, parseJsonObjectOrNull(text))
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
        (this as JsonPrimitive).content
}
