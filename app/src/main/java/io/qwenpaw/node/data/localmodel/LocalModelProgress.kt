package io.qwenpaw.node.data.localmodel

import androidx.annotation.StringRes
import io.qwenpaw.node.R

/**
 * 下载进度比例。
 *
 * 服务端不返回百分比，只给字节数；`total_bytes` 在大小尚未探明时是 null。
 * 返回 null 表示「进度未知」，界面应改用不确定进度条，而不是显示 0%。
 */
fun progressFraction(downloaded: Long, total: Long?): Float? {
    if (total == null || total <= 0L) return null
    if (downloaded <= 0L) return 0f
    return (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/**
 * 是否处于进行中的下载。
 *
 * `canceling` 也算进行中：服务端只把状态置为取消中，要等轮询到 `cancelled` 才真正结束。
 */
fun isActiveDownload(status: String): Boolean =
    status.trim().lowercase() in setOf("pending", "downloading", "canceling")

/** 下载状态对应的界面文案。未知状态单独给一句，不冒充「空闲」。 */
@StringRes
fun progressLabelRes(status: String): Int = when (status.trim().lowercase()) {
    "idle" -> R.string.local_models_status_idle
    "pending" -> R.string.local_models_status_pending
    "downloading" -> R.string.local_models_status_downloading
    "canceling" -> R.string.local_models_status_canceling
    "completed" -> R.string.local_models_status_completed
    "failed" -> R.string.local_models_status_failed
    "cancelled" -> R.string.local_models_status_cancelled
    else -> R.string.local_models_status_unknown
}