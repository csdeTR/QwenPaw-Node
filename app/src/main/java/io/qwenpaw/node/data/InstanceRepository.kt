package io.qwenpaw.node.data

import io.qwenpaw.node.data.local.AddressEntry
import io.qwenpaw.node.data.local.InstanceConfig
import io.qwenpaw.node.data.local.InstanceStore
import io.qwenpaw.node.data.model.AgentDto
import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.BasicAuth
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.ErrorText
import io.qwenpaw.node.data.remote.QwenPawApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 一次地址验证的结果。
 *
 * [baseUrl] 是**实际命中**的地址，可能不是列表里的第一个——这正是回退生效的证据。
 */
data class VerifyResult(
    val baseUrl: String,
    val version: String,
    val needsLogin: Boolean,
    /**
     * 该地址被反向代理的 HTTP Basic 拦住了。
     *
     * 这不是「验证失败」：地址是通的，只是缺一层凭据。界面应当就地展开
     * 「反向代理验证」让它填，而不是把用户送回填地址那一步。
     */
    val proxyAuthRequired: Boolean = false,
    /**
     * 同一地址上配套插件（QwenPaw Node Bridge）的探测结论。
     *
     * 验证阶段还没有登录 token，因此开启了登录鉴权的服务端会返回 401，
     * 此时记为 [io.qwenpaw.node.data.plugin.BridgeStatus.Unknown]，
     * 不能据此判断「没有安装」。
     */
    val bridge: io.qwenpaw.node.data.plugin.BridgeStatus =
        io.qwenpaw.node.data.plugin.BridgeStatus.Unknown,
)

/**
 * 托管平台上的 QwenPaw 实例。这类实例不能经本客户端连接。
 *
 * 两种都是平台的演示环境：连接走平台自己的鉴权与转发，客户端按「自建实例」的方式
 * 直连只会得到一堆看不懂的 401/404。与其让用户反复试，不如在填地址这一步就说明白。
 */
internal enum class HostedPlatform(
    /** 匹配用的主机名；子域名一并拦截。 */
    val host: String,
    /** 给用户看的原因与建议。 */
    val message: String,
) {
    AGENTSCOPE(
        host = "qwenpaw.platform.agentscope.io",
        message = "QwenPaw Node无法添加源于AgentScope Platform的实例，请使用私有化部署方案",
    ),
    MODELSCOPE(
        host = "modelscope.cn",
        message = "QwenPaw Node无法添加通过魔搭创空间创建的实例，请使用私有化部署方案",
    ),
}

/**
 * 判断主机是否属于某个托管平台。
 *
 * 子域名一并命中：`xxx.modelscope.cn` 同样是创空间实例。
 */
internal fun hostedPlatformOf(host: String): HostedPlatform? {
    val h = host.trim().lowercase().removeSuffix(".")
    if (h.isEmpty()) return null
    return HostedPlatform.entries.firstOrNull { h == it.host || h.endsWith("." + it.host) }
}

/**
 * 多实例仓库：负责实例的增删改查、当前激活实例以及登录。
 *
 * 所有网络操作都在 IO 线程执行，token 只保存在加密偏好与内存配置中。
 */
