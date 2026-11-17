package app.acloudroid.qwq.data.settings

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.acloudroid.qwq.R

/**
 * Supported application languages.
 *
 * Mapped to BCP-47 language tags declared in `res/xml/locales_config.xml`.
 * Changes are synchronized with the Android 13+ system Per-App Language Preferences
 * via [AppCompatDelegate.setApplicationLocales].
 */
enum class AppLanguage(
    val tag: String,
    @get:StringRes val labelRes: Int,
    val nativeName: String
) {
    SYSTEM("", R.string.language_system_default, "跟随系统"),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.language_zh_hans, "简体中文"),
    TRADITIONAL_CHINESE("zh-Hant", R.string.language_zh_hant, "繁體中文"),
    ENGLISH("en", R.string.language_en, "English");

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            return entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
                ?: when {
                    tag.startsWith("zh-Hant", ignoreCase = true) ||
                    tag.startsWith("zh-TW", ignoreCase = true) ||
                    tag.startsWith("zh-HK", ignoreCase = true) ||
                    tag.startsWith("zh-MO", ignoreCase = true) -> TRADITIONAL_CHINESE
                    tag.startsWith("zh", ignoreCase = true) -> SIMPLIFIED_CHINESE
                    tag.startsWith("en", ignoreCase = true) -> ENGLISH
                    else -> SYSTEM
                }
        }

        /** Retrieves the current app language from [AppCompatDelegate.getApplicationLocales]. */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            val tag = locales[0]?.toLanguageTag()
            return fromTag(tag)
        }

        /**
         * Sets and applies the app language across system settings and app configuration.
         */
        fun applyLanguage(language: AppLanguage) {
            val localeList = if (language.tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }
}
