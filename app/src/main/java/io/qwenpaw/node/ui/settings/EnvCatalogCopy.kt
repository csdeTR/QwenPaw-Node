package io.qwenpaw.node.ui.settings

import androidx.annotation.StringRes
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts

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
 *
 * 与 [io.qwenpaw.node.ui.CommandCopy] 同样的做法：表里只存资源 id，取值时再解析，
 * 这样切换界面语言后弹窗里的文案跟着变。
 */
internal data class EnvSpecCopy(
    @StringRes val description: Int,
    @StringRes val howToModify: Int,
)

private val STARTUP_ONLY_HINT = R.string.env_hint_startup_only
private val BOOTSTRAP_HINT = R.string.env_hint_bootstrap_only

/** 键 → 说明。只收 `GET /api/envs/catalog` 会下发的内置项。 */
internal val ENV_SPEC_COPY: Map<String, EnvSpecCopy> = mapOf(
    "QWENPAW_AUTH_ENABLED" to EnvSpecCopy(
        description = R.string.env_desc_auth_enabled,
        howToModify = R.string.env_how_auth_enabled,
    ),
    "QWENPAW_LLM_STREAM_FIRST_CONTENT_TIMEOUT" to EnvSpecCopy(
        description = R.string.env_desc_stream_first_content_timeout,
        howToModify = R.string.env_how_takes_effect_next_call,
    ),
    "QWENPAW_LLM_STREAM_IDLE_TIMEOUT" to EnvSpecCopy(
        description = R.string.env_desc_stream_idle_timeout,
        howToModify = R.string.env_how_takes_effect_next_call,
    ),
    "QWENPAW_LLM_MAX_RETRIES" to EnvSpecCopy(
        description = R.string.env_desc_max_retries,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_BACKOFF_BASE" to EnvSpecCopy(
        description = R.string.env_desc_backoff_base,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_BACKOFF_CAP" to EnvSpecCopy(
        description = R.string.env_desc_backoff_cap,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_MAX_CONCURRENT" to EnvSpecCopy(
        description = R.string.env_desc_max_concurrent,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_MAX_QPM" to EnvSpecCopy(
        description = R.string.env_desc_max_qpm,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_RATE_LIMIT_PAUSE" to EnvSpecCopy(
        description = R.string.env_desc_rate_limit_pause,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_RATE_LIMIT_JITTER" to EnvSpecCopy(
        description = R.string.env_desc_rate_limit_jitter,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_LLM_ACQUIRE_TIMEOUT" to EnvSpecCopy(
        description = R.string.env_desc_acquire_timeout,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_WORKING_DIR" to EnvSpecCopy(
        description = R.string.env_desc_working_dir,
        howToModify = BOOTSTRAP_HINT,
    ),
    "QWENPAW_SECRET_DIR" to EnvSpecCopy(
        description = R.string.env_desc_secret_dir,
        howToModify = BOOTSTRAP_HINT,
    ),
    "QWENPAW_CONFIG_FILE" to EnvSpecCopy(
        description = R.string.env_desc_config_file,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_JOBS_FILE" to EnvSpecCopy(
        description = R.string.env_desc_jobs_file,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_CHATS_FILE" to EnvSpecCopy(
        description = R.string.env_desc_chats_file,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_OPENAPI_DOCS" to EnvSpecCopy(
        description = R.string.env_desc_openapi_docs,
        howToModify = STARTUP_ONLY_HINT,
    ),
    "QWENPAW_RUNNING_IN_CONTAINER" to EnvSpecCopy(
        description = R.string.env_desc_running_in_container,
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
    "startup" -> Texts.get(R.string.env_readonly_reason_startup)
    "initial_default" -> Texts.get(R.string.env_readonly_reason_initial_default)
    "agent_owned" -> Texts.get(R.string.env_readonly_reason_agent_owned)
    else -> null
}

/** 当前值的来源，取自 catalog 的 `source`。 */
internal fun envSourceText(source: String): String = when (source) {
    "user" -> "QwenPaw"
    "system" -> Texts.get(R.string.env_source_system)
    "default" -> Texts.get(R.string.env_source_default)
    else -> source
}

/** 实时配置项的说明，与官方 `mutabilityDescription.hot_runtime` 一致。 */
@StringRes
internal val HOT_RUNTIME_HINT = R.string.env_hot_runtime_hint

/** 只读项的开场提示，与官方 `mutabilityDescription.startup_only` 一字不差。 */
@StringRes
internal val READONLY_HINT = R.string.env_readonly_hint
