package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.CRON_TIMEZONE
import io.qwenpaw.node.data.model.CronExecutionRecordDto
import io.qwenpaw.node.data.model.CronJobSpecDto
import io.qwenpaw.node.data.model.CronJobStateDto
import io.qwenpaw.node.ui.i18n.Texts
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.theme.qwenSwitchColors
import java.time.ZoneId

/**
 * 定时任务列表。
 *
 * 服务端时区与北京时间不一致时，页面顶部常驻一块「时间对照」：同一时刻在两地
 * 的写法并排给出。差异是持续状态而不是一次性事件，用内嵌块而不是每次进入都弹窗。
 */
@Composable
internal fun CronSection(
    state: CronUiState,
    onToggle: (String, Boolean) -> Unit,
    onRun: (String) -> Unit,
    onPromote: (String) -> Unit,
    onDelete: (String) -> Unit,
    onLoadHistory: (String) -> Unit,
    dismissRowError: (String) -> Unit,
    dismissError: () -> Unit,
    onNew: () -> Unit,
    onEdit: (CronJobSpecDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CronJobSpecDto?>(null) }
    var pendingRun by remember { mutableStateOf<CronJobSpecDto?>(null) }
    var historyJob by remember { mutableStateOf<CronJobSpecDto?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        state.error?.let { error ->
            CronError(
                text = error,
                onDismiss = dismissError,
            )
        }

        if (isUtcPlus8(state.serverTimezone) == false) {
            CronTimezoneHint(
                serverTimezone = state.serverTimezone.orEmpty(),
                jobs = state.jobs,
                states = state.states,
            )
        }

        SectionHeader(
            stringResource(R.string.cron_header_jobs),
            pluralStringResource(R.plurals.cron_count, state.jobs.size, state.jobs.size),
        )
        Card {
            if (state.jobs.isEmpty()) {
                Text(
                    text = when {
                        state.loading -> stringResource(R.string.action_loading)
                        state.error != null -> stringResource(R.string.cron_load_failed)
                        else -> stringResource(R.string.cron_empty_jobs)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
            } else {
                state.jobs.forEachIndexed { index, job ->
                    CronRow(
                        job = job,
                        state = state.states[job.id],
                        serverTimezone = state.serverTimezone,
                        rowError = state.rowErrors[job.id],
                        menuOpen = menuFor == job.id,
                        onMenuOpenChange = { open -> menuFor = if (open) job.id else null },
                        onToggle = { onToggle(job.id.orEmpty(), it) },
                        onEdit = { onEdit(job) },
                        onRun = { pendingRun = job },
                        onPromote = { onPromote(job.id.orEmpty()) },
                        onHistory = {
                            historyJob = job
                            onLoadHistory(job.id.orEmpty())
                        },
                        onDelete = { pendingDelete = job },
                        dismissRowError = { dismissRowError(job.id.orEmpty()) },
                    )
                    if (index != state.jobs.lastIndex) Divider()
                }
            }
            Divider()
            ActionRow(text = stringResource(R.string.cron_action_new), onClick = onNew)
        }
        Spacer(Modifier.height(24.dp))
    }

    pendingRun?.let { job ->
        AlertDialog(
            onDismissRequest = { pendingRun = null },
            title = {
                Text(stringResource(R.string.cron_action_run), style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.cron_run_confirm,
                        job.name.ifBlank { stringResource(R.string.cron_unnamed) },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Text(
                    text = stringResource(R.string.cron_action_execute),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clickable {
                            onRun(job.id.orEmpty())
                            pendingRun = null
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
            dismissButton = {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { pendingRun = null }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
        )
    }

    pendingDelete?.let { job ->
        io.qwenpaw.node.ui.common.DestructiveActionDialog(
            title = stringResource(R.string.cron_delete_title),
            message = stringResource(
                R.string.cron_delete_message,
                job.name.ifBlank { stringResource(R.string.cron_unnamed) },
            ),
            impacts = listOf(
                stringResource(R.string.cron_delete_impact_schedule),
                stringResource(R.string.cron_delete_impact_history),
            ),
            confirmLabel = stringResource(R.string.action_delete),
            undoable = false,
            onConfirm = {
                onDelete(job.id.orEmpty())
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    historyJob?.let { job ->
        CronHistoryDialog(
            jobName = job.name.ifBlank { stringResource(R.string.cron_unnamed) },
            records = state.history[job.id],
            loading = state.historyLoading,
            onDismiss = { historyJob = null },
        )
    }
}

@Composable
private fun CronError(text: String, onDismiss: () -> Unit) {
    SectionHeader(stringResource(R.string.action_failed))
    Card {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.action_close),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun CronTimezoneHint(
    serverTimezone: String,
    jobs: List<CronJobSpecDto>,
    states: Map<String, CronJobStateDto>,
) {
    val serverZone = remember(serverTimezone) { runCatching { ZoneId.of(serverTimezone) }.getOrNull() }
        ?: return
    val beijing = ZoneId.of(CRON_TIMEZONE)
    // 用服务端算好的 next_run_at，不在本地重排：任务的时区解释权在服务端。
    val next = remember(jobs, states) {
        jobs.asSequence()
            .mapNotNull { job -> states[job.id]?.nextRunAt }
            .mapNotNull { iso -> parseFlexible(iso)?.toInstant() }
            .minOrNull()
    }

    SectionHeader(stringResource(R.string.cron_header_timezone_compare))
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            if (next != null) {
                Text(
                    text = stringResource(R.string.cron_beijing_time, formatInZone(next, beijing)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.cron_server_time, formatInZone(next, serverZone)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = stringResource(R.string.cron_timezone_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun CronRow(
    job: CronJobSpecDto,
    state: CronJobStateDto?,
    serverTimezone: String?,
    rowError: String?,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onPromote: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
    dismissRowError: () -> Unit,
) {
    val review = requiresReview(job)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = job.name.ifBlank { stringResource(R.string.cron_unnamed_task) },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                    )
                    if (review) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.cron_tag_review),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Text(
                    text = cronSummary(job.schedule),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusText(job, state, serverTimezone),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state?.lastStatus == "error") {
                        MaterialTheme.colorScheme.error
                    } else {
                        io.qwenpaw.node.ui.theme.LocalCaptionColor.current
                    },
                )
                rowError?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.action_close),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = dismissRowError)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = job.enabled,
                onCheckedChange = onToggle,
                colors = qwenSwitchColors(),
            )
            Box {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onMenuOpenChange(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = QwenIcons.moreVertical,
                        contentDescription = stringResource(R.string.cron_more_actions),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cron_action_run)) },
                        onClick = {
                            onMenuOpenChange(false)
                            onRun()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = {
                            onMenuOpenChange(false)
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cron_action_history)) },
                        onClick = {
                            onMenuOpenChange(false)
                            onHistory()
                        },
                    )
                    if (review) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cron_action_promote)) },
                            onClick = {
                                onMenuOpenChange(false)
                                onPromote()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            onMenuOpenChange(false)
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun requiresReview(job: CronJobSpecDto): Boolean {
    // `portability.requires_review` 是唯一权威标记：审核通过后它变 false，
    // 而顶层 `meta.safety` 仍保留 `disabled_until_explicit_promotion`，
    // 若两者都看会把已审核的任务继续标成待审核。
    val portability = job.meta["portability"] as? kotlinx.serialization.json.JsonObject
    val requires = (portability?.get("requires_review") as? kotlinx.serialization.json.JsonPrimitive)
        ?.content
    if (requires != null) return requires == "true"
    val safety = (job.meta["safety"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return safety == "disabled_until_explicit_promotion"
}

/**
 * 列表第二行的状态文案。
 *
 * 优先说明「为什么现在用不了」，其次才是下一次触发时间：
 * 待审核与已暂停状态下，下次时间不会到来，先说时间会误导。
 */
private fun statusText(job: CronJobSpecDto, state: CronJobStateDto?, serverTimezone: String?): String {
    if (requiresReview(job)) return Texts.get(R.string.cron_status_needs_review)
    if (!job.enabled) {
        val next = state?.nextRunAt?.let { formatIsoInZone(it, ZoneId.of(CRON_TIMEZONE)) }
        return if (next == null) {
            Texts.get(R.string.cron_status_paused)
        } else {
            Texts.get(R.string.cron_status_paused_with_next, next)
        }
    }
    if (state == null) return Texts.get(R.string.cron_status_unknown)
    val next = state.nextRunAt?.let { formatIsoInZone(it, ZoneId.of(CRON_TIMEZONE)) }
    val serverNext = serverTimezone?.let { name ->
        runCatching { ZoneId.of(name) }.getOrNull()?.let { zone -> state.nextRunAt?.let { formatIsoInZone(it, zone) } }
    }
    val nextText = when {
        next == null -> null
        // 只有时区不同且换算结果确实不同时才并排写出服务端时间。
        serverNext != null && serverNext != next &&
            isUtcPlus8(serverTimezone) == false ->
            Texts.get(R.string.cron_status_next_server, next, serverNext)
        else -> Texts.get(R.string.cron_status_next, next)
    }
    return when {
        state.lastStatus == "error" && state.lastError != null ->
            Texts.get(R.string.cron_status_last_error, state.lastError.take(40))
        state.lastStatus == "error" -> Texts.get(R.string.cron_status_last_failed)
        nextText != null -> nextText
        state.lastStatus == "running" -> Texts.get(R.string.cron_status_running)
        else -> Texts.get(R.string.cron_status_never_run)
    }
}

@Composable
private fun CronHistoryDialog(
    jobName: String,
    records: List<CronExecutionRecordDto>?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.cron_history_title, jobName), style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    loading && records == null -> Text(
                        stringResource(R.string.action_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    records.isNullOrEmpty() -> Text(
                        stringResource(R.string.cron_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> records.forEach { record ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatIsoInZone(record.runAt, ZoneId.of(CRON_TIMEZONE))
                                    ?: record.runAt,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (record.status == "success") {
                                    stringResource(R.string.cron_history_success)
                                } else {
                                    record.status
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (record.status == "success") {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (record.trigger == "manual") {
                                    stringResource(R.string.cron_history_manual)
                                } else {
                                    stringResource(R.string.cron_history_scheduled)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                text = stringResource(R.string.action_close),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}