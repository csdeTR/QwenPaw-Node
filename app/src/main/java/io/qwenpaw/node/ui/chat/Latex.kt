package io.qwenpaw.node.ui.chat

/**
 * 公式节点。
 *
 * 解析层只描述结构，不关心字号与尺寸；排版交给 `LatexView`。
 * 之所以自建而不是引入现成库：`jlatexmath-android` 是 GPL-2.0，
 * 与本项目的 MIT 许可冲突，会传染整个 APK 的分发条款；
 * WebView + KaTeX 则要额外打包几百 KB 的 JS，且流式刷新时每帧都要重建 WebView。
 *
 * 支持的子集是**明确列举的**，不是完整 TeX：见 `LatexParser` 的命令表。
 * 没登记的命令按字面输出而不是让整条公式失败：模型给出的公式里常夹带
 * `\mathbb`、`\vec`、`\tag` 这类本渲染器没有排版能力的写法，
 * 若一处不认就整条退回源码，实机上几乎什么都渲染不出来。
 * 只有结构本身无法解释时（花括号不配平、`\left` 缺 `\right`、`\begin` 缺 `\end`、
 * 嵌套过深）才返回 null，此时源码比一个残缺的公式更容易判断问题。
 */
sealed interface MathNode {
    /** 水平排列。 */
    data class Row(val children: List<MathNode>) : MathNode

    /** 单个字符或转义后的符号，例如 `π`、`≤`、`{`。 */
    data class Sym(val text: String) : MathNode

    /** 分式，`\frac{a}{b}`。 */
    data class Fraction(val numerator: MathNode, val denominator: MathNode) : MathNode

    /** 根式，`\sqrt[n]{x}`；[index] 为 null 表示平方根。 */
    data class Radical(val index: MathNode?, val body: MathNode) : MathNode

    /** 上下标，`x^2`、`a_i`、`x_i^2`。 */
    data class Script(
        val base: MathNode,
        val superscript: MathNode?,
        val subscript: MathNode?,
    ) : MathNode

    /** 大运算符（求和、积分、极限），上下限分别排在符号的上方与下方。 */
    data class BigOp(
        val op: String,
        val subscript: MathNode?,
        val superscript: MathNode?,
    ) : MathNode

    /** `\left( … \right)`。 */
    data class Delim(val left: String, val body: MathNode, val right: String) : MathNode

    /** `\text{}` / `\mathrm{}` / `\operatorname{}` 里的正体文字，空格保留。 */
    data class Text(val text: String) : MathNode

    /**
     * 多行环境，`\begin{cases}`、`\begin{aligned}` 与各种矩阵。
     *
     * [rows] 是「行 → 列」，`&` 分列、`\\` 分行。行数不定，因此不能压成
     * [Row] / [Script] 这类水平或上下两段的组合，需要渲染层单独排版。
     */
    data class Env(val kind: EnvKind, val rows: List<List<MathNode>>) : MathNode

    /**
     * 重音符号，`\vec{v}`、`\hat{y}`、`\overline{AB}`。
     *
     * [mark] 是画在 [body] 上方的字形本身，而不是字符的组合方法：
     * Unicode 的组合附加符在手机的西文字体里普遍缺字形，直接排一个小字更好读。
     */
    data class Accent(val body: MathNode, val mark: String) : MathNode
}

/** 多行环境的种类，决定渲染时两侧加什么定界符。 */
enum class EnvKind { CASES, ALIGNED, MATRIX, PMATRIX, BMATRIX, VMATRIX, ARRAY }

/**
 * 轻量 LaTeX 解析器。
 *
 * 只做一件事：把受支持子集解析成 [MathNode]。未知命令按字面 [MathNode.Sym] 输出，
 * 其余内容照常解析；只有结构无法解释（括号不配平、`\left` 缺 `\right`、
 * `\begin` 缺 `\end`、嵌套过深）时**整体返回 null**，由调用方退回源码。
 */
object LatexParser {

    /** 嵌套上限。超过这个深度的公式在手机上的排版也已无法阅读。 */
    private const val MAX_DEPTH = 8

