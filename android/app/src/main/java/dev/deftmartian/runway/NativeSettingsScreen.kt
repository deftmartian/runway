package dev.deftmartian.runway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** The persisted values and capability summaries that the standalone Settings screen needs. */
internal data class NativeSettingsState(
    val timeZone: String = "",
    val routePrivacy: NativeRoutePrivacy = NativeRoutePrivacy.Discard,
    val heartRate: NativeHeartRateProfile = NativeHeartRateProfile(),
    val healthContext: NativeHealthContext = NativeHealthContext(),
    val folderImport: NativeImportConnection = NativeImportConnection.NotConnected,
    val healthConnectImport: NativeImportConnection = NativeImportConnection.NotConnected,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val sourceCommit: String = BuildConfig.SOURCE_COMMIT,
)

internal enum class NativeRoutePrivacy(val summary: String) {
    Discard("Route points are discarded after import"),
    KeepPrivate("Route traces stay on this phone"),
}

internal data class NativeHeartRateProfile(
    val source: NativeHeartRateSource = NativeHeartRateSource.NotConfigured,
    val maxHeartRateBpm: Int? = null,
    val zone2FloorBpm: Int? = null,
    val zone3FloorBpm: Int? = null,
    val zone4FloorBpm: Int? = null,
    val zone5FloorBpm: Int? = null,
)

internal enum class NativeHeartRateSource { NotConfigured, Estimated, Custom }

internal data class NativeHealthContext(
    val recentInjury: Boolean = false,
    val currentPain: Boolean = false,
    val recurringPain: Boolean = false,
    val clinicianRestriction: Boolean = false,
    val notes: String = "",
)

internal sealed interface NativeImportConnection {
    data object NotConnected : NativeImportConnection
    data object Connected : NativeImportConnection
    data object PermissionRequired : NativeImportConnection
    data class Attention(val detail: String) : NativeImportConnection
    data object Unavailable : NativeImportConnection
}

/** Explicit integration boundary: the screen owns no storage, import, or destructive operation. */
internal data class NativeSettingsCallbacks(
    val onTimeZoneChanged: (String) -> Unit,
    val onRoutePrivacyChanged: (NativeRoutePrivacy) -> Unit,
    val onHeartRateChanged: (NativeHeartRateProfile) -> Unit,
    val onHealthContextChanged: (NativeHealthContext) -> Unit,
    val onImportGpx: () -> Unit,
    val onOpenFolderImports: () -> Unit,
    val onOpenHealthConnect: () -> Unit,
    val onCreateBackup: () -> Unit,
    val onRestoreBackup: () -> Unit,
    val onExportData: () -> Unit,
    val onEraseAllData: () -> Unit,
)

