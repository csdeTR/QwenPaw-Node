package io.qwenpaw.node.data

/**
 * 服务端版本。
 *
 * 只处理 QwenPaw 实际出现的形状：`2.3.0.post1`、`2.3.0b2`、`v2.3.1` 与两段版本。
 * 排序遵循 Python 打包规则中本轮会用到的一部分：
 * `2.3.0b2 < 2.3.0 < 2.3.0.post1 < 2.3.1`。
 */
data class ServerVersion(
    val major: Int,
    val minor: Int = 0,
    val patch: Int = 0,
    val pre: PreRelease? = null,
    val post: Int? = null,
) : Comparable<ServerVersion> {

    enum class PreKind(val rank: Int) {
        ALPHA(0),
        BETA(1),
        RC(2),
    }

    data class PreRelease(val kind: PreKind, val number: Int)

    override fun compareTo(other: ServerVersion): Int {
        val base = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (base != 0) return base
        val preCompare = comparePre(pre, other.pre)
        if (preCompare != 0) return preCompare
        return compareValues(post ?: 0, other.post ?: 0)
    }

    private fun comparePre(a: PreRelease?, b: PreRelease?): Int = when {
        a == null && b == null -> 0
        // 正式版高于同号的预发布版。
        a == null -> 1
        b == null -> -1
        else -> compareValuesBy(a, b, { it.kind.rank }, { it.number })
    }

    fun atLeast(min: ServerVersion): Boolean = this >= min

    companion object {
        /** 频道配置改版所需的最低版本。 */
        val CHANNELS_MIN = ServerVersion(major = 2, minor = 3, patch = 0, post = 1)

        private val PATTERN = Regex(
            """^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-_.]?(a|alpha|b|beta|rc))?(\d+)?(?:[-_.]?post(\d+))?$""",
            RegexOption.IGNORE_CASE,
        )

        /** 无法识别时返回 null，调用方应把「未知」与「确定过低」分开处理。 */
        fun parse(raw: String?): ServerVersion? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val match = PATTERN.matchEntire(text) ?: return null
            val groups = match.groupValues
            val major = groups[1].toIntOrNull() ?: return null
            val minor = groups[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val patch = groups[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val preKind = groups[4].takeIf { it.isNotEmpty() }?.let { tag ->
                when (tag.lowercase()) {
                    "a", "alpha" -> PreKind.ALPHA
                    "b", "beta" -> PreKind.BETA
                    else -> PreKind.RC
                }
            }
            val pre = preKind?.let { kind ->
                PreRelease(kind, groups[5].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0)
            }
            val post = groups[6].takeIf { it.isNotEmpty() }?.toIntOrNull()
            return ServerVersion(major, minor, patch, pre, post)
        }
    }
}