    /** 解析失败返回 null，调用方应展示原始 TeX 源码。 */
    fun parse(source: String): MathNode? {
        if (source.isBlank()) return null
        return runCatching {
            val parser = Parser(source)
            val node = parser.parseSequence(depth = 0)
            // 必须吃干净整个输入：留下残渣说明有未配平的花括号或多余的 \right。
            if (parser.pos != source.length) null else node
        }.getOrNull()
    }

    /** 一行内是否有闭合的 `$$…$$`。流式输出时用来避免把半个公式当公式渲染。 */
    fun isClosed(source: String, open: String, close: String): Boolean {
        val start = source.indexOf(open)
        if (start < 0) return false
        return source.indexOf(close, start + open.length) >= 0
    }

    private class Parser(private val s: String) {
        var pos = 0
            private set

        fun parseSequence(
            depth: Int,
            stopAtRight: Boolean = false,
            stopAtBracket: Boolean = false,
            stopAtEnvBoundary: Boolean = false,
        ): MathNode {
            require(depth <= MAX_DEPTH) { "公式嵌套过深" }
            val children = mutableListOf<MathNode>()
            var pendingSpace = false
            while (pos < s.length) {
                val c = s[pos]
                if (c.isWhitespace()) {
                    // TeX 在数学模式下忽略空格，但完全不加间隙会让 `x = 1` 挤成一团。
                    // 折叠成单个窄空格，既接近原意又不破坏对齐。
                    pendingSpace = children.isNotEmpty()
                    pos++
                    continue
                }
                if (c == '}') break
                // `\sqrt[3]{x}` 的根指数在 `[…]` 里，方括号同样是本层的结束符。
                if (stopAtBracket && c == ']') break
                if (stopAtRight && peekCommandName() == "right") break
                // 环境内的 `&` 与 `\\` 由 parseEnvironment 处理，这里只负责在它们处停下。
                if (stopAtEnvBoundary &&
                    (c == '&' || (c == '\\' && s.getOrNull(pos + 1) == '\\') ||
                        peekCommandName() == "end")
                ) {
                    break
                }
                if (c == '&') {
                    // 环境之外的 `&` 多半是模型写错了，当窄空格而不是画出一个和号。
                    if (pendingSpace) {
                        children += MathNode.Sym(" ")
                        pendingSpace = false
                    }
                    children += MathNode.Sym(" ")
                    pos++
                    continue
                }
                if (pendingSpace) {
                    children += MathNode.Sym(" ")
                    pendingSpace = false
                }
                var atom = parseAtom(depth)
                var sup: MathNode? = null
                var sub: MathNode? = null
                while (pos < s.length && (s[pos] == '^' || s[pos] == '_')) {
                    val isSup = s[pos] == '^'
                    pos++
                    val arg = parseAtom(depth + 1)
                    if (isSup) sup = arg else sub = arg
                }
                if (sup != null || sub != null) {
                    atom = if (atom is MathNode.BigOp) {
                        MathNode.BigOp(
                            atom.op,
                            sub ?: atom.subscript,
                            sup ?: atom.superscript,
                        )
                    } else {
                        MathNode.Script(atom, sup, sub)
                    }
                }
                children += atom
            }
            return when (children.size) {
                0 -> MathNode.Row(emptyList())
                1 -> children[0]
                else -> MathNode.Row(children)
            }
        }

        private fun parseAtom(depth: Int): MathNode {
            require(depth <= MAX_DEPTH) { "公式嵌套过深" }
            require(pos < s.length) { "公式意外结束" }
            return when (val c = s[pos]) {
                '{' -> {
                    pos++
                    val body = parseSequence(depth + 1)
                    require(pos < s.length && s[pos] == '}') { "花括号不配平" }
                    pos++
                    body
                }

                '\\' -> parseCommand(depth)
                '^', '_' -> throw IllegalStateException("缺少上下标的主体")
                else -> {
                    pos++
                    MathNode.Sym(c.toString())
                }
            }
        }

