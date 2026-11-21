package app.acloudroid.qwq.ui.settings

import androidx.annotation.StringRes
import app.acloudroid.qwq.R

/**
 * Identity of one settings row.
 */
enum class SettingsItem {
    ICLOUD_WEB,
    APP_LANGUAGE,
    THEME_MODE,
    DYNAMIC_COLOR,
    CLOUD_MEDIA_PROVIDER,
    FULL_SCAN,
    DOWNLOAD_ALL,
    RE_LOGIN,
    MONTH_COVER_PREVIEW,
    SCAN_FREQUENCY,
    CUSTOM_INTERVAL,
    NAV_BAR_ITEMS,
    BACKUP_CELLULAR_DATA,
    BACKUP_REQUIRE_CHARGING,
    BACKUP_BATTERY_NOT_LOW,
    BACKUP_CONCURRENT_LIMIT,
    BACKUP_NOTIFICATION,
    BATTERY_OPTIMIZATION,
    KEEP_BACKGROUND_ALIVE,
    PERIODIC_BACKUP_INTERVAL,
    DRIVE_DISPLAY_MODE,
    DRIVE_SORT_FIELD,
    DRIVE_SORT_ORDER,
    AUTO_CHECK_UPDATE,
    ABOUT
}

/** One titled block of rows. */
data class SettingsGroup(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int? = null,
    val items: List<SettingsItem>
)

/**
 * Declarative layout of the settings screen.
 */
object SettingsCatalog {

    val groups: List<SettingsGroup> = listOf(
        SettingsGroup(
            titleRes = R.string.settings_group_quick_access,
            descriptionRes = R.string.settings_group_quick_access_desc,
            items = listOf(SettingsItem.ICLOUD_WEB)
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_navigation,
            descriptionRes = R.string.settings_group_navigation_desc,
            items = listOf(SettingsItem.NAV_BAR_ITEMS)
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_appearance,
            descriptionRes = R.string.settings_group_appearance_desc,
            items = listOf(SettingsItem.APP_LANGUAGE, SettingsItem.THEME_MODE, SettingsItem.DYNAMIC_COLOR)
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_system_integration,
            descriptionRes = R.string.settings_group_system_integration_desc,
            items = listOf(SettingsItem.CLOUD_MEDIA_PROVIDER)
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_auto_backup,
            descriptionRes = R.string.settings_group_auto_backup_desc,
            items = listOf(
                SettingsItem.BATTERY_OPTIMIZATION,
                SettingsItem.KEEP_BACKGROUND_ALIVE,
                SettingsItem.PERIODIC_BACKUP_INTERVAL,
                SettingsItem.BACKUP_CELLULAR_DATA,
                SettingsItem.BACKUP_REQUIRE_CHARGING,
                SettingsItem.BACKUP_BATTERY_NOT_LOW,
                SettingsItem.BACKUP_CONCURRENT_LIMIT,
                SettingsItem.BACKUP_NOTIFICATION
            )
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_photos_library,
            descriptionRes = R.string.settings_group_photos_library_desc,
            items = listOf(SettingsItem.FULL_SCAN, SettingsItem.MONTH_COVER_PREVIEW)
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_auto_scan,
            descriptionRes = R.string.settings_group_auto_scan_desc,
            items = listOf(SettingsItem.SCAN_FREQUENCY, SettingsItem.CUSTOM_INTERVAL)
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_drive,
            descriptionRes = R.string.settings_group_drive_desc,
            items = listOf(
                SettingsItem.DRIVE_DISPLAY_MODE,
                SettingsItem.DRIVE_SORT_FIELD,
                SettingsItem.DRIVE_SORT_ORDER
            )
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_advanced,
            descriptionRes = R.string.settings_group_advanced_desc,
            items = listOf(SettingsItem.DOWNLOAD_ALL, SettingsItem.RE_LOGIN)
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_about,
            descriptionRes = R.string.settings_group_about_desc,
            items = listOf(SettingsItem.AUTO_CHECK_UPDATE, SettingsItem.ABOUT)
        )
    )
}
