package io.qwenpaw.node.ui

import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts

/**
 * 斜杠命令与工具的中文文案。
 *
 * 服务端只下发英文 `description`：`GET /api/workspace/commands/available` 实测 35 条里
 * 仅 11 条带描述，且全部为英文；`GET /api/tools` 的 33 个工具同样只有英文。
 * 服务端没有 i18n 接口，中文译文只存在于 Console 前端打包产物
 * （`qwenpaw/console/assets/zh-*.js`）里，那是浏览器端的资源，客户端取不到。
 *
 * 因此译文**内置在客户端**，按名字查表。这是一份静态数据，不做运行时合并：
 * 服务端新增命令或工具时，这里查不到就回退到服务端给的英文，界面不会开天窗。
 */
internal object CommandCopy {

    /**
     * 内部命令：维护与调试用，不进候选列表。
     *
     * 用黑名单而不是白名单，是因为服务端还会注册插件与技能提供的命令，
     * 白名单会把它们的命令一并挡掉。名单里的命令服务端仍在处理，
     * 只是不给用户看——它们的描述是给维护者读的。
     */
    private val internal = setOf(
        // 对话侧的程序化命令（服务端 command_handler.py 的注释同此判断）
        "auto_memory_status", "compact_str", "dump_history", "history",
        "load_history", "message", "proactive", "system_prompt",
        // daemon 子命令
        "daemon", "restart", "logs", "version", "reload-config", "reload_config",
        // 定时任务
        "cron-create", "cron-delete", "cron-list",
    )

    /** 命令名 → 描述文案的资源。覆盖服务端实测的全部 35 条。 */
    private val descriptions = mapOf(
        "approval" to R.string.cmd_approval_desc,
        "approve" to R.string.cmd_approve_desc,
        "auto_memory_status" to R.string.cmd_auto_memory_status_desc,
        "checkpoint" to R.string.cmd_checkpoint_desc,
        "clear" to R.string.cmd_clear_desc,
        "compact" to R.string.cmd_compact_desc,
        "compact_str" to R.string.cmd_compact_str_desc,
        "cron-create" to R.string.cmd_cron_create_desc,
        "cron-delete" to R.string.cmd_cron_delete_desc,
        "cron-list" to R.string.cmd_cron_list_desc,
        "daemon" to R.string.cmd_daemon_desc,
        "deny" to R.string.cmd_deny_desc,
        "dump_history" to R.string.cmd_dump_history_desc,
        "goal" to R.string.cmd_goal_desc,
        "history" to R.string.cmd_history_desc,
        "load_history" to R.string.cmd_load_history_desc,
        "logs" to R.string.cmd_logs_desc,
        "message" to R.string.cmd_message_desc,
        "mission" to R.string.cmd_mission_desc,
        "model" to R.string.cmd_model_desc,
        "new" to R.string.cmd_new_desc,
        "plan" to R.string.cmd_plan_desc,
        "proactive" to R.string.cmd_proactive_desc,
        "reload-config" to R.string.cmd_reload_config_desc,
        "reload_config" to R.string.cmd_reload_config_alias_desc,
        "reme" to R.string.cmd_reme_desc,
        "restart" to R.string.cmd_restart_desc,
        "skills" to R.string.cmd_skills_desc,
        // 服务端的 /status 走的是 daemon 分支，返回的是健康状态与配置，
        // 不是 Console 里那条「账户、模型和工作区」的文案。
        "status" to R.string.cmd_status_desc,
        "stop" to R.string.cmd_stop_desc,
        "system_prompt" to R.string.cmd_system_prompt_desc,
        "tool-bg" to R.string.cmd_tool_bg_desc,
        "tool-cancel" to R.string.cmd_tool_cancel_desc,
        "tools" to R.string.cmd_tools_desc,
        "version" to R.string.cmd_version_desc,
    )

    /** 分类名 → 资源。服务端下发的 `conversation` 译作「对话」，与界面用词一致。 */
    private val categories = mapOf(
        "control" to R.string.cmd_category_control,
        "conversation" to R.string.cmd_category_conversation,
        "builtin" to R.string.cmd_category_builtin,
        "daemon" to R.string.cmd_category_daemon,
        "auto" to R.string.cmd_category_auto,
        "skill" to R.string.cmd_category_skill,
        "user" to R.string.cmd_category_user,
    )

    fun description(name: String): String? = descriptions[name]?.let { Texts.get(it) }