        private fun parseCommand(depth: Int): MathNode {
            pos++ // 跳过反斜杠
            val name = readCommandName()
            if (name.isEmpty()) {
                // `\{` `\}` `\|` `\,` 这类单字符命令
                require(pos < s.length) { "反斜杠后缺少字符" }
                val ch = s[pos]
                pos++
                return when (ch) {
                    '{' -> MathNode.Sym("{")
                    '}' -> MathNode.Sym("}")
                    '%', '#', '_', '&', '$' -> MathNode.Sym(ch.toString())
                    '|' -> MathNode.Sym("‖")
                    ',', ';', ':', ' ' -> MathNode.Sym(" ")
                    // `\\` 是换行，不在环境里时没有可换的行，退化成窄空格。
                    '\\' -> MathNode.Sym(" ")
                    '!' -> MathNode.Row(emptyList())
                    else -> MathNode.Sym(ch.toString())
                }
            }
            return when (name) {
                "frac", "dfrac", "tfrac" -> {
                    val numerator = parseArgument(depth)
                    val denominator = parseArgument(depth)
                    MathNode.Fraction(numerator, denominator)
                }

                "sqrt" -> {
                    var index: MathNode? = null
                    if (s.getOrNull(pos) == '[') {
                        pos++
                        index = parseSequence(depth + 1, stopAtBracket = true)
                        require(s.getOrNull(pos) == ']') { "根指数缺少右方括号" }
                        pos++
                    }
                    MathNode.Radical(index, parseArgument(depth))
                }

                "text", "textrm", "mathrm", "operatorname", "mbox" ->
                    MathNode.Text(readRawGroup())

                "mathbf", "textbf", "textit", "mathit" -> MathNode.Text(readRawGroup())

                "left" -> {
                    val left = readDelimiter()
                    val body = parseSequence(depth + 1, stopAtRight = true)
                    require(peekCommandName() == "right") { "\\left 缺少配对的 \\right" }
                    // peek 只看到 `\right`，不能直接调 readCommandName：
                    // 它从当前字符读字母，而当前字符是反斜杠，会读到空串。
                    pos++
                    readCommandName()
                    val right = readDelimiter()
                    MathNode.Delim(left, body, right)
                }

                "right" -> throw IllegalStateException("多余的 \\right")

                "sum", "prod", "coprod", "int", "iint", "oint", "bigcup", "bigcap" ->
                    MathNode.BigOp(bigOpSymbol(name), null, null)

                "lim" -> MathNode.BigOp("lim", null, null)

                "quad" -> MathNode.Sym("  ")
                "qquad" -> MathNode.Sym("    ")

                // 多行环境。`\end` 由 parseEnvironment 消费，这里只处理起点。
                "begin" -> {
                    val envName = readRawGroup()
                    // 未登记的环境（`equation`、`gather` 之类）按无定界符的逐行排版处理：
                    // 内容仍然可读，总比整条公式退回源码好。
                    parseEnvironment(ENV_KINDS[envName] ?: EnvKind.ALIGNED, envName, depth)
                }

                "mathbb" -> {
                    val letters = readSimpleText()
                    MathNode.Sym(letters.map { BLACKBOARD[it] ?: it }.joinToString(""))
                }

                "vec" -> MathNode.Accent(parseArgument(depth), "→")
                "hat" -> MathNode.Accent(parseArgument(depth), "^")
                "bar", "overline" -> MathNode.Accent(parseArgument(depth), "‾")
                "tilde" -> MathNode.Accent(parseArgument(depth), "~")
                "dot" -> MathNode.Accent(parseArgument(depth), "·")
                "ddot" -> MathNode.Accent(parseArgument(depth), "··")

                // `\boxed{}` 只加边框，不改内容；边框对阅读没有帮助，直接透传参数。
                "boxed" -> parseArgument(depth)

                // `\tag{1}` 是公式编号，出现在行尾，去掉比留一个孤立的数字更清楚。
                "tag" -> {
                    parseArgument(depth)
                    MathNode.Row(emptyList())
                }

                // 排版指令对结果没有影响，吞掉即可，避免整条公式降级成源码。
                "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits",
                "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr",
                "mathstrut", "phantom", "strut",
                -> MathNode.Row(emptyList())

                // 未登记的命令按字面 `\name` 输出。宁可让用户看到原始写法，
                // 也不要因为一个装饰性命令丢掉整条公式。
                else -> MathNode.Sym(SYMBOLS[name] ?: "\\$name")
            }
        }

