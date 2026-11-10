package io.qwenpaw.node.ui

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

    /** 命令名 → 简体中文描述。覆盖服务端实测的全部 35 条。 */
    private val descriptions = mapOf(
        "approval" to "审批待执行的工具操作：批准、拒绝、查看列表或取消",
        "approve" to "批准待执行的工具操作，等同于 /approval approve",
        "auto_memory_status" to "查看自动记忆任务的排队与执行状态",
        "checkpoint" to "查看与管理对话检查点（自动 / 时间线 / 快照 / 恢复 / 清理 / 重置）",
        "clear" to "清空对话上下文",
        "compact" to "压缩对话上下文并生成摘要，支持附带说明",
        "compact_str" to "查看最近一次压缩生成的摘要",
        "cron-create" to "创建或替换定时任务",
        "cron-delete" to "删除定时任务",
        "cron-list" to "列出全部定时任务",
        "daemon" to "服务端守护控制：重启、查看状态、版本与日志",
        "deny" to "拒绝待执行的工具操作，等同于 /approval deny",
        "dump_history" to "把当前对话导出为 JSONL 文件",
        "goal" to "设定目标，智能体持续执行直至完成",
        "history" to "查看当前对话的上下文占用与历史统计",
        "load_history" to "从导出的 JSONL 文件载入对话",
        "logs" to "查看服务端最近的项目日志",
        "message" to "查看指定序号的单条消息",
        "mission" to "启动任务模式，分解、执行并验证复杂任务",
        "model" to "查看或切换 AI 模型",
        "new" to "结束当前对话并开始新的对话",
        "plan" to "创建结构化计划，把任务分解为多个子任务",
        "proactive" to "开关主动消息功能",
        "reload-config" to "重新加载服务端配置，无需重启进程",
        "reload_config" to "重新加载服务端配置，等同于 /reload-config",
        "reme" to "运行并查看 ReMe 记忆操作",
        "restart" to "重启服务端的智能体运行时",
        "skills" to "列出当前对话可用的技能，并显示可显式调用的技能命令",
        // 服务端的 /status 走的是 daemon 分支，返回的是健康状态与配置，
        // 不是 Console 里那条「账户、模型和工作区」的文案。
        "status" to "查看服务端健康状态、运行配置与记忆管理器",
        "stop" to "停止当前正在执行的任务",
        "system_prompt" to "查看当前生效的系统提示词",
        "tool-bg" to "把正在执行的工具调用转入后台继续运行",
        "tool-cancel" to "取消正在执行的工具调用",
        "tools" to "列出当前对话中正在执行的工具调用",
        "version" to "查看服务端版本与关键路径",
    )

    /** 分类名 → 中文。服务端下发的 `conversation` 译作「对话」，与界面用词一致。 */
    private val categories = mapOf(
        "control" to "控制",
        "conversation" to "对话",
        "builtin" to "模式",
        "daemon" to "服务",
        "auto" to "自动",
        "skill" to "技能",
        "user" to "自定义",
    )

    fun description(name: String): String? = descriptions[name]

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
        "compact" to "/compact 保留需求与待办，去掉调试日志",
        "deny" to "/deny",
        "goal" to "/goal 补全输入区单元测试",
        "mission" to "/mission 修复登录失败",
        "model" to "/model openai:gpt-4o",
        "new" to "/new",
        "plan" to "/plan 把构建脚本迁到 Gradle 8",
        "reme" to "/reme search query=\"项目决策\" limit=5",
        "skills" to "/skills",
        "status" to "/status",
        "stop" to "/stop",
        "tool-bg" to "/tool-bg",
        "tool-cancel" to "/tool-cancel",
        "tools" to "/tools",
    )

    /**
     * 命令名 → 比列表灰字更具体的一句说明，只用在长按详情里。
     *
     * 与 [descriptions] 的差别在粒度：列表那行要能一眼扫过，这里可以补上
     * 「不写子命令会怎样」「有哪些子命令」「有什么副作用」这类信息。
     */
    private val details = mapOf(
        "approval" to "列出待审批的工具调用，并按编号批准、拒绝或取消。不写子命令时列出可用用法。",
        "approve" to "批准排队中的工具调用。有多条待审时用 /approval approve <编号> 指定其中一条。",
        "checkpoint" to "按快照、时间线、恢复、清理、重置等子命令管理检查点；不带子命令时列出可用子命令。",
        "clear" to "清空当前对话的历史与摘要，不写入长期记忆。该操作不可撤销。",
        "compact" to "立即压缩上下文并重写接续摘要，可附带一句说明要保留的内容。",
        "deny" to "拒绝排队中的工具调用，可附一句理由，智能体会据此调整做法。",
        "goal" to "把一段描述设为持续目标，智能体循环执行直到声明完成或失败。",
        "mission" to "按 master、worker、verifier 三层流水线拆解并验证复杂任务，适合有明确验收方式的工作。",
        "model" to "不带参数时显示当前模型与来源；带 <供应商>:<模型> 时切换默认模型，另有 list、info、reset 子命令。",
        "new" to "结束当前对话并新建一条，旧内容转为长期记忆。",
        "plan" to "把任务拆成结构化计划并逐步执行，适合步骤较多的改动。",
        "reme" to "调用聊天中允许的 ReMe 记忆动作，例如用 search 按关键词检索记忆。",
        "skills" to "列出当前对话可用的技能。技能名本身也能作为命令直接调用。",
        "status" to "查看服务端健康状态、运行配置与记忆管理器状态。",
        "stop" to "立即终止当前对话中正在运行的任务。",
        "tool-bg" to "把正在执行的工具调用转入后台继续运行，先让对话往下走。",
        "tool-cancel" to "取消正在执行的工具调用，已经产生的输出会被丢弃。",
        "tools" to "列出当前对话中正在运行的工具调用及其进度。",
    )

    fun example(name: String): String? = examples[name]

    fun detail(name: String): String? = details[name]

    /** 面向用户的命令名，按字典序。供「魔法命令」的完整性测试逐条核对。 */
    fun userFacingNames(): List<String> = descriptions.keys.filter { isUserFacing(it) }.sorted()

    /** 内部命令不参与补全。未知命令（插件 / 技能注册）保留。 */
    fun isUserFacing(name: String): Boolean = name !in internal

    fun internalNames(): Set<String> = internal

    fun category(raw: String): String = categories[raw] ?: raw
}

