package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role
import io.qwenpaw.node.ui.chat.MessageBubble
import io.qwenpaw.node.ui.i18n.Texts
import io.qwenpaw.node.ui.theme.ChatFontScale
import io.qwenpaw.node.ui.theme.FontScaling
import kotlin.math.roundToInt

/**
 * 聊天字号预览。
 *
 * 上半部分按真实气泡渲染一组示例消息，字号跟随「聊天字号」设置实时变化；
 * 下半部分把聊天页的输入条换成字号滑块——磨砂输入框占满底部，看不出字号变化，
 * 只有正文在变，把那块位置留给滑块更直接。
 *
 * 示例消息在本地构造，不发网络请求，也不依赖任何实例。
 */
@Composable
internal fun ChatFontPreview(
    chatScale: Float,
    onChatScale: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val samples = remember { previewMessages() }
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            ChatFontScale {
                samples.forEach { message ->
                    MessageBubble(message = message)
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.appearance_chat_scale_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${(chatScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = chatScale.coerceIn(FontScaling.CHAT_MIN, FontScaling.CHAT_MAX),
                    onValueChange = onChatScale,
                    valueRange = FontScaling.CHAT_MIN..FontScaling.CHAT_MAX,
                    steps = (
                        (FontScaling.CHAT_MAX - FontScaling.CHAT_MIN) / 0.05f
                        ).toInt() - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.appearance_chat_scale_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 四条示例消息：短问答、带 Markdown 的正文、带公式的正文、长段落。 */
private fun previewMessages(): List<ChatMessage> = listOf(
    ChatMessage(
        id = "preview-user-1",
        role = Role.USER,
        kind = ChatMessage.Kind.TEXT,
        text = Texts.get(R.string.appearance_sample_user_question),
    ),
    ChatMessage(
        id = "preview-agent-1",
        role = Role.ASSISTANT,
        kind = ChatMessage.Kind.TEXT,
        text = Texts.get(R.string.appearance_sample_answer),
    ),
    ChatMessage(
        id = "preview-agent-2",
        role = Role.ASSISTANT,
        kind = ChatMessage.Kind.TEXT,
        text = Texts.get(R.string.appearance_sample_formula),
    ),
    ChatMessage(
        id = "preview-agent-3",
        role = Role.ASSISTANT,
        kind = ChatMessage.Kind.TEXT,
        text = Texts.get(R.string.appearance_sample_long_paragraph),
    ),
)
