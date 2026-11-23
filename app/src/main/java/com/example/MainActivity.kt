package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.aare.service.AareRuntimeStore
import com.example.aare.transport.ConnectionMode
import com.example.aare.transport.UsbAoapTransport
import com.example.ui.AareMainViewModel
import com.example.ui.screens.AppsScreen
import com.example.ui.screens.CertificatesScreen
import com.example.ui.screens.CockpitScreen
import com.example.ui.screens.ReceiverScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AarePlusTheme
import com.example.ui.theme.ThemeMode

private object NavRoutes {
    const val COCKPIT = "cockpit"
    const val RECEIVER = "receiver"
    const val APPS = "apps"
    const val CERTIFICATES = "certificates"
    const val SETTINGS = "settings"
}

private data class NavDestinationItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

class MainActivity : ComponentActivity() {

    private val viewModel: AareMainViewModel by viewModels()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbAoapTransport.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    AareRuntimeStore.log(
                        "USB",
                        if (granted) "Permission USB AOAP accordée" else "Permission USB AOAP refusée",
                        isError = !granted
                    )
                    viewModel.refreshUsbAccessoryStatus()
                    if (granted) {
                        viewModel.setConnectionMode(ConnectionMode.USB_AOAP)
                        viewModel.startOrStopReceiver()
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    AareRuntimeStore.log("USB", "Accessoire USB débranché")
                    viewModel.refreshUsbAccessoryStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction(UsbAoapTransport.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        handleUsbIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val runtime by viewModel.aareRuntime.collectAsStateWithLifecycle()
            val weather by viewModel.weatherState.collectAsStateWithLifecycle()
            val media by viewModel.mediaState.collectAsStateWithLifecycle()
            val notifications by viewModel.notifications.collectAsStateWithLifecycle()
            val apps by viewModel.apps.collectAsStateWithLifecycle()
            val certState by viewModel.certState.collectAsStateWithLifecycle()
            val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                viewModel.refreshWeather()
            }

            AarePlusTheme(
                themeMode = settings.themeMode,
                carNightModeActive = runtime.carHeadlightsOn
            ) {
                AarePlusShell(
                    toastMessage = toastMessage,
                    onConsumeToast = viewModel::clearToast,
                    content = { navController, currentRoute, navigateTo ->
                        NavHost(
                            navController = navController,
                            startDestination = NavRoutes.COCKPIT,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(NavRoutes.COCKPIT) {
                                CockpitScreen(
                                    settings = settings,
                                    runtime = runtime,
                                    weather = weather,
                                    media = media,
                                    notifications = notifications,
                                    apps = apps,
                                    onStartStopReceiver = viewModel::startOrStopReceiver,
                                    onOpenReceiverTab = { navigateTo(NavRoutes.RECEIVER) },
                                    onOpenAppsTab = { navigateTo(NavRoutes.APPS) },
                                    onRefreshWeather = { viewModel.refreshWeather() },
                                    onUpdateCity = viewModel::updateWeatherCity,
                                    onPlayPause = viewModel::togglePlayPause,
                                    onNextTrack = viewModel::skipNext,
                                    onPrevTrack = viewModel::skipPrevious,
                                    onOpenNotificationAccess = viewModel::openNotificationListenerSettings,
                                    onOpenDialer = viewModel::openDialer,
                                    onDismissNotification = viewModel::dismissNotification,
                                    onLaunchApp = viewModel::launchApp,
                                    onToggleThemeQuick = {
                                        val next = when (settings.themeMode) {
                                            ThemeMode.AUTO_CAR -> ThemeMode.DAY
                                            ThemeMode.DAY -> ThemeMode.NIGHT
                                            ThemeMode.NIGHT -> ThemeMode.AUTO_CAR
                                        }
                                        viewModel.setThemeMode(next)
                                    }
                                )
                            }
                            composable(NavRoutes.RECEIVER) {
                                ReceiverScreen(
                                    settings = settings,
                                    runtime = runtime,
                                    onSelectConnectionMode = viewModel::setConnectionMode,
                                    onSaveWirelessEndpoint = viewModel::setWirelessEndpoint,
                                    onStartStopReceiver = viewModel::startOrStopReceiver,
                                    onScanUsb = viewModel::refreshUsbAccessoryStatus,
                                    onToggleHeadlightsSim = viewModel::toggleCarHeadlightsSimulation,
                                    onClearLogs = viewModel::clearProtocolLogs
                                )
                            }
                            composable(NavRoutes.APPS) {
                                AppsScreen(
                                    apps = apps,
                                    pinnedPackages = settings.pinnedPackages,
                                    virtualDisplayId = runtime.virtualDisplayId,
                                    onLaunchApp = viewModel::launchApp,
                                    onTogglePin = viewModel::togglePinnedApp,
                                    onRefreshApps = viewModel::refreshApps
                                )
                            }
                            composable(NavRoutes.CERTIFICATES) {
                                CertificatesScreen(
                                    state = certState,
                                    onImportPkcs12 = viewModel::importPkcs12,
                                    onImportPemPair = viewModel::importPemPair,
                                    onResetFallback = viewModel::resetFallbackCertificate,
                                    onRunTlsTest = viewModel::runTlsSelfTest
                                )
                            }
                            composable(NavRoutes.SETTINGS) {
                                SettingsScreen(
                                    settings = settings,
                                    hasNotificationAccess = media.hasMediaSessionAccess,
                                    onSelectTheme = viewModel::setThemeMode,
                                    onSelectResolution = viewModel::setVideoResolution,
                                    onSelectFps = viewModel::setTargetFps,
                                    onSelectBitrate = viewModel::setBitrateMbps,
                                    onSelectAudioRouting = viewModel::setAudioRouting,
                                    onSaveWeatherCity = viewModel::updateWeatherCity,
                                    onOpenNotificationAccess = viewModel::openNotificationListenerSettings,
                                    onRequestPermissions = {
                                        val perms = buildList {
                                            add(Manifest.permission.RECORD_AUDIO)
                                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                add(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                add(Manifest.permission.BLUETOOTH_CONNECT)
                                            }
                                        }.toTypedArray()
                                        permissionLauncher.launch(perms)
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkMediaAccess()
        viewModel.refreshUsbAccessoryStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory = UsbAoapTransport.extractAccessoryFromIntent(intent)
            viewModel.refreshUsbAccessoryStatus()
            if (accessory != null) {
                AareRuntimeStore.log("USB", "Autoradio USB branché : ${accessory.manufacturer} ${accessory.model}")
                viewModel.setConnectionMode(ConnectionMode.USB_AOAP)
                if (UsbAoapTransport.hasPermission(this, accessory)) {
                    viewModel.startOrStopReceiver()
                } else {
                    UsbAoapTransport.requestPermission(this, accessory)
                }
            }
        }
    }
}

@Composable
private fun AarePlusShell(
    toastMessage: String?,
    onConsumeToast: () -> Unit,
    content: @Composable (
        navController: androidx.navigation.NavHostController,
        currentRoute: String?,
        navigateTo: (String) -> Unit
    ) -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            snackbarHostState.showSnackbar(toastMessage)
            onConsumeToast()
        }
    }

    val destinations = remember {
        listOf(
            NavDestinationItem(NavRoutes.COCKPIT, "Cockpit", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
            NavDestinationItem(NavRoutes.RECEIVER, "Récepteur", Icons.Filled.Usb, Icons.Outlined.Usb),
            NavDestinationItem(NavRoutes.APPS, "Apps", Icons.Filled.Apps, Icons.Outlined.Apps),
            NavDestinationItem(NavRoutes.CERTIFICATES, "Certificats", Icons.Filled.VerifiedUser, Icons.Outlined.VerifiedUser),
            NavDestinationItem(NavRoutes.SETTINGS, "Réglages", Icons.Filled.Settings, Icons.Outlined.Settings)
        )
    }

    val navigateTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (useRail) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 8.dp)
                    ) {
                        Spacer(Modifier.weight(0.5f))
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        content(navController, currentRoute, navigateTo)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    content(navController, currentRoute, navigateTo)
                }
            }
        }
    }
}
