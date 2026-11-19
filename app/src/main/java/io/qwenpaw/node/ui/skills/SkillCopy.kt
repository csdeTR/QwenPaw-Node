package io.qwenpaw.node.ui.skills

import io.qwenpaw.node.R
import io.qwenpaw.node.ui.TimeLabels
import io.qwenpaw.node.ui.i18n.Texts

/**
 * 技能页会重复用到的几条文案与判定。
 *
 * 抽成纯函数而不是写在 Composable 里，是因为它们都有明确的输入输出、
 * 都能被 JVM 单测钉住：界面文案一旦分散在 Composable 里，改一处漏一处没人发现。
 */
internal object SkillCopy {

    /** 技能来源。实测只有 `customized` 与 `builtin` 两种，其余原样显示。 */
    private val SOURCE_LABELS = mapOf(
        "customized" to R.string.skillcopy_source_customized,
        "builtin" to R.string.skillcopy_source_builtin,
    )

    fun sourceLabel(source: String): String {
        SOURCE_LABELS[source]?.let { return Texts.get(it) }
        return source.ifBlank { Texts.get(R.string.skillcopy_source_unknown) }
    }

    /** 技能池的同步结论。空串表示服务端没给，不显示。 */
    private val SYNC_LABELS = mapOf(
        "synced" to R.string.skillcopy_sync_synced,
        "outdated" to R.string.skillcopy_sync_outdated,
    )

    fun syncStatusLabel(status: String): String {
        if (status.isEmpty()) return ""
        SYNC_LABELS[status]?.let { return Texts.get(it) }
        return status
    }

    /**
     * `SKILL.md` 缺少 frontmatter 时的提示，合法时返回 null。
     *
     * 服务端的校验是「frontmatter 里 `name` 与 `description` 都非空」
     * （`validate_skill_content`），缺了回 400 加一句英文 detail。
     * 这里在提交前给出同样的判据，让用户在自己写的正文里就能看到问题，
     * 而不是等一次往返之后从服务端的英文里猜。
     *
     * 只判断，不阻止提交：判据是简化版（不解析 YAML 嵌套与引号），
     * 真正的权威仍然是服务端。
     */
    fun frontmatterHint(content: String): String? {
        val text = content.trimStart()
        if (!text.startsWith("---")) {
            return Texts.get(R.string.skillcopy_hint_missing_block)
        }
        val body = text.removePrefix("---")
        val end = body.indexOf("\n---")
        if (end < 0) {
            return Texts.get(R.string.skillcopy_hint_unclosed)
        }
        val header = body.substring(0, end)
        val hasName = header.lineSequence().any { line ->
            line.trimStart().startsWith("name:") && line.substringAfter(':').isNotBlank()
        }
        val hasDescription = header.lineSequence().any { line ->
            line.trimStart().startsWith("description:") && line.substringAfter(':').isNotBlank()
        }
        return when {
            !hasName -> Texts.get(R.string.skillcopy_hint_missing_name)
            !hasDescription -> Texts.get(R.string.skillcopy_hint_missing_description)
            else -> null
        }
    }

    /**
     * 技能扫描拦截的说明。
     *
     * 服务端在启用与保存时重新扫描技能目录，命中安全策略回 422 且 body 是
     * `{type:"security_scan_failed", detail, max_severity, findings[]}`。
     * 直接把 `detail` 显示出来是一句英文，用户既看不懂也不知道该改什么。
     */
    fun scanBlockedText(detail: String?, maxSeverity: String?): String {
        val level = when (maxSeverity?.lowercase()) {
            "critical" -> Texts.get(R.string.skillcopy_severity_critical)
            "high" -> Texts.get(R.string.skillcopy_severity_high)
            "medium" -> Texts.get(R.string.skillcopy_severity_medium)
            "low" -> Texts.get(R.string.skillcopy_severity_low)
            else -> null
        }
        val head = if (level != null) {
            Texts.get(R.string.skillcopy_scan_blocked_level, level)
        } else {
            Texts.get(R.string.skillcopy_scan_blocked)
        }
        val tail = detail?.takeIf { it.isNotBlank() }?.let {
            Texts.get(R.string.skillcopy_scan_detail_suffix, it)
        }.orEmpty()
        return Texts.get(R.string.skillcopy_scan_footer, head, tail)
    }

    /**
     * `last_updated` 的展示。
     *
     * 实测是带 `Z` 的 ISO（`2026-11-01T01:08:08.599528Z`），按 UTC 折算本地。
     * **没有时区后缀的值一律原样显示**：把无时区的时间当成 UTC 折算，
     * 等于替服务端编造一个精度，在东八区会显示成晚 8 小时。
     */
    fun updatedLabel(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val hasZone = text.endsWith("Z") || text.endsWith("z") ||
            OFFSET_SUFFIX.containsMatchIn(text)
        if (!hasZone) return text
        return TimeLabels.parse(text)?.let {
            "%04d.%02d.%02d %02d:%02d".format(
                it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute,
            )
        } ?: text
    }

    /** 通道列表的展示。服务端默认 `["all"]`，表示全部通道。 */
    fun channelsLabel(channels: List<String>): String =
        if (channels.isEmpty()) {
            Texts.get(R.string.skillcopy_channels_unlimited)
        } else {
            channels.joinToString(Texts.get(R.string.skillcopy_channel_separator))
        }

    /** 结尾处带 `+08:00` / `-05:30` 这类偏移。 */
    private val OFFSET_SUFFIX = Regex("([+-]\\d{2}:?\\d{2})$")
}