class InstanceRepository(
    private val store: InstanceStore,
    private val api: QwenPawApi,
    private val network: NetworkMonitor? = null,
) {
    private val _instances = MutableStateFlow(store.load())
    val instances: StateFlow<List<InstanceConfig>> = _instances.asStateFlow()

    private val _activeId = MutableStateFlow(store.activeId())
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun active(): InstanceConfig? =
        _instances.value.firstOrNull { it.id == _activeId.value } ?: _instances.value.firstOrNull()

    /**
     * 本次运行中每个实例实际连通的地址。
     *
     * **只存在内存里，不落盘。** 用户明确要求「不记忆」，即下次启动仍按设定顺序尝试；
     * 但同一次运行里已经探明的问题地址没必要每发一条消息就再试一遍，
     * 所以这里留一个运行期缓存。
     *
     * **按网络类型分桶**（键形如 `id@vpn`）。不分的后果很具体：在家连上局域网后
     * 缓存记下 `192.168.x.x`，出门 VPN 上线时缓存仍指向那个地址，
     * 于是每条请求都要先等一遍连接超时才回退——这正是「备用地址不生效」的观感来源。
     * 网络一变，桶就换，缓存自然失效。
     */
    private val runtimeHit = mutableMapOf<String, String>()

    /**
     * 本次运行手动指定的地址。键为实例 id，**不落盘**，进程结束即失效。
     *
     * 与 [InstanceConfig.preferredBaseUrl] 是两件事：那个是用户长期偏好，要写进配置；
     * 这个是「我现在就想走这条」，只影响本次打开。
     */
    private val sessionPick = mutableMapOf<String, String>()

    private fun bucketKey(instanceId: String): String =
        instanceId + "@" + (network?.current?.value?.kind ?: "other")

    /**
     * 解析出一次请求应当使用的地址顺序。
     *
     * 优先级：
     * 1. 本次运行手动指定的地址（[sessionPick]，仅本次打开有效）
     * 2. 当前网络命中某条地址的 `preferOn`（例如「检测到 VPN 时用 EasyTier」）
     * 3. 用户长期指定的 [InstanceConfig.preferredBaseUrl]
     * 4. 本次运行在该网络下已探明的可用地址
     * 5. 用户设定的原始顺序
     *
     * 前四种都只是**提到最前**，不去重也不排他：指定地址坏了仍然能回退，
     * 否则一次手动选择会让实例在地址故障时彻底不可用。
     */
    fun resolveOrder(instance: InstanceConfig): List<String> =
        resolveOrderPure(
            addresses = instance.addresses.map { it.url },
            // 手动指定必须排第一：用户点了「本次使用」，却仍走别的地址，等于没生效。
            sessionPick = sessionPick[instance.id],
            preferUrl = network?.let { monitor ->
                val kind = monitor.current.value.kind
                val ssid = monitor.current.value.ssid
                instance.addresses.firstOrNull { it.preferOn?.matches(kind, ssid) == true }?.url
            },
            preferredBaseUrl = instance.preferredBaseUrl,
            runtimeHit = runtimeHit[bucketKey(instance.id)],
        )

    /** 本次运行手动指定的地址；未指定时为 null。 */
    fun sessionAddress(instanceId: String): String? = sessionPick[instanceId]

    /**
     * 服务端是否要求登录。
     *
     * 判定式与 [probeAgents]、[connect] 保持一致：`enabled && has_users`。
     * 只开 `enabled` 却没有用户时，登录接口无从登录，按「不需要登录」处理。
     */
    suspend fun authRequired(baseUrl: String): Boolean {
        val normalized = runCatching { normalizeBaseUrl(baseUrl) }.getOrNull() ?: return false
        val status = runCatching { api.authStatus(normalized) }.getOrNull() ?: return false
        return loginRequired(status)
    }

    /**
     * 用这组账号试登录一次，只回答「能不能登录」。
     *
     * 返回 null 表示通过，非 null 是给界面显示的原因。
     *
     * 引导页第 2 步在点「下一步」时调用：先前要等到第 3 步读智能体才登录，
     * 账号写错的人会在第 3 步看到一个与账号无关的报错，还要退回去重填。
     *
     * 会顺带吊销这次登录换来的 token：token 由第 4 步的 [connect] 重新申请，
     * 这里只做校验，不该在服务端留下一批用不到的凭据。
     */
    suspend fun checkLogin(
        baseUrl: String,
        username: String,
        password: String,
        basicAuth: BasicAuth? = null,
    ): String? {
        val normalized = runCatching { normalizeBaseUrl(baseUrl) }.getOrNull()
            ?: return "地址格式不正确"
        return runCatching {
            val status = api.authStatus(normalized, basicAuth)
            if (!loginRequired(status)) return@runCatching
            val token = api.login(
                normalized,
                username,
                password,
                permanent = false,
                basicAuth = basicAuth,
            ).token
            // 吊销失败不影响结论：账号确实是对的，留下一个会话 token 也不算错误。
            runCatching { api.revokeToken(Connection(normalized, token, "default", basicAuth), token) }
        }.exceptionOrNull()?.let { ErrorText.of(it) }
    }

    /**
     * 指定本次运行使用的地址。传 null 或列表外的地址都表示恢复自动。
     *
     * 不落盘：用户要的是「仅在本次打开生效」。
     */
    fun chooseSessionAddress(instanceId: String, baseUrl: String?) {
        val instance = _instances.value.firstOrNull { it.id == instanceId } ?: return
        val valid = baseUrl?.takeIf { url ->
            url.isNotBlank() && instance.addresses.any { it.url == url }
        }
        if (valid == null) sessionPick.remove(instanceId) else sessionPick[instanceId] = valid
    }

    /** 当前实例的可用地址：取解析顺序的第一个。 */
    fun resolvedBaseUrl(instance: InstanceConfig): String =
        resolveOrder(instance).firstOrNull() ?: instance.primaryBaseUrl

    /**
     * 按解析顺序探测，返回第一个可达的地址；全部不可达时返回 null。
     *
     * 为什么需要它：`resolvedBaseUrl` 只是「按优先级排序后的第一个」，
     * 并不保证可达。冷启动（或重装）时运行期缓存是空的，若用户把局域网地址排在
     * 第一位而此刻在外网，界面会直接报连接失败——备用地址形同不存在。
     *
     * 用 [QwenPawApi.probeClient] 的短超时，全部失败最坏也就每个地址 8 秒。
     */
    suspend fun probeReachable(instance: InstanceConfig): String? {
        val order = resolveOrder(instance)
        if (order.isEmpty()) return null
        val hit = runCatching {
            AddressFallback.firstReachable(order) { url ->
                api.versionWith(url, QwenPawApi.probeClient(), instance.basicAuth)
            }
        }.getOrNull() ?: return null
        runtimeHit[bucketKey(instance.id)] = hit.first
        return hit.first
    }

    /** 当前网络类型，供界面显示「当前网络：VPN」。 */
    fun networkKind(): String = network?.current?.value?.kind ?: NetworkMonitor.Snapshot.KIND_OTHER

    fun networkSsid(): String? = network?.current?.value?.ssid

    fun connection(instance: InstanceConfig? = active()): Connection? =
        instance?.let {
            Connection(resolvedBaseUrl(it), it.token, it.agentId, it.basicAuth)
        }

    /**
     * 按地址顺序尝试，命中的地址原样返回。
     *
     * 只对**连接类**失败换地址：HTTP 业务错误（4xx/5xx）说明地址是通的，
     * 换下一个地址既解决不了问题，还会掩盖真实原因。
     *
     * 成功地址**不改写列表顺序**。用户把局域网地址放在前面是有意的，
     * 隧道地址可能更慢；安静地调整顺序会让下次连接变慢且难以解释。
     */
    suspend fun <T> withFallback(
        baseUrls: List<String>,
        token: String?,
        agentId: String,
        /** 实例 id 用于记录本次运行命中的地址；不需要记忆时传 null。 */
        instanceId: String? = null,
        /** 反代凭据；非空时随每个候选地址一起带上。 */
        basicAuth: BasicAuth? = null,
        /** 每次尝试前回调，用于展示「正在验证 1/2」。 */
        onAttempt: (index: Int, total: Int, url: String) -> Unit = { _, _, _ -> },
        block: suspend (Connection) -> T,
    ): Pair<Connection, T> {
        val (url, result) = AddressFallback.firstReachable(baseUrls, onAttempt) { url ->
            block(Connection(url, token, agentId, basicAuth))
        }
        if (instanceId != null) runtimeHit[bucketKey(instanceId)] = url
        return Connection(url, token, agentId, basicAuth) to result
    }

    /**
     * 轻量验证一组地址是否可用。
     *
     * 只发 **一个** `GET /api/version`，并用专用的 [QwenPawApi.probeClient]：
     * 主客户端的读超时是 10 分钟，遇上「连上但对端不回包」（隧道或 VPN 半开连接）
     * 会让用户干等十几秒甚至更久。探测只需要回答「这个地址现在通不通」。
     *
     * 拿到版本后顺带查一次 `GET /api/auth/status`：那个端点是公开的，成本很低，
     * 却能让界面在验证阶段就提示「下一步要填账号」，而不是等用户点了提交才被 401 拦住。
     *
     * 传 [instanceId] 时，命中的地址会写进运行期缓存——否则用户在验证里看到
     * 「已回退到 X」，随后真正连接时仍要从列表第一条重新试起。
     */
    suspend fun verify(
        baseUrls: List<String>,
        onAttempt: (index: Int, total: Int, url: String) -> Unit = { _, _, _ -> },
        instanceId: String? = null,
        /** 反代凭据。给了就能验证到「反代之后」的真实状态。 */
        basicAuth: BasicAuth? = null,
        /**
         * 已保存实例的 token。
         *
         * 开着登录的服务端只有带 token 才能读到配套插件版本；为 null 时
         * 插件探测会得到 401，结果记「未能确认」。
         */
        token: String? = null,
    ): VerifyResult {
        val normalized = normalize(baseUrls)
        // 反代拦下时不能按「地址不可达」处理：地址是通的，换下一个地址
        // 同样会被拦。因此这里先单独探一次，把「缺凭据」与「连不上」分开。
        val (conn, result) = try {
            withFallback(
                baseUrls = normalized,
                token = token,
                agentId = "default",
                instanceId = instanceId,
                basicAuth = basicAuth,
                onAttempt = onAttempt,
            ) { probe -> probeVersion(probe) }
        } catch (e: ApiException) {
            if (e.code == 401 &&
                ErrorText.classify401(e.detail ?: e.message, e.wwwAuthenticate) ==
                ErrorText.AuthFailure.PROXY_BASIC
            ) {
                // 已经填了反代凭据却仍被拦，说明这组账号密码不对。
                // 此时「需要填写反向代理账号与密码」是错的——用户已经填了，
                // 要说的是「填的不对」，否则他会去检查一个本来就填对了的字段。
                if (basicAuth != null) {
                    throw ApiException(
                        code = 401,
                        message = e.message,
                        userHint = "反向代理拒绝了这组账号密码，请核对后重试",
                        detail = e.detail,
                        wwwAuthenticate = e.wwwAuthenticate,
                        tokenSent = false,
                    )
                }
                // 没填凭据：这不是「连不上」，也不需要换地址（同一台机器的反代配置相同）。
                // 返回一个专门的标记，界面据此就地展开凭据输入，而不是报「验证失败」。
                return VerifyResult(
                    baseUrl = normalized.first(),
                    version = "",
                    needsLogin = false,
                    proxyAuthRequired = true,
                )
            }
            throw e
        }
        return result.copy(baseUrl = conn.baseUrl)
    }

    /** 用短超时探一个地址的版本与登录状态。 */
    private suspend fun probeVersion(probe: Connection): VerifyResult {
        val version = api.versionWith(
            probe.baseUrl,
            QwenPawApi.probeClient(),
            probe.basicAuth,
        ).version
        // 鉴权状态查不到不算失败：验证的核心结论是「地址通不通」，
        // 拿不到 status 只影响「这一步之后是否要填账号」的预判。
        val needsLogin = runCatching {
            loginRequired(api.authStatus(probe.baseUrl, probe.basicAuth))
        }.getOrDefault(false)
        // 配套插件顺带探一次：用户在同一屏就能确认「服务端通了、插件装没装」。
        // 带 token 时能读到真实版本；新增实例这一阶段还没有 token，401 只能记为「待确认」。
        val bridge = runCatching {
            api.pluginBridgeInfo(probe)?.let {
                io.qwenpaw.node.data.plugin.BridgeStatus.Ready(it)
            } ?: io.qwenpaw.node.data.plugin.BridgeStatus.Absent
        }.getOrElse { e ->
            if (e is ApiException && (e.code == 401 || e.code == 403)) {
                io.qwenpaw.node.data.plugin.BridgeStatus.Unknown
            } else {
                io.qwenpaw.node.data.plugin.BridgeStatus.Absent
            }
        }
        return VerifyResult(
            baseUrl = probe.baseUrl,
            version = version,
            needsLogin = needsLogin,
            bridge = bridge,
        )
    }

    /** 只探测地址是否可用并列出智能体，不写盘。连接页选智能体时用。 */
    suspend fun probeAgents(
        baseUrls: List<String>,
        username: String?,
        password: String?,
        basicAuth: BasicAuth? = null,
    ): List<AgentDto> {
        val normalized = normalize(baseUrls)
        val (_, agents) = withFallback(normalized, null, "default", basicAuth = basicAuth) { probe ->
            val status = api.authStatus(probe.baseUrl, probe.basicAuth)
            var token: String? = null
            if (loginRequired(status)) {
                if (username.isNullOrBlank() || password.isNullOrBlank()) {
                    // userHint 必须一起给：只写 message 的话，[ErrorText.of] 会落到
                    // 401 的默认分支，把「还没填账号」说成「登录已失效」。
                    val msg = "该实例已开启登录鉴权，需要用户名和密码"
                    throw ApiException(401, msg, msg, tokenSent = false)
                }
                token = api.login(
                    probe.baseUrl,
                    username,
                    password,
                    permanent = true,
                    basicAuth = probe.basicAuth,
                ).token
            }
            api.agents(Connection(probe.baseUrl, token, "default", probe.basicAuth))
        }
        return agents
    }

    /**
     * 用已保存实例的凭据列智能体。
     *
     * 实例若已有 token 就直接复用；没有 token 但服务端开着登录时，
     * 拿不到凭据就只能靠调用方处理（这里会抛出 401）。
     */
    suspend fun agentsFor(instance: InstanceConfig): List<AgentDto> {
        val (_, agents) = withFallback(
            resolveOrder(instance),
            instance.token,
            instance.agentId,
            instanceId = instance.id,
            basicAuth = instance.basicAuth,
        ) { conn ->
            api.agents(conn)
        }
        return agents
    }

    /**
     * 新增或更新一个实例。
     *
     * 鉴权说明：QwenPaw 默认只监听 127.0.0.1 且本地请求免鉴权；
     * 当 `--host 0.0.0.0` 暴露到局域网且开启了 Web 登录时，才需要用户名密码。
     *
     * [baseUrls] 按用户设定的顺序保存，命中的地址不会因此被提到前面。
     */
    suspend fun connect(
        name: String,
        addresses: List<AddressEntry>,
        username: String?,
        password: String?,
        agentId: String,
        existingId: String? = null,
        /** 反向代理的 HTTP Basic 凭据；两个字段都非空才带。 */
        basicAuth: BasicAuth? = null,
    ): InstanceConfig {
        val entries = normalizeEntries(addresses)
        val normalized = entries.map { it.url }
        var resolvedToken: String? = null
        var resolvedUser: String? = null
        var version: String? = null

        val (hit, agents) = withFallback(normalized, null, agentId, basicAuth = basicAuth) { probe ->
            val status = api.authStatus(probe.baseUrl, probe.basicAuth)
            if (loginRequired(status)) {
                if (username.isNullOrBlank() || password.isNullOrBlank()) {
                    // 与 probeAgents 同一处理：userHint 里放同一句，否则会被映射成
                    // 「登录已失效」——用户此时根本还没登录过。
                    val msg = "该实例已开启登录鉴权，需要用户名和密码"
                    throw ApiException(401, msg, msg, tokenSent = false)
                }
                val auth = api.login(
                    probe.baseUrl,
                    username,
                    password,
                    permanent = true,
                    basicAuth = probe.basicAuth,
                )
                resolvedToken = auth.token
                resolvedUser = auth.username ?: username
            }
            version = runCatching {
                api.version(probe.baseUrl, probe.basicAuth).version
            }.getOrNull()
            // 校验 token 与 agent 是否可用，顺便拿到智能体列表。
            api.agents(Connection(probe.baseUrl, resolvedToken, agentId, probe.basicAuth))
        }
        val resolvedAgent = when {
            agents.any { it.id == agentId } -> agentId
            agents.isNotEmpty() -> agents.first().id
            else -> agentId
        }

        val config = InstanceConfig(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name.ifBlank { hit.baseUrl },
            addresses = entries,
            agentId = resolvedAgent,
            serverVersion = version,
            token = resolvedToken,
            username = resolvedUser,
            basicUser = basicAuth?.user,
            basicPassword = basicAuth?.password,
        )
        val list = _instances.value.toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        // 更新已有实例时保留用户已经设好的别名与网络偏好：连接流程不认识这些字段，
        // 若整体覆盖，用户每次重连都会丢失别名。
        val merged = if (index >= 0) {
            val old = list[index]
            config.copy(
                addresses = config.addresses.map { fresh ->
                    val previous = old.addresses.firstOrNull { it.url == fresh.url }
                    // 本次表单里填的别名/偏好优先，没填才回落到旧值。
                    fresh.copy(
                        alias = fresh.alias?.takeIf { it.isNotBlank() } ?: previous?.alias,
                        preferOn = fresh.preferOn ?: previous?.preferOn,
                    )
                },
                preferredBaseUrl = old.preferredBaseUrl?.takeIf { it in config.addresses.map { a -> a.url } },
            )
        } else {
            config
        }
        if (index >= 0) list[index] = merged else list += merged
        // 记住本次运行实际命中的地址（仅内存），后续请求直接用这条，避免每条消息都重试坏地址。
        runtimeHit[bucketKey(merged.id)] = hit.baseUrl
        persist(list)
        setActive(merged.id)
        return merged
    }

    /**
     * 规范化地址列表：折全角、补协议、去重，并保持用户设定顺序。
     *
     * 去重放在规范化之后：`192.168.1.10:8088` 与 `http://192.168.1.10:8088`
     * 是同一个地址，只是写法不同。
     */
    internal fun normalize(baseUrls: List<String>): List<String> {
        val cleaned = baseUrls.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) throw IllegalArgumentException("请填写服务器地址")
        return cleaned.map { normalizeBaseUrl(it) }.distinct()
    }

    /**
     * 规范化带元信息的地址列表。
     *
     * 与 [normalize] 同样是「折全角、补协议、去重」，但保留别名与网络偏好。
     * 去重时以首次出现的条目为准：用户复制粘贴两遍同一个地址时，
     * 保留先写的那份别名，后一份只是重复。
     */
    internal fun normalizeEntries(entries: List<AddressEntry>): List<AddressEntry> =
        normalizeEntriesPure(entries, ::normalizeBaseUrl)

    fun setActive(id: String) {
        _activeId.value = id
        store.setActiveId(id)
    }

    fun remove(id: String) {
        val list = _instances.value.filterNot { it.id == id }
        // 命中缓存按 `id@网络类型` 分桶，删除实例要把该实例的所有桶一起清掉。
        runtimeHit.keys.filter { it.startsWith("$id@") }.forEach { runtimeHit.remove(it) }
        sessionPick.remove(id)
        persist(list)
        if (_activeId.value == id) {
            setActive(list.firstOrNull()?.id.orEmpty())
        }
    }

    /** 手动指定该实例优先使用的地址；传 null 或空串恢复自动顺序。 */
    fun chooseAddress(instanceId: String, baseUrl: String?) {
        val list = _instances.value.map { instance ->
            if (instance.id != instanceId) {
                instance
            } else {
                val valid = baseUrl?.takeIf { url ->
                    url.isNotBlank() && instance.addresses.any { it.url == url }
                }
                instance.copy(preferredBaseUrl = valid)
            }
        }
        persist(list)
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
         * 该服务端是否要求登录。
         *
         * 判据是 `enabled && has_users`，四处判定必须一致（验证预判、引导页第 2 步、
         * 探测智能体、真正连接）。只开 `enabled` 却没有用户时登录接口无从登录，
         * 按「不需要登录」处理；漏掉 `hasUsers` 会让人卡在一个填不了账号的页面。
         */
        internal fun loginRequired(status: io.qwenpaw.node.data.model.AuthStatusDto): Boolean =
            status.enabled && status.hasUsers

        /**
         * [resolveOrder] 的纯函数形态。
         *
         * 抽出来是为了让五级优先级能在 JVM 单测里验证：真实 `resolveOrder` 依赖
         * 网络监听与运行期缓存，那些都属于设备行为。这里只关心「谁排第一」。
         */
        internal fun resolveOrderPure(
            addresses: List<String>,
            sessionPick: String? = null,
            preferUrl: String? = null,
            preferredBaseUrl: String? = null,
            runtimeHit: String? = null,
        ): List<String> {
            val all = addresses.filter { it.isNotBlank() }
            if (all.isEmpty()) return emptyList()
            val preferred = listOfNotNull(
                sessionPick?.takeIf { it.isNotBlank() && it in all },
                preferUrl?.takeIf { it.isNotBlank() && it in all },
                preferredBaseUrl?.takeIf { it.isNotBlank() && it in all },
                runtimeHit?.takeIf { it.isNotBlank() && it in all },
            )
            // 只提到最前，不排他：指定地址坏了仍然能回退。
            return (preferred + all).distinct()
        }

        /**
         * 用户可能只填 `192.168.1.10:8088` 或 `chat.example.com`，补齐协议并去掉尾部斜杠。
         *
         * 中文输入法会把 `.` `:` `/` 打成全角（`。` `：` `/`），地址看起来是对的却解析失败，
         * 这里统一折成半角再处理。
         *
         * **默认协议按主机判断，不再一律 `http://`。** 公网域名多数只开 https，
         * 把 `chat.example.com` 补成 `http://` 会被反代 301 到 https；
         * OkHttp 跟随 301 时会把 POST 改写成 GET 并丢掉请求体，
         * `POST /api/auth/login` 于是变成 `GET /api/auth/login` 并返回 404。
         * 用户看到的却是「会话不存在或已被删除」，与真实原因完全对不上。
         */
        fun normalizeBaseUrl(raw: String): String {
            val ascii = raw
                .replace('。', '.')
                .replace('：', ':')
                .replace('／', '/')
                .replace('　', ' ')
            val trimmed = ascii.trim().trimEnd('/')
            if (trimmed.isEmpty()) throw IllegalArgumentException("请填写服务器地址")
            val host = hostOf(trimmed)
            // 托管平台的实例不能按自建实例直连：验证与连接都要先过这一关。
            hostedPlatformOf(host)?.let { throw IllegalArgumentException(it.message) }
            val withScheme = if (trimmed.contains("://")) {
                trimmed
            } else {
                defaultSchemeFor(host) + "://" + trimmed
            }
            val url = QwenPawApi.resolve(withScheme, "/")
            // 端口回填必须按协议取默认值：https 未写端口时回填 80 会得到
            // `https://example.com:80`，请求直接连不上。
            val defaultPort = if (url.scheme == "https") 443 else 80
            return "${url.scheme}://${url.host}:${if (url.port == -1) defaultPort else url.port}"
        }

        /**
         * 用户没写协议时该用哪个。
         *
         * 规则：内网地址用 http（它们本来就是明文直连），其余一律 https。
         * 显式写了协议的地址不会走到这里——用户的意思优先。
         */
        internal fun defaultSchemeFor(host: String): String {
            val h = host.trim().lowercase().removeSurrounding("[", "]")
            if (h.isEmpty()) return "http"
            if (isLocalHost(h)) return "http"
            return "https"
        }

        /**
         * 这个主机名是不是「不会被公网访问到」。
         *
         * 三个来源合并成一处，是因为安全提示与协议默认值必须用同一套判据：
         * 两边各写一份，一处加了网段另一处没加，就会出现「按 http 连内网、
         * 同时又提示它暴露在广域网」这种自相矛盾的界面。
         *
         * 覆盖范围：localhost、回环地址、私有 IPv4 段、链路本地段、CGNAT
         * （easytier / tailscale 常用），以及 `.local` `.lan` `.internal` `.home`
         * 这类只在内网解析的后缀。空串按内网处理——没填地址时不该弹安全提示。
         */
        internal fun isLocalHost(rawHost: String): Boolean {
            val h = rawHost.trim().lowercase().removeSurrounding("[", "]")
            if (h.isEmpty()) return true
            if (h == "localhost" || h == "::1") return true
            if (h.startsWith("127.")) return true
            if (isPrivateIpv4(h)) return true
            if (PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
            return false
        }

        /**
         * 这个地址最终会以 https 访问吗。
         *
         * 供失败提示使用：同样的错误在 http 与 https 下的可能原因不同，
         * 提示要按实际用的协议给，不能猜。地址无法解析时返回 false（异常由调用方另行处理）。
         */
        fun usesHttps(raw: String): Boolean =
            runCatching { normalizeBaseUrl(raw) }.getOrNull()?.startsWith("https://") == true

        /** 从 `host[:port][/path]` 取出主机名，去掉协议、用户信息、端口与路径。 */
        internal fun hostOf(raw: String): String {
            val s = raw.trim()
                .substringAfter("://")
                .substringAfterLast('@')
                .substringBefore('/')
            // IPv6 带方括号：`[::1]:8088`，方括号内可能还有冒号，不能按冒号切。
            if (s.startsWith("[")) return s.substringAfter('[').substringBefore(']')
            return s.substringBefore(':')
        }

        private fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            val a = parts[0]
            val b = parts[1]
            return a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
        }

        private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".internal", ".home")

        /**
         * [normalizeEntries] 的实现，规范化函数作为参数传入。
         *
         * 之所以单独抽出来：`QwenPawApi.resolve` 依赖 OkHttp 的地址解析，
         * 单测里可以传一个假的规范化函数来验证「去重、保留首个、清空别名」这些规则，
         * 不必让测试也依赖网络栈。
         */
        internal fun normalizeEntriesPure(
            entries: List<AddressEntry>,
            normalize: (String) -> String,
        ): List<AddressEntry> {
            val cleaned = entries.filter { it.url.isNotBlank() }
            if (cleaned.isEmpty()) throw IllegalArgumentException("请填写服务器地址")
            val seen = mutableSetOf<String>()
            return cleaned.mapNotNull { entry ->
                val url = normalize(entry.url.trim())
                if (!seen.add(url)) {
                    null
                } else {
                    entry.copy(
                        url = url,
                        alias = entry.alias?.trim()?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
    }
}
