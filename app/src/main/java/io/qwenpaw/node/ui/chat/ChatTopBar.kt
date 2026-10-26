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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit = {},
    onArchive: () -> Unit = {},
    onExport: () -> Unit = {},
    onContextClick: () -> Unit = {},
    onSetThinking: (String) -> Unit = {},
    onSetGroup: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    // 模型支持的思考档位；空表示不可调节，此时不显示入口。
    val efforts = thinking?.control?.efforts.orEmpty()
    val canAdjustThinking = efforts.isNotEmpty() || thinking?.control?.supportsOff == true
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
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
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
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
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (running) Color(0xFFFAAD14)
                                    else MaterialTheme.colorScheme.outline,
                                ),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (running) "正在生成" else "已就绪",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                groupMenu = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("复制全部") },
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
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                    groups.forEach { group ->
                        DropdownMenuItem(
                            text = { Text(group.name.ifBlank { group.id }) },
                            onClick = {
                                groupMenu = false
                                onSetGroup(group.id)
                            },
                        )
                    }
                }
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

/** 把分组 id 换成展示名。 */
private fun groupLabel(
    groups: List<io.qwenpaw.node.data.model.ChatGroupDto>,
    currentGroupId: String?,
): String {
    val id = currentGroupId ?: return "未分组"
    return groups.firstOrNull { it.id == id }?.name?.ifBlank { id } ?: id
}