    /**
     * 命令名 → 一行用法示例，显示在「魔法命令」的长按详情里。
     *
     * 只收面向用户的命令，参数写法取自官方命令文档；插件与技能注册的命令没有固定形态，
     * 这里不编造，详情里回退成 `/命令名`。
     */
    private val examples = mapOf(
        "approval" to "/approval list --all",
        "approve" to "/approve",
        "checkpoint" to "/checkpoint snapshot before-refactor",
        "clear" to "/clear",
        "deny" to "/deny",
        "model" to "/model openai:gpt-4o",
        "new" to "/new",
        "skills" to "/skills",
        "status" to "/status",
        "stop" to "/stop",
        "tool-bg" to "/tool-bg",
        "tool-cancel" to "/tool-cancel",
        "tools" to "/tools",
    )

    /** 示例里带中文参数的条目：整句进资源，避免把命令语法拆开翻译。 */
    private val exampleCopy = mapOf(
        "compact" to R.string.cmd_compact_example,
        "goal" to R.string.cmd_goal_example,
        "mission" to R.string.cmd_mission_example,
        "plan" to R.string.cmd_plan_example,
        "reme" to R.string.cmd_reme_example,
    )

    /**
     * 命令名 → 比列表灰字更具体的一句说明，只用在长按详情里。
     *
     * 与 [descriptions] 的差别在粒度：列表那行要能一眼扫过，这里可以补上
     * 「不写子命令会怎样」「有哪些子命令」「有什么副作用」这类信息。
     */
    private val details = mapOf(
        "approval" to R.string.cmd_approval_detail,
        "approve" to R.string.cmd_approve_detail,
        "checkpoint" to R.string.cmd_checkpoint_detail,
        "clear" to R.string.cmd_clear_detail,
        "compact" to R.string.cmd_compact_detail,
        "deny" to R.string.cmd_deny_detail,
        "goal" to R.string.cmd_goal_detail,
        "mission" to R.string.cmd_mission_detail,
        "model" to R.string.cmd_model_detail,
        "new" to R.string.cmd_new_detail,
        "plan" to R.string.cmd_plan_detail,
        "reme" to R.string.cmd_reme_detail,
        "skills" to R.string.cmd_skills_detail,
        "status" to R.string.cmd_status_detail,
        "stop" to R.string.cmd_stop_detail,
        "tool-bg" to R.string.cmd_tool_bg_detail,
        "tool-cancel" to R.string.cmd_tool_cancel_detail,
        "tools" to R.string.cmd_tools_detail,
    )

    fun example(name: String): String? =
        exampleCopy[name]?.let { Texts.get(it) } ?: examples[name]

    fun detail(name: String): String? = details[name]?.let { Texts.get(it) }

    /** 面向用户的命令名，按字典序。供「魔法命令」的完整性测试逐条核对。 */
    fun userFacingNames(): List<String> = descriptions.keys.filter { isUserFacing(it) }.sorted()

    /** 内部命令不参与补全。未知命令（插件 / 技能注册）保留。 */
    fun isUserFacing(name: String): Boolean = name !in internal

    fun internalNames(): Set<String> = internal

    fun category(raw: String): String = categories[raw]?.let { Texts.get(it) } ?: raw
}

/** 工具的中文名与描述。同样是从 Console 译文摘出来的静态表。 */
internal object ToolCopy {

    /**
     * 分类名 → 资源。
     *
     * 分组归属由 `ToolGrouping.kt` 按官方静态表决定，这里只负责把组键翻成中文。
     * Console 的分组键是 `files` / `web` / `media` / `agents` / `runtime` / `migration`，
     * 服务端下发的 `category` 实测为空（插件工具才有值），因此这里同时收了单数写法。
     */
    private val categories = mapOf(
        "files" to R.string.tool_category_files,
        "file" to R.string.tool_category_files,
        "web" to R.string.tool_category_web,
        "media" to R.string.tool_category_media,
        "agents" to R.string.tool_category_agents,
        "runtime" to R.string.tool_category_runtime,
        "migration" to R.string.tool_category_migration,
        "other" to R.string.tool_category_other,
    )

