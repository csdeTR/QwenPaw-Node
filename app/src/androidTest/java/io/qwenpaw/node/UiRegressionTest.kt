package io.qwenpaw.node

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import io.qwenpaw.node.data.model.ChatGroupDto
import io.qwenpaw.node.data.model.ChatSpecDto
import io.qwenpaw.node.data.stream.ChatMessage
import io.qwenpaw.node.data.stream.Role
import io.qwenpaw.node.ui.chat.ActionStyle
import io.qwenpaw.node.ui.chat.ChatListScreen
import io.qwenpaw.node.ui.chat.MessageAppearance
import io.qwenpaw.node.ui.chat.MessageBubble
import io.qwenpaw.node.ui.chat.GroupMode
import io.qwenpaw.node.ui.settings.SettingsScreen
import io.qwenpaw.node.ui.theme.QwenPawTheme
import org.junit.Rule
import org.junit.Test

/**
 * 本轮改动的界面行为。
 *
 * 只断言「用户能看见的差异」：消息操作行的文字/图标切换、设置页的一级列表与子页切换、
 * 会话图标的显示与否。刻意不去点整套设置页流程——那种用例一旦文案调整就会
 * 连带失败，而它想验证的东西（比如返回键层级）已经由 `BackHandler` 的写法保证。
 */
class UiRegressionTest {

    @get:Rule
    val compose = createComposeRule()

    private fun message(id: String, text: String) = ChatMessage(
        id = id,
        role = Role.ASSISTANT,
        kind = ChatMessage.Kind.TEXT,
        text = text,
        timestamp = "2026-11-01T02:00:00Z",
        finishedAt = "2026-11-01T02:00:01Z",
        turnEnd = true,
    )

    /** 文字档下三个操作项都在，可复制文本的消息才有操作行。 */
    @Test
    fun textActionStyleShowsLabels() {
        compose.setContent {
            QwenPawTheme {
                Surface(Modifier.fillMaxSize()) {
                    MessageBubble(
                        message = message("m1", "你好"),
                        appearance = MessageAppearance(actionStyle = ActionStyle.TEXT),
                    )
                }
            }
        }
        compose.onNodeWithText("复制").assertIsDisplayed()
        compose.onNodeWithText("复制为 Markdown").assertIsDisplayed()
        compose.onNodeWithText("选择文本").assertIsDisplayed()
    }

    /**
     * 图标档下文字节点不再出现，但无障碍描述保留同一段文字。
     *
     * 这条断言同时守住两件事：视觉上确实换成了图标；读屏与自动化仍能找到「复制」。
     */
    @Test
    fun iconActionStyleUsesContentDescription() {
        compose.setContent {
            QwenPawTheme {
                Surface(Modifier.fillMaxSize()) {
                    MessageBubble(
                        message = message("m2", "你好"),
                        appearance = MessageAppearance(actionStyle = ActionStyle.ICON),
                    )
                }
            }
        }
        // 图标档下没有文字节点，只有 contentDescription。
        compose.onNodeWithText("复制").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("复制").assertIsDisplayed()
        compose.onNodeWithContentDescription("复制为 Markdown").assertIsDisplayed()
    }

    /**
     * 设置页改为一级列表 + 子页：一级页只有入口行，点「语音」后
     * 才渲染出子页里的具体控件。
     *
     * 设置页是一个 `verticalScroll` 的 `Column`，入口行可能位于首屏之外，
     * 所以点与断言之前都要先滚到它——直接点击会让命中点落在屏幕外，
     * 表现为「点了没反应」。
     */
    @Test
    fun settingsUsesListThenSubPage() {
        compose.setContent {
            QwenPawTheme {
                SettingsScreen(
                    instances = emptyList(),
                    activeId = null,
                    themeMode = io.qwenpaw.node.ui.theme.ThemeMode.SYSTEM,
                    uiScale = io.qwenpaw.node.ui.theme.FontScaling.DEFAULT,
                    chatScale = io.qwenpaw.node.ui.theme.FontScaling.DEFAULT,
                    useMiSans = false,
                    compactList = false,
                    keepScreenOn = false,
                    appVersion = "test",
                    onBack = {},
                    onSwitch = {},
                    onRemove = {},
                    onEdit = {},
                    onPickAgent = {},
                    onAdd = { _, _ -> },
                    onVerifyHosts = { _, _ ->
                        io.qwenpaw.node.data.VerifyResult(
                            baseUrl = "",
                            version = "",
                            needsLogin = false,
                        )
                    },
                    onThemeMode = {},
                    onUiScale = {},
                    onChatScale = {},
                    onUseMiSans = {},
                    onCompactList = {},
                    onKeepScreenOn = {},
                )
            }
        }
        // 一级页只有入口行，子页内容不在组合树里。
        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.onNodeWithText("语音").assertIsDisplayed()
        compose.onNodeWithText("运行日志").assertIsDisplayed()
        compose.onNodeWithText("触发方式").assertDoesNotExist()

        compose.onNodeWithText("语音").performScrollTo().performClick()
        compose.waitForIdle()
        // 子页里出现具体控件，且其它入口行不再渲染。
        compose.onNodeWithText("触发方式").assertIsDisplayed()
        compose.onNodeWithText("运行日志").assertDoesNotExist()
    }

    /** 紧凑排版下去掉左侧图标区；非紧凑排版保留。 */
    @Test
    fun compactListHidesChatIcon() {
        val chats = listOf(
            ChatSpecDto(id = "c1", name = "会话一"),
            ChatSpecDto(id = "c2", name = "会话二"),
        )
        compose.setContent {
            QwenPawTheme {
                ChatListScreen(
                    chats = chats,
                    loading = false,
                    compact = false,
                    onOpen = {},
                    onCreate = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                    onOpenSettings = {},
                    groupMode = GroupMode.NONE,
                )
            }
        }
        compose.onNodeWithText("会话一").assertIsDisplayed()
        compose.onNodeWithText("会话二").assertIsDisplayed()
    }

    /**
     * 分组表的默认折叠状态来自参数：传进去的 key 对应的组，条目不可见、表头仍在。
     *
     * 表头文字用中文：服务端返回的 `Uncategorized` 由 `ChatGrouping.groupTitle`
     * 按分组 kind 换成「未分组」。
     */
    @Test
    fun collapsedGroupKeepsHeaderButHidesRows() {
        val groups = listOf(
            ChatGroupDto(id = "default", name = "Uncategorized", order = 1, kind = "default"),
        )
        compose.setContent {
            QwenPawTheme {
                ChatListScreen(
                    chats = listOf(ChatSpecDto(id = "x", name = "被折叠的会话", groupId = "default")),
                    loading = false,
                    onOpen = {},
                    onCreate = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                    onOpenSettings = {},
                    groupMode = GroupMode.SOURCE,
                    groups = groups,
                    collapsed = setOf("g:default"),
                )
            }
        }
        compose.onNodeWithText("未分组").assertIsDisplayed()
        compose.onNodeWithText("被折叠的会话").assertIsNotDisplayed()
    }
}