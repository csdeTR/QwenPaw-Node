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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
            message = "本机安全存储不可用，无法保存 SSH 直连凭据"
            messageError = true
            return
        }
        if (profile.addresses.isEmpty()) {
            message = "请至少添加一个 SSH 地址"
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
                    message = "请粘贴 SSH 私钥"
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
                    message = "请填写 SSH 登录密码"
                    messageError = true
                    return
                }
                SshSecret(password = passwordInput.takeIf { it.isNotBlank() })
            }
        }
        if (!store.save(instanceId, profile, secret)) {
            message = "保存失败：本机安全存储不可用"
            messageError = true
            return
        }
        hasKey = store.hasCredentials(instanceId, SshAuthType.KEY)
        hasPassword = store.hasCredentials(instanceId, SshAuthType.PASSWORD)
        keyInput = ""
        passphraseInput = ""
        passwordInput = ""
        message = "已保存；终端页的「SSH 直连」立即生效"
        messageError = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text("SSH 直连")
                        Text(
                            instanceName.ifBlank { instanceId },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(QwenIcons.arrowLeft, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "只从本机直接连接目标 SSH，不经过配套插件；私钥与口令加密保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("连接地址", style = MaterialTheme.typography.titleSmall)
                    if (profile.addresses.isEmpty()) {
                        Text(
                            "还没有地址。添加 host、端口与用户名后，可用手机直连该 SSH。",
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
                                        (if (address.default) " · 默认" else "") +
                                        (if (address.preferOnVpn) " · VPN 优先" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { editing = address }) { Text("编辑") }
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
                            }) { Text("删除") }
                        }
                    }
                    OutlinedButton(onClick = { adding = true }) { Text("添加地址") }
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("认证方式", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = profile.auth == SshAuthType.KEY,
                            onClick = { profile = profile.copy(auth = SshAuthType.KEY) },
                            label = { Text("公钥") },
                        )
                        FilterChip(
                            selected = profile.auth == SshAuthType.PASSWORD,
                            onClick = { profile = profile.copy(auth = SshAuthType.PASSWORD) },
                            label = { Text("密码") },
                        )
                    }
                    if (profile.auth == SshAuthType.KEY) {
                        Text(
                            if (hasKey) "已保存私钥；留空表示保留原值" else "尚未配置私钥",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text("粘贴 RSA/OpenSSH 私钥") },
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
                            Text(if (showKey) "隐藏私钥" else "显示私钥")
                        }
                        OutlinedTextField(
                            value = passphraseInput,
                            onValueChange = { passphraseInput = it },
                            label = { Text("私钥 passphrase（可选，留空保留）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            if (hasPassword) "已保存密码；留空表示保留原值" else "尚未配置密码",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("SSH 登录密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "首次连接仍需核对主机指纹；指纹变化会拒绝连接，不会自动接受。",
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
                    Text("连接行为", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("与当前 QwenPaw 实例同机")
                            Text(
                                "连接后进入智能体工作区；目标不是这台 QwenPaw 时请关闭。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = profile.sameHost,
                            onCheckedChange = { profile = profile.copy(sameHost = it) },
                        )
                    }
                    Text("远端平台", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            TerminalPlatform.AUTO to "自动",
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
                    Text("默认 shell", style = MaterialTheme.typography.labelMedium)
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
                                            "请先确认目标 Windows 已安装 Bash（Git Bash 或 WSL）；未安装时命令会失败。",
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
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
            Spacer(Modifier.height(12.dp))
        }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加 SSH 地址" else "编辑 SSH 地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("别名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机名或 IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                    Text("设为默认地址")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = preferVpn, onCheckedChange = { preferVpn = it })
                    Text("VPN/代理下优先")
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
                    error = "主机与用户名不能为空"
                } else if (parsedPort == null || parsedPort !in 1..65535) {
                    error = "端口需在 1–65535"
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
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