        /**
         * 解析 `\begin{name}` 到 `\end{name}` 之间的内容，返回 [MathNode.Env]。
         *
         * 逐单元调用 parseSequence 并让它停在 `&`、`\\`、`\end` 上：
         * 这样单元内部仍然可以出现分式、上下标与嵌套环境，只有这三者
         * 才被当作环境的分隔符。
         */
        private fun parseEnvironment(kind: EnvKind, name: String, depth: Int): MathNode {
            // `\begin{array}{cc}` 的列格式只影响列宽与竖线，本渲染器按内容自适应，跳过即可。
            if (kind == EnvKind.ARRAY) skipColumnSpec()
            val rows = mutableListOf<List<MathNode>>()
            var current = mutableListOf<MathNode>()
            while (true) {
                current += parseSequence(depth + 1, stopAtEnvBoundary = true)
                when {
                    pos >= s.length ->
                        throw IllegalStateException("\\begin{$name} 缺少 \\end{$name}")

                    s[pos] == '&' -> pos++

                    s[pos] == '\\' && s.getOrNull(pos + 1) == '\\' -> {
                        pos += 2
                        rows += current
                        current = mutableListOf()
                    }

                    peekCommandName() == "end" -> {
                        pos++ // 跳过反斜杠
                        readCommandName()
                        val endName = readRawGroup()
                        require(endName == name) { "\\begin{$name} 与 \\end{$endName} 不匹配" }
                        // 行末的 `\\` 之后会留下一个空行，它不构成一行内容。
                        if (current.any { it !is MathNode.Row || it.children.isNotEmpty() }) {
                            rows += current
                        }
                        break
                    }

                    else -> throw IllegalStateException("环境缺少 \\end")
                }
            }
            return MathNode.Env(kind, rows)
        }

        /** 跳过 `\begin{array}` 后的列格式参数，如 `{cc}`、`{l|c}`。 */
        private fun skipColumnSpec() {
            var i = pos
            while (i < s.length && s[i].isWhitespace()) i++
            if (s.getOrNull(i) != '{') return
            var level = 0
            while (i < s.length) {
                when (s[i]) {
                    '{' -> level++
                    '}' -> {
                        level--
                        if (level == 0) {
                            pos = i + 1
                            return
                        }
                    }
                }
                i++
            }
        }

        /** 参数可以带花括号也可以不带（`\frac12`），后者只取一个原子。 */
        private fun parseArgument(depth: Int): MathNode {
            while (pos < s.length && s[pos].isWhitespace()) pos++
            require(pos < s.length) { "缺少参数" }
            if (s[pos] == '{') {
                pos++
                val body = parseSequence(depth + 1)
                require(pos < s.length && s[pos] == '}') { "花括号不配平" }
                pos++
                return body
            }
            return parseAtom(depth + 1)
        }

