package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import android.widget.Toast
import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role
import io.qwenpaw.node.ui.theme.CodeTextStyle
import io.qwenpaw.node.ui.theme.MessageMaxWidth
import io.qwenpaw.node.ui.theme.QwenIcons
import kotlinx.coroutines.delay

/**
 * 一个回合折叠起来的「N 步」：中间正文、思考与工具调用并成一组。
 *
 * 折叠态只有一行「处理完成 · N 步」，进行中自动展开、结束后自动收起，
 * 与官方 Console 的 `autoCollapseThinking` 行为一致。
 */
@Composable
fun StepsGroup(
    steps: List<ChatMessage>,
    modifier: Modifier = Modifier,
    /** 服务端登记的工具调用，key 为 `tool_call_id`。缺项表示不可控制。 */
    toolCalls: Map<String, io.qwenpaw.node.data.model.ToolCallInfoDto> = emptyMap(),
    /** 实时输出，key 为 `tool_call_id`。 */
    toolLive: Map<String, String> = emptyMap(),
    onCancelTool: (String) -> Unit = {},
    onOffloadTool: (String) -> Unit = {},
    onExtendOffload: (String) -> Unit = {},
    onExtendKill: (String) -> Unit = {},
    /** 展开卡片时重取状态并订阅实时输出。 */
    onToolOpen: (String) -> Unit = {},
    /** 收起卡片时断开实时输出。 */
    onToolClose: () -> Unit = {},
    /** 倒计时归零后重取一次真实状态。 */
    onToolDeadline: () -> Unit = {},
) {
    if (steps.isEmpty()) return
    // 用首条 id 记住手动展开状态：步骤追加进来时不会丢失用户的展开/收起选择。
    var override by remember(steps.first().id) { mutableStateOf<Boolean?>(null) }
    val running = steps.any { step ->
        step.streaming || (step.tool != null && !step.tool.done)
    }
    val expanded = override ?: running
    val stepCount = countSteps(steps)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StepsHeader(
            steps = steps,
            stepCount = stepCount,
            running = running,
            expanded = expanded,
            onToggle = { override = !expanded },
        )
        if (expanded) {
            steps.forEach { step ->
                StepRow(
                    step = step,
                    defaultOpen = false,
                    toolCalls = toolCalls,
                    toolLive = toolLive,
                    onCancelTool = onCancelTool,
                    onOffloadTool = onOffloadTool,
                    onExtendOffload = onExtendOffload,
                    onExtendKill = onExtendKill,
                    onToolOpen = onToolOpen,
                    onToolClose = onToolClose,
                    onToolDeadline = onToolDeadline,
                )
            }
        }
    }
}

/**
 * 过程里的单条内容。
 *
 * 组内展开、以及「全部展开」档下的独立步骤，都走这里，保证同一个步骤
 * 在两种位置下的样子一致。
 */
@Composable
internal fun StepRow(
    step: ChatMessage,
    defaultOpen: Boolean = false,
    toolCalls: Map<String, io.qwenpaw.node.data.model.ToolCallInfoDto> = emptyMap(),
    toolLive: Map<String, String> = emptyMap(),
    onCancelTool: (String) -> Unit = {},
    onOffloadTool: (String) -> Unit = {},
    onExtendOffload: (String) -> Unit = {},
    onExtendKill: (String) -> Unit = {},
    onToolOpen: (String) -> Unit = {},
    onToolClose: () -> Unit = {},
    onToolDeadline: () -> Unit = {},
) {
    when (step.kind) {
        ChatMessage.Kind.REASONING -> ProcessReasoningRow(step, defaultOpen)
        ChatMessage.Kind.TOOL -> ProcessToolRow(
            step = step,
            defaultOpen = defaultOpen,
            toolCalls = toolCalls,
            toolLive = toolLive,
            onCancelTool = onCancelTool,
            onOffloadTool = onOffloadTool,
            onExtendOffload = onExtendOffload,
            onExtendKill = onExtendKill,
            onToolOpen = onToolOpen,
            onToolClose = onToolClose,
            onToolDeadline = onToolDeadline,
        )
        // 中间正文折进组里也要能展开看到，否则用户会以为回答被吞了。
        ChatMessage.Kind.TEXT -> FoldedTextRow(step)
        else -> Unit
    }
}

