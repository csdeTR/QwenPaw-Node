package io.qwenpaw.node.avatar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户头像的来源判定。
 *
 * 本地优先于服务端、开关只作用于服务端，是两条容易被后续改动悄悄颠倒的规则，
 * 所以把全部组合逐一钉住。函数是纯的，不需要 Robolectric。
 */
class AvatarSourcePrecedenceTest {

    private fun resolve(
        hasLocal: Boolean,
        serverEnabled: Boolean,
        hasServerImage: Boolean,
    ) = io.qwenpaw.node.ui.chat.resolveUserAvatar(hasLocal, serverEnabled, hasServerImage)

    /** 本机有图时永远用它，开关状态与服务端是否有图都不影响。 */
    @Test
    fun `local image wins regardless of server`() {
        assertEquals(
            io.qwenpaw.node.ui.chat.UserAvatarSource.LOCAL,
            resolve(hasLocal = true, serverEnabled = true, hasServerImage = true),
        )
        assertEquals(
            io.qwenpaw.node.ui.chat.UserAvatarSource.LOCAL,
            resolve(hasLocal = true, serverEnabled = false, hasServerImage = true),
        )
        assertEquals(
            io.qwenpaw.node.ui.chat.UserAvatarSource.LOCAL,
            resolve(hasLocal = true, serverEnabled = true, hasServerImage = false),
        )
        assertEquals(
            io.qwenpaw.node.ui.chat.UserAvatarSource.LOCAL,
            resolve(hasLocal = true, serverEnabled = false, hasServerImage = false),
        )
    }

    /** 本机没图、服务端有图且开关打开时用服务端。 */
    @Test
    fun `server image is used when enabled and local is absent`() {
        assertEquals(
            io.qwenpaw.node.ui.chat.UserAvatarSource.SERVER,
            resolve(hasLocal = false, serverEnabled = true, hasServerImage = true),
        )
    }

    /** 开关关掉时服务端有图也不用：关掉它的含义是「不显示」，不是「删掉」。 */
    @Test
    fun `disabled switch falls back to initial even if server has image`() {
        assertEquals(
            io.qwenpaw.node.ui.chat.UserAvatarSource.INITIAL,
            resolve(hasLocal = false, serverEnabled = false, hasServerImage = true),
        )
    }

    /** 什么都没有时回落首字。 */
    @Test
    fun `no source falls back to initial`() {
        assertEquals(
            io.qwenpaw.node.ui.chat.UserAvatarSource.INITIAL,
            resolve(hasLocal = false, serverEnabled = true, hasServerImage = false),
        )
        assertEquals(
            io.qwenpaw.node.ui.chat.UserAvatarSource.INITIAL,
            resolve(hasLocal = false, serverEnabled = false, hasServerImage = false),
        )
    }
}

/**
 * 会话图标的来源判定。
 *
 * 图片与预设实际互斥（保存时会清掉另一份），但判定仍按「图片优先」定死：
 * 从备份恢复数据时两份可能同时存在，需要一个确定的答案。
 */
class ChatIconSelectionTest {

    @Test
    fun `image beats preset`() {
        assertEquals(
            ChatIconSource.IMAGE,
            resolveChatIcon(hasImage = true, hasPreset = true),
        )
    }

    @Test
    fun `preset is used when there is no image`() {
        assertEquals(
            ChatIconSource.PRESET,
            resolveChatIcon(hasImage = false, hasPreset = true),
        )
    }

    @Test
    fun `nothing set uses the default icon`() {
        assertEquals(
            ChatIconSource.DEFAULT,
            resolveChatIcon(hasImage = false, hasPreset = false),
        )
    }

    /**
     * 会话 id 哈希必须是稳定且路径安全的。
     *
     * 渠道会话 id 带冒号与斜杠，直接拼进文件名会写到目录之外；
     * 同一 id 每次算出同一个结果，才不至于每次启动都读不到图标。
     */
    @Test
    fun `chat id hash is stable and path safe`() {
        val id = "channel:lark/chat:123"
        val first = ChatIconStore.hashOf(id)
        assertEquals(first, ChatIconStore.hashOf(id))
        assertEquals(16, first.length)
        assertEquals(true, first.all { it in '0'..'9' || it in 'a'..'f' })
        // 不同 id 必须得到不同文件名，否则两个会话会共用一张图。
        assertEquals(false, first == ChatIconStore.hashOf("channel:lark/chat:124"))
    }

    /** 预设清单里的 key 必须唯一，否则「选中的是哪一项」判断会出错。 */
    @Test
    fun `preset keys are unique`() {
        val keys = ChatIconStore.presets.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}