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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
    /**
     * 是否显示麦克风。**只由设置里的开关决定，与模型是否下载无关。**
     *
     * 模型未下载时按钮依然显示，点击后由外部弹提示引导下载——若此时也隐藏，
     * 用户将无从发现该功能缺少什么。这个参数此前叫 voiceAvailable，
     * 混淆了「可用」与「可见」两种含义，导致开关关闭后按钮仍然露出。
     */
    voiceVisible: Boolean,
    voiceReady: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    /**
     * true 时麦克风是「按住说话」：按下开始、松手结束。
     * false（默认）时是「点一下」：点一次开始，再点一次结束。
     */
    voiceHoldMode: Boolean = false,
    /** 「点一下」模式下当前是否正在录音，用来切换麦克风的高亮与图标含义。 */
    voiceListening: Boolean = false,
    /**
     * 点击模型胶囊。打开模型切换对话框。
     *
     * 胶囊既是「当前模型」的显示，也是最顺手的切换入口：用户看到模型名
     * 想换一个时，第一反应就是点它，而不是去右上角的菜单里找。
     */
    onModelClick: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        // 让开手势条 / 键盘的高度加在 Surface **内部**：底色能一直铺到屏幕底边，
        // 系统栏处不会露出一条页面底色。取并集而非相加——键盘高度本身已含手势条。
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
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
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClick = onModelClick,
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
                // 开关关闭时整个按钮不渲染（不是置灰、不占位）。
                if (voiceVisible) {
                    IconButton42(
                        icon = Icons.Default.Mic,
                        description = if (voiceHoldMode) "按住说话" else "点击开始录音",
                        // 模型未就绪也可点：由外部弹提示引导下载，而不是把入口藏起来。
                        enabled = true,
                        dimmed = !voiceReady,
                        highlighted = voiceListening,
                        // 按住说话：按下开始、松手结束。
                        // 点一下：在 start 与 stop 之间切换（由外部状态决定哪一次）。
                        onPress = if (voiceHoldMode) {
                            { pressing ->
                                if (pressing) onVoiceStart() else onVoiceStop()
                            }
                        } else {
                            null
                        },
                        onClick = {
                            if (voiceListening) onVoiceStop() else onVoiceStart()
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                }
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
    /**
     * 视觉上弱化，但**仍然可点**。
     *
     * 用于「功能存在但前置条件未满足」的情形：例如麦克风在模型未下载时依旧显示，
     * 点击会弹提示。不能用 [enabled] = false 表达这种状态，那会连点击都没了。
     */
    dimmed: Boolean = false,
    /** 高亮态：用于「点一下」模式正在录音时的麦克风。 */
    highlighted: Boolean = false,
    /** 按下回调 true，松手回调 false。null 时退化为普通点击。 */
    onPress: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val borderColor = when {
        highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val iconTint = when {
        highlighted -> MaterialTheme.colorScheme.primary
        dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        !enabled -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
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
            tint = iconTint,
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
