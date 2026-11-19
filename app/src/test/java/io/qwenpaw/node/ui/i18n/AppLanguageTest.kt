package io.qwenpaw.node.ui.i18n

import io.qwenpaw.node.ui.i18n.AppLanguage.Companion.fromTag
import io.qwenpaw.node.ui.i18n.AppLocale.shouldMigrateToChinese
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `known tags match case insensitively`() {
        assertEquals(AppLanguage.ZH_CN, fromTag("zh-CN"))
        assertEquals(AppLanguage.ZH_CN, fromTag("zh-cn"))
        assertEquals(AppLanguage.ZH_CN, fromTag(" ZH-CN "))
    }

    @Test
    fun `unknown tags fall back to null`() {
        assertNull(fromTag(null))
        assertNull(fromTag(""))
        assertNull(fromTag("  "))
        assertNull(fromTag("en"))
    }

    @Test
    fun `migration applies only to old installs without a choice`() {
        assertTrue(shouldMigrateToChinese(null, hasSavedInstances = true))
        assertFalse(shouldMigrateToChinese(null, hasSavedInstances = false))
        assertFalse(shouldMigrateToChinese("zh-CN", hasSavedInstances = true))
        assertFalse(shouldMigrateToChinese("zh-CN", hasSavedInstances = false))
    }
}
