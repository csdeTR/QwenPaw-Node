package io.qwenpaw.node.ui.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.voice.VoiceState
import kotlinx.coroutines.launch

/** 聊天页。 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    modelName: String? = null,
    voiceState: VoiceState = VoiceState.Idle,
    voicePartial: String = "",
    /** 麦克风是否显示。只由设置开关决定，模型未下载时也显示。 */
    voiceVisible: Boolean = false,
    /** 模型是否已就绪。未就绪时麦克风仍可点，但点击会先弹下载提示。 */
    voiceReady: Boolean = false,
    imageLoader: ChatImageLoader? = null,
    connection: io.qwenpaw.node.data.remote.Connection? = null,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    onApprove: (io.qwenpaw.node.data.model.ApprovalDto) -> Unit,
    onDeny: (io.qwenpaw.node.data.model.ApprovalDto) -> Unit,
    onRetry: (ChatMessage) -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onArchive: () -> Unit = {},
    onExport: () -> Unit = {},
    /** 重新载入当前会话：断网恢复后的主要入口。 */
    onRefresh: () -> Unit = {},
    onSetThinking: (String) -> Unit = {},
    onSetGroup: (String?) -> Unit = {},
    onPickModel: (providerId: String?, model: String?) -> Unit = { _, _ -> },
    onPickApproval: (String) -> Unit = {},
    onSetProjectDir: (String?) -> Unit = {},
    onDismissError: () -> Unit = {},
    /** true 为「按住说话」，false 为「点一下」。 */
    voiceHoldMode: Boolean = false,
    /** 点一下模式且开启了自动停止。 */
    voiceAutoStop: Boolean = false,
    /** 智能体输出的折叠方式，默认折叠结果。 */
    assistantCollapse: AssistantCollapse = AssistantCollapse.RESULT_COLLAPSED,
    /** 消息外观：头像、显示名与用量开关。 */
    appearance: MessageAppearance = MessageAppearance(),
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var showContext by remember { mutableStateOf(false) }
    var showProjectDir by remember { mutableStateOf(false) }
    // 三处二级选择统一放在这里，避免继续膨胀 ChatTopBar 的菜单 flag。
    var showModelDialog by remember { mutableStateOf(false) }
    var showApprovalDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }

    // 首屏只渲染最近的一页，向上滚动时再往前补。
    // 560+ 条的历史一次铺开会明显掉帧，尤其在低端机上。
    // 先整体分组、再按**条目**开窗：一组「处理过程」可能吃掉几十条原始消息，
    // 若按原始消息数分页，一页可能只剩一两个条目、连屏幕都填不满。
    // [rawStart] 只减不增（补页时往前挪），并与「总条目数 - 一页」在组合期取小，
    // 这样历史刚载入的第一帧就已经是裁剪后的列表，不会先铺开全部再靠副作用收窄。
    val allItems = remember(state.messages, assistantCollapse) {
        buildItems(state.messages, assistantCollapse)
    }
    var rawStart by remember(state.currentChatId) { mutableStateOf(Int.MAX_VALUE) }
    val maxStart = (allItems.size - PAGE_SIZE).coerceAtLeast(0)
    val windowStart = rawStart.coerceAtMost(maxStart)
    val timeline = remember(allItems, windowStart) { buildTimeline(allItems, windowStart) }
    val hasMore = windowStart > 0

    // 已经发出、但智能体还没吐出第一条内容。
    //
    // 这一段时间里界面上只有用户气泡：工具调用、思考、正文都要等首帧到达才出现。
    // 少了这一行，用户点完发送看到的是一个不动的页面，容易以为发送失败。
    val awaitingReply = needsAwaitingPlaceholder(state.messages, state.running)
    /**
     * 列表最后一项的下标。
     *
     * 两个额外条目都要算进去：顶部的「向上滑动」提示行、底部的等待行。
     * 只写 `timeline.lastIndex` 会在有更早历史时少滚一格，停不到真正的最后一条。
     */
    val lastItemIndex = timeline.lastIndex +
        (if (hasMore) 1 else 0) +
        (if (awaitingReply) 1 else 0)

    // 用户真的滚动过一次之前，不允许补页。
    // 否则列表刚落地时首项正好是 0，会被误判成「翻到顶了」，一上来就把历史全铺开。
    var userScrolled by remember(state.currentChatId) { mutableStateOf(false) }

    // 是否跟随列表末尾。
    //
    // **只在用户拖动结束时采样**，不每帧读 `atBottom`，也不把程序化滚动当成用户意图：
    // 流式内容变长会重新分组，`ChatItem` 的 key 随之变化，LazyColumn 找不到原来的锚点，
    // 位置会跳一下。跳完之后 `atBottom` 变成 false，若据此刻判断，就会永久停止跟随——
    // 实测表现为发出一段长回复后，界面莫名退回到几屏之前的历史里。
    //
    // 用 `DragInteraction` 而不是 `isScrollInProgress` 来识别「用户」：
    // 后者对 `scrollToItem` / `animateScrollToItem` 同样为 true，
    // 而本文件里几乎每一帧都在程序化贴底，那样会把跟随标记反复误判成 false。
    var followTail by remember(state.currentChatId) { mutableStateOf(true) }
    /** 用户刚拖完，等惯性滑动停下来再采样「停在哪」。 */
    var pendingSample by remember(state.currentChatId) { mutableStateOf(false) }

    // 是否还停在列表最顶端，以及当前内容能否滚动。
    // 都用 derivedStateOf 跟随布局结果，避免读到过期的快照。
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 0
        }
    }
    val canScrollForward by remember {
        derivedStateOf { listState.canScrollForward }
    }

    // 容差用 dp 换算而不是裸像素：24px 在 xxhdpi 上只有 8dp，
    // 略早一点就被判成「已离开底部」，按钮会闪。
    val bottomTolerance = with(LocalDensity.current) { 8.dp.toPx() }
    val atBottom = remember(bottomTolerance) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + bottomTolerance
        }
    }

    LaunchedEffect(listState, state.currentChatId) {
        snapshotFlow { listState.interactionSource.interactions }
            .collect { interaction ->
                // 只处理拖动：Fling 与程序化滚动都不是「用户在读别处」。
                // 拖完不立刻采样——松手之后还有惯性滑动，那时读到的位置还没定。
                when (interaction) {
                    is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                        userScrolled = true
                    }

                    is androidx.compose.foundation.interaction.DragInteraction.Stop -> {
                        pendingSample = true
                    }

                    else -> Unit
                }
            }
    }

    // 惯性也停下来之后，再判断用户最终停在哪：「滑回底部」要恢复跟随，
    // 「翻上去看历史」要停止跟随，否则新内容会把正在读的位置顶走。
    LaunchedEffect(listState, state.currentChatId) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling && pendingSample) {
                    pendingSample = false
                    followTail = atBottom.value
                }
            }
    }

    // 补页。效果以 timeline.size 为 key：每补一页它就变大，效果自然重跑，
    // 于是「还停在顶部」时会继续补，直到补够或用户离开顶部，能正常收敛。
    LaunchedEffect(atTop, canScrollForward, userScrolled, timeline.size, state.currentChatId) {
        // 生成期间与跟随末尾时都不补页。
        //
        // 「停在顶部」与「跟随底部」本来互斥；但列表在回复结束时会被服务端历史整体替换，
        // 下标在替换瞬间会短暂塌到 0（条目全换了一批，旧锚点消失），被误判成「翻到顶了」，
        // 接着就把几个月前的历史铺回来——这正是回复完成后界面突然退回几屏之前的原因。
        // 跟随标记是用户的真实意图，优先于位置推断。
        if (followTail || state.running) return@LaunchedEffect
        val curStart = rawStart.coerceAtMost((allItems.size - PAGE_SIZE).coerceAtLeast(0))
        if (curStart <= 0 || !atTop) return@LaunchedEffect
        // 内容不足一屏时列表根本滚不动，用户也无法再上滑，此时一路补到能滚动为止。
        // 这种历史总长本就不到两屏，多铺几页不会有性能问题。
        val shortContent = !canScrollForward
        if (!shortContent && !userScrolled) return@LaunchedEffect

        val added = curStart.coerceAtMost(PAGE_SIZE)
        rawStart = curStart - added
        if (!shortContent) {
            // 新内容插在提示行之上，锚定到原先的第一条正文，视觉位置不动。
            // 这样首项不再是 0，不会在这一次手势里连续补页。
            listState.scrollToItem(added + 1, 0)
        }
    }

    // 只有停留在底部时才跟随新消息，往上翻历史时不打断阅读。
    //
    // 判据是 [followTail]（拖动结束时采样），不是实时 `atBottom`：
    // 后者会被流式重分组引起的布局跳动带偏。见 followTail 的注释。
    //
    // 三个副作用分别盯住三种变化：
    // - 最后一条的 id 变了：回复结束时 `reconcileWithServer` 用服务端历史整体替换，
    //   本地 `local-user-N` 与流式 msg_id 全部换成服务端 id，条数却可能不变；
    // - 文本变长：流式逐帧增长；
    // - 条目数变了：流式标记被清掉后，「正文 + 思考 + 工具」被折进一个 `Steps`，
    //   条目数减少，列表整体上移。
    //
    // **偏移量必须给 Int.MAX_VALUE**。`scrollToItem(i)` 默认把第 i 条的**顶部**
    // 对齐到视口顶部；最后一条往往是几屏长的回复，那样只会停在它的开头，
    // `atBottom` 仍为 false，「跳到最新」按钮随即冒出来，看起来就像「跑到了别处」。
    // 给一个大到必然被钳制的偏移，才是真正的「贴底」。
    fun stickToBottom() {
        listState.requestScrollToItem(lastItemIndex, TAIL_SCROLL_OFFSET)
    }

    LaunchedEffect(state.messages.lastOrNull()?.id, state.currentChatId) {
        if (state.messages.isNotEmpty() && followTail) stickToBottom()
    }

    LaunchedEffect(state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty() && followTail) stickToBottom()
    }

    LaunchedEffect(timeline.size) {
        if (state.messages.isNotEmpty() && followTail) stickToBottom()
    }

    // 一轮结束（running 由 true 变 false）时再贴一次底。
    //
    // 此刻 `reconcileWithServer` 刚用服务端历史整体替换列表：条目数与分组方式都变了，
    // 上面几个副作用即便都触发，也可能在「新列表还没完成测量」时按旧下标滚动，
    // 落到半路。这里等两帧让布局落定，再按**实际条目总数**滚到最后一行。
    LaunchedEffect(state.running) {
        if (state.running || !followTail || state.messages.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.requestScrollToItem(total - 1, TAIL_SCROLL_OFFSET)
    }

    // 等待行出现时也要滚到底，否则它会落在可视区之外，等于没加。
    //
    // 这里**不**判断 followTail。发送这个动作本身就意味着「我要看这条」：
    // 键盘收起会把视口变高，原本停在底部的列表因此不再算「在底部」，
    // 若仍按位置判断，刚发出的气泡和等待行都会被留在视口之外，
    // 用户看到的正是「点完发送，页面没反应」。
    LaunchedEffect(awaitingReply) {
        if (awaitingReply) {
            followTail = true
            listState.requestScrollToItem(lastItemIndex, TAIL_SCROLL_OFFSET)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChatTopBar(
                title = state.currentChatName,
                running = state.running,
                contextUsage = state.contextUsage,
                pinned = state.currentChatPinned,
                thinking = state.thinking,
                groups = state.groups,
                currentGroupId = state.currentChatGroupId,
                modelChoices = state.modelChoices,
                currentModelLabel = state.currentModelLabel,
                approvalLevel = state.approvalLevel,
                projectDirName = state.projectDir?.name?.takeIf { it.isNotBlank() },
                link = state.link,
                boundUrl = state.boundUrl,
                boundLabel = state.boundLabel,
                serverName = state.boundServerName,
                onBack = onBack,
                onRename = {
                    renameText = state.currentChatName
                    renaming = true
                },
                onDelete = { deleting = true },
                onTogglePin = onTogglePin,
                onArchive = onArchive,
                onExport = onExport,
                onRefresh = onRefresh,
                onContextClick = { showContext = true },
                onSetThinking = onSetThinking,
                onSetGroup = onSetGroup,
                onPickModel = onPickModel,
                onPickApproval = onPickApproval,
                onOpenProjectDir = { showProjectDir = true },
                onOpenModelDialog = { showModelDialog = true },
                onOpenApprovalDialog = { showApprovalDialog = true },
                onOpenGroupDialog = { showGroupDialog = true },
            )
        },
        bottomBar = {
            Column {
                state.pendingApprovals.firstOrNull()?.let { approval ->
                    ApprovalBanner(
                        approval = approval,
                        onApprove = { onApprove(approval) },
                        onDeny = { onDeny(approval) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                AnimatedVisibility(visible = state.error != null) {
                    ErrorBar(text = state.error.orEmpty(), onClose = onDismissError)
                }
                AnimatedVisibility(
                    visible = voiceState != VoiceState.Idle,
                ) {
                    VoiceSheet(
                        state = voiceState,
                        partial = voicePartial,
                        onCancel = onVoiceCancel,
                        holdMode = voiceHoldMode,
                        autoStop = voiceAutoStop,
                    )
                }
                ChatInputBar(
                    draft = state.draft,
                    attachments = state.attachments,
                    running = state.running,
                    // 胶囊显示当前实际生效的模型，取自 thinking 视图；
                    // 入参 modelName 仅作为尚未拉取到时的兜底。
                    modelName = state.currentModelLabel ?: modelName,
                    voiceVisible = voiceVisible,
                    voiceReady = voiceReady,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onStop = onStop,
                    onAttach = onAttach,
                    onVoice = onVoiceStart,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    voiceHoldMode = voiceHoldMode,
                    voiceListening = voiceState == VoiceState.Listening,
                    onRemoveAttachment = onRemoveAttachment,
                    // 胶囊本身就是「当前模型」的显示，点它是改模型的自然入口，
                    // 比让用户去右上角「...」里翻更直接。
                    onModelClick = { showModelDialog = true },
                    // 底部留白交给输入条自己处理：它需要把留白加在 Surface 内部，
                    // 这样底色能一直铺到屏幕底边，不会在系统栏处露出一条页面底色。
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                // 用 lastOrNull 判断而非 isEmpty：加载中的空壳状态不应触发空态提示。
                state.messages.lastOrNull() == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "开始新的对话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "可渲染 Markdown、代码块与工具调用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> io.qwenpaw.node.ui.theme.ChatFontScale {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (hasMore) {
                            item(key = "load-more") {
                                LoadEarlierHint(loading = false)
                            }
                        }
                        items(timeline, key = { it.key }) { item ->
                            when (item) {
                                is ChatItem.Single -> MessageBubble(
                                    message = item.message,
                                    imageLoader = imageLoader,
                                    connection = connection,
                                    onImageClick = { viewingImage = it },
                                    onRetry = onRetry,
                                    appearance = appearance,
                                )

                                is ChatItem.Steps -> StepsGroup(steps = item.steps)

                                is ChatItem.Files -> DeliveredFilesCard(
                                    files = item.files,
                                    imageLoader = imageLoader,
                                    connection = connection,
                                    onImageClick = { viewingImage = it },
                                )
                            }
                        }
                        if (awaitingReply) {
                            item(key = "awaiting-reply") { AwaitingReplyRow() }
                        }
                    }
                }
            }

            // 「跳到最新」浮层自己读 atBottom：读发生在浮层内部，翻转时只重组这一小块，
            // 不会把整张消息列表连带 Markdown 正文一起拖下水。
            //
            // 可见条件是「**不在**底部」。此前写成 atBottom，导致停在底部时按钮常驻、
            // 上翻历史时反而消失，与「跳到最新」的语义正好相反。
            if (state.messages.isNotEmpty()) {
                JumpToLatestButton(
                    visible = { !atBottom.value },
                    onClick = {
                        scope.launch {
                            followTail = true
                            listState.requestScrollToItem(lastItemIndex, TAIL_SCROLL_OFFSET)
                        }
                    },
                )
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(renameText.trim())
                    renaming = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("取消") }
            },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除会话") },
            text = { Text("删除后无法恢复，确定要删除这个会话吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text("取消") }
            },
        )
    }

    // 图片全屏查看：点任意处关闭。
    viewingImage?.let { url ->
        ImageViewerDialog(
            url = url,
            loader = imageLoader,
            connection = connection,
            onDismiss = { viewingImage = null },
        )
    }

    if (showContext) {
        ContextDetailSheet(
            state = state,
            onDismiss = { showContext = false },
        )
    }

    if (showProjectDir) {
        ProjectDirDialog(
            current = state.projectDir,
            onDismiss = { showProjectDir = false },
            onSave = { path ->
                showProjectDir = false
                onSetProjectDir(path)
            },
            onReset = {
                showProjectDir = false
                onSetProjectDir(null)
            },
        )
    }

    // 模型：首项固定「跟随智能体默认」，其余按供应商分组展示在副标题里。
    if (showModelDialog) {
        val items = state.modelChoices.map { choice ->
            OptionItem(
                key = choice.key,
                label = choice.modelName,
                sublabel = choice.providerName,
                // 没有显式指定过模型时，「跟随默认」是选中项，这里全部不标。
                selected = choice.modelName == state.currentModelLabel,
            )
        }
        OptionDialog(
            title = "切换模型",
            items = items,
            emptyHint = "当前服务端未配置可用模型",
            leading = OptionItem(
                key = "",
                label = "跟随智能体默认",
                selected = state.currentModelLabel == null,
            ),
            onPick = { key ->
                showModelDialog = false
                if (key.isEmpty()) {
                    onPickModel(null, null)
                } else {
                    state.modelChoices.firstOrNull {
                        it.key == key
                    }?.let { onPickModel(it.providerId, it.modelId) }
                }
            },
            onDismiss = { showModelDialog = false },
        )
    }

    if (showApprovalDialog) {
        OptionDialog(
            title = "审批模式",
            items = APPROVAL_OPTIONS.map { option ->
                OptionItem(
                    key = option.code,
                    label = option.label,
                    sublabel = option.hint,
                    selected = option.code == state.approvalLevel,
                )
            },
            onPick = { code ->
                showApprovalDialog = false
                onPickApproval(code)
            },
            onDismiss = { showApprovalDialog = false },
        )
    }

    // 分组：首项固定「未分组」。此前下拉菜单里没有这一项，
    // 会话一旦进了某个分组就无法移回去，只能重新建一个会话。
    if (showGroupDialog) {
        OptionDialog(
            title = "移动到分组",
            items = state.groups.map { group ->
                OptionItem(
                    key = group.id,
                    label = group.name.ifBlank { group.id },
                    selected = group.id == state.currentChatGroupId,
                )
            },
            leading = OptionItem(
                key = "",
                label = "未分组",
                selected = state.currentChatGroupId.isNullOrBlank(),
            ),
            onPick = { key ->
                showGroupDialog = false
                onSetGroup(key.ifBlank { null })
            },
            onDismiss = { showGroupDialog = false },
        )
    }
}

/**
 * 「跳到最新」浮层。
 *
 * [visible] 是**延迟读取**的可视性判断：把对 [atBottom] 的读取关在这个小组件内部，
 * 它的翻转只让这一小块重组，而不是让上半张列表（连同所有可见 Markdown 正文）一起重组。
 * 这是上下滑动时「一阵一阵卡顿」的直接解药。
 */
@Composable
private fun BoxScope.JumpToLatestButton(
    visible: () -> Boolean,
    onClick: () -> Unit,
) {
    if (visible()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(999.dp),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = "跳到最新",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** 还有更早历史时，列表顶部的一行提示。 */
@Composable
private fun LoadEarlierHint(loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = if (loading) "正在加载更早的消息" else "向上滑动可查看更早的消息",
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
    }
}

/**
 * 等待智能体吐出第一帧时的一行占位。
 *
 * 只显示一个呼吸的小圆点加一句「正在生成」。它出现得越早越好，所以不加延迟，
 * 也不写「已发送」之类会让人误以为这是一个状态确认的词——这时请求未必已经到达服务端。
 * 首个 token / 工具调用 / 思考帧到达后，它会连同「处理中」那组一起被真正的步骤取代。
 */
@Composable
private fun AwaitingReplyRow() {
    val transition = rememberInfiniteTransition(label = "awaiting")
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(720),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "awaiting-alpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "正在生成",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一页渲染多少条（分组后的条目）。
 *
 * 按「用户一条 + 助手一条」的 4 个回合算，取 8 条。
 * 上游不支持分页，历史是一次性拿全的，所以这里是纯前端窗口化：
 * 首屏只铺最近一页，其余等滚动到顶再补，避免一次测量上千条。
 */
private const val PAGE_SIZE = 8

/**
 * 「贴底」用的滚动偏移量。
 *
 * `LazyListState` 会把超出内容范围的偏移钳到真实底部，因此给一个足够大的值
 * 就等价于「滚到最后」。默认的 0 是把目标条目的**顶部**对齐到视口顶部——
 * 对最后一条那种几屏长的回复，那不是底部，只是它的开头。
 */
private const val TAIL_SCROLL_OFFSET = Int.MAX_VALUE

/** 页面级错误条，带关闭按钮。 */
@Composable
private fun ErrorBar(text: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                RoundedCornerShape(10.dp),
            )
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭提示",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
