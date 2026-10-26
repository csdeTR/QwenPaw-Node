package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.data.model.AgentDto
import kotlinx.coroutines.launch

/** 智能体选择面板，设置页切换实例的智能体时用。 */
@Composable
fun AgentPickerDialog(
    currentAgentId: String,
    load: suspend () -> List<AgentDto>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var agents by remember { mutableStateOf<List<AgentDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(currentAgentId) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            failure = null
            runCatching { load() }
                .onSuccess { agents = it }
                .onFailure { failure = it.message ?: "无法读取智能体列表" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择智能体") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }

                    failure != null -> Column {
                        Text(
                            text = failure.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { reload() }) { Text("重试") }
                    }

                    agents.isEmpty() -> Text(
                        text = "这个实例没有返回可用的智能体",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> agents.forEach { agent ->
                        PickerRow(
                            agent = agent,
                            selected = agent.id == picked,
                            onClick = { picked = agent.id },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(picked) },
                enabled = !loading && agents.isNotEmpty(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PickerRow(agent: AgentDto, selected: Boolean, onClick: () -> Unit) {
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
            Box(modifier = Modifier.size(16.dp)) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选择",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
