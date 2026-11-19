package io.qwenpaw.node.ui.workspace

import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts
import java.util.Locale

/**
 * 下载前的流量提示规则。
 *
 * 抽成纯对象是为了能单测边界：100 MB 的判定、移动数据与 VPN 的组合、
 * 以及「体积未知」时仍要按网络类型提示。
 */
internal object DownloadPolicy {

    /** 超过这个体积就提示，无论当前是不是计费网络。 */
    const val LARGE_FILE_BYTES = 100L * 1024 * 1024

    /**
     * 是否需要弹确认框。
     *
     * 体积未知（`null`）不等于小文件：目录还没枚举完时就会走到这里，
     * 此时只按网络类型判断，不能因为不知道大小就跳过提示。
     */
    fun needsWarning(sizeBytes: Long?, metered: Boolean, vpn: Boolean): Boolean =
        metered || vpn || (sizeBytes != null && sizeBytes >= LARGE_FILE_BYTES)

    /**
     * 确认框正文；不需要提示时返回 null。
     *
     * [fileCount] 为 null 表示单文件；目录传入文件数，用于说明这是整包下载。
     */
    fun warning(
        sizeBytes: Long?,
        fileCount: Int? = null,
        metered: Boolean,
        vpn: Boolean,
    ): String? {
        if (!needsWarning(sizeBytes, metered, vpn)) return null
        val lines = mutableListOf<String>()
        val subject = if (fileCount != null) {
            Texts.quantity(
                R.plurals.workspace_download_subject_directory,
                fileCount,
                fileCount,
            )
        } else {
            Texts.get(R.string.workspace_download_subject_file)
        }
        if (sizeBytes != null && sizeBytes >= LARGE_FILE_BYTES) {
            lines += Texts.get(
                R.string.workspace_download_warning_large,
                subject,
                formatBytes(sizeBytes),
            )
        } else if (fileCount != null && sizeBytes != null) {
            lines += Texts.get(
                R.string.workspace_download_warning_total,
                subject,
                formatBytes(sizeBytes),
            )
        } else if (fileCount != null) {
            lines += Texts.get(R.string.workspace_download_warning_unknown, subject)
        }
        if (metered) {
            lines += Texts.get(R.string.workspace_download_warning_metered)
        }
        if (vpn) {
            lines += Texts.get(R.string.workspace_download_warning_vpn)
        }
        return lines.joinToString("\n")
    }

    /** 128.4 MB 这类展示文本；只保留一位小数，B / KB 取整。 */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
