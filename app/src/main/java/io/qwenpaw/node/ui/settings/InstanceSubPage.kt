package io.qwenpaw.node.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.ui.common.pageContentInsets

/**
 * 实例表单的二级页外壳：反代验证与 SSH 直连共用。
 *
 * 顶栏直接复用设置页的 [SettingsTopBar]，正文边距与实例编辑表单一致，
 * 这样从编辑页点进去不会换一套观感。
 *
 * [subtitle] 是页名（「SSH 直连」等）。不写实例名：上一页顶栏已经写明
 * 「编辑实例」，这里再重复一次反而让人分不清自己在第几层。
 *
 * 这一页是页内覆盖层而不是 Dialog：Dialog 有独立窗口，Compose 在里面读到的
 * 键盘 insets 恒为 0，输入框会被键盘挡住。改成本页之后键盘高度由
 * [pageContentInsets] 一起消费，系统返回也要自己接管，与左上角返回对齐。
 *
 * [applyWindowInsets] 为 false 时表示外层已经让开了系统栏与键盘（放在 Scaffold
 * 的 `padding` 与 `imePadding()` 之后），这一层不再重复留白。
 */
@Composable
internal fun InstanceSubPage(
    subtitle: String,
    onBack: () -> Unit,
    applyWindowInsets: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Surface(
        // 背景必须铺满整屏，包括状态栏与手势条；insets 只作用于内容，
        // 否则父页的文字会从状态栏或底部露出来。
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (applyWindowInsets) {
                        // 状态栏、手势条与键盘取并集后一次让开。键盘收起时底边回落到
                        // 手势条高度，保存按钮不会被小白条压住。
                        Modifier.windowInsetsPadding(pageContentInsets())
                    } else {
                        Modifier
                    },
                ),
        ) {
            SettingsTopBar(onBack = onBack, subtitle = subtitle)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

/**
 * 二级页的主按钮，与实例编辑表单的提交按钮同款。
 *
 * 另一处在 `ConnectScreen` 的提交区，两处独立实现，改样式时要同步。
 */
@Composable
internal fun PrimaryBarButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
