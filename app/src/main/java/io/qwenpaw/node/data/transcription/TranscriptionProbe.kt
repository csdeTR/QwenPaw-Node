package io.qwenpaw.node.data.transcription

import io.qwenpaw.node.data.remote.ApiException
import io.qwenpaw.node.data.remote.Connection
import io.qwenpaw.node.data.remote.QwenPawApi

/**
 * 服务端语音转写的可用性结论。
 *
 * 与服务端的三档 `transcription_provider_type` 不是一一对应：`whisper_api` 与
 * `local_whisper` 各自还有一个「配没配好」的判断（见 [TranscriptionProbe]）。
 */
sealed interface TranscriptionStatus {

    /** 还没探过，或探测被 401 挡住（服务端要登录、此时还没拿到 token）。 */
    data object Unknown : TranscriptionStatus

    /** 已验证可用，可以录音并上传。 */
    data object Ready : TranscriptionStatus

    /** 服务端明确关掉了转写。 */
    data object Disabled : TranscriptionStatus

    /** 转写已打开但缺配置：`whisper_api` 没选 provider，或 `local_whisper` 依赖不全。 */
    data object Misconfigured : TranscriptionStatus

    /** 服务端版本没有这组端点（404）。 */
    data object Unsupported : TranscriptionStatus

    /**
     * 能否开始录音。
     *
     * 只有 [Unknown] 允许先录——它是「还没问出来」，点一下麦克风会先重探；
     * 其余不可用态都不该让用户白录一段再失败。
     */
    val canRecord: Boolean
        get() = this is Ready

    /** 展现在设置页与服务端说明行的一句话。 */
    val label: String
        get() = when (this) {
            Unknown -> "尚未检测转写服务"
            Ready -> "服务端转写已就绪"
            Disabled -> "服务端未启用语音转写"
            Misconfigured -> "服务端转写缺少配置"
            Unsupported -> "当前服务端版本不支持语音转写"
        }
}

/**
 * 按实例缓存转写服务的可用性。
 *
 * 判定集中在这一处，页面不自行拼 `provider_type` 与 provider 清单：
 * 实测 `whisper_api` 把 `transcription_provider_type` 设成 `whisper_api` 而
 * `configured_provider_id` 为空时，`transcribe` 会返回 500
 * 「Transcription failed. Check provider configuration.」——
 * 只看开关状态会把它当成可用。
 */
class TranscriptionProbe(private val api: QwenPawApi) {

    private val cache = mutableMapOf<String, TranscriptionStatus>()

    private fun key(instanceId: String, agentId: String): String = "$instanceId|$agentId"

    fun cached(instanceId: String, agentId: String): TranscriptionStatus =
        cache[key(instanceId, agentId)] ?: TranscriptionStatus.Unknown

    /**
     * 探测一次并写入缓存。
     *
     * 401/403 记 [TranscriptionStatus.Unknown]（「待重探」）而不是「未配置」：
     * 转写路由不在服务端鉴权的公开路径里，验证阶段还没有 token 时必然 401。
     */
    suspend fun probe(instanceId: String, conn: Connection): TranscriptionStatus {
        val result = try {
            resolve(conn)
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) {
                TranscriptionStatus.Unknown
            } else if (e.code == 404) {
                TranscriptionStatus.Unsupported
            } else {
                // 其余状态码说明端点存在但读不到配置，按未配置处理，让用户去服务端看。
                TranscriptionStatus.Misconfigured
            }
        } catch (e: java.io.IOException) {
            // 连不上：保留上一次的结论，避免网络抖动把「已就绪」翻成未配置。
            cache[key(instanceId, conn.agentId)] ?: TranscriptionStatus.Unknown
        }
        cache[key(instanceId, conn.agentId)] = result
        return result
    }

    private fun resolve(conn: Connection): TranscriptionStatus {
        val type = api.transcriptionProviderType(conn).providerType
        return when (type) {
            "disabled" -> TranscriptionStatus.Disabled
            "whisper_api" -> {
                val configured = api.transcriptionProviders(conn).configuredProviderId
                if (configured.isBlank()) {
                    TranscriptionStatus.Misconfigured
                } else {
                    TranscriptionStatus.Ready
                }
            }
            "local_whisper" -> {
                if (api.localWhisperStatus(conn).available) {
                    TranscriptionStatus.Ready
                } else {
                    TranscriptionStatus.Misconfigured
                }
            }
            // 服务端会拒绝未知取值，这里只防御将来新增的档位。
            else -> TranscriptionStatus.Misconfigured
        }
    }

    /** 换实例或换智能体时不必清缓存：键已经区分开了，各自保留各自的结论。 */
    fun clear(instanceId: String) {
        cache.keys.filter { it.startsWith("$instanceId|") }.forEach { cache.remove(it) }
    }
}