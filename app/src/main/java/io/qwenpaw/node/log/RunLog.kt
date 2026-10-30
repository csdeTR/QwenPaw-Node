package io.qwenpaw.node.log

/**
 * 日志类别。
 *
 * 分类的目的是让用户能关掉不关心的噪音：排查连接问题时只需要 NETWORK，
 * 排查语音时只需要 VOICE。分类集合由设置页控制，未开启的类别不写入。
 */
enum class LogKind(val label: String) {
    NETWORK("网络"),
    STREAM("对话流"),
    UI("界面"),
    STORAGE("存储"),
    VOICE("语音"),
}

/** 一条日志。 */
data class LogEntry(
    /** 记录时刻的本地时间字符串，精确到毫秒。 */
    val time: String,
    val kind: LogKind,
    val tag: String,
    val message: String,
    /** 非 null 表示这是一条错误记录。 */
    val error: String? = null,
)

/**
 * 进程内运行日志。
 *
 * 几条设计约束：
 *
 * 1. **只存在内存里，用有上限的环形缓冲。** 用户要的是「本次运行的日志」，
 *    写文件会引入清理、配额、权限等无关复杂度，而这些内容在导出时已经够用。
 * 2. **默认关闭。** 关着的时候所有 [d] / [e] 调用都是空操作，不产生任何开销
 *    与内存占用——这也是它能随手埋点的前提。
 * 3. **必须脱敏。** 请求头里的 `Authorization: Bearer xxx` 与实例 token
 *    绝不能进日志。见 [redact]。
 * 4. 日志模块自身不能抛异常。任何格式化失败都吞掉，否则日志会变成新的故障源。
 */
object RunLog {

    /** 最多保留多少条。超出后丢弃最旧的，避免长时间运行吃内存。 */
    const val MAX_ENTRIES = 2000

    @Volatile
    private var enabled = false

    @Volatile
    private var kinds: Set<LogKind> = LogKind.entries.toSet()

    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    /** 订阅点。列表是不可变的快照，便于 Compose 直接消费。 */
    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: kotlinx.coroutines.flow.StateFlow<List<LogEntry>> = _entries

    /** 是否正在记录。设置页的开关读它。 */
    val isEnabled: Boolean get() = enabled

    /** 当前记录的类别集合。 */
    val enabledKinds: Set<LogKind> get() = kinds

    /**
     * 打开记录。
     *
     * [clear] 默认 true：每次打开都从空白开始，避免把上一次的残留当成这次的现场。
     */
    fun enable(clear: Boolean = true) {
        enabled = true
        if (clear) clear()
    }

    /** 关闭记录。已记录的条目保留，用户仍可在页面里查看与导出。 */
    fun disable() {
        enabled = false
    }

    /** 设置类别过滤。只影响之后是否记录，已记录的不受影响。 */
    fun setKinds(next: Set<LogKind>) {
        kinds = next
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _entries.value = emptyList()
    }

    fun d(kind: LogKind, tag: String, message: String) {
        if (!enabled || kind !in kinds) return
        append(LogEntry(now(), kind, tag, redact(message), null))
    }

    /**
     * 用外部日志替换当前缓冲。
     *
     * 只服务于「导入日志查看」：导入的目的是看别人分享的日志，
     * 不是把两份记录混在一起，所以是替换而非追加。
     */
    fun replaceWith(entries: List<LogEntry>) {
        synchronized(buffer) {
            buffer.clear()
            entries.takeLast(MAX_ENTRIES).forEach { buffer.addLast(it) }
        }
        _entries.value = entries.takeLast(MAX_ENTRIES)
    }

    fun e(kind: LogKind, tag: String, error: Throwable, message: String = "") {
        if (!enabled || kind !in kinds) return
        val detail = buildString {
            if (message.isNotBlank()) append(message).append("：")
            append(error::class.java.simpleName)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
        append(LogEntry(now(), kind, tag, redact(detail), error::class.java.simpleName))
    }

    /**
     * 把日志拼成可导出的纯文本。
     *
     * 格式固定为「时间 [类别] tag · 内容」，便于用文本工具按行过滤。
     */
    fun export(): String {
        val snapshot = _entries.value
        if (snapshot.isEmpty()) return "（本次运行没有记录日志）\n"
        return buildString {
            append("# QwenPaw Node 运行日志\n")
            append("# 记录条数：").append(snapshot.size).append('\n')
            append("# 类别：").append(kinds.joinToString("、") { it.label }).append("\n\n")
            snapshot.forEach { e ->
                append(e.time).append(" [").append(e.kind.label).append("] ")
                append(e.tag).append(" · ").append(e.message)
                append('\n')
            }
        }
    }

    /**
     * 脱敏。
     *
     * 只处理两类真实存在的敏感形态：`Bearer <token>` 与 query 里的 `token=`。
     * 不做泛化的「看起来像密钥」猜测——过度替换会让日志失去诊断价值。
     */
    fun redact(text: String): String = text
        .replace(Regex("Bearer\\s+[A-Za-z0-9._\\-]+"), "Bearer ***")
        .replace(Regex("([?&](?:token|access_token)=)[^&\\s]+"), "$1***")

    private fun append(entry: LogEntry) {
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
        }
        _entries.value = synchronized(buffer) { buffer.toList() }
    }

    private fun now(): String = try {
        java.time.LocalTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"),
        )
    } catch (_: Exception) {
        ""
    }
}