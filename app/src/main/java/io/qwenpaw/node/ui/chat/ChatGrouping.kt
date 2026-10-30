package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatSpecDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 会话列表的分组方式。
 *
 * 三档与 QwenPaw 网页版一致：按时间、按来源、不分组。
 * 默认按时间，与网页版默认相同。
 */
enum class GroupMode {
    TIME,
    SOURCE,
    NONE,
    ;

    /** 单击循环顺序：时间 → 来源 → 不分组 → 时间。 */
    fun next(): GroupMode = when (this) {
        TIME -> SOURCE
        SOURCE -> NONE
        NONE -> TIME
    }

    val label: String
        get() = when (this) {
            TIME -> "按时间"
            SOURCE -> "按来源"
            NONE -> "不分组"
        }
}

/** 列表里的一个分区。 */
data class ChatSection(val key: String, val title: String, val chats: List<ChatSpecDto>)

/** 置顶区的 key 与标题，两种分组模式下都用同一份。 */
private const val PINNED_KEY = "pinned"
private const val PINNED_TITLE = "置顶"
private const val UNGROUPED_KEY = "ungrouped"
private const val UNGROUPED_TITLE = "未分组"

/** 自建分组的 `kind` 值。内置分组是 `default` / `cron` / `subagents`。 */
internal const val CUSTOM_KIND = "custom"

/**
 * 把会话列表切成若干分区。
 *
 * 两条规则贯穿两种模式：
 *
 * 1. **置顶项单独成区并排最前。** 置顶的意义就是「永远在我手边」，
 *    若按时间桶散落到「近 7 天」里，等于没有置顶。
 * 2. **空区不输出。** 一个只有表头没有内容的分区只是噪声。
 */
object ChatGrouping {

    /**
     * 按时间分桶。
     *
     * 分界按**本地时区**的自然日计算：服务端给的是 UTC 时间，
     * 直接截字符串会把本地凌晨的消息算到前一天。
     *
     * 三档与网页版一致：今天、近 7 天、更早。
     */
    fun byTime(
        chats: List<ChatSpecDto>,
        now: LocalDate = LocalDate.now(),
    ): List<ChatSection> {
        val (pinned, rest) = chats.partition { it.pinned }
        val today = mutableListOf<ChatSpecDto>()
        val recent = mutableListOf<ChatSpecDto>()
        val earlier = mutableListOf<ChatSpecDto>()
        rest.forEach { chat ->
            val date = dateOf(chat)
            val days = if (date == null) Long.MAX_VALUE else ChronoUnit.DAYS.between(date, now)
            when {
                days == 0L -> today += chat
                days in 1..6 -> recent += chat
                // 时间缺失或解析失败的会话归入「更早」：总比凭空消失好。
                else -> earlier += chat
            }
        }
        return buildList {
            if (pinned.isNotEmpty()) add(ChatSection(PINNED_KEY, PINNED_TITLE, pinned))
            if (today.isNotEmpty()) add(ChatSection("today", "今天", today))
            if (recent.isNotEmpty()) add(ChatSection("recent", "近 7 天", recent))
            if (earlier.isNotEmpty()) add(ChatSection("earlier", "更早", earlier))
        }
    }

    /**
     * 按来源（会话分组）分桶。
     *
     * 分区顺序取自服务端 `/api/chats/groups` 的 `order`，置顶分组优先；
     * 找不到分组的会话归入「未分组」并排在最后。
     *
     * 分组名称为空时用 id 兜底：服务端内置组都有名字，自建组偶尔只回 id，
     * 显示空白表头会让用户以为是渲染错误。
     */
    fun bySource(
        chats: List<ChatSpecDto>,
        groups: List<ChatGroupDto>,
    ): List<ChatSection> {
        val (pinned, rest) = chats.partition { it.pinned }
        val ordered = groups.sortedWith(
            compareByDescending<ChatGroupDto> { it.pinned }.thenBy { it.order },
        )
        val byId = ordered.associateBy { it.id }
        val buckets = linkedMapOf<String, MutableList<ChatSpecDto>>()
        val unknown = mutableListOf<ChatSpecDto>()
        rest.forEach { chat ->
            val groupId = chat.groupId
            if (groupId.isNullOrBlank() || groupId !in byId) {
                unknown += chat
            } else {
                buckets.getOrPut(groupId) { mutableListOf() } += chat
            }
        }
        return buildList {
            if (pinned.isNotEmpty()) add(ChatSection(PINNED_KEY, PINNED_TITLE, pinned))
            ordered.forEach { group ->
                // 没有会话的组在 buckets 里根本没有键，这里补成空表而不是跳过：
                // 自建空分组要显示出来（用户刚建完，藏起来像失败），
                // 内置空分组由下面一行过滤掉。
                val items = buckets[group.id].orEmpty()
                // 内置分组（default / cron / subagents）为空时会造成一排空表头；
                // 但自建分组例外——用户刚新建的分组里必然还没有会话，
                // 把它藏起来会让人以为「建失败了」，也找不到该往哪个组里放。
                if (items.isEmpty() && group.kind != CUSTOM_KIND) return@forEach
                val title = group.name.ifBlank { group.id }
                add(ChatSection("g:${group.id}", title, items))
            }
            if (unknown.isNotEmpty()) {
                add(ChatSection(UNGROUPED_KEY, UNGROUPED_TITLE, unknown))
            }
        }
    }

    /** 按模式派发。不分组时返回单个无标题分区，调用方据此隐藏表头。 */
    fun sections(
        chats: List<ChatSpecDto>,
        mode: GroupMode,
        groups: List<ChatGroupDto> = emptyList(),
        now: LocalDate = LocalDate.now(),
    ): List<ChatSection> = when (mode) {
        GroupMode.TIME -> byTime(chats, now)
        GroupMode.SOURCE -> bySource(chats, groups)
        GroupMode.NONE -> if (chats.isEmpty()) {
            emptyList()
        } else {
            listOf(ChatSection("all", "", chats))
        }
    }

    /**
     * 折叠后仍然可见的分区。
     *
     * 折叠**只隐藏条目，保留表头**——用户要的正是「看得见有个分组，只是不想看里面的内容」。
     * 整体删掉会让分组凭空消失，反而看不出折叠了什么。
     *
     * 折叠集合里存在但本次列表里没有的 key 会被自然忽略，因此不必在切换分组方式时
     * 清理集合；用户切回「按来源」时之前的折叠状态还在，这是预期行为。
     */
    fun collapse(
        sections: List<ChatSection>,
        collapsed: Set<String>,
    ): List<ChatSection> = if (collapsed.isEmpty()) {
        sections
    } else {
        sections.map { section ->
            if (section.key in collapsed) section.copy(chats = emptyList()) else section
        }
    }

    /**
     * 取会话的活跃日期（本地时区）。
     *
     * 优先用 `updated_at`，缺失时退到 `last_finished_at`、`created_at`。
     * 解析失败返回 null，由调用方归入「更早」。
     */
    internal fun dateOf(chat: ChatSpecDto): LocalDate? {
        val raw = chat.updatedAt ?: chat.lastFinishedAt ?: chat.createdAt ?: return null
        return runCatching {
            Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate()
        }.recoverCatching {
            Instant.parse(raw + "Z").atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
    }
}