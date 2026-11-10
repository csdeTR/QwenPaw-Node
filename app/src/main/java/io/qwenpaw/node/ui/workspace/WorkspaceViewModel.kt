package io.qwenpaw.node.ui.workspace

import android.graphics.Bitmap
import io.qwenpaw.node.data.NetworkMonitor
import io.qwenpaw.node.data.model.WorkspaceEntryDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.CursorPage
import io.qwenpaw.node.data.remote.DownloadClient
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.PageLoad
import io.qwenpaw.node.data.remote.PagedList
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.remote.UploadConflictException
import io.qwenpaw.node.data.remote.UploadConflictPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** 工作区的三个根。`param` 直接作为 `root` 查询参数。 */
enum class WorkspaceRoot(val param: String, val title: String) {
    WORKSPACE("workspace", "工作区"),
    PROJECT("project", "项目目录"),
    MEMORY("memory", "记忆"),
}

/** 列表项。`previewKind` 来自服务端，取值为 `directory` / `text` / `binary` / `image`。 */
data class WorkspaceEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val previewKind: String,
    val size: Long?,
    val modifiedAt: String?,
)

sealed interface WorkspacePreview {
    val title: String

    /** 文本预览。`eof=false` 时界面显示「继续加载」。 */
    data class Text(
        override val title: String,
        val content: String = "",
        val nextOffset: Long = 0,
        val eof: Boolean = false,
        val truncated: Boolean = false,
        val etag: String? = null,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
    ) : WorkspacePreview

    /** 图片预览。解码失败时 [bitmap] 为 null 并带 [error]。 */
    data class Image(
        override val title: String,
        val bitmap: Bitmap? = null,
        val loading: Boolean = true,
        val error: String? = null,
    ) : WorkspacePreview

    /** 服务端判定为 `binary` 或其他未知类型，只提供下载。 */
    data class Unsupported(
        override val title: String,
        val size: Long?,
    ) : WorkspacePreview
}

/** 目录枚举出的单个文件。[relative] 是相对下载起点的路径，用于在 SAF 目标里重建层级。 */
data class DirectoryFile(val path: String, val relative: String, val size: Long?)

/** 用户确认下载前展示的计划。 */
sealed interface DownloadPlan {
    val title: String
    val subtitle: String
    val warning: String?

    data class Single(
        val entry: WorkspaceEntry,
        override val warning: String?,
    ) : DownloadPlan {
        override val title: String get() = entry.name
        override val subtitle: String
            get() = entry.size?.let { "单文件，${DownloadPolicy.formatBytes(it)}" } ?: "单文件，大小未知"
    }

    data class Directory(
        val basePath: String,
        val files: List<DirectoryFile>,
        val totalBytes: Long?,
        override val warning: String?,
    ) : DownloadPlan {
        override val title: String get() = "下载目录"
        override val subtitle: String
            get() = buildString {
                append("共 ${files.size} 个文件")
                if (totalBytes != null) append("，合计 ${DownloadPolicy.formatBytes(totalBytes)}")
            }
    }
}

enum class DownloadPhase { RUNNING, DONE, CANCELLED, FAILED }

/** 下载进度。`total` 为 null 表示服务端没给长度，进度条改为不确定态。 */
data class DownloadState(
    val title: String,
    val fileIndex: Int = 1,
    val fileCount: Int = 1,
    val read: Long = 0,
    val total: Long? = null,
    val phase: DownloadPhase = DownloadPhase.RUNNING,
    val message: String? = null,
)

/** 可编辑文本的体积上限（字符数）。超过这个量输入框会明显卡顿，改在服务端编辑。 */
internal const val EDIT_MAX_CHARS = 2 * 1024 * 1024

data class WorkspaceUiState(
    val root: WorkspaceRoot = WorkspaceRoot.WORKSPACE,
    val path: String = "",
    val entries: List<WorkspaceEntry> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val preview: WorkspacePreview? = null,
    val plan: DownloadPlan? = null,
    val preparing: Boolean = false,
    val download: DownloadState? = null,
    val edit: EditState? = null,
    val upload: UploadState? = null,
    val uploadLimitMb: Long? = null,
) {
    /** 记忆根是平铺的 Markdown 列表，没有子目录，也没有下载。 */
    val canDownloadDirectory: Boolean
        get() = root != WorkspaceRoot.MEMORY && preview == null &&
            download?.phase != DownloadPhase.RUNNING &&
            upload?.phase != UploadPhase.RUNNING &&
            upload?.phase != UploadPhase.WAITING_CONFLICT

    /**
     * 只有完整读过一遍的文本文件才能编辑。
     *
     * 服务端没有「按区间写入」，保存的是整篇内容；`eof=false` 时本地只有前几块，
     * 写回去会把文件截断。`etag` 为空同样不放行——没有版本号就无法做乐观锁。
     * 记忆根没有 `etag` 与写入端点，固定只读。
     */
    val canEdit: Boolean
        get() {
            val text = preview as? WorkspacePreview.Text ?: return false
            return root != WorkspaceRoot.MEMORY && text.eof && text.etag != null &&
                text.content.length <= EDIT_MAX_CHARS
        }

    /** 上传只在列表页可用；记忆根没有写入端点。 */
    val canUpload: Boolean
        get() = root != WorkspaceRoot.MEMORY && preview == null && !preparing &&
            download?.phase != DownloadPhase.RUNNING &&
            upload?.phase != UploadPhase.RUNNING &&
            upload?.phase != UploadPhase.WAITING_CONFLICT

    /** 编辑入口不可用时的原因，供界面显示。 */
    val editBlockedReason: String?
        get() {
            val text = preview as? WorkspacePreview.Text ?: return null
            return when {
                root == WorkspaceRoot.MEMORY -> "记忆文件不支持编辑"
                !text.eof -> "文件尚未完整加载，先点「继续加载」"
                text.etag == null -> "未取得版本信息，暂时无法编辑"
                text.content.length > EDIT_MAX_CHARS -> "文件超过 2 MB，请在服务端编辑"
                else -> null
            }
        }
}

