package dev.deftmartian.runway

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RunwayNativeApp(
    state: RunwayUiState,
    onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit,
    onLoadMoreHistory: () -> Unit,
    onLoadMoreInbox: () -> Unit,
    onLoadActivityTrace: (String) -> Unit,
    onOpenHistoryDetail: (String) -> Unit,
    onRetryOpen: () -> Unit,
    onAction: (MobileCommand) -> Unit,
    onApplyWorkoutPreview: () -> Unit,
    onDismissWorkoutPreview: () -> Unit,
    onApplyPlanDecisionPreview: () -> Unit,
    onDismissPlanDecisionPreview: () -> Unit,
    onOpenFolder: () -> Unit,
    onImportGpx: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onExportData: () -> Unit,
    onTimeZoneChanged: (String) -> Unit,
    onRoutePrivacyChanged: (NativeRoutePrivacy) -> Unit,
    onHeartRatePrivacyChanged: (NativeHeartRatePrivacy) -> Unit,
    onHeartRateChanged: (NativeHeartRateProfile) -> Unit,
    onHealthContextChanged: (NativeHealthContext) -> Unit,
    onEraseImportedActivityData: () -> Unit,
    onEraseAllData: () -> Unit,
    onAcknowledgeRetentionRepair: () -> Unit,
) = when (state) {
    RunwayUiState.Loading -> LoadingScreen()
    is RunwayUiState.Failed -> FailureScreen(state.message, onRetryOpen)
    is RunwayUiState.Ready -> NativeProductShell(
        state = state,
        onDestinationSelected = onDestinationSelected,
        onCalendarMonthSelected = onCalendarMonthSelected,
        onLoadMoreHistory = onLoadMoreHistory,
        onLoadMoreInbox = onLoadMoreInbox,
        onLoadActivityTrace = onLoadActivityTrace,
        onOpenHistoryDetail = onOpenHistoryDetail,
        onAction = onAction,
        onApplyWorkoutPreview = onApplyWorkoutPreview,
        onDismissWorkoutPreview = onDismissWorkoutPreview,
        onApplyPlanDecisionPreview = onApplyPlanDecisionPreview,
        onDismissPlanDecisionPreview = onDismissPlanDecisionPreview,
        onOpenFolder = onOpenFolder,
        onImportGpx = onImportGpx,
        onOpenHealthConnect = onOpenHealthConnect,
        onCreateBackup = onCreateBackup,
        onRestoreBackup = onRestoreBackup,
        onExportData = onExportData,
        onTimeZoneChanged = onTimeZoneChanged,
        onRoutePrivacyChanged = onRoutePrivacyChanged,
        onHeartRatePrivacyChanged = onHeartRatePrivacyChanged,
        onHeartRateChanged = onHeartRateChanged,
        onHealthContextChanged = onHealthContextChanged,
        onEraseImportedActivityData = onEraseImportedActivityData,
        onEraseAllData = onEraseAllData,
        onAcknowledgeRetentionRepair = onAcknowledgeRetentionRepair,
    )
}

@Composable
private fun LoadingScreen() = Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Opening your training log")
    }
}

