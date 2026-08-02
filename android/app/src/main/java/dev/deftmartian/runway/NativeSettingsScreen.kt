package dev.deftmartian.runway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.deftmartian.runway.data.RetentionRepairNotice
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The persisted values and capability summaries that the standalone Settings screen needs. */
internal data class NativeSettingsState(
    val timeZone: String = "",
    val routePrivacy: NativeRoutePrivacy = NativeRoutePrivacy.Discard,
    val heartRatePrivacy: NativeHeartRatePrivacy = NativeHeartRatePrivacy.Discard,
    val heartRate: NativeHeartRateProfile = NativeHeartRateProfile(),
    val healthContext: NativeHealthContext = NativeHealthContext(),
    val training: NativeTrainingSettings = NativeTrainingSettings.None,
    val notifications: NativeNotificationSettings = NativeNotificationSettings(),
    val folderImport: NativeImportConnection = NativeImportConnection.NotConnected,
    val healthConnectImport: NativeImportConnection = NativeImportConnection.NotConnected,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val sourceCommit: String = BuildConfig.SOURCE_COMMIT,
    val retentionRepair: RetentionRepairNotice? = null,
)

internal data class NativeNotificationSettings(
    val runReminderEnabled: Boolean = false,
    val runReminderMinuteOfDay: Int = 8 * 60,
    val folderImportAlertsEnabled: Boolean = false,
    val runReminderAllowed: Boolean = true,
    val folderImportAlertsAllowed: Boolean = true,
)

internal sealed interface NativeTrainingSettings {
    data object None : NativeTrainingSettings

    data class Active(
        val title: String,
        val phase: String,
        val startedOn: String,
        val targetDate: String?,
        val runsPerWeek: Int?,
    ) : NativeTrainingSettings

    data class Pending(
        val title: String,
        val targetDate: String?,
    ) : NativeTrainingSettings
}

internal data class NativeTrainingSettingsRow(
    val label: String,
    val summary: String,
    val action: String,
)

