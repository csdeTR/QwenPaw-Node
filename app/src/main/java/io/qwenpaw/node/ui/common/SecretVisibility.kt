package io.qwenpaw.node.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.R
import io.qwenpaw.node.ui.theme.LocalCaptionColor
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 密文字段的显隐状态。
 *
 * 只决定是否遮罩，输入值本身不参与，切换不会清空或改写已填内容。
 */
@Stable
class SecretVisibility internal constructor(initialVisible: Boolean) {

    var visible by mutableStateOf(initialVisible)
        private set

    /** 供 `trailingIcon` 直接引用的切换动作。 */
    val toggle: () -> Unit = { visible = !visible }
}

/** 记住一个密文字段的显隐状态；同一次页面停留内保持不变。 */
@Composable
fun rememberSecretVisibility(initialVisible: Boolean = false): SecretVisibility =
    remember { SecretVisibility(initialVisible) }

/** 密码输入框右侧的眼睛按钮；无障碍描述随当前状态切换。 */
@Composable
fun SecretVisibilityTrailingIcon(
    visible: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) QwenIcons.eyeOff else QwenIcons.eye,
            contentDescription = stringResource(
                if (visible) R.string.secret_hide else R.string.secret_show,
            ),
            modifier = Modifier.size(20.dp),
            tint = LocalCaptionColor.current,
        )
    }
}
