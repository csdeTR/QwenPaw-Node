package io.qwenpaw.node.data.qr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 访问令牌里可读的字段。
 *
 * **仅用于展示**，例如在界面上写「有效期至 2027-02-18」。令牌是否有效只能由服务端
 * 回答（`GET /api/auth/verify`），本地解码不验签，改过的载荷也能解出同样的形状。
 */
data class TokenClaims(
    /** 用户名。与 verify 响应体里的 `username` 冲突时以服务端为准。 */
    val subject: String?,
    /** 签发时间，Unix 秒；缺失为 null。 */
    val issuedAt: Long?,
    /** 到期时间，Unix 秒；缺失为 null。长期令牌同样是 100 年后的一个具体时刻。 */
    val expiresAt: Long?,
    val id: String?,
)

/**
 * 解开令牌载荷。
 *
 * 服务端的令牌是 `base64url(payload).signature`，与 JWT 的形态一致但签名算法不同，
 * 因此只能读载荷，不能自称「验证过」。
 */
object TokenClaimsCodec {
    fun decodeUnverified(token: String): TokenClaims? {
        val payloadPart = token.trim().substringBefore('.')
        if (payloadPart.isEmpty() || payloadPart.length > 8192) return null
        val json = runCatching { decodeBase64Url(payloadPart) }.getOrNull() ?: return null
        return runCatching {
            val obj = Json.parseToJsonElement(json) as? JsonObject ?: return null

            fun text(key: String): String? = obj[key]
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull

            fun number(key: String): Long? {
                val raw = text(key)?.trim() ?: return null
                return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
            }

            TokenClaims(
                subject = text("sub")?.takeIf { it.isNotBlank() },
                issuedAt = number("iat"),
                expiresAt = number("exp"),
                id = text("jti")?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun decodeBase64Url(value: String): String {
        val normalized = value.trimEnd('=')
            .replace('-', '+')
            .replace('_', '/')
        val padded = when (normalized.length % 4) {
            0 -> normalized
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> throw IllegalArgumentException("bad base64 length")
        }
        return java.util.Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
    }
}