/** 编辑态。`savedText` 是服务端已确认的版本，`dirty = draft != savedText`。 */
data class EditState(
    val title: String,
    val rootParam: String,
    val path: String,
    val draft: String,
    val savedText: String,
    val etag: String,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val conflict: Boolean = false,
    val error: String? = null,
) {
    val dirty: Boolean get() = draft != savedText
}

enum class UploadPhase { RUNNING, WAITING_CONFLICT, DONE, CANCELLED, FAILED }

/** 上传进度。`index` / `count` 是当前批次内的序号，冲突重试时只数重试的文件。 */
data class UploadState(
    val index: Int = 0,
    val count: Int = 0,
    val name: String = "",
    val phase: UploadPhase = UploadPhase.RUNNING,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val conflicts: List<String> = emptyList(),
    val message: String? = null,
)

/** 已落到 `cacheDir` 的上传源。界面负责生成，ViewModel 负责上传与删除。 */
data class StagedUpload(val name: String, val file: java.io.File)

/** 单文件落盘入口。由界面用 SAF 实现，ViewModel 不碰 `Context` 与 `Uri`。 */
interface DownloadSink {
    /** 返回 null 表示所选位置不可写。 */
    suspend fun open(): OutputStream?

    /** 取消或失败时删除半成品。 */
    suspend fun discard()
}

/** 目录落盘入口。 */
interface DirectorySink {
    /** 按相对路径创建文件并返回输出流；返回 null 表示该文件无法创建。 */
    suspend fun openFile(relativePath: String): OutputStream?

    /** 取消或失败时删除半成品。 */
    suspend fun discardFile(relativePath: String)
}

/**
 * 工作区文件浏览与下载。
 *
 * 设计取舍：
 * - 预览类型以服务端的 `preview_kind` 为准。实测 `history.db` 的 `file-content`
 *   会返回带 NUL 的字符串，按扩展名猜会把数据库当文本渲染。
 * - 文本按 `next_offset` 续读，不自己加 `limit`：服务端会把偏移落到 UTF-8 边界，
 *   返回长度可能略大于请求值。
 * - 下载目标由界面注入 [DownloadSink] / [DirectorySink]，ViewModel 只负责编排、
 *   进度与取消，因此这些逻辑可以在 JVM 单测里用内存流覆盖。
 */
class WorkspaceViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val downloadClient: DownloadClient,
    private val network: NetworkMonitor? = null,
    private val decodeImage: (ByteArray) -> Bitmap? = ::decodeWorkspaceImage,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private var paged: PagedList<WorkspaceEntry>? = null
    private var generation = 0
    private var previewJob: Job? = null
    private var downloadJob: Job? = null
    private var editJob: Job? = null
    private var uploadJob: Job? = null
    private var limitJob: Job? = null

    /** 冲突挂起时等待用户决定的文件；重试时只发这些。 */
    private var pendingConflicts: List<StagedUpload> = emptyList()

    /** 当前上传的目标目录，与浏览位置解耦：用户中途换目录不改变目标。 */
    private var uploadTarget: Pair<WorkspaceRoot, String>? = null

    /** 本轮上传涉及的临时文件，用于取消与清理。 */
    private var uploadStaged: List<StagedUpload> = emptyList()

    /** 首次进入时自动加载；已经加载过就不重复请求。 */
    fun ensureLoaded() {
        ensureUploadLimit()
        if (_state.value.loaded || _state.value.loading) return
        refresh()
    }

    /**
     * 取一次服务端的上传上限。
     *
     * 值可能从 null 变成具体数字，因此每次进入页面都重新取；失败时保持 null
     * （不拦截上传），错误也不打扰用户——上限只是提示性护栏。
     */
    private fun ensureUploadLimit() {
        if (limitJob?.isActive == true) return
        val conn = connection() ?: return
        limitJob = scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.workspaceUploadLimit(conn) } }
                .onSuccess { limit -> _state.update { it.copy(uploadLimitMb = limit) } }
        }
    }

    /** 手动刷新：清空当前列表重来。 */
    fun refresh() {
        val conn = connection() ?: run {
            _state.update { it.copy(loading = false, loaded = true, error = "尚未连接服务器") }
            return
        }
        val gen = ++generation
        previewJob?.cancel()
        closeEdit()
        _state.update {
            it.copy(
                entries = emptyList(),
                loading = true,
                loadingMore = false,
                loaded = false,
                error = null,
                hasMore = true,
                preview = null,
            )
        }
        if (_state.value.root == WorkspaceRoot.MEMORY) {
            loadMemory(conn, gen)
            return
        }
        val root = _state.value.root
        val path = _state.value.path
        val list = PagedList.ofCursor<WorkspaceEntry> { cursor ->
            val page = withContext(Dispatchers.IO) {
                api.workspaceTree(conn, root.param, path.ifBlank { null }, cursor, PAGE_SIZE)
            }
            CursorPage(page.entries.map { it.toEntry() }, page.hasMore, page.nextCursor)
        }
        paged = list
        scope.launch {
            try {
                val result = list.refresh()
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        entries = list.items,
                        hasMore = result == PageLoad.Appended,
                        loading = false,
                        loaded = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(loading = false, loaded = true, error = workspaceError(e)) }
            }
        }
    }

    private fun loadMemory(conn: Connection, gen: Int) {
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) { api.workspaceMemory(conn) }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        entries = files.map { file ->
                            WorkspaceEntry(
                                name = file.filename,
                                path = file.filename,
                                isDir = false,
                                previewKind = "text",
                                size = file.size,
                                modifiedAt = file.modifiedTime,
                            )
                        },
                        loading = false,
                        loaded = true,
                        hasMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(loading = false, loaded = true, error = workspaceError(e)) }
            }
        }
    }

    /** 触底翻页。记忆根一次性返回，不参与分页。 */
    fun loadMore() {
        val list = paged ?: return
        val current = _state.value
        if (current.root == WorkspaceRoot.MEMORY || !current.hasMore || current.loadingMore) return
        val gen = generation
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val result = list.loadNext()
                if (gen != generation) {
                    _state.update { it.copy(loadingMore = false) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        entries = list.items,
                        hasMore = result == PageLoad.Appended,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) {
                    _state.update { it.copy(loadingMore = false) }
                    return@launch
                }
                _state.update { it.copy(loadingMore = false, error = workspaceError(e)) }
            }
        }
    }

    /** 切换根。记忆根与目录根的状态完全分开，切换即重载。 */
    fun openRoot(root: WorkspaceRoot) {
        if (_state.value.root == root && _state.value.loaded) return
        generation++
        paged = null
        previewJob?.cancel()
        _state.update {
            it.copy(
                root = root,
                path = "",
                entries = emptyList(),
                loading = false,
                loadingMore = false,
                loaded = false,
                hasMore = true,
                error = null,
                preview = null,
                plan = null,
                preparing = false,
            )
        }
        refresh()
    }

    /** 进入子目录。 */
    fun enter(entry: WorkspaceEntry) {
        if (!entry.isDir) return
        _state.update { it.copy(path = entry.path, preview = null) }
        refresh()
    }

    /** 返回上一级。已经在根层时不做任何事，交给系统返回键退出页面。 */
    fun up() {
        val path = _state.value.path
        if (path.isBlank()) return
        val parent = path.substringBeforeLast('/', "")
        _state.update { it.copy(path = parent, preview = null) }
        refresh()
    }

    /** 打开文件。文本与图片进入预览，其余类型只给下载入口。 */
    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDir) return
        closeEdit()
        previewJob?.cancel()
        when (entry.previewKind) {
            "text" -> {
                _state.update { it.copy(preview = WorkspacePreview.Text(entry.name)) }
                loadText(entry, reset = true)
            }

            "image" -> {
                _state.update { it.copy(preview = WorkspacePreview.Image(entry.name)) }
                loadImage(entry)
            }

            else -> _state.update {
                it.copy(preview = WorkspacePreview.Unsupported(entry.name, entry.size))
            }
        }
    }

    /** 关闭预览，回到目录。预览的协程随即取消，避免后台继续读大文件。 */
    fun closePreview() {
        closeEdit()
        previewJob?.cancel()
        previewJob = null
        _state.update { it.copy(preview = null) }
    }

    /** 文本预览「继续加载」。一次追加约 512 KB，直到 EOF。 */
    fun loadMoreContent() {
        val preview = _state.value.preview as? WorkspacePreview.Text ?: return
        if (preview.eof || preview.loadingMore || preview.loading) return
        val entry = findEntry(preview.title) ?: return
        loadText(entry, reset = false)
    }

    private fun loadText(entry: WorkspaceEntry, reset: Boolean) {
        val conn = connection() ?: return
        previewJob?.cancel()
        val gen = generation
        previewJob = scope.launch {
            val current = _state.value.preview as? WorkspacePreview.Text ?: return@launch
            if (reset) {
                _state.update {
                    it.copy(preview = WorkspacePreview.Text(entry.name, loading = true))
                }
            } else {
                _state.update {
                    it.copy(preview = (it.preview as? WorkspacePreview.Text)?.copy(loadingMore = true) ?: it.preview)
                }
            }
            try {
                if (_state.value.root == WorkspaceRoot.MEMORY) {
                    val text = withContext(Dispatchers.IO) { api.workspaceMemoryFile(conn, entry.path) }
                    if (gen != generation) return@launch
                    _state.update {
                        it.copy(
                            preview = WorkspacePreview.Text(
                                title = entry.name,
                                content = text,
                                nextOffset = text.length.toLong(),
                                eof = true,
                                loading = false,
                            ),
                        )
                    }
                    return@launch
                }
                val startOffset = if (reset) 0L else current.nextOffset
                val builder = StringBuilder(if (reset) "" else current.content)
                var offset = startOffset
                var etag = if (reset) null else current.etag
                var eof = false
                var truncated = false
                val target = if (reset) AUTO_PREVIEW_BYTES else offset + CONTINUE_PREVIEW_BYTES
                while (!eof && offset < target) {
                    currentCoroutineContext().ensureActive()
                    val chunk = withContext(Dispatchers.IO) {
                        api.workspaceFileContent(conn, _state.value.root.param, entry.path, offset, CONTENT_CHUNK)
                    }
                    if (gen != generation) return@launch
                    builder.append(chunk.content)
                    etag = chunk.etag ?: etag
                    eof = chunk.eof
                    truncated = chunk.truncated
                    val next = chunk.nextOffset
                    if (next <= offset) {
                        // 服务端没有前进：当作到底，避免死循环重复追加同一段。
                        eof = true
                        break
                    }
                    offset = next
                    _state.update {
                        it.copy(
                            preview = WorkspacePreview.Text(
                                title = entry.name,
                                content = builder.toString(),
                                nextOffset = offset,
                                eof = eof,
                                truncated = truncated,
                                etag = etag,
                                loading = false,
                                loadingMore = !eof,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        preview = (it.preview as? WorkspacePreview.Text)?.copy(
                            loading = false,
                            loadingMore = false,
                            error = workspaceError(e),
                        ) ?: it.preview,
                    )
                }
            }
        }
    }

    private fun loadImage(entry: WorkspaceEntry) {
        val conn = connection() ?: return
        previewJob?.cancel()
        val gen = generation
        previewJob = scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val out = CappedByteArrayOutputStream(IMAGE_MAX_BYTES)
                    downloadClient.download(
                        api.workspaceFileDownloadRequest(conn, _state.value.root.param, entry.path),
                        out,
                    )
                    out.toByteArray()
                }
                val bitmap = withContext(Dispatchers.Default) { decodeImage(bytes) }
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        preview = WorkspacePreview.Image(
                            title = entry.name,
                            bitmap = bitmap,
                            loading = false,
                            error = if (bitmap == null) "无法解码这张图片，可下载后查看" else null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ImageTooLargeException) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        preview = WorkspacePreview.Image(
                            title = entry.name,
                            loading = false,
                            error = "图片超过 20 MB，可下载后查看",
                        ),
                    )
                }
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        preview = (it.preview as? WorkspacePreview.Image)?.copy(
                            loading = false,
                            error = workspaceError(e),
                        ) ?: it.preview,
                    )
                }
            }
        }
    }

    // ---------------- 编辑 ----------------

    /** 进入编辑态。只有 [WorkspaceUiState.canEdit] 为真时生效。 */
    fun beginEdit() {
        val state = _state.value
        if (!state.canEdit) return
        val text = state.preview as? WorkspacePreview.Text ?: return
        val entry = findEntry(text.title) ?: return
        val etag = text.etag ?: return
        editJob?.cancel()
        _state.update {
            it.copy(
                edit = EditState(
                    title = text.title,
                    rootParam = state.root.param,
                    path = entry.path,
                    draft = text.content,
                    savedText = text.content,
                    etag = etag,
                ),
            )
        }
    }

    fun updateDraft(text: String) {
        _state.update { state ->
            val edit = state.edit ?: return@update state
            if (edit.saving) return@update state
            state.copy(edit = edit.copy(draft = text, saved = false))
        }
    }

    /** 保存当前草稿，带 `If-Match`。 */
    fun saveEdit() = saveEditWith(_state.value.edit?.etag)

    /**
     * 强制覆盖：不带 `If-Match`。
     *
     * 服务端在缺少这个头时跳过并发校验，直接覆盖并自动建父目录，因此只需要一次请求。
     */
    fun forceOverwrite() = saveEditWith(null)

    private fun saveEditWith(etag: String?) {
        val edit = _state.value.edit ?: return
        if (edit.saving) return
        val conn = connection() ?: return
        if (isDownloading()) {
            _state.update { it.copy(edit = it.edit?.copy(error = "请等待当前下载结束")) }
            return
        }
        editJob?.cancel()
        _state.update { state ->
            state.edit?.let { state.copy(edit = it.copy(saving = true, error = null, conflict = false)) }
                ?: state
        }
        editJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.workspaceSaveFileContent(conn, edit.rootParam, edit.path, edit.draft, etag)
                }
                _state.update { state ->
                    val current = state.edit ?: return@update state
                    val nextEtag = result.etag.ifBlank { current.etag }
                    state.copy(
                        edit = current.copy(
                            savedText = edit.draft,
                            etag = nextEtag,
                            saving = false,
                            saved = current.draft == edit.draft,
                            conflict = false,
                            error = null,
                        ),
                        preview = savedPreview(state.preview, edit.title, edit.draft, nextEtag),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { state ->
                    val current = state.edit ?: return@update state
                    if (e.code == 409) {
                        state.copy(edit = current.copy(saving = false, conflict = true, error = null))
                    } else {
                        state.copy(edit = current.copy(saving = false, error = workspaceError(e)))
                    }
                }
            } catch (e: Exception) {
                _state.update { state ->
                    state.edit?.let { current ->
                        state.copy(edit = current.copy(saving = false, error = workspaceError(e)))
                    } ?: state
                }
            }
        }
    }

    /** 冲突后重新加载：整文件重读，用服务端内容替换草稿与 etag。 */
    fun reloadAfterConflict() {
        val edit = _state.value.edit ?: return
        val conn = connection() ?: return
        editJob?.cancel()
        _state.update { state ->
            state.edit?.let { state.copy(edit = it.copy(saving = true, conflict = false, error = null)) }
                ?: state
        }
        editJob = scope.launch {
            try {
                val (text, etag) = withContext(Dispatchers.IO) {
                    readWholeText(conn, edit.rootParam, edit.path)
                }
                if (etag.isNullOrBlank()) throw IOException("未取得版本信息，无法继续编辑")
                _state.update { state ->
                    val current = state.edit ?: return@update state
                    state.copy(
                        edit = current.copy(
                            draft = text,
                            savedText = text,
                            etag = etag,
                            saving = false,
                            saved = false,
                            conflict = false,
                            error = null,
                        ),
                        preview = savedPreview(state.preview, edit.title, text, etag),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { state ->
                    val current = state.edit ?: return@update state
                    state.copy(
                        edit = current.copy(
                            saving = false,
                            conflict = false,
                            error = workspaceError(e),
                        ),
                    )
                }
            }
        }
    }

    /** 放弃草稿。界面对未保存改动先做一次确认。 */
    fun cancelEdit() = closeEdit()

    fun dismissConflict() {
        _state.update { state ->
            state.edit?.let { state.copy(edit = it.copy(conflict = false)) } ?: state
        }
    }

    private fun closeEdit() {
        editJob?.cancel()
        editJob = null
        if (_state.value.edit != null) _state.update { it.copy(edit = null) }
    }

    /** 读到 `eof` 为止，用来在冲突后取回整篇内容。 */
    private suspend fun readWholeText(
        conn: Connection,
        rootParam: String,
        path: String,
    ): Pair<String, String?> {
        val builder = StringBuilder()
        var offset = 0L
        var etag: String? = null
        while (true) {
            val chunk = api.workspaceFileContent(conn, rootParam, path, offset, CONTENT_CHUNK)
            builder.append(chunk.content)
            etag = chunk.etag ?: etag
            if (chunk.eof) break
            val next = chunk.nextOffset
            if (next <= offset) break
            offset = next
        }
        return builder.toString() to etag
    }

    private fun savedPreview(
        preview: WorkspacePreview?,
        title: String,
        content: String,
        etag: String,
    ): WorkspacePreview? {
        val text = preview as? WorkspacePreview.Text ?: return preview
        if (text.title != title) return preview
        return text.copy(
            content = content,
            nextOffset = content.toByteArray(Charsets.UTF_8).size.toLong(),
            eof = true,
            truncated = false,
            etag = etag,
            loading = false,
            loadingMore = false,
            error = null,
        )
    }

    // ---------------- 上传 ----------------

    /** 只做前置校验；选择器与暂存由界面负责。 */
    fun beginUpload() {
        if (!_state.value.canUpload || isDownloading()) return
        _state.update { it.copy(upload = null, error = null) }
    }

    /** 界面把选中文件落到 `cacheDir` 后调用。 */
    fun upload(staged: List<StagedUpload>) {
        if (staged.isEmpty()) return
        if (uploadJob?.isActive == true || isDownloading()) {
            deleteStaged(staged)
            return
        }
        val state = _state.value
        val target = state.root to state.path
        uploadTarget = target
        uploadStaged = staged
        pendingConflicts = emptyList()
        val initial = UploadState(count = staged.size)
        _state.update { it.copy(upload = initial, error = null) }
        uploadJob = scope.launch {
            runUpload(target.first, target.second, staged, conflict = null, initial = initial)
        }
    }

    /** 用户在冲突框里选了覆盖或跳过：只重发冲突文件。 */
    fun resolveUploadConflict(policy: UploadConflictPolicy) {
        val pending = pendingConflicts
        if (pending.isEmpty()) return
        val target = uploadTarget ?: return
        val previous = _state.value.upload ?: return
        // 挂起时上一批的协程可能还在收尾（状态已到 WAITING_CONFLICT），不能因此拒绝重试。
        if (previous.phase != UploadPhase.WAITING_CONFLICT && uploadJob?.isActive == true) return
        uploadJob?.cancel()
        pendingConflicts = emptyList()
        uploadStaged = pending
        val initial = previous.copy(
            index = 0,
            count = pending.size,
            conflicts = emptyList(),
            phase = UploadPhase.RUNNING,
            message = null,
        )
        _state.update { it.copy(upload = initial) }
        uploadJob = scope.launch {
            runUpload(target.first, target.second, pending, policy, initial)
        }
    }

    fun cancelUpload() {
        val phase = _state.value.upload?.phase
        if (phase == UploadPhase.WAITING_CONFLICT) {
            deleteStaged(pendingConflicts)
            pendingConflicts = emptyList()
            uploadStaged = emptyList()
            uploadTarget = null
            _state.update { state ->
                state.upload?.let {
                    state.copy(upload = it.copy(phase = UploadPhase.CANCELLED, message = "上传已取消"))
                } ?: state
            }
            return
        }
        uploadJob?.cancel()
    }

    /** 关闭完成/取消/失败的卡片。运行中与等待冲突时不能直接关闭。 */
    fun dismissUpload() {
        val phase = _state.value.upload?.phase ?: return
        if (phase == UploadPhase.RUNNING || phase == UploadPhase.WAITING_CONFLICT) return
        _state.update { it.copy(upload = null) }
    }

    private suspend fun runUpload(
        root: WorkspaceRoot,
        targetPath: String,
        files: List<StagedUpload>,
        conflict: UploadConflictPolicy?,
        initial: UploadState,
    ) {
        val conn = connection()
        if (conn == null) {
            deleteStaged(files)
            _state.update { state ->
                state.upload?.let {
                    state.copy(upload = it.copy(phase = UploadPhase.FAILED, message = "尚未连接服务器"))
                } ?: state
            }
            return
        }
        val limit = _state.value.uploadLimitMb
        var succeeded = initial.succeeded
        var failed = initial.failed
        var skipped = initial.skipped
        var lastError: String? = null
        val conflicts = mutableListOf<StagedUpload>()
        var cancelled = false
        try {
            files.forEachIndexed { i, staged ->
                currentCoroutineContext().ensureActive()
                updateUpload {
                    it.copy(
                        index = i + 1,
                        count = files.size,
                        name = staged.name,
                        phase = UploadPhase.RUNNING,
                    )
                }
                if (exceedsLimit(staged.file.length(), limit)) {
                    skipped++
                    updateUpload { it.copy(succeeded = succeeded, failed = failed, skipped = skipped) }
                    return@forEachIndexed
                }
                try {
                    val result = withContext(Dispatchers.IO) {
                        api.workspaceFileUpload(
                            conn, root.param, targetPath, staged.file, staged.name, conflict,
                        )
                    }
                    if (result.files.firstOrNull()?.status == "skipped") skipped++ else succeeded++
                } catch (e: UploadConflictException) {
                    conflicts += staged
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    lastError = if (e is ApiException && e.code == 404) {
                        "目标目录不存在，可能已被删除"
                    } else {
                        workspaceError(e)
                    }
                }
                updateUpload { it.copy(succeeded = succeeded, failed = failed, skipped = skipped) }
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            // 先删临时文件再发终态：调用方看到 DONE / CANCELLED 时，cacheDir 已经干净。
            withContext(NonCancellable) {
                deleteStaged(files.filter { file -> conflicts.none { it === file } })
                if (cancelled) {
                    updateUpload { it.copy(phase = UploadPhase.CANCELLED, message = "上传已取消") }
                }
            }
        }
        if (conflicts.isNotEmpty()) {
            pendingConflicts = conflicts
            uploadStaged = conflicts
            updateUpload {
                it.copy(
                    phase = UploadPhase.WAITING_CONFLICT,
                    conflicts = conflicts.map { file -> file.name },
                    message = "有 ${conflicts.size} 个文件已存在",
                )
            }
            return
        }
        finishUpload(root, targetPath, succeeded, failed, skipped, lastError)
    }

    private fun finishUpload(
        root: WorkspaceRoot,
        targetPath: String,
        succeeded: Int,
        failed: Int,
        skipped: Int,
        lastError: String?,
    ) {
        val message = buildString {
            append("已上传 $succeeded 个文件")
            if (failed > 0) append("，失败 $failed 个")
            if (skipped > 0) append("，跳过 $skipped 个")
            if (succeeded == 0 && failed > 0 && lastError != null) append("：$lastError")
        }
        val phase = if (succeeded == 0 && skipped == 0 && failed > 0) {
            UploadPhase.FAILED
        } else {
            UploadPhase.DONE
        }
        updateUpload {
            it.copy(
                phase = phase,
                succeeded = succeeded,
                failed = failed,
                skipped = skipped,
                message = message,
            )
        }
        uploadStaged = emptyList()
        uploadTarget = null
        // 用户可能已换到别的目录；只有仍停在目标目录时才刷新列表。
        if (root == _state.value.root && targetPath == _state.value.path) refresh()
    }

    private fun isUploading(): Boolean =
        uploadJob?.isActive == true ||
            _state.value.upload?.phase == UploadPhase.RUNNING ||
            _state.value.upload?.phase == UploadPhase.WAITING_CONFLICT

    private inline fun updateUpload(crossinline block: (UploadState) -> UploadState) {
        _state.update { state ->
            state.upload?.let { state.copy(upload = block(it)) } ?: state
        }
    }

    private fun deleteStaged(files: List<StagedUpload>) {
        files.forEach { runCatching { it.file.delete() } }
    }

    // ---------------- 下载 ----------------

    /** 单文件下载：先出确认卡，体积超过 100 MB 或网络计费时带提示。 */
    fun requestDownload(entry: WorkspaceEntry) {
        if (entry.isDir || isDownloading() || isUploading() || _state.value.preparing) return
        val warning = DownloadPolicy.warning(
            sizeBytes = entry.size,
            fileCount = null,
            metered = isMetered(),
            vpn = isVpn(),
        )
        _state.update { it.copy(plan = DownloadPlan.Single(entry, warning)) }
    }

    /**
     * 目录下载：先递归枚举，再出确认卡。
     *
     * 枚举只取元数据，不读文件内容；体积合计用于触发 100 MB 提示。
     * 超过 [MAX_DIRECTORY_FILES] 个文件时拒绝开始，避免一次下载把设备拖死。
     */
    fun requestDirectoryDownload() {
        val conn = connection() ?: return
        if (isDownloading() || isUploading() || _state.value.preparing) return
        val root = _state.value.root
        val basePath = _state.value.path
        if (root == WorkspaceRoot.MEMORY) return
        _state.update { it.copy(preparing = true, error = null) }
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) { collectFiles(conn, root.param, basePath) }
                if (files.isEmpty()) {
                    _state.update { it.copy(preparing = false, error = "这个目录里没有可下载的文件") }
                    return@launch
                }
                val total = files.mapNotNull { it.size }.takeIf { it.size == files.size }?.sum()
                val warning = DownloadPolicy.warning(
                    sizeBytes = total,
                    fileCount = files.size,
                    metered = isMetered(),
                    vpn = isVpn(),
                )
                _state.update {
                    it.copy(
                        preparing = false,
                        plan = DownloadPlan.Directory(basePath, files, total, warning),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(preparing = false, error = workspaceError(e)) }
            }
        }
    }

    fun cancelPlan() = _state.update { it.copy(plan = null) }

    /** 用户在 SAF 选择器返回后调用；消费当前计划并开始传输。 */
    fun startDownload(sink: DownloadSink) {
        val plan = _state.value.plan as? DownloadPlan.Single ?: return
        _state.update { it.copy(plan = null) }
        downloadJob = scope.launch { runSingleDownload(plan, sink) }
    }

    fun startDirectoryDownload(sink: DirectorySink) {
        val plan = _state.value.plan as? DownloadPlan.Directory ?: return
        _state.update { it.copy(plan = null) }
        downloadJob = scope.launch { runDirectoryDownload(plan, sink) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** 关闭完成/失败/取消的卡片。运行中的下载只能取消，不能直接关闭。 */
    fun dismissDownload() {
        if (_state.value.download?.phase == DownloadPhase.RUNNING) return
        _state.update { it.copy(download = null) }
    }

    private fun isDownloading(): Boolean =
        _state.value.download?.phase == DownloadPhase.RUNNING || downloadJob?.isActive == true

    private suspend fun runSingleDownload(plan: DownloadPlan.Single, sink: DownloadSink) {
        val conn = connection() ?: return
        val gen = generation
        _state.update {
            it.copy(
                download = DownloadState(
                    title = plan.entry.name,
                    fileCount = 1,
                    fileIndex = 1,
                    total = plan.entry.size,
                ),
            )
        }
        val out = try {
            sink.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateDownload(gen) {
                it.copy(
                    phase = DownloadPhase.FAILED,
                    message = "无法写入所选位置：${workspaceError(e)}",
                )
            }
            return
        }
        if (out == null) {
            updateDownload(gen) {
                it.copy(phase = DownloadPhase.FAILED, message = "无法写入所选位置，请换一个目录")
            }
            return
        }
        try {
            out.use { stream ->
                downloadClient.download(
                    api.workspaceFileDownloadRequest(conn, _state.value.root.param, plan.entry.path),
                    stream,
                ) { read, total ->
                    updateDownload(gen) {
                        it.copy(read = read, total = total ?: it.total)
                    }
                }
            }
            updateDownload(gen) {
                it.copy(phase = DownloadPhase.DONE, message = "已保存到所选位置")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { sink.discard() } }
            updateDownload(gen) {
                it.copy(phase = DownloadPhase.CANCELLED, message = "下载已取消")
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { sink.discard() } }
            updateDownload(gen) {
                it.copy(phase = DownloadPhase.FAILED, message = workspaceError(e))
            }
        }
    }

    private suspend fun runDirectoryDownload(plan: DownloadPlan.Directory, sink: DirectorySink) {
        val conn = connection() ?: return
        val gen = generation
        var doneBytes = 0L
        var failures = 0
        var index = 0
        _state.update {
            it.copy(
                download = DownloadState(
                    title = "下载目录",
                    fileIndex = 0,
                    fileCount = plan.files.size,
                    total = plan.totalBytes,
                ),
            )
        }
        for (file in plan.files) {
            currentCoroutineContext().ensureActive()
            index++
            val out = try {
                sink.openFile(file.relative)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (out == null) {
                failures++
                continue
            }
            try {
                val written = out.use { stream ->
                    downloadClient.download(
                        api.workspaceFileDownloadRequest(conn, _state.value.root.param, file.path),
                        stream,
                    ) { read, total ->
                        updateDownload(gen) {
                            it.copy(
                                fileIndex = index,
                                read = doneBytes + read,
                                total = plan.totalBytes ?: total,
                            )
                        }
                    }
                }
                doneBytes += written
                updateDownload(gen) { it.copy(fileIndex = index, read = doneBytes) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { sink.discardFile(file.relative) } }
                updateDownload(gen) {
                    it.copy(phase = DownloadPhase.CANCELLED, message = "下载已取消，已完成的文件会保留")
                }
                return
            } catch (_: Exception) {
                failures++
                withContext(NonCancellable) { runCatching { sink.discardFile(file.relative) } }
            }
        }
        updateDownload(gen) {
            it.copy(
                phase = DownloadPhase.DONE,
                fileIndex = plan.files.size,
                message = if (failures == 0) {
                    "已保存 ${plan.files.size} 个文件"
                } else {
                    "已完成 ${plan.files.size - failures} 个，失败 $failures 个"
                },
            )
        }
    }

    /**
     * 递归枚举目录。只取元数据；`has_more` 为真但 `next_cursor` 没前进时停止，
     * 避免服务端异常导致死循环。
     */
    private suspend fun collectFiles(
        conn: Connection,
        root: String,
        basePath: String,
    ): List<DirectoryFile> {
        val result = mutableListOf<DirectoryFile>()
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<String>().apply { add(basePath) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            if (!visited.add(dir)) continue
            var cursor: String? = null
            while (true) {
                val page = api.workspaceTree(conn, root, dir.ifBlank { null }, cursor, PAGE_SIZE)
                page.entries.forEach { entry ->
                    when {
                        entry.kind == "directory" -> stack.add(entry.path)
                        entry.kind == "file" -> {
                            if (result.size >= MAX_DIRECTORY_FILES) {
                                throw IOException("目录内文件过多，请分批下载")
                            }
                            result += DirectoryFile(
                                path = entry.path,
                                relative = relativePath(basePath, entry.path),
                                size = entry.size,
                            )
                        }
                    }
                }
                val next = page.nextCursor
                if (!page.hasMore || next == null || next == cursor) break
                cursor = next
            }
        }
        return result
    }

    private fun WorkspaceEntryDto.toEntry() = WorkspaceEntry(
        name = name,
        path = path,
        isDir = kind == "directory",
        previewKind = previewKind.orEmpty(),
        size = size,
        modifiedAt = modifiedAt,
    )

    private fun findEntry(title: String): WorkspaceEntry? =
        _state.value.entries.firstOrNull { it.name == title && !it.isDir }

    private fun isMetered(): Boolean = network?.isMetered() ?: false

    private fun isVpn(): Boolean = network?.current?.value?.kind == NetworkMonitor.Snapshot.KIND_VPN

    private inline fun updateDownload(gen: Int, crossinline block: (DownloadState) -> DownloadState) {
        if (gen != generation) return
        _state.update { state ->
            val download = state.download ?: return@update state
            state.copy(download = block(download))
        }
    }

    private fun workspaceError(e: Throwable): String = when {
        e is ApiException && e.code == 404 -> "文件或目录不存在，可能已被删除"
        e is ApiException && e.code == 403 ->
            e.detail?.takeIf { it.isNotBlank() } ?: "没有权限访问这个位置"
        e is ApiException && e.code == 400 ->
            e.detail?.takeIf { it.isNotBlank() } ?: "请求参数无效"
        else -> ErrorText.of(e)
    }

    /** 换实例或换智能体时清空：工作区归属上一个智能体，留着会误导。 */
    fun clear() {
        generation++
        paged = null
        previewJob?.cancel()
        downloadJob?.cancel()
        editJob?.cancel()
        uploadJob?.cancel()
        limitJob?.cancel()
        previewJob = null
        downloadJob = null
        editJob = null
        uploadJob = null
        limitJob = null
        deleteStaged(uploadStaged + pendingConflicts)
        uploadStaged = emptyList()
        pendingConflicts = emptyList()
        uploadTarget = null
        _state.value = WorkspaceUiState()
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val CONTENT_CHUNK = 64 * 1024
        const val AUTO_PREVIEW_BYTES = 1024 * 1024L
        const val CONTINUE_PREVIEW_BYTES = 512 * 1024L
        const val IMAGE_MAX_BYTES = 20 * 1024 * 1024
        const val MAX_DIRECTORY_FILES = 5000

        /** `basePath` 为空时保持原路径；否则去掉前缀，供 SAF 目标重建层级。 */
        fun relativePath(basePath: String, path: String): String {
            if (basePath.isBlank()) return path
            val prefix = "$basePath/"
            return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
        }
    }
}
