package io.qwenpaw.node.ui.mcp

import io.qwenpaw.node.data.model.McpClientDto
import io.qwenpaw.node.data.model.McpClientWriteDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 表单里的错误落在哪个字段上。 */
enum class McpDraftField { KEY, NAME, COMMAND, URL, TIMEOUT, ENV, HEADERS }

/**
 * 错误种类。
 *
 * 纯逻辑不持有资源，因此只给种类，文案由界面按 [McpDraftIssue] 映射；
 * 这样这一层可以在 JVM 单测里断言，不需要装 [io.qwenpaw.node.ui.i18n.Texts]。
 */
enum class McpDraftIssue {
    KEY_REQUIRED,
    KEY_RESERVED,
    NAME_REQUIRED,
    COMMAND_REQUIRED,
    URL_REQUIRED,
    URL_SCHEME,
    TIMEOUT_NUMBER,
    ENV_LINE,
    HEADER_LINE,
}

/** 一条错误。[detail] 放保留前缀一类的补充数据，[line] 放行号（从 1 起）。 */
data class McpDraftError(
    val field: McpDraftField,
    val issue: McpDraftIssue,
    val detail: String = "",
    val line: Int = 0,
)

/**
 * 编辑页的完整草稿。
 *
 * [original] 为空表示新建；编辑时用它算「只提交变化字段」，也用来判断未保存更改。
 * 多行文本字段（[argsText] / [envText] / [headersText]）保留用户输入的原始排版，
 * 只在提交时解析，这样来回切换焦点不会把用户写的空行吃掉。
 */
data class McpDraftState(
    val key: String = "",
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val transport: String = McpEditDraft.STDIO,
    val command: String = "",
    val argsText: String = "",
    val envText: String = "",
    val cwd: String = "",
    val url: String = "",
    val headersText: String = "",
    val httpTimeoutText: String = "",
    val original: McpClientDto? = null,
) {
    val isNew: Boolean get() = original == null
}

/**
 * MCP 客户端的新建与编辑逻辑。
 *
 * 两条边界值得记住：
 * - **编辑只提交变化字段**：`PUT /api/mcp/{key}` 是部分更新，没写的字段服务端保持原值。
 *   把整份对象发回去会把用户没动过的 `tools` 之外的字段也覆盖掉，因此这里逐字段比对。
 * - **切换传输方式要显式清空另一侧**：清空后的空串、空表、空列表与原值不同，
 *   比对时自然进入请求体，服务端据此把旧参数删掉；若只是不写，旧参数会留在卡片里。
 *   `http_timeout` 没有「清空」的写法（`null` 被序列化配置省略），切回本机进程时不回写，
 *   服务端仍保留该值，但对本机进程不生效。
 */
object McpEditDraft {

    const val STDIO = "stdio"
    const val HTTP = "streamable_http"
    const val SSE = "sse"

    val transports: List<String> = listOf(STDIO, HTTP, SSE)

    /**
     * 服务端内部使用的键前缀。
     *
     * 这些前缀下的键是端点自身（工具列表、开关、OAuth、策略）而不是客户端，
     * 新建时撞上会被路由到别的处理器，表现为 405 或改到了别的对象。
     */
    private val reservedPrefixes = listOf(
        "access-principals/",
        "tools/",
        "toggle/",
        "oauth/",
        "policy/",
    )

    private val expiryFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

    fun isRemote(transport: String): Boolean = transport != STDIO

    fun blank(): McpDraftState = McpDraftState()

    /** 打开某个客户端的编辑页。 */
    fun from(client: McpClientDto): McpDraftState = McpDraftState(
        key = client.key,
        name = client.name,
        description = client.description,
        enabled = client.enabled,
        transport = client.transport.ifBlank { STDIO },
        command = client.command,
        argsText = client.args.joinToString("\n"),
        envText = linesOf(client.env, separator = "="),
        cwd = client.cwd.orEmpty(),
        url = client.url,
        headersText = linesOf(client.headers, separator = ": "),
        httpTimeoutText = formatSeconds(client.httpTimeout),
        original = client,
    )

