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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
    onRefresh: () -> Unit,
    onAction: (MobileCommand) -> Unit,
    onApplyWorkoutPreview: () -> Unit,
    onDismissWorkoutPreview: () -> Unit,
    onOpenFolder: () -> Unit,
    onImportGpx: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onExportData: () -> Unit,
    onTimeZoneChanged: (String) -> Unit,
    onRoutePrivacyChanged: (NativeRoutePrivacy) -> Unit,
    onHeartRateChanged: (NativeHeartRateProfile) -> Unit,
    onHealthContextChanged: (NativeHealthContext) -> Unit,
    onEraseAllData: () -> Unit,
) = when (state) {
    RunwayUiState.Loading -> LoadingScreen()
    is RunwayUiState.Failed -> FailureScreen(state.message, onRefresh)
    is RunwayUiState.Ready -> NativeProductShell(
        state, onDestinationSelected, onCalendarMonthSelected, onLoadMoreHistory, onLoadMoreInbox,
        onLoadActivityTrace, onOpenHistoryDetail, onRefresh, onAction, onApplyWorkoutPreview, onDismissWorkoutPreview, onOpenFolder, onImportGpx,
        onOpenHealthConnect, onCreateBackup, onRestoreBackup, onExportData, onTimeZoneChanged,
        onRoutePrivacyChanged, onHeartRateChanged, onHealthContextChanged, onEraseAllData,
    )
}

@Composable private fun LoadingScreen() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("Opening your training log") }
}
@Composable private fun FailureScreen(message: String, retry: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Couldn’t open Runway", fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(message); Spacer(Modifier.height(20.dp)); Button(onClick = retry) { Text("Try again") } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NativeProductShell(
    state: RunwayUiState.Ready, onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit, onLoadMoreHistory: () -> Unit, onLoadMoreInbox: () -> Unit,
    onLoadActivityTrace: (String) -> Unit, onOpenHistoryDetail: (String) -> Unit, onRefresh: () -> Unit,
    onAction: (MobileCommand) -> Unit, onApplyWorkoutPreview: () -> Unit, onDismissWorkoutPreview: () -> Unit,
    onOpenFolder: () -> Unit, onImportGpx: () -> Unit,
    onOpenHealthConnect: () -> Unit, onCreateBackup: () -> Unit, onRestoreBackup: () -> Unit,
    onExportData: () -> Unit, onTimeZoneChanged: (String) -> Unit,
    onRoutePrivacyChanged: (NativeRoutePrivacy) -> Unit, onHeartRateChanged: (NativeHeartRateProfile) -> Unit,
    onHealthContextChanged: (NativeHealthContext) -> Unit, onEraseAllData: () -> Unit,
) {
    val parent = state.destination.navigationParent
    BackHandler(enabled = parent != null) { parent?.let(onDestinationSelected) }
    Scaffold(
        topBar = { TopAppBar(
            navigationIcon = { parent?.let { IconButton(onClick = { onDestinationSelected(it) }) { Icon(painterResource(R.drawable.ic_arrow_back), "Back to ${it.label}") } } },
            title = { Row(verticalAlignment = Alignment.CenterVertically) { RunwayMark(); Spacer(Modifier.size(10.dp)); Text("runway", fontWeight = FontWeight.SemiBold) } },
            actions = { IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(painterResource(R.drawable.ic_refresh), "Refresh ${state.destination.label}") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        ) },
        bottomBar = { if (state.destination != NativeDestination.Setup) NavigationBar {
            val selected = state.destination.primaryNavigationDestination()
            NativeDestination.entries.filter(NativeDestination::primaryNavigation).forEach { destination ->
                NavigationBarItem(modifier = Modifier.testTag("primary-destination-${destination.view}"), selected = selected == destination, onClick = { onDestinationSelected(destination) }, icon = { Icon(painterResource(destination.iconRes), null) }, label = { Text(destination.label) }, alwaysShowLabel = true, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent))
            }
        } },
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        state.notice?.let { Notice(it.message, it.isError) }
        when (state.destination) {
            NativeDestination.Setup -> SetupScreen(state.payload as? NativeOnboardingPayload, state.actionPending, onAction)
            NativeDestination.Calendar -> CalendarScreen(state.payload as? NativeCalendarPayload, state.loading, state.actionPending, state.notice, state.completedAction, state.workoutPreview, onCalendarMonthSelected, state.activityEvidence, state.activityEvidenceLoading, state.activityEvidenceFailures, onLoadActivityTrace, onDestinationSelected, onAction, onApplyWorkoutPreview = onApplyWorkoutPreview, onDismissWorkoutPreview = onDismissWorkoutPreview)
            NativeDestination.Inbox -> InboxScreen(state.payload as? NativeReviewPayload, state.loading, state.actionPending, state.notice, state.completedAction, onAction, onLoadMoreInbox, state.activityEvidence, state.activityEvidenceLoading, state.activityEvidenceFailures, onLoadActivityTrace, onOpenFolder, { onDestinationSelected(NativeDestination.Settings) })
            NativeDestination.Stats -> StatsScreen(state.payload as? NativeStatsPayload, state.loading, onDestinationSelected)
            NativeDestination.History -> HistoryScreen(state.payload as? NativeHistoryPayload, state.loading, onLoadMoreHistory, onOpenHistoryDetail, state.actionPending, onDestinationSelected, onAction)
            NativeDestination.Settings -> SettingsScreen(
                state = state.payload as? NativeSettingsState ?: NativeSettingsState(),
                actionPending = state.actionPending,
                callbacks = NativeSettingsCallbacks(onTimeZoneChanged, onRoutePrivacyChanged, onHeartRateChanged,
                    onHealthContextChanged, onImportGpx, onOpenFolder, onOpenHealthConnect, onCreateBackup,
                    onRestoreBackup, onExportData, onEraseAllData),
            )
            NativeDestination.HistoryDetail -> HistoryDetailScreen(state.payload as? NativeHistoryDetailPayload, state.loading)
        }
    } }
}

@Composable internal fun RunwayMark() { val rail = MaterialTheme.colorScheme.primary; val center = MaterialTheme.colorScheme.onSurfaceVariant; Canvas(Modifier.size(30.dp, 22.dp)) { val left = size.width * .22f; val right = size.width * .78f; drawLine(rail, Offset(left, 0f), Offset(left, size.height), 4f, cap = StrokeCap.Round); drawLine(rail, Offset(right, 0f), Offset(right, size.height), 4f, cap = StrokeCap.Round); val dash = size.height / 5f; repeat(3) { index -> drawLine(center, Offset(size.width / 2f, index * dash * 2f), Offset(size.width / 2f, ((index * dash * 2f) + dash).coerceAtMost(size.height)), 2f, cap = StrokeCap.Round) } } }
