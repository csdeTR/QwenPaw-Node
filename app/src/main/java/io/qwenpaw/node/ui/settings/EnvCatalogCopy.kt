package io.qwenpaw.node.ui.settings

/**
 * 内置环境变量的说明与修改方式，用于只读项的详情弹窗。
 *
 * 官方语言包只为 `QWENPAW_LLM_STREAM_FIRST_CONTENT_TIMEOUT` 与
 * `QWENPAW_LLM_STREAM_IDLE_TIMEOUT` 提供了描述，其余条目按服务端源码含义补写：
 * 读取位置见 `qwenpaw/constant.py` 与 `qwenpaw/config/config.py`。
 * 因此这里的文案属于客户端自撰，不是官方译文的摘录。
 *
 * [howToModify] 分两种：位于 `_PROTECTED_BOOTSTRAP_KEYS` 的目录两项即使落盘，
 * 重启时也会被 `store.py:362` 跳过，只能靠启动时的进程环境变量；其余项虽然不能在
 * 运行期生效，但可以落盘，重启后会被读到。
 */
internal data class EnvSpecCopy(
    val description: String,
    val howToModify: String,
)

private const val STARTUP_ONLY_HINT = "启动之后才会被读取，修改后需要重启 QwenPaw 才能生效。"
private const val BOOTSTRAP_HINT = "只能通过启动 QwenPaw 时的进程环境变量设置，客户端不能写入。"

/** 键 → 说明。只收 `GET /api/envs/catalog` 会下发的内置项。 */
internal val ENV_SPEC_COPY: Map<String, EnvSpecCopy> = mapOf(
    "QWENPAW_LLM_STREAM_FIRST_CONTENT_TIMEOUT" to EnvSpecCopy(
        description = "等待首个有效流式内容的最长时间；设为 0 可关闭检测。",
        howToModify = "修改后下一次调用即生效，已运行的 MCP 需要重新加载。",
    ),
    "QWENPAW_LLM_STREAM_IDLE_TIMEOUT" to EnvSpecCopy(
        description = "两个有效流式内容之间的最长等待时间；设为 0 可关闭检测。",
        howToModify = "修改后下一次调用即生效，已运行的 MCP 需要重新加载。",
    ),
    "QWENPAW_LLM_MAX_RETRIES" to EnvSpecCopy(
        description = "模型调用遇到可重试错误时的最大重试次数；设为 0 表示不重试。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_BACKOFF_BASE" to EnvSpecCopy(
        description = "重试退避的起始等待时间，单位为秒。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_BACKOFF_CAP" to EnvSpecCopy(
        description = "重试退避的等待上限，单位为秒，不能小于起始等待时间。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_MAX_CONCURRENT" to EnvSpecCopy(
        description = "同时在途的模型调用上限，超出部分排队等待。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_MAX_QPM" to EnvSpecCopy(
        description = "每分钟允许的模型调用次数，超出后排队；设为 0 表示不限制。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_RATE_LIMIT_PAUSE" to EnvSpecCopy(
        description = "收到限流响应后默认暂停的时长，单位为秒。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_RATE_LIMIT_JITTER" to EnvSpecCopy(
        description = "暂停时长上额外叠加的随机等待范围，用于错开并发请求的唤醒时间。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_ACQUIRE_TIMEOUT" to EnvSpecCopy(
        description = "等待并发配额的最长时间，单位为秒，超时后本次调用报错。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_WORKING_DIR" to EnvSpecCopy(
        description = "QwenPaw 的数据目录，会话、记忆与配置文件都存放在这里。",
        howToModify = BOOTSTRAP_HINT,
    ),
    "QWENPAW_SECRET_DIR" to EnvSpecCopy(
        description = "密钥目录，保存加密后的凭据与访问令牌。",
        howToModify = BOOTSTRAP_HINT,
    ),
    "QWENPAW_CONFIG_FILE" to EnvSpecCopy(
        description = "配置文件的位置，相对于数据目录。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_JOBS_FILE" to EnvSpecCopy(
        description = "定时任务列表的文件名，相对于数据目录。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_CHATS_FILE" to EnvSpecCopy(
        description = "会话记录的文件名，相对于数据目录。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_OPENAPI_DOCS" to EnvSpecCopy(
        description = "是否开放接口文档页，取值 true 或 false。",
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_RUNNING_IN_CONTAINER" to EnvSpecCopy(
        description = "是否运行在容器内，取值 true 或 false，影响密钥的存放方式。",
        howToModify = STARTUP_ONLY_HINT,
    ),
)

internal fun envSpecCopy(key: String): EnvSpecCopy? = ENV_SPEC_COPY[key]

/**
 * 为什么这一项只读，取自 catalog 的 `readonly_reason_code`。
 *
 * 取值来自 `envs/registry.py:112-136`；返回 null 表示服务端没给出原因，
 * 界面据此不显示这一行，而不是编一个理由。
 */
internal fun readonlyReasonText(code: String?): String? = when (code) {
    "startup" -> "QwenPaw 启动时确定"
    "initial_default" -> "仅作为 Agent 配置的初始化默认值"
    "agent_owned" -> "由各 Agent 配置管理"
    else -> null
}

/** 当前值的来源，取自 catalog 的 `source`。 */
internal fun envSourceText(source: String): String = when (source) {
    "user" -> "QwenPaw"
    "system" -> "系统"
    "default" -> "默认"
    else -> source
}

/** 实时配置项的说明，与官方 `mutabilityDescription.hot_runtime` 一致。 */
internal const val HOT_RUNTIME_HINT = "每次操作开始时从当前 QwenPaw 进程读取。"

/** 只读项的开场提示，与官方 `mutabilityDescription.startup_only` 一字不差。 */
internal const val READONLY_HINT = "仅供查看，QwenPaw 运行期间不能修改。"
