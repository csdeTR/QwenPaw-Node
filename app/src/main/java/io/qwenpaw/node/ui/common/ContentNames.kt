package io.qwenpaw.node.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.qwenpaw.node.data.remote.UploadNames

/** 读取系统文件选择器给出的显示名；查询失败或没有该列时返回 null。 */
internal fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

/**
 * 上传时使用的文件名：显示名优先，uri 末段兜底，缺扩展名时按 MIME 补。
 *
 * 附件上传与工作区上传共用这一处，避免两边的命名规则慢慢分叉。
 */
internal fun uploadFileName(context: Context, uri: Uri): String {
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    return UploadNames.resolve(
        displayName = queryDisplayName(context, uri),
        uriLastSegment = uri.lastPathSegment,
        mime = mime,
    )
}
