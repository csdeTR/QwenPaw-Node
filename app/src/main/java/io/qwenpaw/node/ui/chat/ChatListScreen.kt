package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.avatar.resolveChatIcon
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 对话列表页。
 *
 * 顶部是大标题和总数，下面一个搜索框，再往下是对话列表，
 * 新建对话固定在最底部。右上角可进入多选批量模式。
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chats: List<ChatSpecDto>,
    loading: Boolean,
    compact: Boolean = false,
    previews: Map<String, String> = emptyMap(),
    archivedCount: Int = 0,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (String) -> Unit = {},
    onArchive: (String) -> Unit = {},
    onBatchArchive: (List<String>) -> Unit = {},
    onBatchDelete: (List<String>) -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenTokenUsage: () -> Unit = {},
    onOpenSettings: () -> Unit,
    /** 打开收件箱。 */
    onOpenInbox: () -> Unit = {},
    /** 收件箱未读数；0 时不显示角标。 */
    inboxUnread: Int = 0,
    /**
     * 今天是否还要为归档弹确认框。
     *
     * 用户勾过「今日不再提示」后为 false：此时归档直接执行，不再打断。
     * 批量的关键信息（运行中的对话会被跳过）仍需告知，因此改由调用方提示。
     */
    showArchiveTip: Boolean = true,
    onSuppressArchiveTip: () -> Unit = {},
    /** 对话列表是附加页，左上角留一个回聊天页的入口。 */
    onBack: () -> Unit = {},
    /**
     * 下拉刷新。断网恢复后列表可能是空的，用户的第一反应是往下拉一把；
     * 有了它就不必退回聊天页或重启应用。
     */
    onRefresh: () -> Unit = {},
    /** 分组方式：按时间 / 按来源 / 不分组。 */
    groupMode: GroupMode = GroupMode.TIME,
    onGroupMode: (GroupMode) -> Unit = {},
    /** 服务端返回的对话分组，按来源分组时用。 */
    groups: List<io.qwenpaw.node.data.model.ChatGroupDto> = emptyList(),
    /** 已折叠的分区 key。折叠只隐藏条目、保留表头。 */
    collapsed: Set<String> = emptySet(),
    onToggleCollapse: (String) -> Unit = {},
    /** 新建分组；空名不会被调用。 */
    onCreateGroup: (String) -> Unit = {},
    /** 重命名自定义分组。 */
    onRenameGroup: (groupId: String, name: String) -> Unit = { _, _ -> },
    /** 删除自定义分组。 */
    onDeleteGroup: (groupId: String) -> Unit = {},
    /** 调整分组顺序：-1 上移、+1 下移。 */
    onReorderGroup: (groupId: String, delta: Int) -> Unit = { _, _ -> },
    /** 把某条对话移入分组。移回「未分组」要传服务端的默认分组 id。 */
    onMoveChatToGroup: (chatId: String, groupId: String?) -> Unit = { _, _ -> },
    /** 对话图标存储。为 null 时只显示默认图标，也不提供「设置图标」入口。 */
    iconStore: io.qwenpaw.node.avatar.ChatIconStore? = null,
    /** 图标版本：变化时重读图片。 */
    iconVersion: Long = 0L,
    /** 从相册为某对话选图标图片。参数是对话 id。 */
    onPickIconImage: ((String) -> Unit)? = null,
    /**
     * 打开全文检索页。为 null 表示不可用：未装配套插件，或用户关掉了
     * 「依赖配套插件的功能」。此时入口置灰，并在说明行写「需要配套插件」。
     */
    onOpenGlobalSearch: (() -> Unit)? = null,
) {
    var renaming by remember { mutableStateOf<ChatSpecDto?>(null) }
    var deleting by remember { mutableStateOf<ChatSpecDto?>(null) }
    var archiving by remember { mutableStateOf<ChatSpecDto?>(null) }
    // 长按菜单里的「移动到分组」。服务端没返回分组时不提供该入口。
    var movingToGroup by remember { mutableStateOf<ChatSpecDto?>(null) }
    // 归档确认框里的「今日不再提示」。每次打开弹窗时重置为未勾选。
    var archiveTipChecked by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    // 频道筛选只在列表页有效，不持久化：换服务器或换智能体后旧频道可能根本不存在，
    // 记住它会让用户进入列表看到空页却找不到原因。
    var channel by remember { mutableStateOf<String?>(null) }

    // 分组管理：新建 / 长按表头弹出的菜单。
    var creatingGroup by remember { mutableStateOf(false) }
    var groupNameText by remember { mutableStateOf("") }
    var groupMenuFor by remember { mutableStateOf<io.qwenpaw.node.data.model.ChatGroupDto?>(null) }
    var renamingGroup by remember { mutableStateOf<io.qwenpaw.node.data.model.ChatGroupDto?>(null) }
    var deletingGroup by remember { mutableStateOf<io.qwenpaw.node.data.model.ChatGroupDto?>(null) }

    // 多选批量模式。
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatch by remember { mutableStateOf<BatchAction?>(null) }

    // 候选从完整列表算，不能从切片结果算：否则选中后候选退化成单个，控件直接消失。
    val channels = remember(chats) { channelCandidates(chats) }
    val showChannelFilter = remember(channels) { shouldShowChannelFilter(channels) }

    // 刷新可能删掉某渠道的最后一个会话，残留的选中值会让列表变成空页。
    LaunchedEffect(channels) {
        if (!channelStillValid(channel, channels)) channel = null
    }

    // 顺序是「先按频道切片，再在切片里搜索」：用户看某个渠道时的搜索预期是在该渠道内找，
    // 反过来会跨频道命中，而列表头显示的是频道切片，容易对不上。
    val filtered = remember(chats, query, channel) {
        val inChannel = filterByChannel(chats, channel)
        val q = query.trim()
        if (q.isEmpty()) inChannel else inChannel.filter { it.name.contains(q, ignoreCase = true) }
    }

    // 搜索时强制不分组：分组会把命中的对话拆到多个表头下，反而更难扫读。
    val effectiveMode = if (query.isNotBlank()) GroupMode.NONE else groupMode
    // 原始分区与折叠后的分区都算一次：
    // 折叠后 chats 为空，但表头仍要显示该组原本的数量，所以两边都要有。
    val fullSections = remember(filtered, effectiveMode, groups) {
        ChatGrouping.sections(filtered, effectiveMode, groups)
    }
    val sections = remember(fullSections, collapsed, query) {
        ChatGrouping.collapse(
            fullSections,
            // 搜索时没有表头，也就没有折叠，这里清空集合避免残留状态干扰。
            if (query.isBlank()) collapsed else emptySet(),
        )
    }

    // 退出多选时清掉选中，避免下次进入残留旧的勾选。
    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    // 对话图标对话框。放在列表层而不是行内：一个对话只有一份图标，
    // 每次只可能有一个对话框，行内各自持有状态反而会留下多份。
    var iconTarget by remember { mutableStateOf<ChatSpecDto?>(null) }

    // 对话被外部删掉/归档后，选中集合里可能有失效 id，这里对齐一次。
    LaunchedEffect(chats) {
        if (selecting) {
            val live = chats.map { it.id }.toSet()
            if (selected.any { it !in live }) selected = selected.intersect(live)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ListHeader(
                count = chats.size,
                query = query,
                onQueryChange = { query = it },
                onBack = onBack,
                onOpenSettings = onOpenSettings,
                onOpenArchived = onOpenArchived,
                onOpenTokenUsage = onOpenTokenUsage,
                onOpenInbox = onOpenInbox,
                inboxUnread = inboxUnread,
                archivedCount = archivedCount,
                selecting = selecting,
                selectedCount = selected.size,
                groupMode = groupMode,
                onGroupMode = { onGroupMode(groupMode.next()) },
                channels = channels,
                showChannelFilter = showChannelFilter,
                channel = channel,
                onChannel = { channel = it },
                onOpenGlobalSearch = onOpenGlobalSearch,
                onCreateGroup = {
                    groupNameText = ""
                    creatingGroup = true
                },
                onToggleSelecting = {
                    if (selecting) exitSelection() else selecting = true
                },
                onSelectAll = {
                    selected = if (selected.size == filtered.size) {
                        emptySet()
                    } else {
                        filtered.map { it.id }.toSet()
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                BatchActionBar(
                    count = selected.size,
                    onArchive = {
                        if (selected.isNotEmpty()) {
                            // 今日不再提示时直接执行。运行中的对话会被服务端跳过，
                            // 这条信息仍要告知，因此由调用方用轻提示补充。
                            if (showArchiveTip) {
                                archiveTipChecked = false
                                confirmBatch = BatchAction.ARCHIVE
                            } else {
                                val ids = selected.toList()
                                onBatchArchive(ids)
                                exitSelection()
                            }
                        }
                    },
                    onDelete = {
                        if (selected.isNotEmpty()) confirmBatch = BatchAction.DELETE
                    },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        // 底色铺满后，再让开手势条，避免按钮被小白条压住。
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onCreate)
                            .padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = QwenIcons.plus,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "新建对话",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 下拉刷新：Material3 的 PullToRefreshBox 直接包住内容，
        // 指示器由框架画出，不需要自己维护下拉位移。
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading && chats.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                filtered.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = when {
                            chats.isEmpty() -> "还没有对话"
                            query.isNotBlank() -> "没有匹配的对话"
                            else -> "该频道没有对话"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            chats.isEmpty() -> "点击下方按钮即可新建对话"
                            query.isNotBlank() -> "请更换关键词后重试"
                            else -> "请切换到「全部」或其它频道"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 不分组时只有一个无标题分区，表头不渲染。
                    sections.forEach { section ->
                        if (section.title.isNotBlank()) {
                            stickyHeader(key = "h:${section.key}") {
                                SectionHeaderRow(
                                    title = section.title,
                                    // 折叠后 chats 已被清空，数量要从原始分区里取，
                                    // 否则表头会显示「0」——那不是「空分组」，是「被折起来了」。
                                    count = if (section.key in collapsed) {
                                        fullSections.firstOrNull { it.key == section.key }
                                            ?.chats?.size ?: 0
                                    } else {
                                        section.chats.size
                                    },
                                    collapsed = section.key in collapsed,
                                    onToggle = { onToggleCollapse(section.key) },
                                    /** 置顶区是虚拟分区，没有对应的服务端分组，不能改名或删除。 */
                                    group = groups.firstOrNull { "g:${it.id}" == section.key },
                                    onManageGroup = { groupMenuFor = it },
                                )
                            }
                        }
                        items(section.chats, key = { it.id }) { chat ->
                            ChatRow(
                                chat = chat,
                                compact = compact,
                                preview = previews[chat.id],
                                selecting = selecting,
                                checked = chat.id in selected,
                                iconStore = iconStore,
                                iconVersion = iconVersion,
                                onClick = {
                                    if (selecting) {
                                        selected = if (chat.id in selected) {
                                            selected - chat.id
                                        } else {
                                            selected + chat.id
                                        }
                                    } else {
                                        onOpen(chat.id)
                                    }
                                },
                                onRename = {
                                    renameText = chat.name
                                    renaming = chat
                                },
                                onDelete = { deleting = chat },
                                onTogglePin = { onTogglePin(chat.id) },
                                onArchive = {
                                    if (showArchiveTip) {
                                        archiveTipChecked = false
                                        archiving = chat
                                    } else {
                                        onArchive(chat.id)
                                    }
                                },
                                // 只有传入了存储才提供入口；预览/测试环境没有存储，
                                // 显示一个点了没反应的菜单项比不显示更糟。
                                onSetIcon = iconStore?.let { { iconTarget = chat } },
                                // 分组列表为空时同样不提供入口：没有可选目标，
                                // 菜单项点开后只会是一个空对话框。
                                onMoveToGroup = if (groups.any { it.kind != "default" }) {
                                    { movingToGroup = chat }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { chat ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(chat.id, renameText.trim())
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    deleting?.let { chat ->
        io.qwenpaw.node.ui.common.DestructiveActionDialog(
            title = "删除对话",
            message = "删除后无法恢复，确定要删除「${chat.name}」吗？",
            impacts = listOf(
                "该对话的全部消息记录",
                "与这些消息一同保存的图片与文件引用",
                "本机为该对话缓存的离线历史",
            ),
            confirmLabel = "删除",
            undoable = false,
            onConfirm = {
                onDelete(chat.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    movingToGroup?.let { chat ->
        OptionDialog(
            title = "移动到分组",
            items = groups.filter { it.kind != "default" }.map { group ->
                OptionItem(
                    key = group.id,
                    label = groupTitle(group),
                    selected = group.id == chat.groupId,
                )
            },
            leading = OptionItem(
                key = "",
                label = "未分组",
                // 服务端的默认分组 id 是 default，不是 null。
                selected = chat.groupId.isNullOrBlank() || chat.groupId == "default",
            ),
            onPick = { key ->
                movingToGroup = null
                // 移回「未分组」要发默认分组的 id：服务端对 group_id=null 返回
                // 400 `Unknown chat group: None`，静默失败会让用户以为没点中。
                val target = key.ifBlank { ChatGrouping.defaultGroupId(groups) }
                onMoveChatToGroup(chat.id, target)
            },
            onDismiss = { movingToGroup = null },
        )
    }

    archiving?.let { chat ->
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = { Text("归档对话") },
            text = {
                Column {
                    Text("归档后可在「已归档」中恢复，确定归档「${chat.name}」吗？")
                    ArchiveTipRow(
                        checked = archiveTipChecked,
                        onCheckedChange = { archiveTipChecked = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (archiveTipChecked) onSuppressArchiveTip()
                    onArchive(chat.id)
                    archiving = null
                }) { Text("归档") }
            },
            dismissButton = {
                TextButton(onClick = { archiving = null }) { Text("取消") }
            },
        )
    }

    // 图标对话框：预设网格 + 从相册选 + 恢复默认。
    // 只在这一处渲染，行菜单只负责把目标对话塞进来。
    iconTarget?.let { chat ->
        ChatIconDialog(
            chat = chat,
            store = iconStore,
            version = iconVersion,
            onPickPreset = { key ->
                iconStore?.savePreset(chat.id, key)
                iconTarget = null
            },
            onPickImage = {
                iconTarget = null
                onPickIconImage?.invoke(chat.id)
            },
            onReset = {
                iconStore?.clear(chat.id)
                iconTarget = null
            },
            onDismiss = { iconTarget = null },
        )
    }

    confirmBatch?.let { action ->
        val count = selected.size
        if (action == BatchAction.DELETE) {
            io.qwenpaw.node.ui.common.DestructiveActionDialog(
                title = "批量删除",
                message = "将删除选中的 $count 个对话，删除后无法恢复。",
                impacts = listOf(
                    "共 $count 个对话的全部消息记录",
                    "与这些消息一同保存的图片与文件引用",
                    "本机为这些对话缓存的离线历史",
                ),
                confirmLabel = "删除 $count 个对话",
                undoable = false,
                onConfirm = {
                    onBatchDelete(selected.toList())
                    confirmBatch = null
                    exitSelection()
                },
                onDismiss = { confirmBatch = null },
            )
            return@let
        }
        AlertDialog(
            onDismissRequest = { confirmBatch = null },
            title = { Text("批量归档") },
            text = {
                Column {
                    Text(
                        "将归档选中的 $count 个对话，之后可在「已归档」中恢复。" +
                            "正在运行的对话会被跳过。",
                    )
                    // 「今日不再提示」只对归档有意义：删除不可恢复，每次都该确认。
                    ArchiveTipRow(
                        checked = archiveTipChecked,
                        onCheckedChange = { archiveTipChecked = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (archiveTipChecked) onSuppressArchiveTip()
                    onBatchArchive(selected.toList())
                    confirmBatch = null
                    exitSelection()
                }) {
                    Text(if (action == BatchAction.ARCHIVE) "归档" else "删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatch = null }) { Text("取消") }
            },
        )
    }

    // ---------------- 分组管理 ----------------

    if (creatingGroup) {
        AlertDialog(
            onDismissRequest = { creatingGroup = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = groupNameText,
                    onValueChange = { groupNameText = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = groupNameText.trim()
                        if (name.isNotBlank()) onCreateGroup(name)
                        creatingGroup = false
                        groupNameText = ""
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { creatingGroup = false }) { Text("取消") }
            },
        )
    }

    groupMenuFor?.let { group ->
        AlertDialog(
            onDismissRequest = { groupMenuFor = null },
            title = { Text(group.name.ifBlank { group.id }) },
            text = {
                Column {
                    Text("可重命名、调整顺序或删除该分组；删除后组内对话将移回「未分组」。")
                    Spacer(Modifier.height(10.dp))
                    // 调整顺序只对自建分组开放：内置分组的相对位置由服务端固定，
                    // 移了也不会生效，给入口反而像是坏了。
                    val movable = group.kind == CUSTOM_KIND
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = movable,
                            onClick = {
                                onReorderGroup(group.id, -1)
                                groupMenuFor = null
                            },
                        ) { Text("上移") }
                        TextButton(
                            enabled = movable,
                            onClick = {
                                onReorderGroup(group.id, 1)
                                groupMenuFor = null
                            },
                        ) { Text("下移") }
                    }
                    if (!movable) {
                        Text(
                            text = "内置分组的顺序固定，不可调整",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        groupMenuFor = null
                        groupNameText = group.name
                        renamingGroup = group
                    },
                ) { Text("重命名") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { groupMenuFor = null }) { Text("取消") }
                    TextButton(
                        onClick = {
                            groupMenuFor = null
                            deletingGroup = group
                        },
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }

    renamingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { renamingGroup = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = groupNameText,
                    onValueChange = { groupNameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = groupNameText.trim()
                        if (name.isNotBlank()) onRenameGroup(group.id, name)
                        renamingGroup = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingGroup = null }) { Text("取消") }
            },
        )
    }

    deletingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = { Text("删除分组") },
            text = {
                Text(
                    "删除「${group.name.ifBlank { group.id }}」后，组内对话会移回「未分组」，" +
                        "对话本身不会被删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteGroup(group.id)
                        deletingGroup = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) { Text("取消") }
            },
        )
    }
}

/** 归档确认框里的「今日不再提示」勾选项。 */
@Composable
private fun ArchiveTipRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "今日不再提示",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 批量模式下要做的事，用于确认弹窗的文案。 */
private enum class BatchAction { ARCHIVE, DELETE }

/** 多选模式下的底部操作条。 */
@Composable
private fun BatchActionBar(
    count: Int,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 0) "选择对话" else "已选 $count 个",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        BatchButton(
            text = "归档",
            enabled = count > 0,
            onClick = onArchive,
        )
        BatchButton(
            text = "删除",
            enabled = count > 0,
            danger = true,
            onClick = onDelete,
        )
    }
}

@Composable
private fun BatchButton(
    text: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.outline
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun ListHeader(
    count: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenTokenUsage: () -> Unit,
    onOpenInbox: () -> Unit = {},
    inboxUnread: Int = 0,
    archivedCount: Int,
    selecting: Boolean = false,
    selectedCount: Int = 0,
    groupMode: GroupMode = GroupMode.TIME,
    onGroupMode: () -> Unit = {},
    /** 新建分组。只在「按来源」模式下有意义，其余模式隐藏入口。 */
    onCreateGroup: () -> Unit = {},
    onToggleSelecting: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    /** 频道候选（已去重排序）。 */
    channels: List<String> = emptyList(),
    /** 候选多于一个才显示筛选行。 */
    showChannelFilter: Boolean = false,
    /** 当前选中的频道，null 表示全部。 */
    channel: String? = null,
    onChannel: (String?) -> Unit = {},
    /** 全文检索入口。null 表示不可用，按钮置灰并显示说明行。 */
    onOpenGlobalSearch: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // 自定义顶栏要自己让开状态栏：Scaffold 仅在顶栏槽位为空时才把留白算进内容区。
            .statusBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Text(
                    text = if (selectedCount == 0) "选择对话" else "已选 $selectedCount 个",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (selectedCount == count && count > 0) "取消全选" else "全选",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSelectAll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    text = "完成",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleSelecting)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                IconButtonCell(
                    icon = QwenIcons.arrowLeft,
                    description = "返回聊天",
                    onClick = onBack,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "对话",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$count 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                // 收件箱入口。有未读时叠一个数字角标，没有时不额外占位。
                Box(contentAlignment = Alignment.TopEnd) {
                    IconButtonCell(
                        icon = QwenIcons.bell,
                        description = if (inboxUnread > 0) "收件箱 $inboxUnread 条未读" else "收件箱",
                        onClick = onOpenInbox,
                    )
                    if (inboxUnread > 0) {
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp, end = 2.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = if (inboxUnread > 99) "99+" else "$inboxUnread",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                            )
                        }
                    }
                }
                if (archivedCount > 0) {
                    IconButtonCell(
                        icon = QwenIcons.packageIcon,
                        description = "已归档 $archivedCount 个",
                        onClick = onOpenArchived,
                    )
                }
                IconButtonCell(
                    icon = QwenIcons.barChart,
                    description = "用量统计",
                    onClick = onOpenTokenUsage,
                )
                if (count > 0) {
                    IconButtonCell(
                        icon = QwenIcons.clipboardList,
                        description = "批量管理",
                        onClick = onToggleSelecting,
                    )
                }
                IconButtonCell(
                    icon = QwenIcons.settings,
                    description = "设置",
                    onClick = onOpenSettings,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!selecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = QwenIcons.search,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(7.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索对话名称",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable { onQueryChange("") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = QwenIcons.close,
                                contentDescription = "清空搜索",
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 分组切换：单击循环「按时间 → 按来源 → 不分组」，
                // 与 QwenPaw 网页版的单击循环一致，不必额外开菜单。
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable(onClick = onGroupMode)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = groupMode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 只有「按来源」模式下才提供新建分组：其他两种模式没有用户可见的分组概念，
                // 在那里建一个组既看不到、也移不进去。
                if (groupMode == GroupMode.SOURCE) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable(onClick = onCreateGroup)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "新建分组",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            // 全文检索入口。不可用时保留按钮并置灰，比整个隐藏更容易让人知道
            // 「这个能力存在，但需要插件」，也不必再找一个地方解释它。
            val globalSearch = onOpenGlobalSearch
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (globalSearch != null) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .then(
                            if (globalSearch != null) Modifier.clickable(onClick = globalSearch)
                            else Modifier
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = QwenIcons.search,
                            contentDescription = null,
                            tint = if (globalSearch != null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "全文检索",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (globalSearch != null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                if (globalSearch == null) {
                    Text(
                        text = "需要配套插件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            // 频道筛选只在候选多于一个时出现：只有一个渠道的实例上这一行不该占位置。
            if (showChannelFilter) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 候选不设上限时不换行，横向滚动比撑高顶栏更可控。
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChannelChip(label = "全部", selected = channel == null) { onChannel(null) }
                    channels.forEach { name ->
                        ChannelChip(label = name, selected = channel == name) { onChannel(name) }
                    }
                }
            }
        }
    }
}

/** 频道筛选胶囊。视觉配方与分组按钮一致，选中态改用次要容器色。 */
@Composable
private fun ChannelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 分区表头。吸顶显示，告诉用户下面这一组是什么。
 *
 * 整行可点 = 折叠/展开。折叠只隐藏条目、保留表头——用户需要看到「有这么一组」，
 * 只是不想看里面的内容。左侧箭头明确当前状态，光靠条目消失不易分辨。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionHeaderRow(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    /** 对应的服务端分组；置顶区与「未分组」为 null，长按无动作。 */
    group: io.qwenpaw.node.data.model.ChatGroupDto? = null,
    onManageGroup: (io.qwenpaw.node.data.model.ChatGroupDto) -> Unit = {},
) {
    // 只有自定义分组可以改名/删除。内置分组由服务端保护，客户端先拦一次，
    // 否则用户点删除只会拿到一条解释不了原因的报错。
    val editable = group != null && (group.kind == null || group.kind == CUSTOM_KIND)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .combinedClickable(
                onClick = onToggle,
                onLongClick = if (editable) ({ onManageGroup(group!!) }) else null,
            )
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) QwenIcons.chevronDown else QwenIcons.chevronUp,
            contentDescription = if (collapsed) "已折叠，点击展开" else "点击折叠",
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = io.qwenpaw.node.ui.theme.LocalCaptionColor.current,
        )
        if (collapsed) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "已折叠",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatSpecDto,
    compact: Boolean,
    preview: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    selecting: Boolean = false,
    checked: Boolean = false,
    iconStore: io.qwenpaw.node.avatar.ChatIconStore? = null,
    iconVersion: Long = 0L,
    /** 点「设置图标」。为 null 时菜单不显示该项。 */
    onSetIcon: (() -> Unit)? = null,
    /** 点「移动到分组」。为 null 时菜单不显示该项。 */
    onMoveToGroup: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    // 时间文案只在时间戳变化时重算，避免每次重组都解析日期。
    val time = remember(chat.updatedAt, chat.lastFinishedAt, chat.createdAt) {
        timeLabel(chat)
    }
    // 图标只在非紧凑排版下显示，紧凑排版的整个左侧图标区都被去掉了。
    // 按 (chatId, version) 记忆：滚动时反复重组不应重复查磁盘。
    val iconFile = remember(chat.id, iconVersion, compact) {
        if (!compact) runCatching { iconStore?.fileFor(chat.id) }.getOrNull() else null
    }
    val presetKey = remember(chat.id, iconVersion, compact) {
        if (!compact) iconStore?.presetFor(chat.id) else null
    }
    val iconSource = resolveChatIcon(hasImage = iconFile != null, hasPreset = presetKey != null)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                // 多选时选中行给一层浅色底，扫一眼就知道选了哪些。
                if (selecting && checked) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .combinedClickable(
                onClick = onClick,
                // 多选模式下长按不再弹菜单，避免两套操作互相打断。
                onLongClick = if (selecting) null else ({ menu = true }),
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 9.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                SelectionCheck(checked = checked)
                Spacer(Modifier.width(10.dp))
            } else if (!compact) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (chat.status == "running") {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .border(
                            1.dp,
                            if (chat.status == "running") {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when (iconSource) {
                        io.qwenpaw.node.avatar.ChatIconSource.IMAGE -> coil.compose.AsyncImage(
                            model = coil.request.ImageRequest.Builder(
                                androidx.compose.ui.platform.LocalContext.current,
                            )
                                .data(iconFile)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )

                        io.qwenpaw.node.avatar.ChatIconSource.PRESET -> Icon(
                            imageVector = iconStore?.presetIcon(presetKey)
                                ?: QwenIcons.messageCircle,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = if (chat.status == "running") {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )

                        io.qwenpaw.node.avatar.ChatIconSource.DEFAULT -> Icon(
                            imageVector = QwenIcons.messageCircle,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = if (chat.status == "running") {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.pinned) {
                        Icon(
                            imageVector = QwenIcons.pin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = chat.name.ifBlank { "未命名对话" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // 预览文案：紧凑模式也保留，用户扫一眼就能认出对话内容。
                if (!preview.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val running = chat.status == "running"
                if (!compact && running) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusTag("运行中", MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (!selecting) {
            ChatRowMenu(
                expanded = menu,
                pinned = chat.pinned,
                onDismiss = { menu = false },
                onRename = {
                    menu = false
                    onRename()
                },
                onDelete = {
                    menu = false
                    onDelete()
                },
                onTogglePin = {
                    menu = false
                    onTogglePin()
                },
                onArchive = {
                    menu = false
                    onArchive()
                },
                onSetIcon = onSetIcon?.let { action ->
                    {
                        menu = false
                        action()
                    }
                },
                onMoveToGroup = onMoveToGroup?.let { action ->
                    {
                        menu = false
                        action()
                    }
                },
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 14.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** 多选模式的勾选框。 */
@Composable
private fun SelectionCheck(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = QwenIcons.check,
                contentDescription = "已选择",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun IconButtonCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatusTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun ChatRowMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    /** 为 null 时不显示「设置图标」。 */
    onSetIcon: (() -> Unit)? = null,
    /** 为 null 时不显示「移动到分组」。 */
    onMoveToGroup: (() -> Unit)? = null,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("重命名") },
            leadingIcon = { MenuIcons.Leading(MenuAction.RENAME) },
            onClick = onRename,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(if (pinned) "取消置顶" else "置顶") },
            leadingIcon = {
                MenuIcons.Leading(if (pinned) MenuAction.UNPIN else MenuAction.PIN)
            },
            onClick = onTogglePin,
        )
        if (onSetIcon != null) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("设置图标") },
                leadingIcon = { MenuIcons.Leading(MenuAction.SET_ICON) },
                onClick = onSetIcon,
            )
        }
        if (onMoveToGroup != null) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("移动到分组") },
                leadingIcon = { MenuIcons.Leading(MenuAction.GROUP) },
                onClick = onMoveToGroup,
            )
        }
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("归档") },
            leadingIcon = { MenuIcons.Leading(MenuAction.ARCHIVE) },
            onClick = onArchive,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("删除") },
            leadingIcon = { MenuIcons.Leading(MenuAction.DELETE) },
            onClick = onDelete,
        )
    }
}

/**
 * 对话图标设置对话框。
 *
 * 三部分：预设网格、从相册选择、恢复默认。
 * 预设网格用 `FlowRow` 一屏铺满 8 个，不必滚动；每项都带文字标签，
 * 只有图标时用户很难判断「星」和「想法」哪个是自己的用途。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatIconDialog(
    chat: ChatSpecDto,
    store: io.qwenpaw.node.avatar.ChatIconStore?,
    version: Long,
    onPickPreset: (String) -> Unit,
    onPickImage: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentPreset = remember(chat.id, version) { store?.presetFor(chat.id) }
    val hasImage = remember(chat.id, version) { store?.fileFor(chat.id) != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("对话图标") },
        text = {
            Column {
                Text(
                    text = "「${chat.name.ifBlank { "未命名对话" }}」使用哪个图标",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    io.qwenpaw.node.avatar.ChatIconStore.presets.forEach { preset ->
                        val selected = currentPreset == preset.key && !hasImage
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPickPreset(preset.key) }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        RoundedCornerShape(10.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = preset.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(19.dp),
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "自选图片会裁剪为正方形并压缩，优先于上面的预设显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onPickImage) { Text("从相册选择") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 对话时间标签。
 *
 * 服务端给的是 UTC ISO 时间，必须换算到本地时区再切日期与时分：
 * 旧实现直接截字符串前 10 位，等于按 UTC 日界划分「今天／昨天」，
 * 在东八区会把早上 7 点发的消息算成前一天。
 */
internal fun timeLabel(chat: ChatSpecDto): String {
    val raw = chat.updatedAt ?: chat.lastFinishedAt ?: chat.createdAt ?: return ""
    val time = io.qwenpaw.node.ui.TimeLabels.parse(raw) ?: return ""
    val today = java.time.LocalDate.now()
    val date = time.toLocalDate()
    val diffDays = java.time.temporal.ChronoUnit.DAYS.between(date, today).toInt()
    val hhmm = time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        diffDays == 0 -> hhmm
        diffDays == 1 -> "昨天 $hhmm"
        diffDays in 2..6 -> {
            val weekday = date.dayOfWeek.value // 1=周一 .. 7=周日
            "周" + "一二三四五六日"[weekday - 1] + " " + hhmm
        }
        else -> date.toString().substring(5) + " " + hhmm
    }
}
