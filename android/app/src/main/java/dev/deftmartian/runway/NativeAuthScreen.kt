package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private enum class LocalAccountMode { SignIn, CreateAccount }

@Composable
internal fun NativeSignInScreen(
    state: RunwayUiState.SignedOut,
    onSignInLocal: (String, String) -> Unit,
    onSignUpLocal: (String, String, String) -> Unit,
    onVerifyTwoFactor: (String) -> Unit,
    onSelectSecondFactor: (NativeSecondFactor) -> Unit,
    onCancelTwoFactor: () -> Unit,
    onStartExternalAuthorization: () -> Unit,
    onOpenExternalAuthorization: (String) -> Unit,
    onCancelExternalAuthorization: () -> Unit,
    onRetryCapabilities: () -> Unit,
    onOpenPasswordReset: () -> Unit,
) {
    state.pending?.let { pending ->
        LaunchedEffect(pending.deviceCode) {
            onOpenExternalAuthorization(pending.verificationUri)
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    shadowElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        NativeAuthHeader()
                        when {
                            state.pending != null -> ExternalAuthorizationPending(
                                state = state,
                                onOpen = {
                                    onOpenExternalAuthorization(state.pending.verificationUri)
                                },
                                onCancel = onCancelExternalAuthorization,
                            )
                            state.challenge != null -> NativeTwoFactorForm(
                                state = state,
                                onVerify = onVerifyTwoFactor,
                                onSelectMethod = onSelectSecondFactor,
                                onCancel = onCancelTwoFactor,
                            )
                            state.capabilities == null -> AuthCapabilitiesLoading(
                                message = state.message,
                                onRetry = onRetryCapabilities,
                            )
                            else -> NativeAccountForm(
                                state = state,
                                onSignIn = onSignInLocal,
                                onSignUp = onSignUpLocal,
                                onStartExternal = onStartExternalAuthorization,
                                onOpenPasswordReset = onOpenPasswordReset,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeAuthHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RunwayMark()
            Text(
                "runway",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Training plan and log",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AuthCapabilitiesLoading(message: String?, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator()
        Text("Reading sign-in options from your server.")
        message?.let { Notice(it, isError = true) }
        if (message != null) {
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun NativeAccountForm(
    state: RunwayUiState.SignedOut,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onStartExternal: () -> Unit,
    onOpenPasswordReset: () -> Unit,
) {
    val capabilities = requireNotNull(state.capabilities)
    var mode by rememberSaveable { mutableStateOf(LocalAccountMode.SignIn) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val submit = {
        if (mode == LocalAccountMode.CreateAccount) {
            onSignUp(name, email, password)
        } else {
            onSignIn(email, password)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (capabilities.local) {
            if (capabilities.localSignups) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (mode == LocalAccountMode.SignIn) {
                        Button(
                            onClick = { mode = LocalAccountMode.SignIn },
                            modifier = Modifier.weight(1f),
                        ) { Text("Sign in") }
                    } else {
                        OutlinedButton(
                            onClick = { mode = LocalAccountMode.SignIn },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Sign in")
                        }
                    }
                    if (mode == LocalAccountMode.CreateAccount) {
                        Button(
                            onClick = { mode = LocalAccountMode.CreateAccount },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Create account")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { mode = LocalAccountMode.CreateAccount },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Create account")
                        }
                    }
                }
            }
            Text(
                if (mode == LocalAccountMode.CreateAccount) {
                    "Create a local account on this runway server."
                } else {
                    "Sign in to this runway server."
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (mode == LocalAccountMode.CreateAccount) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !state.signingIn,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(320) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") },
                singleLine = true,
                enabled = !state.signingIn,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        if (mode == LocalAccountMode.CreateAccount) {
                            "Password · at least 12 characters"
                        } else {
                            "Password"
                        },
                    )
                },
                singleLine = true,
                enabled = !state.signingIn,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Button(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.signingIn &&
                    email.isNotBlank() &&
                    password.isNotBlank() &&
                    (mode != LocalAccountMode.CreateAccount || name.isNotBlank()),
            ) {
                if (state.signingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        if (mode == LocalAccountMode.CreateAccount) {
                            "Create account"
                        } else {
                            "Sign in"
                        },
                    )
                }
            }
            if (mode == LocalAccountMode.SignIn) {
                TextButton(onClick = onOpenPasswordReset, enabled = !state.signingIn) {
                    Text("Reset password")
                }
            }
        }
        if (capabilities.oidc || capabilities.passkeys) {
            if (capabilities.local) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Other sign-in methods",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onStartExternal,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.starting && !state.signingIn,
            ) {
                Text(
                    when {
                        capabilities.oidc && capabilities.passkeys ->
                            "Organization sign-in or passkey"
                        capabilities.oidc -> "Organization sign-in"
                        else -> "Use a passkey"
                    },
                )
            }
            Text(
                "This opens your server’s secure sign-in page and returns here automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.message?.let { Notice(it, isError = true) }
    }
}

@Composable
private fun NativeTwoFactorForm(
    state: RunwayUiState.SignedOut,
    onVerify: (String) -> Unit,
    onSelectMethod: (NativeSecondFactor) -> Unit,
    onCancel: () -> Unit,
) {
    val challenge = requireNotNull(state.challenge)
    var code by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Verify sign-in",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (challenge.methods.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactorButton(
                    label = "Authenticator",
                    selected = state.selectedSecondFactor == NativeSecondFactor.Totp,
                    enabled = NativeSecondFactor.Totp in challenge.methods && !state.signingIn,
                    onClick = { onSelectMethod(NativeSecondFactor.Totp) },
                )
                FactorButton(
                    label = "Backup code",
                    selected = state.selectedSecondFactor == NativeSecondFactor.BackupCode,
                    enabled = NativeSecondFactor.BackupCode in challenge.methods && !state.signingIn,
                    onClick = { onSelectMethod(NativeSecondFactor.BackupCode) },
                )
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = when (state.selectedSecondFactor) {
                    NativeSecondFactor.Totp -> it.filter(Char::isDigit).take(6)
                    NativeSecondFactor.BackupCode -> it.take(128)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (state.selectedSecondFactor == NativeSecondFactor.Totp) {
                        "Six-digit code"
                    } else {
                        "Backup code"
                    },
                )
            },
            singleLine = true,
            enabled = !state.signingIn,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (state.selectedSecondFactor == NativeSecondFactor.Totp) {
                    KeyboardType.NumberPassword
                } else {
                    KeyboardType.Password
                },
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onVerify(code) }),
        )
        Button(
            onClick = { onVerify(code) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.signingIn && code.isNotBlank(),
        ) {
            Text(if (state.signingIn) "Checking…" else "Verify")
        }
        TextButton(onClick = onCancel, enabled = !state.signingIn) {
            Text("Back to sign in")
        }
        state.message?.let { Notice(it, isError = true) }
    }
}

@Composable
private fun FactorButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

@Composable
private fun ExternalAuthorizationPending(
    state: RunwayUiState.SignedOut,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Finish secure sign-in",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Your server’s sign-in page is open. Runway will return here when it is complete.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CircularProgressIndicator()
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text("Open sign-in again")
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
        state.message?.let { Notice(it, isError = true) }
    }
}