    private fun linesOf(map: Map<String, String>?, separator: String): String =
        map.orEmpty().entries.joinToString("\n") { it.key + separator + it.value }

    private fun formatSeconds(value: Double?): String = when {
        value == null -> ""
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> value.toString()
    }

    /**
     * 切换传输方式，并显式清空另一侧字段。
     *
     * 不清空的后果很具体：编辑一个远端客户端并改成本机进程后，旧的 `url` 与请求头
     * 会继续留在请求体里（因为比对不出差异），卡片上会出现一个既跑本机进程又带地址的客户端。
     */
    fun withTransport(draft: McpDraftState, transport: String): McpDraftState {
        if (transport !in transports || transport == draft.transport) return draft
        return if (transport == STDIO) {
            draft.copy(transport = transport, url = "", headersText = "", httpTimeoutText = "")
        } else {
            draft.copy(transport = transport, command = "", argsText = "", envText = "", cwd = "")
        }
    }

    /** `KEY=VALUE` / `KEY: VALUE` 的逐行解析结果。空行忽略，不合法的行记下行号。 */
    data class ParsedPairs(
        val entries: Map<String, String>,
        val invalidLines: List<Int>,
    )

    fun parsePairs(text: String, separator: Char): ParsedPairs {
        val entries = LinkedHashMap<String, String>()
        val invalid = mutableListOf<Int>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val at = line.indexOf(separator)
            val key = if (at < 0) "" else line.substring(0, at).trim()
            if (at < 0 || key.isEmpty()) {
                invalid += index + 1
            } else {
                entries[key] = line.substring(at + 1).trim()
            }
        }
        return ParsedPairs(entries, invalid)
    }

    /** 参数每行一个，空行忽略。 */
    fun parseArgs(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** 超时留空返回 null（用服务端默认值）；写错或非正数同样返回 null，由 [validate] 拦下。 */
    fun parseTimeout(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it > 0.0 }

    /** 命中的保留前缀，没有则返回 null。 */
    fun reservedPrefixOf(key: String): String? {
        val value = key.trim()
        return reservedPrefixes.firstOrNull { value.startsWith(it) }
    }

    fun isHttpUrl(url: String): Boolean {
        val value = url.trim().lowercase()
        return value.startsWith("http://") || value.startsWith("https://")
    }

    /**
     * 按当前传输方式校验。
     *
     * 只校验会影响服务端建连的字段：标识、名称，以及当前传输方式必填的那一个地址或命令。
     * `args` 与 `cwd` 不校验——写错了由 MCP 服务端在拉起进程时报错，客户端猜不出来。
     */
    fun validate(draft: McpDraftState): List<McpDraftError> {
        val errors = mutableListOf<McpDraftError>()
        if (draft.isNew) {
            val key = draft.key.trim()
            val prefix = reservedPrefixOf(key)
            when {
                key.isEmpty() ->
                    errors += McpDraftError(McpDraftField.KEY, McpDraftIssue.KEY_REQUIRED)

                prefix != null ->
                    errors += McpDraftError(McpDraftField.KEY, McpDraftIssue.KEY_RESERVED, prefix)
            }
        }
        if (draft.name.isBlank()) {
            errors += McpDraftError(McpDraftField.NAME, McpDraftIssue.NAME_REQUIRED)
        }
        if (draft.transport == STDIO) {
            if (draft.command.isBlank()) {
                errors += McpDraftError(McpDraftField.COMMAND, McpDraftIssue.COMMAND_REQUIRED)
            }
            parsePairs(draft.envText, '=').invalidLines.firstOrNull()?.let { line ->
                errors += McpDraftError(McpDraftField.ENV, McpDraftIssue.ENV_LINE, line = line)
            }
        } else {
            val url = draft.url.trim()
            when {
                url.isEmpty() ->
                    errors += McpDraftError(McpDraftField.URL, McpDraftIssue.URL_REQUIRED)

                !isHttpUrl(url) ->
                    errors += McpDraftError(McpDraftField.URL, McpDraftIssue.URL_SCHEME)
            }
            if (draft.httpTimeoutText.isNotBlank() && parseTimeout(draft.httpTimeoutText) == null) {
                errors += McpDraftError(McpDraftField.TIMEOUT, McpDraftIssue.TIMEOUT_NUMBER)
            }
            parsePairs(draft.headersText, ':').invalidLines.firstOrNull()?.let { line ->
                errors += McpDraftError(McpDraftField.HEADERS, McpDraftIssue.HEADER_LINE, line = line)
            }
        }
        return errors
    }

    /** 新建请求体。只写当前传输方式用到的字段，另一侧留空由服务端取默认值。 */
    fun newClientBody(draft: McpDraftState): McpClientWriteDto {
        val base = McpClientWriteDto(
            name = draft.name.trim(),
            description = draft.description.trim().takeIf { it.isNotEmpty() },
            enabled = draft.enabled,
            transport = draft.transport,
        )
        return if (draft.transport == STDIO) {
            base.copy(
                command = draft.command.trim(),
                args = parseArgs(draft.argsText),
                env = parsePairs(draft.envText, '=').entries,
                cwd = draft.cwd.trim().takeIf { it.isNotEmpty() },
            )
        } else {
            base.copy(
                url = draft.url.trim(),
                headers = parsePairs(draft.headersText, ':').entries,
                httpTimeout = parseTimeout(draft.httpTimeoutText),
            )
        }
    }

    /**
     * 部分更新的请求体：只含与原值不同的字段。
     *
     * 两种传输方式的字段都要比对，因为切换传输方式时草稿里另一侧已被 [withTransport] 清空，
     * 比对结果就是显式的空串 / 空表 / 空列表，服务端据此清掉旧参数。
     * 返回体用 [hasChanges] 判断是否真的需要发请求。
     */
    fun changes(draft: McpDraftState, original: McpClientDto): McpClientWriteDto {
        val name = draft.name.trim()
        val description = draft.description.trim()
        val command = draft.command.trim()
        val cwd = draft.cwd.trim()
        val url = draft.url.trim()
        val args = parseArgs(draft.argsText)
        val env = parsePairs(draft.envText, '=').entries
        val headers = parsePairs(draft.headersText, ':').entries
        val timeout = parseTimeout(draft.httpTimeoutText)

        var out = McpClientWriteDto()
        if (name != original.name) out = out.copy(name = name)
        if (description != original.description) out = out.copy(description = description)
        if (draft.enabled != original.enabled) out = out.copy(enabled = draft.enabled)
        if (draft.transport != original.transport) out = out.copy(transport = draft.transport)

        if (command != original.command) out = out.copy(command = command)
        if (args != original.args) out = out.copy(args = args)
        if (env != original.env.orEmpty()) out = out.copy(env = env)
        if (cwd != original.cwd.orEmpty()) out = out.copy(cwd = cwd)
        if (url != original.url) out = out.copy(url = url)
        if (headers != original.headers.orEmpty()) out = out.copy(headers = headers)
        // 超时只能改不能清：null 在请求体里被省略，清空需要服务端接受显式 null。
        if (timeout != null && timeout != original.httpTimeout) out = out.copy(httpTimeout = timeout)
        return out
    }

    fun hasChanges(payload: McpClientWriteDto): Boolean = payload != McpClientWriteDto()

    /**
     * OAuth 有效期的展示。
     *
     * `expires_at` 是 epoch 秒（`McpOAuthStatusDto` 里声明为 Double）。时区作为参数传入，
     * 默认值就是本机时区，单测显式传 UTC 才能钉住输出而不依赖运行环境。
     */
    fun expiryLabel(seconds: Double, zone: ZoneId = ZoneId.systemDefault()): String {
        if (seconds <= 0.0) return ""
        val time = runCatching {
            Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone)
        }.getOrNull() ?: return ""
        return time.format(expiryFormatter)
    }
}
