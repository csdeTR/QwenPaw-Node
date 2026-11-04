package io.qwenpaw.node.ui.settings

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.CRON_TIMEZONE
import io.qwenpaw.node.data.model.CronDispatchDto
import io.qwenpaw.node.data.model.CronDispatchTargetItemDto
import io.qwenpaw.node.data.model.CronJobSpecDto
import io.qwenpaw.node.data.model.CronRuntimeDto
import io.qwenpaw.node.data.model.CronScheduleDto
import io.qwenpaw.node.data.model.CronTargetDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 定时任务表单的可编辑状态。
 *
 * `request` 与 `meta` 单独保存原始 JSON：`PUT` 是整体替换，
 * 只序列化自己认识的键会在编辑一次之后抹掉迁移审阅锁与模型覆盖。
 */
internal data class CronDraft(
    val id: String? = null,
    val name: String = "",
    val taskType: String = "text",
    val text: String = "",
    val request: String = "",
    val scheduleType: String = "cron",
    val visual: CronVisual = CronVisual(),
    val expression: String = "",
    val directEdit: Boolean = false,
    val runAt: LocalDateTime? = null,
    val repeatEveryDays: Int? = null,
    val repeatEndType: String = "never",
    val repeatUntil: LocalDateTime? = null,
    val repeatCount: Int? = null,
    val timezone: String = CRON_TIMEZONE,
    val channel: String = "console",
    val targetSessionId: String = "",
    val targetUserId: String = "",
    val silent: Boolean = false,
    val maxConcurrency: Int = 1,
    val timeoutSeconds: Int = 120,
    val misfireGraceSeconds: Int = 600,
    val shareSession: Boolean = false,
    val toolSafety: Boolean = false,
    val saveResultToInbox: Boolean? = null,
    val meta: JsonObject = JsonObject(emptyMap()),
    val requestJson: JsonObject? = null,
    /** 编辑时原任务的启用状态，原样带回；新建默认启用。 */
    val initialEnabled: Boolean = true,
) {

    companion object {

        fun from(spec: CronJobSpecDto?): CronDraft {
            if (spec == null) {
                val start = LocalDateTime.ofInstant(Instant.now(), ZoneId.of(CRON_TIMEZONE))
                    .withSecond(0).withNano(0).plusHours(1)
                // 新建时表达式直接由默认选项生成，用户不改也能保存。
                val visual = CronVisual()
                return CronDraft(
                    visual = visual,
                    expression = visual.toExpression(),
                    runAt = start,
                    repeatEndType = "never",
                )
            }
            val schedule = spec.schedule
            val expression = schedule.cron.orEmpty()
            val visual = parseCronExpression(expression)
            val zone = runCatching { ZoneId.of(schedule.timezone) }.getOrDefault(ZoneId.of(CRON_TIMEZONE))
            return CronDraft(
                id = spec.id,
                name = spec.name,
                taskType = spec.taskType,
                text = spec.text.orEmpty(),
                request = inputTextOf(spec.request),
                scheduleType = schedule.type,
                visual = visual ?: CronVisual(),
                expression = expression,
                directEdit = visual == null,
                runAt = schedule.runAt?.let { parseFlexible(it)?.atZoneSameInstant(zone)?.toLocalDateTime() },
                repeatEveryDays = schedule.repeatEveryDays,
                repeatEndType = schedule.repeatEndType ?: "never",
                repeatUntil = schedule.repeatUntil
                    ?.let { parseFlexible(it)?.atZoneSameInstant(zone)?.toLocalDateTime() },
                repeatCount = schedule.repeatCount,
                timezone = schedule.timezone,
                channel = spec.dispatch.channel,
                targetSessionId = spec.dispatch.target.sessionId,
                targetUserId = spec.dispatch.target.userId,
                silent = spec.dispatch.silent,
                maxConcurrency = spec.runtime.maxConcurrency,
                timeoutSeconds = spec.runtime.timeoutSeconds,
                misfireGraceSeconds = spec.runtime.misfireGraceSeconds,
                shareSession = spec.runtime.shareSession,
                toolSafety = spec.runtime.toolSafety,
                saveResultToInbox = spec.saveResultToInbox,
                meta = spec.meta,
                requestJson = spec.request,
                initialEnabled = spec.enabled,
            )
        }

        /** 请求体里的 `input` 以文本形式编辑；非字符串时保留其 JSON 写法。 */
        private fun inputTextOf(request: JsonObject?): String {
            val input = request?.get("input") ?: return ""
            return (input as? JsonPrimitive)?.takeIf { it.isString }?.content ?: input.toString()
        }
    }
}

