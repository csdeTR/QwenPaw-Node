package io.qwenpaw.node.ui.workspace

import android.content.Context
import android.net.Uri
import io.qwenpaw.node.ui.common.uploadFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 暂存结果：能上传的放 [staged]，超过上限或读不到的记在 [skipped]。 */
data class StageResult(
    val staged: List<StagedUpload>,
    val skipped: List<String>,
)

/**
 * 把选择器的 Uri 落到 `cacheDir/workspace-upload/`。
 *
 * 不申请存储权限，也不长期持有 URI：复制完成后上传只用本地临时文件，
 * 上传结束（成功、失败、取消）由 ViewModel 删除。
 *
 * 单个文件读不到只跳过它，不影响其余文件。
 */
internal suspend fun stageWorkspaceUploads(
    context: Context,
    uris: List<Uri>,
    limitMb: Long?,
): StageResult = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "workspace-upload").apply { mkdirs() }
    val staged = mutableListOf<StagedUpload>()
    val skipped = mutableListOf<String>()
    uris.forEach { uri ->
        val name = sanitizeUploadName(uploadFileName(context, uri))
        // 能直接拿到体积就先判一次，避免把超限文件整份复制进 cacheDir。
        val declared = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (exceedsLimit(declared, limitMb)) {
            skipped += name
            return@forEach
        }
        val target = File.createTempFile("upload-", ".tmp", dir)
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: throw IOException("无法读取所选文件")
            // 拿不到声明体积时以复制后的实际大小为准。
            if (exceedsLimit(target.length(), limitMb)) {
                target.delete()
                false
            } else {
                true
            }
        }.getOrDefault(false)
        if (ok) staged += StagedUpload(name, target) else skipped += name
    }
    StageResult(staged, skipped)
}

/**
 * 只取文件名最后一段，去掉路径分隔符。
 *
 * 显示名来自外部应用，直接拼进 `cacheDir` 有目录穿越风险；
 * 这里只保留最后一段并把空名兜底为 `upload`。
 */
internal fun sanitizeUploadName(raw: String): String {
    val last = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    return last.ifBlank { "upload" }.take(120)
}

/** 是否超过服务端给出的上限。上限为 null、或体积未知（≤ 0）时不拦截。 */
internal fun exceedsLimit(size: Long, limitMb: Long?): Boolean {
    if (limitMb == null || size <= 0) return false
    return size > limitMb * 1024 * 1024
}
