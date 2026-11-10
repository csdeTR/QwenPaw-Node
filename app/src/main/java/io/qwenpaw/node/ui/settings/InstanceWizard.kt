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
import androidx.compose.ui.platform.LocalUriHandler
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
import io.qwenpaw.node.ui.theme.QwenIcons

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
    /**
     * 试登录一次，判断账号密码是否可用。
     *
     * 返回 null 表示通过，否则返回要显示给用户的原因。第 2 步点「下一步」
     * 时先调它，失败就留在这一步并把原因弹出来。
     */
    onCheckLogin: suspend (
        url: String,
        username: String,
        password: String,
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth?,
    ) -> String?,
    onLoadAgents: suspend (
        hosts: List<String>,
        username: String,
        password: String,
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth?,
    ) -> List<AgentDto>,
    onVerifyHosts: suspend (
        hosts: List<String>,
        basicAuth: io.qwenpaw.node.data.remote.BasicAuth?,
        onAttempt: (VerifyAttempt) -> Unit,
    ) -> VerifyResult,
    /**
     * 是否自带滚动。
     *
     * 首次启动页在外层已经有滚动容器（它还要把「已保存的实例」接在下面），
     * 这里再套一层会两层抢手势；设置页的子页面则只有这一层，必须自己滚。
     */
    scrollable: Boolean = true,
    /** 该主机已在「受信任的连接」里时返回 true，此时不弹安全提示。 */
    isTrustedHost: (String) -> Boolean = { false },
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
    // 验证通过后检测到的安全风险。非空时弹窗，用户点「知道了」才清空。
    var securityNotice by remember { mutableStateOf<io.qwenpaw.node.data.SecurityNotice?>(null) }
    // 反向代理的 HTTP Basic 凭据。验证时被发现需要（proxyAuthRequired）就自动展开。
    var basicUser by remember { mutableStateOf("") }
    var basicPassword by remember { mutableStateOf("") }
    var proxyExpanded by remember { mutableStateOf(false) }
    var proxyRequired by remember { mutableStateOf(false) }

    // 第 2 步
    var checkingAuth by remember { mutableStateOf(false) }
    var needsLogin by remember { mutableStateOf<Boolean?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // 点「下一步」时正在试登录。与 checkingAuth 分开：前者是进这一步的自动判定，
    // 后者是用户主动触发的校验，两者都可能阻塞按钮。
    var checkingLogin by remember { mutableStateOf(false) }
    var loginIssue by remember { mutableStateOf<String?>(null) }

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
        basicUser = basicUser.trim(),
        basicPassword = basicPassword,
    )

    /** 反代凭据；两个都非空才生效。 */
    fun proxyAuth(): io.qwenpaw.node.data.remote.BasicAuth? =
        io.qwenpaw.node.data.remote.BasicAuth(basicUser.trim(), basicPassword)
            .takeIf { basicUser.isNotBlank() && basicPassword.isNotBlank() }

    fun verify() {
        if (url.isBlank() || verifying) return
        verifying = true
        verifyMessage = null
        verifyFailed = false
        scope.launch {
            runCatching { onVerifyHosts(listOf(url.trim()), proxyAuth()) {} }
                .onSuccess { result ->
                    verified = !result.proxyAuthRequired
                    verifyFailed = false
                    proxyRequired = result.proxyAuthRequired
                    if (result.proxyAuthRequired) {
                        // 不是「连不上」：地址是通的，被反代拦住了。就地展开凭据输入，
                        // 比让用户回到地址那一步重填有用得多。
                        proxyExpanded = true
                        verifyMessage = "该地址由反向代理保护，请填写反向代理账号与密码后重新验证"
                    } else {
                        verifyMessage = buildString {
                            append("连接正常")
                            if (result.version.isNotBlank()) append("，服务端 ").append(result.version)
                            append("，").append(bridgeLabel(result.bridge))
                        }
                    }
                    // 验证已经顺带查过 `/api/auth/status`，而且带着反代凭据。
                    // 第 2 步不再重复查：反代开启 `auth_basic` 时，不带凭据的那一次
                    // 必然 401，会被误判成「不需要登录」，于是第 2 步被跳过、
                    // 直接进第 3 步报错。
                    needsLogin = result.needsLogin
                    // 地址通不通只是第一步：公网地址若不设防，等于是把整台机器
                    // 交出去。提示放在这里而不是「完成」之后，因为这是唯一一个
                    // 用户还愿意回头改地址或补凭据的时刻。
                    securityNotice = if (result.proxyAuthRequired) {
                        null
                    } else {
                        io.qwenpaw.node.data.assessSecurity(
                            url = result.baseUrl,
                            loginRequired = result.needsLogin,
                        ).takeIf { it.any && !isTrustedHost(it.host) }
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
            runCatching {
                onLoadAgents(listOf(url.trim()), username, password, proxyAuth())
            }
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
                    // 「是否要登录」也是上一个地址的结论。
                    needsLogin = null
                    // 风险结论同样作废：它描述的是上一个地址。
                    securityNotice = null
                },
                instanceName = instanceName,
                onInstanceName = { instanceName = it },
                alias = alias,
                onAlias = { alias = it },
                basicUser = basicUser,
                onBasicUser = {
                    basicUser = it
                    // 凭据一改，上一轮验证结论与「反代拦住」的判定都作废，
                    // 否则改完账号不点验证也能点「下一步」。
                    verified = false
                    verifyMessage = null
                    verifyFailed = false
                    needsLogin = null
                },
                basicPassword = basicPassword,
                onBasicPassword = {
                    basicPassword = it
                    verified = false
                    verifyMessage = null
                    verifyFailed = false
                    needsLogin = null
                },
                proxyExpanded = proxyExpanded,
                onToggleProxy = { proxyExpanded = !proxyExpanded },
                proxyRequired = proxyRequired,
                verifying = verifying,
                verified = verified,
                verifyMessage = verifyMessage,
                verifyFailed = verifyFailed,
                onVerify = { verify() },
            )

            2 -> StepLogin(
                checking = checkingAuth || checkingLogin,
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
            // 检测到安全风险时先要求读完提示（对话框未确认前这一步不可点），
            // 否则「下一步」会被用户直接点掉，提示等于没出现过。
            1 -> verified && !verifying && securityNotice == null
            2 -> needsLogin == true &&
                username.isNotBlank() && password.isNotBlank() &&
                !checkingAuth && !checkingLogin
            else -> agentId.isNotBlank() && !connecting
        }
        WizardButton(
            text = when (step) {
                1 -> "下一步"
                2 -> "下一步"
                else -> if (connecting) "正在连接" else "完成"
            },
            enabled = canNext,
            busy = connecting || checkingLogin,
            onClick = {
                when (step) {
                    1 -> step = 2
                    // 先把账号发给服务端核对。写错账号的人应当停在这一步，
                    // 而不是进到第 3 步才发现拉不到智能体。
                    2 -> if (!checkingLogin) {
                        checkingLogin = true
                        loginIssue = null
                        scope.launch {
                            val failure = runCatching {
                                onCheckLogin(
                                    url.trim(),
                                    username.trim(),
                                    password,
                                    proxyAuth(),
                                )
                            }.getOrElse { e ->
                                io.qwenpaw.node.data.remote.ErrorText.of(e)
                            }
                            checkingLogin = false
                            if (failure == null) step = 3 else loginIssue = failure
                        }
                    }
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
                    .clickable(enabled = !connecting && !checkingLogin) {
                        step = wizardBackStep(step, needsLogin)
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    securityNotice?.let { notice ->
        SecurityNoticeDialog(
            notice = notice,
            onDismiss = { securityNotice = null },
        )
    }

    // 账号密码被服务端拒绝时就地弹出来，用户改完再点一次「下一步」。
    // 不用行内错误：第 2 步的内容区会被滚动推走，弹窗不会看丢。
    loginIssue?.let { issue ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { loginIssue = null },
            title = { Text("无法登录") },
            text = { Text(issue, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { loginIssue = null }) {
                    Text("知道了")
                }
            },
        )
    }
}

/**
 * 公网地址的安全风险提示。
 *
 * 两条风险分成两个对话框，按严重程度依次出现：先「未启用登录」，关掉之后才是
 * 「通信内容未加密」。两者的处置不同（一条去服务端开启登录认证，一条配证书或
 * 反向代理），合在一个长对话框里时，用户往往只看前半段就点掉。
 *
 * 「文档」只在登录这一条上出现：官方文档的该锚点讲的是 Web 登录认证，
 * 与证书配置不是同一篇内容，挂在 TLS 那条上会把人带到错误的位置。
 */
@Composable
internal fun SecurityNoticeDialog(
    notice: io.qwenpaw.node.data.SecurityNotice,
    onDismiss: () -> Unit,
) {
    var stage by remember(notice) {
        mutableStateOf(if (notice.exposed) SecurityStage.AUTH else SecurityStage.TLS)
    }
    // 关掉登录这一条后，如果还存在明文传输，接着弹第二条；否则退出。
    val advance = {
        if (stage == SecurityStage.AUTH && notice.noTls) {
            stage = SecurityStage.TLS
        } else {
            onDismiss()
        }
    }
    val uriHandler = LocalUriHandler.current
    val docUrl = "https://qwenpaw.agentscope.io/docs/security" +
        "#Web-%E7%99%BB%E5%BD%95%E8%AE%A4%E8%AF%81"

    when (stage) {
        SecurityStage.AUTH -> androidx.compose.material3.AlertDialog(
            onDismissRequest = advance,
            title = { Text("这个地址可能可以从公网访问") },
            text = {
                Column {
                    Text(
                        text = "未检测到登录验证。任何能访问到 ${notice.host} 的人" +
                            "都可以操作这台机器上的智能体、读取对话记录。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "当前状态无异于将设备向全世界公开，为确保安全，" +
                            "请尽快开启QwenPaw「Web登录认证」，保护控制台免受未授权访问",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TrustedHostHint()
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = advance) { Text("知道了") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        runCatching { uriHandler.openUri(docUrl) }
                        advance()
                    },
                ) { Text("文档") }
            },
        )

        SecurityStage.TLS -> androidx.compose.material3.AlertDialog(
            onDismissRequest = advance,
            title = { Text("通信内容未加密") },
            text = {
                Column {
                    Text(
                        text = "QwenPaw Node 与 QwenPaw 之间的通信内容将明文传输，" +
                            "存在被窃听的风险。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "建议为访问地址启用HTTPS与TLS加密。你可以直接为该地址配置SSL证书，" +
                            "或在前方部署带证书的反向代理。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TrustedHostHint()
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = advance) { Text("知道了") }
            },
        )
    }
}

private enum class SecurityStage { AUTH, TLS }

/**
 * 安全弹窗底部的「受信任的连接」去处。
 *
 * 两个阶段都要给这一句：无论用户是被哪条风险拦下的，只要这台服务器是自己的，
 * 处置方式就是同一条。不做成可点击跳转——弹窗可能在连接前出现，此时设置页不可达。
 */
@Composable
private fun TrustedHostHint() {
    Spacer(Modifier.height(10.dp))
    Text(
        text = "如果这台服务器是你自己的，可以在「设置 → 服务器 → 受信任的连接」里" +
            "添加该地址，之后不再弹出本条提示。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    basicUser: String,
    onBasicUser: (String) -> Unit,
    basicPassword: String,
    onBasicPassword: (String) -> Unit,
    proxyExpanded: Boolean,
    onToggleProxy: () -> Unit,
    /** 反代确实在要凭据：此时折叠项默认展开并给一句说明。 */
    proxyRequired: Boolean,
    verifying: Boolean,
    verified: Boolean,
    verifyMessage: String?,
    verifyFailed: Boolean,
    onVerify: () -> Unit,
) {
    // 名称在上、地址与别名收在同一个灰框里：与填表添加的步骤 1 同构，
    // 两处填的是同一批字段，排版不同会让人以为其中一个少填了什么。
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
    Spacer(Modifier.height(14.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
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
            text = "只改这条地址在实例卡片上的显示名，留空则显示地址本身",
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
    }

    Spacer(Modifier.height(10.dp))
    // 反向代理验证收在折叠项里：多数人没有反代，把它铺开会让第 1 步多出两个
    // 用不到的输入框。只有验证时真的被拦住，才自动展开并给说明。
    Text(
        text = if (proxyExpanded) "收起反向代理验证" else "反向代理验证（可选）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleProxy)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
    if (proxyExpanded) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (proxyRequired) {
                "该地址由反向代理保护，需要填写账号与密码才能访问服务端"
            } else {
                "仅当 nginx / openresty 对该地址开启了访问验证（auth_basic）时需要填写。" +
                    "它与「Web登录认证」无关。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (proxyRequired) {
                MaterialTheme.colorScheme.secondary
            } else {
                io.qwenpaw.node.ui.theme.LocalCaptionColor.current
            },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = basicUser,
            onValueChange = onBasicUser,
            placeholder = { Text("反向代理用户名") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = basicPassword,
            onValueChange = onBasicPassword,
            placeholder = { Text("反向代理密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "两个都填写才会生效；只填一项按未启用处理",
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
    }

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
                imageVector = QwenIcons.check,
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
                imageVector = QwenIcons.check,
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