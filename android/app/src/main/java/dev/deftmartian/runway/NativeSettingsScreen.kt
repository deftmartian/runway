package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    payload: NativeSettingsPayload?,
    loading: Boolean,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
    onSignOut: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var editingTimeZone by rememberSaveable { mutableStateOf(false) }
    var editingHealthContext by rememberSaveable { mutableStateOf(false) }
    var editingHeartRate by rememberSaveable { mutableStateOf(false) }
    var connectingNextcloud by rememberSaveable { mutableStateOf(false) }
    var disconnectingSource by remember { mutableStateOf<NativeImportSource?>(null) }
    var timeZone by rememberSaveable(payload?.profile?.timeZone) {
        mutableStateOf(payload?.profile?.timeZone.orEmpty())
    }
    NativeList(loading) {
        item { ScreenIntro("Settings", "Training preferences, imports, and this phone’s connection.") }
        if (payload == null) {
            item { EmptyCard("Loading settings…") }
        } else {
            val profile = payload.profile
            val about = payload.about
            item {
                SettingCard("Training") {
                    SettingRow("Time zone", profile?.timeZone.orDash())
                    SettingRow("Route privacy", profile?.routeDataMode.orDash())
                    SettingRow(
                        "Heart-rate zones",
                        profile?.heartRateSettingsSource
                            ?.takeUnless { it == "not_configured" }
                            ?.replaceFirstChar { it.uppercase() }
                            ?: "Not configured",
                    )
                    OutlinedButton(
                        onClick = { editingTimeZone = true },
                        enabled = !actionPending,
                    ) {
                        Text("Change time zone")
                    }
                    OutlinedButton(
                        onClick = {
                            val next =
                                if (profile?.routeDataMode == "discard") {
                                    "private"
                                } else {
                                    "discard"
                                }
                            onAction(UpdateRouteDataModeCommand(next))
                        },
                        enabled = !actionPending,
                    ) {
                        Text(
                            if (profile?.routeDataMode == "discard") {
                                "Keep future route points"
                            } else {
                                "Discard route points"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { editingHeartRate = true },
                        enabled = !actionPending,
                    ) {
                        Text("Heart-rate profile")
                    }
                    OutlinedButton(
                        onClick = { editingHealthContext = true },
                        enabled = !actionPending,
                    ) {
                        Text("Health context")
                    }
                }
            }
            item {
                SettingCard("Imports") {
                    val sources = payload.sources
                    if (sources.isEmpty()) {
                        Text(
                            "No Nextcloud GPX folders are connected.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sources.forEach { source ->
                            SettingRow(
                                source.label.orEmpty().ifBlank { "Nextcloud GPX folder" },
                                source.lastError?.takeIf(String::isNotBlank)
                                    ?: if (source.enabled == true) "Connected" else "Disabled",
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        onAction(TestNextcloudCommand(source.id.orEmpty()))
                                    },
                                    enabled = !actionPending,
                                ) {
                                    Text("Test")
                                }
                                TextButton(
                                    onClick = {
                                        onAction(SyncNextcloudCommand(source.id.orEmpty()))
                                    },
                                    enabled = !actionPending,
                                ) {
                                    Text("Sync")
                                }
                                TextButton(
                                    onClick = { disconnectingSource = source },
                                    enabled = !actionPending,
                                ) {
                                    Text("Disconnect")
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { connectingNextcloud = true },
                        enabled = !actionPending,
                    ) {
                        Text("Connect Nextcloud folder")
                    }
                    val health = payload.healthConnect
                    SettingRow(
                        "Health Connect",
                        when (health?.state) {
                            "connected" -> "Connected"
                            "needs_attention" -> "Needs attention"
                            "unavailable" -> "Unavailable"
                            else -> "Not connected"
                        },
                    )
                    OutlinedButton(onClick = onOpenFolder) { Text("Imports and Health Connect") }
                }
            }
            item {
                SettingCard("Server") {
                    SettingRow("Connected to", about?.serverOrigin.orDash())
                    OutlinedButton(onClick = onOpenServer) { Text("Change server") }
                }
            }
            item {
                SettingCard("About") {
                    SettingRow("Android app", BuildConfig.VERSION_NAME)
                    SettingRow("Android source", BuildConfig.SOURCE_COMMIT)
                    SettingRow("Server release", about?.release.orDash())
                    SettingRow("Server build", about?.commit.orDash())
                    SettingRow("Native API", "v2 · connected")
                }
            }
            item {
                SettingCard("Account") {
                    Text(
                        "Passkeys, two-factor authentication, exports, and account deletion use the secure server page.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    payload.accountSecurityUrl?.takeIf(String::isNotBlank)?.let { url ->
                        OutlinedButton(onClick = { uriHandler.openUri(url) }) {
                            Text("Account security")
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out from this phone")
                }
            }
        }
    }
    if (editingTimeZone) {
        AlertDialog(
            onDismissRequest = { editingTimeZone = false },
            title = { Text("Training time zone") },
            text = {
                OutlinedTextField(
                    value = timeZone,
                    onValueChange = { timeZone = it.take(100) },
                    label = { Text("IANA time zone") },
                    supportingText = { Text("Example: America/Halifax") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        editingTimeZone = false
                        onAction(UpdateTimeZoneCommand(timeZone))
                    },
                    enabled = !actionPending && timeZone.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTimeZone = false }) { Text("Cancel") }
            },
        )
    }
    if (editingHealthContext) {
        HealthContextDialog(
            injuryFlags = payload?.profile?.injuryFlags,
            actionPending = actionPending,
            onDismiss = { editingHealthContext = false },
            onSubmit = {
                editingHealthContext = false
                onAction(it)
            },
        )
    }
    if (editingHeartRate) {
        HeartRateProfileDialog(
            profile = payload?.profile,
            actionPending = actionPending,
            onDismiss = { editingHeartRate = false },
            onSubmit = {
                editingHeartRate = false
                onAction(it)
            },
        )
    }
    if (connectingNextcloud) {
        NextcloudConnectionDialog(
            actionPending = actionPending,
            onDismiss = { connectingNextcloud = false },
            onSubmit = {
                connectingNextcloud = false
                onAction(it)
            },
        )
    }
    disconnectingSource?.let { source ->
        AlertDialog(
            onDismissRequest = { disconnectingSource = null },
            title = { Text("Disconnect this folder?") },
            text = {
                Text(
                    "${source.label.orEmpty().ifBlank { "This Nextcloud folder" }} will stop syncing. Imported runs remain in runway.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        disconnectingSource = null
                        onAction(DisconnectNextcloudCommand(source.id.orEmpty()))
                    },
                    enabled = !actionPending,
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { disconnectingSource = null }) { Text("Cancel") }
            },
        )
    }
}
