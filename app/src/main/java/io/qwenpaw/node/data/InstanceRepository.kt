package io.qwenpaw.node.data

import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.local.InstanceStore
import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 多实例仓库：负责实例的增删改查、当前激活实例以及登录。
 *
 * 所有网络操作都在 IO 线程执行，token 只保存在加密偏好与内存配置中。
 */
class InstanceRepository(
    private val store: InstanceStore,
    private val api: QwenPawApi,
) {
    private val _instances = MutableStateFlow(store.load())
    val instances: StateFlow<List<InstanceConfig>> = _instances.asStateFlow()

    private val _activeId = MutableStateFlow(store.activeId())
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun active(): InstanceConfig? =
        _instances.value.firstOrNull { it.id == _activeId.value } ?: _instances.value.firstOrNull()

    fun connection(instance: InstanceConfig? = active()): Connection? =
        instance?.let { Connection(it.baseUrl, it.token, it.agentId) }

    /** 只探测地址是否可用并列出智能体，不写盘。连接页选智能体时用。 */
    suspend fun probeAgents(
        baseUrl: String,
        username: String?,
        password: String?,
    ): List<AgentDto> {
        val normalized = normalizeBaseUrl(baseUrl)
        val status = api.authStatus(normalized)
        var token: String? = null
        if (status.enabled && status.hasUsers) {
            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                throw ApiException(401, "该实例已开启登录鉴权，需要用户名和密码")
            }
            token = api.login(normalized, username, password, permanent = true).token
        }
        return api.agents(Connection(normalized, token, "default"))
    }

    /**
     * 新增或更新一个实例。
     *
     * 鉴权说明：QwenPaw 默认只监听 127.0.0.1 且本地请求免鉴权；
     * 当 `--host 0.0.0.0` 暴露到局域网且开启了 Web 登录时，才需要用户名密码。
     */
    suspend fun connect(
        name: String,
        baseUrl: String,
        username: String?,
        password: String?,
        agentId: String,
        existingId: String? = null,
    ): InstanceConfig {
        val normalized = normalizeBaseUrl(baseUrl)
        val status = api.authStatus(normalized)
        var token: String? = null
        var resolvedUser: String? = null

        if (status.enabled && status.hasUsers) {
            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                throw ApiException(401, "该实例已开启登录鉴权，需要用户名和密码")
            }
            val auth = api.login(normalized, username, password, permanent = true)
            token = auth.token
            resolvedUser = auth.username ?: username
        }

        val version = runCatching { api.version(normalized).version }.getOrNull()
        val conn = Connection(normalized, token, agentId)
        // 校验 token 与 agent 是否可用，顺便拿到智能体列表。
        val agents = api.agents(conn)
        val resolvedAgent = when {
            agents.any { it.id == agentId } -> agentId
            agents.isNotEmpty() -> agents.first().id
            else -> agentId
        }

        val config = InstanceConfig(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name.ifBlank { normalized },
            baseUrl = normalized,
            agentId = resolvedAgent,
            serverVersion = version,
            token = token,
            username = resolvedUser,
        )
        val list = _instances.value.toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        if (index >= 0) list[index] = config else list += config
        persist(list)
        setActive(config.id)
        return config
    }

    fun setActive(id: String) {
        _activeId.value = id
        store.setActiveId(id)
    }

    fun remove(id: String) {
        val list = _instances.value.filterNot { it.id == id }
        persist(list)
        if (_activeId.value == id) {
            setActive(list.firstOrNull()?.id.orEmpty())
        }
    }

    fun updateAgent(instanceId: String, agentId: String) {
        val list = _instances.value.map {
            if (it.id == instanceId) it.copy(agentId = agentId) else it
        }
        persist(list)
    }

    /** 就地修改一条实例配置，改完如果是当前实例需要重新连接。 */
    fun update(instance: InstanceConfig) {
        val list = _instances.value.map { if (it.id == instance.id) instance else it }
        persist(list)
    }

    /** 退出登录：清掉 token，但仍保留实例条目方便再次登录。 */
    fun logout(id: String) {
        val list = _instances.value.map {
            if (it.id == id) it.copy(token = null, username = null) else it
        }
        persist(list)
    }

    private fun persist(list: List<InstanceConfig>) {
        _instances.value = list
        store.save(list)
    }

    companion object {
        /**
         * 用户可能只填 `192.168.1.10:8088`，补齐协议并去掉尾部斜杠。
         *
         * 中文输入法会把 `.` `:` `/` 打成全角（`。` `：` `/`），地址看起来是对的却解析失败，
         * 这里统一折成半角再处理。
         */
        fun normalizeBaseUrl(raw: String): String {
            val ascii = raw
                .replace('。', '.')
                .replace('：', ':')
                .replace('／', '/')
                .replace('　', ' ')
            val trimmed = ascii.trim().trimEnd('/')
            if (trimmed.isEmpty()) throw IllegalArgumentException("请填写服务器地址")
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val url = QwenPawApi.resolve(withScheme, "/")
            return "${url.scheme}://${url.host}:${if (url.port == -1) 80 else url.port}"
        }
    }
}
