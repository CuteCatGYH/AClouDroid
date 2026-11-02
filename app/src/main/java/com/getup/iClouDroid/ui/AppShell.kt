package com.getup.iClouDroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getup.iClouDroid.IClouDroidApp
import com.getup.iClouDroid.feature.photos.ui.PhotosScreen
import com.getup.iClouDroid.feature.photos.ui.PhotosViewModel
import com.getup.iClouDroid.feature.photos.work.PeriodicScanScheduler
import com.getup.iClouDroid.ui.navigation.AppDestination
import com.getup.iClouDroid.ui.settings.SettingsContainer

/**
 * Root container for every authenticated section.
 *
 * Authentication lives *above* this shell ([com.getup.iClouDroid.MainActivity]), so a feature
 * composable here can assume a valid session exists. Switching sections is plain Compose state
 * rather than a nav graph — with a handful of top-level tabs that is simpler and keeps each
 * feature's back stack out of the shell's concerns. Swap in Navigation Compose if nested
 * per-feature routes become necessary.
 *
 * Settings is a *full-screen overlay* rather than a fourth tab: it is not a place you browse to and
 * from, and giving it a permanent slot would dilute the three real sections while the set of
 * settings is still small.
 */
@Composable
fun AppShell(
    photosViewModel: PhotosViewModel,
    localAlbumsViewModel: com.getup.iClouDroid.feature.local.ui.LocalAlbumsViewModel,
    onOpenAccountInfo: () -> Unit,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass? = null,
    openSettings: Boolean = false,
    onOpenSettingsHandled: () -> Unit = {}
) {
    var selected by rememberSaveable { mutableStateOf(AppDestination.Photos) }

    // True while the photo viewer is full-screen. The bottom/side navigation is removed then:
    // the viewer is only "full screen" if nothing else is drawing below or beside it.
    var viewerOpen by rememberSaveable { mutableStateOf(false) }

    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // The entry point (账号信息 sheet) lives in MainActivity, above this shell, so an open request
    // arrives as a one-shot flag that is consumed here.
    androidx.compose.runtime.LaunchedEffect(openSettings) {
        if (openSettings) {
            settingsOpen = true
            onOpenSettingsHandled()
        }
    }

    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as IClouDroidApp).container }
    val settings = container.appSettings

    // Settings live here, not inside `SettingsScreen`, because they outlive it: scheduling must be
    // applied even if the user navigates straight back out.
    val frequency by settings.scanFrequency.collectAsStateWithLifecycle()
    val syncMessage by photosViewModel.syncMessage.collectAsStateWithLifecycle()
    val isSyncing by photosViewModel.isSyncing.collectAsStateWithLifecycle()
    val scanProgress by photosViewModel.scanProgress.collectAsStateWithLifecycle()
    val navBarDestinations by settings.navBarDestinations.collectAsStateWithLifecycle()

    // If the currently selected destination is no longer enabled, switch to the first enabled one
    androidx.compose.runtime.LaunchedEffect(navBarDestinations) {
        if (selected !in navBarDestinations && navBarDestinations.isNotEmpty()) {
            selected = navBarDestinations.first()
        }
    }

    val isCompact = windowSizeClass == null || windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    val activeDestinations = remember(navBarDestinations) {
        AppDestination.entries.filter { it in navBarDestinations }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isCompact) {
            // Compact Mode (e.g. phones in portrait): Bottom NavigationBar
            val navBarHeight = 80.dp
            val navBarPadding = if (viewerOpen || settingsOpen) 0.dp else navBarHeight

            Box(modifier = Modifier.fillMaxSize()) {
                MainContentArea(
                    selected = selected,
                    photosViewModel = photosViewModel,
                    localAlbumsViewModel = localAlbumsViewModel,
                    onOpenAccountInfo = onOpenAccountInfo,
                    viewerOpen = viewerOpen,
                    onViewerOpenChange = { viewerOpen = it },
                    contentPadding = PaddingValues(bottom = navBarPadding)
                )

                if (!viewerOpen && !settingsOpen) {
                    NavigationBar(modifier = Modifier.align(Alignment.BottomCenter)) {
                        activeDestinations.forEach { destination ->
                            NavigationBarItem(
                                selected = selected == destination,
                                onClick = { selected = destination },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label
                                    )
                                },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            }
        } else {
            // Medium / Expanded Mode (e.g. foldables unfolded, tablets, landscape): Side NavigationRail
            Row(modifier = Modifier.fillMaxSize()) {
                if (!viewerOpen && !settingsOpen) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        Spacer(modifier = Modifier.weight(1f))
                        activeDestinations.forEach { destination ->
                            NavigationRailItem(
                                selected = selected == destination,
                                onClick = { selected = destination },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label
                                    )
                                },
                                label = { Text(destination.label) }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    MainContentArea(
                        selected = selected,
                        photosViewModel = photosViewModel,
                        localAlbumsViewModel = localAlbumsViewModel,
                        onOpenAccountInfo = onOpenAccountInfo,
                        viewerOpen = viewerOpen,
                        onViewerOpenChange = { viewerOpen = it },
                        contentPadding = PaddingValues()
                    )
                }
            }
        }

        if (settingsOpen) {
            SettingsContainer(
                onClose = { settingsOpen = false },
                onStartFullScan = { photosViewModel.startFullScan() },
                settings = settings,
                fullScanMessage = syncMessage,
                isFullScanRunning = isSyncing || scanProgress.isScanning || scanProgress.isCachingCovers,
                onFrequencyChanged = { newFrequency ->
                    settings.setScanFrequency(newFrequency)
                    PeriodicScanScheduler.apply(context, settings)
                },
                onCustomIntervalChanged = { days ->
                    settings.setCustomIntervalDays(days)
                    if (frequency == com.getup.iClouDroid.data.settings.ScanFrequency.CUSTOM) {
                        PeriodicScanScheduler.apply(context, settings)
                    }
                }
            )
        }
    }

    /**
     * Keeps WorkManager in step with the stored preference.
     *
     * Runs on first composition so a schedule set in a previous session is re-asserted even if the
     * app was reinstalled or WorkManager dropped the job; the `UPDATE` policy makes this idempotent.
     */
    androidx.compose.runtime.LaunchedEffect(Unit) {
        PeriodicScanScheduler.apply(context, settings)
    }
}

