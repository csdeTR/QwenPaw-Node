package io.qwenpaw.node.ui.skills

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.ConsoleHint
import io.qwenpaw.node.ui.common.WebEntryDialog
import io.qwenpaw.node.ui.common.pageContentInsets
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle

@Composable
fun SkillConfigScreen(
    state: SkillConfigUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onConfigChange: (String) -> Unit,
    onSetPreload: (Boolean) -> Unit,
    onTagDraftChange: (String) -> Unit,
    onAddTags: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onToggleChannel: (String) -> Unit,
    onRequestClear: () -> Unit,
    onConfirmClear: () -> Unit,
    onDismissClear: () -> Unit,
    onFilePathChange: (String) -> Unit,
    onReadFile: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    WebEntryDialog(pageKey = "skill_config", onOpenConsole = onOpenConsole)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = pageContentInsets(),
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
                    onClick = onBack,
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.skill_config_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.label.isNotBlank()) {
                        Text(
                            text = state.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onSave, enabled = !state.saving && !state.loading) {
                    Text(
                        if (state.saving) {
                            stringResource(R.string.skill_config_busy)
                        } else {
                            stringResource(R.string.skill_config_save)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.loaded && state.error != null && state.label.isBlank() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConsoleHint(onOpenConsole = onOpenConsole)
                    state.error?.let {
                        NoticeRow(it, true, onDismissNotice)
                    }
                    state.notice?.let {
                        NoticeRow(it, false, onDismissNotice)
                    }

                    SectionCard(stringResource(R.string.skill_config_json_label)) {
                        OutlinedTextField(
                            value = state.configText,
                            onValueChange = onConfigChange,
                            minLines = 6,
                            maxLines = 14,
                            isError = state.configInvalid,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.configInvalid) {
                            Text(
                                text = stringResource(R.string.skill_config_json_invalid),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            text = stringResource(R.string.skill_config_json_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onRequestClear, enabled = !state.saving) {
                                Text(stringResource(R.string.skill_config_clear))
                            }
                        }
                    }

                    SectionCard(stringResource(R.string.skill_config_preload)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.skill_config_preload_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = state.preload,
                                onCheckedChange = onSetPreload,
                            )
                        }
                    }

                    SectionCard(stringResource(R.string.skill_config_tags)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.tags.forEach { tag ->
                                TagChip(text = tag, onClick = { onRemoveTag(tag) })
                            }
                            if (state.tags.isEmpty()) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = state.tagDraft,
                                onValueChange = onTagDraftChange,
                                placeholder = {
                                    Text(stringResource(R.string.skill_config_tag_hint))
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onAddTags) {
                                Text(stringResource(R.string.skill_config_add_tag))
                            }
                        }
                    }

                    SectionCard(stringResource(R.string.skill_config_channels)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ChannelChip(
                                text = "all",
                                selected = "all" in state.channels,
                            ) { onToggleChannel("all") }
                            state.availableChannels.forEach { channel ->
                                ChannelChip(
                                    text = channel,
                                    selected = channel in state.channels,
                                ) { onToggleChannel(channel) }
                            }
                        }
                        Text(
                            text = stringResource(R.string.skill_config_channels_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SectionCard(stringResource(R.string.skill_config_file_title)) {
                        Text(
                            text = stringResource(R.string.skill_config_file_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = state.filePath,
                                onValueChange = onFilePathChange,
                                label = { Text(stringResource(R.string.skill_config_file_path)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onReadFile, enabled = !state.fileLoading) {
                                Text(stringResource(R.string.skill_config_file_read))
                            }
                        }
                        state.fileError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.fileContent.isNotEmpty()) {
                            SelectionContainer {
                                Text(
                                    text = state.fileContent,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 260.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        } else if (!state.fileLoading && state.fileError == null) {
                            Text(
                                text = stringResource(R.string.skill_config_file_empty),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.confirmClear) {
        AlertDialog(
            onDismissRequest = onDismissClear,
            title = { Text(stringResource(R.string.skill_config_clear_title)) },
            text = { Text(stringResource(R.string.skill_config_clear_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmClear) {
                    Text(stringResource(R.string.skill_config_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClear) {
                    Text(stringResource(R.string.action_cancel))
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

@Composable
private fun TagChip(text: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = "$text ×",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ChannelChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun NoticeRow(text: String, error: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (error) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.action_close))
        }
    }
}
