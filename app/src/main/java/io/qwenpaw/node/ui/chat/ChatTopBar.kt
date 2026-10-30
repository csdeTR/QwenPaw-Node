package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

/** 聊天页顶栏：标题、状态行、上下文胶囊、更多菜单。 */
@Composable
fun ChatTopBar(
    title: String,
    running: Boolean,
    contextUsage: Double,
    pinned: Boolean = false,
    thinking: io.qwenpaw.node.data.model.ThinkingViewDto? = null,
    groups: List<io.qwenpaw.node.data.model.ChatGroupDto> = emptyList(),
    currentGroupId: String? = null,
    /** 可切换的模型，已扁平化。 */
    modelChoices: List<ModelChoice> = emptyList(),
    /** 当前模型显示名，用于菜单里标出「当前」。 */
    currentModelLabel: String? = null,
    /** 智能体当前审批模式：STRICT / SMART / AUTO / OFF。 */
    approvalLevel: String? = null,
    /** 当前工作区目录名；null 表示尚未拉取到。 */
    projectDirName: String? = null,
    /**
     * 与服务端的链路状态。
     *
     * 与 [running] 是两件不同的事：断网时 [running] 同样是 false，
     * 所以「已就绪」这种说法会和真实的连通性矛盾。
     */
    link: LinkState = LinkState.UNKNOWN,
    /** 当前实际绑定的地址，用于说清「连的是哪里」。 */
    boundUrl: String = "",
    /** 该地址的显示名：有别名用别名，否则与 [boundUrl] 相同。 */
    boundLabel: String = "",
    /** 实例显示名。为空时状态行末尾仍写 QwenPaw。 */
    serverName: String = "",
    /** 返回主页面：聊天页是主页面，这里的按钮改成打开会话列表。 */
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit = {},
    onArchive: () -> Unit = {},
    onExport: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onContextClick: () -> Unit = {},
    onSetThinking: (String) -> Unit = {},
    onSetGroup: (String?) -> Unit = {},
    onPickModel: (providerId: String?, model: String?) -> Unit = { _, _ -> },
    onPickApproval: (String) -> Unit = {},
    onOpenProjectDir: () -> Unit = {},
    /** 打开「切换模型」对话框。二级选择由外层渲染，这里只负责发信号。 */
    onOpenModelDialog: () -> Unit = {},
    /** 打开「审批模式」对话框。 */
    onOpenApprovalDialog: () -> Unit = {},
    /** 打开「移动到分组」对话框。 */
    onOpenGroupDialog: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    // 模型支持的思考档位；空表示不可调节，此时不显示入口。
    val efforts = thinking?.control?.efforts.orEmpty()
    val canAdjustThinking = efforts.isNotEmpty() || thinking?.control?.supportsOff == true
    Column(
        modifier = modifier
            .fillMaxWidth()
            // targetSdk 35 起系统强制边到边，系统栏浮在内容之上。Scaffold 只在
            // 顶栏槽位为空时才把状态栏留白算进内容区；这里顶栏是自定义的，
            // 必须自己让开状态栏，否则标题会被通知栏压住。
            // 放在 background 之后：底色仍铺满状态栏区域，只有内容下移。
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = "会话列表",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = title.ifBlank { "新会话" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onRename),
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 四种状态互斥，优先级：正在生成 > 连接中断 > 正在连接 > 已连接。
                        // 少一档就会把 UNKNOWN 落进「已连接」，而那正是「关掉网络仍显示
                        // 已连接」的来源：没有请求失败，状态就一直停在上一轮的 ONLINE。
                        val offline = !running && link == LinkState.OFFLINE
                        val dotColor = when {
                            running -> Color(0xFFFAAD14)
                            offline -> MaterialTheme.colorScheme.error
                            link == LinkState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.secondary
                        }
                        val label = when {
                            running -> "正在生成"
                            offline -> "连接中断，点击重连"
                            link == LinkState.UNKNOWN -> "正在连接…"
                            else -> connectedLabel(boundUrl, boundLabel, serverName)
                        }
                        Row(
                            // 「已通过 192.168.1.10:8088 连接至 QwenPaw」比原来的「已连接」
                            // 长得多，给它限一行并允许省略，否则会把右侧的上下文胶囊挤出去。
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .then(
                                    // 只有断线时这一行可点：此时点击就是「立刻重连」，
                                    // 比让用户自己去菜单里找刷新更直接。
                                    if (offline) {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(onClick = onRefresh)
                                            .padding(horizontal = 2.dp)
                                    } else {
                                        Modifier
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (offline) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    if (contextUsage > 0) {
                        ContextPill(ratio = contextUsage, onClick = onContextClick)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable { menu = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // 刷新放在最前：断网恢复后最常见的动作就是它，
                    // 而这一项不改变任何数据，误点的代价只是多一次请求。
                    DropdownMenuItem(
                        text = { Text("刷新会话") },
                        onClick = {
                            menu = false
                            onRefresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名会话") },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (pinned) "取消置顶" else "置顶会话") },
                        onClick = {
                            menu = false
                            onTogglePin()
                        },
                    )

                    // 模型 / 审批 / 工作区三项都作用于「接下来这一轮怎么跑」，
                    // 与上面的会话操作分开一组。
                    DropdownMenuItem(
                        text = {
                            Text("切换模型：${currentModelLabel ?: "跟随智能体默认"}")
                        },
                        enabled = modelChoices.isNotEmpty(),
                        onClick = {
                            menu = false
                            onOpenModelDialog()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("审批模式：${approvalLabel(approvalLevel)}") },
                        enabled = approvalLevel != null,
                        onClick = {
                            menu = false
                            onOpenApprovalDialog()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("工作区目录：${projectDirName ?: "未读取"}") },
                        onClick = {
                            menu = false
                            onOpenProjectDir()
                        },
                    )

                    // 思考模式：只在模型明确支持调节时给出可选项。
                    if (canAdjustThinking) {
                        DropdownMenuItem(
                            text = {
                                Text("思考模式：${thinkingLevelLabel(thinking?.value?.level)}")
                            },
                            onClick = {
                                menu = false
                                thinkingMenu = true
                            },
                        )
                    }
                    if (groups.isNotEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text("移动到分组：${groupLabel(groups, currentGroupId)}")
                            },
                            onClick = {
                                menu = false
                                onOpenGroupDialog()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("导出 Markdown") },
                        onClick = {
                            menu = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("归档会话") },
                        onClick = {
                            menu = false
                            onArchive()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除会话") },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
                DropdownMenu(expanded = thinkingMenu, onDismissRequest = { thinkingMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("跟随模型默认") },
                        onClick = {
                            thinkingMenu = false
                            onSetThinking("inherit")
                        },
                    )
                    if (thinking?.control?.supportsOff == true) {
                        DropdownMenuItem(
                            text = { Text("关闭思考") },
                            onClick = {
                                thinkingMenu = false
                                onSetThinking("off")
                            },
                        )
                    }
                    efforts.forEach { effort ->
                        DropdownMenuItem(
                            text = { Text(thinkingLevelLabel(effort)) },
                            onClick = {
                                thinkingMenu = false
                                onSetThinking(effort)
                            },
                        )
                    }
                }
                // 模型 / 审批 / 分组三项的二级选择改由外层渲染成居中对话框：
                // 下拉菜单贴着这个小图标展开，长列表会顶到屏幕边缘且不好滚。
                // 这里保留思考模式的二级菜单——它最多五六项，下拉反而更快。
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** 上下文占用胶囊，带一圈细进度环；点击查看明细。 */
@Composable
private fun ContextPill(ratio: Double, onClick: () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = (360f * ratio.coerceIn(0.0, 1.0)).toFloat(),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${(ratio * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「已连接」时的完整描述。
 *
 * 只说「已连接」看不出连的是哪条路：一台机器可能同时存着局域网地址与
 * 隧道地址，用户需要一眼确认走的是哪条。别名与地址不同时用「别名」包起来，
 * 相同时直接写地址——不会出现「「192.168.1.10:8088」」这种双重引号。
 *
 * 末尾的服务器名取自实例的「服务器名称」：设置过就用「名称」包起来，
 * 没设置时仍写产品名 QwenPaw，避免出现「连接至「」」这种空引号。
 *
 * 传进来的 [serverName] 应当已经排除了「名称只是回落出来的地址」这一种情况，
 * 见 `InstanceConfig.serverDisplayName`。
 */
internal fun connectedLabel(
    boundUrl: String,
    boundLabel: String,
    serverName: String = "",
): String {
    val url = boundUrl.trim()
    val label = boundLabel.trim()
    val server = serverName.trim()
    // 空名字回落到产品名。调用方（AppViewModel）已经用
    // InstanceConfig.serverDisplayName 过滤掉「名字其实就是地址」的情况，
    // 这里只处理「没起名字」这一种。
    val named = server.isNotEmpty()
    // 「连接至」后面接中文引号时不留空格，接拉丁产品名时留一个空格。
    // 两种都写成空格会得到「连接至 「家里的机器」」，排版上多出一个洞。
    val target = if (named) "「$server」" else " QwenPaw"
    return when {
        // 地址与别名都缺失时只剩一句话，此时若设过名称仍应说清连的是哪台。
        label.isEmpty() && url.isEmpty() ->
            if (named) "已连接至$target" else "已连接"

        label.isEmpty() || label == url -> "已通过 $url 连接至$target"
        else -> "已通过「$label」连接至$target"
    }
}

/** 思考档位的中文标签。 */
internal fun thinkingLevelLabel(level: String?): String = when (level) {
    null, "", "inherit" -> "跟随模型"
    "off" -> "关闭"
    "minimal" -> "最低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最高"
    "budget" -> "预算"
    else -> level
}

/**
 * 审批模式的四个档位。
 *
 * 取值与 qwenpaw web 页一致（`approval_level`），这是**智能体级**配置，
 * 改动会影响该智能体的全部会话。
 */
internal data class ApprovalOption(val code: String, val label: String, val hint: String)

internal val APPROVAL_OPTIONS = listOf(
    ApprovalOption("STRICT", "严格", "每一步工具调用均需批准"),
    ApprovalOption("SMART", "智能", "低风险操作自动放行，风险较高的操作才需询问"),
    ApprovalOption("AUTO", "自动", "仅受保护的工具需要批准"),
    ApprovalOption("OFF", "关闭防护", "不再询问，工具调用直接执行"),
)

/** 审批模式的中文标签。 */
internal fun approvalLabel(level: String?): String =
    APPROVAL_OPTIONS.firstOrNull { it.code == level }?.label ?: "未读取"

/** 把分组 id 换成展示名。 */
private fun groupLabel(
    groups: List<io.qwenpaw.node.data.model.ChatGroupDto>,
    currentGroupId: String?,
): String {
    val id = currentGroupId ?: return "未分组"
    return groups.firstOrNull { it.id == id }?.name?.ifBlank { id } ?: id
}