        /** 原样读取 `{…}` 里的文本，保留空格与中文。 */
        private fun readRawGroup(): String {
            while (pos < s.length && s[pos].isWhitespace()) pos++
            require(pos < s.length && s[pos] == '{') { "\\text 缺少花括号" }
            var depth = 0
            val start = pos + 1
            while (pos < s.length) {
                when (s[pos]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val text = s.substring(start, pos)
                            pos++
                            return text
                        }
                    }
                }
                pos++
            }
            throw IllegalStateException("\\text 花括号不配平")
        }

        /** 读一个不带排版语义的文本参数，用于 `\mathbb{R}` 这类逐字符映射的命令。 */
        private fun readSimpleText(): String {
            while (pos < s.length && s[pos].isWhitespace()) pos++
            require(pos < s.length) { "缺少参数" }
            if (s[pos] == '{') return readRawGroup()
            val c = s[pos]
            pos++
            return c.toString()
        }

        private fun readDelimiter(): String {
            while (pos < s.length && s[pos].isWhitespace()) pos++
            require(pos < s.length) { "\\left 缺少定界符" }
            if (s[pos] == '\\') {
                pos++
                val name = readCommandName()
                return when (name) {
                    "" -> s.let { val c = it[pos]; pos++; c.toString() }
                    "{" -> "{"
                    "}" -> "}"
                    "|" -> "‖"
                    "langle" -> "⟨"
                    "rangle" -> "⟩"
                    "lvert" -> "|"
                    "rvert" -> "|"
                    "lfloor" -> "⌊"
                    "rfloor" -> "⌋"
                    "lceil" -> "⌈"
                    "rceil" -> "⌉"
                    else -> throw IllegalStateException("不支持的定界符 \\$name")
                }
            }
            val c = s[pos]
            pos++
            // `\left.` 是「这一侧不画定界符」的写法。
            return if (c == '.') "" else c.toString()
        }

        private fun readCommandName(): String {
            val start = pos
            while (pos < s.length && s[pos].isLetter()) pos++
            return s.substring(start, pos)
        }

        private fun peekCommandName(): String? {
            if (s.getOrNull(pos) != '\\') return null
            var i = pos + 1
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            return if (i > start) s.substring(start, i) else null
        }

        private fun bigOpSymbol(name: String): String = when (name) {
            "sum" -> "∑"
            "prod" -> "∏"
            "coprod" -> "∐"
            "int" -> "∫"
            "iint" -> "∬"
            "oint" -> "∮"
            "bigcup" -> "⋃"
            "bigcap" -> "⋂"
            else -> name
        }
    }

    /**
     * 命令表。
     *
     * 希腊字母取 Unicode 里的标准字形；`varepsilon` 与 `varphi` 用变体，
     * 与数学排版里常见的写法一致。函数名（`\sin`、`\log`）按正体文字输出。
     */
    private val SYMBOLS: Map<String, String> = mapOf(
        // 小写希腊字母
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ϵ", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
        "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
        "phi" to "φ", "varphi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        // 大写希腊字母
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        // 关系
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "simeq" to "≃", "propto" to "∝", "ll" to "≪", "gg" to "≫",
        "in" to "∈", "notin" to "∉", "ni" to "∋",
        "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
        "perp" to "⊥", "parallel" to "∥", "mid" to "|",
        // 运算
        "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷",
        "cdot" to "·", "ast" to "∗", "star" to "⋆", "circ" to "∘",
        "bullet" to "•", "oplus" to "⊕", "otimes" to "⊗",
        "cup" to "∪", "cap" to "∩", "setminus" to "∖",
        "land" to "∧", "lor" to "∨", "neg" to "¬", "lnot" to "¬",
        // 箭头
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "Leftrightarrow" to "⇔", "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
        // 其它符号
        "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "forall" to "∀", "exists" to "∃", "emptyset" to "∅", "varnothing" to "∅",
        "angle" to "∠", "degree" to "°", "prime" to "′",
        "dots" to "…", "ldots" to "…", "cdots" to "…", "vdots" to "⋮", "ddots" to "⋱",
        "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
        "aleph" to "ℵ", "therefore" to "∴", "because" to "∵",
        "ldotp" to ".", "colon" to ":",
        // 函数名按正体输出
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot",
        "sec" to "sec", "csc" to "csc", "arcsin" to "arcsin", "arccos" to "arccos",
        "arctan" to "arctan", "sinh" to "sinh", "cosh" to "cosh", "tanh" to "tanh",
        "log" to "log", "lg" to "lg", "ln" to "ln", "exp" to "exp",
        "max" to "max", "min" to "min", "sup" to "sup", "inf" to "inf",
        "det" to "det", "gcd" to "gcd", "deg" to "deg", "dim" to "dim",
        "arg" to "arg", "bmod" to "mod",
    )

    /** `\begin{…}` 的环境名到排版种类。只登记常用项，其余按逐行排版降级。 */
    private val ENV_KINDS: Map<String, EnvKind> = mapOf(
        "cases" to EnvKind.CASES,
        "aligned" to EnvKind.ALIGNED,
        "matrix" to EnvKind.MATRIX,
        "pmatrix" to EnvKind.PMATRIX,
        "bmatrix" to EnvKind.BMATRIX,
        "vmatrix" to EnvKind.VMATRIX,
        "array" to EnvKind.ARRAY,
    )

    /** 黑板粗体（`\mathbb`）只对应数论里常用的几个集合字母，其余保持原字母。 */
    private val BLACKBOARD: Map<Char, Char> = mapOf(
        'R' to 'ℝ', 'N' to 'ℕ', 'Z' to 'ℤ', 'Q' to 'ℚ', 'C' to 'ℂ',
    )
}

