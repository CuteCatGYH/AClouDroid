package app.acloudroid.qwq.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.acloudroid.qwq.IClouDroidApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.acloudroid.qwq.data.settings.AppSettings
import app.acloudroid.qwq.data.settings.DriveDisplayMode
import app.acloudroid.qwq.data.settings.DriveSortField
import app.acloudroid.qwq.data.settings.DriveSortOrder
import app.acloudroid.qwq.data.settings.ScanCadence
import app.acloudroid.qwq.data.settings.ScanFrequency
import app.acloudroid.qwq.ui.theme.ThemeMode

/**
 * Settings, reachable from the account sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    settings: AppSettings,
    fullScanMessage: String?,
    isFullScanRunning: Boolean,
    onStartFullScan: () -> Unit,
    onFrequencyChanged: (ScanFrequency) -> Unit,
    onCustomIntervalChanged: (Int) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(),
    modifier: Modifier = Modifier
) {
    val frequency by settings.scanFrequency.collectAsStateWithLifecycle()
    val customDays by settings.customIntervalDays.collectAsStateWithLifecycle()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val useDynamicColor by settings.useDynamicColor.collectAsStateWithLifecycle()
    val monthCoverEnabled by settings.monthCoverEnabled.collectAsStateWithLifecycle()
    val navBarDestinations by settings.navBarDestinations.collectAsStateWithLifecycle()
    val allowCellularUpload by settings.allowCellularUpload.collectAsStateWithLifecycle()
    val requireCharging by settings.requireCharging.collectAsStateWithLifecycle()
    val requireBatteryNotLow by settings.requireBatteryNotLow.collectAsStateWithLifecycle()
    val maxConcurrentUploads by settings.maxConcurrentUploads.collectAsStateWithLifecycle()
    val showBackupNotification by settings.showBackupNotification.collectAsStateWithLifecycle()
    val periodicBackupInterval by settings.periodicBackupInterval.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showScanWarning by remember { mutableStateOf(false) }
    var showAboutScreen by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "设置",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                SettingsCatalog.groups.forEach { group ->
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)
                    )

                    if (group.description != null) {
                        Text(
                            text = group.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column {
                            val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
                            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                            val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
                            val isIgnoringBatteryOptimizations = remember(lifecycleState) {
                                powerManager.isIgnoringBatteryOptimizations(context.packageName)
                            }

                            val visibleItems = group.items.filter { item ->
                                if (item == SettingsItem.KEEP_BACKGROUND_ALIVE) isIgnoringBatteryOptimizations else true
                            }

                            visibleItems.forEachIndexed { index, item ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                                SettingsRow(
                                    item = item,
                                    settings = settings,
                                    themeMode = themeMode,
                                    useDynamicColor = useDynamicColor,
                                    monthCoverEnabled = monthCoverEnabled,
                                    navBarDestinations = navBarDestinations,
                                    allowCellularUpload = allowCellularUpload,
                                    requireCharging = requireCharging,
                                    requireBatteryNotLow = requireBatteryNotLow,
                                    maxConcurrentUploads = maxConcurrentUploads,
                                    showBackupNotification = showBackupNotification,
                                    periodicBackupInterval = periodicBackupInterval,
                                    frequency = frequency,
                                    customDays = customDays,
                                    isFullScanRunning = isFullScanRunning,
                                    onRequestFullScan = { showScanWarning = true },
                                    onFrequencyChanged = onFrequencyChanged,
                                    onCustomIntervalChanged = onCustomIntervalChanged,
                                    onOpenAbout = { showAboutScreen = true }
                                )
                            }
                        }
                    }
                }

                if (fullScanMessage != null) {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(
                            text = fullScanMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }

            if (showScanWarning) {
                FullScanWarningDialog(
                    frequency = frequency,
                    customDays = customDays,
                    onConfirm = {
                        showScanWarning = false
                        onStartFullScan()
                    },
                    onDismiss = { showScanWarning = false }
                )
            }
        }

        if (showAboutScreen) {
            AboutSubScreen(onBack = { showAboutScreen = false })
        }
    }
}

@Composable
private fun FullScanWarningDialog(
    frequency: ScanFrequency,
    customDays: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始全盘扫描？") },
        text = {
            Column {
                Text(
                    "全盘扫描会完整遍历云端照片库（发起数百次请求），并比对本地数据库以清理在其它设备上已删除的照片。"
                )
                Spacer(Modifier.height(8.dp))
                if (frequency != ScanFrequency.OFF) {
                    val cadenceDesc = when (frequency) {
                        ScanFrequency.DAILY -> "每天"
                        ScanFrequency.WEEKLY -> "每周"
                        ScanFrequency.MONTHLY -> "每月"
                        ScanFrequency.CUSTOM -> "每 $customDays 天"
                        ScanFrequency.OFF -> ""
                    }
                    Text(
                        "后台扫描已开启（$cadenceDesc 凌晨 3:00 自动执行），通常无需手动触发。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "扫到一半退出不会损坏任何数据，下次会从断点继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("开始扫描") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SettingsRow(
    item: SettingsItem,
    settings: AppSettings,
    themeMode: ThemeMode,
    useDynamicColor: Boolean,
    monthCoverEnabled: Boolean,
    navBarDestinations: Set<app.acloudroid.qwq.ui.navigation.AppDestination>,
    allowCellularUpload: Boolean,
    requireCharging: Boolean,
    requireBatteryNotLow: Boolean,
    maxConcurrentUploads: Int,
    showBackupNotification: Boolean,
    periodicBackupInterval: Long,
    frequency: ScanFrequency,
    customDays: Int,
    isFullScanRunning: Boolean,
    onRequestFullScan: () -> Unit,
    onFrequencyChanged: (ScanFrequency) -> Unit,
    onCustomIntervalChanged: (Int) -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current

    when (item) {
        SettingsItem.BATTERY_OPTIMIZATION -> {
            val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
            
            // Re-evaluate whenever the screen is resumed
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
            val isIgnoring = remember(lifecycleState) {
                if (lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
                } else {
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
                }
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isIgnoring) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("SettingsScreen", "Failed to launch battery optimization intent", e)
                                android.widget.Toast.makeText(context, "设备不支持直接跳转，请到系统设置中手动开启", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Already ignoring, maybe guide user to regular settings if they want to turn it off?
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "常驻后台 (实时备份)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isIgnoring) "已允许常驻后台，可提升实时备份概率" else "忽略电池优化，防止应用在后台被系统杀死",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Text(
                        text = "若设置未生效，请参考 Don't kill my app! ",
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://dontkillmyapp.com/")
                        }
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = isIgnoring,
                    onCheckedChange = null
                )
            }
        }

        SettingsItem.KEEP_BACKGROUND_ALIVE -> {
            val keepBackgroundAlive by settings.keepBackgroundAlive.collectAsStateWithLifecycle()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val newValue = !keepBackgroundAlive
                        settings.setKeepBackgroundAlive(newValue)
                        if (newValue) {
                            app.acloudroid.qwq.feature.photos.work.KeepAliveBackupService.start(context)
                        } else {
                            app.acloudroid.qwq.feature.photos.work.KeepAliveBackupService.stop(context)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "显示常驻通知",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (keepBackgroundAlive) "已开启常驻通知" else "显示一条常驻通知，阻止应用被系统杀死",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = keepBackgroundAlive,
                    onCheckedChange = { newValue ->
                        settings.setKeepBackgroundAlive(newValue)
                        if (newValue) {
                            app.acloudroid.qwq.feature.photos.work.KeepAliveBackupService.start(context)
                        } else {
                            app.acloudroid.qwq.feature.photos.work.KeepAliveBackupService.stop(context)
                        }
                    }
                )
            }
        }

        SettingsItem.PERIODIC_BACKUP_INTERVAL -> {
            var showCustomIntervalDialog by remember { mutableStateOf(false) }

            DropdownRow(
                title = "周期备份频率",
                subtitle = if (periodicBackupInterval == 0L) "新增照片后立刻触发备份" else "后台触发自动备份的最高频率",
                current = when (periodicBackupInterval) {
                    0L -> "立即"
                    15L -> "15 分钟"
                    30L -> "30 分钟"
                    60L -> "1 小时"
                    else -> "$periodicBackupInterval 分钟"
                },
                options = listOf(
                    0L to "立即 (媒体监听)",
                    15L to "15 分钟",
                    30L to "30 分钟",
                    60L to "1 小时",
                    -1L to "自定义..."
                ) + (if (listOf(0L, 15L, 30L, 60L, -1L).contains(periodicBackupInterval)) emptyList() else listOf(periodicBackupInterval to "$periodicBackupInterval 分钟 (自定义)")),
                onSelect = {
                    if (it == -1L) {
                        showCustomIntervalDialog = true
                    } else {
                        settings.setPeriodicBackupInterval(it)
                        // Need to re-schedule Worker after changing interval
                        app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker.scheduleBackup(context)
                    }
                }
            )

            if (showCustomIntervalDialog) {
                var customInput by remember { mutableStateOf(periodicBackupInterval.toString()) }
                AlertDialog(
                    onDismissRequest = { showCustomIntervalDialog = false },
                    title = { Text("自定义周期备份频率") },
                    text = {
                        Column {
                            Text("输入间隔分钟数 (分钟):", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = customInput,
                                onValueChange = { customInput = it },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val value = customInput.toLongOrNull() ?: 15L
                                settings.setPeriodicBackupInterval(value)
                                app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker.scheduleBackup(context)
                                showCustomIntervalDialog = false
                            }
                        ) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomIntervalDialog = false }) { Text("取消") }
                    }
                )
            }
        }

        SettingsItem.BACKUP_CELLULAR_DATA -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "允许蜂窝移动网络备份",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (allowCellularUpload) "已允许使用蜂窝移动数据上传备份" else "默认仅限 Wi-Fi 网络下执行自动备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = allowCellularUpload,
                onCheckedChange = {
                    settings.setAllowCellularUpload(it)
                    app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker.scheduleBackup(context)
                }
            )
        }

        SettingsItem.BACKUP_REQUIRE_CHARGING -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "仅限设备充电时备份",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (requireCharging) "仅在连接电源充电时触发后台自动备份" else "连接电源或未充电时均可运行后台备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = requireCharging,
                onCheckedChange = {
                    settings.setRequireCharging(it)
                    app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker.scheduleBackup(context)
                }
            )
        }

        SettingsItem.BACKUP_BATTERY_NOT_LOW -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "低电量时自动暂停备份",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (requireBatteryNotLow) "系统处于低电量模式时暂停后台备份" else "忽略系统电量限制继续备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = requireBatteryNotLow,
                onCheckedChange = {
                    settings.setRequireBatteryNotLow(it)
                    app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker.scheduleBackup(context)
                }
            )
        }

        SettingsItem.BACKUP_CONCURRENT_LIMIT -> DropdownRow(
            title = "多线程并发备份",
            subtitle = "同时上传的照片或视频数量（最多 3 项）",
            current = "$maxConcurrentUploads 项" + when (maxConcurrentUploads) {
                1 -> " (串行)"
                2 -> " (推荐)"
                3 -> " (并行)"
                else -> ""
            },
            options = listOf(
                1 to "1 项 (串行上传)",
                2 to "2 项 (均衡推荐)",
                3 to "3 项 (高速并行)"
            ),
            onSelect = { settings.setMaxConcurrentUploads(it) }
        )

        SettingsItem.BACKUP_NOTIFICATION -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "显示备份通知栏进度",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (showBackupNotification) "后台备份时在通知栏实时展示上传进度" else "静默后台备份，不在通知栏显示进度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = showBackupNotification,
                onCheckedChange = { settings.setShowBackupNotification(it) }
            )
        }

        SettingsItem.ICLOUD_WEB -> {
            var showICloudWebDialog by remember { mutableStateOf(false) }
            val isChinaMainland = remember(context) {
                (context.applicationContext as? IClouDroidApp)
                    ?.container?.sessionManager?.isChinaMainland() ?: true
            }
            val targetUrl = if (isChinaMainland) "https://www.icloud.com.cn" else "https://www.icloud.com"

            ActionRow(
                title = "访问 iCloud 网页版",
                subtitle = if (isChinaMainland) "https://www.icloud.com.cn" else "https://www.icloud.com",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = {
                    showICloudWebDialog = true
                }
            )

            if (showICloudWebDialog) {
                ICloudWebDialog(
                    url = targetUrl,
                    onDismiss = { showICloudWebDialog = false }
                )
            }
        }

        SettingsItem.NAV_BAR_ITEMS -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                app.acloudroid.qwq.ui.navigation.AppDestination.entries.forEach { dest ->
                    val isPhotos = dest == app.acloudroid.qwq.ui.navigation.AppDestination.Photos
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = dest.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = dest.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isPhotos || dest in navBarDestinations,
                            enabled = !isPhotos,
                            onCheckedChange = { checked ->
                                val newSet = if (checked) {
                                    navBarDestinations + dest
                                } else {
                                    navBarDestinations - dest
                                }
                                if (newSet.isNotEmpty()) {
                                    settings.setNavBarDestinations(newSet)
                                }
                            }
                        )
                    }
                }
            }
        }

        SettingsItem.THEME_MODE -> DropdownRow(
            title = "主题外观",
            subtitle = "切换应用深色、浅色或跟随系统",
            current = themeMode.label,
            options = ThemeMode.entries.map { it to it.label },
            onSelect = { settings.setThemeMode(it) }
        )

        SettingsItem.DYNAMIC_COLOR -> {
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monet 动态色彩",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    Text(
                        text = if (supported) "从系统壁纸提取主色调 (Android 12+)" else "当前系统版本不支持",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = useDynamicColor && supported,
                    enabled = supported,
                    onCheckedChange = { settings.setUseDynamicColor(it) }
                )
            }
        }

        SettingsItem.MONTH_COVER_PREVIEW -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "月份封面预览",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    // States the non-obvious half: turning this off also stops the download, so
                    // a user reaching for it to save space gets what they expect.
                    text = if (monthCoverEnabled) {
                        "跳转面板显示每月照片封面；关闭后不再下载封面"
                    } else {
                        "已关闭，月份以纯文本显示且不占用存储"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = monthCoverEnabled,
                onCheckedChange = { settings.setMonthCoverEnabled(it) }
            )
        }

        SettingsItem.CLOUD_MEDIA_PROVIDER -> {
            var showShizukuDialog by remember { mutableStateOf(false) }

            ActionRow(
                title = "云端媒体提供方",
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "通过 Shizuku 一键激活或前往系统设置"
                } else {
                    "需要 Android 13 (API 33) 及以上系统支持"
                },
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = {
                    showShizukuDialog = true
                }
            )

            if (showShizukuDialog) {
                CloudMediaActivationDialog(
                    onDismiss = { showShizukuDialog = false },
                    onOpenSystemSettings = {
                        showShizukuDialog = false
                        try {
                            val intent = Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }
                )
            }
        }

        SettingsItem.FULL_SCAN -> ActionRow(
            title = "全盘扫描照片库",
            subtitle = if (isFullScanRunning) "正在扫描…" else "清理在其它设备上已删除的照片",
            enabled = !isFullScanRunning,
            trailing = {
                if (isFullScanRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            },
            onClick = onRequestFullScan
        )

        SettingsItem.SCAN_FREQUENCY -> DropdownRow(
            title = "自动扫描频率",
            subtitle = "关闭时常驻后台的自动扫描不会运行",
            current = frequency.label,
            options = ScanFrequency.entries.map { it to it.label },
            onSelect = onFrequencyChanged
        )

        SettingsItem.CUSTOM_INTERVAL -> DropdownRow(
            title = "自定义周期",
            subtitle = if (frequency == ScanFrequency.CUSTOM) {
                "每 $customDays 天执行一次"
            } else {
                "仅在频率选择「自定义」时生效"
            },
            enabled = frequency == ScanFrequency.CUSTOM,
            current = "$customDays 天",
            options = CUSTOM_DAY_OPTIONS.map { it to "$it 天" },
            onSelect = { onCustomIntervalChanged(it) }
        )

        SettingsItem.DRIVE_DISPLAY_MODE -> {
            val displayMode by settings.driveDisplayMode.collectAsStateWithLifecycle()
            DropdownRow(
                title = "云盘显示模式",
                subtitle = "切换文件与文件夹在云盘中的显示样式",
                current = displayMode.label,
                options = DriveDisplayMode.entries.map { it to it.label },
                onSelect = { settings.setDriveDisplayMode(it) }
            )
        }

        SettingsItem.DRIVE_SORT_FIELD -> {
            val sortField by settings.driveSortField.collectAsStateWithLifecycle()
            DropdownRow(
                title = "默认排序字段",
                subtitle = "设置云盘文件与文件夹的默认排序依据",
                current = sortField.label,
                options = DriveSortField.entries.map { it to it.label },
                onSelect = { settings.setDriveSortField(it) }
            )
        }

        SettingsItem.DRIVE_SORT_ORDER -> {
            val sortOrder by settings.driveSortOrder.collectAsStateWithLifecycle()
            DropdownRow(
                title = "默认排序方向",
                subtitle = "设置云盘文件与文件夹的默认排序升降序",
                current = sortOrder.label,
                options = DriveSortOrder.entries.map { it to it.label },
                onSelect = { settings.setDriveSortOrder(it) }
            )
        }

        SettingsItem.AUTO_CHECK_UPDATE -> {
            val autoCheck by settings.autoCheckUpdate.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settings.setAutoCheckUpdate(!autoCheck) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动检查更新",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "应用启动时自动检测 GitHub Releases 最新版本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoCheck,
                    onCheckedChange = { settings.setAutoCheckUpdate(it) }
                )
            }
        }

        SettingsItem.ABOUT -> ActionRow(
            title = "关于",
            subtitle = "关于 AClouDroid",
            trailing = {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = onOpenAbout
        )
    }
}

/** Offered intervals. Free-form entry adds a text field for two days a year of use cases. */
private val CUSTOM_DAY_OPTIONS = listOf(1, 2, 3, 7, 14, 30, 60, 90, 180)

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun <T> DropdownRow(
    title: String,
    subtitle: String,
    current: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = current,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}

