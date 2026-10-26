package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** 底部输入区：全宽多行输入，下面一行是附件、模型名、语音和发送。 */
@Composable
fun ChatInputBar(
    draft: String,
    attachments: List<String>,
    running: Boolean,
    modelName: String?,
    voiceAvailable: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (attachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    attachments.forEach { url ->
                        AttachmentChip(
                            name = url.substringAfterLast('/'),
                            onRemove = { onRemoveAttachment(url) },
                        )
                    }
                }
            }

            val borderColor = when {
                focused -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = "输入消息",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp, max = 120.dp)
                        .onFocusChanged { focused = it.isFocused },
                )
            }

            Spacer(Modifier.size(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton42(
                    icon = Icons.Default.AttachFile,
                    description = "添加附件",
                    onClick = onAttach,
                )
                Spacer(Modifier.width(6.dp))
                if (!modelName.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = modelName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton42(
                    icon = Icons.Default.Mic,
                    description = "语音输入",
                    enabled = voiceAvailable,
                    // 按住说话，松手结束
                    onPress = { pressing -> if (pressing) onVoiceStart() else onVoiceStop() },
                )
                Spacer(Modifier.width(6.dp))
                SendButton(
                    running = running,
                    enabled = draft.isNotBlank() || attachments.isNotEmpty(),
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

@Composable
private fun IconButton42(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    /** 按下回调 true，松手回调 false。null 时退化为普通点击。 */
    onPress: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .then(
                if (onPress != null) {
                    Modifier.pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            onPress(true)
                            // 只要手指不离开屏幕就一直按住，即使滑出按钮范围也不结束。
                            // 只跟踪按下那根手指的抬起，其它指针的变化不影响。
                            while (true) {
                                val event = awaitPointerEvent()
                                val tracked = event.changes.firstOrNull { it.id == down.id }
                                if (tracked != null && !tracked.pressed) break
                            }
                            onPress(false)
                        }
                    }
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(17.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
}

@Composable
private fun SendButton(
    running: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    if (running) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                .clickable(onClick = onStop)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "停止",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                )
                .clickable(enabled = enabled, onClick = onSend)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "发送",
                modifier = Modifier.size(14.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "发送",
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttachmentChip(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "移除附件",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
