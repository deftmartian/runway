package dev.deftmartian.runway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
    val heartRatePrivacy: NativeHeartRatePrivacy = NativeHeartRatePrivacy.Discard,
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

internal enum class NativeHeartRatePrivacy(val summary: String) {
    Discard("Imported heart-rate values are discarded"),
    KeepPrivate("Imported heart-rate values stay on this phone"),
}

internal data class NativeHeartRateProfile(
    val source: NativeHeartRateSource = NativeHeartRateSource.NotConfigured,
    val sexForEstimates: NativeSexForEstimate = NativeSexForEstimate.NotSpecified,
    val ageYears: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val zone2FloorBpm: Int? = null,
    val zone3FloorBpm: Int? = null,
    val zone4FloorBpm: Int? = null,
    val zone5FloorBpm: Int? = null,
)

internal enum class NativeHeartRateSource { NotConfigured, Estimated, Custom }
internal enum class NativeSexForEstimate { NotSpecified, Female, Male }

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
    val onHeartRatePrivacyChanged: (NativeHeartRatePrivacy) -> Unit,
    val onHeartRateChanged: (NativeHeartRateProfile) -> Unit,
    val onHealthContextChanged: (NativeHealthContext) -> Unit,
    val onImportGpx: () -> Unit,
    val onOpenFolderImports: () -> Unit,
    val onOpenHealthConnect: () -> Unit,
    val onCreateBackup: () -> Unit,
    val onRestoreBackup: () -> Unit,
    val onExportData: () -> Unit,
    val onEraseImportedActivityData: () -> Unit,
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
    var editingHeartRatePrivacy by rememberSaveable { mutableStateOf(false) }
    var editingHeartRate by rememberSaveable { mutableStateOf(false) }
    var editingHealthContext by rememberSaveable { mutableStateOf(false) }
    var confirmingBackup by rememberSaveable { mutableStateOf(false) }
    var confirmingRestore by rememberSaveable { mutableStateOf(false) }
    var confirmingExport by rememberSaveable { mutableStateOf(false) }
    var confirmingImportedErase by rememberSaveable { mutableStateOf(false) }
    var confirmingErase by rememberSaveable { mutableStateOf(false) }

    NativeList(loading = false) {
        item { ScreenContext("Private training preferences and local data.") }
        item {
            SettingsRail("Training") {
                SettingsActionRow("Time zone", state.timeZone.ifBlank { "Not set" }, "Change", !actionPending) {
                    editingTimeZone = true
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
                SettingsActionRow("GPX file", "Choose one file to review on this phone", "Choose", !actionPending, onClick = callbacks.onImportGpx)
                SettingsActionRow(
                    "GPX folder",
                    importConnectionSummary(state.folderImport),
                    folderImportActionLabel(state.folderImport),
                    !actionPending,
                    onClick = callbacks.onOpenFolderImports,
                )
                SettingsActionRow(
                    "Health Connect",
                    importConnectionSummary(state.healthConnectImport),
                    healthConnectActionLabel(state.healthConnectImport),
                    !actionPending,
                    onClick = callbacks.onOpenHealthConnect,
                )
            }
        }
        item {
            SettingsRail("Privacy") {
                SettingsActionRow("Route privacy", state.routePrivacy.summary, "Change", !actionPending) {
                    editingRoutePrivacy = true
                }
                SettingsActionRow(
                    "Heart-rate privacy",
                    state.heartRatePrivacy.summary,
                    "Change",
                    !actionPending,
                ) {
                    editingHeartRatePrivacy = true
                }
            }
        }
        item {
            SettingsRail("Data") {
                SettingsActionRow("Backup", "Save a complete copy before moving or resetting this phone", "Create", !actionPending) {
                    confirmingBackup = true
                }
                SettingsActionRow("Restore", "Replace everything in runway with a complete backup", "Restore", !actionPending) {
                    confirmingRestore = true
                }
                SettingsActionRow("Export", "Create a readable copy of your training history", "Export", !actionPending) {
                    confirmingExport = true
                }
            }
        }
        item {
            SettingsRail("Reset and removal") {
                SettingsActionRow(
                    "Remove imported runs",
                    "Delete GPX and Health Connect data; keep manual entries and plans",
                    "Remove",
                    !actionPending,
                    destructive = true,
                ) {
                    confirmingImportedErase = true
                }
                SettingsActionRow(
                    "Reset runway",
                    "Delete every plan, activity, preference, and private note on this phone",
                    "Reset",
                    !actionPending,
                    destructive = true,
                ) {
                    confirmingErase = true
                }
            }
        }
        item {
            SettingsRail("About") {
                SettingsValueRow("Version", state.appVersion, monospace = true)
                val buildRevision = normalizedBuildRevision(state.sourceCommit)
                SettingsValueRow("Build revision", shortBuildRevision(buildRevision), monospace = true)
                SettingsValueRow("App data", "Stored on this device")
                SettingsValueRow(
                    "Copies you create",
                    "Saved to the Android document location you choose",
                )
            }
        }
    }

    if (editingTimeZone) {
        NativeTimeZonePicker(
            currentTimeZoneId = state.timeZone,
            onDismiss = { editingTimeZone = false },
            onSelected = {
                callbacks.onTimeZoneChanged(it)
                editingTimeZone = false
            },
        )
    }
    if (editingRoutePrivacy) {
        RoutePrivacyDialog(state.routePrivacy, actionPending, { editingRoutePrivacy = false }) {
            callbacks.onRoutePrivacyChanged(it)
            editingRoutePrivacy = false
        }
    }
    if (editingHeartRatePrivacy) {
        HeartRatePrivacyDialog(
            state.heartRatePrivacy,
            actionPending,
            { editingHeartRatePrivacy = false },
        ) {
            callbacks.onHeartRatePrivacyChanged(it)
            editingHeartRatePrivacy = false
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
    if (confirmingBackup) {
        LocalDocumentDialog(
            title = "Create a complete backup?",
            message = "The backup is not encrypted. It can contain training history, private notes, route data, and heart-rate data. Choose an Android document destination you trust; its provider may store it on this device or in cloud-backed storage.",
            actionLabel = "Choose location",
            actionPending = actionPending,
            onDismiss = { confirmingBackup = false },
        ) {
            confirmingBackup = false
            callbacks.onCreateBackup()
        }
    }
    if (confirmingRestore) {
        LocalDocumentDialog(
            title = "Replace local runway data?",
            message = "Restoring a backup replaces every plan, activity, preference, and private note currently in runway. Folder and Health Connect imports are disconnected before restore and must be enabled again. The app restarts after a successful restore.",
            actionLabel = "Choose backup",
            actionPending = actionPending,
            onDismiss = { confirmingRestore = false },
        ) {
            confirmingRestore = false
            callbacks.onRestoreBackup()
        }
    }
    if (confirmingExport) {
        LocalDocumentDialog(
            title = "Export training history?",
            message = "The readable export is not encrypted. It omits detailed route and heart-rate samples, but still contains private training history and notes. Choose an Android document destination you trust; its provider may store it on this device or in cloud-backed storage.",
            actionLabel = "Choose location",
            actionPending = actionPending,
            onDismiss = { confirmingExport = false },
        ) {
            confirmingExport = false
            callbacks.onExportData()
        }
    }
    if (confirmingErase) {
        DestructiveConfirmationDialog(
            title = "Reset runway?",
            message =
                "This removes plans, activities, imported files, and private health context " +
                    "from this phone. This cannot be undone.",
            confirmLabel = "Reset runway",
            actionPending = actionPending,
            onDismiss = { confirmingErase = false },
            onConfirm = {
                callbacks.onEraseAllData()
                confirmingErase = false
            },
        )
    }
    if (confirmingImportedErase) {
        DestructiveConfirmationDialog(
            title = "Remove imported runs?",
            message =
                "This permanently removes activity data imported from GPX files and Health " +
                    "Connect, including retained route and heart-rate samples. Manual entries, " +
                    "plans, preferences, and recorded plan adjustments stay. Folder access and " +
                    "Health Connect permissions will be disconnected after removal. This cannot " +
                    "be undone.",
            confirmLabel = "Remove imported runs",
            actionPending = actionPending,
            onDismiss = { confirmingImportedErase = false },
            onConfirm = {
                callbacks.onEraseImportedActivityData()
                confirmingImportedErase = false
            },
        )
    }
}

@Composable
private fun LocalDocumentDialog(
    title: String,
    message: String,
    actionLabel: String,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !actionPending) {
                Text(actionLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable private fun SettingsRail(title: String, content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth()) {
    Text(title, Modifier.padding(top = 12.dp, bottom = 6.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    content()
    HorizontalDivider()
}

@Composable private fun SettingsActionRow(
    label: String,
    value: String,
    actionLabel: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) = Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        actionLabel,
        Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
            destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable private fun SettingsValueRow(label: String, value: String, monospace: Boolean = false) = Column(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    SelectionContainer {
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
        )
    }
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

internal fun folderImportActionLabel(connection: NativeImportConnection): String = when (connection) {
    NativeImportConnection.NotConnected -> "Choose"
    NativeImportConnection.PermissionRequired -> "Grant access"
    NativeImportConnection.Connected -> "Manage"
    is NativeImportConnection.Attention -> "Review"
    NativeImportConnection.Unavailable -> "Unavailable"
}

internal fun healthConnectActionLabel(connection: NativeImportConnection): String = when (connection) {
    NativeImportConnection.NotConnected -> "Set up"
    NativeImportConnection.PermissionRequired -> "Grant access"
    NativeImportConnection.Connected -> "Manage"
    is NativeImportConnection.Attention -> "Review"
    NativeImportConnection.Unavailable -> "Unavailable"
}

private const val BUILD_REVISION_SHORT_LENGTH = 12

internal fun normalizedBuildRevision(sourceCommit: String): String =
    sourceCommit.trim().ifBlank { "Not available" }

internal fun shortBuildRevision(sourceCommit: String): String {
    val revision = normalizedBuildRevision(sourceCommit)
    return if (revision == "Not available") revision else revision.take(BUILD_REVISION_SHORT_LENGTH)
}
