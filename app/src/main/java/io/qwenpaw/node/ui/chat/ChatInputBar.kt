package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.ui.CommandCopy
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 底部输入区：所有控件收在同一个圆角框里。
 *
 * 框内自上而下是附件条、输入行、工具栏。工具栏顺序固定为
 * 「+ / 模型名 / 思考 / … / 麦克风 / 发送」：前三项决定这一轮怎么跑，
 * 后两项是提交动作，中间用弹性空隙分开。形状对齐 OpenClaw 的 Android composer，
 * 所以工具栏里没有任何描边按钮。
 */
@Composable
fun ChatInputBar(
    draft: String,
    attachments: List<String>,
    running: Boolean,
    modelName: String?,
    /**
     * 是否显示麦克风。**只由设置里的开关决定，与服务端转写是否可用无关。**
     *
     * 转写不可用时按钮依然显示，点击后由外部弹说明——若此时也隐藏，
     * 用户将无从发现该功能缺少什么。这个参数此前叫 voiceAvailable，
     * 混淆了「可用」与「可见」两种含义，导致开关关闭后按钮仍然露出。
     */
    voiceVisible: Boolean,
    /** 服务端转写是否可用。只影响图标的弱化显示，不影响点击。 */
    voiceAvailable: Boolean,
    /** 本地识别是否可用。与 [voiceAvailable] 任一为 true 即不弱化。 */
    voiceLocalReady: Boolean = false,
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
    /** 当前是否正在录音，用来切换麦克风的高亮与图标含义。 */
    voiceListening: Boolean = false,
    /**
     * 点击模型名。打开模型切换对话框。
     *
     * 模型名既是「当前模型」的显示，也是最顺手的切换入口：用户看到它想换一个时，
     * 第一反应就是点它，而不是去右上角的菜单里找。
     */
    onModelClick: () -> Unit = {},
    /** 脑图填充比例 0..1；为 null 表示模型不支持思考，按钮整体不渲染。 */
    thinkingRatio: Float? = null,
    /** 思考强度的中文描述，只用于无障碍播报；为空时退回「思考强度」。 */
    thinkingLabel: String? = null,
    onThinkingClick: () -> Unit = {},
    /** 当前审批档位名。为 null 时「+」菜单不渲染「审批模式」这一项。 */
    approvalLabel: String? = null,
    onApprovalClick: () -> Unit = {},
    /** 打开「魔法命令」弹窗。 */
    onCommandsClick: () -> Unit = {},
    /** 打开全屏输入。 */
    onFullscreenInput: () -> Unit = {},
    /** 打开「智能体工作区」弹窗。 */
    onWorkspaceClick: () -> Unit = {},
    /** 「+」菜单里工作区一项右侧的当前目录名；null 表示尚未读取。 */
    workspaceLabel: String? = null,
    onRemoveAttachment: (String) -> Unit,
    /**
     * 可补全的斜杠命令。
     *
     * 候选列表留在框外上方、内嵌而不弹 `Popup`：这个 Column 已经让开了键盘高度，
     * 内嵌后候选自然被键盘顶上去，不需要自己算浮层位置，也不会出现浮层与输入条之间的白条。
     */
    commands: List<io.qwenpaw.node.data.model.SlashCommandDto> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // 正在输入命令时给出候选。要求输入框有焦点：失去焦点后列表还挂着会盖住页面。
    val token = if (focused) slashToken(draft) else null
    val candidates = remember(token, commands) {
        if (token == null) emptyList() else presentableCommands(commands, token)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        // 让开手势条 / 键盘的高度加在 Surface **内部**：底色能一直铺到屏幕底边，
        // 系统栏处不会露出一条页面底色。取并集而非相加——键盘高度本身已含手势条。
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (candidates.isNotEmpty()) {
                SlashCommandCandidates(
                    commands = candidates,
                    onPick = { onDraftChange(commandInsertion(it)) },
                )
                Spacer(Modifier.size(6.dp))
            }

            // 录音时描边不跟随焦点：这一圈是「输入框正在接收文字」的信号，
            // 而按住说话期间焦点可能还停在输入框上。
            val borderColor = when {
                focused && !voiceListening -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
            ) {
                Column {
                    if (attachments.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            attachments.forEach { url ->
                                AttachmentChip(
                                    name = url.substringAfterLast('/'),
                                    onRemove = { onRemoveAttachment(url) },
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        contentAlignment = Alignment.CenterStart,
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
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            maxLines = 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focused = it.isFocused },
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachmentMenuButton(
                            approvalLabel = approvalLabel,
                            workspaceLabel = workspaceLabel,
                            onAttach = onAttach,
                            onCommandsClick = onCommandsClick,
                            onApprovalClick = onApprovalClick,
                            onFullscreenInput = onFullscreenInput,
                            onWorkspaceClick = onWorkspaceClick,
                        )
                        if (!modelName.isNullOrBlank()) {
                            Spacer(Modifier.width(2.dp))
                            ModelNameButton(name = modelName, onClick = onModelClick)
                        }
                        thinkingRatio?.let { ratio ->
                            Spacer(Modifier.width(2.dp))
                            ComposerButton(
                                description = thinkingLabel?.takeIf { it.isNotBlank() }
                                    ?.let { "思考强度：$it" }
                                    ?: "思考强度",
                                onClick = onThinkingClick,
                            ) {
                                ThinkingBrainIcon(ratio = ratio)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 开关关闭时整个按钮不渲染（不是置灰、不占位）。
                        if (voiceVisible) {
                            ComposerButton(
                                description = if (voiceHoldMode) "按住说话" else "点击开始录音",
                                enabled = true,
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
                            ) {
                                Icon(
                                    imageVector = QwenIcons.mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = composerIconTint(
                                        // 转写不可用也可点：由外部弹说明，而不是把入口藏起来。
                                        dimmed = !voiceAvailable && !voiceLocalReady,
                                        highlighted = voiceListening,
                                    ),
                                )
                            }
                            Spacer(Modifier.width(2.dp))
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
    }
}

/** 「+」按钮与它挂着的菜单：附件、魔法命令、审批档位、全屏输入、智能体工作区。 */
@Composable
private fun AttachmentMenuButton(
    approvalLabel: String?,
    workspaceLabel: String?,
    onAttach: () -> Unit,
    onCommandsClick: () -> Unit,
    onApprovalClick: () -> Unit,
    onFullscreenInput: () -> Unit,
    onWorkspaceClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ComposerButton(
            description = "更多输入方式",
            onClick = { expanded = true },
        ) {
            Icon(
                imageVector = QwenIcons.plus,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(176.dp),
        ) {
            DropdownMenuItem(
                text = {
                    Text("添加附件", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = {
                    Icon(
                        imageVector = QwenIcons.paperclip,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                },
                modifier = Modifier.height(44.dp),
                onClick = {
                    expanded = false
                    onAttach()
                },
            )
            DropdownMenuItem(
                text = { Text("魔法命令", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(
                        imageVector = QwenIcons.sparkles,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                },
                modifier = Modifier.height(44.dp),
                onClick = {
                    expanded = false
                    onCommandsClick()
                },
            )
            // 服务端没给审批档位时整项不渲染，菜单里不留空行。
            if (approvalLabel != null) {
                DropdownMenuItem(
                    text = {
                        // 档位名放第二行，与「智能体工作区」的目录名同一处理：
                        // 挤在右侧时稍长的名称会被截断，还会让标题看起来像被推开的标签。
                        Column {
                            Text("审批模式", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = approvalLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = QwenIcons.shieldCheck,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                    },
                    modifier = Modifier.height(44.dp),
                    onClick = {
                        expanded = false
                        onApprovalClick()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("全屏输入", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(
                        imageVector = QwenIcons.maximize,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                },
                modifier = Modifier.height(44.dp),
                onClick = {
                    expanded = false
                    onFullscreenInput()
                },
            )
            DropdownMenuItem(
                text = {
                    // 目录名常常很长，放在第二行整行截断，比挤在右侧多出几个字符可读。
                    Column {
                        Text("智能体工作区", style = MaterialTheme.typography.bodyMedium)
                        if (workspaceLabel != null) {
                            Text(
                                text = workspaceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = QwenIcons.folder,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                },
                modifier = Modifier.height(44.dp),
                onClick = {
                    expanded = false
                    onWorkspaceClick()
                },
            )
        }
    }
}

/** 模型名：无边框文字按钮，尾部带下拉箭头，超宽时省略模型名本身。 */
@Composable
private fun ModelNameButton(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = 132.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = QwenIcons.chevronDown,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 工具栏图标按钮：34dp、无边框、无底色。
 *
 * 按下或录音高亮时只在图标位置铺一层 12% 的 `primary`，不做描边——
 * 这是 OpenClaw composer 的视觉约定，工具栏里出现边框会显得比输入框还重。
 */
@Composable
private fun ComposerButton(
    description: String? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    /** 按下回调 true，松手回调 false。null 时退化为普通点击。 */
    onPress: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (pressed || highlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .semantics {
                if (description != null) contentDescription = description
            }
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
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 工具栏图标的着色：未就绪的麦克风弱化但仍可点。 */
@Composable
private fun composerIconTint(
    dimmed: Boolean,
    highlighted: Boolean,
    enabled: Boolean = true,
): Color = when {
    !enabled -> MaterialTheme.colorScheme.outline
    highlighted -> MaterialTheme.colorScheme.primary
    dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 发送 / 停止：34dp 圆角矩形，与 console Web 的发送键同一形状。 */
@Composable
private fun SendButton(
    running: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val active = running || enabled
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            )
            .semantics { contentDescription = if (running) "停止" else "发送" }
            .clickable(enabled = active, onClick = if (running) onStop else onSend),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onPrimary),
            )
        } else {
            Icon(
                imageVector = QwenIcons.arrowUp,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 斜杠命令候选。限高后内部滚动，避免命令多时把输入条推走半屏。
 *
 * 点击只改草稿、不接管焦点：`clickable` 在触摸模式下不请求焦点，
 * 键盘保持弹出，用户点完可以继续接参数。
 */
@Composable
private fun SlashCommandCandidates(
    commands: List<io.qwenpaw.node.data.model.SlashCommandDto>,
    onPick: (io.qwenpaw.node.data.model.SlashCommandDto) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        groupByCategory(commands).forEach { (category, items) ->
            if (category.isNotBlank()) {
                Text(
                    text = CommandCopy.category(category),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            items.forEach { cmd ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(cmd) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "/${cmd.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // 本地表优先：服务端给的是英文，未收录的命令（插件、技能）才用它兜底。
                    val description = CommandCopy.description(cmd.name)
                        ?: cmd.description
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            // 容器本身就是 surfaceVariant，chip 用 surface 才分得出来。
            .background(MaterialTheme.colorScheme.surface)
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
                imageVector = QwenIcons.close,
                contentDescription = "移除附件",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
