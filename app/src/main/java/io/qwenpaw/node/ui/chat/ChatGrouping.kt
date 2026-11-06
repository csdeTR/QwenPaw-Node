package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatSpecDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 对话列表的分组方式。
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
 * 分组表头文字。
 *
 * 服务端内置分组的 `name` 是英文（Uncategorized / Scheduled tasks / Subagents），
 * 直接显示会让中文界面里夹三段英文。按 `kind` 映射而不是按 name：
 * name 可被修改，kind 是服务端固定的枚举值。
 * 自建分组没有 kind，继续用用户起的名字，为空时退回 id。
 */
internal fun groupTitle(group: ChatGroupDto): String = when (group.kind) {
    "default" -> "未分组"
    "cron" -> "定时任务对话"
    "subagents" -> "子智能体对话"
    else -> group.name.ifBlank { group.id }
}

/**
 * 把对话列表切成若干分区。
 *
 * 两条规则贯穿两种模式：
 *
 * 1. **置顶项单独成区并排最前。** 置顶的意义就是「永远在我手边」，
 *    若按时间桶散落到「近 7 天」里，等于没有置顶。
 * 2. **空区不输出。** 一个只有表头没有内容的分区只是噪声。
 */
object ChatGrouping {

    /**
     * 移回「未分组」时要发给服务端的 group_id。
     *
     * 服务端对 `group_id: null` 返回 400 `Unknown chat group: None`，必须给默认分组的 id。
     * 默认分组正常会出现在分组列表里，没拉到时按 `default` 兜底。
     */
    internal fun defaultGroupId(groups: List<ChatGroupDto>): String =
        groups.firstOrNull { it.kind == "default" }?.id ?: "default"

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
                // 时间缺失或解析失败的对话归入「更早」：总比凭空消失好。
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
     * 分组在「按来源」模式下的排序权重，数值越小越靠前。
     *
     * 网页版把定时任务与子智能体放在列表最底：它们由后台自动产生，
     * 不是用户当下要处理的对话。自建分组与默认分组属于用户自己的东西，排在前面。
     * `cron` 与 `subagents` 再细分为两档，保证两者的先后固定，
     * 不依赖服务端 `order` 字段的取值——实测该字段在不同服务器上并不一致。
     */
    internal fun rankOf(kind: String?): Int = when (kind) {
        CUSTOM_KIND -> 0
        "default" -> 1
        "cron" -> 3
        "subagents" -> 4
        // 其他内置分组排在未分组之前，与网页版的相对位置一致。
        else -> 2
    }

    /**
     * 分组表的展示顺序。
     *
     * 权重顺序：置顶分组 → 自建分组 → 默认分组 → 其他内置 → 定时任务 → 子智能体。
     * `order` 只在同一权重内生效，因此服务端调整 order 不会把定时任务顶到前面。
     * order 相同时保持列表原顺序——否则同一份数据两次渲染的顺序可能不同，
     * 用户会觉得列表在跳。
     */
    internal fun orderedGroups(groups: List<ChatGroupDto>): List<ChatGroupDto> =
        groups.withIndex().sortedWith(
            compareByDescending<IndexedValue<ChatGroupDto>> { it.value.pinned }
                .thenBy { rankOf(it.value.kind) }
                .thenBy { it.value.order }
                .thenBy { it.index },
        ).map { it.value }

    /**
     * 把 [groupId] 在**其所在的权重块内**上移或下移一位，返回新的完整 id 顺序。
     *
     * 三条规则：
     * - 只在**同一展示块**内交换：块的判据是「置顶与否 + `rankOf(kind)`」。
     *   跨块移动在界面上根本看不出来（展示顺序由这两个字段决定，交换 order 无效），
     *   把自建分组「上移」到定时任务之后，它还是排在前面，用户只会觉得点不动。
     * - 已在块内首/末位时原样返回，调用方据此判断「按钮该禁用」。
     * - 返回的数组包含**全部**分组（含内置）：`PUT groups/order` 是按传入顺序回写，
     *   漏掉一个就变成「未提及」，它的相对位置会变得不可预测。
     */
    internal fun moveGroup(
        groups: List<ChatGroupDto>,
        groupId: String,
        delta: Int,
        rank: (String?) -> Int = { rankOf(it) },
    ): List<String> {
        if (delta == 0) return groups.map { it.id }
        // 起点是展示顺序而不是原始数组顺序：用户看到的相邻关系才是他要调整的那个。
        val ordered = orderedGroups(groups)
        val index = ordered.indexOfFirst { it.id == groupId }
        if (index < 0) return ordered.map { it.id }
        val target = index + delta
        if (target !in ordered.indices) return ordered.map { it.id }
        val moving = ordered[index]
        val neighbour = ordered[target]
        if (moving.pinned != neighbour.pinned || rank(moving.kind) != rank(neighbour.kind)) {
            return ordered.map { it.id }
        }
        val swapped = ordered.toMutableList()
        val tmp = swapped[index]
        swapped[index] = swapped[target]
        swapped[target] = tmp
        return swapped.map { it.id }
    }

    /**
     * 按来源（对话分组）分桶。
     *
     * 分区顺序：置顶分组 → 自建分组 → 默认分组 → 其他内置 → 未分组 → 定时任务 → 子智能体。
     * `order` 只在同一权重内生效，因此服务端调整 order 不会把定时任务顶到前面。
     *
     * 分组名称为空时用 id 兜底：服务端内置组都有名字，自建组偶尔只回 id，
     * 显示空白表头会让用户以为是渲染错误。
     */
    fun bySource(
        chats: List<ChatSpecDto>,
        groups: List<ChatGroupDto>,
    ): List<ChatSection> {
        val (pinned, rest) = chats.partition { it.pinned }
        val byId = groups.associateBy { it.id }
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

        // 同一权重内按服务端 order 排；order 相同时保持列表原顺序，
        // 否则同一份数据两次渲染的顺序可能不同，用户会觉得列表在跳。
        val ordered = orderedGroups(groups)

        return buildList {
            if (pinned.isNotEmpty()) add(ChatSection(PINNED_KEY, PINNED_TITLE, pinned))
            var ungroupedPlaced = false
            ordered.forEach { group ->
                // 未分组紧跟在「其他内置分组」之后、定时任务之前：
                // 它属于用户自己产生的对话，不该被自动任务的表头压在下面。
                if (!ungroupedPlaced && unknown.isNotEmpty() && rankOf(group.kind) > 2) {
                    add(ChatSection(UNGROUPED_KEY, UNGROUPED_TITLE, unknown))
                    ungroupedPlaced = true
                }
                // 没有对话的组在 buckets 里根本没有键，这里补成空表而不是跳过：
                // 自建空分组要显示出来（用户刚建完，藏起来像失败），
                // 内置空分组由下面一行过滤掉。
                val items = buckets[group.id].orEmpty()
                // 内置分组（default / cron / subagents）为空时会造成一排空表头；
                // 但自建分组例外——用户刚新建的分组里必然还没有对话，
                // 把它藏起来会让人以为「建失败了」，也找不到该往哪个组里放。
                if (items.isEmpty() && group.kind != CUSTOM_KIND) return@forEach
                val title = groupTitle(group)
                add(ChatSection("g:${group.id}", title, items))
            }
            if (unknown.isNotEmpty() && !ungroupedPlaced) {
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
     * 取对话的活跃日期（本地时区）。
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