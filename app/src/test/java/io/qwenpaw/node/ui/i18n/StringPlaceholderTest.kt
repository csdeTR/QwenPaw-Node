package io.qwenpaw.node.ui.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 字符串资源里的占位符必须带位置（`%1$s` / `%1$d`）。
 *
 * 无位置占位符在语序调整后会错位；这条用例把问题挡在构建期。
 * 确实包含字面 `%` 的条目应写 `%%`，或显式标 `formatted="false"`。
 */
class StringPlaceholderTest {

    @Test
    fun `all placeholders are positional`() {
        val valuesDir = File("src/main/res/values").takeIf { it.isDirectory }
            ?: File("app/src/main/res/values")
        assertTrue("找不到资源目录：${valuesDir.absolutePath}", valuesDir.isDirectory)

        val violations = mutableListOf<String>()
        valuesDir.listFiles { file -> file.name.endsWith(".xml") }?.forEach { file ->
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file)
            val elements = document.documentElement.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as? Element ?: continue
                if (element.tagName != "string" && element.tagName != "item") continue
                if (element.getAttribute("formatted") == "false") continue
                val name = element.getAttribute("name")
                checkFormat(element.textContent)?.let {
                    violations += "${file.name} $name -> $it"
                }
            }
        }

        assertTrue(
            "以下资源使用了无位置占位符：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** 返回第一个违规占位符；没有返回 null。 */
    private fun checkFormat(text: String): String? {
        var index = 0
        while (index < text.length) {
            if (text[index] != '%') {
                index++
                continue
            }
            if (index + 1 < text.length && text[index + 1] == '%') {
                index += 2
                continue
            }
            val rest = text.substring(index + 1)
            val positional = Regex("^\\d+\\$[a-zA-Z]").find(rest)
            if (positional == null) return rest.take(4)
            index += 1 + positional.value.length
        }
        return null
    }
}
