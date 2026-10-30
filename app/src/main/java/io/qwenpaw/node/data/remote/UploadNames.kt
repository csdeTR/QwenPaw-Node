package io.qwenpaw.node.data.remote

/**
 * 上传文件名的补全规则。
 *
 * 服务端 `/api/console/upload` 会把收到的文件名原样用作保存名，返回的路径
 * 也带着它。系统相册给出的 uri 末段形如 `..._1000000158`，没有扩展名；
 * 若直接拿它上传，客户端随后按扩展名判断媒体类型（`isImageName`）就会判否，
 * 图片被当作普通文件发出——服务端只收到 `file` 块，模型看不到图像。
 *
 * 因此上传前必须保证文件名带扩展名：优先用 ContentResolver 给的显示名，
 * 没有就按 MIME 补。
 */
internal object UploadNames {

    /** MIME → 扩展名。只覆盖常见媒体，其余交给服务端按原样处理。 */
    private val EXT_BY_MIME = mapOf(
        "image/png" to ".png",
        "image/jpeg" to ".jpg",
        "image/webp" to ".webp",
        "image/gif" to ".gif",
        "image/bmp" to ".bmp",
        "image/heic" to ".heic",
        "video/mp4" to ".mp4",
        "application/pdf" to ".pdf",
    )

    /**
     * 计算最终上传文件名。
     *
     * @param displayName ContentResolver 查到的真实显示名，可能为 null
     * @param uriLastSegment uri 的末段，作为显示名缺失时的兜底
     * @param mime ContentResolver 给出的 MIME
     */
    fun resolve(displayName: String?, uriLastSegment: String?, mime: String?): String {
        val base = displayName?.takeIf { it.isNotBlank() }
            ?: uriLastSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "upload"
        // 已有扩展名就不再动，尊重用户原始文件名。
        if (base.substringAfterLast('.', "").isNotBlank()) return base
        return base + extensionFor(mime)
    }

    fun extensionFor(mime: String?): String =
        EXT_BY_MIME[mime?.trim()?.lowercase()] ?: ""
}