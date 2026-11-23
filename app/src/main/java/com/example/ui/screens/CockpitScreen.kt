package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.aare.service.AareConnectionStage
import com.example.aare.service.AareRuntimeState
import com.example.aare.transport.ConnectionMode
import com.example.betterauto.apps.LaunchableApp
import com.example.betterauto.data.UserSettings
import com.example.betterauto.media.CarNotificationItem
import com.example.betterauto.media.MediaPlaybackUiState
import com.example.betterauto.weather.WeatherCondition
import com.example.betterauto.weather.WeatherState
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CockpitScreen(
    settings: UserSettings,
    runtime: AareRuntimeState,
    weather: WeatherState,
    media: MediaPlaybackUiState,
    notifications: List<CarNotificationItem>,
    apps: List<LaunchableApp>,
    onStartStopReceiver: () -> Unit,
    onOpenReceiverTab: () -> Unit,
    onOpenAppsTab: () -> Unit,
    onRefreshWeather: () -> Unit,
    onUpdateCity: (String) -> Unit,
    onPlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPrevTrack: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenDialer: () -> Unit,
    onDismissNotification: (String) -> Unit,
    onLaunchApp: (String) -> Unit,
    onToggleThemeQuick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCityDialog by remember { mutableStateOf(false) }

    val quickApps = remember(apps, settings.pinnedPackages) {
        if (settings.pinnedPackages.isNotEmpty()) {
            settings.pinnedPackages.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
        } else {
            apps.take(6)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWideCockpit = maxWidth >= 680.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isWideCockpit) {
                // BetterAuto-inspired Wide Automotive Split Grid
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ClockAndConnectionHeroCard(
                            settings = settings,
                            runtime = runtime,
                            onStartStopReceiver = onStartStopReceiver,
                            onOpenReceiverTab = onOpenReceiverTab,
                            onToggleThemeQuick = onToggleThemeQuick,
                            modifier = Modifier.weight(1.25f)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            WeatherWidgetCard(
                                weather = weather,
                                onRefresh = onRefreshWeather,
                                onEditCity = { showCityDialog = true }
                            )
                            MediaWidgetCard(
                                media = media,
                                onPlayPause = onPlayPause,
                                onNext = onNextTrack,
                                onPrev = onPrevTrack,
                                onRequestMediaAccess = onOpenNotificationAccess
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        NotificationsAndCallsCard(
                            notifications = notifications,
                            hasAccess = media.hasMediaSessionAccess,
                            onOpenDialer = onOpenDialer,
                            onDismiss = onDismissNotification,
                            onEnableAccess = onOpenNotificationAccess,
                            modifier = Modifier.weight(1f)
                        )
                        QuickAppDockCard(
                            apps = quickApps,
                            isVirtualDisplayActive = runtime.virtualDisplayId != null,
                            onLaunchApp = onLaunchApp,
                            onSeeAllApps = onOpenAppsTab,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                // Handheld / Compact Portrait Stack
                item {
                    ClockAndConnectionHeroCard(
                        settings = settings,
                        runtime = runtime,
                        onStartStopReceiver = onStartStopReceiver,
                        onOpenReceiverTab = onOpenReceiverTab,
                        onToggleThemeQuick = onToggleThemeQuick
                    )
                }
                item {
                    WeatherWidgetCard(
                        weather = weather,
                        onRefresh = onRefreshWeather,
                        onEditCity = { showCityDialog = true }
                    )
                }
                item {
                    MediaWidgetCard(
                        media = media,
                        onPlayPause = onPlayPause,
                        onNext = onNextTrack,
                        onPrev = onPrevTrack,
                        onRequestMediaAccess = onOpenNotificationAccess
                    )
                }
                item {
                    QuickAppDockCard(
                        apps = quickApps,
                        isVirtualDisplayActive = runtime.virtualDisplayId != null,
                        onLaunchApp = onLaunchApp,
                        onSeeAllApps = onOpenAppsTab
                    )
                }
                item {
                    NotificationsAndCallsCard(
                        notifications = notifications,
                        hasAccess = media.hasMediaSessionAccess,
                        onOpenDialer = onOpenDialer,
                        onDismiss = onDismissNotification,
                        onEnableAccess = onOpenNotificationAccess
                    )
                }
            }
        }
    }

    if (showCityDialog) {
        var cityInput by remember { mutableStateOf(weather.cityName) }
        AlertDialog(
            onDismissRequest = { showCityDialog = false },
            title = { Text("Ville météo") },
            text = {
                OutlinedTextField(
                    value = cityInput,
                    onValueChange = { cityInput = it },
                    label = { Text("Ville (ex : Lyon, Montréal)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateCity(cityInput)
                    showCityDialog = false
                }) { Text("Appliquer") }
            },
            dismissButton = {
                TextButton(onClick = { showCityDialog = false }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun ClockAndConnectionHeroCard(
    settings: UserSettings,
    runtime: AareRuntimeState,
    onStartStopReceiver: () -> Unit,
    onOpenReceiverTab: () -> Unit,
    onToggleThemeQuick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(15_000L)
        }
    }

    val timeLabel = remember(nowMillis) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMillis))
    }
    val dateLabel = remember(nowMillis) {
        SimpleDateFormat("EEEE d MMMM", Locale.FRENCH)
            .format(Date(nowMillis))
            .replaceFirstChar { it.uppercase() }
    }

    val isActive = runtime.stage != AareConnectionStage.DISCONNECTED &&
        runtime.stage != AareConnectionStage.ERROR

    val statusColor by animateColorAsState(
        targetValue = when (runtime.stage) {
            AareConnectionStage.CONNECTED -> MaterialTheme.colorScheme.tertiary
            AareConnectionStage.ERROR -> MaterialTheme.colorScheme.error
            AareConnectionStage.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.secondary
        },
        label = "statusColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_car_cockpit),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.18f)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = statusColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clickable(onClick = onOpenReceiverTab)
                            .testTag("cockpit_status_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (settings.connectionMode == ConnectionMode.USB_AOAP) {
                                    Icons.Rounded.Usb
                                } else {
                                    Icons.Rounded.Wifi
                                },
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${settings.connectionMode.label} · ${runtime.stage.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor
                            )
                        }
                    }

                    FilledTonalIconButton(
                        onClick = onToggleThemeQuick,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("cockpit_theme_toggle")
                    ) {
                        Icon(
                            imageVector = when (settings.themeMode) {
                                ThemeMode.DAY -> Icons.Rounded.LightMode
                                ThemeMode.NIGHT -> Icons.Rounded.DarkMode
                                ThemeMode.AUTO_CAR -> Icons.Rounded.DirectionsCar
                            },
                            contentDescription = "Changer thème Jour/Nuit"
                        )
                    }
                }

                Column {
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = runtime.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onStartStopReceiver,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("cockpit_connect_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isActive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isActive) "Arrêter AARE" else "Démarrer Récepteur",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    Surface(
                        onClick = onOpenReceiverTab,
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("cockpit_details_button")
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Protocole",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherWidgetCard(
    weather: WeatherState,
    onRefresh: () -> Unit,
    onEditCity: () -> Unit
) {
    val icon: ImageVector = when (weather.condition) {
        WeatherCondition.CLEAR -> Icons.Rounded.WbSunny
        WeatherCondition.PARTLY_CLOUDY -> Icons.Rounded.Cloud
        WeatherCondition.FOG -> Icons.Rounded.Grain
        WeatherCondition.DRIZZLE, WeatherCondition.RAIN -> Icons.Rounded.WaterDrop
        WeatherCondition.SNOW -> Icons.Rounded.AcUnit
        WeatherCondition.STORM -> Icons.Rounded.FlashOn
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = weather.condition.label,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Column {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = weather.temperatureC?.let { "$it°C" } ?: "--°C",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = weather.cityName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = weather.errorMessage ?: buildString {
                            append(weather.condition.label)
                            weather.apparentTempC?.let { append(" · Ressenti $it°") }
                            weather.windSpeedKmh?.let { append(" · Vent $it km/h") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row {
                IconButton(
                    onClick = onEditCity,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("weather_edit_city")
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Changer la ville météo")
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("weather_refresh")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Actualiser la météo")
                }
            }
        }
    }
}

@Composable
private fun MediaWidgetCard(
    media: MediaPlaybackUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onRequestMediaAccess: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = media.artwork
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Pochette album",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Album,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = media.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = onPrev,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("media_prev_button")
                    ) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Piste précédente")
                    }
                    FilledIconButton(
                        onClick = onPlayPause,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("media_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (media.isPlaying) "Pause" else "Lecture"
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onNext,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("media_next_button")
                    ) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Piste suivante")
                    }
                }
            }

            if (!media.hasMediaSessionAccess) {
                Surface(
                    onClick = onRequestMediaAccess,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("media_enable_access_banner")
                ) {
                    Text(
                        text = "Appuyez ici pour autoriser la lecture des titres & pochettes (NotificationListener)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAppDockCard(
    apps: List<LaunchableApp>,
    isVirtualDisplayActive: Boolean,
    onLaunchApp: (String) -> Unit,
    onSeeAllApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Raccourcis Conduite",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isVirtualDisplayActive) "Lancement sur VirtualDisplay AARE" else "Lancement direct sur écran",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onSeeAllApps,
                    modifier = Modifier.testTag("dock_all_apps_button")
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tout voir")
                }
            }

            if (apps.isEmpty()) {
                Text(
                    text = "Épinglez vos applications GPS ou musique depuis l'onglet Apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(78.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { onLaunchApp(app.packageName) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                                .testTag("dock_app_${app.packageName}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                val bmp = app.iconBitmap
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = app.label,
                                        modifier = Modifier.size(40.dp)
                                    )
                                } else {
                                    Icon(Icons.Rounded.Apps, contentDescription = app.label)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsAndCallsCard(
    notifications: List<CarNotificationItem>,
    hasAccess: Boolean,
    onOpenDialer: () -> Unit,
    onDismiss: (String) -> Unit,
    onEnableAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Appels & Notifications",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Surface(
                    onClick = onOpenDialer,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("cockpit_phone_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Téléphone",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (!hasAccess) {
                Surface(
                    onClick = onEnableAccess,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Activez l'accès aux notifications pour afficher les messages et appels entrants sur le tableau de bord.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else if (notifications.isEmpty()) {
                Text(
                    text = "Aucune notification récente. Conduisez sereinement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    notifications.take(4).forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    if (item.isCallOrUrgent) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (item.isCallOrUrgent) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary
                                            )
                                    )
                                    Text(
                                        text = "${item.appName} · ${item.timeLabel}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { onDismiss(item.key) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Ignorer la notification")
                            }
                        }
                    }
                }
            }
        }
    }
}