internal fun nativeTrainingSettingsRow(training: NativeTrainingSettings): NativeTrainingSettingsRow =
    when (training) {
        NativeTrainingSettings.None -> NativeTrainingSettingsRow(
            label = "Training plan",
            summary = "No plan set",
            action = "Set up",
        )
        is NativeTrainingSettings.Pending -> NativeTrainingSettingsRow(
            label = "Goal setup",
            summary = listOfNotNull(
                training.title.takeIf(String::isNotBlank),
                training.targetDate?.let { "target ${ledgerDate(it)}" },
                "No runs scheduled",
            ).joinToString(" · "),
            action = "Review",
        )
        is NativeTrainingSettings.Active -> if (training.phase == "routine") {
            NativeTrainingSettingsRow(
                label = "Weekly routine",
                summary = listOfNotNull(
                    training.runsPerWeek?.let {
                        "$it ${if (it == 1) "run" else "runs"} each week"
                    },
                    "Since ${ledgerDate(training.startedOn)}",
                ).joinToString(" · "),
                action = "Change",
            )
        } else {
            NativeTrainingSettingsRow(
                label = "Training plan",
                summary = listOfNotNull(
                    training.title.takeIf(String::isNotBlank),
                    training.targetDate?.let { "target ${ledgerDate(it)}" },
                ).joinToString(" · ").ifBlank { "Active plan" },
                action = "Change",
            )
        }
    }

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
    val onOpenTrainingSetup: () -> Unit,
    val onRunReminderChanged: (Boolean, Int) -> Unit,
    val onFolderImportAlertsChanged: (Boolean) -> Unit,
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
    val onAcknowledgeRetentionRepair: () -> Unit,
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
    var editingRunReminder by rememberSaveable { mutableStateOf(false) }
    var confirmingBackup by rememberSaveable { mutableStateOf(false) }
    var confirmingRestore by rememberSaveable { mutableStateOf(false) }
    var confirmingExport by rememberSaveable { mutableStateOf(false) }
    var confirmingImportedErase by rememberSaveable { mutableStateOf(false) }
    var confirmingErase by rememberSaveable { mutableStateOf(false) }

    NativeList(loading = false) {
        item { ScreenContext("Update your plan, imports, reminders, and saved data.") }
        state.retentionRepair?.let { repair ->
            item {
                SettingCard("Import settings restored") {
                    Text(
                        repair.settingsMessage(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = callbacks.onAcknowledgeRetentionRepair,
                            enabled = !actionPending,
                        ) {
                            Text("Dismiss note")
                        }
                    }
                }
            }
        }
        item {
            SettingsRail("Training") {
                val trainingRow = nativeTrainingSettingsRow(state.training)
                SettingsActionRow(
                    trainingRow.label,
                    trainingRow.summary,
                    trainingRow.action,
                    !actionPending,
                    onClick = callbacks.onOpenTrainingSetup,
                )
                SettingsActionRow("Time zone", state.timeZone.ifBlank { "Not set" }, "Change", !actionPending) {
                    editingTimeZone = true
                }
                SettingsActionRow("Heart rate", heartRateSummary(state.heartRate), "Edit", !actionPending) {
                    editingHeartRate = true
                }
                SettingsActionRow(
                    "Running limits",
                    runningCheckInSummary(state.healthContext),
                    if (hasRunningLimits(state.healthContext)) "Change" else "Add",
                    !actionPending,
                ) {
                    editingHealthContext = true
                }
            }
        }
        item {
            SettingsRail("Notifications") {
                SettingsActionRow(
                    "Run reminders",
                    runReminderSummary(state.notifications),
                    when {
                        state.notifications.runReminderEnabled &&
                            !state.notifications.runReminderAllowed -> "Allow"
                        state.notifications.runReminderEnabled -> "Change"
                        else -> "Set"
                    },
                    !actionPending,
                ) {
                    if (
                        state.notifications.runReminderEnabled &&
                        !state.notifications.runReminderAllowed
                    ) {
                        callbacks.onRunReminderChanged(
                            true,
                            state.notifications.runReminderMinuteOfDay,
                        )
                    } else {
                        editingRunReminder = true
                    }
                }
                SettingsActionRow(
                    "Import review alerts",
                    importAlertSummary(state.notifications),
                    when {
                        state.notifications.folderImportAlertsEnabled &&
                            !state.notifications.folderImportAlertsAllowed -> "Allow"
                        state.notifications.folderImportAlertsEnabled -> "Turn off"
                        else -> "Turn on"
                    },
                    !actionPending,
                ) {
                    val turnOn = !state.notifications.folderImportAlertsEnabled ||
                        !state.notifications.folderImportAlertsAllowed
                    callbacks.onFolderImportAlertsChanged(turnOn)
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
                SettingsActionRow("Imported route details", state.routePrivacy.summary, "Change", !actionPending) {
                    editingRoutePrivacy = true
                }
                SettingsActionRow(
                    "Imported heart-rate details",
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
                SettingsActionRow(
                    "Backup",
                    "Save a complete copy before moving or resetting this phone",
                    "Create",
                    !actionPending,
                    stackAction = true,
                ) {
                    confirmingBackup = true
                }
                SettingsActionRow(
                    "Restore",
                    "Replace everything in runway with a complete backup",
                    "Restore",
                    !actionPending,
                    stackAction = true,
                ) {
                    confirmingRestore = true
                }
                SettingsActionRow(
                    "Export",
                    "Create a readable copy of your training history",
                    "Export",
                    !actionPending,
                    stackAction = true,
                ) {
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
                    stackAction = true,
                ) {
                    confirmingImportedErase = true
                }
                SettingsActionRow(
                    "Reset runway",
                    "Delete every plan, activity, preference, and private note on this phone",
                    "Reset",
                    !actionPending,
                    destructive = true,
                    stackAction = true,
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
    if (editingRunReminder) {
        RunReminderSettingsDialog(
            settings = state.notifications,
            actionPending = actionPending,
            onDismiss = { editingRunReminder = false },
            onSave = { enabled, minuteOfDay ->
                editingRunReminder = false
                callbacks.onRunReminderChanged(enabled, minuteOfDay)
            },
        )
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
        RestoreBackupDialog(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunReminderSettingsDialog(
    settings: NativeNotificationSettings,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSave: (Boolean, Int) -> Unit,
) {
    val picker = rememberTimePickerState(
        initialHour = settings.runReminderMinuteOfDay.coerceIn(0, 1_439) / 60,
        initialMinute = settings.runReminderMinuteOfDay.coerceIn(0, 1_439) % 60,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run reminders") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Runway will check for a planned run around this time. Android may deliver the reminder later.")
                TimePicker(state = picker)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(true, picker.hour * 60 + picker.minute) },
                enabled = !actionPending,
            ) {
                Text(if (settings.runReminderEnabled) "Save" else "Turn on")
            }
        },
        dismissButton = {
            Row {
                if (settings.runReminderEnabled) {
                    TextButton(
                        onClick = { onSave(false, settings.runReminderMinuteOfDay) },
                        enabled = !actionPending,
                    ) {
                        Text("Turn off")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun RestoreBackupDialog(
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    LocalDocumentDialog(
        title = "Replace local runway data?",
        message = "Restoring a backup replaces every plan, activity, preference, and private note currently in runway. Folder and Health Connect imports are disconnected before restore and must be enabled again. The app restarts after a successful restore.",
        actionLabel = "Choose backup",
        actionPending = actionPending,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
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
    stackAction: Boolean = false,
    onClick: () -> Unit,
) {
    val actionColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        if (stackAction || usesStackedSettingsActionRow(maxWidth.value, LocalDensity.current.fontScale)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(
                    actionLabel,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    color = actionColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    actionLabel,
                    Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    color = actionColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

internal fun usesStackedSettingsActionRow(availableWidthDp: Float, fontScale: Float): Boolean =
    availableWidthDp < 300f || fontScale > 1.15f

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

internal fun runningCheckInSummary(context: NativeHealthContext): String = when {
    context.currentPain && context.clinicianRestriction ->
        "Pain reported and clinician's limit · setup will not schedule runs"
    context.currentPain -> "Pain reported now · setup will not schedule runs"
    context.clinicianRestriction -> "Clinician's running limit · setup will not schedule runs"
    context.recentInjury && context.recurringPain ->
        "Recent injury and recurring pain · distance increases checked more cautiously"
    context.recentInjury -> "Recent injury · distance increases checked more cautiously"
    context.recurringPain -> "Recurring pain · distance increases checked more cautiously"
    context.notes.isNotBlank() -> "Private reminder · no effect on the schedule"
    else -> "No running limits saved"
}

internal fun hasRunningLimits(context: NativeHealthContext): Boolean =
    context.recentInjury ||
        context.currentPain ||
        context.recurringPain ||
        context.clinicianRestriction ||
        context.notes.isNotBlank()

internal fun runningCheckInEffect(context: NativeHealthContext): String? = when {
    context.currentPain || context.clinicianRestriction ->
        "New setup saves the goal but does not create a schedule. Existing workouts stay recorded."
    context.recentInjury || context.recurringPain ->
        "Distance plans and edits to runs with targets use more cautious increase checks. Foundation sessions and open routine runs stay unchanged."
    context.notes.isNotBlank() ->
        "The private reminder is for your reference only. It does not change workouts or scheduling."
    else -> null
}

internal fun heartRateSummary(profile: NativeHeartRateProfile): String = when (profile.source) {
    NativeHeartRateSource.NotConfigured -> "Not configured"
    NativeHeartRateSource.Estimated -> "Estimated profile · ${profile.maxHeartRateBpm ?: "—"} bpm max"
    NativeHeartRateSource.Custom -> "Custom profile · ${profile.maxHeartRateBpm ?: "—"} bpm max"
}

internal fun runReminderSummary(settings: NativeNotificationSettings): String = when {
    settings.runReminderEnabled && !settings.runReminderAllowed -> "Blocked by Android"
    settings.runReminderEnabled ->
        "Around ${formatReminderTime(settings.runReminderMinuteOfDay)} on planned run days"
    else -> "Off"
}

internal fun importAlertSummary(settings: NativeNotificationSettings): String = when {
    settings.folderImportAlertsEnabled && !settings.folderImportAlertsAllowed ->
        "Blocked by Android"
    settings.folderImportAlertsEnabled -> "New folder runs ready for Inbox review"
    else -> "Off"
}

internal fun formatReminderTime(minuteOfDay: Int): String {
    val safeMinute = minuteOfDay.coerceIn(0, 1_439)
    return LocalTime.of(safeMinute / 60, safeMinute % 60)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
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
