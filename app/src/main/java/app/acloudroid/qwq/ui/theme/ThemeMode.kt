package app.acloudroid.qwq.ui.theme

import androidx.annotation.StringRes
import app.acloudroid.qwq.R

/**
 * Display theme options for the application.
 */
enum class ThemeMode(
    @get:StringRes val labelRes: Int,
    val label: String
) {
    SYSTEM(R.string.theme_system, "跟随系统"),
    LIGHT(R.string.theme_light, "浅色模式"),
    DARK(R.string.theme_dark, "深色模式")
}
