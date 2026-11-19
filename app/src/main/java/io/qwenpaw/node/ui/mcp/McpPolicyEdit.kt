package io.qwenpaw.node.ui.mcp

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.McpPolicyDto
import io.qwenpaw.node.data.model.McpToolDefaultDto
import io.qwenpaw.node.ui.i18n.Texts

/**
 * MCP 访问策略的编辑逻辑。
 *
 * 策略是**整读整写**：服务端按提交的 payload 重建所有「控制台托管」的规则，
 * 少带一个字段就等于删掉一批规则。因此这里所有函数都返回**新的整份对象**，
 * 只改目标字段，其余四个字段原样透传——不做「只发改动字段」的增量提交。
 *
 * 抽成纯函数是为了让「整份带回」这件事可以被单测断言，
 * 而不是只靠写代码时记得。
 */
internal object McpPolicyEdit {

    /** 三档效果，服务端的取值是小写英文。 */
    val effects: List<String> = listOf("allow", "ask", "deny")

    fun effectLabel(effect: String): String = when (effect) {
        "allow" -> Texts.get(R.string.mcp_effect_allow)
        "ask" -> Texts.get(R.string.mcp_effect_ask)
        "deny" -> Texts.get(R.string.mcp_effect_deny)
        else -> effect
    }

    /** 传输方式。`stdio` 是本机进程，另外两种是远端。 */
    fun transportLabel(transport: String): String = when (transport) {
        "stdio" -> Texts.get(R.string.mcp_transport_stdio)
        "streamable_http" -> "HTTP"
        "sse" -> "SSE"
        else -> transport
    }

    /**
     * 客户端地址或命令的一行摘要。
     *
     * 远端取 `url`，本机进程取 `command`；两者都空时给「未配置」。
     * 不展示 `env` 与 `headers`：里面通常是凭据的引用。
     */
    fun endpointLabel(transport: String, url: String, command: String): String =
        when {
            transport == "stdio" && command.isNotBlank() -> command
            url.isNotBlank() -> url
            command.isNotBlank() -> command
            else -> Texts.get(R.string.mcp_endpoint_unconfigured)
        }

    /** 只改默认效果，其余四个字段原样带回。 */
    fun withDefaultEffect(policy: McpPolicyDto, effect: String): McpPolicyDto =
        policy.copy(defaultEffect = effect)

    /**
     * 设置某个工具的默认效果：已有条目就地替换，没有则追加。
     *
     * 不动 `clientOverrides` 与 `toolOverrides`。传 `effect` 为空串表示移除该条目
     * （服务端对未知工具的效果没有意义，留着只会让规则表越来越长）。
     */
    fun withToolDefault(policy: McpPolicyDto, toolName: String, effect: String): McpPolicyDto {
        val name = toolName.trim()
        if (name.isEmpty()) return policy
        val rest = policy.toolDefaults.filter { it.toolName != name }
        val next = if (effect.isBlank()) {
            rest
        } else {
            rest + McpToolDefaultDto(toolName = name, effect = effect)
        }
        return policy.copy(toolDefaults = next)
    }

    /**
     * 把勾选集合换算成提交给 `PUT /api/mcp/tools/{key}` 的 `tools` 参数。
     *
     * 三种结果对应三种语义：
     * - 全部勾选 → `null`，即取消白名单、启用全部工具（服务端如此定义 `null`）；
     * - 部分勾选 → 按 `all` 的顺序筛出的列表，保证每次提交的顺序一致，
     *   否则同一个选择在不同次提交里顺序不同，服务端与界面的对比会一直显示「有改动」；
     * - 一个都没勾 → 空列表，即一个工具都不启用（不是 `null`）。
     *
     * 空集合与 `null` 只差一个分支，混起来的后果却很具体：用户取消全部勾选后
     * 会把所有工具都打开。这条逻辑必须只有一处出口。
     */
    fun whitelistSelection(all: List<String>, enabled: Set<String>): List<String>? = when {
        enabled.isEmpty() -> emptyList()
        all.isNotEmpty() && all.all { it in enabled } -> null
        else -> all.filter { it in enabled }
    }

    /** 某个工具当前的默认效果，没有单独设置时返回空串。 */
    fun toolEffectOf(policy: McpPolicyDto, toolName: String): String =
        policy.toolDefaults.firstOrNull { it.toolName == toolName }?.effect.orEmpty()
}