package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.chat.OptionDialog
import io.qwenpaw.node.ui.chat.OptionItem
import io.qwenpaw.node.ui.common.AdminTopBar
import io.qwenpaw.node.ui.common.SecretVisibilityTrailingIcon
import io.qwenpaw.node.ui.common.rememberSecretVisibility
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 添加自定义提供商。
 *
 * 三个字段之外只有一个协议下拉：ID 创建后不可改，其余配置都在详情页继续做，
 * 这一页只保证「先能创建出来」。
 */
@Composable
fun ProviderAddScreen(
    state: ProviderAddUiState,
    onBack: () -> Unit,
    onId: (String) -> Unit,
    onName: (String) -> Unit,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onProtocol: (ProviderProtocol) -> Unit,
    onSave: () -> Unit,
    onSaved: () -> Unit,
) {
    var pickingProtocol by remember { mutableStateOf(false) }
    val secret = rememberSecretVisibility()

    // 保存成功后由宿主刷新列表并返回；本页不自己决定去哪一页。
    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = io.qwenpaw.node.ui.common.pageContentInsets(),
        topBar = {
            AdminTopBar(
                title = stringResource(R.string.models_rework_add_title),
                onBack = onBack,
                action = stringResource(R.string.action_save),
                busy = state.saving,
                onAction = onSave,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.id,
                onValueChange = onId,
                label = { Text(stringResource(R.string.models_rework_field_id)) },
                supportingText = {
                    Text(stringResource(R.string.models_rework_field_id_hint))
                },
                isError = state.idError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.idError?.let { error ->
                Text(
                    text = stringResource(error.messageRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.models_rework_field_name)) },
                supportingText = {
                    Text(stringResource(R.string.models_rework_field_name_hint))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrl,
                label = { Text(stringResource(R.string.models_rework_field_base_url)) },
                supportingText = {
                    Text(stringResource(R.string.models_rework_field_base_url_hint))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = onApiKey,
                label = { Text(stringResource(R.string.models_rework_field_api_key)) },
                supportingText = {
                    Text(stringResource(R.string.models_rework_field_api_key_hint))
                },
                singleLine = true,
                visualTransformation = if (secret.visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    SecretVisibilityTrailingIcon(
                        visible = secret.visible,
                        onToggle = secret.toggle,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.models_rework_field_protocol),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { pickingProtocol = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(state.protocol.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = QwenIcons.chevronDown,
                            contentDescription = stringResource(
                                R.string.models_rework_protocol_title,
                            ),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.models_rework_add_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickingProtocol) {
        OptionDialog(
            title = stringResource(R.string.models_rework_protocol_title),
            items = ProviderProtocol.entries.map { protocol ->
                OptionItem(
                    key = protocol.name,
                    label = stringResource(protocol.labelRes),
                    selected = protocol == state.protocol,
                )
            },
            onPick = { key ->
                pickingProtocol = false
                ProviderProtocol.entries.firstOrNull { it.name == key }?.let(onProtocol)
            },
            onDismiss = { pickingProtocol = false },
        )
    }
}
