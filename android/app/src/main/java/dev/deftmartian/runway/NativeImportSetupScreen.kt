package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class NativeImportSetupDialog {
    None,
    HealthPermission,
    HealthBackgroundPermission,
    ForgetUnrevoked,
    RouteConsent,
}

internal data class NativeImportSetupUiState(
    val serverOrigin: String = "",
    val deviceLabel: String = "",
    val setupStatus: String = "",
    val setupReady: Boolean = false,
    val pairingStatus: String = "",
    val folderStatus: String = "",
    val backgroundStatus: String = "",
    val lastCheckStatus: String = "",
    val healthConnectStatus: String = "",
    val primaryActionLabel: String = "",
    val primaryActionEnabled: Boolean = true,
    val showPairingForm: Boolean = false,
    val showForgetAccount: Boolean = false,
    val forgetActionEnabled: Boolean = true,
    val showChangeFolder: Boolean = false,
    val showDisconnectFolder: Boolean = false,
    val backgroundActionLabel: String = "",
    val backgroundActionEnabled: Boolean = false,
    val showHealthPermission: Boolean = false,
    val showHealthSync: Boolean = false,
    val healthSyncActionEnabled: Boolean = false,
    val showHealthBackground: Boolean = false,
    val healthBackgroundActionLabel: String = "",
    val healthBackgroundActionEnabled: Boolean = false,
    val oneOffImportEnabled: Boolean = false,
    val oneOffImportStatus: String = "",
    val dialog: NativeImportSetupDialog = NativeImportSetupDialog.None,
)

@Composable
internal fun NativeImportSetupScreen(
    state: NativeImportSetupUiState,
    onDeviceLabelChange: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    onChangeServer: () -> Unit,
    onForgetAccount: () -> Unit,
    onChangeFolder: () -> Unit,
    onDisconnectFolder: () -> Unit,
    onBackgroundAction: () -> Unit,
    onHealthPermission: () -> Unit,
    onHealthSync: () -> Unit,
    onHealthBackground: () -> Unit,
    onPickOneOffGpx: () -> Unit,
    onReturnToRunway: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmDialog: (NativeImportSetupDialog) -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        NativeList(loading = false) {
            item { ScreenIntro(stringResource(R.string.folder_screen_title), stringResource(R.string.folder_screen_intro)) }
        item {
            ImportSetupSection("One-off GPX") {
                Text("Choose one GPX from this phone. It goes to Review and never completes a workout automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onPickOneOffGpx, enabled = state.oneOffImportEnabled) { Text("Choose GPX file") }
                state.oneOffImportStatus.takeIf(String::isNotBlank)?.let { LiveStatus(it) }
            }
        }
        item {
            Notice(if (state.setupReady) "✓ ${state.setupStatus}" else "! ${state.setupStatus}")
        }
        item {
            ImportSetupSection(stringResource(R.string.server_section_title)) {
                Text(state.serverOrigin, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onChangeServer) { Text(stringResource(R.string.change_server)) }
            }
        }
        item {
            ImportSetupSection(stringResource(R.string.pairing_title)) {
                LiveStatus(state.pairingStatus)
                if (state.showPairingForm) {
                    OutlinedTextField(
                        value = state.deviceLabel,
                        onValueChange = onDeviceLabelChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.device_label)) },
                        singleLine = true,
                    )
                }
                if (state.showForgetAccount) {
                    TextButton(onClick = onForgetAccount, enabled = state.forgetActionEnabled) { Text(stringResource(R.string.forget_account)) }
                }
            }
        }
        item { Button(onClick = onPrimaryAction, enabled = state.primaryActionEnabled, modifier = Modifier.fillMaxWidth()) { Text(state.primaryActionLabel) } }
        item {
            ImportSetupSection(stringResource(R.string.folder_section_title)) {
                LiveStatus(state.folderStatus)
                if (state.showChangeFolder || state.showDisconnectFolder) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (state.showChangeFolder) OutlinedButton(onClick = onChangeFolder, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.change_folder)) }
                        if (state.showDisconnectFolder) TextButton(onClick = onDisconnectFolder, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.disconnect_folder)) }
                    }
                }
            }
        }
        item {
            ImportSetupSection(stringResource(R.string.background_title)) {
                LiveStatus(state.backgroundStatus)
                Text(state.lastCheckStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onBackgroundAction, enabled = state.backgroundActionEnabled) { Text(state.backgroundActionLabel) }
            }
        }
        item {
            ImportSetupSection(stringResource(R.string.health_connect_title)) {
                LiveStatus(state.healthConnectStatus)
                if (state.showHealthPermission) OutlinedButton(onClick = onHealthPermission) { Text(stringResource(R.string.health_connect_grant_permission)) }
                if (state.showHealthSync) Button(onClick = onHealthSync, enabled = state.healthSyncActionEnabled) { Text(stringResource(R.string.health_connect_sync_now)) }
                if (state.showHealthBackground) TextButton(onClick = onHealthBackground, enabled = state.healthBackgroundActionEnabled) { Text(state.healthBackgroundActionLabel) }
            }
        }
            item {
                Text(stringResource(R.string.folder_upload_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onReturnToRunway) { Text(stringResource(R.string.return_to_runway)) }
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
    Text(text, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ImportSetupDialog(
    dialog: NativeImportSetupDialog,
    onDismiss: () -> Unit,
    onConfirm: (NativeImportSetupDialog) -> Unit,
) {
    val content = when (dialog) {
        NativeImportSetupDialog.HealthPermission -> stringResource(R.string.health_connect_permission_needed)
        NativeImportSetupDialog.HealthBackgroundPermission -> stringResource(R.string.health_connect_background_permission_needed)
        NativeImportSetupDialog.ForgetUnrevoked -> stringResource(R.string.pairing_disconnect_unavailable_consequence)
        NativeImportSetupDialog.RouteConsent -> stringResource(R.string.health_connect_route_consent)
        NativeImportSetupDialog.None -> null
    } ?: return
    val confirm = when (dialog) {
        NativeImportSetupDialog.ForgetUnrevoked -> stringResource(R.string.forget_anyway)
        NativeImportSetupDialog.RouteConsent -> stringResource(R.string.health_connect_route_allow)
        else -> stringResource(R.string.health_connect_grant_permission)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (dialog == NativeImportSetupDialog.ForgetUnrevoked) R.string.pairing_disconnect_unavailable_title else R.string.health_connect_title)) },
        text = { Text(content) },
        confirmButton = { TextButton(onClick = { onConfirm(dialog) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
