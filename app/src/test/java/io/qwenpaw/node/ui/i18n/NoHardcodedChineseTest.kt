package io.qwenpaw.node.ui.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码中不得再出现中文字符串字面量。
 *
 * 注释、字符字面量与测试目录不在检查范围内；只扫描 `app/src/main/java` 的 Kotlin 源码。
 * 文案一律走 `res/values` 下的 XML，新增语言时才能整体替换。
 */
class NoHardcodedChineseTest {

    @Test
    fun `no chinese string literals in main sources`() {
        val root = File("src/main/java").takeIf { it.isDirectory }
            ?: File("app/src/main/java")
        assertTrue("找不到源码目录：${root.absolutePath}", root.isDirectory)

        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file -> violations += scan(file) }

        assertTrue(
            "以下位置的字符串字面量仍含中文，应迁入 res/values：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun scan(file: File): List<String> {
        val text = file.readText()
        val violations = mutableListOf<String>()
        var line = 1
        var index = 0
        var mode = Mode.CODE
        var blockDepth = 0
        var literalLine = 0
        val literal = StringBuilder()

        fun flush() {
            if (literal.any { isCjk(it) }) {
                violations += "${file.path}:$literalLine -> ${literal.take(60)}"
            }
            literal.clear()
        }

        while (index < text.length) {
            val c = text[index]
            when (mode) {
                Mode.CODE -> when {
                    c == '\n' -> { line++; index++ }
                    c == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                        mode = Mode.LINE_COMMENT
                        index += 2
                    }
                    c == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                        mode = Mode.BLOCK_COMMENT
                        blockDepth = 1
                        index += 2
                    }
                    c == '"' && text.startsWith("\"\"\"", index) -> {
                        mode = Mode.RAW_STRING
                        literal.clear()
                        literalLine = line
                        index += 3
                    }
                    c == '"' -> {
                        mode = Mode.STRING
                        literal.clear()
                        literalLine = line
                        index++
                    }
                    c == '\'' -> {
                        mode = Mode.CHAR
                        index++
                    }
                    else -> index++
                }

                Mode.LINE_COMMENT -> when (c) {
                    '\n' -> { mode = Mode.CODE; line++; index++ }
                    else -> index++
                }

                Mode.BLOCK_COMMENT -> when {
                    c == '\n' -> { line++; index++ }
                    text.startsWith("/*", index) -> { blockDepth++; index += 2 }
                    text.startsWith("*/", index) -> {
                        blockDepth--
                        index += 2
                        if (blockDepth == 0) mode = Mode.CODE
                    }
                    else -> index++
                }

                Mode.STRING -> when (c) {
                    '\\' -> index += 2
                    '"' -> { flush(); mode = Mode.CODE; index++ }
                    '\n' -> { line++; literal.append(c); index++ }
                    else -> { literal.append(c); index++ }
                }

                Mode.RAW_STRING -> when {
                    text.startsWith("\"\"\"", index) -> {
                        flush()
                        mode = Mode.CODE
                        index += 3
                    }
                    c == '\n' -> { line++; literal.append(c); index++ }
                    else -> { literal.append(c); index++ }
                }

                Mode.CHAR -> when (c) {
                    '\\' -> index += 2
                    '\'' -> { mode = Mode.CODE; index++ }
                    '\n' -> { line++; index++ }
                    else -> index++
                }
            }
        }
        return violations
    }

    private enum class Mode { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, RAW_STRING, CHAR }

    private fun isCjk(c: Char): Boolean =
        c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf'
}
