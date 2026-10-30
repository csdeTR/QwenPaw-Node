package io.qwenpaw.node.log

/**
 * 解析导出的日志文本。
 *
 * 只为「导入后查看」服务：把 [RunLog.export] 产出的格式读回来。
 * 格式是每行 `HH:mm:ss.SSS [类别] tag · 内容`，`#` 开头的是注释行。
 *
 * 解析必须宽容：用户可能导入别人手工改过、或者被聊天软件截断的文件，
 * 无法识别的行跳过即可，不能因为一行脏数据就整份失败。
 */
object LogImporter {

    private val lineRegex = Regex(
        "^\\s*(\\d{1,2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)\\s*\\[([^\\]]+)]\\s*([^·]*?)·\\s*(.*)$",
    )

    fun parse(text: String): List<LogEntry> {
        val out = mutableListOf<LogEntry>()
        text.lineSequence().forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val match = lineRegex.find(line)
            if (match == null) {
                // 认不出格式的行原样保留，至少让用户能看到内容。
                out += LogEntry(
                    time = "",
                    kind = LogKind.UI,
                    tag = "导入",
                    message = line.trim(),
                    error = null,
                )
                return@forEach
            }
            val (time, label, tag, message) = match.destructured
            val kind = LogKind.entries.firstOrNull { it.label == label.trim() } ?: LogKind.UI
            out += LogEntry(
                time = time.trim(),
                kind = kind,
                tag = tag.trim(),
                message = message.trim(),
                error = null,
            )
        }
        return out
    }
}