package io.qwenpaw.node.ui.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * SAF 落盘。
 *
 * 下载目标由系统文档选择器授权，应用只拿到一个 Uri，因此不需要
 * `WRITE_EXTERNAL_STORAGE`，也不在私有目录留副本。ViewModel 只认识
 * [DownloadSink] / [DirectorySink] 两个接口，这里负责把它们落到真实文件上。
 */

/** 单文件：`ACTION_CREATE_DOCUMENT` 返回的 Uri。 */
class SingleFileSink(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : DownloadSink {

    override suspend fun open(): OutputStream? = withContext(Dispatchers.IO) {
        // "wt" 会截断已有内容；个别提供器不支持该模式，退回默认写模式。
        runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(uri) }.getOrNull()
    }

    override suspend fun discard() = withContext(Dispatchers.IO) {
        val removed = runCatching {
            DocumentsContract.deleteDocument(resolver, uri)
        }.getOrDefault(false)
        if (!removed) runCatching { resolver.delete(uri, null, null) }
        Unit
    }
}

/**
 * 目录：`ACTION_OPEN_DOCUMENT_TREE` 返回的树 Uri。
 *
 * 需要 [Context] 而不是 `ContentResolver`：`DocumentFile.fromTreeUri` 只接受
 * Context，所有子节点的创建与查找都由它代理。
 */
class TreeSink(
    context: Context,
    treeUri: Uri,
) : DirectorySink {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val root: DocumentFile? = DocumentFile.fromTreeUri(context.applicationContext, treeUri)

    /** 已创建的目录，按相对路径缓存，避免每个文件都从根重走一遍。 */
    private val dirs = mutableMapOf("" to root)

    override suspend fun openFile(relativePath: String): OutputStream? =
        withContext(Dispatchers.IO) {
            val segments = segments(relativePath)
            if (segments.isEmpty()) return@withContext null
            val parent = resolveDir(segments.dropLast(1)) ?: return@withContext null
            val name = segments.last()
            // 已存在同名节点时先删除：否则提供器会生成「name (1)」，目录结构就错了。
            parent.findFile(name)?.delete()
            val file = parent.createFile(MIME, name) ?: return@withContext null
            runCatching { resolver.openOutputStream(file.uri, "wt") }.getOrNull()
                ?: runCatching { resolver.openOutputStream(file.uri) }.getOrNull()
        }

    override suspend fun discardFile(relativePath: String) = withContext(Dispatchers.IO) {
        val segments = segments(relativePath)
        if (segments.isNotEmpty()) {
            resolveDir(segments.dropLast(1))?.findFile(segments.last())?.delete()
        }
        Unit
    }

    /** 逐级取或建目录；任一层建不出来就返回 null，由调用方计为失败。 */
    private fun resolveDir(segments: List<String>): DocumentFile? {
        var current = root ?: return null
        val key = StringBuilder()
        for (segment in segments) {
            if (key.isNotEmpty()) key.append('/')
            key.append(segment)
            val cached = dirs[key.toString()]
            if (cached != null) {
                current = cached
                continue
            }
            val next = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: current.createDirectory(segment)
                ?: return null
            dirs[key.toString()] = next
            current = next
        }
        return current
    }

    private companion object {
        /**
         * 统一用二进制流创建文件。
         *
         * 若按扩展名猜 MIME，部分提供器会按 MIME 追加后缀，把 `README.md`
         * 变成 `README.md.txt`；用通用二进制类型可以避开这条推断。
         */
        const val MIME = "application/octet-stream"

        /** 丢弃空段与 `.` / `..`，防止越出所选目录。 */
        fun segments(relativePath: String): List<String> =
            relativePath.split('/', '\\')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
    }
}
