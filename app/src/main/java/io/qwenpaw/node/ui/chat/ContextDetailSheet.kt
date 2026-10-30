package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role

/** 全屏图片查看：双指缩放、拖动，单击关闭。 */
@Composable
fun ImageViewerDialog(
    url: String,
    loader: ChatImageLoader?,
    connection: Connection?,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(url, loader, connection) {
        if (loader == null || connection == null) return@LaunchedEffect
        val result = loader.load(connection, url)
        if (result == null) failed = true else bitmap = result
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> androidx.compose.foundation.Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        },
                )

                failed -> Text(
                    text = "图片加载失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )

                else -> CircularProgressIndicator(color = Color.White)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 上下文用量明细。
 *
 * 服务端只给「整轮」级别的估算（`turn_usage`：已用 token / 上限 / 比例），
 * 没有按消息拆分，因此这里展示总量 + 按字符估算的各消息占比（标注为估算）。
 */
@Composable
fun ContextDetailSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
) {
    val ratio = state.contextUsage.coerceIn(0.0, 1.0)
    val totalChars = state.messages.sumOf { estimateChars(it) }.coerceAtLeast(1)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp),
        ) {
            Text(
                text = "上下文用量",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(ratio * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (state.contextMax > 0) {
                            "约 ${formatTokens(state.contextTokens)} / ${formatTokens(state.contextMax)} tokens"
                        } else {
                            "服务端尚未返回本轮用量"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (state.contextMax > 0) {
                            "剩余约 ${((1 - ratio) * 100).toInt()}%"
                        } else {
                            "发送消息后显示"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 进度条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.toFloat())
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.height(16.dp))

            Text(
                text = "各消息占比（按字符估算）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (state.messages.isEmpty()) {
                Text(
                    text = "当前会话还没有消息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 从大到小排，让占比高的消息先被看到。
                    state.messages
                        .map { it to estimateChars(it) }
                        .sortedByDescending { it.second }
                        .take(30)
                        .forEach { (message, chars) ->
                            MessageUsageRow(
                                message = message,
                                fraction = chars.toFloat() / totalChars.toFloat(),
                                chars = chars,
                            )
                        }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) { Text("关闭") }
        }
    }
}

/** 单条消息的占比条。 */
@Composable
private fun MessageUsageRow(
    message: ChatMessage,
    fraction: Float,
    chars: Int,
) {
    val label = when (message.kind) {
        ChatMessage.Kind.TEXT -> if (message.role == Role.USER) "你" else "助手"
        ChatMessage.Kind.REASONING -> "思考"
        ChatMessage.Kind.TOOL -> "工具"
        ChatMessage.Kind.IMAGE -> "图片"
    }
    val preview = when (message.kind) {
        ChatMessage.Kind.IMAGE -> message.imageName ?: "图片"
        else -> message.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$chars 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f)),
            )
        }
    }
}

/** 估算一条消息的字符数：图片按固定值，其余按文本长度。 */
private fun estimateChars(message: ChatMessage): Int = when (message.kind) {
    ChatMessage.Kind.IMAGE -> 800
    ChatMessage.Kind.TOOL -> (message.tool?.arguments?.length ?: 0) +
        (message.tool?.output?.length ?: 0) + 40

    else -> message.text.length
}

/** token 数量友好显示：整数或 k/M 级缩写。 */
internal fun formatTokens(value: Long): String = when {
    value <= 0 -> "0"
    value < 1000 -> value.toString()
    value < 1_000_000 -> {
        val k = value / 1000.0
        if (k < 10) "%.1fk".format(k) else "${value / 1000}k"
    }

    else -> "%.2fM".format(value / 1_000_000.0)
}