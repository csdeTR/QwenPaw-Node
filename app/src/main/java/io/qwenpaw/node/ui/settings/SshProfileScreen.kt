package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.terminal.SshAddress
import io.qwenpaw.node.data.terminal.SshAuthType
import io.qwenpaw.node.data.terminal.SshProfile
import io.qwenpaw.node.data.terminal.SshProfileStore
import io.qwenpaw.node.data.terminal.SshSecret
import io.qwenpaw.node.data.terminal.ShellBootstrap
import io.qwenpaw.node.data.terminal.TerminalPlatform
import io.qwenpaw.node.data.terminal.TerminalShell
import io.qwenpaw.node.ui.theme.QwenIcons
import java.util.UUID
import android.widget.Toast

/** 一个实例的 SSH 直连配置：地址、认证方式、同机与平台。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshProfileScreen(
    store: SshProfileStore,
    instanceId: String,
    instanceName: String,
    onBack: () -> Unit,
) {
    var profile by remember(instanceId) { mutableStateOf(store.load(instanceId) ?: SshProfile()) }
    var keyInput by remember { mutableStateOf("") }
    var passphraseInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var hasKey by remember(instanceId, profile.auth) {
        mutableStateOf(store.hasCredentials(instanceId, SshAuthType.KEY))
    }
    var hasPassword by remember(instanceId, profile.auth) {
        mutableStateOf(store.hasCredentials(instanceId, SshAuthType.PASSWORD))
    }
    var editing by remember { mutableStateOf<SshAddress?>(null) }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun save() {
        if (!store.available) {
            message = context.getString(R.string.sshprofile_storage_unavailable)
            messageError = true
            return
        }
        if (profile.addresses.isEmpty()) {
            message = context.getString(R.string.sshprofile_need_address)
            messageError = true
            return
        }
        if (profile.addresses.none { it.default }) {
            profile = profile.copy(addresses = profile.addresses.mapIndexed { index, item ->
                if (index == 0) item.copy(default = true) else item
            })
        }
        val secret = when (profile.auth) {
            SshAuthType.KEY -> {
                if (!hasKey && keyInput.isBlank()) {
                    message = context.getString(R.string.sshprofile_need_key)
                    messageError = true
                    return
                }
                SshSecret(
                    privateKey = keyInput.takeIf { it.isNotBlank() },
                    passphrase = passphraseInput.takeIf { it.isNotBlank() },
                )
            }
            SshAuthType.PASSWORD -> {
                if (!hasPassword && passwordInput.isBlank()) {
                    message = context.getString(R.string.sshprofile_need_password)
                    messageError = true
                    return
                }
                SshSecret(password = passwordInput.takeIf { it.isNotBlank() })
            }
        }
        if (!store.save(instanceId, profile, secret)) {
            message = context.getString(R.string.sshprofile_save_unavailable)
            messageError = true
            return
        }
        hasKey = store.hasCredentials(instanceId, SshAuthType.KEY)
        hasPassword = store.hasCredentials(instanceId, SshAuthType.PASSWORD)
        keyInput = ""
        passphraseInput = ""
        passwordInput = ""
        message = context.getString(R.string.sshprofile_saved)
        messageError = false
    }

    InstanceSubPage(
        title = stringResource(R.string.sshprofile_title),
        subtitle = instanceName.ifBlank { instanceId },
        onBack = onBack,
    ) {
            Text(
                stringResource(R.string.sshprofile_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.sshprofile_addresses_title), style = MaterialTheme.typography.titleSmall)
                    if (profile.addresses.isEmpty()) {
                        Text(
                            stringResource(R.string.sshprofile_addresses_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    profile.addresses.forEach { address ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    address.alias.ifBlank { "${address.user}@${address.host}" },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    "${address.user}@${address.host}:${address.port}" +
                                        (if (address.default) {
                                            stringResource(R.string.sshprofile_address_default_suffix)
                                        } else {
                                            ""
                                        }) +
                                        (if (address.preferOnVpn) {
                                            stringResource(R.string.sshprofile_address_vpn_suffix)
                                        } else {
                                            ""
                                        }),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { editing = address }) { Text(stringResource(R.string.action_edit)) }
                            TextButton(onClick = {
                                val remaining = profile.addresses.filterNot { it.id == address.id }
                                val normalized = if (remaining.none { it.default } && remaining.isNotEmpty()) {
                                    remaining.mapIndexed { index, item ->
                                        if (index == 0) item.copy(default = true) else item
                                    }
                                } else {
                                    remaining
                                }
                                profile = profile.copy(addresses = normalized)
                            }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                    OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.sshprofile_add_address)) }
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.sshprofile_auth_title), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = profile.auth == SshAuthType.KEY,
                            onClick = { profile = profile.copy(auth = SshAuthType.KEY) },
                            label = { Text(stringResource(R.string.sshprofile_auth_key)) },
                        )
                        FilterChip(
                            selected = profile.auth == SshAuthType.PASSWORD,
                            onClick = { profile = profile.copy(auth = SshAuthType.PASSWORD) },
                            label = { Text(stringResource(R.string.sshprofile_auth_password)) },
                        )
                    }
                    if (profile.auth == SshAuthType.KEY) {
                        Text(
                            if (hasKey) {
                                stringResource(R.string.sshprofile_key_saved)
                            } else {
                                stringResource(R.string.sshprofile_key_missing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text(stringResource(R.string.sshprofile_key_label)) },
                            minLines = 3,
                            maxLines = 6,
                            visualTransformation = if (showKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(
                                if (showKey) {
                                    stringResource(R.string.sshprofile_key_hide)
                                } else {
                                    stringResource(R.string.sshprofile_key_show)
                                },
                            )
                        }
                        OutlinedTextField(
                            value = passphraseInput,
                            onValueChange = { passphraseInput = it },
                            label = { Text(stringResource(R.string.sshprofile_passphrase_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            if (hasPassword) {
                                stringResource(R.string.sshprofile_password_saved)
                            } else {
                                stringResource(R.string.sshprofile_password_missing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text(stringResource(R.string.sshprofile_password_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        stringResource(R.string.sshprofile_fingerprint_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.sshprofile_behavior_title), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sshprofile_same_host_title))
                            Text(
                                stringResource(R.string.sshprofile_same_host_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = profile.sameHost,
                            onCheckedChange = { profile = profile.copy(sameHost = it) },
                        )
                    }
                    Text(stringResource(R.string.sshprofile_platform_title), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            TerminalPlatform.AUTO to stringResource(R.string.sshprofile_platform_auto),
                            TerminalPlatform.WINDOWS to "Windows",
                            TerminalPlatform.POSIX to "Linux/Unix",
                        ).forEach { (platform, label) ->
                            FilterChip(
                                selected = profile.platform == platform,
                                onClick = { profile = profile.copy(platform = platform) },
                                label = { Text(label) },
                            )
                        }
                    }
                    val targetPlatform = ShellBootstrap.resolve(profile.platform, null)
                    Text(stringResource(R.string.sshprofile_shell_title), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShellBootstrap.options(targetPlatform).forEach { shell ->
                            FilterChip(
                                selected = profile.shell == shell,
                                onClick = {
                                    if (shell == TerminalShell.GIT_BASH) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.sshprofile_git_bash_hint),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    profile = profile.copy(shell = shell)
                                },
                                label = { Text(ShellBootstrap.label(shell)) },
                            )
                        }
                    }
                }
            }

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
            Spacer(Modifier.height(12.dp))
        }

    if (adding || editing != null) {
        val initial = editing
        AddressDialog(
            initial = initial,
            first = profile.addresses.isEmpty(),
            onDismiss = { adding = false; editing = null },
            onSave = { address ->
                val without = profile.addresses.filterNot { it.id == address.id }
                val withNew = without + address
                val normalized = if (address.default) {
                    withNew.map { if (it.id == address.id) it else it.copy(default = false) }
                } else if (withNew.none { it.default }) {
                    withNew.mapIndexed { index, item ->
                        if (index == 0) item.copy(default = true) else item
                    }
                } else {
                    withNew
                }
                profile = profile.copy(addresses = normalized)
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun AddressDialog(
    initial: SshAddress?,
    first: Boolean,
    onDismiss: () -> Unit,
    onSave: (SshAddress) -> Unit,
) {
    var alias by remember(initial) { mutableStateOf(initial?.alias.orEmpty()) }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember(initial) { mutableStateOf(initial?.user.orEmpty()) }
    var isDefault by remember(initial) { mutableStateOf(initial?.default ?: first) }
    var preferVpn by remember(initial) { mutableStateOf(initial?.preferOnVpn ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    val errorHostUser = stringResource(R.string.sshprofile_error_host_user)
    val errorPort = stringResource(R.string.sshprofile_error_port)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) {
                    stringResource(R.string.sshprofile_dialog_add_title)
                } else {
                    stringResource(R.string.sshprofile_dialog_edit_title)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.sshprofile_field_alias)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.sshprofile_field_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.sshprofile_field_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.sshprofile_field_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                    Text(stringResource(R.string.sshprofile_switch_default))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = preferVpn, onCheckedChange = { preferVpn = it })
                    Text(stringResource(R.string.sshprofile_switch_vpn))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedPort = port.trim().toIntOrNull()
                if (host.isBlank() || user.isBlank()) {
                    error = errorHostUser
                } else if (parsedPort == null || parsedPort !in 1..65535) {
                    error = errorPort
                } else {
                    onSave(
                        SshAddress(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            alias = alias.trim(),
                            host = host.trim(),
                            port = parsedPort,
                            user = user.trim(),
                            default = isDefault,
                            preferOnVpn = preferVpn,
                        ),
                    )
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
