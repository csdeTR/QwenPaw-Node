package io.qwenpaw.node.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.common.SecretVisibilityTrailingIcon
import io.qwenpaw.node.ui.common.rememberSecretVisibility
import io.qwenpaw.node.ui.theme.LocalCaptionColor

/**
 * 反向代理 HTTP Basic 凭据的二级页；密码留空表示保留原值。
 *
 * [applyWindowInsets] 转给 [InstanceSubPage]：从实例表单里打开时表单外层已经让开
 * 系统栏与键盘，这一层不再重复留白。
 */
@Composable
fun ProxyAuthScreen(
    initialUser: String,
    onBack: () -> Unit,
    applyWindowInsets: Boolean = true,
    onSave: (user: String, password: String) -> Unit,
) {
    var user by remember { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val passwordVisibility = rememberSecretVisibility()
    val needUser = stringResource(R.string.connect_proxy_need_user)

    InstanceSubPage(
        subtitle = stringResource(R.string.connect_proxy_title),
        onBack = onBack,
        applyWindowInsets = applyWindowInsets,
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
        // 标签在框上方、框内只放占位提示，和实例表单的字段写法一致。
        Text(
            text = stringResource(R.string.connect_proxy_user),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = user,
            onValueChange = {
                user = it
                error = null
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.connect_proxy_password),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            singleLine = true,
            visualTransformation = if (passwordVisibility.visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                SecretVisibilityTrailingIcon(
                    visible = passwordVisibility.visible,
                    onToggle = passwordVisibility.toggle,
                )
            },
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
        PrimaryBarButton(
            text = stringResource(R.string.action_save),
            onClick = {
                if (user.isBlank() && password.isNotEmpty()) {
                    error = needUser
                } else {
                    onSave(user.trim(), password)
                }
            },
        )
    }
}
