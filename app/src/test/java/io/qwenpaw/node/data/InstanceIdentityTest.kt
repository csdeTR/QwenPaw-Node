package io.qwenpaw.node.data

import io.qwenpaw.node.data.local.InstanceIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 身份比对只认 workspace 强信号。 */
class InstanceIdentityTest {

    private fun identity(vararg workspaces: Pair<String, String>, avatar: String? = null) =
        InstanceIdentity(
            agentIds = workspaces.map { it.first },
            workspaces = workspaces.toMap(),
            avatarHash = avatar,
        )

    @Test
    fun `same workspace is the same instance`() {
        assertEquals(
            IdentityVerdict.SAME,
            compareIdentity(
                identity("default" to "/srv/a"),
                identity("default" to "/srv/a"),
            ),
        )
    }

    @Test
    fun `different workspace is a different instance`() {
        assertEquals(
            IdentityVerdict.DIFFERENT,
            compareIdentity(
                identity("default" to "/srv/a"),
                identity("default" to "/srv/b"),
            ),
        )
    }

    @Test
    fun `one strong match suppresses another difference`() {
        assertEquals(
            IdentityVerdict.SAME,
            compareIdentity(
                identity("default" to "/srv/a", "writer" to "/srv/w1"),
                identity("default" to "/srv/a", "writer" to "/srv/w2"),
            ),
        )
    }

    @Test
    fun `avatar change alone does not conclude`() {
        assertEquals(
            IdentityVerdict.INCONCLUSIVE,
            compareIdentity(
                identity(avatar = "a"),
                identity(avatar = "b"),
            ),
        )
    }

    @Test
    fun `missing fingerprint is inconclusive`() {
        assertEquals(
            IdentityVerdict.INCONCLUSIVE,
            compareIdentity(null, identity("default" to "/srv/a")),
        )
        assertEquals(
            IdentityVerdict.INCONCLUSIVE,
            compareIdentity(identity("default" to "/srv/a"), null),
        )
    }

    @Test
    fun `blank workspace is not a strong signal`() {
        assertEquals(
            IdentityVerdict.INCONCLUSIVE,
            compareIdentity(
                identity("default" to ""),
                identity("default" to "/srv/a"),
            ),
        )
    }
}
