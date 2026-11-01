package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.avatar.AvatarStore
import io.qwenpaw.node.ui.common.ChoiceDialog
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 空对话页的首页。
 *
 * 结构与官方网页的空态一致：智能体头像、一句欢迎语、一句定位说明，
 * 再给两条可以直接发的预设。预设的好处是省掉「第一句说什么」的犹豫，
 * 代价是误触——触屏上点一下就真的建了一个对话，因此点完先弹一次确认。
 *
 * 头像走 [ChatAvatar]，因此设置页里换的智能体头像会直接反映在这里。
 */
@Composable
internal fun EmptyChatHome(
    appearance: MessageAppearance,
    onSendPreset: (String) -> Unit,
) {
    // 待确认的那条预设。null 表示没有待发。
    var pending by remember { mutableStateOf<String?>(null) }

    pending?.let { text ->
        ChoiceDialog(
            title = "发送这条消息？",
            message = text,
            choices = listOf("发送"),
            onPick = {
                pending = null
                onSendPreset(text)
            },
            onDismiss = { pending = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ChatAvatar(
            store = appearance.avatarStore,
            kind = AvatarStore.KIND_AGENT,
            name = appearance.agentName,
            size = 56.dp,
            version = appearance.avatarVersion,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "你好，我今天能帮你做什么？",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "我是一个智能助手，可以帮助你解答问题。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PresetCard("让我们开启一段新的旅程吧！") { pending = it }
        Spacer(Modifier.height(10.dp))
        PresetCard("能告诉我你有哪些技能吗？") { pending = it }
    }
}

/** 一条预设消息。整行可点，右侧一个箭头表示「点它会发出去」。 */
@Composable
private fun PresetCard(text: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable { onPick(text) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = QwenIcons.sparkles,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = QwenIcons.chevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}