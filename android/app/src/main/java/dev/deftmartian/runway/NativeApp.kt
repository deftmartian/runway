package dev.deftmartian.runway

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun RunwayNativeApp(
    state: RunwayUiState,
    onStartAuthorization: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onSignInLocal: (String, String) -> Unit,
    onSignUpLocal: (String, String, String) -> Unit,
    onVerifyTwoFactor: (String) -> Unit,
    onSelectSecondFactor: (NativeSecondFactor) -> Unit,
    onCancelTwoFactor: () -> Unit,
    onOpenExternalAuthorization: (String) -> Unit,
    onOpenPasswordReset: () -> Unit,
    onRetry: () -> Unit,
    onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit,
    onLoadMoreHistory: () -> Unit,
    onLoadMoreInbox: () -> Unit,
    onLoadActivityTrace: (String) -> Unit,
    onOpenHistoryDetail: (String) -> Unit,
    onRefresh: () -> Unit,
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
    onRevokeAccountSession: (String) -> Unit,
    onRenamePasskey: (String, String) -> Unit,
    onDeletePasskey: (String) -> Unit,
    onExportTrainingData: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onConfirmActionPreview: () -> Unit,
    onDismissActionPreview: () -> Unit,
    onSignOut: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    when (state) {
        RunwayUiState.Loading -> LoadingScreen()
        is RunwayUiState.SignedOut -> NativeSignInScreen(
            state = state,
            onSignInLocal = onSignInLocal,
            onSignUpLocal = onSignUpLocal,
            onVerifyTwoFactor = onVerifyTwoFactor,
            onSelectSecondFactor = onSelectSecondFactor,
            onCancelTwoFactor = onCancelTwoFactor,
            onStartExternalAuthorization = onStartAuthorization,
            onOpenExternalAuthorization = onOpenExternalAuthorization,
            onCancelExternalAuthorization = onCancelAuthorization,
            onRetryCapabilities = onRetry,
            onOpenPasswordReset = onOpenPasswordReset,
        )
        is RunwayUiState.Failed -> FailureScreen(state.message, onRetry)
        is RunwayUiState.Ready -> NativeProductShell(
            state = state,
            onDestinationSelected = onDestinationSelected,
            onCalendarMonthSelected = onCalendarMonthSelected,
            onLoadMoreHistory = onLoadMoreHistory,
            onLoadMoreInbox = onLoadMoreInbox,
            onLoadActivityTrace = onLoadActivityTrace,
            onOpenHistoryDetail = onOpenHistoryDetail,
            onRefresh = onRefresh,
            onAction = onAction,
            onRequestPasswordReset = onRequestPasswordReset,
            onChangePassword = onChangePassword,
            onEnableTwoFactor = onEnableTwoFactor,
            onOpenAuthenticator = onOpenAuthenticator,
            onVerifyTwoFactorSetup = onVerifyTwoFactorSetup,
            onCancelTotpSetup = onCancelTotpSetup,
            onDisableTwoFactor = onDisableTwoFactor,
            onRegenerateRecoveryCodes = onRegenerateRecoveryCodes,
            onSaveRecoveryCodes = onSaveRecoveryCodes,
            onClearRecoveryCodes = onClearRecoveryCodes,
            onRevokeAccountSession = onRevokeAccountSession,
            onRenamePasskey = onRenamePasskey,
            onDeletePasskey = onDeletePasskey,
            onExportTrainingData = onExportTrainingData,
            onDeleteAccount = onDeleteAccount,
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
        Button(onClick = onRetry, shape = MaterialTheme.shapes.small) {
            Text("Try again")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeProductShell(
    state: RunwayUiState.Ready,
    onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit,
    onLoadMoreHistory: () -> Unit,
    onLoadMoreInbox: () -> Unit,
    onLoadActivityTrace: (String) -> Unit,
    onOpenHistoryDetail: (String) -> Unit,
    onRefresh: () -> Unit,
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
    onRevokeAccountSession: (String) -> Unit,
    onRenamePasskey: (String, String) -> Unit,
    onDeletePasskey: (String) -> Unit,
    onExportTrainingData: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onConfirmActionPreview: () -> Unit,
    onDismissActionPreview: () -> Unit,
    onSignOut: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    val navigationParent =
        state.destination.navigationParent
            ?: NativeDestination.Calendar.takeIf {
                state.destination == NativeDestination.Setup &&
                    state.bootstrap.setupComplete == true
            }
    BackHandler(enabled = navigationParent != null && !state.actionPending) {
        navigationParent?.let(onDestinationSelected)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    navigationParent?.let { parent ->
                        IconButton(
                            onClick = { onDestinationSelected(parent) },
                            enabled = !state.actionPending,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Back to ${parent.label}",
                            )
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RunwayMark()
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "runway",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.loading && !state.actionPending,
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Refresh ${state.destination.label}",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (state.destination != NativeDestination.Setup) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    val selectedDestination =
                        state.destination.primaryNavigationDestination()
                    NativeDestination.entries
                        .filter(NativeDestination::primaryNavigation)
                        .forEach { destination ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("primary-destination-${destination.view}"),
                            selected = selectedDestination == destination,
                            enabled = !state.actionPending,
                            onClick = { onDestinationSelected(destination) },
                            icon = {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        painter = painterResource(destination.iconRes),
                                        contentDescription = null,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Box(
                                        Modifier
                                            .width(24.dp)
                                            .height(3.dp)
                                            .background(
                                                color = if (selectedDestination == destination) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    Color.Transparent
                                                },
                                                shape = MaterialTheme.shapes.extraSmall,
                                            ),
                                    )
                                }
                            },
                            label = { Text(destination.label) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
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
                NativeDestination.Calendar -> CalendarScreen(
                    payload = state.payload as? NativeCalendarPayload,
                    loading = state.loading,
                    actionPending = state.actionPending,
                    actionNotice = state.notice,
                    completedAction = state.completedAction,
                    onCalendarMonthSelected = onCalendarMonthSelected,
                    activityEvidence = state.activityEvidence,
                    activityEvidenceLoading = state.activityEvidenceLoading,
                    activityEvidenceFailures = state.activityEvidenceFailures,
                    onLoadActivityTrace = onLoadActivityTrace,
                    onDestinationSelected = onDestinationSelected,
                    onAction = onAction,
                )
                NativeDestination.Inbox -> InboxScreen(
                    state.payload as? NativeReviewPayload,
                    state.loading,
                    state.actionPending,
                    state.notice,
                    state.completedAction,
                    onAction,
                    onLoadMoreInbox,
                    state.activityEvidence,
                    state.activityEvidenceLoading,
                    state.activityEvidenceFailures,
                    onLoadActivityTrace,
                    onOpenFolder,
                    { onDestinationSelected(NativeDestination.Settings) },
                )
                NativeDestination.Stats -> StatsScreen(
                    state.payload as? NativeStatsPayload,
                    state.loading,
                    onDestinationSelected,
                )
                NativeDestination.History -> HistoryScreen(
                    payload = state.payload as? NativeHistoryPayload,
                    loading = state.loading,
                    actionPending = state.actionPending,
                    onLoadMore = onLoadMoreHistory,
                    onOpenPlan = onOpenHistoryDetail,
                    onDestinationSelected = onDestinationSelected,
                    onAction = onAction,
                )
                NativeDestination.Settings -> SettingsScreen(
                    payload = state.payload as? NativeSettingsPayload,
                    loading = state.loading,
                    actionPending = state.actionPending,
                    onAction = onAction,
                    onOpenServer = onOpenServer,
                    onOpenFolder = onOpenFolder,
                    onOpenAccountSecurity = { onDestinationSelected(NativeDestination.AccountSecurity) },
                    onSignOut = onSignOut,
                )
                NativeDestination.AccountSecurity -> AccountSecurityScreen(
                    payload = state.payload as? NativeAccountSecurityPayload,
                    ephemeral = state.accountSecurityEphemeral,
                    loading = state.loading,
                    actionPending = state.actionPending,
                    onBack = { onDestinationSelected(NativeDestination.Settings) },
                    onAction = onAction,
                    onRequestPasswordReset = onRequestPasswordReset,
                    onChangePassword = onChangePassword,
                    onEnableTwoFactor = onEnableTwoFactor,
                    onOpenAuthenticator = onOpenAuthenticator,
                    onVerifyTwoFactorSetup = onVerifyTwoFactorSetup,
                    onCancelTotpSetup = onCancelTotpSetup,
                    onDisableTwoFactor = onDisableTwoFactor,
                    onRegenerateRecoveryCodes = onRegenerateRecoveryCodes,
                    onSaveRecoveryCodes = onSaveRecoveryCodes,
                    onClearRecoveryCodes = onClearRecoveryCodes,
                    onRevokeSession = onRevokeAccountSession,
                    onRenamePasskey = onRenamePasskey,
                    onDeletePasskey = onDeletePasskey,
                    onExportTrainingData = onExportTrainingData,
                    onDeleteAccount = onDeleteAccount,
                    onReauthenticate = onSignOut,
                )
                NativeDestination.HistoryDetail -> HistoryDetailScreen(
                    payload = state.payload as? NativeHistoryDetailPayload,
                    loading = state.loading,
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

@Composable
internal fun RunwayMark() {
    val rail = MaterialTheme.colorScheme.primary
    val center = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(width = 30.dp, height = 22.dp),
    ) {
        val left = size.width * 0.22f
        val right = size.width * 0.78f
        drawLine(
            color = rail,
            start = Offset(left, 0f),
            end = Offset(left, size.height),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = rail,
            start = Offset(right, 0f),
            end = Offset(right, size.height),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )
        val dash = size.height / 5f
        repeat(3) { index ->
            val startY = index * dash * 2f
            drawLine(
                color = center,
                start = Offset(size.width / 2f, startY),
                end = Offset(size.width / 2f, (startY + dash).coerceAtMost(size.height)),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
        }
    }
}
