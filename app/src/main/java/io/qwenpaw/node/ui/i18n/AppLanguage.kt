package io.qwenpaw.node.ui.i18n

import androidx.annotation.StringRes
import io.qwenpaw.node.R

/**
 * 应用界面语言。
 *
 * 语言自称固定用该语言本身书写，不随界面语言变化，资源上标 `translatable="false"`。
 * 1.0.0 之前只有简体中文；新增语言时在这里加枚举项，并为该语言新建 `values-<lang>/`。
 */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    ZH_CN("zh-CN", R.string.app_language_zh_cn),
    ;

    companion object {
        /** 偏好里存语言标签；未知值返回 null，界面回退到默认资源（简体中文）。 */
        fun fromTag(tag: String?): AppLanguage? {
            val value = tag?.trim().orEmpty()
            if (value.isEmpty()) return null
            return entries.firstOrNull { it.tag.equals(value, ignoreCase = true) }
        }
    }
}
