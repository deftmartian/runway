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
    onRenamePasskey: (String, String) -> Unit,
    onDeletePasskey: (String) -> Unit,
    onExportTrainingData: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onReauthenticate: () -> Unit,
) {
    var changingPassword by remember { mutableStateOf(false) }
    var enablingTwoFactor by remember { mutableStateOf(false) }
    var disablingTwoFactor by remember { mutableStateOf(false) }
    var replacingRecoveryCodes by remember { mutableStateOf(false) }
    var revokingDevice by remember { mutableStateOf<NativeAccountImportDevice?>(null) }
    var revokingSession by remember { mutableStateOf<NativeAccountSession?>(null) }
    var renamingPasskey by remember { mutableStateOf<NativePasskeySecurity?>(null) }
    var deletingPasskey by remember { mutableStateOf<NativePasskeySecurity?>(null) }
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
            val authentication = payload.authentication
            if (!freshAccountSession) {
                item {
                    SettingCard("Confirm sensitive changes") {
                        Text(
                            "Password, passkey, session, export, and account changes need a recent sign-in.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onReauthenticate,
                            enabled = !actionPending,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Sign in again")
                        }
                    }
                }
            }
            item {
                SettingCard("Sign-in methods") {
                    SettingRow("Local password", if (authentication?.localPassword == true) "Available" else "Not set")
                    SettingRow("Single sign-on", if (authentication?.oidc == true) "Connected" else "Not connected")
                    SettingRow("Two-factor authentication", if (authentication?.twoFactor == true) "Enabled" else "Not enabled")
                    SettingRow("Passkeys", "${authentication?.passkeyCount ?: 0} registered")
                    when {
                        authentication?.localPassword == true -> {
                            if (!freshAccountSession) {
                                Text(
                                    "Sign in again above before changing your password or authenticator settings.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = { changingPassword = true },
                                enabled = !actionPending && freshAccountSession,
                                shape = MaterialTheme.shapes.small,
                            ) { Text("Change password") }
                            TextButton(
                                onClick = onRequestPasswordReset,
                                enabled = !actionPending,
                            ) { Text("Send password reset email") }
                            if (authentication.twoFactor == true) {
                                OutlinedButton(
                                    onClick = { replacingRecoveryCodes = true },
                                    enabled = !actionPending && freshAccountSession,
                                    shape = MaterialTheme.shapes.small,
                                ) { Text("Replace recovery codes") }
                                OutlinedButton(
                                    onClick = { disablingTwoFactor = true },
                                    enabled = !actionPending && freshAccountSession,
                                    shape = MaterialTheme.shapes.small,
                                ) { Text("Disable authenticator") }
                            } else if (!ephemeral.setupPending) {
                                OutlinedButton(
                                    onClick = { enablingTwoFactor = true },
                                    enabled = !actionPending && freshAccountSession,
                                    shape = MaterialTheme.shapes.small,
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
                }
            }
            item {
                SettingCard("Passkeys") {
                    when {
                        !freshAccountSession -> Text(
                            "Sign in again above to see and manage passkeys.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        payload.passkeys.isEmpty() -> Text(
                            "No passkeys registered.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> payload.passkeys.forEach { passkey ->
                            val removingWouldLockAccount =
                                authentication?.localPassword != true &&
                                    authentication?.oidc != true &&
                                    (authentication?.passkeyCount ?: 0) <= 1
                            val label = passkey.name.orEmpty().ifBlank { "Passkey" }
                            val details = buildList {
                                passkey.deviceType?.takeIf(String::isNotBlank)?.let(::add)
                                if (passkey.backedUp == true) add("Backed up")
                                passkey.createdAt?.takeIf(String::isNotBlank)?.let { add("Added $it") }
                            }.joinToString(" · ").ifBlank { "Registered passkey" }
                            SettingRow(label, details)
                            if (!passkey.id.isNullOrBlank()) {
                                TextButton(
                                    onClick = { renamingPasskey = passkey },
                                    enabled = !actionPending,
                                ) { Text("Rename") }
                                TextButton(
                                    onClick = { deletingPasskey = passkey },
                                    enabled = !actionPending && !removingWouldLockAccount,
                                ) { Text("Remove") }
                                if (removingWouldLockAccount) {
                                    Text(
                                        "Add another sign-in method before removing this passkey.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "Add a passkey in runway in a browser. Android can manage registered passkeys but cannot create one for this website.",
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
                                "Sign in again above to review or end other sessions.",
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
                        shape = MaterialTheme.shapes.small,
                    ) { Text("Choose export document") }
                }
            }
            item {
                SettingCard("Imported activity data") {
                    Text("Deletes imported GPX and Health Connect activities, disconnects import folders, and revokes all import devices. Manual runs remain.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { deletingImportedData = true },
                        enabled = !actionPending,
                        shape = MaterialTheme.shapes.small,
                    ) { Text("Delete imported activity data") }
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
                        shape = MaterialTheme.shapes.small,
                    ) { Text("Delete account") }
                }
            }
            item {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !actionPending,
                    shape = MaterialTheme.shapes.small,
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
    renamingPasskey?.let { passkey ->
        RenamePasskeyDialog(
            currentName = passkey.name.orEmpty(),
            actionPending = actionPending,
            onDismiss = { renamingPasskey = null },
            onConfirm = { name ->
                renamingPasskey = null
                passkey.id?.let { onRenamePasskey(it, name) }
            },
        )
    }
    deletingPasskey?.let { passkey ->
        AlertDialog(
            onDismissRequest = { deletingPasskey = null },
            title = { Text("Remove this passkey?") },
            text = {
                Text(
                    "${passkey.name.orEmpty().ifBlank { "This passkey" }} will no longer be able to sign in to runway. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deletingPasskey = null
                        passkey.id?.let(onDeletePasskey)
                    },
                    enabled = !actionPending && !passkey.id.isNullOrBlank(),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deletingPasskey = null }) { Text("Cancel") } },
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