/**
 * 行内公式的单行降级渲染。
 *
 * 段落里混排公式时，用 Compose 的 `InlineTextContent` 需要预先给出固定尺寸，
 * 而公式的高度随分式、根式的嵌套层数变化，估出来的占位框不是压字就是留空。
 * 因此行内公式不排版成二维结构，而是压成一行文本：分式写成 `a⁄b`，
 * 根号写成 `√(x)`，可映射的指数用 Unicode 上标（`x²`），其余保留 `^`。
 *
 * 块级公式（独占一行的 `$$…$$`）才走二维排版，见 `LatexView`。
 * 这样段落能正常换行，二维结构只出现在它真正需要空间的地方。
 */
object LatexInline {

    /** 把已解析的节点压成单行文本。 */
    fun toPlainText(node: MathNode): String = when (node) {
        is MathNode.Row -> node.children.joinToString("") { toPlainText(it) }.trim()
        is MathNode.Sym -> node.text
        is MathNode.Text -> node.text
        is MathNode.Fraction ->
            "${wrap(toPlainText(node.numerator))}⁄${wrap(toPlainText(node.denominator))}"

        is MathNode.Radical -> {
            val index = node.index?.let { toPlainText(it) }
            if (index.isNullOrBlank()) "√(${toPlainText(node.body)})" else "${index}√(${toPlainText(node.body)})"
        }

        is MathNode.Script -> {
            val base = toPlainText(node.base)
            val sup = node.superscript?.let { toPlainText(it) }.orEmpty()
            val sub = node.subscript?.let { toPlainText(it) }.orEmpty()
            buildString {
                append(base)
                if (sup.isNotEmpty()) append(toSuperscript(sup))
                if (sub.isNotEmpty()) append(toSubscript(sub))
            }
        }

        is MathNode.BigOp -> {
            val sub = node.subscript?.let { toSubscript(toPlainText(it)) }.orEmpty()
            val sup = node.superscript?.let { toSuperscript(toPlainText(it)) }.orEmpty()
            "${node.op}$sub$sup"
        }

        is MathNode.Delim -> "${node.left}${toPlainText(node.body)}${node.right}"

        // 行内不排多行结构，按「一行一行、列间空格」压平，至少保住阅读顺序。
        is MathNode.Env -> node.rows.joinToString(" ; ") { row ->
            row.joinToString(" ") { toPlainText(it) }
        }

        // 重音符号没有单行等价物，把标记直接跟在被标的字符后面。
        is MathNode.Accent -> toPlainText(node.body) + node.mark
    }

    /** 分式的分子或分母本身含运算时需要括号，否则 `a+b⁄c` 会读成 `a + b/c`。 */
    private fun wrap(text: String): String =
        if (text.length > 1 && text.any { it in "+-×÷· =<>≤≥≠" }) "($text)" else text

    private fun toSuperscript(text: String): String {
        val mapped = text.map { SUPERSCRIPTS[it] ?: return "^$text" }.joinToString("")
        return mapped
    }

    private fun toSubscript(text: String): String {
        val mapped = text.map { SUBSCRIPTS[it] ?: return "_$text" }.joinToString("")
        return mapped
    }

    private val SUPERSCRIPTS = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ',
    )

    private val SUBSCRIPTS = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'i' to 'ᵢ', 'j' to 'ⱼ', 'n' to 'ₙ',
    )
}