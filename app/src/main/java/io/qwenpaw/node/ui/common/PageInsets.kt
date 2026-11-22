package io.qwenpaw.node.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 子页的 Scaffold 内容 insets。
 *
 * targetSdk 35 起系统强制边到边，`adjustResize` 不再缩小窗口；键盘高度必须由
 * Compose 消费。Scaffold 会把这些 insets 并进 `padding`，页面只保留现有的
 * `.padding(padding)` 即可，不要在每个输入框上再补一次。
 */
@Composable
fun pageContentInsets(): WindowInsets =
    WindowInsets.systemBars.union(WindowInsets.ime)

/**
 * 顶栏在滚动内容里的页面使用。
 *
 * 先消费掉 Scaffold 已经提供的底部导航栏，避免键盘弹起时出现「导航栏 + 键盘」
 * 的双倍底距；只补键盘那一段。
 */
@Composable
fun Modifier.pageImePadding(): Modifier =
    consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
        .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))

/** 固定底部操作条的 insets。导航栏与键盘只能取并集，不能相加。 */
@Composable
fun bottomBarInsets(): WindowInsets =
    WindowInsets.navigationBars.union(WindowInsets.ime)
