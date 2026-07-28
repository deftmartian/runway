package dev.deftmartian.runway

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

@Composable
internal fun AccountSecurityScreen(
    payload: NativeAccountSecurityPayload?,
    ephemeral: NativeAccountSecurityEphemeral,
    loading: Boolean,
    actionPending: Boolean,
    onBack: () -> Unit,
    onAction: (MobileCommand) -> Unit,
    onRequestPasswordReset: () -> Unit,
    onChangePassword: (String, String) -> Unit,
    onEnableTwoFactor: (String) -> Unit,
    onOpenAuthenticator: (String) -> Unit,
    onVerifyTwoFactorSetup: (String) -> Unit,
    onCancelTotpSetup: () -> Unit,
    onDisableTwoFactor: (String) -> Unit,
    onRegenerateRecoveryCodes: (String) -> Unit,
    onSaveRecoveryCodes: () -> Unit,
    onClearRecoveryCodes: () -> Unit,
    onRevokeSession: (String) -> Unit,
    onExportTrainingData: () -> Unit,
    onDeleteAccount: (String) -> Unit,
) {
    var changingPassword by remember { mutableStateOf(false) }
    var enablingTwoFactor by remember { mutableStateOf(false) }
    var disablingTwoFactor by remember { mutableStateOf(false) }
    var replacingRecoveryCodes by remember { mutableStateOf(false) }
    var revokingDevice by remember { mutableStateOf<NativeAccountImportDevice?>(null) }
    var revokingSession by remember { mutableStateOf<NativeAccountSession?>(null) }
    var deletingImportedData by remember { mutableStateOf(false) }
    var deletionConfirmation by rememberSaveable { mutableStateOf("") }
    var deletingAccount by remember { mutableStateOf(false) }
    var accountDeletionConfirmation by rememberSaveable { mutableStateOf("") }
    NativeList(loading) {
        item { ScreenIntro("Account security", "A private summary of sign-in and import access.") }
        if (payload == null) {
            item { EmptyCard("Loading account security…") }
        } else {
            val freshAccountSession = payload.sessions?.requiresFreshSession == false
            item {
                SettingCard("Sign-in methods") {
                    val authentication = payload.authentication
                    SettingRow("Local password", if (authentication?.localPassword == true) "Available" else "Not set")
                    SettingRow("Single sign-on", if (authentication?.oidc == true) "Connected" else "Not connected")
                    SettingRow("Two-factor authentication", if (authentication?.twoFactor == true) "Enabled" else "Not enabled")
                    SettingRow("Passkeys", "${authentication?.passkeyCount ?: 0} registered")
                    when {
                        authentication?.localPassword == true -> {
                            if (!freshAccountSession) {
                                Text(
                                    "Sign out and sign in again before changing your password or authenticator settings.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = { changingPassword = true },
                                enabled = !actionPending && freshAccountSession,
                            ) { Text("Change password") }
                            TextButton(
                                onClick = onRequestPasswordReset,
                                enabled = !actionPending,
                            ) { Text("Send password reset email") }
                            if (authentication.twoFactor == true) {
                                OutlinedButton(
                                    onClick = { replacingRecoveryCodes = true },
                                    enabled = !actionPending && freshAccountSession,
                                ) { Text("Replace recovery codes") }
                                OutlinedButton(
                                    onClick = { disablingTwoFactor = true },
                                    enabled = !actionPending && freshAccountSession,
                                ) { Text("Disable authenticator") }
                            } else if (!ephemeral.setupPending) {
                                OutlinedButton(
                                    onClick = { enablingTwoFactor = true },
                                    enabled = !actionPending && freshAccountSession,
                                ) { Text("Set up authenticator") }
                            }
                        }
                        authentication?.oidc == true -> {
                            Text(
                                "This account uses single sign-on. Password and authenticator settings are managed by your identity provider.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> {
                            Text(
                                "This account does not have a local password. Credential settings cannot be changed in Android.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "Passkey management is not available in the Android app yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingCard("Sessions") {
                    SettingRow("Active sessions", "${payload.sessions?.activeCount ?: 0}")
                    SettingRow("This session", if (payload.sessions?.currentIsNative == true) "Native Android" else "Unknown")
                    when {
                        payload.sessions?.requiresFreshSession == true -> {
                            Text(
                                "Sign out and sign in again to review or end other sessions.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        payload.sessions?.items.orEmpty().isEmpty() -> {
                            Text(
                                "No session details are available.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> payload.sessions?.items.orEmpty().forEach { session ->
                            SettingRow(
                                session.client.orEmpty().ifBlank { "Signed-in client" },
                                if (session.current == true) {
                                    "This session"
                                } else {
                                    session.updatedAt?.let { "Last active $it" } ?: "Active"
                                },
                            )
                            if (session.current != true && !session.id.isNullOrBlank()) {
                                TextButton(
                                    onClick = { revokingSession = session },
                                    enabled = !actionPending,
                                ) { Text("End session") }
                            }
                        }
                    }
                }
            }
            item {
                SettingCard("Import devices") {
                    if (payload.importDevices.isEmpty()) {
                        Text("No active import devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        payload.importDevices.forEach { device ->
                            SettingRow(device.label.orEmpty().ifBlank { "Android device" }, device.lastImportedAt?.let { "Last imported $it" } ?: "No imports recorded")
                            TextButton(onClick = { revokingDevice = device }, enabled = !actionPending) { Text("Revoke import access") }
                        }
                    }
                }
            }
            item {
                SettingCard("Training-data export") {
                    Text(
                        "Writes a fresh JSON export directly to a document you choose. Runway does not keep a second copy on this phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onExportTrainingData,
                        enabled = !actionPending && freshAccountSession,
                    ) { Text("Choose export document") }
                }
            }
            item {
                SettingCard("Imported activity data") {
                    Text("Deletes imported GPX and Health Connect activities, disconnects import folders, and revokes all import devices. Manual runs remain.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { deletingImportedData = true }, enabled = !actionPending) { Text("Delete imported activity data") }
                }
            }
            item {
                SettingCard("Delete account") {
                    Text(
                        "Permanently deletes this account and its runway data. This phone’s session, import credential, folder access, and local import state are cleared only after the server confirms deletion.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { deletingAccount = true },
                        enabled = !actionPending && freshAccountSession,
                    ) { Text("Delete account") }
                }
            }
            item {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !actionPending,
                ) { Text("Back to settings") }
            }
        }
    }
    revokingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { revokingSession = null },
            title = { Text("End this session?") },
            text = {
                Text(
                    "${session.client.orEmpty().ifBlank { "This client" }} will need to sign in again.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        revokingSession = null
                        session.id?.let(onRevokeSession)
                    },
                    enabled = !actionPending && !session.id.isNullOrBlank(),
                ) { Text("End session") }
            },
            dismissButton = {
                TextButton(onClick = { revokingSession = null }) { Text("Cancel") }
            },
        )
    }
    if (changingPassword) {
        PasswordChangeDialog(
            actionPending = actionPending,
            onDismiss = { changingPassword = false },
            onConfirm = onChangePassword,
        )
    }
    if (enablingTwoFactor) {
        EnableTwoFactorDialog(
            actionPending = actionPending,
            onDismiss = { enablingTwoFactor = false },
            onConfirm = onEnableTwoFactor,
        )
    }
    if (disablingTwoFactor) {
        DisableTwoFactorDialog(
            actionPending = actionPending,
            onDismiss = { disablingTwoFactor = false },
            onConfirm = onDisableTwoFactor,
        )
    }
    if (replacingRecoveryCodes) {
        ReplaceRecoveryCodesDialog(
            actionPending = actionPending,
            onDismiss = { replacingRecoveryCodes = false },
            onConfirm = onRegenerateRecoveryCodes,
        )
    }
    if (ephemeral.setupPending) {
        TotpSetupDialog(
            state = ephemeral,
            actionPending = actionPending,
            onOpenAuthenticator = onOpenAuthenticator,
            onVerify = onVerifyTwoFactorSetup,
            onCancel = onCancelTotpSetup,
        )
    }
    if (ephemeral.recoveryCodes.isNotEmpty()) {
        RecoveryCodesDialog(
            recoveryCodes = ephemeral.recoveryCodes,
            actionPending = actionPending,
            onSave = onSaveRecoveryCodes,
            onDone = onClearRecoveryCodes,
        )
    }
    revokingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { revokingDevice = null },
            title = { Text("Revoke import access?") },
            text = { Text("${device.label.orEmpty().ifBlank { "This Android device" }} will no longer be able to import activity data.") },
            confirmButton = { Button(onClick = { revokingDevice = null; onAction(RevokeAndroidDeviceCommand(device.id.orEmpty())) }, enabled = !actionPending && !device.id.isNullOrBlank()) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = { revokingDevice = null }) { Text("Cancel") } },
        )
    }
    if (deletingImportedData) {
        AlertDialog(
            onDismissRequest = { deletingImportedData = false },
            title = { Text("Delete imported activity data?") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("This permanently deletes imported GPX and Health Connect activities, disconnects folders, revokes import devices, and removes this phone’s import automation. Manual runs remain.")
                    androidx.compose.material3.OutlinedTextField(
                        value = deletionConfirmation,
                        onValueChange = { deletionConfirmation = it.take(64) },
                        label = { Text("Type DELETE IMPORTED ACTIVITY DATA") },
                        singleLine = true,
                        modifier = Modifier,
                    )
                }
            },
            confirmButton = { Button(onClick = { deletingImportedData = false; deletionConfirmation = ""; onAction(DeleteImportedActivityDataCommand) }, enabled = !actionPending && deletionConfirmation == "DELETE IMPORTED ACTIVITY DATA") { Text("Delete imported data") } },
            dismissButton = { TextButton(onClick = { deletingImportedData = false; deletionConfirmation = "" }) { Text("Cancel") } },
        )
    }
    if (deletingAccount) {
        AlertDialog(
            onDismissRequest = {
                deletingAccount = false
                accountDeletionConfirmation = ""
            },
            title = { Text("Delete your account?") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        "This permanently deletes the account, training plans, activities, imports, and account access. It cannot be undone.",
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = accountDeletionConfirmation,
                        onValueChange = { accountDeletionConfirmation = it.take(16) },
                        label = { Text("Type DELETE") },
                        singleLine = true,
                        modifier = Modifier,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deletingAccount = false
                        val confirmation = accountDeletionConfirmation
                        accountDeletionConfirmation = ""
                        onDeleteAccount(confirmation)
                    },
                    enabled = !actionPending && accountDeletionConfirmation == "DELETE",
                ) { Text("Delete account") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deletingAccount = false
                        accountDeletionConfirmation = ""
                    },
                ) { Text("Cancel") }
            },
        )
    }
}
