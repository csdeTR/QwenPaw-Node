package io.qwenpaw.node.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.NetworkMonitor
import io.qwenpaw.node.data.local.AddressEntry
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.local.InstanceDraft
import io.qwenpaw.node.data.model.AgentDto
import kotlinx.coroutines.launch

/** 验证过程中的进度：第几次尝试、共几次、正在试哪条地址。 */
data class VerifyAttempt(val index: Int, val total: Int, val url: String)

/**
 * 实例表单：地址 → 读取智能体列表 → 选一个。
 *
 * 首次连接页和设置页内的「添加实例」共用这套交互，避免出现两套不同逻辑。
 * 用户名密码与智能体 id 都是可选项，收在高级选项里；常用路径是填地址、
 * 读列表、点一个智能体。
 */
@Composable
fun InstanceForm(
    showHeader: Boolean = true,
    connecting: Boolean,
    error: String?,
    /** 预填地址，编辑已有实例时用。第一行为默认地址。 */
    initialAddresses: List<AddressEntry> = emptyList(),
    /** 预填实例显示名，编辑已有实例时用。 */
    initialName: String = "",
    initialAgentId: String = "default",
    /** 不显示「已保存的实例」区块时为 null。 */
    saved: List<InstanceConfig>? = null,
    onUseSaved: ((InstanceConfig) -> Unit)? = null,
    onConnect: (InstanceDraft) -> Unit,
    onLoadAgents: suspend (hosts: List<String>, username: String, password: String) -> List<AgentDto>,
    /** 轻量验证地址（只查版本），成功返回命中的地址与版本描述。 */
    onVerifyHosts: suspend (hosts: List<String>, onAttempt: (VerifyAttempt) -> Unit) -> io.qwenpaw.node.data.VerifyResult,
    /** 已有凭据时优先用它拉列表（编辑已有实例）。 */
    loadAgentsWithToken: (suspend () -> List<AgentDto>)? = null,
    /** 当前网络，用于地址编辑区提示「这条现在会被优先使用」。 */
    currentNetKind: String = NetworkMonitor.Snapshot.KIND_OTHER,
    currentSsid: String? = null,
    submitLabel: String = "连接",
    onCancel: (() -> Unit)? = null,
) {
    // 至少保留一行输入框，否则用户没有地方填地址。
    var addresses by remember {
        mutableStateOf(initialAddresses.ifEmpty { listOf(AddressEntry(url = "")) })
    }
    // 实例显示名。与地址别名是两件事：前者区分「家里那台」，后者只改某条地址的显示。
    var instanceName by remember { mutableStateOf(initialName) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agentId by remember { mutableStateOf(initialAgentId) }
    var advanced by remember { mutableStateOf(false) }
    var fixedPunctuation by remember { mutableStateOf(false) }

    var agents by remember { mutableStateOf<List<AgentDto>>(emptyList()) }
    var loadingAgents by remember { mutableStateOf(false) }
    var agentError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 地址是否已验证通过。步骤 1 的勾只在验证成功后出现——
    // 只看「地址非空」会让用户以为这一步已经完成。
    var hostVerified by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var verifyingAt by remember { mutableStateOf<VerifyAttempt?>(null) }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var verifyFailed by remember { mutableStateOf(false) }

    /** 有效地址：去掉空行。回退逻辑只关心这几个 URL。 */
    val effectiveUrls = addresses.map { it.url.trim() }.filter { it.isNotBlank() }
    val hostBlank = effectiveUrls.isEmpty()
    val picked = agents.firstOrNull { it.id == agentId }
    val step = when {
        !hostVerified -> 1
        agents.isEmpty() -> 2
        picked == null -> 3
        else -> 4
    }

    /** 地址列表变动后，之前读到的智能体与验证结果都作废。 */
    fun onAddressesChange(next: List<AddressEntry>) {
        addresses = next
        agents = emptyList()
        agentId = ""
        agentError = null
        hostVerified = false
        verifyMessage = null
        verifyFailed = false
        verifyingAt = null
    }

    /** 只验证地址可达，不拉智能体列表。多个地址时按顺序回退。 */
    fun verifyHosts() {
        if (hostBlank || verifying) return
        verifying = true
        verifyMessage = null
        verifyFailed = false
        verifyingAt = null
        scope.launch {
            runCatching {
                onVerifyHosts(effectiveUrls) { attempt -> verifyingAt = attempt }
            }
                .onSuccess { result ->
                    hostVerified = true
                    verifyFailed = false
                    val version = result.version
                    // 比较前要统一规范化：effectiveUrls 是用户原文（可能缺协议），
                    // result.baseUrl 已补齐协议，直接比字符串会把「未回退」误报成「已回退」。
                    val primaryNormalized = runCatching {
                        io.qwenpaw.node.data.InstanceRepository.normalizeBaseUrl(
                            effectiveUrls.first(),
                        )
                    }.getOrNull()
                    verifyMessage = buildString {
                        append("连接正常")
                        if (version.isNotBlank()) append("，服务端 ").append(version)
                        // 命中的不是第一个地址时明确说出来，让用户知道回退生效了。
                        if (primaryNormalized != null && result.baseUrl != primaryNormalized) {
                            append("，已回退到 ").append(result.baseUrl)
                        }
                    }
                }
                .onFailure { e ->
                    hostVerified = false
                    verifyFailed = true
                    verifyMessage = io.qwenpaw.node.data.remote.ErrorText.ofWithHint(
                        e,
                        io.qwenpaw.node.data.InstanceRepository.usesHttps(
                            effectiveUrls.firstOrNull().orEmpty(),
                        ),
                    )
                }
            verifying = false
            verifyingAt = null
        }
    }

    fun fetchAgents() {
        if (hostBlank || loadingAgents) return
        loadingAgents = true
        agentError = null
        scope.launch {
            // 优先用已有凭据；失败再回退到「地址 + 手填账号」这条路。
            val byToken = loadAgentsWithToken?.let { loader ->
                runCatching { loader() }.getOrNull()
            }
            val list = byToken?.takeIf { it.isNotEmpty() }
                ?: runCatching { onLoadAgents(effectiveUrls, username, password) }
                    .onFailure { e -> agentError = io.qwenpaw.node.data.remote.ErrorText.of(e) }
                    .getOrDefault(emptyList())
            agents = list
            if (list.size == 1) agentId = list.first().id
            loadingAgents = false
            // 能拉到列表说明地址一定可达，顺带把步骤 1 标记完成，
            // 这样用户不点「验证连接」、直接读列表也能推进。
            if (list.isNotEmpty()) {
                hostVerified = true
                verifyFailed = false
                verifyMessage = "连接正常"
            }
        }
    }

    // 地址已预填时自动读一次，编辑已有实例就不用再多点一下。
    LaunchedEffect(Unit) {
        if (initialAddresses.any { it.url.isNotBlank() }) fetchAgents()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showHeader) {
            Image(
                painter = painterResource(R.drawable.qwenpaw_logo),
                contentDescription = "QwenPaw",
                modifier = Modifier
                    .padding(top = 8.dp)
                    .height(94.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "连接到 QwenPaw",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                // 显式给色：这一层没有 Surface 提供 LocalContentColor，
                // 不写的话深色模式下会退到默认黑色。
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "数据始终存放在你自己的机器上",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))

            StepIndicator(step = step, total = 4)
            Spacer(Modifier.height(18.dp))
        }

        // 实例名称。放在地址上方，与地址别名在视觉上分开：
// 名称属于「这个实例叫什么」，别名属于「这条地址叫什么」。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "服务器名称",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "可选",
                style = MaterialTheme.typography.labelSmall,
                color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = instanceName,
            onValueChange = { instanceName = it },
            placeholder = { Text("例如「家里的机器」，留空则使用地址") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "服务器地址",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "必填",
                style = MaterialTheme.typography.labelSmall,
                color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
            )
        }
        Spacer(Modifier.height(6.dp))
        AddressListEditor(
            entries = addresses,
            onChange = { next, folded ->
                if (folded) fixedPunctuation = true
                onAddressesChange(next)
            },
            isError = error != null,
            currentKind = currentNetKind,
            currentSsid = currentSsid,
        )
        if (fixedPunctuation) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "检测到中文标点，已自动更正",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "未写 http:// 或 https:// 时，内网地址按 http 尝试，公网域名按 https 尝试。" +
                "若服务端只能通过 http 访问，请写明 http://。",
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
            modifier = Modifier.fillMaxWidth(),
        )

        // 「验证连接」：只查版本，不拉智能体列表。
        // 步骤 1 的勾以验证成功为准，而非「地址非空」。
        // 列表顺序回退的说明只在 AddressListEditor 里写一次，这里不重复。
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canVerify = !hostBlank && !verifying
            Text(
                text = when {
                    verifying -> "正在验证"
                    hostVerified -> "已验证"
                    else -> "验证连接"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    hostVerified -> MaterialTheme.colorScheme.secondary
                    canVerify -> MaterialTheme.colorScheme.primary
                    else -> io.qwenpaw.node.ui.theme.LocalCaptionColor.current
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        if (hostVerified) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = canVerify) { verifyHosts() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            if (verifying) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                )
            }
            val progress = verifyingAt
            when {
                // 多个地址逐个尝试时，把「第几个、哪条」写出来，
                // 否则用户只看到转圈，分不清是卡住还是在回退。
                progress != null && progress.total > 1 -> {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在尝试 ${progress.index}/${progress.total} · ${progress.url}",
                        style = MaterialTheme.typography.labelSmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                progress != null -> {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在验证 ${progress.url}",
                        style = MaterialTheme.typography.labelSmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                verifyMessage != null -> {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = verifyMessage.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (verifyFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            io.qwenpaw.node.ui.theme.LocalCaptionColor.current
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advanced = !advanced }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "高级选项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = advanced) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "用户名",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "可选",
                        style = MaterialTheme.typography.labelSmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "密码",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "可选",
                        style = MaterialTheme.typography.labelSmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "仅在服务端开启 Web 登录认证（QWENPAW_AUTH_ENABLED=true）时需要填写；" +
                        "本机或局域网直连一般留空。",
                    style = MaterialTheme.typography.labelSmall,
                    color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                // 智能体同样是「读列表再挑」，手填 id 只作为读不到列表时的备用。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "智能体 ID",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "备用方式",
                        style = MaterialTheme.typography.labelSmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = agentId,
                    onValueChange = { agentId = it },
                    placeholder = { Text("default") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 第二步：读智能体列表
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "选择智能体",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (agents.isEmpty()) {
                Text(
                    text = if (loadingAgents) "正在读取" else "读取列表",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hostBlank) {
                        io.qwenpaw.node.ui.theme.LocalCaptionColor.current
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !hostBlank && !loadingAgents) { fetchAgents() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    text = "重新读取",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !loadingAgents) { fetchAgents() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        when {
            loadingAgents -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正在读取智能体列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            agentError != null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp),
            ) {
                Text(
                    text = agentError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { fetchAgents() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            agents.isEmpty() -> Text(
                text = "填写地址后点击「读取列表」；也可先保存服务地址",
                style = MaterialTheme.typography.bodySmall,
                color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> Column {
                agents.forEach { agent ->
                    AgentRow(
                        agent = agent,
                        selected = agent.id == agentId,
                        onClick = { agentId = agent.id },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        if (!saved.isNullOrEmpty() && onUseSaved != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "已保存的实例",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            saved.forEach { instance ->
                SavedInstanceRow(instance = instance, onClick = { onUseSaved(instance) })
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(14.dp))

        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (connecting) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.primary,
                )
                .clickable(enabled = !connecting) {
                    onConnect(
                        InstanceDraft(
                            addresses = addresses.filter { it.url.isNotBlank() },
                            instanceName = instanceName,
                            username = username,
                            password = password,
                            agentId = agentId.ifBlank { "default" },
                        ),
                    )
                }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正在连接",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = submitLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (onCancel != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "取消",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !connecting, onClick = onCancel)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "连接时会检查服务端版本和智能体是否可用",
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 首次启动的连接页。
 *
 * 用三步引导而不是把整张表铺开：首次使用者不知道「实例名」「地址别名」
 * 「智能体 ID」各指什么，一次看到十几个输入框只会无从下手。
 * 已保存的实例仍列在下方——连不上时想切回旧实例，这是唯一的入口。
 */
@Composable
fun ConnectScreen(
    connecting: Boolean,
    error: String?,
    onConnect: (InstanceDraft) -> Unit,
    onLoadAgents: suspend (hosts: List<String>, username: String, password: String) -> List<AgentDto>,
    onVerifyHosts: suspend (hosts: List<String>, onAttempt: (VerifyAttempt) -> Unit) -> io.qwenpaw.node.data.VerifyResult,
    /** 服务端是否要求登录。引导第 2 步据此决定要不要填账号。 */
    onCheckAuth: suspend (address: String) -> Boolean,
    saved: List<InstanceConfig>,
    onUseSaved: (InstanceConfig) -> Unit,
) {
    // 连接页是裸页面（没有 Scaffold 帮忙处理系统栏），自己让开状态栏与手势条。
    // 引导组件也被设置页复用，那边包在 Scaffold 里、已由 Scaffold 注入留白，
    // 所以留白必须加在这一层，不能加进 InstanceWizard 内部，否则设置页会双重留白。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Image(
            painter = painterResource(R.drawable.qwenpaw_logo),
            contentDescription = "QwenPaw",
            modifier = Modifier
                .padding(top = 8.dp)
                .height(78.dp)
                .align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "连接到 QwenPaw",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "数据始终存放在你自己的机器上",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))

        // 引导体在外层滚动容器里，自己不滚：两层 verticalScroll 会互相抢手势。
        InstanceWizard(
            connecting = connecting,
            error = error,
            onFinish = onConnect,
            onCheckAuth = onCheckAuth,
            onLoadAgents = onLoadAgents,
            onVerifyHosts = onVerifyHosts,
            scrollable = false,
        )

        if (saved.isNotEmpty()) {
            Text(
                text = "已保存的实例",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            saved.forEach { instance ->
                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    SavedInstanceRow(instance = instance, onClick = { onUseSaved(instance) })
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 步骤进度：只画圆点与连线。
 *
 * 右侧原本还有一行「第 x 步，共 N 步」的灰色小字，与圆点表达的进度完全重复；
 * 当前步骤叫什么由下方加粗的标题说明，不必再复述一遍序号。
 */
@Composable
internal fun StepIndicator(step: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 1..total) {
            StepDot(index = index, done = index < step, active = index == step)
            if (index != total) {
                StepLine(done = index < step)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StepDot(index: Int, done: Boolean, active: Boolean) {
    val filled = done || active
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(
                if (filled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (filled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.onPrimary
                else io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
            )
        }
    }
}

@Composable
private fun StepLine(done: Boolean) {
    Box(
        modifier = Modifier
            .width(22.dp)
            .height(1.dp)
            .background(
                if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    )
}

@Composable
private fun AgentRow(agent: AgentDto, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agent.name.ifBlank { agent.id },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    // 同上：卡片在裸 Column 里，没有 Surface 传下 LocalContentColor。
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (agent.name.isNotBlank() && agent.name != agent.id) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = agent.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                        maxLines = 1,
                    )
                }
            }
            val desc = agent.description.orEmpty()
            if (desc.isNotBlank()) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            agent.activeModel?.model?.let { model ->
                Text(
                    text = "模型 $model",
                    style = MaterialTheme.typography.labelSmall,
                    color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选择",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun SavedInstanceRow(instance: InstanceConfig, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = instance.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(instance.labelOf(instance.primaryBaseUrl))
                    if (instance.backupCount > 0) {
                        append("（+").append(instance.backupCount).append(" 个备用）")
                    }
                    instance.serverVersion?.let { append(" 版本 ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "连接",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}