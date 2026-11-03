package io.qwenpaw.node.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import io.qwenpaw.node.data.model.ToolDto
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 工具的分组与图标，取自官方控制台的 `toolPresentation-COFtiO7I.js`。
 *
 * 官方并不按服务端下发的 `category` 分组：内置工具的 `category` 实测为 `null`，
 * 照它分组会把 33 个工具全部塞进同一组。官方的做法是内置工具查静态表
 * [TOOL_GROUP_OF]，只有插件工具（`source_plugin_id` 非空）才用 `category`。
 *
 * 组顺序是固定的七组，未分类排最后；官方 `TOOL_GROUPS` 即这份顺序。
 */
internal val TOOL_GROUP_ORDER = listOf(
    "files", "web", "media", "agents", "runtime", "migration", "other",
)

/** 服务端 `category` 不在 [TOOL_GROUP_ORDER] 里时的归属。 */
internal const val TOOL_GROUP_OTHER = "other"

/** 工具名 → 官方分组。与 `TOOL_PRESENTATION` 的 `group` 字段逐条对应。 */
internal val TOOL_GROUP_OF: Map<String, String> = mapOf(
    "execute_shell_command" to "runtime",
    "read_file" to "files",
    "write_file" to "files",
    "edit_file" to "files",
    "append_file" to "files",
    "grep_search" to "files",
    "glob_search" to "files",
    "ast_search" to "files",
    "desktop_screenshot" to "media",
    "view_image" to "media",
    "view_video" to "media",
    "enhance_and_generate_image" to "media",
    "web_search" to "web",
    "web_fetch" to "web",
    "browser" to "web",
    "list_agents" to "agents",
    "chat_with_agent" to "agents",
    "submit_to_agent" to "agents",
    "check_agent_task" to "agents",
    "spawn_subagent" to "agents",
    "delegate_external_agent" to "agents",
    "send_file_to_user" to "files",
    "get_current_time" to "runtime",
    "set_user_timezone" to "runtime",
    "get_token_usage" to "runtime",
    "materialize_skill" to "runtime",
    "run_tool_batch" to "runtime",
    "activate_f1_exploration_mode" to "runtime",
    "migration_compat_inspect" to "migration",
    "migration_compat_read_file" to "migration",
    "migration_compat_write_file" to "migration",
    "migration_compat_update" to "migration",
    "migration_compat_finalize" to "migration",
    // 服务端下发但官方分组表未收录，按语义归入运行类，与 view_video 同组。
    "view_audio" to "media",
)

/**
 * 工具名 → 图标，逐个还原自官方 `ui-vendor` 里 `TOOL_PRESENTATION` 引用的 lucide 组件。
 *
 * 未收录的工具返回 null，由界面回退到通用的 [QwenIcons.wrench]。
 */
internal val TOOL_ICONS: Map<String, ImageVector> = mapOf(
    "execute_shell_command" to QwenIcons.terminal,
    "read_file" to QwenIcons.fileText,
    "write_file" to QwenIcons.filePlus,
    "edit_file" to QwenIcons.filePen,
    "append_file" to QwenIcons.filePlus,
    "grep_search" to QwenIcons.search,
    "glob_search" to QwenIcons.folderSearch,
    "ast_search" to QwenIcons.scanSearch,
    "desktop_screenshot" to QwenIcons.monitor,
    "view_image" to QwenIcons.image,
    "view_video" to QwenIcons.volume,
    "enhance_and_generate_image" to QwenIcons.sparkles,
    "web_search" to QwenIcons.search,
    "web_fetch" to QwenIcons.globe,
    "browser" to QwenIcons.panelsTopLeft,
    "list_agents" to QwenIcons.users,
    "chat_with_agent" to QwenIcons.messagesSquare,
    "submit_to_agent" to QwenIcons.sendPlane,
    "check_agent_task" to QwenIcons.listChecks,
    "spawn_subagent" to QwenIcons.workflow,
    "delegate_external_agent" to QwenIcons.blocks,
    "send_file_to_user" to QwenIcons.sendPlane,
    "get_current_time" to QwenIcons.clock,
    "set_user_timezone" to QwenIcons.globe,
    "get_token_usage" to QwenIcons.gauge,
    "materialize_skill" to QwenIcons.layers,
    "run_tool_batch" to QwenIcons.listChecks,
    "activate_f1_exploration_mode" to QwenIcons.mailCheck,
    "migration_compat_inspect" to QwenIcons.refresh,
    "migration_compat_read_file" to QwenIcons.fileText,
    "migration_compat_write_file" to QwenIcons.filePen,
    "migration_compat_update" to QwenIcons.checkCheck,
    "migration_compat_finalize" to QwenIcons.checkCheck,
    "view_audio" to QwenIcons.volume,
)

/** 工具没有对应图标时的兜底，与设置页其它「工具」入口一致。 */
internal fun toolIcon(tool: ToolDto): ImageVector = TOOL_ICONS[tool.name] ?: QwenIcons.wrench

/** 分组归属，规则与官方 `toolGroup(e)` 一致。 */
internal fun toolGroup(tool: ToolDto): String {
    if (!tool.sourcePluginId.isNullOrBlank()) {
        val category = tool.category
        return if (category != null && category in TOOL_GROUP_ORDER) category else TOOL_GROUP_OTHER
    }
    return TOOL_GROUP_OF[tool.name] ?: TOOL_GROUP_OTHER
}

/**
 * 按官方顺序分组，空组不输出，组内保持服务端顺序。
 *
 * 返回的是组键而不是中文组名：组名映射在 [io.qwenpaw.node.ui.ToolCopy.category]。
 */
internal fun groupTools(tools: List<ToolDto>): List<Pair<String, List<ToolDto>>> {
    val byGroup = tools.groupBy { toolGroup(it) }
    return TOOL_GROUP_ORDER.mapNotNull { key ->
        byGroup[key]?.let { key to it }
    }
}
