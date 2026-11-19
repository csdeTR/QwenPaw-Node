package io.qwenpaw.node.ui.i18n

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/**
 * 语言偏好的读写与 Context 包装。
 *
 * 偏好与其余界面设置同用一个偏好文件。键不存在表示用户从未选择过语言：
 * 全新安装由首屏要求选择，老用户由启动流程静默写入简体中文。
 */
object AppLocale {

    const val PREFS_NAME = "qwenpaw_ui"
    const val KEY_LANGUAGE = "app_language"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(prefs: SharedPreferences): AppLanguage? =
        AppLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null))

    fun read(context: Context): AppLanguage? = read(prefs(context))

    fun write(prefs: SharedPreferences, language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.tag).apply()
    }

    /**
     * 用已选语言包装 Context。没有偏好时原样返回：
     * 默认资源就是简体中文，不需要额外覆盖。
     */
    fun wrap(base: Context): Context {
        val language = read(base) ?: return base
        val config = Configuration(base.resources.configuration)
        val locale = Locale.forLanguageTag(language.tag)
        config.setLocale(locale)
        config.setLocales(android.os.LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * 老用户迁移判据：从未选择过语言、但设备上已有实例，说明是升级上来的老用户。
     * 这类用户不显示语言选择页，直接使用简体中文。
     */
    fun shouldMigrateToChinese(languageTag: String?, hasSavedInstances: Boolean): Boolean =
        languageTag.isNullOrBlank() && hasSavedInstances
}
