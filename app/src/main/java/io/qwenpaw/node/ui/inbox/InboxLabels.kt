package io.qwenpaw.node.ui.inbox

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

/** 来源的中文标签。 */
internal fun inboxSourceLabel(sourceType: String): String = when (sourceType) {
    "cron" -> "定时任务"
    "heartbeat" -> "心跳"
    "memory" -> "记忆"
    "skill_autoupdate" -> "技能更新"
    "mail" -> "邮件"
    else -> sourceType
}
