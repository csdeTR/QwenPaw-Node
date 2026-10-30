package io.qwenpaw.node.ui.chat

/**
 * 发送时要在本地补的用户侧占位文字。
 *
 * 图片附件有自己的缩略图气泡，不需要占位；非图片附件没有可渲染的
 * 缩略图，若不补一行文字，用户会看到聊天页顶部直接是「处理中」，
 * 以为消息没发出去。
 *
 * 早期版本的判据是「文本为空且附件为空」，但该条件在 `send()` 里已被
 * 提前 `return` 排除，这段分支永远执行不到——这正是「只发文件不发文字时
 * 本地没有任何用户气泡」的成因。
 *
 * @param attachments 已上传的服务端路径
 * @param text 用户输入的正文
 * @param isImage 判断文件名是否为图片，由调用方注入以便单测
 */
internal fun filePlaceholders(
    attachments: List<String>,
    text: String,
    isImage: (String) -> Boolean,
): List<String> {
    if (text.isNotBlank()) return emptyList()
    if (attachments.isEmpty()) return listOf("[附件]")

    val files = attachments.filterNot { isImage(it.substringAfterLast('/')) }
    // 只有图片时，图片自身已经是可见气泡，不再补文字。
    return files.map { "[文件] ${it.substringAfterLast('/')}" }
}