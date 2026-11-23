package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.CertificatesUiState

@Composable
fun CertificatesScreen(
    state: CertificatesUiState,
    onImportPkcs12: (Uri, String?) -> Unit,
    onImportPemPair: (Uri, Uri) -> Unit,
    onResetFallback: () -> Unit,
    onRunTlsTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingP12Uri by remember { mutableStateOf<Uri?>(null) }
    var pendingPemCertUri by remember { mutableStateOf<Uri?>(null) }

    val p12Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingP12Uri = uri
    }

    val pemKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { keyUri ->
        val certUri = pendingPemCertUri
        pendingPemCertUri = null
        if (certUri != null && keyUri != null) {
            onImportPemPair(certUri, keyUri)
        }
    }

    val pemCertPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { certUri ->
        if (certUri != null) {
            pendingPemCertUri = certUri
            pemKeyPicker.launch(arrayOf("*/*"))
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Certificats mTLS AARE", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Gestion du certificat téléphone pour le handshake TLS 1.2 (Channel 0 ENCAPSULATED_SSL).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.statusBanner != null) {
            item {
                Surface(
                    color = if (state.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.statusBanner,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (state.activeCert?.isGoogleCarService == true) {
                                    Icons.Rounded.VerifiedUser
                                } else {
                                    Icons.Rounded.Security
                                },
                                contentDescription = null,
                                tint = if (state.activeCert?.isExpired == true) {
                                    MaterialTheme.colorScheme.error
                                } else if (state.activeCert?.isGoogleCarService == true) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                }
                            )
                            Text("Certificat Actif", style = MaterialTheme.typography.titleLarge)
                        }
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }

                    val cert = state.activeCert
                    if (cert != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = cert.origin.label + if (cert.isGoogleCarService) " · Chaîne Google reconnue" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        CertFieldRow("Sujet (CN)", cert.subject)
                        CertFieldRow("Émetteur", cert.issuer)
                        CertFieldRow("Validité", "${cert.validFrom} → ${cert.validUntil}")
                        CertFieldRow("Chaîne", "${cert.chainLength} certificat(s)")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(10.dp)
                        ) {
                            Text("Empreinte SHA-256", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(cert.sha256Fingerprint, style = MaterialTheme.typography.labelSmall)
                        }

                        if (cert.isExpired) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(
                                    text = "Attention : certificat expiré (risque d'erreur AUTH_COMPLETE -24).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // Import & Test Actions
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Importer ou Vérifier", style = MaterialTheme.typography.titleLarge)

                    Button(
                        onClick = { p12Picker.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("cert_import_p12_button")
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Importer certificat PKCS#12 (.p12 / .pfx)")
                    }

                    OutlinedButton(
                        onClick = { pemCertPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("cert_import_pem_button")
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Importer paire PEM (cert.pem puis key.pem)")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRunTlsTest,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("cert_test_tls_button")
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test TLS 1.2")
                        }
                        OutlinedButton(
                            onClick = onResetFallback,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("cert_reset_fallback_button")
                        ) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Secours RSA")
                        }
                    }

                    val test = state.lastTlsTest
                    if (test != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(12.dp)
                        ) {
                            Icon(
                                imageVector = if (test.success) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = if (test.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            )
                            Text(test.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // AUTH_COMPLETE Diagnostic Reference (AARE docs/certificates.md)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), MaterialTheme.shapes.large),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Codes AUTH_COMPLETE Android Auto", style = MaterialTheme.typography.titleMedium)
                    Text("• status 0 : Certificat accepté par l'autoradio.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "• status -3 : L'autoradio OEM exige le certificat officiel Google CarService (.p12 / .pem).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• status -24 : Certificat expiré (CERT_EXPIRED) — importez un certificat valide.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    val p12Uri = pendingP12Uri
    if (p12Uri != null) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingP12Uri = null },
            title = { Text("Mot de passe PKCS#12") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Laissez vide pour tester automatiquement les mots de passe standards (vide, notasecret, android).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mot de passe (optionnel)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingP12Uri = null
                    onImportPkcs12(p12Uri, password.ifEmpty { null })
                }) { Text("Importer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingP12Uri = null }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun CertFieldRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
