package io.qwenpaw.node.ui.chat

/** 用户头像最终显示的来源。 */
enum class UserAvatarSource {
    /** 用户在本机选的图片，存在 `filesDir/avatars/user.*`。 */
    LOCAL,

    /** QwenPaw 服务端网页端设置的头像，缓存到 `filesDir/server-avatar/`。 */
    SERVER,

    /** 两者都没有（或服务端头像被关掉）时回落成昵称首字。 */
    INITIAL,
}

/**
 * 决定用户头像显示哪一张。
 *
 * 本地优先于服务端：本机选的图片是用户在这台设备上的主动选择，
 * 而服务端那张是网页端的设置，两者冲突时以眼前这台设备为准。
 * 开关只作用于服务端来源——关掉它不该连带禁用本机头像，
 * 否则「关闭服务端头像」这个动作的含义会变成「清空我的头像」。
 */
fun resolveUserAvatar(
    hasLocal: Boolean,
    serverEnabled: Boolean,
    hasServerImage: Boolean,
): UserAvatarSource = when {
    hasLocal -> UserAvatarSource.LOCAL
    serverEnabled && hasServerImage -> UserAvatarSource.SERVER
    else -> UserAvatarSource.INITIAL
}