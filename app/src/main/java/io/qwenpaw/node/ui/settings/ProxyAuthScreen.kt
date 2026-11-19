package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.theme.LocalCaptionColor

/** 反向代理 HTTP Basic 凭据的二级页；密码留空表示保留原值。 */
@Composable
fun ProxyAuthScreen(
    instanceName: String,
    initialUser: String,
    onBack: () -> Unit,
    onSave: (user: String, password: String) -> Unit,
) {
    var user by remember { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val needUser = stringResource(R.string.connect_proxy_need_user)

    InstanceSubPage(
        title = stringResource(R.string.connect_proxy_title),
        subtitle = instanceName,
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.connect_proxy_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.connect_proxy_keep_hint),
            style = MaterialTheme.typography.labelSmall,
            color = LocalCaptionColor.current,
        )
        OutlinedTextField(
            value = user,
            onValueChange = {
                user = it
                error = null
            },
            label = { Text(stringResource(R.string.connect_proxy_user)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = { Text(stringResource(R.string.connect_proxy_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                if (user.isBlank() && password.isNotEmpty()) {
                    error = needUser
                    return@Button
                }
                onSave(user.trim(), password)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}
