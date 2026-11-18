package io.qwenpaw.node.ui.git

import io.qwenpaw.node.data.model.GitBranchDto
import io.qwenpaw.node.data.model.GitChangeDto
import io.qwenpaw.node.data.model.GitLogEntryDto
import io.qwenpaw.node.data.model.GitStatusDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.terminal.TerminalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GitTab { CHANGES, HISTORY }

data class GitDiffView(
    val title: String,
    val text: String,
    val truncated: Boolean,
)

data class GitUiState(
    val loading: Boolean = true,
    val acknowledged: Boolean = false,
    val status: GitStatusDto? = null,
    val log: List<GitLogEntryDto> = emptyList(),
    val branches: List<GitBranchDto> = emptyList(),
    val diff: GitDiffView? = null,
    val tab: GitTab = GitTab.CHANGES,
    val message: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val staged: List<GitChangeDto>
        get() = status?.changes.orEmpty().filter { it.staged }

    val unstaged: List<GitChangeDto>
        get() = status?.changes.orEmpty().filter { !it.staged && it.status != "?" }

    val untracked: List<GitChangeDto>
        get() = status?.changes.orEmpty().filter { !it.staged && it.status == "?" }
}

/**
 * Git 提交链路。
 *
 * 只做「看清楚改了什么，然后提交」：状态、diff、暂存、提交、历史。
 * 换分支、丢弃改动与反向提交都不在本版。
 */
class GitViewModel(
    private val api: QwenPawApi,
    private val connection: () -> Connection?,
    private val instanceId: () -> String?,
    private val store: TerminalStore,
) {
    private val _state = MutableStateFlow(GitUiState())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    fun open() {
        val conn = connection() ?: return
        val inst = instanceId() ?: return
        val acknowledged = store.gitNoticeAcknowledged(inst, conn.agentId)
        _state.update { it.copy(acknowledged = acknowledged, loading = false) }
        if (acknowledged) load()
    }

    /** 用户确认首次说明后才调 status：该端点可能会初始化仓库并做一次初始提交。 */
    fun acknowledgeNotice() {
        val conn = connection() ?: return
        val inst = instanceId() ?: return
        store.acknowledgeGitNotice(inst, conn.agentId)
        _state.update { it.copy(acknowledged = true) }
        load()
    }

    fun load() {
        val conn = connection() ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val (status, log, branches) = withContext(Dispatchers.IO) {
                    Triple(
                        api.gitStatus(conn),
                        api.gitLog(conn),
                        api.gitBranches(conn),
                    )
                }
                _state.update {
                    it.copy(
                        loading = false,
                        status = status,
                        log = log,
                        branches = branches,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = ErrorText.of(e))
                }
            }
        }
    }

    fun showDiff(change: GitChangeDto) {
        val conn = connection() ?: return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    api.gitDiff(
                        conn,
                        path = change.path,
                        staged = change.staged,
                        untracked = change.status == "?",
                    )
                }
                _state.update {
                    it.copy(
                        busy = false,
                        diff = diffView(change.path, result.diff),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun showCommitDiff(hash: String) {
        val conn = connection() ?: return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    api.gitCommitDiff(conn, hash)
                }
                _state.update {
                    it.copy(
                        busy = false,
                        diff = diffView(hash.take(8), result.diff),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun stage(paths: List<String>) {
        val conn = connection() ?: return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                withContext(Dispatchers.IO) { api.gitStage(conn, paths) }
                _state.update { it.copy(busy = false) }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun unstage(paths: List<String>) {
        val conn = connection() ?: return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                withContext(Dispatchers.IO) { api.gitUnstage(conn, paths) }
                _state.update { it.copy(busy = false) }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun commit() {
        val conn = connection() ?: return
        val message = _state.value.message.trim()
        if (message.isEmpty()) {
            _state.update { it.copy(notice = "提交信息不能为空") }
            return
        }
        if (_state.value.staged.isEmpty()) {
            _state.update { it.copy(notice = "没有已暂存的改动") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                withContext(Dispatchers.IO) { api.gitCommit(conn, message) }
                _state.update {
                    it.copy(busy = false, message = "", notice = "已提交")
                }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun updateMessage(value: String) {
        _state.update { it.copy(message = value) }
    }

    fun selectTab(tab: GitTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun dismissDiff() {
        _state.update { it.copy(diff = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clear() {
        loadJob?.cancel()
        _state.value = GitUiState(loading = false)
    }

    private fun diffView(title: String, text: String): GitDiffView {
        val lines = text.lineSequence().toList()
        val truncatedLines = lines.size > MAX_DIFF_LINES
        var body = lines.take(MAX_DIFF_LINES).joinToString("\n")
        val truncatedChars = body.length > MAX_DIFF_CHARS
        if (truncatedChars) body = body.take(MAX_DIFF_CHARS)
        return GitDiffView(
            title = title,
            text = body,
            truncated = truncatedLines || truncatedChars,
        )
    }

    companion object {
        const val MAX_DIFF_LINES = 2000
        const val MAX_DIFF_CHARS = 200_000
    }
}
