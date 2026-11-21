package io.qwenpaw.node.ui.skills

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.SkillWorkspaceDto
import io.qwenpaw.node.ui.theme.QwenIcons
import io.qwenpaw.node.ui.workspace.IconCircle

private const val STEP_WORKSPACES = 0
private const val STEP_SKILLS = 1

/**
 * 从工作区导入技能池的二级页。
 *
 * 第一步选工作区，第二步勾选技能；返回键先从第二步退回第一步，再从第一步退回技能池。
 * 页面只读 [SkillPoolUiState] 与回调，宿主负责把它挂到导航与 ViewModel 上。
 */
@Composable
fun SkillPoolImportScreen(
    state: SkillPoolUiState,
    onBack: () -> Unit,
    onWorkspace: (String) -> Unit,
    onWorkspaceQuery: (String) -> Unit,
    onSkillQuery: (String) -> Unit,
    onToggleSkill: (String) -> Unit,
    onSelectAllSkills: () -> Unit,
    onClearSkills: () -> Unit,
    onOverwrite: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismissNotice: () -> Unit = {},
) {
    var step by rememberSaveable { mutableStateOf(STEP_WORKSPACES) }
    val workspace = state.workspaces.firstOrNull { it.agentId == state.uploadWorkspace }
    val workspaces = state.filteredUploadWorkspaces()
    val skills = state.uploadSkillCandidates()

    fun goBack() {
        if (step == STEP_SKILLS) step = STEP_WORKSPACES else onBack()
    }

    // 选择被清空（例如宿主动了 ViewModel 状态）时退回第一步，避免停在空白的第二步。
    LaunchedEffect(workspace) {
        if (workspace == null && step == STEP_SKILLS) step = STEP_WORKSPACES
    }
    BackHandler { goBack() }

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
                    onClick = ::goBack,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.skill_pool_upload_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        bottomBar = {
            if (step == STEP_SKILLS && workspace != null) {
                ImportActions(
                    selectedCount = state.uploadSkills.size,
                    uploading = state.uploading,
                    overwrite = state.uploadOverwrite,
                    onOverwrite = onOverwrite,
                    onConfirm = onConfirm,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.message?.let { message ->
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    NoticeBanner(message, error = false, onDismiss = onDismissNotice)
                }
            }
            state.error?.let { message ->
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    NoticeBanner(message, error = true, onDismiss = onDismissNotice)
                }
            }
            when {
                state.loading && state.workspaces.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                step == STEP_WORKSPACES -> WorkspaceStep(
                    query = state.uploadWorkspaceQuery,
                    workspaces = workspaces,
                    noWorkspaces = state.workspaces.isEmpty(),
                    onQuery = onWorkspaceQuery,
                    onOpen = { agentId ->
                        onWorkspace(agentId)
                        step = STEP_SKILLS
                    },
                )

                workspace != null -> SkillStep(
                    state = state,
                    workspace = workspace,
                    skills = skills,
                    onQuery = onSkillQuery,
                    onToggle = onToggleSkill,
                    onSelectAll = onSelectAllSkills,
                    onClear = onClearSkills,
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun WorkspaceStep(
    query: String,
    workspaces: List<SkillWorkspaceDto>,
    noWorkspaces: Boolean,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ImportSearch(
            value = query,
            onValueChange = onQuery,
            placeholder = R.string.skill_pool_import_workspace_search,
        )
        if (workspaces.isEmpty()) {
            ImportEmpty(
                if (noWorkspaces) {
                    R.string.skill_pool_import_no_workspaces
                } else {
                    R.string.skill_pool_import_no_match
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(workspaces, key = { it.agentId }) { workspace ->
                    WorkspaceRow(workspace = workspace, onOpen = { onOpen(workspace.agentId) })
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(workspace: SkillWorkspaceDto, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = workspace.agentName.ifBlank { workspace.agentId },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(
                    R.string.skill_pool_import_skill_count,
                    workspace.skillNames.size,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkillStep(
    state: SkillPoolUiState,
    workspace: SkillWorkspaceDto,
    skills: List<String>,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(
                R.string.skill_pool_import_workspace_name,
                workspace.agentName.ifBlank { workspace.agentId },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        ImportSearch(
            value = state.uploadSkillQuery,
            onValueChange = onQuery,
            placeholder = R.string.skill_pool_import_skill_search,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.acl_selected, state.uploadSkills.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            TextButton(onClick = onSelectAll, enabled = skills.isNotEmpty()) {
                Text(stringResource(R.string.skill_pool_select_all))
            }
            TextButton(onClick = onClear, enabled = state.uploadSkills.isNotEmpty()) {
                Text(stringResource(R.string.skill_pool_select_none))
            }
        }
        if (skills.isEmpty()) {
            ImportEmpty(
                if (workspace.skillNames.isEmpty()) {
                    R.string.skill_pool_upload_no_skills
                } else {
                    R.string.skill_pool_import_no_match
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(skills, key = { it }) { name ->
                    SkillRow(
                        name = name,
                        checked = name in state.uploadSkills,
                        onToggle = { onToggle(name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 复选框只是状态显示，点击交给整行处理。
            Checkbox(checked = checked, onCheckedChange = null)
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ImportSearch(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes placeholder: Int,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(placeholder)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun ImportEmpty(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 32.dp),
    )
}

/** 第二步底栏：覆盖开关与导入按钮，始终可点。 */
@Composable
private fun ImportActions(
    selectedCount: Int,
    uploading: Boolean,
    overwrite: Boolean,
    onOverwrite: (Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skill_pool_upload_overwrite),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = overwrite, onCheckedChange = onOverwrite, enabled = !uploading)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(
                    onClick = onConfirm,
                    enabled = selectedCount > 0 && !uploading,
                ) {
                    Text(
                        if (selectedCount > 0) {
                            stringResource(R.string.skill_pool_import_confirm_count, selectedCount)
                        } else {
                            stringResource(R.string.skill_pool_upload_confirm)
                        },
                    )
                }
            }
        }
    }
}
