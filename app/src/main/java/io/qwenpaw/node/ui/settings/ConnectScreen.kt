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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.model.AgentDto
import kotlinx.coroutines.launch

/**
 * 首次启动的连接页。
 *
 * 四步：填地址、读智能体列表、挑一个、连进去。
 * 账号密码和智能体 id 都收进高级选项，只有地址是必填。
 */
@Composable
fun ConnectScreen(
    connecting: Boolean,
    error: String?,
    onConnect: (host: String, username: String, password: String, agentId: String) -> Unit,
    onLoadAgents: suspend (host: String, username: String, password: String) -> List<AgentDto>,
    saved: List<InstanceConfig>,
    onUseSaved: (InstanceConfig) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agentId by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var fixedPunctuation by remember { mutableStateOf(false) }

    var agents by remember { mutableStateOf<List<AgentDto>>(emptyList()) }
    var loadingAgents by remember { mutableStateOf(false) }
    var agentError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val hostBlank = host.isBlank()
    val picked = agents.firstOrNull { it.id == agentId }
    val step = when {
        hostBlank -> 1
        agents.isEmpty() -> 2
        picked == null -> 3
        else -> 4
    }

    fun fetchAgents() {
        if (hostBlank || loadingAgents) return
        loadingAgents = true
        agentError = null
        scope.launch {
            runCatching { onLoadAgents(host, username, password) }
                .onSuccess { list ->
                    agents = list
                    // 只有一个智能体时不用让用户多点一次。
                    if (list.size == 1) agentId = list.first().id
                }
                .onFailure { e ->
                    agents = emptyList()
                    agentError = e.message ?: "无法读取智能体列表"
                }
            loadingAgents = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { raw ->
                val normalized = foldFullWidth(raw)
                fixedPunctuation = normalized != raw || fixedPunctuation
                host = normalized
                // 地址变了，之前读到的智能体就作废了。
                agents = emptyList()
                agentId = ""
                agentError = null
            },
            placeholder = { Text("192.168.1.10:8088") },
            singleLine = true,
            isError = error != null,
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
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
            text = "真机填写局域网地址，模拟器填写 10.0.2.2:8088",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
            Spacer(Modifier.weight(1f))
            Text(
                text = "服务端开启登录时填写",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        AnimatedVisibility(visible = advanced) {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                        MaterialTheme.colorScheme.outline
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
                text = "填好地址后读取，也可以先把服务地址存下来",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
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

        if (saved.isNotEmpty()) {
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
                    onConnect(host, username, password, agentId.ifBlank { "default" })
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
                    text = "连接",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "连接时会检查服务端版本和智能体是否可用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** 四步进度。 */
@Composable
private fun StepIndicator(step: Int, total: Int) {
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
        Text(
            text = "第 $step 步，共 $total 步",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                else MaterialTheme.colorScheme.outline,
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (agent.name.isNotBlank() && agent.name != agent.id) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = agent.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
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
                    color = MaterialTheme.colorScheme.outline,
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
                    append(instance.baseUrl)
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

/** 把中文标点折成半角，真机上输入法常把点和冒号打成全角。 */
private fun foldFullWidth(raw: String): String = raw
    .replace('。', '.')
    .replace('：', ':')
    .replace('／', '/')
    .replace('　', ' ')
    .replace('，', ',')
