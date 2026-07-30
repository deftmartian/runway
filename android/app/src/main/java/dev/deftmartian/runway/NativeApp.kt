package dev.deftmartian.runway

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
) {
    val parent = state.surface.navigationParent()
    BackHandler(enabled = parent != null) { parent?.let(onDestinationSelected) }
    Scaffold(
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RunwayMark()
                        Spacer(Modifier.size(10.dp))
                        Text("runway", fontWeight = FontWeight.SemiBold)
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
                    val selected = state.destination.primaryNavigationDestination()
                    NativeDestination.entries
                        .filter(NativeDestination::primaryNavigation)
                        .forEach { destination ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("primary-destination-${destination.view}"),
                                selected = selected == destination,
                                onClick = { onDestinationSelected(destination) },
                                icon = { Icon(painterResource(destination.iconRes), null) },
                                label = {
                                    Text(
                                        text = destination.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                alwaysShowLabel = true,
                            )
                        }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.notice?.let { Notice(it.message, it.isError) }
            when (val surface = state.surface) {
                is NativeSurface.Setup ->
                    SetupScreen(surface.payload, state.actionPending, onAction)
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

internal fun NativeSurface.navigationParent(): NativeDestination? = when (this) {
    is NativeSurface.Setup ->
        if (payload?.activeGoal != null) NativeDestination.History else null
    else -> destination.navigationParent
}

@Composable
internal fun RunwayMark() {
    val rail = MaterialTheme.colorScheme.primary
    val center = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(30.dp, 22.dp)) {
        val left = size.width * .22f
        val right = size.width * .78f
        drawLine(rail, Offset(left, 0f), Offset(left, size.height), 4f, cap = StrokeCap.Round)
        drawLine(rail, Offset(right, 0f), Offset(right, size.height), 4f, cap = StrokeCap.Round)
        val dash = size.height / 5f
        repeat(3) { index ->
            drawLine(
                center,
                Offset(size.width / 2f, index * dash * 2f),
                Offset(
                    size.width / 2f,
                    ((index * dash * 2f) + dash).coerceAtMost(size.height),
                ),
                2f,
                cap = StrokeCap.Round,
            )
        }
    }
}