    /** 工具名 → (中文名, 中文描述) 的资源。未收录的工具返回 null，由界面回退到服务端原文。 */
    private val entries = mapOf(
        "execute_shell_command" to
            (R.string.tool_execute_shell_command_name to R.string.tool_execute_shell_command_desc),
        "read_file" to (R.string.tool_read_file_name to R.string.tool_read_file_desc),
        "write_file" to (R.string.tool_write_file_name to R.string.tool_write_file_desc),
        "edit_file" to (R.string.tool_edit_file_name to R.string.tool_edit_file_desc),
        "append_file" to (R.string.tool_append_file_name to R.string.tool_append_file_desc),
        "grep_search" to (R.string.tool_grep_search_name to R.string.tool_grep_search_desc),
        "glob_search" to (R.string.tool_glob_search_name to R.string.tool_glob_search_desc),
        "ast_search" to (R.string.tool_ast_search_name to R.string.tool_ast_search_desc),
        "desktop_screenshot" to
            (R.string.tool_desktop_screenshot_name to R.string.tool_desktop_screenshot_desc),
        "view_image" to (R.string.tool_view_image_name to R.string.tool_view_image_desc),
        "view_video" to (R.string.tool_view_video_name to R.string.tool_view_video_desc),
        // Console 的语言包没有这条，服务端却下发了；按 view_video 的句式补上。
        "view_audio" to (R.string.tool_view_audio_name to R.string.tool_view_audio_desc),
        "enhance_and_generate_image" to
            (R.string.tool_enhance_and_generate_image_name to
                R.string.tool_enhance_and_generate_image_desc),
        "web_search" to (R.string.tool_web_search_name to R.string.tool_web_search_desc),
        "web_fetch" to (R.string.tool_web_fetch_name to R.string.tool_web_fetch_desc),
        "browser" to (R.string.tool_browser_name to R.string.tool_browser_desc),
        "list_agents" to (R.string.tool_list_agents_name to R.string.tool_list_agents_desc),
        "chat_with_agent" to
            (R.string.tool_chat_with_agent_name to R.string.tool_chat_with_agent_desc),
        "submit_to_agent" to
            (R.string.tool_submit_to_agent_name to R.string.tool_submit_to_agent_desc),
        "check_agent_task" to
            (R.string.tool_check_agent_task_name to R.string.tool_check_agent_task_desc),
        "spawn_subagent" to
            (R.string.tool_spawn_subagent_name to R.string.tool_spawn_subagent_desc),
        "delegate_external_agent" to
            (R.string.tool_delegate_external_agent_name to
                R.string.tool_delegate_external_agent_desc),
        "send_file_to_user" to
            (R.string.tool_send_file_to_user_name to R.string.tool_send_file_to_user_desc),
        "get_current_time" to
            (R.string.tool_get_current_time_name to R.string.tool_get_current_time_desc),
        "set_user_timezone" to
            (R.string.tool_set_user_timezone_name to R.string.tool_set_user_timezone_desc),
        "get_token_usage" to
            (R.string.tool_get_token_usage_name to R.string.tool_get_token_usage_desc),
        "materialize_skill" to
            (R.string.tool_materialize_skill_name to R.string.tool_materialize_skill_desc),
        "run_tool_batch" to
            (R.string.tool_run_tool_batch_name to R.string.tool_run_tool_batch_desc),
        "activate_f1_exploration_mode" to
            (R.string.tool_activate_f1_exploration_mode_name to
                R.string.tool_activate_f1_exploration_mode_desc),
        "migration_compat_inspect" to
            (R.string.tool_migration_compat_inspect_name to
                R.string.tool_migration_compat_inspect_desc),
        "migration_compat_read_file" to
            (R.string.tool_migration_compat_read_file_name to
                R.string.tool_migration_compat_read_file_desc),
        "migration_compat_write_file" to
            (R.string.tool_migration_compat_write_file_name to
                R.string.tool_migration_compat_write_file_desc),
        "migration_compat_update" to
            (R.string.tool_migration_compat_update_name to
                R.string.tool_migration_compat_update_desc),
        "migration_compat_finalize" to
            (R.string.tool_migration_compat_finalize_name to
                R.string.tool_migration_compat_finalize_desc),
    )

    fun of(name: String): Pair<String, String>? = entries[name]?.let { (nameRes, descRes) ->
        Texts.get(nameRes) to Texts.get(descRes)
    }

    fun count(): Int = entries.size

    /** 空分类归到「未分类」；未知分类原样返回，插件可能自带新分组。 */
    fun category(raw: String): String =
        if (raw.isBlank()) Texts.get(R.string.tool_category_other)
        else categories[raw]?.let { Texts.get(it) } ?: raw
}
