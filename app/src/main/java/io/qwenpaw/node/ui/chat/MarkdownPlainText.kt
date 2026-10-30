package io.qwenpaw.node.ui.chat

/**
 * 把 Markdown 还原成纯文本。
 *
 * 用途是「复制」按钮：多数人复制消息是为了粘到聊天窗口或记事本里，
 * 带着 `**` 与 ```` ``` ```` 的原文在那些地方读起来更糟。
 * 「复制为 Markdown」按钮保留原文，交给需要保留格式的场景。
 *
 * 这里不做完整解析——只为去掉标记字符，规则覆盖仓库里 Markdown 渲染器
 * 支持的那几种语法即可。围栏代码块的内容原样保留：代码里的 `*` 与 `_`
 * 是语法本身，剥掉会改变含义。
 */
object MarkdownPlainText {

    /** 围栏代码块的起始行：三个及以上反引号或波浪线，允许带语言标注。 */
    private fun isFence(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    /** 表格的分隔行，例如 `|---|:--:|`。整行没有信息量，直接丢弃。 */
    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || !t.contains('-')) return false
        if (!t.startsWith("|") && !t.startsWith(":")) return false
        return t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    /** 分隔线。`---` 与表格分隔行形状接近，但分隔线里没有竖线。 */
    private fun isRule(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        if (t.any { it != '-' && it != '*' && it != '_' }) return false
        // 必须全是同一种字符，否则 `-*-` 这种不应被当作分隔线。
        return t.toSet().size == 1
    }

    /** 行首的标题井号、引用箭头与列表符号。 */
    private val blockPrefixes = listOf(
        Regex("^\\s{0,3}#{1,6}\\s+"),
        Regex("^\\s{0,3}>\\s?"),
        Regex("^\\s*(?:[-*+]|\\d{1,9}[.)])\\s+"),
    )

    /**
     * 行内标记。
     *
     * 顺序有意为之：图片先于链接（否则 `![x](y)` 会被链接规则吃掉前面半截），
     * 三重强调先于双重再先于单重。
     */
    private val inlineRules = listOf(
        Regex("!\\[([^\\]]*)]\\([^)]*\\)") to "$1",
        Regex("\\[([^\\]]+)]\\([^)]*\\)") to "$1",
        Regex("`+([^`]+)`+") to "$1",
        Regex("\\*\\*\\*([^*]+)\\*\\*\\*") to "$1",
        Regex("\\*\\*([^*]+)\\*\\*") to "$1",
        Regex("___([^_]+)___") to "$1",
        Regex("__([^_]+)__") to "$1",
        Regex("\\*([^*\\n]+)\\*") to "$1",
        // 下划线斜体只在词边界生效，否则 `user_id_name` 会被吃掉中间一段。
        Regex("(?<![\\w])_([^_\\n]+)_(?![\\w])") to "$1",
        Regex("~~([^~\\n]+)~~") to "$1",
    )

    /** 单行：先剥块级前缀，再剥行内标记。 */
    private fun stripLine(line: String): String {
        var out = line
        // 前缀可能叠加（引用里套列表），最多剥三层。
        repeat(3) {
            val hit = blockPrefixes.firstOrNull { it.containsMatchIn(out) } ?: return@repeat
            out = hit.replaceFirst(out, "")
        }
        // 表格行：去掉竖线，单元格之间留一个空格，避免两个字粘在一起。
        if (out.trimStart().startsWith("|")) {
            out = out.trim().trim('|').split('|').joinToString("  ") { it.trim() }
        }
        inlineRules.forEach { (pattern, replacement) ->
            out = pattern.replace(out, replacement)
        }
        return out
    }

    /**
     * 主入口。空输入返回空串，调用方据此跳过复制。
     *
     * 保留空行结构：段落之间的空行是 Markdown 里唯一的段落分隔方式，
     * 抹掉之后整段会挤成一坨。
     */
    fun strip(markdown: String): String {
        if (markdown.isBlank()) return ""
        val out = mutableListOf<String>()
        var inFence = false
        // 删掉分隔线 / 表格分隔行之后，它上下紧挨的空行也一并收掉：
        // 只删标记行会留下三连空行，粘到别处就是一片空白。
        var skipBlank = false
        markdown.lineSequence().forEach { line ->
            if (isFence(line)) {
                // 只有围栏行本身被丢掉，代码内容原样输出。
                inFence = !inFence
                return@forEach
            }
            if (inFence) {
                out += line
                return@forEach
            }
            if (isRule(line) || isTableSeparator(line)) {
                while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)
                skipBlank = true
                return@forEach
            }
            if (line.isBlank()) {
                if (skipBlank) {
                    skipBlank = false
                    return@forEach
                }
                out += ""
                return@forEach
            }
            skipBlank = false
            out += stripLine(line).trimEnd()
        }
        return out.joinToString("\n").trim()
    }
}