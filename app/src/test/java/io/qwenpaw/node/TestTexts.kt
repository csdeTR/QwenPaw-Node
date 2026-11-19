package io.qwenpaw.node

import io.qwenpaw.node.ui.i18n.Texts
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 单测用的文案解析器。
 *
 * JVM 单测没有 Android 资源环境。这里直接读取 `src/main/res/values` 下的 XML，
 * 借助反射把 R.string 的数字 ID 映射回资源名，再交给 [Texts] 的测试入口。
 * 这样断言仍然可以比对真实的中文文案，不必在测试里复制一份。
 */
object TestTexts {

    private var installed = false

    fun install() {
        if (installed) return
        val valuesDir = File("src/main/res/values").takeIf { it.isDirectory }
            ?: File("app/src/main/res/values")

        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, MutableMap<String, String>>()
        valuesDir.listFiles { file -> file.name.endsWith(".xml") }?.forEach { file ->
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file)
            val elements = document.documentElement.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as? Element ?: continue
                when (element.tagName) {
                    "string" -> {
                        val name = element.getAttribute("name")
                        if (name.isNotEmpty()) strings[name] = unescape(element.textContent)
                    }

                    "plurals" -> {
                        val name = element.getAttribute("name")
                        if (name.isBlank()) continue
                        val items = element.getElementsByTagName("item")
                        val byQuantity = mutableMapOf<String, String>()
                        for (itemIndex in 0 until items.length) {
                            val item = items.item(itemIndex) as? Element ?: continue
                            byQuantity[item.getAttribute("quantity")] = unescape(item.textContent)
                        }
                        plurals[name] = byQuantity
                    }
                }
            }
        }

        val idToName = mutableMapOf<Int, String>()
        R.string::class.java.declaredFields.forEach { field ->
            if (field.type == Int::class.javaPrimitiveType) {
                runCatching { idToName[field.getInt(null)] = field.name }
            }
        }
        val pluralIdToName = mutableMapOf<Int, String>()
        R.plurals::class.java.declaredFields.forEach { field ->
            if (field.type == Int::class.javaPrimitiveType) {
                runCatching { pluralIdToName[field.getInt(null)] = field.name }
            }
        }

        Texts.installResolver { id, args ->
            val name = idToName[id] ?: error("unknown string id $id")
            val raw = strings[name] ?: error("missing string resource $name")
            if (args.isEmpty()) raw else String.format(Locale.CHINA, raw, *args)
        }
        Texts.installQuantityResolver { id, count, args ->
            val name = pluralIdToName[id] ?: error("unknown plurals id $id")
            val items = plurals[name] ?: error("missing plurals resource $name")
            val raw = items["other"] ?: items.values.firstOrNull()
                ?: error("plurals $name has no item")
            if (args.isEmpty()) {
                String.format(Locale.CHINA, raw, count)
            } else {
                String.format(Locale.CHINA, raw, *args)
            }
        }
        installed = true
    }

    /** Android 资源在打包时会解释 `\n` 等转义；测试直接读 XML，需要补上这一步。 */
    private fun unescape(raw: String): String = buildString {
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c != '\\' || index + 1 >= raw.length) {
                append(c)
                index++
                continue
            }
            when (val next = raw[index + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                '\'' -> append('\'')
                '"' -> append('"')
                '\\' -> append('\\')
                else -> append(c).append(next)
            }
            index += 2
        }
    }
}
