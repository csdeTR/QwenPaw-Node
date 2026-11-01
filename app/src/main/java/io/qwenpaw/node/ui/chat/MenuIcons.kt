package io.qwenpaw.node.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.qwenpaw.node.ui.theme.QwenIcons

/**
 * 菜单里的一项动作。
 *
 * 枚举而不是散落的字符串，是为了让「新增动作时忘了配图标」在单测里暴露：
 * `MenuIcons.of` 对未登记的取值返回 null，测试遍历全枚举即可发现遗漏。
 */
enum class MenuAction {
    COPY,
    COPY_MARKDOWN,
    SELECT_TEXT,
    DONE_SELECTING,
    REFRESH,
    RENAME,
    PIN,
    UNPIN,
    ARCHIVE,
    DELETE,
    MODEL,
    APPROVAL,
    PROJECT_DIR,
    THINKING,
    GROUP,
    EXPORT,
    SET_ICON,
}

/**
 * 菜单动作到图标的唯一映射。
 *
 * 图标全部取自 [QwenIcons]（lucide），与官方网页控制台同一套几何图形，
 * 不新增 drawable：矢量图标跟随主题着色，深色模式下不会出现一列黑色小图。
 */
object MenuIcons {

    /** 未登记的动作返回 null，调用方据此退化为纯文字菜单项。 */
    fun of(action: MenuAction): ImageVector? = when (action) {
        MenuAction.COPY -> QwenIcons.copy
        MenuAction.COPY_MARKDOWN -> QwenIcons.fileCode
        MenuAction.SELECT_TEXT -> QwenIcons.alignLeft
        MenuAction.DONE_SELECTING -> QwenIcons.check
        MenuAction.REFRESH -> QwenIcons.refresh
        MenuAction.RENAME -> QwenIcons.pencil
        MenuAction.PIN -> QwenIcons.pin
        MenuAction.UNPIN -> QwenIcons.pin
        MenuAction.ARCHIVE -> QwenIcons.archive
        MenuAction.DELETE -> QwenIcons.delete
        MenuAction.MODEL -> QwenIcons.bot
        MenuAction.APPROVAL -> QwenIcons.shieldCheck
        MenuAction.PROJECT_DIR -> QwenIcons.folder
        MenuAction.THINKING -> QwenIcons.brain
        MenuAction.GROUP -> QwenIcons.tag
        MenuAction.EXPORT -> QwenIcons.share
        MenuAction.SET_ICON -> QwenIcons.star
    }

    /**
     * 菜单项左侧图标。
     *
     * 单独封一层是为了统一尺寸：`DropdownMenuItem` 的 `leadingIcon` 不做缩放，
     * 20dp 与正文行高接近，不会把行撑高。
     */
    @androidx.compose.runtime.Composable
    fun Leading(action: MenuAction) {
        val icon = of(action) ?: return
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}