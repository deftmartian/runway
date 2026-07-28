package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

private val SensitiveDialogProperties = DialogProperties(
    securePolicy = SecureFlagPolicy.SecureOn,
)

@Composable
internal fun RenamePasskeyDialog(
    currentName: String,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName.take(80)) }
    fun dismiss() {
        name = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Rename passkey") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Passkey name") },
                singleLine = true,
                enabled = !actionPending,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val replacement = name.trim()
                    name = ""
                    onDismiss()
                    onConfirm(replacement)
                },
                enabled = !actionPending && name.trim().isNotEmpty(),
            ) { Text("Save name") }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !actionPending) { Text("Cancel") }
        },
    )
}

@Composable
internal fun PasswordChangeDialog(
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    fun clear() {
        currentPassword = ""
        newPassword = ""
        confirmation = ""
    }
    fun dismiss() {
        clear()
        onDismiss()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { dismiss() }
    AlertDialog(
        onDismissRequest = ::dismiss,
        properties = SensitiveDialogProperties,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Changing your password ends your other sessions.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecurePasswordField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = "Current password",
                    enabled = !actionPending,
                )
                SecurePasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = "New password · at least 12 characters",
                    enabled = !actionPending,
                )
                SecurePasswordField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = "Confirm new password",
                    enabled = !actionPending,
                )
                if (confirmation.isNotEmpty() && confirmation != newPassword) {
                    Text(
                        "The new passwords do not match.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val current = currentPassword
                    val replacement = newPassword
                    clear()
                    onDismiss()
                    onConfirm(current, replacement)
                },
                enabled = !actionPending &&
                    currentPassword.isNotEmpty() &&
                    newPassword.length >= 12 &&
                    newPassword == confirmation,
            ) { Text("Change password") }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !actionPending) { Text("Cancel") }
        },
    )
}

@Composable
internal fun EnableTwoFactorDialog(
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    PasswordConfirmationDialog(
        title = "Set up authenticator",
        explanation = "Enter your current password to create a private setup key.",
        confirmLabel = "Continue",
        actionPending = actionPending,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun DisableTwoFactorDialog(
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    PasswordConfirmationDialog(
        title = "Disable authenticator?",
        explanation =
            "Enter your current password. Authenticator codes will no longer protect sign-in.",
        confirmLabel = "Disable",
        actionPending = actionPending,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun ReplaceRecoveryCodesDialog(
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    PasswordConfirmationDialog(
        title = "Replace recovery codes?",
        explanation =
            "Replacing recovery codes immediately stops every existing recovery code. Enter your current password, then save the new set.",
        confirmLabel = "Replace codes",
        actionPending = actionPending,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun PasswordConfirmationDialog(
    title: String,
    explanation: String,
    confirmLabel: String,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    fun dismiss() {
        password = ""
        onDismiss()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { dismiss() }
    AlertDialog(
        onDismissRequest = ::dismiss,
        properties = SensitiveDialogProperties,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecurePasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Current password",
                    enabled = !actionPending,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val current = password
                    password = ""
                    onDismiss()
                    onConfirm(current)
                },
                enabled = !actionPending && password.isNotEmpty(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !actionPending) { Text("Cancel") }
        },
    )
}

@Composable
internal fun TotpSetupDialog(
    state: NativeAccountSecurityEphemeral,
    actionPending: Boolean,
    onOpenAuthenticator: (String) -> Unit,
    onVerify: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { code = "" }
    val setup = state.totpSetup
    AlertDialog(
        onDismissRequest = {
            code = ""
            onCancel()
        },
        properties = SensitiveDialogProperties,
        title = { Text("Set up authenticator") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (setup != null) {
                    Text(
                        "Open the setup link in an authenticator app. If no app accepts it, enter the key manually.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onOpenAuthenticator(setup.uri) },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open authenticator app") }
                    Text("Manual setup key", style = MaterialTheme.typography.labelLarge)
                    Text(
                        setup.manualSecret,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        "The setup key is no longer held in Runway. Enter the code shown by your authenticator, or cancel and restart setup for a new key.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { candidate ->
                        code = candidate.filter(Char::isDigit).take(6)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("6-digit code") },
                    singleLine = true,
                    enabled = !actionPending,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val verificationCode = code
                    code = ""
                    onVerify(verificationCode)
                },
                enabled = !actionPending && code.length == 6,
            ) { Text("Verify") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    code = ""
                    onCancel()
                },
                enabled = !actionPending,
            ) { Text("Cancel setup") }
        },
    )
}

@Composable
internal fun RecoveryCodesDialog(
    recoveryCodes: List<String>,
    actionPending: Boolean,
    onSave: () -> Unit,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDone,
        properties = SensitiveDialogProperties,
        title = { Text("Save recovery codes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Two-factor authentication is enabled. These codes are shown once. Each can be used once if your authenticator is unavailable.",
                )
                Text(
                    "Any previous recovery codes no longer work.",
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    recoveryCodes.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Save them to a private document. Runway never puts them on the clipboard.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !actionPending && recoveryCodes.isNotEmpty(),
            ) { Text("Save to document") }
        },
        dismissButton = {
            TextButton(onClick = onDone, enabled = !actionPending) { Text("Done") }
        },
    )
}

@Composable
private fun SecurePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(128)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
