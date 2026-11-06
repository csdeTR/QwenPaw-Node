package io.qwenpaw.node.data.plugin

import io.qwenpaw.node.data.model.BridgeInfoDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi

/**
 * 配套插件的探测结果。
 *
 * **没有「已禁用」这一态。** 禁用插件是把目录改名为 `.disabled`，发现阶段直接跳过，
 * 重启后列表里消失、端点返回 404，与从未安装完全一致。因此只有下面三态。
 */
sealed interface BridgeStatus {

    /** 还没探过，或探测被 401 挡住（服务端要登录、验证阶段还没有 token）。 */
    data object Unknown : BridgeStatus

    /** 插件已就位且契约版本兼容。 */
    data class Ready(val info: BridgeInfoDto) : BridgeStatus

    /** 未检测到：404 或连不上。 */
    data object Absent : BridgeStatus

    /** 展示用的一行文字，设置页直接读它。 */
    val label: String
        get() = when (this) {
            Unknown -> "尚未检测"
            is Ready -> "已检测到 QwenPaw Node Bridge v${info.pluginVersion}"
            Absent -> "未检测到配套插件"
        }
}

/**
 * 按实例缓存插件探测结果。
 *
 * **判定集中在这一处**，页面不自行查能力清单：一旦允许各页面自己判断
 * `capabilities`，「这个入口该不该显示」的规则就会散落成多份，迟早对不上。
 */
class PluginProbe(private val api: QwenPawApi) {

    private val cache = mutableMapOf<String, BridgeStatus>()

    private fun key(instanceId: String, agentId: String): String = "$instanceId|$agentId"

    fun cached(instanceId: String, agentId: String): BridgeStatus =
        cache[key(instanceId, agentId)] ?: BridgeStatus.Unknown

    /**
     * 探测一次并写入缓存。
     *
     * 401 记为 [BridgeStatus.Unknown]（「待重探」）而不是「未检测到」：插件路由不在
     * 服务端鉴权的公开路径里，验证阶段还没有 token 时必然 401；把它当成未安装，
     * 会让用户在开启登录的服务端上永远看到「未检测到配套插件」。
     */
    suspend fun probe(
        instanceId: String,
        conn: Connection,
    ): BridgeStatus {
        val result = try {
            api.pluginBridgeInfo(conn)?.let { BridgeStatus.Ready(it) } ?: BridgeStatus.Absent
        } catch (e: ApiException) {
            // 401/403 说明「没权限问」，不是「没装」。其余状态码按未检测到处理：
            // 插件的 /info 只有 200 与 404 两种预期结果，别的都是异常。
            if (e.code == 401 || e.code == 403) BridgeStatus.Unknown else BridgeStatus.Absent
        } catch (e: java.io.IOException) {
            // 连不上：保留上一次的结论更稳妥，避免网络抖动把「已安装」翻成「未安装」。
            // 首次探测时缓存里本来就没有结论，返回 Unknown 也只是让界面写「尚未检测」。
            cache[key(instanceId, conn.agentId)] ?: BridgeStatus.Unknown
        }
        cache[key(instanceId, conn.agentId)] = result
        return result
    }

    /** 换实例或换智能体时不必清缓存：键已经区分开了，各自保留各自的结论。 */
    fun clear(instanceId: String) {
        cache.keys.filter { it.startsWith("$instanceId|") }.forEach { cache.remove(it) }
    }
}