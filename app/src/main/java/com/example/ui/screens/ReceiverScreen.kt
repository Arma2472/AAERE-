package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aare.service.AareConnectionStage
import com.example.aare.service.AareRuntimeState
import com.example.aare.transport.ConnectionMode
import com.example.betterauto.data.UserSettings

@Composable
fun ReceiverScreen(
    settings: UserSettings,
    runtime: AareRuntimeState,
    onSelectConnectionMode: (ConnectionMode) -> Unit,
    onSaveWirelessEndpoint: (String, Int) -> Unit,
    onStartStopReceiver: () -> Unit,
    onScanUsb: () -> Unit,
    onToggleHeadlightsSim: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hostDraft by remember(settings.wirelessHost) { mutableStateOf(settings.wirelessHost) }
    var portDraft by remember(settings.wirelessPort) { mutableStateOf(settings.wirelessPort.toString()) }

    val isRunning = runtime.stage != AareConnectionStage.DISCONNECTED &&
        runtime.stage != AareConnectionStage.ERROR

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), MaterialTheme.shapes.large),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Transport AARE (USB / Sans-Fil)", style = MaterialTheme.typography.titleLarge)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConnectionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.connectionMode == mode,
                                onClick = { onSelectConnectionMode(mode) },
                                label = { Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (mode == ConnectionMode.USB_AOAP) Icons.Rounded.Usb else Icons.Rounded.Wifi,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("receiver_mode_${mode.name}")
                            )
                        }
                    }

                    if (settings.connectionMode == ConnectionMode.USB_AOAP) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (runtime.usbAccessoryPresent) "Accessoire AOAP détecté" else "En attente câble USB",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = runtime.usbAccessoryName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = onScanUsb,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("receiver_scan_usb")
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Scanner USB AOAP")
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (settings.connectionMode == ConnectionMode.WIFI_CLIENT) {
                                OutlinedTextField(
                                    value = hostDraft,
                                    onValueChange = { hostDraft = it },
                                    label = { Text("IP Récepteur") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.5f)
                                )
                            }
                            OutlinedTextField(
                                value = portDraft,
                                onValueChange = { portDraft = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Port TCP") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    val port = portDraft.toIntOrNull() ?: 5277
                                    onSaveWirelessEndpoint(hostDraft, port)
                                },
                                modifier = Modifier.height(54.dp)
                            ) {
                                Text("OK")
                            }
                        }
                    }

                    Button(
                        onClick = onStartStopReceiver,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("receiver_start_stop_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRunning) "Arrêter le service AARE" else "Lancer la session AARE")
                    }
                }
            }
        }

        // Pipeline Stepper & Telemetry
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), MaterialTheme.shapes.large),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Pipeline Protocole AAP & Télémétrie", style = MaterialTheme.typography.titleLarge)

                    val steps = listOf(
                        AareConnectionStage.CONNECTING_TRANSPORT to "Lien",
                        AareConnectionStage.VERSION_EXCHANGE to "AAP 1.6",
                        AareConnectionStage.TLS_HANDSHAKE to "TLS 1.2",
                        AareConnectionStage.AUTH_CHECK to "Auth",
                        AareConnectionStage.CONNECTED to "H.264"
                    )
                    val activeOrdinal = runtime.stage.ordinal
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        steps.forEach { (stepStage, label) ->
                            val reached = runtime.stage == AareConnectionStage.CONNECTED ||
                                (runtime.stage != AareConnectionStage.DISCONNECTED &&
                                    runtime.stage != AareConnectionStage.ERROR &&
                                    activeOrdinal >= stepStage.ordinal)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (reached) MaterialTheme.colorScheme.tertiary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (reached) {
                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TelemetryTile(
                            label = "Display Virtuel",
                            value = runtime.virtualDisplayId?.let { "#$it" } ?: "Inactif",
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryTile(
                            label = "Trames H.264",
                            value = runtime.encodedFramesCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryTile(
                            label = "Flux Vidéo",
                            value = "${runtime.resolution.width}×${runtime.resolution.height}",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.NightsStay, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Column {
                                Text("Capteur Phares Véhicule (CH2)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Synchronise le thème Jour/Nuit de BetterAuto",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = runtime.carHeadlightsOn == true,
                            onCheckedChange = { onToggleHeadlightsSim() },
                            modifier = Modifier.testTag("receiver_headlights_switch")
                        )
                    }
                }
            }
        }

        // Live AAP Logs Console
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Journal Protocole AARE", style = MaterialTheme.typography.titleLarge)
                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("receiver_clear_logs")
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Effacer le journal")
                }
            }
        }

        if (runtime.logs.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Aucune trame AAP enregistrée. Lancez une connexion USB/Wi-Fi ou testez le handshake TLS dans l'onglet Certificats.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(runtime.logs.asReversed()) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (entry.isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = entry.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "[${entry.channel}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
