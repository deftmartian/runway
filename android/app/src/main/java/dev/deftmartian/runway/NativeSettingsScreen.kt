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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    payload: NativeSettingsPayload?,
    loading: Boolean,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenAccountSecurity: () -> Unit,
    onSignOut: () -> Unit,
) {
    var editingTimeZone by rememberSaveable { mutableStateOf(false) }
    var editingHealthContext by rememberSaveable { mutableStateOf(false) }
    var editingHeartRate by rememberSaveable { mutableStateOf(false) }
    var connectingNextcloud by rememberSaveable { mutableStateOf(false) }
    var nextcloudManagementOpen by rememberSaveable { mutableStateOf(false) }
    var technicalDetailsOpen by rememberSaveable { mutableStateOf(false) }
    var disconnectingSource by remember { mutableStateOf<NativeImportSource?>(null) }
    var confirmingRouteDiscard by rememberSaveable { mutableStateOf(false) }
    var timeZone by rememberSaveable(payload?.profile?.timeZone) {
        mutableStateOf(payload?.profile?.timeZone.orEmpty())
    }
    NativeList(loading) {
        item { ScreenIntro("Settings", "Training preferences and app connections.") }
        if (payload == null) {
            item { EmptyCard("Loading settings…") }
        } else {
            val profile = payload.profile
            val about = payload.about
            item {
                SettingsRail("Training") {
                    val routeMode = profile?.routeDataMode
                    SettingsActionRow("Time zone", profile?.timeZone.orDash(), "Change", !actionPending) {
                        editingTimeZone = true
                    }
                    SettingsActionRow(
                        "Route privacy",
                        when (routeMode) {
                            "discard" -> "Route points discarded"
                            "private" -> "Route traces kept privately"
                            else -> "Not configured"
                        },
                        if (routeMode == "private") "Discard points" else "Keep points",
                        !actionPending,
                    ) {
                        val next = if (routeMode == "private") "discard" else "private"
                        if (next == "discard") confirmingRouteDiscard = true
                        else onAction(UpdateRouteDataModeCommand(next))
                    }
                    SettingsActionRow(
                        "Heart-rate zones",
                        profile?.heartRateSettingsSource
                            ?.takeUnless { it == "not_configured" }
                            ?.replaceFirstChar { it.uppercase() }
                            ?: "Not configured",
                        "Edit",
                        !actionPending,
                    ) { editingHeartRate = true }
                    SettingsActionRow("Health context", healthContextSummary(profile?.injuryFlags), "Edit", !actionPending) {
                        editingHealthContext = true
                    }
                }
            }
            item {
                SettingsRail("Phone imports") {
                    SettingsActionRow(
                        "Android imports",
                        phoneImportSummary(payload.healthConnect),
                        "Open setup",
                        !actionPending,
                        onClick = onOpenFolder,
                    )
                }
            }
            item {
                SettingsRail("Nextcloud folders") {
                    val sources = payload.sources
                    SettingsActionRow(
                        "GPX folders",
                        nextcloudSourceSummary(sources),
                        if (nextcloudManagementOpen) "Hide" else "Manage",
                        !actionPending,
                    ) { nextcloudManagementOpen = !nextcloudManagementOpen }
                    if (nextcloudManagementOpen) {
                        sources.forEach { source ->
                            SettingsValueRow(
                                source.label.orEmpty().ifBlank { "Nextcloud GPX folder" },
                                source.lastError?.takeIf(String::isNotBlank)
                                    ?: if (source.enabled == true) "Connected" else "Disabled",
                            )
                            SettingsInlineActions(
                                actionPending = actionPending,
                                onTest = { onAction(TestNextcloudCommand(source.id.orEmpty())) },
                                onSync = { onAction(SyncNextcloudCommand(source.id.orEmpty())) },
                                onDisconnect = { disconnectingSource = source },
                            )
                        }
                        SettingsActionRow(
                            "Connect a folder",
                            "GPX files stay in Review until you confirm them",
                            "Connect",
                            !actionPending,
                        ) { connectingNextcloud = true }
                    }
                }
            }
            item {
                SettingsRail("Server") {
                    SettingsActionRow(
                        "runway server",
                        about?.serverOrigin.orDash(),
                        "Change",
                        !actionPending,
                        monospaceValue = true,
                        onClick = onOpenServer,
                    )
                }
            }
            item {
                SettingsRail("Account") {
                    SettingsActionRow(
                        "Account security",
                        "Sign-in methods, sessions, and import devices",
                        "Open",
                        payload.accountSecurityAvailable == true && !actionPending,
                        onClick = onOpenAccountSecurity,
                    )
                    SettingsActionRow(
                        "This phone",
                        "Remove its runway session",
                        "Sign out",
                        !actionPending,
                        onClick = onSignOut,
                    )
                }
            }
            item {
                SettingsRail("About") {
                    SettingsValueRow("Android app", BuildConfig.VERSION_NAME, monospace = true)
                    SettingsValueRow("Server release", about?.release.orDash(), monospace = true)
                    SettingsActionRow(
                        "Technical details",
                        "Version and build identifiers",
                        if (technicalDetailsOpen) "Hide" else "Show",
                        true,
                    ) { technicalDetailsOpen = !technicalDetailsOpen }
                    if (technicalDetailsOpen) {
                        TechnicalValueRow("Android source", BuildConfig.SOURCE_COMMIT)
                        TechnicalValueRow("Server build", about?.commit.orDash())
                        TechnicalValueRow("App protocol", "v2")
                    }
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
    if (confirmingRouteDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingRouteDiscard = false },
            title = { Text("Discard all retained route points?") },
            text = { Text("This removes every saved route trace from runway. Activity totals and heart-rate data remain. This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    confirmingRouteDiscard = false
                    onAction(UpdateRouteDataModeCommand("discard"))
                }, enabled = !actionPending) { Text("Discard route points") }
            },
            dismissButton = { TextButton(onClick = { confirmingRouteDiscard = false }) { Text("Cancel") } },
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

@Composable
private fun SettingsRail(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            modifier = Modifier
                .padding(top = 12.dp, bottom = 6.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider()
        content()
        HorizontalDivider()
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    value: String,
    actionLabel: String,
    enabled: Boolean,
    monospaceValue: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospaceValue) FontFamily.Monospace else FontFamily.SansSerif,
            )
        }
        Text(
            actionLabel,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SettingsValueRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
        )
    }
}

@Composable
private fun TechnicalValueRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            value,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SettingsInlineActions(
    actionPending: Boolean,
    onTest: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onTest, enabled = !actionPending, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Test")
        }
        TextButton(onClick = onSync, enabled = !actionPending, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Sync")
        }
        TextButton(onClick = onDisconnect, enabled = !actionPending, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Disconnect")
        }
    }
}

