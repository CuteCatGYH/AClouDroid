package app.acloudroid.qwq.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.R
import app.acloudroid.qwq.data.settings.AppLanguage
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import app.acloudroid.qwq.feature.photos.data.LibraryEstimate
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadStatus
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadTaskEntity
import kotlinx.coroutines.delay
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
    onReLogin: () -> Unit = {},
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(),
    modifier: Modifier = Modifier
) {
    val frequency by settings.scanFrequency.collectAsStateWithLifecycle()
    val customDays by settings.customIntervalDays.collectAsStateWithLifecycle()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val useDynamicColor by settings.useDynamicColor.collectAsStateWithLifecycle()
    val showAccountAvatar by settings.showAccountAvatar.collectAsStateWithLifecycle()
    val monthCoverEnabled by settings.monthCoverEnabled.collectAsStateWithLifecycle()
    val navBarDestinations by settings.navBarDestinations.collectAsStateWithLifecycle()
    val allowCellularUpload by settings.allowCellularUpload.collectAsStateWithLifecycle()
    val requireCharging by settings.requireCharging.collectAsStateWithLifecycle()
    val requireBatteryNotLow by settings.requireBatteryNotLow.collectAsStateWithLifecycle()
    val maxConcurrentUploads by settings.maxConcurrentUploads.collectAsStateWithLifecycle()
    val showBackupNotification by settings.showBackupNotification.collectAsStateWithLifecycle()
    val periodicBackupInterval by settings.periodicBackupInterval.collectAsStateWithLifecycle()
    val appLanguage by settings.appLanguage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(lifecycleState) {
        if (lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            settings.syncAppLanguageFromSystem()
        }
    }
    val batchDownloadManager = remember(context) {
        (context.applicationContext as IClouDroidApp).container.batchDownloadManager
    }
    val activeDownloadTask by batchDownloadManager.observeActiveTask().collectAsStateWithLifecycle(initialValue = null)

    var showScanWarning by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showCancelDownloadDialog by remember { mutableStateOf(false) }
    var showReloginDialog by remember { mutableStateOf(false) }
    var libraryEstimate by remember { mutableStateOf<LibraryEstimate?>(null) }
    var showAboutScreen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.settings_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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

                // Ongoing batch download progress banner
                activeDownloadTask?.takeIf {
                    it.status == BatchDownloadStatus.DOWNLOADING ||
                    it.status == BatchDownloadStatus.PAUSED ||
                    it.status == BatchDownloadStatus.PREPARING
                }?.let { task ->
                    BatchDownloadProgressCard(
                        task = task,
                        onPause = { scope.launch { batchDownloadManager.pause(task.batchId) } },
                        onResume = { scope.launch { batchDownloadManager.startOrResume(task.batchId) } },
                        onCancel = { showCancelDownloadDialog = true }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                SettingsCatalog.groups.forEach { group ->
                    Text(
                        text = stringResource(group.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)
                    )

                    if (group.descriptionRes != null) {
                        Text(
                            text = stringResource(group.descriptionRes),
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
                                    appLanguage = appLanguage,
                                    themeMode = themeMode,
                                    useDynamicColor = useDynamicColor,
                                    showAccountAvatar = showAccountAvatar,
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
                                    activeDownloadTask = activeDownloadTask,
                                    onRequestFullScan = { showScanWarning = true },
                                    onRequestDownloadAll = {
                                        val cur = activeDownloadTask
                                        if (cur != null && (cur.status == BatchDownloadStatus.DOWNLOADING || cur.status == BatchDownloadStatus.PAUSED)) {
                                            showCancelDownloadDialog = true
                                        } else {
                                            scope.launch {
                                                libraryEstimate = batchDownloadManager.estimateLibrary()
                                                showDownloadDialog = true
                                            }
                                        }
                                    },
                                    onFrequencyChanged = onFrequencyChanged,
                                    onCustomIntervalChanged = onCustomIntervalChanged,
                                    onRequestRelogin = { showReloginDialog = true },
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

            if (showDownloadDialog) {
                BatchDownloadPrecheckDialog(
                    estimate = libraryEstimate,
                    onConfirm = { chosenPath ->
                        showDownloadDialog = false
                        scope.launch {
                            batchDownloadManager.prepareAndStartBatch(chosenPath)
                        }
                    },
                    onDismiss = { showDownloadDialog = false }
                )
            }

            if (showCancelDownloadDialog) {
                val currentTask = activeDownloadTask
                BatchDownloadCancelDialog(
                    completedCount = currentTask?.successCount ?: 0,
                    targetPath = currentTask?.targetRelativePath ?: "DCIM/Camera",
                    onKeepAndCancel = {
                        showCancelDownloadDialog = false
                        currentTask?.let { t ->
                            scope.launch { batchDownloadManager.cancelAndRollback(t.batchId, deleteDownloaded = false) }
                        }
                    },
                    onDeleteAndCancel = {
                        showCancelDownloadDialog = false
                        currentTask?.let { t ->
                            scope.launch { batchDownloadManager.cancelAndRollback(t.batchId, deleteDownloaded = true) }
                        }
                    },
                    onDismiss = { showCancelDownloadDialog = false }
                )
            }
        }

        if (showReloginDialog) {
            AlertDialog(
                onDismissRequest = { showReloginDialog = false },
                title = {
                    Text(
                        text = stringResource(R.string.settings_dialog_re_login_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(text = stringResource(R.string.settings_dialog_re_login_msg))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showReloginDialog = false
                            onReLogin()
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.settings_dialog_re_login_confirm),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showReloginDialog = false }) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                }
            )
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
        title = { Text(stringResource(R.string.settings_full_scan_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_full_scan_dialog_desc_1)
                )
                Spacer(Modifier.height(8.dp))
                if (frequency != ScanFrequency.OFF) {
                    val cadenceDesc = when (frequency) {
                        ScanFrequency.DAILY -> stringResource(R.string.settings_scan_freq_daily)
                        ScanFrequency.WEEKLY -> stringResource(R.string.settings_scan_freq_weekly)
                        ScanFrequency.MONTHLY -> stringResource(R.string.settings_scan_freq_monthly)
                        ScanFrequency.CUSTOM -> stringResource(R.string.settings_scan_freq_custom, customDays)
                        ScanFrequency.OFF -> ""
                    }
                    Text(
                        stringResource(R.string.settings_full_scan_dialog_desc_2_on, cadenceDesc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    stringResource(R.string.settings_full_scan_dialog_desc_3),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_full_scan_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun SettingsRow(
    item: SettingsItem,
    settings: AppSettings,
    appLanguage: AppLanguage,
    themeMode: ThemeMode,
    useDynamicColor: Boolean,
    showAccountAvatar: Boolean,
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
    activeDownloadTask: BatchDownloadTaskEntity?,
    onRequestFullScan: () -> Unit,
    onRequestDownloadAll: () -> Unit,
    onFrequencyChanged: (ScanFrequency) -> Unit,
    onCustomIntervalChanged: (Int) -> Unit,
    onRequestRelogin: () -> Unit,
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
                                android.widget.Toast.makeText(context, context.getString(R.string.settings_item_cloud_media_toast_manual), android.widget.Toast.LENGTH_SHORT).show()
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
                        text = stringResource(R.string.settings_item_battery_opt),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isIgnoring) stringResource(R.string.settings_item_battery_opt_desc_on) else stringResource(R.string.settings_item_battery_opt_desc_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Text(
                        text = stringResource(R.string.settings_item_battery_opt_help),
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
                        text = stringResource(R.string.settings_item_keep_alive),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (keepBackgroundAlive) stringResource(R.string.settings_item_keep_alive_desc_on) else stringResource(R.string.settings_item_keep_alive_desc_off),
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
                title = stringResource(R.string.settings_item_backup_interval),
                subtitle = if (periodicBackupInterval == 0L) stringResource(R.string.settings_item_backup_interval_desc_immediate) else stringResource(R.string.settings_item_backup_interval_desc),
                current = when (periodicBackupInterval) {
                    0L -> stringResource(R.string.settings_backup_interval_immediate_short)
                    15L -> stringResource(R.string.settings_backup_interval_15m)
                    30L -> stringResource(R.string.settings_backup_interval_30m)
                    60L -> stringResource(R.string.settings_backup_interval_1h)
                    else -> stringResource(R.string.settings_backup_interval_custom_val, periodicBackupInterval)
                },
                options = listOf(
                    0L to stringResource(R.string.settings_backup_interval_immediate),
                    15L to stringResource(R.string.settings_backup_interval_15m),
                    30L to stringResource(R.string.settings_backup_interval_30m),
                    60L to stringResource(R.string.settings_backup_interval_1h),
                    -1L to stringResource(R.string.settings_backup_interval_custom)
                ) + (if (listOf(0L, 15L, 30L, 60L, -1L).contains(periodicBackupInterval)) emptyList() else listOf(periodicBackupInterval to stringResource(R.string.settings_backup_interval_custom_val, periodicBackupInterval))),
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
                    title = { Text(stringResource(R.string.settings_item_backup_interval)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.settings_item_backup_interval), style = MaterialTheme.typography.bodyMedium)
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
                        ) { Text(stringResource(R.string.action_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomIntervalDialog = false }) { Text(stringResource(R.string.action_cancel)) }
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
                    text = stringResource(R.string.settings_item_backup_cellular),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (allowCellularUpload) stringResource(R.string.settings_item_backup_cellular_desc_on) else stringResource(R.string.settings_item_backup_cellular_desc_off),
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
                    text = stringResource(R.string.settings_item_backup_charging),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (requireCharging) stringResource(R.string.settings_item_backup_charging_desc_on) else stringResource(R.string.settings_item_backup_charging_desc_off),
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
                    text = stringResource(R.string.settings_item_backup_battery),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (requireBatteryNotLow) stringResource(R.string.settings_item_backup_battery_desc_on) else stringResource(R.string.settings_item_backup_battery_desc_off),
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
            title = stringResource(R.string.settings_item_backup_concurrent),
            subtitle = stringResource(R.string.settings_item_backup_concurrent_desc, 3),
            current = stringResource(R.string.settings_item_backup_concurrent_val, maxConcurrentUploads) + when (maxConcurrentUploads) {
                1 -> " " + stringResource(R.string.settings_item_backup_concurrent_val_1)
                2 -> " " + stringResource(R.string.settings_item_backup_concurrent_val_2)
                3 -> " " + stringResource(R.string.settings_item_backup_concurrent_val_3)
                else -> ""
            },
            options = listOf(
                1 to stringResource(R.string.settings_item_backup_concurrent_val_1_full),
                2 to stringResource(R.string.settings_item_backup_concurrent_val_2_full),
                3 to stringResource(R.string.settings_item_backup_concurrent_val_3_full)
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
                    text = stringResource(R.string.settings_item_backup_notification),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (showBackupNotification) stringResource(R.string.settings_item_backup_notification_desc_on) else stringResource(R.string.settings_item_backup_notification_desc_off),
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
                title = stringResource(R.string.settings_group_quick_access),
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
                            text = stringResource(dest.labelRes),
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

        SettingsItem.APP_LANGUAGE -> {
            val systemDefaultLabel = stringResource(R.string.language_system_default)
            DropdownRow(
                title = stringResource(R.string.settings_item_language),
                subtitle = "",
                current = if (appLanguage == AppLanguage.SYSTEM) systemDefaultLabel else appLanguage.nativeName,
                options = AppLanguage.entries.map {
                    it to (if (it == AppLanguage.SYSTEM) systemDefaultLabel else it.nativeName)
                },
                onSelect = { settings.setAppLanguage(it) }
            )
        }

        SettingsItem.THEME_MODE -> DropdownRow(
            title = stringResource(R.string.theme_title),
            subtitle = "",
            current = stringResource(themeMode.labelRes),
            options = ThemeMode.entries.map { it to stringResource(it.labelRes) },
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
                        text = stringResource(R.string.theme_dynamic_color),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    Text(
                        text = if (supported) stringResource(R.string.theme_dynamic_color_desc) else stringResource(R.string.theme_dynamic_color_unsupported),
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

        SettingsItem.SHOW_ACCOUNT_AVATAR -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_item_show_account_avatar),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (showAccountAvatar) stringResource(R.string.settings_item_show_account_avatar_desc_on) else stringResource(R.string.settings_item_show_account_avatar_desc_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = showAccountAvatar,
                onCheckedChange = { settings.setShowAccountAvatar(it) }
            )
        }

        SettingsItem.MONTH_COVER_PREVIEW -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_item_month_cover),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    // States the non-obvious half: turning this off also stops the download, so
                    // a user reaching for it to save space gets what they expect.
                    text = if (monthCoverEnabled) {
                        stringResource(R.string.settings_item_month_cover_desc_on)
                    } else {
                        stringResource(R.string.settings_item_month_cover_desc_off)
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
                title = stringResource(R.string.settings_item_cloud_media),
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    stringResource(R.string.settings_item_cloud_media_desc_shizuku)
                } else {
                    stringResource(R.string.settings_item_cloud_media_desc_req)
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
            title = stringResource(R.string.settings_group_photos_library),
            subtitle = if (isFullScanRunning) stringResource(R.string.settings_item_full_scan_desc_scanning) else stringResource(R.string.settings_item_full_scan_desc_idle),
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

        SettingsItem.DOWNLOAD_ALL -> {
            val isDownloading = activeDownloadTask?.status == BatchDownloadStatus.DOWNLOADING
            val isPaused = activeDownloadTask?.status == BatchDownloadStatus.PAUSED
            val isPreparing = activeDownloadTask?.status == BatchDownloadStatus.PREPARING

            ActionRow(
                title = stringResource(R.string.settings_item_download_all),
                subtitle = when {
                    isPreparing -> stringResource(R.string.settings_item_download_all_desc_prep)
                    isDownloading && activeDownloadTask != null -> stringResource(R.string.settings_item_download_all_desc_dl, activeDownloadTask.targetRelativePath, activeDownloadTask.successCount, activeDownloadTask.totalCount)
                    isPaused && activeDownloadTask != null -> stringResource(R.string.settings_item_download_all_desc_pause, activeDownloadTask.successCount, activeDownloadTask.totalCount)
                    else -> stringResource(R.string.settings_item_download_all_desc_idle)
                },
                enabled = true,
                trailing = {
                    if (isDownloading || isPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                },
                onClick = onRequestDownloadAll
            )
        }

        SettingsItem.RE_LOGIN -> {
            ActionRow(
                title = stringResource(R.string.settings_item_re_login),
                subtitle = stringResource(R.string.settings_item_re_login_desc),
                enabled = true,
                trailing = {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                },
                onClick = onRequestRelogin
            )
        }

        SettingsItem.SCAN_FREQUENCY -> DropdownRow(
            title = stringResource(R.string.settings_item_scan_freq_title_short),
            subtitle = stringResource(R.string.settings_item_scan_freq_desc_off),
            current = stringResource(frequency.labelRes),
            options = ScanFrequency.entries.map { it to stringResource(it.labelRes) },
            onSelect = onFrequencyChanged
        )

        SettingsItem.CUSTOM_INTERVAL -> DropdownRow(
            title = stringResource(R.string.settings_item_custom_interval),
            subtitle = if (frequency == ScanFrequency.CUSTOM) {
                stringResource(R.string.settings_item_custom_interval_desc_on, customDays)
            } else {
                stringResource(R.string.settings_item_custom_interval_desc_off)
            },
            enabled = frequency == ScanFrequency.CUSTOM,
            current = stringResource(R.string.settings_item_custom_interval_val, customDays),
            options = CUSTOM_DAY_OPTIONS.map { it to stringResource(R.string.settings_item_custom_interval_val, it) },
            onSelect = { onCustomIntervalChanged(it) }
        )

        SettingsItem.DRIVE_DISPLAY_MODE -> {
            val displayMode by settings.driveDisplayMode.collectAsStateWithLifecycle()
            DropdownRow(
                title = stringResource(R.string.settings_item_drive_mode_title),
                subtitle = stringResource(R.string.settings_item_drive_mode_desc),
                current = stringResource(displayMode.labelRes),
                options = DriveDisplayMode.entries.map { it to stringResource(it.labelRes) },
                onSelect = { settings.setDriveDisplayMode(it) }
            )
        }

        SettingsItem.DRIVE_SORT_FIELD -> {
            val sortField by settings.driveSortField.collectAsStateWithLifecycle()
            DropdownRow(
                title = stringResource(R.string.settings_item_drive_sort_field_title),
                subtitle = stringResource(R.string.settings_item_drive_sort_field_desc),
                current = stringResource(sortField.labelRes),
                options = DriveSortField.entries.map { it to stringResource(it.labelRes) },
                onSelect = { settings.setDriveSortField(it) }
            )
        }

        SettingsItem.DRIVE_SORT_ORDER -> {
            val sortOrder by settings.driveSortOrder.collectAsStateWithLifecycle()
            DropdownRow(
                title = stringResource(R.string.settings_item_drive_sort_order_title),
                subtitle = stringResource(R.string.settings_item_drive_sort_order_desc),
                current = stringResource(sortOrder.labelRes),
                options = DriveSortOrder.entries.map { it to stringResource(it.labelRes) },
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
                        text = stringResource(R.string.settings_item_auto_update_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_item_auto_update_desc),
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
            title = stringResource(R.string.settings_item_about_title),
            subtitle = stringResource(R.string.settings_item_about_desc),
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
    subtitle: String = "",
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
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
    var isShizukuRunning by remember { mutableStateOf(app.acloudroid.qwq.provider.ShizukuMediaHelper.isShizukuAvailable()) }
    var hasPermission by remember { mutableStateOf(app.acloudroid.qwq.provider.ShizukuMediaHelper.hasShizukuPermission()) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        val binderListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
            isShizukuRunning = app.acloudroid.qwq.provider.ShizukuMediaHelper.isShizukuAvailable()
            hasPermission = app.acloudroid.qwq.provider.ShizukuMediaHelper.hasShizukuPermission()
        }
        val permissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1001) {
                isShizukuRunning = app.acloudroid.qwq.provider.ShizukuMediaHelper.isShizukuAvailable()
                hasPermission = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }

        app.acloudroid.qwq.provider.ShizukuMediaHelper.addBinderReceivedListener(binderListener)
        app.acloudroid.qwq.provider.ShizukuMediaHelper.addPermissionResultListener(permissionListener)

        onDispose {
            app.acloudroid.qwq.provider.ShizukuMediaHelper.removeBinderReceivedListener(binderListener)
            app.acloudroid.qwq.provider.ShizukuMediaHelper.removePermissionResultListener(permissionListener)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cloud_media_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_cloud_media_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))

                if (isShizukuRunning) {
                    Text(
                        text = if (hasPermission) stringResource(R.string.settings_cloud_media_shizuku_auth) else stringResource(R.string.settings_cloud_media_shizuku_req),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_cloud_media_shizuku_missing),
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
                        android.widget.Toast.makeText(context, context.getString(R.string.settings_cloud_media_adb_copied), android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_cloud_media_adb_copy_btn))
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
                            activationStatus = context.getString(R.string.settings_cloud_media_writing)
                            scope.launch {
                                val result = app.acloudroid.qwq.provider.ShizukuMediaHelper.executeActivation()
                                isActivating = false
                                activationStatus = result.fold(
                                    onSuccess = { it },
                                    onFailure = { context.getString(R.string.settings_cloud_media_failed, it.message) }
                                )
                            }
                        }
                    }
                ) {
                    if (isActivating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (!hasPermission) stringResource(R.string.settings_cloud_media_req_btn) else stringResource(R.string.settings_cloud_media_activate_btn))
                }
            } else {
                Button(onClick = onOpenSystemSettings) {
                    Text(stringResource(R.string.settings_cloud_media_go_settings_btn))
                }
            }
        },
        dismissButton = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (isShizukuRunning) {
                    TextButton(onClick = onOpenSystemSettings) {
                        Text(stringResource(R.string.settings_cloud_media_go_settings_btn))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
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
                                text = stringResource(R.string.settings_icloud_web_title),
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
                                contentDescription = stringResource(R.string.action_close)
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
                        text = stringResource(R.string.settings_about_header),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                text = stringResource(R.string.settings_about_slogan),
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
                                        context.getString(R.string.settings_about_check_update),
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
                                                    context.getString(R.string.settings_about_up_to_date, versionName),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        onFailure = { error ->
                                            android.widget.Toast.makeText(
                                                context,
                                                error.localizedMessage ?: context.getString(R.string.settings_about_update_failed),
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
                                text = stringResource(R.string.settings_about_version),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isCheckingUpdate) stringResource(R.string.settings_about_check_update) else "v$versionName (Build $versionCode)",
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
                                text = stringResource(R.string.settings_about_check_btn),
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
                                        android.widget.Toast.makeText(context, context.getString(R.string.settings_about_repo_copied), android.widget.Toast.LENGTH_SHORT).show()
                                    } catch (ex: Exception) {
                                        android.widget.Toast.makeText(context, context.getString(R.string.settings_about_repo_failed, REPOSITORY_URL), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_about_repo_btn),
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
                            contentDescription = stringResource(R.string.settings_about_repo_icon),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Diagnostic Log Section
            Text(
                text = stringResource(R.string.settings_diag_title),
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
                                            context.getString(R.string.settings_diag_export_failed, e.message),
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
                                text = stringResource(R.string.settings_diag_export_btn),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_diag_export_desc),
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
                                contentDescription = stringResource(R.string.settings_diag_export_icon),
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
                                    context.getString(R.string.settings_diag_clear_toast),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_diag_clear_btn),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_diag_clear_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.settings_diag_clear_icon),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = "AClouDroid • CuteCatGYH",
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

@Composable
private fun BatchDownloadProgressCard(
    task: BatchDownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val isPaused = task.status == BatchDownloadStatus.PAUSED
    val isPreparing = task.status == BatchDownloadStatus.PREPARING
    val progress = if (task.totalCount > 0) {
        task.successCount.toFloat() / task.totalCount.toFloat()
    } else {
        0f
    }
    val percent = (progress * 100).toInt().coerceIn(0, 100)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isPreparing) stringResource(R.string.settings_dl_progress_prep) else if (isPaused) stringResource(R.string.settings_dl_progress_pause) else stringResource(R.string.settings_dl_progress_active, percent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_dl_progress_loc, task.targetRelativePath, task.successCount, task.totalCount) +
                    (if (task.failedCount > 0) stringResource(R.string.settings_dl_progress_loc_failed, task.failedCount) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )

            Spacer(Modifier.height(8.dp))

            if (isPreparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isPreparing) {
                    if (isPaused) {
                        TextButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_dl_resume))
                        }
                    } else {
                        TextButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_dl_pause))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                TextButton(
                    onClick = onCancel,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_dl_stop))
                }
            }
        }
    }
}

