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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.BuiltinSourceDto
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle

/** 内置技能库：可滚动的全屏列表，每个技能独立选择语言版本。 */
@Composable
fun SkillPoolBuiltinScreen(
    state: SkillPoolUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onLanguage: (String, String) -> Unit,
    onImport: (Boolean) -> Unit,
    onUpdateBuiltin: (String, String) -> Unit,
    onDismissMessage: () -> Unit = {},
) {
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
                    onClick = onBack,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.skill_pool_builtin_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        state.builtinSources
                            .filter { it.name !in state.builtinSelection }
                            .forEach { onToggle(it.name) }
                    },
                ) { Text(stringResource(R.string.skill_pool_builtin_select_all)) }
                TextButton(
                    onClick = {
                        state.builtinSelection.forEach { onToggle(it) }
                    },
                ) { Text(stringResource(R.string.skill_pool_builtin_clear)) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = stringResource(R.string.skill_pool_builtin_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
            state.message?.let { message ->
                BuiltinNotice(message, error = false, onDismiss = onDismissMessage)
            }
            state.error?.let { message ->
                BuiltinNotice(message, error = true, onDismiss = onDismissMessage)
            }
            if (state.builtinSources.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.skill_pool_builtin_empty))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 4.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.builtinSources, key = { it.name }) { source ->
                        BuiltinRow(
                            source = source,
                            checked = source.name in state.builtinSelection,
                            language = state.builtinLanguages[source.name]
                                ?: source.currentLanguage.takeIf { it.isNotBlank() }
                                ?: "en",
                            onToggle = { onToggle(source.name) },
                            onLanguage = { lang -> onLanguage(source.name, lang) },
                            onUpdate = { lang -> onUpdateBuiltin(source.name, lang) },
                        )
                    }
                    if (state.builtinConflicts.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.skill_pool_import_conflict_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.skill_pool_import_conflict_message,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                state.builtinConflicts.forEach {
                                    Text(
                                        text = "- " + it.skillName + " (" + it.status + ")",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onImport(state.builtinConflicts.isNotEmpty()) },
                        enabled = state.builtinSelection.isNotEmpty() && !state.builtinSaving,
                    ) {
                        Text(
                            if (state.builtinConflicts.isNotEmpty()) {
                                stringResource(R.string.skill_pool_overwrite)
                            } else {
                                stringResource(R.string.skill_pool_import)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltinNotice(text: String, error: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (error) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun BuiltinRow(
    source: BuiltinSourceDto,
    checked: Boolean,
    language: String,
    onToggle: () -> Unit,
    onLanguage: (String) -> Unit,
    onUpdate: (String) -> Unit,
) {
    val languages = when {
        source.availableLanguages.isNotEmpty() -> source.availableLanguages
        source.languages.isNotEmpty() -> source.languages.keys.toList()
        else -> listOf("en", "zh")
    }.distinct().filter { it.isNotBlank() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = buildString {
                            if (source.currentLanguage.isNotBlank()) {
                                append(
                                    stringResource(
                                        R.string.skill_pool_builtin_installed,
                                        source.currentLanguage,
                                    ),
                                )
                            }
                            source.status.takeIf { it.isNotBlank() }?.let {
                                if (isNotEmpty()) append("，")
                                append(it)
                            }
                        }.ifBlank { stringResource(R.string.skill_pool_not_installed) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                languages.forEach { lang ->
                    Surface(
                        color = if (lang == language) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { onLanguage(lang) },
                    ) {
                        Text(
                            text = lang,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lang == language) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                if (source.currentLanguage.isNotBlank()) {
                    TextButton(onClick = { onUpdate(language) }) {
                        Text(stringResource(R.string.skill_pool_builtin_update))
                    }
                }
            }
        }
    }
}