@Composable
private fun CloudMediaActivationDialog(
    onDismiss: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var isActivating by remember { mutableStateOf(false) }
    var activationStatus by remember { mutableStateOf<String?>(null) }
    val isShizukuRunning = app.acloudroid.qwq.provider.ShizukuMediaHelper.isShizukuAvailable()
    val hasPermission = app.acloudroid.qwq.provider.ShizukuMediaHelper.hasShizukuPermission()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统云端媒体设置") },
        text = {
            Column {
                Text(
                    text = "Android 系统对云端媒体提供方开启了白名单保护。您可以通过以下方式之一完成激活：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))

                if (isShizukuRunning) {
                    Text(
                        text = if (hasPermission) "✅ Shizuku 已连接并授权" else "⚠️ Shizuku 已运行，点击可请求授权",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "ℹ️ 未检测到 Shizuku 运行。您也可通过电脑执行 ADB 命令完成激活。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (activationStatus != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = activationStatus ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Copy ADB command
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("ADB Command", app.acloudroid.qwq.provider.ShizukuMediaHelper.adbCommand)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "已复制 ADB 命令到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("复制电脑端 ADB 激活命令")
                }
            }
        },
        confirmButton = {
            if (isShizukuRunning) {
                Button(
                    enabled = !isActivating,
                    onClick = {
                        if (!hasPermission) {
                            app.acloudroid.qwq.provider.ShizukuMediaHelper.requestShizukuPermission()
                        } else {
                            isActivating = true
                            activationStatus = "正在写入系统配置..."
                            scope.launch {
                                val result = app.acloudroid.qwq.provider.ShizukuMediaHelper.executeActivation()
                                isActivating = false
                                activationStatus = result.fold(
                                    onSuccess = { it },
                                    onFailure = { "失败: ${it.message}" }
                                )
                            }
                        }
                    }
                ) {
                    if (isActivating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (!hasPermission) "请求 Shizuku 授权" else "通过 Shizuku 一键激活")
                }
            } else {
                Button(onClick = onOpenSystemSettings) {
                    Text("前往系统设置")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ICloudWebDialog(
    url: String,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "iCloud 网页版",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                app.acloudroid.qwq.ui.FeatureWebViewScreen(url = url)
            }
        }
    }
}

private const val REPOSITORY_URL = "https://github.com/CuteCatGYH/AClouDroid"

private fun getAppIconBitmap(context: android.content.Context): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 192
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 192
            val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSubScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val container = remember(context) { (context.applicationContext as IClouDroidApp).container }
    val updateRepository = container.updateRepository

    var isCheckingUpdate by remember { androidx.compose.runtime.mutableStateOf(false) }
    var updateDialogReleaseInfo by remember { androidx.compose.runtime.mutableStateOf<app.acloudroid.qwq.feature.update.data.ReleaseInfo?>(null) }

    val versionName = remember(context) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    val versionCode = remember(context) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    BackHandler(enabled = true) {
        onBack()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "关于",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            val appIconBitmap = remember(context) {
                getAppIconBitmap(context)
            }

            // App Icon & Name magnified and centered at top
            if (appIconBitmap != null) {
                Image(
                    bitmap = appIconBitmap,
                    contentDescription = "App Icon",
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(22.dp))
                )
            } else {
                Image(
                    painter = painterResource(id = app.acloudroid.qwq.R.mipmap.ic_launcher_foreground),
                    contentDescription = "App Icon",
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(22.dp))
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(id = app.acloudroid.qwq.R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "在 Android 手机上直接浏览与同步你的 iCloud！",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(36.dp))

            // Information list card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column {
                    // Version Item (Click to manually check update)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isCheckingUpdate) {
                                scope.launch {
                                    isCheckingUpdate = true
                                    android.widget.Toast.makeText(
                                        context,
                                        "正在检查更新...",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    val result = updateRepository.checkLatestReleaseIfDue(
                                        currentVersion = versionName,
                                        force = true
                                    )
                                    isCheckingUpdate = false
                                    result.fold(
                                        onSuccess = { releaseInfo ->
                                            if (releaseInfo != null) {
                                                updateDialogReleaseInfo = releaseInfo
                                            } else {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "当前已是最新版本 (v$versionName)",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        onFailure = { error ->
                                            android.widget.Toast.makeText(
                                                context,
                                                error.localizedMessage ?: "检查更新失败",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "版本号",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isCheckingUpdate) "正在检查更新..." else "v$versionName (Build $versionCode)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "检查更新",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // Repository Item
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    uriHandler.openUri(REPOSITORY_URL)
                                } catch (e: Exception) {
                                    try {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Repository URL", REPOSITORY_URL)
                                        clipboard.setPrimaryClip(clip)
                                        android.widget.Toast.makeText(context, "已复制仓库地址到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                                    } catch (ex: Exception) {
                                        android.widget.Toast.makeText(context, "无法打开网址: $REPOSITORY_URL", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "在 GitHub 上查看源代码",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = REPOSITORY_URL,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "打开仓库",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Diagnostic Log Section
            Text(
                text = "故障调试与诊断",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp)
            )

            val scope = rememberCoroutineScope()
            var isExportingLog by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column {
                    // Export Diagnostic Logs Item
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isExportingLog) {
                                isExportingLog = true
                                scope.launch {
                                    try {
                                        app.acloudroid.qwq.util.DiagnosticLogExporter.exportAndShareDiagnosticZip(context)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "导出诊断日志失败: ${e.message}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } finally {
                                        isExportingLog = false
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "导出诊断日志",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "打包导出 App 行为日志与 Logcat",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (isExportingLog) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "导出诊断日志",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // Clear Diagnostic Logs Item
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                app.acloudroid.qwq.util.DiagnosticLogExporter.clearAllLogs(context)
                                android.widget.Toast.makeText(
                                    context,
                                    "诊断日志已清空",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "清空诊断日志",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "清空本地保存的行为日志与 Logcat 缓冲区",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "清空诊断日志",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = "iClouDroid • CuteCatGYH",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    updateDialogReleaseInfo?.let { releaseInfo ->
        app.acloudroid.qwq.feature.update.ui.UpdateDialog(
            releaseInfo = releaseInfo,
            onDismiss = { updateDialogReleaseInfo = null },
            onConfirmUpdate = {
                updateRepository.openReleasePage(context, releaseInfo.releaseUrl)
            }
        )
    }
}