@Composable
private fun BatchDownloadPrecheckDialog(
    estimate: LibraryEstimate?,
    onConfirm: (selectedPath: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedPathOption by remember { mutableStateOf("DCIM/Camera") }
    var customPickedPath by remember { mutableStateOf<String?>(null) }
    var isCustomSelected by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val extractedPath = if (docId != null && docId.startsWith("primary:")) {
                docId.removePrefix("primary:")
            } else {
                treeUri.lastPathSegment?.substringAfterLast(":")
            }?.trim()?.trim('/')

            if (!extractedPath.isNullOrBlank()) {
                customPickedPath = extractedPath
                isCustomSelected = true
            }
        }
    }

    var countdownSeconds by remember { mutableStateOf(5) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (countdownSeconds > 0) {
            delay(1000L)
            countdownSeconds -= 1
        }
    }

    val finalPath = if (isCustomSelected && !customPickedPath.isNullOrBlank()) {
        customPickedPath!!
    } else {
        selectedPathOption
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_dl_title), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (estimate != null) {
                    Text(
                        text = stringResource(R.string.settings_dl_est_count, estimate.totalCount, formatDownloadSize(estimate.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_dl_est_avail, formatDownloadSize(estimate.availableBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (estimate.availableBytes < estimate.totalBytes) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    if (estimate.availableBytes < estimate.totalBytes) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_dl_est_warn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(stringResource(R.string.settings_dl_est_calc), style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.settings_dl_loc_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(4.dp))

                // Option 1: DCIM/Camera (Default)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isCustomSelected = false
                            selectedPathOption = "DCIM/Camera"
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !isCustomSelected && selectedPathOption == "DCIM/Camera",
                        onClick = {
                            isCustomSelected = false
                            selectedPathOption = "DCIM/Camera"
                        }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.settings_dl_loc_dcim_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.settings_dl_loc_dcim_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Option 2: DCIM/iCloud
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isCustomSelected = false
                            selectedPathOption = "DCIM/iCloud"
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !isCustomSelected && selectedPathOption == "DCIM/iCloud",
                        onClick = {
                            isCustomSelected = false
                            selectedPathOption = "DCIM/iCloud"
                        }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("DCIM/iCloud", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.settings_dl_loc_pic_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Option 3: Pictures/iCloud
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isCustomSelected = false
                            selectedPathOption = "Pictures/iCloud"
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !isCustomSelected && selectedPathOption == "Pictures/iCloud",
                        onClick = {
                            isCustomSelected = false
                            selectedPathOption = "Pictures/iCloud"
                        }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Pictures/iCloud", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.settings_dl_loc_pic_desc_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Option 4: Pick via system file picker
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (customPickedPath.isNullOrBlank()) {
                                folderPickerLauncher.launch(null)
                            } else {
                                isCustomSelected = true
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isCustomSelected && !customPickedPath.isNullOrBlank(),
                        onClick = {
                            if (customPickedPath.isNullOrBlank()) {
                                folderPickerLauncher.launch(null)
                            } else {
                                isCustomSelected = true
                            }
                        }
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        val pathStr = customPickedPath.orEmpty()
                        Text(
                            text = if (customPickedPath.isNullOrBlank()) stringResource(R.string.settings_dl_loc_custom_title_empty) else stringResource(R.string.settings_dl_loc_custom_title_picked, pathStr),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCustomSelected && !customPickedPath.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (customPickedPath.isNullOrBlank()) stringResource(R.string.settings_dl_loc_custom_desc_empty) else stringResource(R.string.settings_dl_loc_custom_desc_picked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!customPickedPath.isNullOrBlank()) {
                        TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Text(stringResource(R.string.settings_dl_loc_custom_change))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.settings_dl_meta_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_dl_wifi_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(
                enabled = countdownSeconds == 0 && (estimate == null || estimate.totalCount > 0) && (!isCustomSelected || !customPickedPath.isNullOrBlank()),
                onClick = { onConfirm(finalPath) }
            ) {
                Text(
                    if (countdownSeconds > 0) stringResource(R.string.settings_dl_start_countdown, countdownSeconds) else stringResource(R.string.settings_dl_start)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun BatchDownloadCancelDialog(
    completedCount: Int,
    targetPath: String,
    onKeepAndCancel: () -> Unit,
    onDeleteAndCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_dl_stop_title), fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_dl_stop_desc, completedCount, targetPath),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onKeepAndCancel() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.settings_dl_stop_keep_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.settings_dl_stop_keep_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeleteAndCancel() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.settings_dl_stop_delete_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.settings_dl_stop_delete_desc, completedCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dl_stop_back))
            }
        }
    )
}

private fun formatDownloadSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.getDefault(), "%.2f GB", gb)
}

