package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class NativeImportDialog {
    None,
    HealthPermission,
    HealthBackgroundPermission,
    RouteConsent,
}

internal data class NativeImportSettingsUiState(
    val oneOffStatus: String = "",
    val folderStatus: String = "",
    val folderConnected: Boolean = false,
    val folderPermissionRequired: Boolean = false,
    val folderCheckRunning: Boolean = false,
    val periodicFolderChecks: Boolean = false,
    val lastFolderCheck: String = "",
    val healthStatus: String = "",
    val healthAvailable: Boolean = false,
    val healthPermissionsGranted: Boolean = false,
    val healthSyncRunning: Boolean = false,
    val healthBackgroundSupported: Boolean = false,
    val healthBackgroundEnabled: Boolean = false,
    val dialog: NativeImportDialog = NativeImportDialog.None,
)

@Composable
internal fun NativeImportSetupScreen(
    state: NativeImportSettingsUiState,
    onPickOneOffGpx: () -> Unit,
    onChooseFolder: () -> Unit,
    onDisconnectFolder: () -> Unit,
    onCheckFolder: () -> Unit,
    onTogglePeriodicFolderChecks: () -> Unit,
    onHealthPermission: () -> Unit,
    onHealthSync: () -> Unit,
    onToggleHealthBackground: () -> Unit,
    onReturnToRunway: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmDialog: (NativeImportDialog) -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        NativeList(loading = false) {
            item {
                ScreenIntro(
                    stringResource(R.string.folder_screen_title),
                    stringResource(R.string.folder_screen_intro),
                )
            }
            item {
                ImportSetupSection(stringResource(R.string.one_off_gpx_title)) {
                    Text(
                        stringResource(R.string.one_off_gpx_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onPickOneOffGpx,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(R.string.choose_gpx_file))
                    }
                    state.oneOffStatus.takeIf(String::isNotBlank)?.let { LiveStatus(it) }
                }
            }
            item {
                ImportSetupSection(stringResource(R.string.folder_section_title)) {
                    LiveStatus(state.folderStatus)
                    Text(
                        stringResource(R.string.folder_review_boundary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = onChooseFolder,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                stringResource(
                                    if (state.folderConnected || state.folderPermissionRequired) {
                                        R.string.change_folder
                                    } else {
                                        R.string.choose_folder
                                    },
                                ),
                            )
                        }
                        if (state.folderConnected || state.folderPermissionRequired) {
                            TextButton(
                                onClick = onDisconnectFolder,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.disconnect_folder))
                            }
                        }
                    }
                    if (state.folderConnected) {
                        Button(
                            onClick = onCheckFolder,
                            enabled = !state.folderCheckRunning,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                stringResource(
                                    if (state.folderCheckRunning) {
                                        R.string.checking_now
                                    } else {
                                        R.string.check_now
                                    },
                                ),
                            )
                        }
                        TextButton(onClick = onTogglePeriodicFolderChecks) {
                            Text(
                                stringResource(
                                    if (state.periodicFolderChecks) {
                                        R.string.disable_background
                                    } else {
                                        R.string.enable_background
                                    },
                                ),
                            )
                        }
                    }
                    state.lastFolderCheck.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                ImportSetupSection(stringResource(R.string.health_connect_title)) {
                    LiveStatus(state.healthStatus)
                    when {
                        !state.healthAvailable -> Unit
                        !state.healthPermissionsGranted -> OutlinedButton(
                            onClick = onHealthPermission,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(stringResource(R.string.health_connect_grant_permission))
                        }
                        else -> {
                            Button(
                                onClick = onHealthSync,
                                enabled = !state.healthSyncRunning,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    stringResource(
                                        if (state.healthSyncRunning) {
                                            R.string.health_connect_syncing_action
                                        } else {
                                            R.string.health_connect_sync_now
                                        },
                                    ),
                                )
                            }
                            if (state.healthBackgroundSupported) {
                                TextButton(onClick = onToggleHealthBackground) {
                                    Text(
                                        stringResource(
                                            if (state.healthBackgroundEnabled) {
                                                R.string.health_connect_disable_background
                                            } else {
                                                R.string.health_connect_enable_background
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onReturnToRunway) {
                    Text(stringResource(R.string.return_to_runway))
                }
            }
        }
    }
    ImportSetupDialog(state.dialog, onDismissDialog, onConfirmDialog)
}

@Composable
private fun ImportSetupSection(title: String, content: @Composable () -> Unit) {
    LedgerSurface {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun LiveStatus(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ImportSetupDialog(
    dialog: NativeImportDialog,
    onDismiss: () -> Unit,
    onConfirm: (NativeImportDialog) -> Unit,
) {
    val content = when (dialog) {
        NativeImportDialog.HealthPermission ->
            stringResource(R.string.health_connect_permission_needed)
        NativeImportDialog.HealthBackgroundPermission ->
            stringResource(R.string.health_connect_background_permission_needed)
        NativeImportDialog.RouteConsent ->
            stringResource(R.string.health_connect_route_consent)
        NativeImportDialog.None -> null
    } ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.health_connect_title)) },
        text = { Text(content) },
        confirmButton = {
            TextButton(onClick = { onConfirm(dialog) }) {
                Text(
                    stringResource(
                        if (dialog == NativeImportDialog.RouteConsent) {
                            R.string.health_connect_route_allow
                        } else {
                            R.string.health_connect_grant_permission
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
