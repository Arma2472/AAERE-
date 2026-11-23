package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.aare.projection.VideoResolution
import com.example.betterauto.data.AudioRoutingMode
import com.example.betterauto.data.UserSettings
import com.example.ui.theme.ThemeMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: UserSettings,
    hasNotificationAccess: Boolean,
    onSelectTheme: (ThemeMode) -> Unit,
    onSelectResolution: (VideoResolution) -> Unit,
    onSelectFps: (Int) -> Unit,
    onSelectBitrate: (Int) -> Unit,
    onSelectAudioRouting: (AudioRoutingMode) -> Unit,
    onSaveWeatherCity: (String) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var cityDraft by remember(settings.weatherCity) { mutableStateOf(settings.weatherCity) }

    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Paramètres AARE+ & BetterAuto", style = MaterialTheme.typography.headlineMedium)
        }

        // Theme Mode
        item {
            SettingsCard("Apparence & Thème Jour / Nuit") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onSelectTheme(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_theme_${mode.name}")
                        )
                    }
                }
                Text(
                    text = "En mode « Auto Véhicule », le thème bascule selon le capteur phares AAP (CH2) ou le thème système.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Video & VirtualDisplay
        item {
            SettingsCard("Projection Vidéo H.264 (VirtualDisplay)") {
                Text("Résolution", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoResolution.entries.forEach { res ->
                        FilterChip(
                            selected = settings.videoResolution == res,
                            onClick = { onSelectResolution(res) },
                            label = { Text(res.label) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_res_${res.name}")
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Fréquence d'images : ${settings.targetFps} FPS", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60).forEach { fps ->
                        FilterChip(
                            selected = settings.targetFps == fps,
                            onClick = { onSelectFps(fps) },
                            label = { Text("$fps FPS") },
                            modifier = Modifier.testTag("settings_fps_$fps")
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Débit vidéo cible : ${settings.bitrateMbps} Mbps", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.bitrateMbps.toFloat(),
                    onValueChange = { onSelectBitrate(it.roundToInt()) },
                    valueRange = 2f..20f,
                    steps = 8,
                    modifier = Modifier.testTag("settings_bitrate_slider")
                )
            }
        }

        // Audio Routing
        item {
            SettingsCard("Routage Audio") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioRoutingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.audioRoutingMode == mode,
                            onClick = { onSelectAudioRouting(mode) },
                            label = {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(mode.label, style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        mode.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_audio_${mode.name}")
                        )
                    }
                }
            }
        }

        // Weather City
        item {
            SettingsCard("Widget Météo (Open-Meteo)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = cityDraft,
                        onValueChange = { cityDraft = it },
                        label = { Text("Ville par défaut") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSaveWeatherCity(cityDraft) },
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text("Enregistrer")
                    }
                }
            }
        }

        // Android 10 (API 29+) Compatibility & Permissions
        item {
            SettingsCard("Compatibilité Android 10 (API 29+) & Permissions") {
                Text(
                    text = "Système détecté : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                PermissionRow("Passerelle Média & Notifications", hasNotificationAccess)
                PermissionRow("Microphone (Commandes vocales / AAP)", micGranted)
                PermissionRow("Localisation approximative (Météo)", locGranted)
                PermissionRow("Notifications Service (Android 13+)", notifGranted)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenNotificationAccess,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Accès Notifs")
                    }
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Autoriser")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
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
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = if (granted) "Accordée" else "Non accordée",
            tint = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
        )
    }
}