@Composable
private fun MainContentArea(
    selected: AppDestination,
    photosViewModel: PhotosViewModel,
    localAlbumsViewModel: com.getup.iClouDroid.feature.local.ui.LocalAlbumsViewModel,
    onOpenAccountInfo: () -> Unit,
    viewerOpen: Boolean,
    onViewerOpenChange: (Boolean) -> Unit,
    contentPadding: PaddingValues
) {
    when (selected) {
        AppDestination.Photos -> PhotosScreen(
            viewModel = photosViewModel,
            onOpenAccountInfo = onOpenAccountInfo,
            isViewerOpen = viewerOpen,
            onViewerOpenChange = onViewerOpenChange,
            contentPadding = contentPadding
        )
        AppDestination.Albums -> {
            com.getup.iClouDroid.feature.local.ui.LocalAlbumsScreen(
                viewModel = localAlbumsViewModel,
                contentPadding = contentPadding
            )
        }
        // Native browser (D2). Replaces the old WebView placeholder: a real directory tree
        // cannot be rendered usefully through the web UI, and the point of the Drive work is
        // to stop relying on it.
        AppDestination.Drive -> com.getup.iClouDroid.feature.drive.ui.DriveEntry(
            contentPadding = contentPadding
        )
        AppDestination.Notes -> FeatureWebViewScreen(url = "https://www.icloud.com.cn/notes/")
        AppDestination.Reminders -> FeatureWebViewScreen(url = "https://www.icloud.com.cn/reminders/")
        AppDestination.FindMy -> FeatureWebViewScreen(url = "https://www.icloud.com.cn/find/")
    }
}