/** 工具的中文名与描述。同样是从 Console 译文摘出来的静态表。 */
internal object ToolCopy {

    /**
     * 分类名 → 中文。
     *
     * 分组归属由 `ToolGrouping.kt` 按官方静态表决定，这里只负责把组键翻成中文。
     * Console 的分组键是 `files` / `web` / `media` / `agents` / `runtime` / `migration`，
     * 服务端下发的 `category` 实测为空（插件工具才有值），因此这里同时收了单数写法。
     */
    private val categories = mapOf(
        "files" to "文件与代码",
        "file" to "文件与代码",
        "web" to "网页与浏览器",
        "media" to "图像与视频",
        "agents" to "智能体协作",
        "runtime" to "运行与自动化",
        "migration" to "应用迁移",
        "other" to "未分类",
    )

    /** 工具名 → (中文名, 中文描述)。未收录的工具返回 null，由界面回退到服务端原文。 */
    private val entries = mapOf(
        "execute_shell_command" to
            ("执行命令" to "运行终端命令，完成系统操作与脚本任务。"),
        "read_file" to ("读取文件" to "读取文本文件，查看内容或指定行。"),
        "write_file" to ("写入文件" to "创建文件，或写入新的文件内容。"),
        "edit_file" to ("编辑文件" to "查找并替换文件中的指定内容。"),
        "append_file" to ("追加内容" to "在文件末尾添加文本，保留已有内容。"),
        "grep_search" to ("搜索文件内容" to "按关键词或模式查找文件中的文本。"),
        "glob_search" to ("查找文件" to "按文件名或路径模式定位文件。"),
        "ast_search" to ("搜索代码结构" to "按语法结构查找代码中的匹配片段。"),
        "desktop_screenshot" to ("桌面截图" to "截取当前桌面，供智能体查看和分析。"),
        "view_image" to ("查看图片" to "读取图片，让模型理解画面内容。"),
        "view_video" to ("查看视频" to "读取视频，让模型分析视频内容。"),
        // Console 的语言包没有这条，服务端却下发了；按 view_video 的句式补上。
        "view_audio" to ("查看音频" to "读取音频，让模型转录或分析音频内容。"),
        "enhance_and_generate_image" to
            ("增强并生成图片" to "完善图片描述，再生成所需画面。"),
        "web_search" to ("搜索网页" to "搜索互联网，获取最新信息与来源。"),
        "web_fetch" to ("读取网页" to "提取指定网页的内容，供阅读和分析。"),
        "browser" to ("浏览器操作" to "浏览网页，并与页面上的内容和控件交互。"),
        "list_agents" to ("查看智能体" to "列出可用智能体，了解可协作的对象。"),
        "chat_with_agent" to
            ("与智能体对话" to "向另一个智能体发送消息并获取回复。"),
        "submit_to_agent" to ("委派后台任务" to "将任务交给其他智能体在后台执行。"),
        "check_agent_task" to
            ("查看任务进度" to "查询已委派任务的运行状态与结果。"),
        "spawn_subagent" to ("创建子任务" to "启动临时子智能体，处理独立的工作。"),
        "delegate_external_agent" to
            ("委派外部智能体" to "将工作交给已配置的外部智能体。"),
        "send_file_to_user" to
            ("发送文件" to "将文件发送给当前对话中的用户。"),
        "get_current_time" to ("获取当前时间" to "查询当前日期和时间。"),
        "set_user_timezone" to ("设置时区" to "设置用户时区，用于时间相关任务。"),
        "get_token_usage" to ("查看 Token 用量" to "查询模型调用的 Token 消耗。"),
        "materialize_skill" to ("部署技能" to "将技能定义写入当前工作区。"),
        "run_tool_batch" to
            ("批量执行工具" to "按 JSON 文件中的定义执行一组工具调用。"),
        "activate_f1_exploration_mode" to
            ("邮件审批探索" to "进入逐步处理邮件审批的探索模式。"),
        "migration_compat_inspect" to
            ("检查迁移资源" to "检查暂存资源与当前 QwenPaw 支持的能力。"),
        "migration_compat_read_file" to
            ("读取迁移文件" to "分页读取暂存资源中的文件内容。"),
        "migration_compat_write_file" to
            ("写入迁移文件" to "创建或覆盖待修复资源中的文本文件。"),
        "migration_compat_update" to
            ("更新迁移配置" to "修改允许更新的 MCP 或定时任务字段。"),
        "migration_compat_finalize" to
            ("验证并完成迁移" to "运行最终测试，通过后启用修复完成的资源。"),
    )

    fun of(name: String): Pair<String, String>? = entries[name]

    fun count(): Int = entries.size

    /** 空分类归到「未分类」；未知分类原样返回，插件可能自带新分组。 */
    fun category(raw: String): String =
        if (raw.isBlank()) "未分类" else categories[raw] ?: raw
}