/** 表单提交的请求体。 */
internal fun CronDraft.toSpec(): CronJobSpecDto {
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of(CRON_TIMEZONE))
    val schedule = if (scheduleType == "once") {
        CronScheduleDto(
            type = "once",
            runAt = runAt?.atZone(zone)?.toOffsetDateTime()?.toString(),
            timezone = timezone,
            repeatEveryDays = repeatEveryDays,
            repeatEndType = if (repeatEveryDays == null) null else repeatEndType,
            repeatUntil = if (repeatEveryDays != null && repeatEndType == "until") {
                repeatUntil?.atZone(zone)?.toOffsetDateTime()?.toString()
            } else {
                null
            },
            repeatCount = if (repeatEveryDays != null && repeatEndType == "count") repeatCount else null,
        )
    } else {
        CronScheduleDto(type = "cron", cron = expression, timezone = timezone)
    }
    val request = if (taskType == "agent") {
        val base = requestJson ?: JsonObject(emptyMap())
        val input = runCatching { Json.parseToJsonElement(request.trim()) }.getOrNull()
            ?: JsonPrimitive(request)
        JsonObject(
            base + mapOf(
                "input" to input,
                "session_id" to JsonPrimitive(targetSessionId),
                "user_id" to JsonPrimitive(targetUserId),
            ),
        )
    } else {
        null
    }
    return CronJobSpecDto(
        id = id,
        name = name.trim(),
        // 编辑时保留原有启用状态，新建默认启用。
        enabled = initialEnabled,
        schedule = schedule,
        taskType = taskType,
        text = if (taskType == "text") text else null,
        request = request,
        dispatch = CronDispatchDto(
            channel = channel.ifBlank { "console" },
            target = CronTargetDto(userId = targetUserId, sessionId = targetSessionId),
            silent = silent,
        ),
        runtime = CronRuntimeDto(
            maxConcurrency = maxConcurrency,
            timeoutSeconds = timeoutSeconds,
            misfireGraceSeconds = misfireGraceSeconds,
            shareSession = shareSession,
            toolSafety = toolSafety,
        ),
        saveResultToInbox = saveResultToInbox,
        meta = meta,
    )
}

/**
 * 新建 / 编辑定时任务。
 *
 * 排期提供「快捷」与「自定义」两种操作方式：前者是固定预设，后者按频率逐项
 * 填出表达式。用户还可以打开「直接编辑表达式」，此时客户端只做可逆解析，
 * 认不出的写法原样保留，不猜测性重写。
 */
