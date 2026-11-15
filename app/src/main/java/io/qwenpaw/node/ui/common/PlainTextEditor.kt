package io.qwenpaw.node.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 纯文本编辑页的通用外壳。
 *
 * 工作区文件与技能正文共用这一套：顶栏（返回 / 标题 / 状态 / 保存）、分隔线、
 * 错误行、占满剩余高度的等宽输入框，以及离开前的未保存确认。
 *
 * 组件的状态全部由调用方持有（受控组件），因为两处的「脏」判据不同：
 * 工作区比的是 `etag` 对应的已确认文本，技能比的是打开时的正文。
 * 这里只负责把它渲染出来，并在 `dirty` 时拦下一次返回。
 *
 * **冲突处理不在本组件里。** 工作区文件的保存带 `etag` 乐观锁，409 要在
 * 「重新加载」与「强制覆盖」之间选一个；技能正文没有版本号，不会冲突。
 * 把两者塞进一个组件，等于让技能页也背着一套它永远不会走到的分支。
 *
 * [onExit] 只在确认之后调用：`dirty` 为真时组件先弹一次询问，
 * 用户选「继续编辑」就留在原地，不把判断下放给每个调用方。
 */
@Composable
internal fun PlainTextEditor(
    title: String,
    backDescription: String,
    draft: String,
    dirty: Boolean,
    saving: Boolean,
    saved: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onExit: () -> Unit,
    /** 输入框上方的补充提示，为空时不占位。技能页用它显示 frontmatter 判据。 */
    hint: String? = null,
) {
    var confirmDiscard by remember { mutableStateOf(false) }

    fun requestExit() {
        if (saving) return
        if (dirty) confirmDiscard = true else onExit()
    }

    BackHandler { requestExit() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = ::requestExit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = QwenIcons.arrowLeft,
                    contentDescription = backDescription,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            } else if (saved && !dirty) {
                Text(
                    text = "已保存",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(
                onClick = onSave,
                enabled = dirty && !saving,
            ) { Text("保存") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        hint?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = !saving,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    if (confirmDiscard) {
        ChoiceDialog(
            title = "放弃未保存的修改？",
            message = "当前草稿还没有保存，离开后会丢失。",
            choices = listOf("放弃修改"),
            onPick = {
                confirmDiscard = false
                onExit()
            },
            onDismiss = { confirmDiscard = false },
            dismissLabel = "继续编辑",
        )
    }
}