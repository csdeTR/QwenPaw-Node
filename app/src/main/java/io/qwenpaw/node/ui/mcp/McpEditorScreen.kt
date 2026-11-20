package io.qwenpaw.node.ui.mcp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.ChoiceDialog
import io.qwenpaw.node.ui.theme.qwenSwitchColors

/**
 * MCP 客户端的编辑页。
 *
 * 表单按 `transport` 分两套：本机进程只认命令、参数、环境变量与工作目录；
 * 远端两种只认地址、请求头与超时。切换传输方式时由 [McpEditDraft.withTransport]
 * 把另一侧字段清空，页面不自己拼请求体。
 *
 * 新建时 `key` 可填、编辑时只读：服务端用它做路由与凭据目录名，改名等于换了一个客户端。
 * 工具白名单与访问策略不在这里，编辑页只碰连接参数。
 */
@Composable
internal fun McpEditorScreen(
    editor: McpEditorState,
    onDraftChange: (McpDraftState) -> Unit,
    onSave: () -> Unit,
    onExit: () -> Unit,
) {
    val draft = editor.draft
    var confirmDiscard by remember { mutableStateOf(false) }

    fun requestExit() {
        if (editor.saving) return
        if (editor.dirty) confirmDiscard = true else onExit()
    }

    BackHandler { requestExit() }

    Column(modifier = Modifier.fillMaxSize()) {
        AdminTopBar(
            title = stringResource(
                if (draft.isNew) R.string.mcp_admin_title_new else R.string.mcp_admin_title_edit,
            ),
            onBack = ::requestExit,
            action = stringResource(R.string.mcp_admin_action_save),
            busy = editor.saving,
            onAction = onSave,
        )
        BoxDivider()
        editor.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IdentitySection(
                draft = draft,
                keyError = editor.fieldErrors[McpDraftField.KEY],
                nameError = editor.fieldErrors[McpDraftField.NAME],
                onDraftChange = onDraftChange,
            )
            TransportSection(draft = draft, onDraftChange = onDraftChange)
            ConnectionSection(
                draft = draft,
                errors = editor.fieldErrors,
                onDraftChange = onDraftChange,
            )
            Text(
                text = stringResource(R.string.mcp_admin_hint_secrets),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!draft.isNew) {
                Text(
                    text = stringResource(R.string.mcp_admin_hint_edit_scope),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDiscard) {
        ChoiceDialog(
            title = stringResource(R.string.mcp_admin_discard_title),
            message = stringResource(R.string.mcp_admin_discard_message),
            choices = listOf(stringResource(R.string.mcp_admin_discard_confirm)),
            onPick = {
                confirmDiscard = false
                onExit()
            },
            onDismiss = { confirmDiscard = false },
            dismissLabel = stringResource(R.string.mcp_admin_discard_keep),
        )
    }
}

@Composable
private fun IdentitySection(
    draft: McpDraftState,
    keyError: McpDraftError?,
    nameError: McpDraftError?,
    onDraftChange: (McpDraftState) -> Unit,
) {
    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            SectionTitle(stringResource(R.string.mcp_admin_label_identity))
            if (draft.isNew) {
                Field(
                    label = stringResource(R.string.mcp_admin_label_key),
                    hint = stringResource(R.string.mcp_admin_hint_key),
                    value = draft.key,
                    error = draftErrorText(keyError),
                    onValueChange = { onDraftChange(draft.copy(key = it)) },
                )
            } else {
                ReadOnlyRow(
                    label = stringResource(R.string.mcp_admin_label_key),
                    value = draft.key,
                    hint = stringResource(R.string.mcp_admin_hint_key),
                )
            }
            Spacer(Modifier.height(10.dp))
            Field(
                label = stringResource(R.string.mcp_admin_label_name),
                value = draft.name,
                error = draftErrorText(nameError),
                onValueChange = { onDraftChange(draft.copy(name = it)) },
            )
            Spacer(Modifier.height(10.dp))
            Field(
                label = stringResource(R.string.mcp_admin_label_description),
                value = draft.description,
                singleLine = false,
                onValueChange = { onDraftChange(draft.copy(description = it)) },
            )
            if (draft.isNew) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mcp_admin_label_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                        colors = qwenSwitchColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportSection(draft: McpDraftState, onDraftChange: (McpDraftState) -> Unit) {
    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            SectionTitle(stringResource(R.string.mcp_admin_label_transport))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McpEditDraft.transports.forEach { transport ->
                    TabChip(
                        text = McpPolicyEdit.transportLabel(transport),
                        selected = draft.transport == transport,
                        onClick = {
                            onDraftChange(McpEditDraft.withTransport(draft, transport))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionSection(
    draft: McpDraftState,
    errors: Map<McpDraftField, McpDraftError>,
    onDraftChange: (McpDraftState) -> Unit,
) {
    CardBox {
        Column(modifier = Modifier.padding(14.dp)) {
            SectionTitle(stringResource(R.string.mcp_admin_label_connection))
            if (draft.transport == McpEditDraft.STDIO) {
                Field(
                    label = stringResource(R.string.mcp_admin_label_command),
                    hint = stringResource(R.string.mcp_admin_hint_command),
                    value = draft.command,
                    error = draftErrorText(errors[McpDraftField.COMMAND]),
                    onValueChange = { onDraftChange(draft.copy(command = it)) },
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    label = stringResource(R.string.mcp_admin_label_args),
                    hint = stringResource(R.string.mcp_admin_hint_args),
                    value = draft.argsText,
                    singleLine = false,
                    onValueChange = { onDraftChange(draft.copy(argsText = it)) },
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    label = stringResource(R.string.mcp_admin_label_env),
                    hint = stringResource(R.string.mcp_admin_hint_env),
                    value = draft.envText,
                    singleLine = false,
                    error = draftErrorText(errors[McpDraftField.ENV]),
                    onValueChange = { onDraftChange(draft.copy(envText = it)) },
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    label = stringResource(R.string.mcp_admin_label_cwd),
                    value = draft.cwd,
                    onValueChange = { onDraftChange(draft.copy(cwd = it)) },
                )
            } else {
                Field(
                    label = stringResource(R.string.mcp_admin_label_url),
                    hint = stringResource(R.string.mcp_admin_hint_url),
                    value = draft.url,
                    error = draftErrorText(errors[McpDraftField.URL]),
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { onDraftChange(draft.copy(url = it)) },
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    label = stringResource(R.string.mcp_admin_label_headers),
                    hint = stringResource(R.string.mcp_admin_hint_headers),
                    value = draft.headersText,
                    singleLine = false,
                    error = draftErrorText(errors[McpDraftField.HEADERS]),
                    onValueChange = { onDraftChange(draft.copy(headersText = it)) },
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    label = stringResource(R.string.mcp_admin_label_timeout),
                    hint = stringResource(R.string.mcp_admin_hint_timeout),
                    value = draft.httpTimeoutText,
                    keyboardType = KeyboardType.Decimal,
                    error = draftErrorText(errors[McpDraftField.TIMEOUT]),
                    onValueChange = { onDraftChange(draft.copy(httpTimeoutText = it)) },
                )
            }
        }
    }
}

/** 一个输入框：标签、可选说明、输入框本身、下方错误行。 */
@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            isError = error != null,
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = keyboardType,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (singleLine) Modifier else Modifier.heightIn(min = 96.dp)),
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String, hint: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BoxDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 一条校验错误的中文文案。行号类的错误把行号插进去，避免用户从头数到尾。 */
@Composable
private fun draftErrorText(error: McpDraftError?): String? {
    if (error == null) return null
    return when (error.issue) {
        McpDraftIssue.KEY_REQUIRED -> stringResource(R.string.mcp_admin_error_key_required)
        McpDraftIssue.KEY_RESERVED ->
            stringResource(R.string.mcp_admin_error_key_reserved, error.detail)

        McpDraftIssue.NAME_REQUIRED -> stringResource(R.string.mcp_admin_error_name_required)
        McpDraftIssue.COMMAND_REQUIRED -> stringResource(R.string.mcp_admin_error_command_required)
        McpDraftIssue.URL_REQUIRED -> stringResource(R.string.mcp_admin_error_url_required)
        McpDraftIssue.URL_SCHEME -> stringResource(R.string.mcp_admin_error_url_scheme)
        McpDraftIssue.TIMEOUT_NUMBER -> stringResource(R.string.mcp_admin_error_timeout_number)
        McpDraftIssue.ENV_LINE -> stringResource(R.string.mcp_admin_error_env_line, error.line)
        McpDraftIssue.HEADER_LINE -> stringResource(R.string.mcp_admin_error_header_line, error.line)
    }
}

/** 素色卡片，与 MCP 页其余分块一致。 */
@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) { content() }
}
