package io.qwenpaw.node.ui.chat

import io.qwenpaw.node.data.model.SlashCommandDto

/**
 * 斜杠命令补全的纯逻辑。
 *
 * 触发判据与服务端 `SlashCommandRegistry.resolve` 对齐：只有**整条草稿以 `/` 开头**、
 * 且名字部分不含空白时才是一次命令输入。正文中间的 `/` 不是命令，
 * 名字后一旦出现空格就进入参数阶段，候选继续弹出只会挡住视线。
 */

/** 从草稿里取出正在输入的命令名（不含 `/`）；不在输入命令时返回 null。 */
internal fun slashToken(draft: String): String? {
    if (!draft.startsWith("/")) return null
    val body = draft.substring(1)
    if (body.any { it.isWhitespace() }) return null
    return body
}

/** 按前缀过滤候选。`token` 为空（只敲了 `/`）时返回全部。 */
internal fun visibleCommands(
    commands: List<SlashCommandDto>,
    token: String,
): List<SlashCommandDto> {
    val usable = commands.filter { it.name.isNotBlank() }
    if (token.isEmpty()) return usable
    return usable.filter { it.name.startsWith(token, ignoreCase = true) }
}

/**
 * 按分类分组，保持服务端顺序。
 *
 * 服务端已按名字排序，这里不做二次排序；分类为空的命令归到空串分组，
 * 由界面决定是否给它画标题。空分组不产生条目。
 */
internal fun groupByCategory(commands: List<SlashCommandDto>): List<Pair<String, List<SlashCommandDto>>> {
    val grouped = LinkedHashMap<String, MutableList<SlashCommandDto>>()
    commands.filter { it.name.isNotBlank() }.forEach { cmd ->
        grouped.getOrPut(cmd.category) { mutableListOf() }.add(cmd)
    }
    return grouped.map { (category, list) -> category to list.toList() }
}

/** 点击候选后写回输入框的内容：带尾随空格，方便直接接参数，同时让候选收起。 */
internal fun commandInsertion(command: SlashCommandDto): String = "/${command.name} "