package io.qwenpaw.node.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.voice.VoiceState

/** 首次使用语音输入时的说明，同时说明模型下载。 */
@Composable
fun VoiceIntroDialog(
    modelReady: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语音输入需要离线模型") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "语音输入在手机本地完成，不走云端。第一次用要先下载识别模型，之后断网也能用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(10.dp))
                Bullet("按住输入框旁的麦克风开始说话，松开即结束。")
                Bullet("音频只在本机处理，不会上传到任何服务器。")
                Bullet("识别结果显示在输入框上方，松手后填进输入框。")
                if (modelReady) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "模型已经下载好了，直接说话就行。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (modelReady) onDismiss else onDownload) {
                Text(if (modelReady) "开始说话" else "去下载模型")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 收音时贴在输入框上方的浮层，显示实时字幕。 */
@Composable
fun VoiceSheet(
    state: VoiceState,
    partial: String,
    onCancel: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .graphicsLayer {
                        val scale = if (state == VoiceState.Listening) pulse.value else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (state) {
                        VoiceState.Listening -> "正在听，松开麦克风结束"
                        is VoiceState.Error -> state.message
                        VoiceState.Idle -> "准备中"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (state is VoiceState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = partial.ifBlank { "说话的內容会实时出现在这里" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (partial.isBlank()) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = 18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "取消",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
