package io.qwenpaw.node.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.qwenpaw.node.R
import io.qwenpaw.node.data.transcription.TranscriptionStatus
import io.qwenpaw.node.ui.i18n.Texts

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
        title = { Text(stringResource(R.string.voicegate_model_required_title)) },
        text = {
            Text(
                text = stringResource(R.string.voicegate_model_required_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onGoToSettings) { Text(stringResource(R.string.voicegate_go_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

private fun titleOf(status: TranscriptionStatus): String = when (status) {
    TranscriptionStatus.Disabled -> Texts.get(R.string.voicegate_title_disabled)
    TranscriptionStatus.Misconfigured -> Texts.get(R.string.voicegate_title_misconfigured)
    TranscriptionStatus.Unsupported -> Texts.get(R.string.voicegate_title_unsupported)
    TranscriptionStatus.Unknown -> Texts.get(R.string.voicegate_title_unknown)
    TranscriptionStatus.Ready -> Texts.get(R.string.voicegate_title_ready)
}

/**
 * 三种不可用的处置都在服务端，因此说明里只写「找谁做什么」，不给客户端操作。
 *
 * 未检测到与网络故障合并成一句：两者的表现相同（点下去拿不到结论），
 * 分开写只会让用户以为需要做两种不同的操作。
 */
private fun bodyOf(status: TranscriptionStatus): String = when (status) {
    TranscriptionStatus.Disabled ->
        Texts.get(R.string.voicegate_body_disabled)
    TranscriptionStatus.Misconfigured ->
        Texts.get(R.string.voicegate_body_misconfigured)
    TranscriptionStatus.Unsupported ->
        Texts.get(R.string.voicegate_body_unsupported)
    TranscriptionStatus.Unknown ->
        Texts.get(R.string.voicegate_body_unknown)
    TranscriptionStatus.Ready ->
        Texts.get(R.string.voicegate_body_ready)
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
        title = { Text(stringResource(R.string.voicegate_hide_title)) },
        text = {
            Text(
                text = stringResource(R.string.voicegate_hide_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onHide) { Text(stringResource(R.string.voicegate_hide_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.voicegate_hide_keep)) }
        },
    )
}