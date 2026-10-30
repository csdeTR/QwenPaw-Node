package io.qwenpaw.node.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.VerifyResult
import io.qwenpaw.node.data.local.AddressEntry
import io.qwenpaw.node.data.local.InstanceDraft
import io.qwenpaw.node.data.model.AgentDto
import kotlinx.coroutines.launch

/**
 * 引导式创建实例：一步一屏，每屏只处理一件事。
 *
 * 与 [InstanceForm] 的分工：那张表是「全部字段铺开」，适合已经熟悉这个应用、
 * 想一次改很多项的人；这里是首次启动的路径，使用者不知道有哪些概念，
 * 所以拆成「地址 → 账号 → 智能体」，每步只问必要信息，下一步按钮在
 * 这一步真做完之前保持不可点。
 *
 * 引导页不提供多地址：多数人只有一个地址，把「备用地址」「回退」这类概念
 * 摆在首次启动的路径上只会让人怀疑自己是不是漏填了什么。需要多地址的人
 * 可以在设置里用填表方式再补。
 */
@Composable
fun InstanceWizard(
    connecting: Boolean,
    error: String?,
    onFinish: (InstanceDraft) -> Unit,
    /** 查服务端是否要求登录。异常时返回 false，按「不需要」处理。 */
    onCheckAuth: suspend (String) -> Boolean,
    onLoadAgents: suspend (hosts: List<String>, username: String, password: String) -> List<AgentDto>,
    onVerifyHosts: suspend (
        hosts: List<String>,
        onAttempt: (VerifyAttempt) -> Unit,
    ) -> VerifyResult,
    /**
     * 是否自带滚动。
     *
     * 首次启动页在外层已经有滚动容器（它还要把「已保存的实例」接在下面），
     * 这里再套一层会两层抢手势；设置页的子页面则只有这一层，必须自己滚。
     */
    scrollable: Boolean = true,
) {
    var step by remember { mutableStateOf(1) }

    // 第 1 步
    var url by remember { mutableStateOf("") }
    var instanceName by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var verifyFailed by remember { mutableStateOf(false) }

    // 第 2 步
    var checkingAuth by remember { mutableStateOf(false) }
    var needsLogin by remember { mutableStateOf<Boolean?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // 第 3 步
    var agents by remember { mutableStateOf<List<AgentDto>>(emptyList()) }
    var loadingAgents by remember { mutableStateOf(false) }
    var agentError by remember { mutableStateOf<String?>(null) }
    var agentId by remember { mutableStateOf("") }
    var manualId by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun draft(): InstanceDraft = InstanceDraft(
        addresses = listOf(AddressEntry(url = url.trim(), alias = alias.trim())),
        instanceName = instanceName.trim(),
        username = username.trim(),
        password = password,
        agentId = agentId.ifBlank { "default" },
    )

    fun verify() {
        if (url.isBlank() || verifying) return
        verifying = true
        verifyMessage = null
        verifyFailed = false
        scope.launch {
            runCatching { onVerifyHosts(listOf(url.trim())) {} }
                .onSuccess { result ->
                    verified = true
                    verifyFailed = false
                    verifyMessage = buildString {
                        append("连接正常")
                        if (result.version.isNotBlank()) append("，服务端 ").append(result.version)
                    }
                }
                .onFailure { e ->
                    verified = false
                    verifyFailed = true
                    verifyMessage = io.qwenpaw.node.data.remote.ErrorText.ofWithHint(
                        e,
                        io.qwenpaw.node.data.InstanceRepository.usesHttps(url),
                    )
                }
            verifying = false
        }
    }

    fun loadAgents() {
        if (loadingAgents) return
        loadingAgents = true
        agentError = null
        scope.launch {
            runCatching { onLoadAgents(listOf(url.trim()), username, password) }
                .onSuccess { list ->
                    agents = list
                    if (list.size == 1) agentId = list.first().id
                }
                .onFailure { e -> agentError = io.qwenpaw.node.data.remote.ErrorText.of(e) }
            loadingAgents = false
        }
    }

    // 进入第 2 步时先问服务端要不要登录；不需要就直接推到第 3 步。
    //
    // 「是否已判定」与「是否要跳走」是两件事，不能因为缓存命中就整体跳过。
    // 此前写成 `if (needsLogin != null) return`，于是从第 3 步点「上一步」回到第 2 步时
    // 既不再自动跳到第 3 步，又因为 `needsLogin == false` 不渲染输入框，
    // 「下一步」的前提（账号密码非空）永远无法满足——第 2 步成了死页面。
    LaunchedEffect(step) {
        if (step != 2) return@LaunchedEffect
        val fresh = needsLogin == null
        if (fresh) {
            checkingAuth = true
            needsLogin = runCatching { onCheckAuth(url.trim()) }.getOrDefault(false)
            checkingAuth = false
        }
        if (needsLogin == false) {
            // 只在首次判定时提示；从第 3 步返回时不再重复弹，避免每次返回都被打断。
            if (fresh) {
                Toast.makeText(context, "该服务端未开启登录验证，已跳过", Toast.LENGTH_SHORT).show()
            }
            step = 3
        }
    }

    // 进入第 3 步自动读一次列表；已经有列表时不重复请求。
    LaunchedEffect(step) {
        if (step == 3 && agents.isEmpty() && !loadingAgents) loadAgents()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        StepIndicator(step = step, total = 3)
        Spacer(Modifier.height(18.dp))
        Text(
            text = when (step) {
                1 -> "服务器"
                2 -> "登录"
                else -> "智能体"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(14.dp))

        when (step) {
            1 -> StepServer(
                url = url,
                onUrl = {
                    url = it
                    // 地址一改动，之前的验证结论就作废，否则用户改了地址但按钮仍是亮的。
                    verified = false
                    verifyMessage = null
                    verifyFailed = false
                },
                instanceName = instanceName,
                onInstanceName = { instanceName = it },
                alias = alias,
                onAlias = { alias = it },
                verifying = verifying,
                verified = verified,
                verifyMessage = verifyMessage,
                verifyFailed = verifyFailed,
                onVerify = { verify() },
            )

            2 -> StepLogin(
                checking = checkingAuth,
                needsLogin = needsLogin == true,
                username = username,
                onUsername = { username = it },
                password = password,
                onPassword = { password = it },
            )

            else -> StepAgent(
                loading = loadingAgents,
                error = agentError,
                agents = agents,
                selectedId = agentId,
                onPick = { agentId = it },
                onRetry = { loadAgents() },
                manual = manualId,
                onToggleManual = { manualId = !manualId },
                manualText = manualText,
                onManualText = {
                    manualText = it
                    agentId = it.trim()
                },
            )
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))

        val canNext = when (step) {
            // 名称是可选项：留空时由 InstanceDraft.resolvedName() 回落到地址，
            // 与「填表添加」的名称字段口径一致。硬条件只有地址验证通过。
            1 -> verified && !verifying
            2 -> needsLogin == true &&
                username.isNotBlank() && password.isNotBlank() && !checkingAuth
            else -> agentId.isNotBlank() && !connecting
        }
        WizardButton(
            text = when (step) {
                1 -> "下一步"
                2 -> "下一步"
                else -> if (connecting) "正在连接" else "完成"
            },
            enabled = canNext,
            busy = connecting,
            onClick = {
                when (step) {
                    1 -> step = 2
                    2 -> step = 3
                    else -> onFinish(draft())
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        if (step > 1) {
            Text(
                text = "上一步",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !connecting) { step = wizardBackStep(step, needsLogin) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 点「上一步」时应当回到的步骤。
 *
 * 第 2 步在「服务端未开启登录验证」时是**不可停留**的：`LaunchedEffect` 会立刻
 * 把页面推回第 3 步，而第 2 步又没有输入框可填。此时从第 3 步返回若落在第 2 步，
 * 用户只会看到画面闪一下又被送回原处。因此这种情况下直接回第 1 步。
 *
 * 抽成纯函数是为了让「哪种组合回哪一步」可以脱离 Compose 断言。
 */
internal fun wizardBackStep(step: Int, needsLogin: Boolean?): Int = when {
    step <= 1 -> 1
    step == 3 && needsLogin == false -> 1
    else -> step - 1
}

/** 第 1 步：地址、实例名、该地址的别名。三者在表单里分开标注，不再互相推导。 */
@Composable
private fun StepServer(
    url: String,
    onUrl: (String) -> Unit,
    instanceName: String,
    onInstanceName: (String) -> Unit,
    alias: String,
    onAlias: (String) -> Unit,
    verifying: Boolean,
    verified: Boolean,
    verifyMessage: String?,
    verifyFailed: Boolean,
    onVerify: () -> Unit,
) {
    FieldLabel("服务器地址", "必填")
    OutlinedTextField(
        value = url,
        onValueChange = { onUrl(foldFullWidth(it)) },
        placeholder = { Text("例如 chat.example.com 或 192.168.1.10:8088") },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "未写 http:// 或 https:// 时，内网地址按 http 尝试，公网域名按 https 尝试。" +
            "若服务端只能通过 http 访问，请写明 http://。",
        style = MaterialTheme.typography.labelSmall,
        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
    )
    Spacer(Modifier.height(12.dp))

    FieldLabel("服务器名称", "可选")
    OutlinedTextField(
        value = instanceName,
        onValueChange = onInstanceName,
        placeholder = { Text("例如「家里的机器」") },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "将在实例列表显示，可随时修改，留空则显示地址",
        style = MaterialTheme.typography.labelSmall,
        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
    )
    Spacer(Modifier.height(12.dp))

    FieldLabel("此地址的别名", "可选")
    OutlinedTextField(
        value = alias,
        onValueChange = onAlias,
        placeholder = { Text("例如「局域网」「EasyTier」") },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "将在实例列表显示，可随时修改，留空则显示地址",
        style = MaterialTheme.typography.labelSmall,
        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
    )

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val canVerify = url.isNotBlank() && !verifying
        Text(
            text = when {
                verifying -> "正在验证"
                verified -> "已验证"
                else -> "验证连接"
            },
            style = MaterialTheme.typography.labelMedium,
            color = when {
                verified -> MaterialTheme.colorScheme.secondary
                canVerify -> MaterialTheme.colorScheme.primary
                else -> io.qwenpaw.node.ui.theme.LocalCaptionColor.current
            },
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    if (verified) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(8.dp),
                )
                .clickable(enabled = canVerify, onClick = onVerify)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (verifying) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
        }
        if (verified) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
    if (verifyMessage != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = verifyMessage,
            style = MaterialTheme.typography.labelSmall,
            color = if (verifyFailed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 第 2 步：只在服务端开启登录时才出现。 */
@Composable
private fun StepLogin(
    checking: Boolean,
    needsLogin: Boolean,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
) {
    if (checking) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "正在检查该服务器是否需要登录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (!needsLogin) {
        Text(
            text = "该服务器未开启登录验证，正在进入下一步。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        text = "这台服务器开启了登录验证，需要填写账号与密码。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    FieldLabel("用户名", "必填")
    OutlinedTextField(
        value = username,
        onValueChange = onUsername,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    FieldLabel("密码", "必填")
    OutlinedTextField(
        value = password,
        onValueChange = onPassword,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 第 3 步：读列表单选，手填 id 作为备用入口收在下面。 */
@Composable
private fun StepAgent(
    loading: Boolean,
    error: String?,
    agents: List<AgentDto>,
    selectedId: String,
    onPick: (String) -> Unit,
    onRetry: () -> Unit,
    manual: Boolean,
    onToggleManual: () -> Unit,
    manualText: String,
    onManualText: (String) -> Unit,
) {
    when {
        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "正在读取智能体列表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error != null -> Column {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }

        agents.isEmpty() -> Text(
            text = "没有读到可用的智能体，可以在下面手动填写 ID。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Column {
            agents.forEach { agent ->
                WizardAgentRow(
                    agent = agent,
                    selected = agent.id == selectedId,
                    onClick = { onPick(agent.id) },
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleManual)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (manual) "收起手动填写" else "手动填写 ID",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
    AnimatedVisibility(visible = manual) {
        Column {
            OutlinedTextField(
                value = manualText,
                onValueChange = onManualText,
                placeholder = { Text("default") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WizardAgentRow(agent: AgentDto, selected: Boolean, onClick: () -> Unit) {
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
            Text(
                text = agent.name.ifBlank { agent.id },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/** 一行字段标签。引导页里每个字段都带说明，避免用户猜「这个填什么」。 */
@Composable
private fun FieldLabel(title: String, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
    }
    Spacer(Modifier.height(6.dp))
}

/** 主按钮。不可点时会明显变灰，而不是只说「未完成」。 */
@Composable
private fun WizardButton(
    text: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
                    busy -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}