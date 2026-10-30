package io.qwenpaw.node.data.local

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
            .ifBlank { "本地实例" }
    }
}