/** 折叠态的一行摘要：状态点 + 「处理完成 · N 步」+ 最近一步在干什么。 */
@Composable
private fun StepsHeader(
    steps: List<ChatMessage>,
    stepCount: Int,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val latest = steps.lastOrNull()?.let { step ->
        step.tool?.name?.takeIf { it.isNotBlank() }
            ?: when (step.kind) {
                ChatMessage.Kind.REASONING -> "思考"
                ChatMessage.Kind.TEXT -> step.text.lineSequence()
                    .firstOrNull { it.isNotBlank() }?.trim()?.take(24)
                else -> null
            }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 进行中用主色小点呼吸，结束后退成中性色，避免长列表里一堆高亮点。
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                ),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = buildString {
                // 措辞对齐官方：进行中 / 已完成 / N 步。
                append(
                    when {
                        running -> "处理中"
                        stepCount > 0 -> "处理完成，$stepCount 步"
                        else -> "处理完成"
                    },
                )
                if (!expanded && latest != null) append("，").append(latest)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = if (expanded) QwenIcons.chevronUp else QwenIcons.chevronDown,
            contentDescription = if (expanded) "收起步骤" else "展开步骤",
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 折进步骤组的中间正文：展开后按正文样式显示，不加气泡底色。 */
@Composable
private fun FoldedTextRow(step: ChatMessage) {
    SelectionContainer {
        MarkdownText(
            markdown = step.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 过程里的思考片段。
 *
 * 不套卡片、不加边框，只用左侧一条细线区分层级，尽量不占视觉重量。
 */
@Composable
private fun ProcessReasoningRow(step: ChatMessage, defaultOpen: Boolean = false) {
    var open by remember(step.id) { mutableStateOf(defaultOpen) }
    val preview = step.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 13.dp)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(2.dp),
            )
            .clickable { open = !open }
            .padding(start = 9.dp, top = 1.dp, bottom = 1.dp, end = 4.dp),
    ) {
        Text(
            text = if (open) "思考过程" else "思考：$preview",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (open) {
            Spacer(Modifier.height(3.dp))
            SelectionContainer {
                Text(
                    text = step.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 过程里的工具调用。
 *
 * 折叠态压缩成一行，展开才给参数与输出。整体用低饱和底色、无描边，
 * 让它在长对话里「退到背景」而不是每条都抢眼。
 */
@Composable
private fun ProcessToolRow(
    step: ChatMessage,
    defaultOpen: Boolean = false,
    toolCalls: Map<String, io.qwenpaw.node.data.model.ToolCallInfoDto> = emptyMap(),
    toolLive: Map<String, String> = emptyMap(),
    onCancelTool: (String) -> Unit = {},
    onOffloadTool: (String) -> Unit = {},
    onExtendOffload: (String) -> Unit = {},
    onExtendKill: (String) -> Unit = {},
    onToolOpen: (String) -> Unit = {},
    onToolClose: () -> Unit = {},
    onToolDeadline: () -> Unit = {},
) {
    val tool = step.tool ?: return
    var open by remember(step.id) { mutableStateOf(defaultOpen) }
    // 用户手动收/展开过就按他的选择来，不再自动动。
    var userToggled by remember(step.id) { mutableStateOf(false) }
    val argHint = tool.arguments.replace('\n', ' ').trim()
    // 只有服务端登记过这一条时才给操作区：否则点了必然 404。
    val info = toolCalls[tool.callId]
    // 只有服务端登记过、且尚未结束时才可控制：结束的条目会被服务端清出缓存，点了必然 404。
    val controllableNow = if (tool.done) null else info
    var pendingCancel by remember(step.id) { mutableStateOf(false) }
    // 倒计时从「取回那一刻」起算，快照本身不会随请求自动刷新。
    val fetchedAt = remember(info) { SystemClock.elapsedRealtime() }
    val live = toolLive[tool.callId]
    val output = live ?: tool.output

    // 运行超过 7.5 秒仍未结束就主动展开，与官方网页的行为一致。
    // 只动还没被用户手动切换过的卡片：用户刚收起来又被自动弹开会很烦。
    LaunchedEffect(tool.done, step.id) {
        if (tool.done || userToggled || open) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (!autoExpandDue(SystemClock.elapsedRealtime() - startedAt)) {
            delay(250)
        }
        if (!userToggled && !open) {
            open = true
            onToolOpen(tool.callId)
        }
    }

    // 归零后重取一次真实状态；只在还有操作区时做，避免结束后反复打请求。
    LaunchedEffect(controllableNow) {
        val active = controllableNow ?: return@LaunchedEffect
        val wait = shorterRemaining(active.offloadRemaining, active.killRemaining)
        if (wait == null || wait <= 0.0) return@LaunchedEffect
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - fetchedAt
            if (remainingSeconds(shorterRemaining(active.offloadRemaining, active.killRemaining), elapsed) == 0.0) {
                onToolDeadline()
                break
            }
            delay(500)
        }
    }

    // 每秒重算一次倒计时：只有这一行小字会重组，不牵动参数与输出。
    var tick by remember(info) { mutableStateOf(0) }
    LaunchedEffect(info) {
        if (info == null) return@LaunchedEffect
        while (true) {
            delay(1000)
            tick++
        }
    }
    val autoOffload = LocalAutoOffload.current
    val deadline = remember(info, tick, autoOffload) {
        info?.let {
            toolDeadlineLabel(
                it.offloadRemaining,
                it.killRemaining,
                SystemClock.elapsedRealtime() - fetchedAt,
                autoOffload = autoOffload,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable {
                open = !open
                userToggled = true
                if (open) onToolOpen(tool.callId) else onToolClose()
            }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = QwenIcons.wrench,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                // 运行中的工具用主色，结束后同样退成中性色。
                tint = if (tool.done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tool.name.ifBlank { "工具调用" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            toolStateBadge(tool, info)?.let { badge ->
                Spacer(Modifier.width(6.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.weight(1f))
            // 折叠时右侧露出一小段参数，扫一眼就知道在操作什么。
            if (!open && argHint.isNotBlank()) {
                Text(
                    text = argHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.8f),
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (open) QwenIcons.chevronUp else QwenIcons.chevronDown,
                contentDescription = if (open) "收起" else "展开",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            if (tool.arguments.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text("参数", style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(tool.arguments, style = CodeTextStyle) }
            }
            if (!output.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = if (live != null) "输出（实时）" else "输出",
                    style = MaterialTheme.typography.labelSmall,
                )
                SelectionContainer {
                    Text(text = output.take(MAX_TOOL_OUTPUT_CHARS), style = CodeTextStyle)
                }
                if (output.length > MAX_TOOL_OUTPUT_CHARS) {
                    Text(
                        text = "（输出过长，已截断）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (controllableNow != null) {
                Spacer(Modifier.height(6.dp))
                ToolControlRow(
                    info = controllableNow,
                    onCancel = { pendingCancel = true },
                    onOffload = { onOffloadTool(tool.callId) },
                    onExtendOffload = { onExtendOffload(tool.callId) },
                    onExtendKill = { onExtendKill(tool.callId) },
                )
                deadline?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                    )
                }
            }
        }
    }

    if (pendingCancel) {
        io.qwenpaw.node.ui.common.DestructiveActionDialog(
            title = "取消执行",
            message = "确定要取消工具「${tool.name.ifBlank { "工具调用" }}」的执行吗？",
            impacts = listOf("该工具已产生的执行会被中断，结果不再返回"),
            confirmLabel = "取消执行",
            undoable = false,
            onConfirm = {
                onCancelTool(tool.callId)
                pendingCancel = false
            },
            onDismiss = { pendingCancel = false },
        )
    }
}

/** 状态徽标：运行中 / 已转后台 / 已完成 / 已取消。 */
private fun toolStateBadge(
    tool: io.qwenpaw.node.data.stream.ToolCard,
    info: io.qwenpaw.node.data.model.ToolCallInfoDto?,
): String? = when {
    info?.endState == "interrupted" || info?.forceCancelled == true -> "已取消"
    info?.status == "offloaded" -> "已转后台"
    tool.done -> "已完成"
    else -> "运行中"
}

/** 展开后的操作行：取消执行 / 转入后台 / 推迟转后台 / 延长执行超时。 */
@Composable
private fun ToolControlRow(
    info: io.qwenpaw.node.data.model.ToolCallInfoDto,
    onCancel: () -> Unit,
    onOffload: () -> Unit,
    onExtendOffload: () -> Unit,
    onExtendKill: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 两个延长按钮只在与字段对应时限存在时才有意义：没有时限就无从延长。
        ToolAction("取消执行", onCancel)
        if (info.status == "running") {
            ToolAction("转入后台", onOffload)
            if (info.offloadRemaining != null) ToolAction("推迟转后台", onExtendOffload)
        }
        if (info.killRemaining != null || info.status == "offloaded") {
            ToolAction("延长执行超时", onExtendKill)
        }
    }
}

@Composable
private fun ToolAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** 单条消息气泡。 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    imageLoader: ChatImageLoader? = null,
    connection: io.qwenpaw.node.data.remote.Connection? = null,
    onImageClick: (String) -> Unit = {},
    onRetry: (ChatMessage) -> Unit = {},
    /** 消息外观参数：头像、显示名、时间与统计开关。 */
    appearance: MessageAppearance = MessageAppearance(),
    /** 点「选择文本」对话框里选「显示」时，把这条消息的原始 Markdown 交给上层打开原文页。 */
    onOpenSource: (String) -> Unit = {},
) {
    when (message.kind) {
        // 正常路径下这几种已在 ChatTimeline 里并成 StepsGroup；
        // 这里兜住单独出现的情况（如「全部展开」档），默认把内容摊开。
        ChatMessage.Kind.REASONING,
        ChatMessage.Kind.TOOL,
        -> Box(modifier) { StepRow(message, defaultOpen = true) }

        ChatMessage.Kind.IMAGE -> ImageBubble(
            message = message,
            modifier = modifier,
            imageLoader = imageLoader,
            connection = connection,
            onImageClick = onImageClick,
            appearance = appearance,
        )

        ChatMessage.Kind.TEXT -> TextBubble(message, modifier, onRetry, appearance, onOpenSource)
    }
}

/**
 * 消息下方操作行的呈现方式。
 *
 * 默认文字：三项操作里有两项（复制、复制为 Markdown）只有文字才说得清，
 * 换成图标后「复制」与「复制为 Markdown」的图形几乎无法区分。
 */
enum class ActionStyle {
    TEXT,
    ICON,
}

/**
 * 消息外观参数。
 *
 * 头像与显示名来自设置，统计开关也在这里；打包成一个对象是为了让
 * `ChatScreen` 只多一个参数，而不是每加一项就要改三处签名。
 */
data class MessageAppearance(
    /** 头像存储。为 null 时只显示首字回落，用于预览或测试。 */
    val avatarStore: io.qwenpaw.node.avatar.AvatarStore? = null,
    /** 头像文件版本，变化时重新读取图片。 */
    val avatarVersion: Long = 0L,
    /** 用户侧显示名。默认「我」。 */
    val userName: String = "我",
    /** 智能体显示名。来自实例选择的智能体，消息自带 `original_name` 时优先用后者。 */
    val agentName: String = "智能体",
    /** 是否在回复末尾显示用量。 */
    val showStats: Boolean = false,
    /**
     * 服务端头像的本地缓存文件，只作用于用户侧。
     *
     * 服务端的 `avatar_owner()` 固定 `"console"`，没有按智能体区分的头像端点，
     * 因此智能体侧恒为 null。
     */
    val serverUserAvatar: java.io.File? = null,
    /** 服务端头像开关。关掉后回落到本机头像或首字。 */
    val useServerAvatar: Boolean = true,
    /** 底部操作行用文字还是图标。 */
    val actionStyle: ActionStyle = ActionStyle.TEXT,
)

/** 图片气泡：用户图靠右、助手图靠左，点击可全屏查看。 */
@Composable
private fun ImageBubble(
    message: ChatMessage,
    modifier: Modifier,
    imageLoader: ChatImageLoader?,
    connection: io.qwenpaw.node.data.remote.Connection?,
    onImageClick: (String) -> Unit,
    appearance: MessageAppearance,
) {
    val isUser = message.role == Role.USER
    val url = message.imageUrl.orEmpty()
    MessageFrame(
        message = message,
        modifier = modifier,
        appearance = appearance,
        isUser = isUser,
        // 图片没有可复制文本，不显示复制按钮。
        copyable = false,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = MessageMaxWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(3.dp),
        ) {
            RemoteImage(
                loader = imageLoader,
                conn = connection,
                url = url,
                onClick = { onImageClick(url) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TextBubble(
    message: ChatMessage,
    modifier: Modifier,
    onRetry: (ChatMessage) -> Unit,
    appearance: MessageAppearance,
    onOpenSource: (String) -> Unit = {},
) {
    val isUser = message.role == Role.USER
    val container = when {
        message.error != null -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        message.error != null -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 用户发的长消息默认折起来，避免一条长日志把整屏占满；
    // 助手回复不折叠——那是想看的内容，且 Markdown 折叠会破坏排版。
    val collapsible = isUser && isLongUserMessage(message.text)
    var expanded by remember(message.id) { mutableStateOf(false) }
    // 长按弹菜单，不直接复制；选文本是按需开启的第二态。
    // 两者不能同时生效：`SelectionContainer` 也要用长按手势，叠在一起就互相抢。
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    var selectionMode by remember(message.id) { mutableStateOf(false) }
    // 「选择文本」前先问是否改看原文。三个答案：
    // 显示 → 交给上层打开原文页；不显示 → 进原有的选择态；取消 → 什么都不做。
    var askSource by remember(message.id) { mutableStateOf(false) }
    val shownText = if (collapsible && !expanded) {
        collapsedUserPreview(message.text)
    } else {
        message.text
    }

    fun copy(text: String) {
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
    }

    if (askSource) {
        io.qwenpaw.node.ui.common.ChoiceDialog(
            title = "是否显示 Markdown/LaTeX 原文？",
            message = "页面上看到的是排版后的结果，公式与表格已重排。" +
                "看原文可以完整选中并复制其中的标记。",
            choices = listOf("显示", "不显示"),
            dismissLabel = "取消",
            onPick = { index ->
                askSource = false
                if (index == 0) {
                    onOpenSource(message.text)
                } else {
                    selectionMode = true
                    Toast.makeText(
                        context,
                        "已进入选择模式，「完成选择」按钮在这条消息下方",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { askSource = false },
        )
    }

    MessageFrame(
        message = message,
        modifier = modifier,
        appearance = appearance,
        isUser = isUser,
        // 流式期间内容还在变，露出复制按钮会每帧抖动，也会复制到半句话。
        copyable = !message.streaming && message.text.isNotBlank(),
        onCopy = { copy(MarkdownPlainText.strip(message.text)) },
        onCopyMarkdown = { copy(message.text) },
        selectionMode = selectionMode,
        onToggleSelection = {
            selectionMode = !selectionMode
            menuOpen = false
        },
        onRequestSelect = {
            menuOpen = false
            askSource = true
        },
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = MessageMaxWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 14.dp,
                    ),
                )
                .background(container)
                .then(
                    if (isUser) Modifier
                    else Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(
                            topStart = 14.dp,
                            topEnd = 14.dp,
                            bottomStart = 4.dp,
                            bottomEnd = 14.dp,
                        ),
                    ),
                )
                // 选择态下不挂点击手势，把手势让给 SelectionContainer。
                .then(
                    if (selectionMode || message.streaming) {
                        Modifier
                    } else {
                        Modifier.combinedClickable(
                            onLongClick = { menuOpen = true },
                            onClick = {},
                        )
                    },
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Column {
                if (message.error != null) {
                    // 错误文案是纯文本，不经过 Markdown 解析，避免异常内容被当作标记吞掉。
                    Text(
                        text = message.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )
                } else {
                    val body: @Composable () -> Unit = {
                        MarkdownText(
                            markdown = if (message.streaming && message.text.isEmpty()) {
                                "…"
                            } else {
                                shownText
                            },
                            color = contentColor,
                        )
                    }
                    if (selectionMode) {
                        SelectionContainer { body() }
                    } else {
                        body()
                    }
                }
                // 长消息折叠时的展开/收起入口。
                if (collapsible && message.error == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (expanded) "收起" else "展开全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.75f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                // 发送失败的提示只做「重试」入口，不做自动补发。
                if (message.error != null && message.retryText != null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { onRetry(message) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("重试", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // 菜单锚在这个气泡上，与列表页的行菜单同一范式。
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("复制") },
                    leadingIcon = { MenuIcons.Leading(MenuAction.COPY) },
                    onClick = {
                        menuOpen = false
                        copy(MarkdownPlainText.strip(message.text))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("复制为 Markdown") },
                    leadingIcon = { MenuIcons.Leading(MenuAction.COPY_MARKDOWN) },
                    onClick = {
                        menuOpen = false
                        copy(message.text)
                        Toast.makeText(context, "已复制 Markdown", Toast.LENGTH_SHORT).show()
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("选择文本") },
                    leadingIcon = { MenuIcons.Leading(MenuAction.SELECT_TEXT) },
                    onClick = {
                        menuOpen = false
                        askSource = true
                    },
                )
            }
        }
    }
}

/**
 * 消息的三段式外框：头部（昵称 + 时间）、气泡、底部操作行。
 *
 * 左右由 [isUser] 决定：用户消息整体靠右、头像在最右侧；智能体消息靠左、头像在最左侧。
 * 抽成公用组件是因为文字气泡与图片气泡都要这套结构，两份实现迟早会走偏。
 */
@Composable
private fun MessageFrame(
    message: ChatMessage,
    modifier: Modifier,
    appearance: MessageAppearance,
    isUser: Boolean,
    copyable: Boolean,
    onCopy: () -> Unit = {},
    onCopyMarkdown: () -> Unit = {},
    /** 是否处于「选文本」态。为真时底部操作行换成「完成选择」。 */
    selectionMode: Boolean = false,
    onToggleSelection: () -> Unit = {},
    /**
     * 请求进入选择态前的询问。
     *
     * 触摸选中的只是屏幕上渲染出来的文字：公式被排版成二维结构、图片与表格也各有排版，
     * 拖动选到的字符与原文未必一一对应。因此先问一次「要不要看原文」，
     * 由调用方弹对话框决定，气泡本身不持有这份状态。
     */
    onRequestSelect: () -> Unit = onToggleSelection,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 智能体侧优先用它自带的发送者名，缺失时退回配置里的智能体名。
    val displayName = if (isUser) {
        appearance.userName
    } else {
        message.senderName?.takeIf { it.isNotBlank() } ?: appearance.agentName
    }
    // 智能体侧用完成时间，用户侧用创建时间。流式期间不显示时间：
    // 那时 `finishedAt` 还没到，显示创建时间会让人以为回复已经结束。
    val stamp = if (isUser) {
        io.qwenpaw.node.ui.TimeLabels.fullStamp(message.timestamp)
    } else {
        io.qwenpaw.node.ui.TimeLabels.fullStamp(message.finishedAt ?: message.timestamp)
    }
    val showHeader = !message.streaming && stamp != null
    val avatarKind = if (isUser) {
        io.qwenpaw.node.avatar.AvatarStore.KIND_USER
    } else {
        io.qwenpaw.node.avatar.AvatarStore.KIND_AGENT
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 头部行：头像 + 昵称 + 时刻。
        //
        // 头像此前与**内容**同处一行，内容列必须让出「8dp + 头像宽」的横向空间，
        // 于是用户气泡右侧、智能体气泡左侧各留一条空白，两端都不齐。
        // 头像挪进头部行之后，内容与操作行都能用满整宽。
        // 流式期间头部只留头像：那时还没有完成时间，显示创建时间会让人以为回复已结束。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isUser) {
                ChatAvatar(
                    store = appearance.avatarStore,
                    kind = avatarKind,
                    name = displayName,
                    version = appearance.avatarVersion,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (showHeader) {
                // 两行：昵称在上、完整时刻在下。合成一行时两者会互相挤，
                // 昵称长一点就只剩时间，或者时间被截掉；分开后各自完整。
                Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stamp!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
                        maxLines = 1,
                    )
                }
            }
            if (isUser) {
                Spacer(Modifier.width(8.dp))
                ChatAvatar(
                    store = appearance.avatarStore,
                    kind = avatarKind,
                    name = displayName,
                    version = appearance.avatarVersion,
                    serverImage = appearance.serverUserAvatar,
                    useServerAvatar = appearance.useServerAvatar,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // 内容与操作行独立成一层，宽度不再被头像占去。
        // 气泡仍按自身内容占位（`widthIn(max = MessageMaxWidth)`），
        // 只在内容很长时才会铺满，短消息各自贴向自己的那一侧。
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            content()
            // 底部操作行：只在消息收尾后出现，流式期间留空。
            // 选择态下换成「完成选择」——那是退出该态的唯一入口，
            // 不加的话用户选出选区后没有明显办法回到普通态。
            if (selectionMode) {
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                    MessageAction("完成选择", MenuAction.DONE_SELECTING, appearance.actionStyle, onToggleSelection)
                }
            } else if (copyable) {
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                    MessageAction("复制", MenuAction.COPY, appearance.actionStyle) {
                        onCopy()
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    MessageAction("复制为 Markdown", MenuAction.COPY_MARKDOWN, appearance.actionStyle) {
                        onCopyMarkdown()
                        Toast.makeText(context, "已复制 Markdown", Toast.LENGTH_SHORT).show()
                    }
                    // 与长按菜单里的「选择文本」是同一个入口，两处都要先询问一次。
                    // 进入选择态后这条消息下方的按钮会换成「完成选择」，
                    // 用户未必知道往哪找，所以进态的就近提示由调用方在确认之后给出。
                    MessageAction("选择文本", MenuAction.SELECT_TEXT, appearance.actionStyle) {
                        onRequestSelect()
                    }
                }
            }
            // 本轮用量只挂在该轮收尾消息上，且受设置开关控制。
            if (appearance.showStats && message.turnEnd) {
                message.stats?.let { stats ->
                    Spacer(Modifier.height(4.dp))
                    MessageStatsLine(stats)
                }
            }
        }
    }
}

/**
 * 气泡下方的操作项。
 *
 * 文字档用 labelSmall 压小字号，避免操作行比正文更抢眼；
 * 图标档只画图标，`contentDescription` 仍取同一段文字——
 * 无障碍朗读与自动化测试都靠它，不能因为换成图标就把文字丢掉。
 */
@Composable
private fun MessageAction(
    text: String,
    action: MenuAction,
    style: ActionStyle,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 3.dp)
    if (style == ActionStyle.ICON) {
        Icon(
            imageVector = MenuIcons.of(action) ?: QwenIcons.moreVertical,
            contentDescription = text,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(18.dp),
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/**
 * 单轮用量。
 *
 * 只显示普通读者能理解的三个量：输入、输出、模型。缓存命中率与技术 id
 * 属于诊断信息，需要时去设置里的用量页看，不塞进聊天气泡。
 */
@Composable
private fun MessageStatsLine(stats: io.qwenpaw.node.data.stream.TurnStats) {
    val parts = buildList {
        add("输入 ${stats.promptTokens}")
        add("输出 ${stats.completionTokens}")
        stats.model?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    Text(
        text = parts.joinToString("，") + " tokens",
        style = MaterialTheme.typography.labelSmall,
        color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
    )
}

/** 大输出截断阈值，避免超长工具输出拖慢渲染。 */
private const val MAX_TOOL_OUTPUT_CHARS = 4000
