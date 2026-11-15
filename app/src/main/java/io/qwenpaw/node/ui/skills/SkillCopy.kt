package io.qwenpaw.node.ui.skills

import io.qwenpaw.node.ui.TimeLabels

/**
 * 技能页会重复用到的几条文案与判定。
 *
 * 抽成纯函数而不是写在 Composable 里，是因为它们都有明确的输入输出、
 * 都能被 JVM 单测钉住：界面文案一旦分散在 Composable 里，改一处漏一处没人发现。
 */
internal object SkillCopy {

    /** 技能来源。实测只有 `customized` 与 `builtin` 两种，其余原样显示。 */
    fun sourceLabel(source: String): String = when (source) {
        "customized" -> "自定义"
        "builtin" -> "内置"
        else -> source.ifBlank { "未知来源" }
    }

    /** 技能池的同步结论。空串表示服务端没给，不显示。 */
    fun syncStatusLabel(status: String): String = when (status) {
        "synced" -> "已同步"
        "outdated" -> "待更新"
        "" -> ""
        else -> status
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
            return "正文开头需要一段 frontmatter，写明 name 与 description 两项"
        }
        val body = text.removePrefix("---")
        val end = body.indexOf("\n---")
        if (end < 0) {
            return "frontmatter 没有闭合，需要在末尾补一行 ---"
        }
        val header = body.substring(0, end)
        val hasName = header.lineSequence().any { line ->
            line.trimStart().startsWith("name:") && line.substringAfter(':').isNotBlank()
        }
        val hasDescription = header.lineSequence().any { line ->
            line.trimStart().startsWith("description:") && line.substringAfter(':').isNotBlank()
        }
        return when {
            !hasName -> "frontmatter 缺少 name，服务端会拒绝保存"
            !hasDescription -> "frontmatter 缺少 description，服务端会拒绝保存"
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
            "critical" -> "严重"
            "high" -> "高"
            "medium" -> "中"
            "low" -> "低"
            else -> null
        }
        val head = if (level != null) "技能扫描发现${level}风险，操作已被拦截" else "技能扫描未通过，操作已被拦截"
        val tail = detail?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        return "$head$tail。请修改技能内容，或先在 Web 控制台确认扫描规则。"
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
        if (channels.isEmpty()) "未限定" else channels.joinToString("、")

    /** 结尾处带 `+08:00` / `-05:30` 这类偏移。 */
    private val OFFSET_SUFFIX = Regex("([+-]\\d{2}:?\\d{2})$")
}