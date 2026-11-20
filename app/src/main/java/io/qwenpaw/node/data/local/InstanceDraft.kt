package io.qwenpaw.node.data.local

import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts

/**
 * 表单与引导页交给仓库的连接参数。
 *
 * 独立成类型是为了把「实例显示名」从地址里分离出来。此前实例名是从
 * [AddressEntry.alias] 推导的，结果实例卡片的标题与副标题显示同一个字符串，
 * 读起来像是「给实例起别名」，实际那个字段描述的是某一条连接地址。
 *
 * [instanceName] 与 [addresses] 里的别名是完全无关的两件事：
 * - 名称用来在设置里区分「家里那台」「公司那台」；
 * - 地址别名只影响这一条地址在卡片与选择清单里的显示。
 */
data class InstanceDraft(
    val addresses: List<AddressEntry>,
    /** 实例显示名。留空时由调用方回落到第一个地址。 */
    val instanceName: String = "",
    val username: String = "",
    val password: String = "",
    val agentId: String = "default",
    /**
     * 反向代理的 HTTP Basic 凭据，与 [username] / [password] 是两套身份。
     *
     * 两者都非空才生效：只填账号说明用户还没填完，此时按「没开反代验证」处理，
     * 让请求直接打到服务端，而不是拿半组凭据反复撞 401。
     */
    val basicUser: String = "",
    val basicPassword: String = "",
    /**
     * 连接方式。PASSWORD 用 [username] / [password] 换令牌；
     * TOKEN 直接把 [token] 当登录凭据。
     */
    val mode: io.qwenpaw.node.data.CredentialMode = io.qwenpaw.node.data.CredentialMode.PASSWORD,
    /** 访问令牌。只在 [mode] 为 TOKEN 时生效；编辑时留空表示保留已保存的令牌。 */
    val token: String = "",
) {
    /**
     * 落库用的名称。
     *
     * 名称是自由文本，用户很可能只敲了空格；此时退回第一个地址，
     * 否则实例卡片第一行会是一片空白，看起来像渲染坏了。
     */
    fun resolvedName(): String {
        val trimmed = instanceName.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return addresses.firstOrNull { it.url.isNotBlank() }?.url?.trim().orEmpty()
            .ifBlank { Texts.get(R.string.drest_local_instance) }
    }
}