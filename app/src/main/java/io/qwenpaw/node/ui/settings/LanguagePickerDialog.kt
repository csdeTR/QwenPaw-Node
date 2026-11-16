package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.ui.theme.QwenIcons

internal data class ServerLanguageOption(val code: String, val label: String)

/** 与服务端 `_VALID_LANGUAGES` 一一对应；少一个服务端就会 400。 */
internal val SERVER_LANGUAGES = listOf(
    ServerLanguageOption("zh", "中文"),
    ServerLanguageOption("en", "English"),
    ServerLanguageOption("ja", "日本語"),
    ServerLanguageOption("ru", "Русский"),
    ServerLanguageOption("pt-BR", "Português (Brasil)"),
    ServerLanguageOption("id", "Bahasa Indonesia"),
    ServerLanguageOption("vi", "Tiếng Việt"),
)

internal fun serverLanguageLabel(code: String): String =
    SERVER_LANGUAGES.firstOrNull { it.code == code }?.label ?: code

/**
 * 服务端 Web 控制台语言的选择框。
 *
 * 这不是客户端语言：客户端的界面文案只有中文。入口文案与说明都必须写明
 * 「服务端界面语言」，否则用户会以为切换后手机界面会跟着变。
 */
@Composable
internal fun LanguagePickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务端界面语言", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "只影响 QwenPaw Web 控制台的语言，不改变本应用的界面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 8.dp))
                SERVER_LANGUAGES.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option.code) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (option.code == current) {
                            Icon(
                                imageVector = QwenIcons.check,
                                contentDescription = "当前语言",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