@Composable
internal fun SettingsScreen(
    state: NativeSettingsState,
    actionPending: Boolean,
    callbacks: NativeSettingsCallbacks,
) {
    var editingTimeZone by rememberSaveable { mutableStateOf(false) }
    var editingRoutePrivacy by rememberSaveable { mutableStateOf(false) }
    var editingHeartRate by rememberSaveable { mutableStateOf(false) }
    var editingHealthContext by rememberSaveable { mutableStateOf(false) }
    var confirmingErase by rememberSaveable { mutableStateOf(false) }
    var timeZone by rememberSaveable(state.timeZone) { mutableStateOf(state.timeZone) }

    NativeList(loading = false) {
        item { ScreenIntro("Settings", "Private training preferences and local data.") }
        item {
            SettingsRail("Training") {
                SettingsActionRow("Time zone", state.timeZone.ifBlank { "Not set" }, "Change", !actionPending) {
                    timeZone = state.timeZone
                    editingTimeZone = true
                }
                SettingsActionRow("Route privacy", state.routePrivacy.summary, "Change", !actionPending) {
                    editingRoutePrivacy = true
                }
                SettingsActionRow("Heart rate", heartRateSummary(state.heartRate), "Edit", !actionPending) {
                    editingHeartRate = true
                }
                SettingsActionRow("Health context", healthContextSummary(state.healthContext), "Edit", !actionPending) {
                    editingHealthContext = true
                }
            }
        }
        item {
            SettingsRail("Imports") {
                SettingsActionRow("GPX file", "Choose one file to review on this phone", "Choose", !actionPending, callbacks.onImportGpx)
                SettingsActionRow("GPX folder", importConnectionSummary(state.folderImport), "Open", !actionPending, callbacks.onOpenFolderImports)
                SettingsActionRow("Health Connect", importConnectionSummary(state.healthConnectImport), "Open", !actionPending, callbacks.onOpenHealthConnect)
            }
        }
        item {
            SettingsRail("Data") {
                SettingsActionRow("Backup", "Save a copy before moving or resetting this phone", "Create", !actionPending, callbacks.onCreateBackup)
                SettingsActionRow("Restore", "Replace local runway data with a backup", "Restore", !actionPending, callbacks.onRestoreBackup)
                SettingsActionRow("Export", "Create a readable copy of your training data", "Export", !actionPending, callbacks.onExportData)
                SettingsActionRow("Erase local data", "Plans, activities, and private notes are removed from this phone", "Erase", !actionPending) {
                    confirmingErase = true
                }
            }
        }
        item {
            SettingsRail("About") {
                SettingsValueRow("Version", state.appVersion, monospace = true)
                SettingsValueRow("Commit", state.sourceCommit, monospace = true)
                SettingsValueRow("Storage", "Stored locally on this phone")
                SettingsValueRow("Folder import", importConnectionSummary(state.folderImport))
                SettingsValueRow("Health Connect", importConnectionSummary(state.healthConnectImport))
            }
        }
    }

    if (editingTimeZone) {
        AlertDialog(
            onDismissRequest = { editingTimeZone = false },
            title = { Text("Training time zone") },
            text = { OutlinedTextField(value = timeZone, onValueChange = { timeZone = it.take(100) }, modifier = Modifier.fillMaxWidth(), label = { Text("IANA time zone") }, supportingText = { Text("Example: America/Halifax") }, singleLine = true) },
            confirmButton = { Button(onClick = { callbacks.onTimeZoneChanged(timeZone.trim()); editingTimeZone = false }, enabled = !actionPending && timeZone.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingTimeZone = false }) { Text("Cancel") } },
        )
    }
    if (editingRoutePrivacy) {
        RoutePrivacyDialog(state.routePrivacy, actionPending, { editingRoutePrivacy = false }) {
            callbacks.onRoutePrivacyChanged(it)
            editingRoutePrivacy = false
        }
    }
    if (editingHeartRate) {
        HeartRateProfileDialog(state.heartRate, actionPending, { editingHeartRate = false }) {
            callbacks.onHeartRateChanged(it)
            editingHeartRate = false
        }
    }
    if (editingHealthContext) {
        HealthContextDialog(state.healthContext, actionPending, { editingHealthContext = false }) {
            callbacks.onHealthContextChanged(it)
            editingHealthContext = false
        }
    }
    if (confirmingErase) {
        AlertDialog(
            onDismissRequest = { confirmingErase = false },
            title = { Text("Erase all local runway data?") },
            text = { Text("This removes plans, activities, imported files, and private health context from this phone. It cannot be undone.") },
            confirmButton = { Button(onClick = { callbacks.onEraseAllData(); confirmingErase = false }, enabled = !actionPending) { Text("Erase local data") } },
            dismissButton = { TextButton(onClick = { confirmingErase = false }) { Text("Cancel") } },
        )
    }
}

@Composable private fun SettingsRail(title: String, content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth()) {
    Text(title, Modifier.padding(top = 12.dp, bottom = 6.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    HorizontalDivider()
    content()
    HorizontalDivider()
}

@Composable private fun SettingsActionRow(label: String, value: String, actionLabel: String, enabled: Boolean, onClick: () -> Unit) = Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    Text(actionLabel, Modifier.padding(horizontal = 12.dp, vertical = 14.dp), color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f), style = MaterialTheme.typography.labelLarge)
}

@Composable private fun SettingsValueRow(label: String, value: String, monospace: Boolean = false) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.width(16.dp))
    Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif)
}

internal fun healthContextSummary(context: NativeHealthContext): String = when {
    context.currentPain -> "Current pain reported"
    context.clinicianRestriction -> "Clinician restriction saved"
    context.recentInjury && context.recurringPain -> "Recent and recurring issue history saved"
    context.recentInjury -> "Recent injury history saved"
    context.recurringPain -> "Recurring issue history saved"
    context.notes.isNotBlank() -> "Private note saved"
    else -> "Nothing saved"
}

internal fun heartRateSummary(profile: NativeHeartRateProfile): String = when (profile.source) {
    NativeHeartRateSource.NotConfigured -> "Not configured"
    NativeHeartRateSource.Estimated -> "Estimated profile · ${profile.maxHeartRateBpm ?: "—"} bpm max"
    NativeHeartRateSource.Custom -> "Custom profile · ${profile.maxHeartRateBpm ?: "—"} bpm max"
}

internal fun importConnectionSummary(connection: NativeImportConnection): String = when (connection) {
    NativeImportConnection.NotConnected -> "Not connected"
    NativeImportConnection.Connected -> "Connected"
    NativeImportConnection.PermissionRequired -> "Permission needed"
    is NativeImportConnection.Attention -> connection.detail.ifBlank { "Needs attention" }
    NativeImportConnection.Unavailable -> "Unavailable on this phone"
}