@Composable
private fun FailureScreen(message: String, retry: () -> Unit) = Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn’t open Runway", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message)
        Spacer(Modifier.height(20.dp))
        Button(onClick = retry) { Text("Try again") }
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
    onAction: (MobileCommand) -> Unit,
    onApplyWorkoutPreview: () -> Unit,
    onDismissWorkoutPreview: () -> Unit,
    onApplyPlanDecisionPreview: () -> Unit,
    onDismissPlanDecisionPreview: () -> Unit,
    onOpenFolder: () -> Unit,
    onImportGpx: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onExportData: () -> Unit,
    onTimeZoneChanged: (String) -> Unit,
    onRoutePrivacyChanged: (NativeRoutePrivacy) -> Unit,
    onHeartRatePrivacyChanged: (NativeHeartRatePrivacy) -> Unit,
    onHeartRateChanged: (NativeHeartRateProfile) -> Unit,
    onHealthContextChanged: (NativeHealthContext) -> Unit,
    onEraseImportedActivityData: () -> Unit,
    onEraseAllData: () -> Unit,
    onAcknowledgeRetentionRepair: () -> Unit,
) {
    val parent = state.surface.navigationParent()
    BackHandler(enabled = parent != null) { parent?.let(onDestinationSelected) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val navigationLayout = nativeNavigationLayoutForWidth(maxWidth.value)
        val showPrimaryNavigation = state.destination != NativeDestination.Setup
        Row(Modifier.fillMaxSize()) {
            if (showPrimaryNavigation && navigationLayout == NativeNavigationLayout.Rail) {
                NativePrimaryNavigationRail(state.destination, onDestinationSelected)
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            parent?.let {
                                IconButton(onClick = { onDestinationSelected(it) }) {
                                    Icon(
                                        painterResource(R.drawable.ic_arrow_back),
                                        "Back to ${it.label}",
                                    )
                                }
                            }
                        },
                        title = {
                            Text(
                                state.destination.label,
                                modifier = Modifier.semantics { heading() },
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
                bottomBar = {
                    if (showPrimaryNavigation && navigationLayout == NativeNavigationLayout.BottomBar) {
                        NativePrimaryNavigationBar(state.destination, onDestinationSelected)
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    state.notice?.let { Notice(it.message, it.isError) }
                    when (val surface = state.surface) {
                        is NativeSurface.Setup ->
                            SetupScreen(
                                payload = surface.payload,
                                actionPending = state.actionPending,
                                onAction = onAction,
                                onRestoreBackup = onRestoreBackup,
                            )
                        is NativeSurface.Calendar -> CalendarScreen(
                            payload = surface.payload,
                            loading = state.loading,
                            actionPending = state.actionPending,
                            actionNotice = state.notice,
                            completedAction = state.completedAction,
                            workoutPreview = state.workoutPreview,
                            onCalendarMonthSelected = onCalendarMonthSelected,
                            activityEvidence = state.activityEvidence,
                            activityEvidenceLoading = state.activityEvidenceLoading,
                            activityEvidenceFailures = state.activityEvidenceFailures,
                            onLoadActivityTrace = onLoadActivityTrace,
                            onDestinationSelected = onDestinationSelected,
                            onAction = onAction,
                            onApplyWorkoutPreview = onApplyWorkoutPreview,
                            onDismissWorkoutPreview = onDismissWorkoutPreview,
                        )
                        is NativeSurface.Inbox -> InboxScreen(
                            payload = surface.payload,
                            loading = state.loading,
                            actionPending = state.actionPending,
                            actionNotice = state.notice,
                            completedAction = state.completedAction,
                            onAction = onAction,
                            activityEvidence = state.activityEvidence,
                            activityEvidenceLoading = state.activityEvidenceLoading,
                            activityEvidenceFailures = state.activityEvidenceFailures,
                            onLoadActivityTrace = onLoadActivityTrace,
                            onLoadMore = onLoadMoreInbox,
                            onImportGpx = onImportGpx,
                            onOpenImportSettings = {
                                onDestinationSelected(NativeDestination.Settings)
                            },
                        )
                        is NativeSurface.Stats -> StatsScreen(
                            payload = surface.payload,
                            loading = state.loading,
                            onDestinationSelected = onDestinationSelected,
                        )
                        is NativeSurface.History -> HistoryScreen(
                            payload = surface.payload,
                            loading = state.loading,
                            onLoadMore = onLoadMoreHistory,
                            onOpenPlan = onOpenHistoryDetail,
                            actionPending = state.actionPending,
                            onDestinationSelected = onDestinationSelected,
                            onAction = onAction,
                        )
                        is NativeSurface.Settings -> SettingsScreen(
                            state = surface.payload ?: NativeSettingsState(),
                            actionPending = state.actionPending,
                            callbacks = NativeSettingsCallbacks(
                                onTimeZoneChanged = onTimeZoneChanged,
                                onRoutePrivacyChanged = onRoutePrivacyChanged,
                                onHeartRatePrivacyChanged = onHeartRatePrivacyChanged,
                                onHeartRateChanged = onHeartRateChanged,
                                onHealthContextChanged = onHealthContextChanged,
                                onImportGpx = onImportGpx,
                                onOpenFolderImports = onOpenFolder,
                                onOpenHealthConnect = onOpenHealthConnect,
                                onCreateBackup = onCreateBackup,
                                onRestoreBackup = onRestoreBackup,
                                onExportData = onExportData,
                                onEraseImportedActivityData = onEraseImportedActivityData,
                                onEraseAllData = onEraseAllData,
                                onAcknowledgeRetentionRepair =
                                    onAcknowledgeRetentionRepair,
                            ),
                        )
                        is NativeSurface.HistoryDetail ->
                            HistoryDetailScreen(surface.payload, state.loading)
                    }
                    state.planDecisionPreview?.let { preview ->
                        PlanDecisionPreviewDialog(
                            preview = preview,
                            actionPending = state.actionPending,
                            errorMessage = state.notice?.takeIf(NativeNotice::isError)?.message,
                            onDismiss = onDismissPlanDecisionPreview,
                            onConfirm = onApplyPlanDecisionPreview,
                        )
                    }
                }
            }
        }
    }
}

internal enum class NativeNavigationLayout {
    BottomBar,
    Rail,
}

internal fun nativeNavigationLayoutForWidth(widthDp: Float): NativeNavigationLayout =
    if (widthDp >= NATIVE_NAVIGATION_RAIL_MIN_WIDTH_DP) {
        NativeNavigationLayout.Rail
    } else {
        NativeNavigationLayout.BottomBar
    }

private const val NATIVE_NAVIGATION_RAIL_MIN_WIDTH_DP = 600f

@Composable
private fun NativePrimaryNavigationBar(
    destination: NativeDestination,
    onDestinationSelected: (NativeDestination) -> Unit,
) {
    NavigationBar(modifier = Modifier.testTag("primary-navigation-bar")) {
        NativeDestination.entries
            .filter(NativeDestination::primaryNavigation)
            .forEach { item ->
                NavigationBarItem(
                    modifier = Modifier.testTag("primary-destination-${item.view}"),
                    selected = destination.primaryNavigationDestination() == item,
                    onClick = { onDestinationSelected(item) },
                    icon = { Icon(painterResource(item.iconRes), null) },
                    label = { NativeNavigationLabel(item) },
                    alwaysShowLabel = true,
                )
            }
    }
}

@Composable
private fun NativePrimaryNavigationRail(
    destination: NativeDestination,
    onDestinationSelected: (NativeDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .testTag("primary-navigation-rail"),
    ) {
        NativeDestination.entries
            .filter(NativeDestination::primaryNavigation)
            .forEach { item ->
                NavigationRailItem(
                    modifier = Modifier.testTag("primary-destination-${item.view}"),
                    selected = destination.primaryNavigationDestination() == item,
                    onClick = { onDestinationSelected(item) },
                    icon = { Icon(painterResource(item.iconRes), null) },
                    label = { NativeNavigationLabel(item) },
                    alwaysShowLabel = true,
                )
            }
    }
}

@Composable
private fun NativeNavigationLabel(destination: NativeDestination) {
    Text(
        text = destination.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
    )
}

internal fun NativeSurface.navigationParent(): NativeDestination? = when (this) {
    is NativeSurface.Setup ->
        if (payload?.currentGoal?.state == "active") NativeDestination.History else null
    else -> destination.navigationParent
}