@Composable
internal fun CronEditorSection(
    initial: CronJobSpecDto?,
    targets: List<CronDispatchTargetItemDto>,
    targetChannels: List<String>,
    /** session_id → 会话名称，用于选择器与已选行显示。 */
    chatNames: Map<String, String> = emptyMap(),
    serverTimezone: String?,
    saving: Boolean,
    error: String?,
    onLoadTargets: () -> Unit,
    onSave: (spec: CronJobSpecDto, originalId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var draft by remember(initial) { mutableStateOf(CronDraft.from(initial)) }
    var advancedOpen by remember { mutableStateOf(false) }
    var localError by remember(initial) { mutableStateOf<String?>(null) }
    var pickingTarget by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onLoadTargets() }

    fun applyVisual(next: CronVisual, direct: Boolean = false) {
        draft = draft.copy(
            visual = next,
            expression = next.toExpression(),
            directEdit = direct,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
    ) {
        (localError ?: error)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        SectionHeader("基本信息")
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("任务名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("任务类型", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("消息内容", draft.taskType == "text") {
                        draft = draft.copy(taskType = "text", silent = false)
                    }
                    Chip("Agent", draft.taskType == "agent") {
                        draft = draft.copy(taskType = "agent")
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = if (draft.taskType == "text") draft.text else draft.request,
                    onValueChange = { value ->
                        draft = if (draft.taskType == "text") {
                            draft.copy(text = value)
                        } else {
                            draft.copy(request = value)
                        }
                    },
                    label = { Text(if (draft.taskType == "text") "消息内容" else "请求内容") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionHeader("调度")
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("循环", draft.scheduleType == "cron") {
                        draft = draft.copy(scheduleType = "cron")
                    }
                    Chip("一次性", draft.scheduleType == "once") {
                        draft = draft.copy(scheduleType = "once")
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (draft.scheduleType == "cron") {
                    Text("快捷", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    CRON_PRESETS.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (label, visual) ->
                                Chip(label, draft.expression == visual.toExpression()) {
                                    applyVisual(visual)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("自定义", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("每分钟", draft.visual.frequency == CronFrequency.MINUTES) {
                            applyVisual(draft.visual.copy(frequency = CronFrequency.MINUTES))
                        }
                        Chip("每小时", draft.visual.frequency == CronFrequency.HOURLY) {
                            applyVisual(draft.visual.copy(frequency = CronFrequency.HOURLY))
                        }
                        Chip("每天", draft.visual.frequency == CronFrequency.DAILY) {
                            applyVisual(draft.visual.copy(frequency = CronFrequency.DAILY))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("每周", draft.visual.frequency == CronFrequency.WEEKLY) {
                            applyVisual(draft.visual.copy(frequency = CronFrequency.WEEKLY))
                        }
                        Chip("每月", draft.visual.frequency == CronFrequency.MONTHLY) {
                            applyVisual(draft.visual.copy(frequency = CronFrequency.MONTHLY))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when (draft.visual.frequency) {
                        CronFrequency.MINUTES -> Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CRON_MINUTE_STEPS.filter { it <= 30 }.forEach { step ->
                                Chip("$step 分钟", draft.visual.everyMinutes == step) {
                                    applyVisual(draft.visual.copy(everyMinutes = step))
                                }
                            }
                        }

                        CronFrequency.HOURLY -> TimeRow(
                            label = "每小时的第几分",
                            value = LocalTime.of(0, draft.visual.minute),
                            onPick = { draft = draft.copy(visual = draft.visual.copy(minute = it.minute)) },
                            onExpression = { draft = draft.copy(expression = draft.visual.toExpression()) },
                        )

                        CronFrequency.DAILY -> TimeRow(
                            label = "每天",
                            value = LocalTime.of(draft.visual.hour, draft.visual.minute),
                            onPick = { draft = draft.copy(visual = draft.visual.copy(hour = it.hour, minute = it.minute)) },
                            onExpression = { draft = draft.copy(expression = draft.visual.toExpression()) },
                        )

                        CronFrequency.WEEKLY -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (1..7).forEach { day ->
                                    Chip(WEEKDAY_LABELS[day - 1], draft.visual.daysOfWeek.contains(day)) {
                                        val days = if (draft.visual.daysOfWeek.contains(day)) {
                                            draft.visual.daysOfWeek - day
                                        } else {
                                            draft.visual.daysOfWeek + day
                                        }
                                        applyVisual(draft.visual.copy(daysOfWeek = days.ifEmpty { setOf(1) }))
                                    }
                                }
                            }
                            TimeRow(
                                label = "时间",
                                value = LocalTime.of(draft.visual.hour, draft.visual.minute),
                                onPick = { draft = draft.copy(visual = draft.visual.copy(hour = it.hour, minute = it.minute)) },
                                onExpression = { draft = draft.copy(expression = draft.visual.toExpression()) },
                            )
                        }

                        CronFrequency.MONTHLY -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1, 15, 28, 31).forEach { day ->
                                    Chip("$day 日", draft.visual.dayOfMonth == day) {
                                        applyVisual(draft.visual.copy(dayOfMonth = day))
                                    }
                                }
                            }
                            TimeRow(
                                label = "时间",
                                value = LocalTime.of(draft.visual.hour, draft.visual.minute),
                                onPick = { draft = draft.copy(visual = draft.visual.copy(hour = it.hour, minute = it.minute)) },
                                onExpression = { draft = draft.copy(expression = draft.visual.toExpression()) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "直接编辑表达式",
                        subtitle = "关闭时由上方选项自动生成；开启后可写任意 5 段写法",
                        checked = draft.directEdit,
                        onCheckedChange = { direct ->
                            draft = draft.copy(directEdit = direct)
                            if (!direct) parseCronExpression(draft.expression)?.let { applyVisual(it) }
                        },
                    )
                    OutlinedTextField(
                        value = draft.expression,
                        onValueChange = { value ->
                            draft = draft.copy(expression = value)
                            parseCronExpression(value)?.let { draft = draft.copy(visual = it) }
                        },
                        label = { Text("Cron 表达式") },
                        singleLine = true,
                        readOnly = !draft.directEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    DateTimeRow(
                        label = "执行时间",
                        value = draft.runAt,
                        onPick = { draft = draft.copy(runAt = it) },
                    )
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = "重复执行",
                        subtitle = "按固定间隔再次执行该任务",
                        checked = draft.repeatEveryDays != null,
                        onCheckedChange = { on ->
                            draft = draft.copy(
                                repeatEveryDays = if (on) draft.repeatEveryDays ?: 1 else null,
                                repeatEndType = if (on) draft.repeatEndType else "never",
                                repeatUntil = if (on) draft.repeatUntil else null,
                                repeatCount = if (on) draft.repeatCount else null,
                            )
                        },
                    )
                    draft.repeatEveryDays?.let { every ->
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            NumberRow(
                                label = "每 N 天",
                                value = every,
                                onChange = { draft = draft.copy(repeatEveryDays = it) },
                            )
                            Text("结束方式", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("无限", draft.repeatEndType == "never") {
                                    draft = draft.copy(repeatEndType = "never", repeatUntil = null, repeatCount = null)
                                }
                                Chip("截止", draft.repeatEndType == "until") {
                                    draft = draft.copy(repeatEndType = "until")
                                }
                                Chip("次数", draft.repeatEndType == "count") {
                                    draft = draft.copy(repeatEndType = "count")
                                }
                            }
                            when (draft.repeatEndType) {
                                "until" -> DateTimeRow(
                                    label = "截止时间",
                                    value = draft.repeatUntil,
                                    onPick = { draft = draft.copy(repeatUntil = it) },
                                )

                                "count" -> NumberRow(
                                    label = "执行次数",
                                    value = draft.repeatCount ?: 0,
                                    onChange = { draft = draft.copy(repeatCount = it) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("时区", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("北京时间（UTC+8）", draft.timezone == CRON_TIMEZONE) {
                        draft = draft.copy(timezone = CRON_TIMEZONE)
                    }
                    Chip("UTC", draft.timezone == "UTC") {
                        draft = draft.copy(timezone = "UTC")
                    }
                }
                if (isUtcPlus8(serverTimezone) == false) {
                    Spacer(Modifier.height(8.dp))
                    CronTimezoneCompare(
                        expression = draft.expression,
                        timezone = draft.timezone,
                        serverTimezone = serverTimezone.orEmpty(),
                    )
                }
            }
        }

        SectionHeader("投递")
        Card {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("频道", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                // `console` 是网页/手机端自己的通道，界面上不显示它，也不给选项：
                // 定时任务在移动端唯一可用的投递通道就是它，列出名字反而让人以为要选什么。
                val channels = targetChannels.filter { it != "console" }
                if (channels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        channels.forEach { name ->
                            Chip(name, draft.channel == name) { draft = draft.copy(channel = name) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                val selected = targets.firstOrNull { it.sessionId == draft.targetSessionId }
                Text("目标会话", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { pickingTarget = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selected?.let { targetLabel(it, chatNames) }
                            ?: draft.targetSessionId.ifBlank { "选择会话" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text("选择", style = MaterialTheme.typography.labelMedium)
                }
                // 拉不到目标列表时退回手填，不挡住保存。
                if (targets.isEmpty() || selected == null) {
                    OutlinedTextField(
                        value = draft.targetSessionId,
                        onValueChange = { draft = draft.copy(targetSessionId = it) },
                        label = { Text("session_id") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.targetUserId,
                        onValueChange = { draft = draft.copy(targetUserId = it) },
                        label = { Text("user_id") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (draft.taskType == "agent") {
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = "静默投递",
                        subtitle = "只执行任务，不把过程推送到频道",
                        checked = draft.silent,
                        onCheckedChange = { draft = draft.copy(silent = it) },
                    )
                }
            }
        }

        SectionHeader("高级")
        Card {
            SwitchRow(
                title = "展开高级选项",
                subtitle = "并发、超时与结果归档",
                checked = advancedOpen,
                onCheckedChange = { advancedOpen = it },
            )
            if (advancedOpen) {
                Divider()
                Column(modifier = Modifier.padding(14.dp)) {
                    NumberRow("最大并发", draft.maxConcurrency) {
                        draft = draft.copy(maxConcurrency = it)
                    }
                    NumberRow("超时秒数", draft.timeoutSeconds) {
                        draft = draft.copy(timeoutSeconds = it)
                    }
                    NumberRow("错过宽限秒数", draft.misfireGraceSeconds) {
                        draft = draft.copy(misfireGraceSeconds = it)
                    }
                    SwitchRow(
                        title = "共用目标会话",
                        subtitle = "关闭时每次执行使用该任务专属的可见会话",
                        checked = draft.shareSession,
                        onCheckedChange = { draft = draft.copy(shareSession = it) },
                    )
                    SwitchRow(
                        title = "工具审批检查",
                        subtitle = "开启后高风险工具需要审批，可能阻塞无人值守执行",
                        checked = draft.toolSafety,
                        onCheckedChange = { draft = draft.copy(toolSafety = it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("结果存入收件箱", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("自动", draft.saveResultToInbox == null) {
                            draft = draft.copy(saveResultToInbox = null)
                        }
                        Chip("存入", draft.saveResultToInbox == true) {
                            draft = draft.copy(saveResultToInbox = true)
                        }
                        Chip("不存", draft.saveResultToInbox == false) {
                            draft = draft.copy(saveResultToInbox = false)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = "保存",
                style = MaterialTheme.typography.labelLarge,
                color = if (saving) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !saving) {
                        val spec = draft.toSpec()
                        val message = validateCronDraft(
                            name = spec.name,
                            taskType = spec.taskType,
                            text = spec.text.orEmpty(),
                            request = draft.request,
                            schedule = spec.schedule,
                            silent = spec.dispatch.silent,
                        )
                        if (message != null) {
                            localError = message
                        } else {
                            localError = null
                            onSave(spec, initial?.id)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }

    if (pickingTarget) {
        AlertDialog(
            onDismissRequest = { pickingTarget = false },
            title = { Text("选择目标会话", style = MaterialTheme.typography.titleMedium) },
            text = {
                var keyword by remember { mutableStateOf("") }
                // 按关键词过滤：名称优先，session_id 也参与匹配，便于按 id 定位。
                val filtered = targets.filter { item ->
                    if (keyword.isBlank()) return@filter true
                    val name = chatNames[item.sessionId].orEmpty()
                    name.contains(keyword, ignoreCase = true) ||
                        item.sessionId.contains(keyword, ignoreCase = true)
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        placeholder = { Text("搜索会话名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        filtered.forEach { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        draft = draft.copy(
                                            channel = item.channel,
                                            targetSessionId = item.sessionId,
                                            targetUserId = item.userId,
                                        )
                                        pickingTarget = false
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                // 主行是名称，没有名称时退回 session_id，避免出现一片空白项。
                                Text(
                                    text = item.displayName(chatNames),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = item.sessionId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (filtered.isEmpty()) {
                            Text(
                                text = if (targets.isEmpty()) {
                                    "服务端没有可用的会话目标，请手动填写"
                                } else {
                                    "没有匹配的会话"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { pickingTarget = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
        )
    }
}

/** 两条时间并排：同一时刻在客户端与服务端时区的写法。 */
@Composable
private fun CronTimezoneCompare(expression: String, timezone: String, serverTimezone: String) {
    val serverZone = remember(serverTimezone) { runCatching { ZoneId.of(serverTimezone) }.getOrNull() }
        ?: return
    val taskZone = remember(timezone) { runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of(CRON_TIMEZONE)) }
    val clientZone = ZoneId.of(CRON_TIMEZONE)
    val runs = remember(expression, taskZone) {
        nextRuns(expression, Instant.now(), count = 2, zone = taskZone)
    }
    Column {
        if (runs.isNotEmpty()) {
            runs.forEach { instant ->
                Text(
                    text = "北京时间  ${formatInZone(instant, clientZone)}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = "服务端时区  ${formatInZone(instant, serverZone)}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        Text(
            text = "服务端时区与北京时间不同，任务仍按北京时间执行。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 目标会话的主行文字：有名称用名称，没有才退回 session_id。 */
internal fun CronDispatchTargetItemDto.displayName(names: Map<String, String>): String =
    names[sessionId]?.takeIf { it.isNotBlank() } ?: sessionId

/** 已选目标的单行写法：名称 + session_id，丢掉无信息量的 channel。 */
internal fun targetLabel(item: CronDispatchTargetItemDto, names: Map<String, String>): String {
    val name = names[item.sessionId]?.takeIf { it.isNotBlank() }
    return if (name == null) item.sessionId else "$name · ${item.sessionId}"
}

@Composable
private fun TimeRow(
    label: String,
    value: LocalTime,
    onPick: (LocalTime) -> Unit,
    onExpression: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = "%02d:%02d".format(value.hour, value.minute),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            onPick(LocalTime.of(hour, minute))
                            onExpression()
                        },
                        value.hour,
                        value.minute,
                        true,
                    ).show()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DateTimeRow(label: String, value: LocalDateTime?, onPick: (LocalDateTime) -> Unit) {
    val context = LocalContext.current
    val shown = value ?: LocalDateTime.ofInstant(Instant.now(), ZoneId.of(CRON_TIMEZONE))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = shown.toLocalDate().toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                onPick(LocalDateTime.of(LocalDate.of(year, month + 1, day), shown.toLocalTime()))
                            },
                            shown.year,
                            shown.monthValue - 1,
                            shown.dayOfMonth,
                        ).show()
                    }
                    .padding(vertical = 4.dp),
            )
            Text(
                text = "%02d:%02d".format(shown.hour, shown.minute),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                onPick(LocalDateTime.of(shown.toLocalDate(), LocalTime.of(hour, minute)))
                            },
                            shown.hour,
                            shown.minute,
                            true,
                        ).show()
                    }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { text ->
                text.trim().toIntOrNull()?.let { if (it >= 0) onChange(it) }
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.width(110.dp),
        )
    }
}

