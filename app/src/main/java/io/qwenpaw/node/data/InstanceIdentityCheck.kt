package io.qwenpaw.node.data

import io.qwenpaw.node.data.local.InstanceIdentity

/** 身份比对结论。弱信号单独出现时一律为 [INCONCLUSIVE]。 */
enum class IdentityVerdict { SAME, DIFFERENT, INCONCLUSIVE }

/**
 * 比较两次连接读到的身份指纹。
 *
 * 只认强信号：**同一个 agent 的 workspace_dir 两边都非空且不同**。
 * 头像、agent 增删、认证开关变化都可能由正常操作引起，单独出现不下结论。
 * 只要有一项强一致，就不因为其他弱信号改判——同一实例里增删智能体很常见。
 */
fun compareIdentity(stored: InstanceIdentity?, current: InstanceIdentity?): IdentityVerdict {
    if (stored == null || current == null) return IdentityVerdict.INCONCLUSIVE
    val shared = stored.workspaces.keys.intersect(current.workspaces.keys)
    var strongSame = false
    var strongDifferent = false
    shared.forEach { id ->
        val a = stored.workspaces[id].orEmpty()
        val b = current.workspaces[id].orEmpty()
        if (a.isBlank() || b.isBlank()) return@forEach
        if (a == b) strongSame = true else strongDifferent = true
    }
    return when {
        strongDifferent && !strongSame -> IdentityVerdict.DIFFERENT
        strongSame -> IdentityVerdict.SAME
        else -> IdentityVerdict.INCONCLUSIVE
    }
}