internal fun healthContextSummary(injuryFlags: NativeInjuryFlags?): String {
    if (injuryFlags == null) return "Nothing saved"
    return when {
        injuryFlags.currentPain == true -> "Current pain reported"
        injuryFlags.medicalRestriction == true -> "Medical restriction saved"
        injuryFlags.recentInjury == true && injuryFlags.recurringPain == true ->
            "Recent and recurring issue history saved"
        injuryFlags.recentInjury == true -> "Recent injury history saved"
        injuryFlags.recurringPain == true -> "Recurring issue history saved"
        !injuryFlags.notes.isNullOrBlank() -> "Private note saved"
        else -> "Nothing saved"
    }
}

internal fun phoneImportSummary(health: NativeHealthConnectStatus?): String =
    when (health?.state) {
        "connected" -> "Health Connect connected · GPX file and folder options"
        "needs_attention" -> health.message?.takeIf(String::isNotBlank)
            ?: "Health Connect needs attention"
        "unavailable" -> health.message?.takeIf(String::isNotBlank)
            ?: "Health Connect unavailable"
        else -> "Health Connect not connected · GPX options available"
    }

internal fun nextcloudSourceSummary(sources: List<NativeImportSource>): String {
    if (sources.isEmpty()) return "None configured"
    val needsAttention = sources.count { !it.lastError.isNullOrBlank() }
    val active = sources.count { it.enabled == true && it.lastError.isNullOrBlank() }
    val paused = sources.size - active - needsAttention
    return buildList {
        if (needsAttention > 0) add("$needsAttention need attention")
        if (active > 0) add("$active active")
        if (paused > 0) add("$paused paused")
    }.joinToString(" · ")
}
