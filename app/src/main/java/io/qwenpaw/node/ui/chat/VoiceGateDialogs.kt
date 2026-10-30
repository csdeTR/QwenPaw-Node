package io.qwenpaw.node.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 点击麦克风但模型未下载时的第一级提示。
 *
 * 与 [VoiceIntroDialog] 的区别：那个在**打开设置开关**时出现，讲能力与用法；
 * 这个在**点击麦克风**时出现，解决「想用但缺模型」。
 *
 * 用户在这里选「关闭」不会直接关闭语音开关，而是进入第二级询问是否隐藏按钮——
 * 因为「这次不用」与「以后都别显示」是两件事，不该合并成一次点击。
 */
@Composable
fun VoiceModelRequiredDialog(
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语音输入需要先下载模型") },
        text = {
            Text(
                text = "识别在本机完成，音频不会上传；模型约 150MB。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onGoToSettings) { Text("去设置下载") }
        },
        // 按钮文案用「关闭」而非「以后再说」，避免暗示「稍后会自动提醒」。
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
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