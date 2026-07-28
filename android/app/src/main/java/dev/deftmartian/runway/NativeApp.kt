package dev.deftmartian.runway

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun RunwayNativeApp(
    state: RunwayUiState,
    onStartAuthorization: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onRetry: () -> Unit,
    onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onAction: (MobileCommand) -> Unit,
    onConfirmActionPreview: () -> Unit,
    onDismissActionPreview: () -> Unit,
    onSignOut: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    when (state) {
        RunwayUiState.Loading -> LoadingScreen()
        is RunwayUiState.SignedOut -> DeviceAuthorizationScreen(
            state = state,
            onStartAuthorization = onStartAuthorization,
            onCancelAuthorization = onCancelAuthorization,
        )
        is RunwayUiState.Failed -> FailureScreen(state.message, onRetry)
        is RunwayUiState.Ready -> NativeProductShell(
            state = state,
            onDestinationSelected = onDestinationSelected,
            onCalendarMonthSelected = onCalendarMonthSelected,
            onRefresh = onRefresh,
            onAction = onAction,
            onConfirmActionPreview = onConfirmActionPreview,
            onDismissActionPreview = onDismissActionPreview,
            onSignOut = onSignOut,
            onOpenServer = onOpenServer,
            onOpenFolder = onOpenFolder,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Opening your training log")
        }
    }
}

@Composable
private fun FailureScreen(message: String, onRetry: () -> Unit) {
    CenteredSurface {
        Text("Couldn’t open Runway", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun DeviceAuthorizationScreen(
    state: RunwayUiState.SignedOut,
    onStartAuthorization: () -> Unit,
    onCancelAuthorization: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    CenteredSurface {
        Text(
            "runway",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text("Your training, on this phone.", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in in your browser, then return here. The browser keeps your account credentials; this app receives only its own session.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        when {
            state.starting -> CircularProgressIndicator()
            state.pending != null -> {
                Text("Enter this code if the browser asks:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        state.pending.userCode.chunked(4).joinToString(" "),
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { uriHandler.openUri(state.pending.verificationUri) }) {
                    Text("Open secure sign-in")
                }
                TextButton(onClick = onCancelAuthorization) { Text("Cancel") }
                Text(
                    "Waiting for approval…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Button(onClick = onStartAuthorization) { Text("Sign in") }
        }
        state.message?.let {
            Spacer(Modifier.height(16.dp))
            Notice(it, isError = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeProductShell(
    state: RunwayUiState.Ready,
    onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onAction: (MobileCommand) -> Unit,
    onConfirmActionPreview: () -> Unit,
    onDismissActionPreview: () -> Unit,
    onSignOut: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "runway",
                            modifier = Modifier.semantics { heading() },
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            listOfNotNull(
                                state.destination.label,
                                state.bootstrap.user?.name?.takeIf(String::isNotBlank),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !state.loading) {
                        Text(if (state.loading) "Loading" else "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (state.destination != NativeDestination.Setup) {
                NavigationBar {
                    NativeDestination.entries.filterNot { it == NativeDestination.Setup }.forEach { destination ->
                        NavigationBarItem(
                            selected = state.destination == destination,
                            onClick = { onDestinationSelected(destination) },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.iconRes),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.label) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.notice?.let { Notice(it.message, isError = it.isError) }
            when (state.destination) {
                NativeDestination.Setup -> SetupScreen(
                    payload = state.payload as? NativeOnboardingPayload,
                    actionPending = state.actionPending,
                    onAction = onAction,
                )
                NativeDestination.Today -> TodayScreen(
                    payload = state.payload as? NativeCalendarPayload,
                    loading = state.loading,
                    actionPending = state.actionPending,
                    actionNotice = state.notice,
                    completedAction = state.completedAction,
                    onDestinationSelected = onDestinationSelected,
                    onAction = onAction,
                )
                NativeDestination.Calendar -> CalendarScreen(
                    state.payload as? NativeCalendarPayload,
                    state.loading,
                    state.actionPending,
                    state.notice,
                    state.completedAction,
                    onCalendarMonthSelected,
                    onAction,
                )
                NativeDestination.Review -> ReviewScreen(
                    state.payload as? NativeReviewPayload,
                    state.loading,
                    state.actionPending,
                    state.notice,
                    state.completedAction,
                    onAction,
                )
                NativeDestination.Progress -> ProgressScreen(
                    state.payload as? NativeStatsPayload,
                    state.loading,
                    state.actionPending,
                    onDestinationSelected,
                    onAction,
                )
                NativeDestination.Settings -> SettingsScreen(
                    payload = state.payload as? NativeSettingsPayload,
                    loading = state.loading,
                    actionPending = state.actionPending,
                    onAction = onAction,
                    onOpenServer = onOpenServer,
                    onOpenFolder = onOpenFolder,
                    onSignOut = onSignOut,
                )
            }
        }
    }
    state.actionPreview?.let {
        WorkoutPreviewDialog(
            preview = it.preview,
            actionPending = state.actionPending,
            errorMessage = state.notice?.takeIf { notice -> notice.isError }?.message,
            onDismiss = onDismissActionPreview,
            onConfirm = onConfirmActionPreview,
        )
    }
}
