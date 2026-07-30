package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun InboxScreen(
    payload: NativeReviewPayload?,
    loading: Boolean,
    actionPending: Boolean,
    actionNotice: NativeNotice?,
    completedAction: String?,
    onAction: (MobileCommand) -> Unit,
    onLoadMore: () -> Unit,
    activityEvidence: Map<String, NativeActivityEvidence>,
    activityEvidenceLoading: Set<String>,
    activityEvidenceFailures: Set<String>,
    onLoadActivityTrace: (String) -> Unit,
    onOpenPhoneImports: () -> Unit,
    onOpenImportSettings: () -> Unit,
) {
    val candidates = payload?.candidates.orEmpty()
    val activities = payload?.activities.orEmpty()
    var selectedActivity by remember { mutableStateOf<NativeActivity?>(null) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedAction) {
        if (completedAction == submittedDialogAction) {
            if (completedAction == "preview_plan_decision" || completedAction == "apply_plan_decision") {
                pendingDecision = null
            } else {
                selectedActivity = null
            }
            submittedDialogAction = null
        }
    }
    NativeList(loading) {
        item {
            ScreenIntro(
                "Inbox",
                "Link each imported run, count it as extra training, or delete it.",
            )
        }
        when {
            payload == null -> item { EmptyCard("Loading activity review…") }
            activities.none { it.reviewState == "review" } ->
                item { EmptyCard("Nothing needs a decision right now.") }
            else -> {
                items(
                    activities.filter { it.reviewState == "review" },
                    key = { it.id.orEmpty() },
                ) { activity ->
                    ActivityCard(
                        activity = activity,
                        title = "Needs review",
                        actions = {
                            Button(
                                onClick = { selectedActivity = activity; activity.id?.let(onLoadActivityTrace) },
                                enabled = !actionPending,
                            ) {
                                Text("Review")
                            }
                        },
                    )
                }
            }
        }
        val acceptedActivities = activities.filter { it.reviewState != "review" }
        if (acceptedActivities.isNotEmpty()) {
            item { SectionLabel("Recent activity") }
            items(acceptedActivities, key = { "activity-${it.id.orEmpty()}" }) { activity ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityCard(
                        activity = activity,
                        actions = {
                            TextButton(
                                onClick = { selectedActivity = activity; activity.id?.let(onLoadActivityTrace) },
                                enabled = !actionPending,
                            ) {
                                Text("Open details")
                            }
                        },
                    )
                    activity.consequence?.let { consequence ->
                        ConsequenceChoices(
                            consequence = consequence,
                            actionPending = actionPending,
                            onDecision = { decision ->
                                pendingDecision = PendingPlanDecision(
                                    source = "activity",
                                    sourceId = activity.id.orEmpty(),
                                    decision = decision,
                                    consequence = consequence,
                                )
                            },
                        )
                    }
                }
            }
        }
        if (payload != null) {
            item { SectionLabel("Sources") }
            item {
                SettingCard("Import connections") {
                    SettingRow(
                        "This phone",
                        if (payload.androidDevices.isEmpty()) "Not connected" else "Connected",
                    )
                    OutlinedButton(
                        onClick = onOpenPhoneImports,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Folder and Health Connect")
                    }
                    if (payload.sources.isEmpty()) {
                        SettingRow("Server folders", "Not connected")
                    } else {
                        payload.sources.forEach { source ->
                            SettingRow(
                                source.label.orDash(),
                                source.lastError?.takeIf(String::isNotBlank)
                                    ?: if (source.enabled == true) "Connected" else "Disabled",
                            )
                        }
                    }
                    TextButton(
                        onClick = onOpenImportSettings,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Manage server folders")
                    }
                    SettingRow(
                        "Route points",
                        if (payload.routeDataMode == "discard") "Discarded" else "Kept privately",
                    )
                }
            }
        }
        if (payload?.activityPage?.nextOffset != null) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !loading && !actionPending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (loading) "Loading…" else "Load earlier activity") }
            }
        }
    }
    selectedActivity?.let { activity ->
        ImportedActivityDetailSheet(
            activity = activity,
            candidates = candidates,
            evidence = activity.id?.let { activityEvidence[it] },
            evidenceLoading = activity.id?.let(activityEvidenceLoading::contains) == true,
            evidenceFailed = activity.id?.let(activityEvidenceFailures::contains) == true,
            actionPending = actionPending,
            onDismiss = { selectedActivity = null },
            onLoadRouteTrace = { activity.id?.let(onLoadActivityTrace) },
            onAction = { command ->
                submittedDialogAction = command.action
                onAction(command)
            },
        )
    }
    pendingDecision?.let { decision ->
        PlanDecisionDialog(
            pending = decision,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { pendingDecision = null },
            onConfirm = {
                submittedDialogAction = "preview_plan_decision"
                onAction(planDecisionCommand(decision))
            },
        )
    }
}
@Composable
internal fun StatsScreen(
    payload: NativeStatsPayload?,
    loading: Boolean,
    onDestinationSelected: (NativeDestination) -> Unit,
) {
    val weeks = payload?.detail?.weeks.orEmpty()
    val completedByStart = payload?.history?.weeklySummaries.orEmpty()
        .associateBy { it.startDate.orEmpty() }
    val noActiveSummary = noActiveStatsSummary(payload?.history)
    val hasRecordedHistory = hasRecordedStatsHistory(payload?.history)
    NativeList(loading) {
        item {
            ScreenIntro(
                "Stats",
                if (payload?.active == null) {
                    "Recorded runs and past plans."
                } else {
                    "Current plan and recorded runs."
                },
            )
        }
        when {
            payload == null -> item { EmptyCard("Loading training stats…") }
            payload.onboardingRequired == true -> {
                item { EmptyCard("Finish your training setup to see stats.") }
                item {
                    Button(
                        onClick = { onDestinationSelected(NativeDestination.Setup) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Continue setup")
                    }
                }
            }
            payload.active == null -> {
                item { NativeNoActiveStats(noActiveSummary) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onDestinationSelected(NativeDestination.History) },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                        ) { Text("History") }
                        Button(
                            onClick = { onDestinationSelected(NativeDestination.Setup) },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Build plan") }
                    }
                }
            }
            !hasRecordedHistory -> FirstRunStats(
                active = payload.active,
                todayIso = payload.history?.todayIso,
                fallbackWeeks = weeks.size,
                onOpenCalendar = { onDestinationSelected(NativeDestination.Calendar) },
            )
            else -> {
                item { StatsAssessment(payload.history) }
                item { NativeStatsTraces(payload.planTrace, payload.history?.weeklySummaries.orEmpty()) }
                items(weeks, key = { it.id.orEmpty() }) { week ->
                    WeekCard(week, completedByStart[week.startDate.orEmpty()])
                }
            }
        }
    }
}

