package io.qwenpaw.node.ui.acl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.AclChannelDto
import io.qwenpaw.node.data.model.AclPendingDto
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ACL_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun timeLabel(timestamp: Double): String =
    if (timestamp <= 0.0) "" else ACL_TIME.format(Instant.ofEpochSecond(timestamp.toLong()))

/** 聊天渠道与邮件的待审批队列、名单增删。 */
@Composable
fun AclScreen(
    state: AclUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSetTab: (AclTab) -> Unit,
    onToggleSelected: (String) -> Unit,
    onAct: (String, String) -> Unit,
    onRemark: (String, String) -> Unit,
    onAddMember: (group: String, userId: String, remark: String, blacklist: Boolean) -> Unit,
    onRemoveMember: (group: String, userId: String, blacklist: Boolean) -> Unit,
    onDismissNotice: () -> Unit,
    currentAgentId: String,
) {
    var remarkFor by remember { mutableStateOf<AclPendingDto?>(null) }
    var remarkText by remember { mutableStateOf("") }
    var addDialog by remember { mutableStateOf(false) }
    var addGroup by remember { mutableStateOf("") }
    var addUser by remember { mutableStateOf("") }
    var addRemark by remember { mutableStateOf("") }
    var addBlacklist by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var mailApprove by remember { mutableStateOf(false) }

    val groups = if (state.tab == AclTab.CHAT) state.chatChannels else state.mailChannels

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                Text(
                    text = stringResource(R.string.acl_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
            }
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    text = stringResource(R.string.acl_tab_chat),
                    selected = state.tab == AclTab.CHAT,
                ) { onSetTab(AclTab.CHAT) }
                FilterChip(
                    text = if (state.mailPendingCount > 0) {
                        stringResource(R.string.acl_tab_mail) +
                            " (" + state.mailPendingCount + ")"
                    } else {
                        stringResource(R.string.acl_tab_mail)
                    },
                    selected = state.tab == AclTab.MAIL,
                ) { onSetTab(AclTab.MAIL) }
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                TextButton(onClick = onDismissNotice, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.runtime_action_clear_notice))
                }
            }

            SectionTitle(stringResource(R.string.acl_pending_title))
            if (state.pending.isEmpty()) {
                EmptyHint(stringResource(R.string.acl_pending_empty))
            } else {
                state.pending.forEach { entry ->
                    PendingCard(
                        entry = entry,
                        checked = entry.key in state.selected,
                        onToggle = { onToggleSelected(entry.key) },
                        onRemark = {
                            remarkText = entry.remark
                            remarkFor = entry
                        },
                    )
                }
            }

            if (state.selected.isNotEmpty()) {
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(R.string.acl_selected, state.selected.size),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                enabled = !state.busy,
                                onClick = {
                                    if (state.tab == AclTab.MAIL) {
                                        mailApprove = true
                                    } else {
                                        onAct("pending/approve", "")
                                    }
                                },
                            ) { Text(stringResource(R.string.acl_action_approve)) }
                            TextButton(
                                enabled = !state.busy,
                                onClick = { onAct("pending/deny", "") },
                            ) { Text(stringResource(R.string.acl_action_deny)) }
                            TextButton(
                                enabled = !state.busy,
                                onClick = { onAct("pending/dismiss", "") },
                            ) { Text(stringResource(R.string.acl_action_dismiss)) }
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.acl_lists_title))
            if (groups.isEmpty()) {
                EmptyHint(stringResource(R.string.acl_lists_empty))
            } else {
                groups.forEach { (group, acl) ->
                    GroupCard(
                        group = group.ifBlank { stringResource(R.string.acl_unknown_group) },
                        acl = acl,
                        onAdd = { blacklist ->
                            addGroup = group
                            addUser = ""
                            addRemark = ""
                            addBlacklist = blacklist
                            addDialog = true
                        },
                        onRemove = { userId, blacklist ->
                            removeTarget = Triple(group, userId, blacklist)
                        },
                    )
                }
            }
            // 名单为空时也必须能添加第一条，因此按钮不依赖 groups 是否为空。
            TextButton(
                onClick = {
                    addGroup = if (state.tab == AclTab.MAIL) currentAgentId else ""
                    addUser = ""
                    addRemark = ""
                    addBlacklist = false
                    addDialog = true
                },
                modifier = Modifier.padding(horizontal = 10.dp),
            ) { Text(stringResource(R.string.acl_action_add)) }
        }
    }

    remarkFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { remarkFor = null },
            title = { Text(stringResource(R.string.acl_remark_title)) },
            text = {
                OutlinedTextField(
                    value = remarkText,
                    onValueChange = { remarkText = it },
                    label = { Text(stringResource(R.string.acl_remark_field)) },
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val key = entry.key
                        remarkFor = null
                        onRemark(key, remarkText)
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { remarkFor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text(stringResource(R.string.acl_add_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = addGroup,
                        onValueChange = { addGroup = it },
                        label = { Text(stringResource(R.string.acl_add_group)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addUser,
                        onValueChange = { addUser = it },
                        label = { Text(stringResource(R.string.acl_add_user)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addRemark,
                        onValueChange = { addRemark = it },
                        label = { Text(stringResource(R.string.acl_add_remark)) },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = addBlacklist,
                            onCheckedChange = { addBlacklist = it },
                        )
                        Text(stringResource(R.string.acl_add_blacklist))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = addGroup.isNotBlank() && addUser.isNotBlank(),
                    onClick = {
                        addDialog = false
                        onAddMember(addGroup.trim(), addUser.trim(), addRemark.trim(), addBlacklist)
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { addDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    removeTarget?.let { (group, userId, blacklist) ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.acl_remove_title)) },
            text = { Text(stringResource(R.string.acl_remove_message, userId)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTarget = null
                        onRemoveMember(group, userId, blacklist)
                    },
                ) { Text(stringResource(R.string.acl_action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (mailApprove) {
        AlertDialog(
            onDismissRequest = { mailApprove = false },
            title = { Text(stringResource(R.string.acl_mail_approve_title)) },
            text = { Text(stringResource(R.string.acl_mail_approve_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mailApprove = false
                        onAct("pending/approve", "")
                    },
                ) { Text(stringResource(R.string.acl_action_approve)) }
            },
            dismissButton = {
                TextButton(onClick = { mailApprove = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun PendingCard(
    entry: AclPendingDto,
    checked: Boolean,
    onToggle: () -> Unit,
    onRemark: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.label, fontWeight = FontWeight.Medium)
                val group = entry.channel.ifBlank { entry.agentId }
                val time = timeLabel(entry.timestamp)
                if (group.isNotBlank() || time.isNotBlank()) {
                    Text(
                        text = listOf(group, time).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.effectiveFirstMessage.isNotBlank()) {
                    Text(entry.effectiveFirstMessage, style = MaterialTheme.typography.bodySmall)
                }
                if (entry.remark.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.acl_remark_prefix, entry.remark),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onRemark) {
                Text(stringResource(R.string.acl_action_remark))
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: String,
    acl: AclChannelDto,
    onAdd: (blacklist: Boolean) -> Unit,
    onRemove: (userId: String, blacklist: Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(group, fontWeight = FontWeight.Medium)
            ListBlock(
                title = stringResource(R.string.acl_whitelist),
                entries = acl.whitelist,
                onAdd = { onAdd(false) },
                onRemove = { onRemove(it, false) },
            )
            Divider(modifier = Modifier.padding(vertical = 6.dp))
            ListBlock(
                title = stringResource(R.string.acl_blacklist),
                entries = acl.blacklist,
                onAdd = { onAdd(true) },
                onRemove = { onRemove(it, true) },
            )
        }
    }
}

@Composable
private fun ListBlock(
    title: String,
    entries: Map<String, io.qwenpaw.node.data.model.AclUserInfoDto>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title + " (" + entries.size + ")",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAdd) { Text(stringResource(R.string.acl_action_add)) }
    }
    entries.forEach { (id, info) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.username
                        .ifBlank { info.displayName }
                        .ifBlank { id }
                        .ifBlank { stringResource(R.string.acl_unknown_user) },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (info.remark.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.acl_remark_prefix, info.remark),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { onRemove(id) }) {
                Text(stringResource(R.string.acl_action_remove))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
