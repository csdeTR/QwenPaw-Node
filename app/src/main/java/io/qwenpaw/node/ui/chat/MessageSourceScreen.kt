package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qwenpaw.node.ui.theme.LocalChatFontScale
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 一条消息的原始 Markdown。
 *
 * 「选择文本」在排版后的气泡里进行，而气泡里的公式是二维排版的、表格被重画、
 * 图片只剩一块占位，拖动选到的字符与原文并非一一对应。原文页只做一件事：
 * 把这条消息的源字符串原样铺开，等宽、可拖动选中、可整条复制。
 *
 * 不做 Markdown 渲染是刻意的：这里要给的是「标记本身」，
 * 再渲染一遍又回到与原文有差异的状态。
 */
@Composable
fun MessageSourceScreen(
    text: String,
    onBack: () -> Unit,
) {
    val scale = LocalChatFontScale.current
    val clipboard = LocalClipboardManager.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    // 自定义顶栏自行让开状态栏，否则会被通知栏压住。
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
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
                            imageVector = QwenIcons.arrowLeft,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Markdown 原文",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "可拖动选中，或用右侧「复制全文」",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "复制全文",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                if (text.isNotBlank()) clipboard.setText(AnnotatedString(text))
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(width = 0.dp, height = 1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        },
    ) { padding ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (13 * scale).sp,
                    lineHeight = (20 * scale).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}