private fun LazyListScope.FirstRunStats(
    active: NativePlanHistoryItem,
    todayIso: String?,
    fallbackWeeks: Int,
    onOpenCalendar: () -> Unit,
) {
    val plan = active.plan
    val planHasStarted = plan?.startDate?.takeIf(String::isNotBlank)?.let { startDate ->
        todayIso?.let { startDate <= it }
    } == true
    val title = if (planHasStarted) "Start with the next workout" else "Your plan is ready"
    val detail = if (planHasStarted) {
        "Open Calendar to see what is planned next, or record a run you already completed."
    } else {
        plan?.startDate?.takeIf(String::isNotBlank)?.let { startDate ->
            "Your plan starts ${friendlyDate(startDate)}. Open Calendar to see the first workout."
        } ?: "Open Calendar to see the first workout."
    }
    val planWeeks = plan?.weeks?.takeIf { it > 0 } ?: fallbackWeeks

    item {
        SettingCard("Nothing to compare yet") {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = onOpenCalendar,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Open calendar")
            }
        }
    }
    item {
        SettingCard("Current plan") {
            SettingRow("Starts", plan?.startDate?.takeIf(String::isNotBlank)?.let(::friendlyDate) ?: "Not set")
            SettingRow("Target date", plan?.targetDate?.takeIf(String::isNotBlank)?.let(::friendlyDate) ?: "Not set")
            SettingRow("Plan length", if (planWeeks > 0) "$planWeeks weeks" else "Not set")
        }
    }
}

@Composable
private fun StatsAssessment(history: NativeTrainingHistory?) {
    val weeks = history?.weeklySummaries.orEmpty()
    val signal = history?.currentSignal
    signal?.healthNotice?.let { notice ->
        SettingCard(notice.heading.orEmpty().ifBlank { "Health context" }) {
            Text(notice.message.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (signal != null && history.hasAcceptedActivities == true) {
        val assessment = if (signal.source == "plan") {
            nativeRampAssessment(signal.risk)
        } else {
            nativeLoadAssessment(signal.risk)
        }
        SettingCard("Current assessment") {
            SettingRow("Assessment", assessment.label)
            if (signal.reasons.isEmpty()) {
                Text(assessment.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                signal.reasons.forEach {
                    Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (weeks.isNotEmpty()) {
        val plannedRuns = weeks.sumOf { it.plannedRuns ?: 0 }
        val completedRuns = weeks.sumOf { it.completedRuns ?: 0 }
        val missedRuns = weeks.sumOf { it.missedRuns ?: 0 }
        val skippedRuns = weeks.sumOf { it.skippedRuns ?: 0 }
        val plannedDistance = weeks.sumOf { it.targetDistanceMeters ?: 0.0 }
        val completedDistance = weeks.sumOf { it.completedDistanceMeters ?: 0.0 }
        SettingCard("Plan versus actual") {
            SettingRow("Recorded / scheduled", "$completedRuns / $plannedRuns")
            SettingRow("Recorded distance", formatDistance(completedDistance))
            if (plannedDistance > 0) SettingRow("Planned distance", formatDistance(plannedDistance))
            SettingRow("Missed / skipped", "$missedRuns / $skippedRuns")
        }
    }
}
