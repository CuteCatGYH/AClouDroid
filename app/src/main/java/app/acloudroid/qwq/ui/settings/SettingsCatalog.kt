package app.acloudroid.qwq.ui.settings

/**
 * Identity of one settings row.
 *
 * The screen renders groups from [SettingsCatalog]; adding a setting means adding an enum entry,
 * one line in [SettingsCatalog.groups], and one branch in the screen's row builder. The enum (not
 * a string key) is what keeps the `when` exhaustive, so a new item cannot be added and then
 * silently not rendered.
 */
enum class SettingsItem {
    /** Open iCloud web version in an in-app WebView. */
    ICLOUD_WEB,

    /** Theme mode: Follow system / Light / Dark. */
    THEME_MODE,

    /** Material You Monet dynamic color from system wallpaper. */
    DYNAMIC_COLOR,

    /** Cloud media provider settings shortcut. */
    CLOUD_MEDIA_PROVIDER,

    /** Long-running: walks the whole cloud library. Shows a warning before starting. */
    FULL_SCAN,

    /**
     * Whether the month jump panel shows a thumbnail per month.
     *
     * Turning it off also stops the scan from downloading any cover — this is not a display-only
     * switch (see [app.acloudroid.qwq.data.settings.AppSettings.monthCoverEnabled]).
     */
    MONTH_COVER_PREVIEW,

    /** How often the full walk repeats in the background. */
    SCAN_FREQUENCY,

    /** Interval in days; only meaningful while the frequency is 自定义. */
    CUSTOM_INTERVAL,

    /** Bottom navigation bar items visibility toggle. */
    NAV_BAR_ITEMS,

    /** Allow cellular data upload for auto-backup. */
    BACKUP_CELLULAR_DATA,

    /** Require device charging for auto-backup. */
    BACKUP_REQUIRE_CHARGING,

    /** Require battery not low for auto-backup. */
    BACKUP_BATTERY_NOT_LOW,

    /** Maximum concurrent photo uploads (1 to 3). */
    BACKUP_CONCURRENT_LIMIT,

    /** Show persistent progress notification during background backup. */
    BACKUP_NOTIFICATION,

    /** Request ignore battery optimizations to keep app alive in background. */
    BATTERY_OPTIMIZATION,

    /** Periodic backup interval in minutes. */
    PERIODIC_BACKUP_INTERVAL,

    /** About sub-screen containing app icon, name, version, and repository link. */
    ABOUT
}

/** One titled block of rows. */
data class SettingsGroup(
    val title: String,
    val description: String? = null,
    val items: List<SettingsItem>
)

/**
 * Declarative layout of the settings screen.
 *
 * Kept data-only (no composables) so the structure is reviewable at a glance and a future
 * [SettingsItem] can be slotted in without touching layout code.
 */
object SettingsCatalog {

    val groups: List<SettingsGroup> = listOf(
        SettingsGroup(
            title = "快捷访问",
            description = "在应用内快速访问 iCloud 官方网页版",
            items = listOf(SettingsItem.ICLOUD_WEB)
        ),
        SettingsGroup(
            title = "导航栏",
            description = "自定义底部导航栏显示的模块",
            items = listOf(SettingsItem.NAV_BAR_ITEMS)
        ),
        SettingsGroup(
            title = "外观与个性化",
            description = "设置应用的深浅主题模式与 Android 12+ Monet 动态壁纸取色效果。",
            items = listOf(SettingsItem.THEME_MODE, SettingsItem.DYNAMIC_COLOR)
        ),
        SettingsGroup(
            title = "系统整合",
            description = "配置 iClouDroid 与 Android 系统的多媒体生态深度联动。",
            items = listOf(SettingsItem.CLOUD_MEDIA_PROVIDER)
        ),
        SettingsGroup(
            title = "自动备份策略",
            description = "配置本地相册后台备份时的网络与电量运行约束条件。",
            items = listOf(
                SettingsItem.BATTERY_OPTIMIZATION,
                SettingsItem.PERIODIC_BACKUP_INTERVAL,
                SettingsItem.BACKUP_CELLULAR_DATA,
                SettingsItem.BACKUP_REQUIRE_CHARGING,
                SettingsItem.BACKUP_BATTERY_NOT_LOW,
                SettingsItem.BACKUP_CONCURRENT_LIMIT,
                SettingsItem.BACKUP_NOTIFICATION
            )
        ),
        SettingsGroup(
            title = "照片库",
            description = "全盘扫描会遍历整个 iCloud 照片库，用于发现并清理在其它设备上已删除的照片。",
            items = listOf(SettingsItem.FULL_SCAN, SettingsItem.MONTH_COVER_PREVIEW)
        ),
        SettingsGroup(
            title = "自动扫描",
            description = "按设定周期在后台（凌晨 3 点）自动执行全盘扫描与增量同步。",
            items = listOf(SettingsItem.SCAN_FREQUENCY, SettingsItem.CUSTOM_INTERVAL)
        ),
        SettingsGroup(
            title = "关于",
            description = "查看应用版本信息与开源仓库",
            items = listOf(SettingsItem.ABOUT)
        )
    )
}
