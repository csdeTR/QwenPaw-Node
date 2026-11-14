package io.qwenpaw.node.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.qwenpaw.node.data.transcription.TranscriptionStatus

/**
 * 点击麦克风但本地识别模型尚未下载时的一级提示。
 *
 * 只在带端上引擎的包、且转写来源为本地识别时出现。
 * 与 [VoiceUnavailableDialog] 的区别：那个解决「服务端没配好转写」，
 * 这个解决「本机缺文件」，后者用户自己能处理，因此给「去设置」。
 */
@Composable
fun VoiceModelRequiredDialog(
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要先下载识别模型") },
        text = {
            Text(
                text = "本地识别要用本机模型，当前尚未下载。模型约 150MB，只需下载一次，" +
                    "之后音频只在本机处理。也可以在设置中改用服务端转写。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onGoToSettings) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 点击麦克风但服务端转写不可用时的一级提示。
 *
 * 与 [VoiceIntroDialog] 的区别：那个在**打开设置开关**时出现，讲能力与用法；
 * 这个在**点击麦克风**时出现，解决「点了却录不了」。
 *
 * 不给「去设置」按钮：本机没有任何可下载的东西，配置在服务端那一侧；
 * 指向设置只会让用户去找一个不存在的入口。
 *
 * 用户在这里选「关闭」不会直接关闭语音开关，而是进入第二级询问是否隐藏按钮——
 * 因为「这次不用」与「以后都别显示」是两件事，不该合并成一次点击。
 */
@Composable
fun VoiceUnavailableDialog(
    status: TranscriptionStatus,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleOf(status)) },
        text = {
            Text(
                text = bodyOf(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            // 按钮文案用「关闭」而非「以后再说」，避免暗示「稍后会自动提醒」。
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun titleOf(status: TranscriptionStatus): String = when (status) {
    TranscriptionStatus.Disabled -> "服务端未启用语音转写"
    TranscriptionStatus.Misconfigured -> "服务端转写缺少配置"
    TranscriptionStatus.Unsupported -> "当前服务端版本不支持语音转写"
    TranscriptionStatus.Unknown -> "无法确认转写服务"
    TranscriptionStatus.Ready -> "语音转写可用"
}

/**
 * 三种不可用的处置都在服务端，因此说明里只写「找谁做什么」，不给客户端操作。
 *
 * 未检测到与网络故障合并成一句：两者的表现相同（点下去拿不到结论），
 * 分开写只会让用户以为需要做两种不同的操作。
 */
private fun bodyOf(status: TranscriptionStatus): String = when (status) {
    TranscriptionStatus.Disabled ->
        "服务端关闭了语音转写。在 QwenPaw 服务端的设置中启用转写服务并选定提供商后，此处即可使用。"
    TranscriptionStatus.Misconfigured ->
        "服务端已开启语音转写，但没有选定可用的提供商，或本地转写缺少依赖。" +
            "在 QwenPaw 服务端的设置中补齐转写配置后重试。"
    TranscriptionStatus.Unsupported ->
        "这个服务端版本没有语音转写接口，需要升级 QwenPaw 服务端。"
    TranscriptionStatus.Unknown ->
        "暂时无法确认服务端是否提供语音转写，请检查连接后重试。"
    TranscriptionStatus.Ready ->
        "可以录音并转写。"
}

/**
 * 第二级提示：询问是否顺带隐藏语音按钮。
 *
 * 只在用户于第一级选择「关闭」后出现。
 */
@Composable
fun HideVoiceButtonDialog(
    onHide: () -> Unit,
    onKeep: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("是否隐藏语音按钮") },
        text = {
            Text(
                text = "隐藏后输入框不再显示麦克风，「显示语音按钮」开关将同时关闭；" +
                    "需要时可在此重新开启。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onHide) { Text("隐藏") }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text("保留") }
        },
    )
}