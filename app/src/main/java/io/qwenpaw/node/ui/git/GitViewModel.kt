package io.qwenpaw.node.ui.git

import io.qwenpaw.node.R
import io.qwenpaw.node.data.model.GitBranchDto
import io.qwenpaw.node.data.model.GitChangeDto
import io.qwenpaw.node.data.model.GitLogEntryDto
import io.qwenpaw.node.data.model.GitStatusDto
import io.qwenpaw.node.data.model.GitCheckoutResultDto
import io.qwenpaw.node.data.model.GitDiscardResultDto
import io.qwenpaw.node.data.model.GitRevertResultDto
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import io.qwenpaw.node.data.terminal.TerminalStore
import io.qwenpaw.node.ui.i18n.Texts
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

/** 待确认的换分支动作。 */
data class GitBranchAction(
    val branch: String,
    val create: Boolean,
)

/** 待确认的丢弃动作。[paths] 始终是展开后的具体文件，避免确认后工作区又变化。 */
data class GitDiscardAction(val paths: List<String>)

/** 待确认的反向提交。 */
data class GitRevertAction(
    val entry: GitLogEntryDto,
    val preview: GitDiffView? = null,
    val previewLoading: Boolean = false,
    val previewFailed: Boolean = false,
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
    val pendingBranch: GitBranchAction? = null,
    val pendingDiscard: GitDiscardAction? = null,
    val pendingRevert: GitRevertAction? = null,
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
            _state.update {
                it.copy(notice = Texts.get(R.string.git_notice_message_required))
            }
            return
        }
        if (_state.value.staged.isEmpty()) {
            _state.update {
                it.copy(notice = Texts.get(R.string.git_notice_nothing_staged))
            }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                withContext(Dispatchers.IO) { api.gitCommit(conn, message) }
                _state.update {
                    it.copy(
                        busy = false,
                        message = "",
                        notice = Texts.get(R.string.git_notice_committed),
                    )
                }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    /** 选择分支后先进入确认，不直接切。 */
    fun requestCheckout(branch: String, create: Boolean) {
        val target = branch.trim()
        if (target.isEmpty() || _state.value.busy) return
        if (!create && target == _state.value.status?.branch) return
        _state.update {
            it.copy(
                pendingBranch = GitBranchAction(target, create),
                error = null,
                notice = null,
            )
        }
    }

    fun dismissCheckout() {
        _state.update { it.copy(pendingBranch = null) }
    }

    fun confirmCheckout() {
        val action = _state.value.pendingBranch ?: return
        val conn = connection() ?: return
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching {
                withContext(Dispatchers.IO) { api.gitCheckout(conn, action.branch, action.create) }
            }.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        pendingBranch = null,
                        notice = Texts.get(
                            if (action.create) {
                                R.string.git_notice_branch_created
                            } else {
                                R.string.git_notice_branch_switched
                            },
                            action.branch,
                        ),
                    )
                }
                load()
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        busy = false,
                        pendingBranch = null,
                        error = ErrorText.of(e),
                    )
                }
            }
        }
    }

    /** 传入空列表表示「全部未暂存改动」；界面仍会把展开后的路径交给确认框。 */
    fun requestDiscard(paths: List<String>) {
        if (_state.value.busy) return
        val targets = paths.ifEmpty {
            _state.value.status?.changes.orEmpty()
                .filter { !it.staged }
                .map { it.path }
                .distinct()
        }
        if (targets.isEmpty()) {
            _state.update { it.copy(notice = Texts.get(R.string.git_notice_nothing_to_discard)) }
            return
        }
        _state.update {
            it.copy(
                pendingDiscard = GitDiscardAction(targets),
                error = null,
                notice = null,
            )
        }
    }

    fun dismissDiscard() {
        _state.update { it.copy(pendingDiscard = null) }
    }

    fun confirmDiscard() {
        val action = _state.value.pendingDiscard ?: return
        val conn = connection() ?: return
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching {
                withContext(Dispatchers.IO) { api.gitDiscard(conn, action.paths) }
            }.onSuccess { result ->
                val count = result.discarded.size.takeIf { it > 0 } ?: action.paths.size
                _state.update {
                    it.copy(
                        busy = false,
                        pendingDiscard = null,
                        notice = Texts.get(R.string.git_notice_discarded, count),
                    )
                }
                load()
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        busy = false,
                        pendingDiscard = null,
                        error = ErrorText.of(e),
                    )
                }
            }
        }
    }

    /** 反向提交前先取该提交的 diff，确认框里能看到实际影响。 */
    fun requestRevert(entry: GitLogEntryDto) {
        val conn = connection() ?: return
        if (_state.value.busy) return
        _state.update {
            it.copy(
                pendingRevert = GitRevertAction(entry = entry, previewLoading = true),
                error = null,
                notice = null,
            )
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.gitCommitDiff(conn, entry.hash) }
            }
            _state.update { current ->
                val pending = current.pendingRevert
                if (pending?.entry?.hash != entry.hash) {
                    current
                } else {
                    current.copy(
                        pendingRevert = pending.copy(
                            preview = result.getOrNull()?.let {
                                diffView(entry.hash.take(8), it.diff)
                            },
                            previewLoading = false,
                            previewFailed = result.isFailure,
                        ),
                    )
                }
            }
        }
    }

    fun dismissRevert() {
        _state.update { it.copy(pendingRevert = null) }
    }

    fun confirmRevert() {
        val action = _state.value.pendingRevert ?: return
        val conn = connection() ?: return
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching {
                withContext(Dispatchers.IO) { api.gitRevert(conn, action.entry.hash) }
            }.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        pendingRevert = null,
                        notice = Texts.get(
                            R.string.git_notice_reverted,
                            action.entry.hash.take(8),
                        ),
                    )
                }
                load()
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        busy = false,
                        pendingRevert = null,
                        error = ErrorText.of(e),
                    )
                }
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
