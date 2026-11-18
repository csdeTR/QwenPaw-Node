package io.qwenpaw.node.data.terminal

import android.content.Context
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 终端的本地持久化。
 *
 * `group`、默认创建方式与字号都不是秘密，用普通 SharedPreferences 即可；SSH 直连凭据
 * 在 [SshProfileStore] 的加密存储里。
 */
class TerminalStore(context: Context) {

    private val prefs = context.getSharedPreferences("qwenpaw_terminal", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun group(instanceId: String, agentId: String): String {
        val key = key("group", instanceId, agentId)
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(key, created).apply()
        return created
    }

    fun source(instanceId: String, agentId: String): TerminalSource? {
        val raw = prefs.getString(key("source", instanceId, agentId), null) ?: return null
        // 0.7.0 以前的版本把经插件的 SSH 存成 SSH；现在改名为 SSH_RELAY。
        if (raw == "SSH") return TerminalSource.SSH_RELAY
        return runCatching { TerminalSource.valueOf(raw) }.getOrNull()
    }

    fun setSource(instanceId: String, agentId: String, source: TerminalSource) {
        prefs.edit().putString(key("source", instanceId, agentId), source.name).apply()
    }

    fun defaultCreation(instanceId: String, agentId: String): TerminalCreation? {
        val raw = prefs.getString(key("default", instanceId, agentId), null) ?: return null
        return runCatching { json.decodeFromString<TerminalCreation>(raw) }.getOrNull()
    }

    fun setDefaultCreation(instanceId: String, agentId: String, creation: TerminalCreation?) {
        val prefKey = key("default", instanceId, agentId)
        prefs.edit().apply {
            if (creation == null) remove(prefKey)
            else putString(prefKey, json.encodeToString(TerminalCreation.serializer(), creation))
        }.apply()
    }

    /** 字号按实例保存；默认 13sp，范围 9–24sp。 */
    fun fontSize(instanceId: String): Int =
        prefs.getInt(key("font", instanceId, ""), DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    fun setFontSize(instanceId: String, sizeSp: Int) {
        prefs.edit()
            .putInt(key("font", instanceId, ""), sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
            .apply()
    }

    fun lastTerminal(group: String): String? =
        prefs.getString(key("last", group, ""), null)

    fun setLastTerminal(group: String, terminalId: String?) {
        prefs.edit().apply {
            if (terminalId == null) remove(key("last", group, ""))
            else putString(key("last", group, ""), terminalId)
        }.apply()
    }

    /** Git 页首次说明是否已经确认；按实例 + 智能体记住。 */
    fun gitNoticeAcknowledged(instanceId: String, agentId: String): Boolean =
        prefs.getBoolean(key("git_notice", instanceId, agentId), false)

    fun acknowledgeGitNotice(instanceId: String, agentId: String) {
        prefs.edit().putBoolean(key("git_notice", instanceId, agentId), true).apply()
    }

    private fun key(prefix: String, first: String, second: String): String =
        "$prefix|$first|$second"

    companion object {
        const val DEFAULT_FONT_SIZE = 13
        const val MIN_FONT_SIZE = 9
        const val MAX_FONT_SIZE = 24
    }
}
