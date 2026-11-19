package io.qwenpaw.node.data.plugin

import android.content.Context

/** 触发安装引导的时机。 */
enum class BridgeGuideTrigger { FIRST_CONNECT, LAUNCH }

/** 是否弹安装引导；[askMute] 为 true 表示这是第二次取消，同时提供「不再提示」。 */
sealed interface BridgeGuideDecision {
    data class Show(val askMute: Boolean) : BridgeGuideDecision
    data object Skip : BridgeGuideDecision
}

/**
 * 纯决策函数。
 *
 * 首次添加实例时一定提示；之后每次冷启动只有 30% 概率再提，且每个进程只掷一次。
 * 插件已安装、用户已选「不再提示」时不提示。
 */
fun bridgeGuideDecision(
    trigger: BridgeGuideTrigger,
    status: BridgeStatus,
    cancelledCount: Int,
    muted: Boolean,
    random: Double,
): BridgeGuideDecision {
    if (muted || status !is BridgeStatus.Absent) return BridgeGuideDecision.Skip
    val askMute = cancelledCount > 0
    return when (trigger) {
        BridgeGuideTrigger.FIRST_CONNECT -> BridgeGuideDecision.Show(askMute)
        BridgeGuideTrigger.LAUNCH ->
            if (random < 0.30) BridgeGuideDecision.Show(askMute) else BridgeGuideDecision.Skip
    }
}

/** 按实例记录安装引导的取消次数与「不再提示」。 */
class BridgeGuideStore(context: Context) {
    private val prefs = context.getSharedPreferences("qwenpaw_bridge_guide", Context.MODE_PRIVATE)

    fun cancelledCount(instanceId: String): Int = prefs.getInt("count_$instanceId", 0)

    fun markCancelled(instanceId: String) {
        prefs.edit().putInt("count_$instanceId", cancelledCount(instanceId) + 1).apply()
    }

    fun muted(instanceId: String): Boolean = prefs.getBoolean("muted_$instanceId", false)

    fun mute(instanceId: String) {
        prefs.edit().putBoolean("muted_$instanceId", true).apply()
    }
}
