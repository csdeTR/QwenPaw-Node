package io.qwenpaw.node.ui.skills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.SkillWorkspaceDto
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle

/** 技能池新建/编辑全屏页。 */
@Composable
fun SkillPoolEditorScreen(
    editor: PoolEditState?,
    workspaces: List<SkillWorkspaceDto>,
    onBack: () -> Unit,
    onSave: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onContent: (String) -> Unit,
    onConfig: (String) -> Unit,
    onTagDraft: (String) -> Unit,
    onAddTags: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onSetAutoUpdate: (Boolean) -> Unit,
    onSetAutoSync: (Boolean) -> Unit,
    onToggleTarget: (String) -> Unit,
) {
    if (editor == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = editor.name != editor.savedName ||
        editor.content != editor.savedContent ||
        editor.configText != editor.savedConfigText
    fun requestExit() {
        if (editor.saving) return
        if (dirty) confirmDiscard = true else onBack()
    }

    BackHandler { requestExit() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircle(
                    icon = QwenIcons.arrowLeft,
                    description = stringResource(R.string.action_back),
                    onClick = ::requestExit,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(
                        if (editor.renameFrom == null) {
                            R.string.skill_pool_editor_title_new
                        } else {
                            R.string.skill_pool_editor_title_edit
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSave(editor.suggestedName != null) },
                    enabled = !editor.saving,
                ) { Text(stringResource(R.string.skill_pool_save)) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = editor.name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.skill_pool_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editor.content,
                onValueChange = onContent,
                label = { Text(stringResource(R.string.skill_pool_content)) },
                minLines = 14,
                maxLines = 30,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editor.configText,
                onValueChange = onConfig,
                label = { Text(stringResource(R.string.skill_pool_config)) },
                minLines = 4,
                maxLines = 12,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            SectionCard(stringResource(R.string.skill_pool_tags)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    editor.tags.forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable { onRemoveTag(tag) },
                        ) {
                            Text(
                                text = "$tag ×",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                    }
                    if (editor.tags.isEmpty()) {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = editor.tagDraft,
                        onValueChange = onTagDraft,
                        label = { Text(stringResource(R.string.skill_pool_tags_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAddTags) {
                        Text(stringResource(R.string.skill_pool_add_tag))
                    }
                }
            }
            SectionCard(stringResource(R.string.skill_pool_automation)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.skill_pool_auto_update),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = editor.autoUpdate,
                        onCheckedChange = onSetAutoUpdate,
                        enabled = editor.builtin,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.skill_pool_auto_sync),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = editor.autoSync,
                        onCheckedChange = onSetAutoSync,
                    )
                }
                if (editor.autoSync) {
                    Text(
                        text = stringResource(R.string.skill_pool_targets),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    workspaces.forEach { ws ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = ws.agentId in editor.targets,
                                onCheckedChange = { onToggleTarget(ws.agentId) },
                            )
                            Text(
                                text = ws.agentName.ifBlank { ws.agentId },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            editor.suggestedName?.let { suggested ->
                Text(
                    text = suggested,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            editor.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.skill_pool_discard_title)) },
            text = { Text(stringResource(R.string.skill_pool_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onBack()
                    },
                ) { Text(stringResource(R.string.skill_pool_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.skill_pool_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            content()
        }
    }
}