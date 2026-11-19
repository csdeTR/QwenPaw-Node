package io.qwenpaw.node.ui.inbox

import io.qwenpaw.node.R
import io.qwenpaw.node.ui.i18n.Texts

/**
 * 收件箱的来源类型与中文标签。
 *
 * 取值来自服务端实际写入的位置：`crons/manager.py` 与 `crons/heartbeat.py` 写 `cron` / `heartbeat`，
 * `agents/memory/reme_inbox.py` 写 `memory`，`routers/skills.py` 写 `skill_autoupdate`，
 * `app/mail/monitor.py` 写 `mail`。web 控制台的推送消息页用的也是这五个。
 *
 * 未知取值原样返回，不猜中文名：服务端新增来源时列表里宁可显示原值，也不该显示空白。
 */
internal val INBOX_SOURCE_FILTERS: List<String> = listOf(
    "cron",
    "heartbeat",
    "memory",
    "skill_autoupdate",
    "mail",
)

/**
 * 筛选按钮上的来源文案。
 *
 * `null` 与空白都表示不做来源筛选，统一显示「全部」，与首项选项一致。
 */
internal fun sourceFilterLabel(source: String?): String =
    if (source.isNullOrBlank()) {
        Texts.get(R.string.inbox_filter_all)
    } else {
        inboxSourceLabel(source)
    }

/** 来源的中文标签。 */
internal fun inboxSourceLabel(sourceType: String): String = when (sourceType) {
    "cron" -> Texts.get(R.string.inbox_source_cron)
    "heartbeat" -> Texts.get(R.string.inbox_source_heartbeat)
    "memory" -> Texts.get(R.string.inbox_source_memory)
    "skill_autoupdate" -> Texts.get(R.string.inbox_source_skill_autoupdate)
    "mail" -> Texts.get(R.string.inbox_source_mail)
    else -> sourceType
}
