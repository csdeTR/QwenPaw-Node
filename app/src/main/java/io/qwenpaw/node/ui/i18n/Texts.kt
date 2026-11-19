package io.qwenpaw.node.ui.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * 非 Compose 代码取界面文案的入口。
 *
 * ViewModel 与 data 层没有可直接使用的 Context，统一从这里取资源。
 * Application 启动时安装；切换语言后 Activity 重建，重建入口会再安装一次，
 * 保证存活的 ViewModel 后续取到的是新语言。
 */
object Texts {

    @Volatile
    private var installed: Resources? = null

    /** 单测注入的替代实现，避免测试依赖 Android 资源。 */
    @Volatile
    private var override: ((Int, Array<out Any>) -> String)? = null

    @Volatile
    private var overrideQuantity: ((Int, Int, Array<out Any>) -> String)? = null

    fun install(context: Context) {
        val app = context.applicationContext
        val language = AppLocale.read(app)
        installed = if (language == null) {
            app.resources
        } else {
            val config = Configuration(app.resources.configuration)
            val locale = Locale.forLanguageTag(language.tag)
            config.setLocale(locale)
            config.setLocales(android.os.LocaleList(locale))
            app.createConfigurationContext(config).resources
        }
    }

    internal fun installResolver(resolver: (Int, Array<out Any>) -> String) {
        override = resolver
    }

    internal fun installQuantityResolver(resolver: (Int, Int, Array<out Any>) -> String) {
        overrideQuantity = resolver
    }

    fun get(@StringRes id: Int, vararg args: Any): String {
        override?.let { return it(id, args) }
        val res = installed ?: error("Texts is not installed")
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }

    fun quantity(@PluralsRes id: Int, count: Int, vararg args: Any): String {
        overrideQuantity?.let { return it(id, count, args) }
        val res = installed ?: error("Texts is not installed")
        return if (args.isEmpty()) {
            res.getQuantityString(id, count)
        } else {
            res.getQuantityString(id, count, *args)
        }
    }
}
