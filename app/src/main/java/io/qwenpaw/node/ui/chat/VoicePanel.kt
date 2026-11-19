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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.voice.VoiceState
import io.qwenpaw.node.ui.theme.QwenIcons
import kotlinx.coroutines.delay

/** 首次打开语音开关时的说明。带端上引擎的包会说明模型与来源的选择。 */
@Composable
fun VoiceIntroDialog(
    onDismiss: () -> Unit,
    /** 本地识别模型是否已就绪。 */
    modelReady: Boolean = true,
    /** 「去下载模型」。仅在 modelReady 为 false 时作为主按钮出现。 */
    onDownload: () -> Unit = {},
    /** 当前触发方式，用来给出对应的操作提示。 */
    holdMode: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (modelReady) R.string.voicepanel_intro_title else R.string.voicepanel_intro_title_model,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(
                        if (modelReady) R.string.voicepanel_intro_body else R.string.voicepanel_intro_body_model,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(10.dp))
                if (holdMode) {
                    Bullet(stringResource(R.string.voicepanel_bullet_hold))
                } else {
                    Bullet(stringResource(R.string.voicepanel_bullet_tap))
                }
                Bullet(stringResource(R.string.voicepanel_bullet_result))
                Bullet(stringResource(R.string.voicepanel_bullet_settings))
            }
        },
        confirmButton = {
            if (modelReady) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.voicepanel_got_it)) }
            } else {
                TextButton(onClick = onDownload) { Text(stringResource(R.string.voicepanel_download_model)) }
            }
        },
        dismissButton = if (modelReady) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.voicepanel_not_now)) } }
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

/** 录音与转写时贴在输入框上方的浮层。 */
@Composable
fun VoiceSheet(
    state: VoiceState,
    /** 录音开始的时刻（毫秒），用于录制计时；非录音态可为 null。 */
    startedAt: Long?,
    /** 本地识别的实时文字。服务端转写没有增量结果，此时为空串。 */
    partial: String = "",
    onCancel: () -> Unit,
    /** 按住说话模式：提示「松开结束」；点一下模式：提示「再次点击结束」。 */
    holdMode: Boolean = true,
) {
    val pulse = rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )

    // 计时只在录音态走。没有秒级更新的话，用户看不出录了多久。
    var seconds by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAt, state) {
        if (state != VoiceState.Recording || startedAt == null) {
            seconds = 0
            return@LaunchedEffect
        }
        while (true) {
            seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
            delay(500)
        }
    }

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
                        val scale = if (state == VoiceState.Recording) pulse.value else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = QwenIcons.mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (state) {
                        VoiceState.Recording -> stringResource(
                            if (holdMode) {
                                R.string.voicepanel_recording_hold
                            } else {
                                R.string.voicepanel_recording_tap
                            },
                        )
                        VoiceState.Transcribing -> stringResource(R.string.voicepanel_transcribing)
                        is VoiceState.Error -> state.message
                        VoiceState.Idle -> stringResource(R.string.voicepanel_preparing)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (state is VoiceState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(min = 18.dp),
                ) {
                    if (state == VoiceState.Transcribing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                    Text(
                        text = when (state) {
                            VoiceState.Recording ->
                                pluralStringResource(
                                    R.plurals.voicepanel_recorded_seconds,
                                    seconds.toInt(),
                                    seconds.toInt(),
                                )
                            VoiceState.Transcribing -> stringResource(R.string.voicepanel_uploaded)
                            // 本地识别边录边出字；服务端转写没有增量结果，这一行留空。
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                    )
                }
                // 本地识别的实时字幕。服务端转写时 partial 恒为空，这一段不占位。
                if (state == VoiceState.Recording && partial.isNotBlank()) {
                    Text(
                        text = partial,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 转写中不给「取消」：请求已经发出去，取消不了，点了只会让用户以为停了。
            if (state != VoiceState.Transcribing) {
                Text(
                    text = if (state == VoiceState.Recording) {
                        stringResource(R.string.action_cancel)
                    } else {
                        stringResource(R.string.action_close)
                    },
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
}