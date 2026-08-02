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
    activityEvidence: Map<String, NativeActivityEvidence>,
    activityEvidenceLoading: Set<String>,
    activityEvidenceFailures: Set<String>,
    onLoadActivityTrace: (String) -> Unit,
    onLoadMore: () -> Unit,
    onImportGpx: () -> Unit,
    onOpenImportSettings: () -> Unit,
) {
    val candidates = payload?.candidates.orEmpty()
    val activities = payload?.activities.orEmpty()
    val workoutDecisions = payload?.workoutDecisions.orEmpty()
    val reviewActivities = activities.filter { it.reviewState == "review" }
    val acceptedActivities = activities.filter { it.reviewState != "review" }
    val healthConnectChanges = payload?.healthConnectChanges.orEmpty()
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
            ScreenContext(
                "Choose how each run counts, then decide whether its result changes the plan.",
            )
        }
        when {
            payload == null -> item { EmptyCard("Loading activity review…") }
            reviewActivities.isEmpty() && acceptedActivities.isEmpty() &&
                workoutDecisions.isEmpty() && healthConnectChanges.isEmpty() -> {
                item { EmptyCard("Nothing needs a decision right now.") }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onImportGpx,
                            enabled = !actionPending,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Choose GPX")
                        }
                        OutlinedButton(
                            onClick = onOpenImportSettings,
                            enabled = !actionPending,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Import settings")
                        }
                    }
                }
            }
            else -> {
                if (healthConnectChanges.isNotEmpty()) {
                    item { SectionLabel("Health Connect changes") }
                    items(
                        healthConnectChanges,
                        key = { "health-connect-${it.mappingId}" },
                    ) { change ->
                        HealthConnectChangeCard(change, actionPending, onAction)
                    }
                }
                if (reviewActivities.isNotEmpty()) {
                    item { SectionLabel("Runs to review") }
                }
                items(reviewActivities, key = { it.id.orEmpty() }) { activity ->
                    ActivityCard(
                        activity = activity,
                        supportingText =
                            "Choose a planned run or count it as extra. " +
                                "The plan stays unchanged until you decide.",
                        actions = {
                            Button(
                                onClick = {
                                    selectedActivity = activity
                                    activity.id?.let(onLoadActivityTrace)
                                },
                                enabled = !actionPending,
                            ) {
                                Text("Review")
                            }
                        },
                    )
                }
            }
        }
        if (workoutDecisions.isNotEmpty() || acceptedActivities.isNotEmpty()) {
            item { SectionLabel("Plan decisions") }
            items(workoutDecisions, key = { "feedback-${it.id.orEmpty()}" }) { feedback ->
                WorkoutDecisionCard(feedback, actionPending) { decision ->
                    pendingPlanDecision("feedback", feedback, decision)?.let {
                        pendingDecision = it
                    }
                }
            }
            items(acceptedActivities, key = { "activity-${it.id.orEmpty()}" }) { activity ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityCard(
                        activity = activity,
                        actions = {
                            TextButton(
                                onClick = {
                                    selectedActivity = activity
                                    activity.id?.let(onLoadActivityTrace)
                                },
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
                                pendingActivityPlanDecision(activity, decision)?.let {
                                    pendingDecision = it
                                }
                            },
                        )
                    }
                }
            }
        }
        if (payload?.hasMore == true) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !loading && !actionPending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (loading) "Loading…" else "Load more decisions")
                }
            }
        }
    }
    selectedActivity?.let { activity ->
        ActivityDetailSheet(
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
private fun WorkoutDecisionCard(
    feedback: NativeWorkoutFeedback,
    actionPending: Boolean,
    onDecision: (String) -> Unit,
) {
    SettingCard("Recorded workout") {
        val planContext = listOfNotNull(
            feedback.scheduledDate?.let(::friendlyDate),
            feedback.workoutPurpose?.takeIf(String::isNotBlank),
        ).joinToString(" · ")
        if (planContext.isNotBlank()) {
            Text(planContext)
        }
        val measurement = listOfNotNull(
            feedback.completedDistanceMeters?.takeIf { it > 0 }?.let(::formatDistance),
            feedback.completedDurationSeconds?.takeIf { it > 0 }?.let(::formatDuration),
        ).joinToString(" · ").ifBlank { "Recorded result" }
        Text(measurement, color = MaterialTheme.colorScheme.onSurfaceVariant)
        feedback.consequence?.let { consequence ->
            ConsequenceChoices(
                consequence = consequence,
                actionPending = actionPending,
                onDecision = onDecision,
            )
        }
    }
}

@Composable
private fun HealthConnectChangeCard(
    change: NativeHealthConnectChange,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
) {
    val isCorrection = change.state == "pending_correction"
    val isDuplicate = change.state == "possible_duplicate"
    val title = when {
        isCorrection -> "An imported run changed"
        isDuplicate -> "These may be the same run"
        else -> "An imported run was deleted"
    }
    SettingCard(title) {
        Text(
            when {
                isCorrection ->
                    "Health Connect has newer details for a run you already accepted. Review both records before choosing."
                isDuplicate ->
                    "A new Health Connect run closely matches one already in runway. Nothing is merged or removed until you choose."
                else ->
                    "Health Connect no longer has this run. Choose whether its accepted copy should remain in runway."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        change.current?.let {
            ActivitySummaryRows(
                if (isDuplicate) "Health Connect import" else "Current",
                it,
            )
        }
        if (isCorrection) {
            change.proposed?.let { ActivitySummaryRows("Updated", it) }
            Button(
                onClick = {
                    onAction(
                        ResolveHealthConnectRecordCommand(
                            provider = change.provider,
                            recordId = change.recordId,
                            decision = HealthConnectRecordDecision.AcceptCorrection,
                        ),
                    )
                },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use updated record")
            }
            OutlinedButton(
                onClick = {
                    onAction(
                        ResolveHealthConnectRecordCommand(
                            provider = change.provider,
                            recordId = change.recordId,
                            decision = HealthConnectRecordDecision.KeepCurrent,
                        ),
                    )
                },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Keep current record")
            }
        } else if (isDuplicate) {
            change.proposed?.let { ActivitySummaryRows("Existing runway run", it) }
            Button(
                onClick = {
                    onAction(
                        ResolveHealthConnectDuplicateCommand(
                            provider = change.provider,
                            recordId = change.recordId,
                            decision = HealthConnectDuplicateDecision.UseExisting,
                        ),
                    )
                },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use existing run")
            }
            OutlinedButton(
                onClick = {
                    onAction(
                        ResolveHealthConnectDuplicateCommand(
                            provider = change.provider,
                            recordId = change.recordId,
                            decision = HealthConnectDuplicateDecision.KeepBoth,
                        ),
                    )
                },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Keep both")
            }
        } else {
            Button(
                onClick = {
                    onAction(
                        ResolveHealthConnectRecordCommand(
                            provider = change.provider,
                            recordId = change.recordId,
                            decision = HealthConnectRecordDecision.RetainInRunway,
                        ),
                    )
                },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Keep in runway")
            }
            TextButton(
                onClick = {
                    onAction(
                        ResolveHealthConnectRecordCommand(
                            provider = change.provider,
                            recordId = change.recordId,
                            decision = HealthConnectRecordDecision.DeleteFromRunway,
                        ),
                    )
                },
                enabled = !actionPending,
            ) {
                Text("Remove from runway")
            }
        }
    }
}

@Composable
private fun ActivitySummaryRows(label: String, activity: NativeActivitySummary) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    SettingRow("Date", activity.date)
    SettingRow(
        "Recorded",
        listOfNotNull(
            activity.distanceMeters?.let(::formatDistance),
            activity.durationSeconds?.let(::formatDuration),
        ).joinToString(" · ").ifBlank { "No distance or duration" },
    )
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
    val isRoutine = payload?.active?.plan?.phase == "routine"
    NativeList(loading) {
        item {
            ScreenContext(
                if (payload?.active == null) {
                    "Recorded runs and past plans."
                } else {
                    if (isRoutine) "Weekly routine and recorded runs." else "Current plan and recorded runs."
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
                        ) { Text("Set up running") }
                    }
                }
            }
            !hasRecordedHistory -> if (isRoutine) {
                FirstRoutineStats(
                    runsPerWeek = payload.active.plan.sessionsPerWeek,
                    onOpenCalendar = { onDestinationSelected(NativeDestination.Calendar) },
                )
            } else {
                FirstRunStats(
                    active = payload.active,
                    todayIso = payload.history?.todayIso,
                    fallbackWeeks = weeks.size,
                    onOpenCalendar = { onDestinationSelected(NativeDestination.Calendar) },
                )
            }
            else -> {
                if (isRoutine) {
                    item {
                        RoutineStatsAssessment(
                            history = payload.history,
                            runsPerWeek = payload.active.plan.sessionsPerWeek,
                            routineStartDate = payload.active.plan.startDate,
                            onOpenCalendar = { onDestinationSelected(NativeDestination.Calendar) },
                        )
                    }
                    item {
                        NativeRoutineStatsTraces(
                            weeklySummaries = payload.history?.weeklySummaries.orEmpty(),
                            todayIso = payload.history?.todayIso,
                            runsPerWeek = payload.active.plan.sessionsPerWeek,
                        )
                    }
                } else {
                    item { StatsAssessment(payload.history) }
                    item { NativeStatsTraces(payload.planTrace, payload.history?.weeklySummaries.orEmpty()) }
                    items(weeks, key = { it.id.orEmpty() }) { week ->
                        WeekCard(week, completedByStart[week.startDate.orEmpty()])
                    }
                }
            }
        }
    }
}

private fun LazyListScope.FirstRoutineStats(
    runsPerWeek: Int?,
    onOpenCalendar: () -> Unit,
) {
    item {
        SettingCard("Your weekly routine is ready") {
            Text(
                if (runsPerWeek != null && runsPerWeek > 0) {
                    "$runsPerWeek ${if (runsPerWeek == 1) "run is" else "runs are"} scheduled each week. Routine runs start open; you can add a target to an individual run."
                } else {
                    "Open Calendar to see the next open run."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onOpenCalendar,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Open calendar")
            }
        }
    }
}

@Composable
private fun RoutineStatsAssessment(
    history: NativeTrainingHistory?,
    runsPerWeek: Int?,
    routineStartDate: String?,
    onOpenCalendar: () -> Unit,
) {
    history?.currentSignal?.healthNotice?.let { notice ->
        SettingCard(notice.heading.orEmpty().ifBlank { "Running limits" }) {
            Text(notice.message.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val week = routineCurrentWeek(history)
    if (routineWeekIsWaiting(week)) {
        SettingCard("Your routine is ready") {
            Text(
                routineWaitingMessage(routineStartDate, history?.todayIso),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onOpenCalendar,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Open calendar")
            }
        }
        return
    }
    requireNotNull(week)
    val planned = week.plannedRuns ?: 0
    val extras = week.extraRuns ?: 0
    val plannedRecorded = week.plannedRunsRecorded
        ?: ((week.completedRuns ?: 0) - extras).coerceAtLeast(0)
    val recorded = week.completedRuns ?: 0
    SettingCard("This week") {
        runsPerWeek?.takeIf { it > 0 }?.let {
            SettingRow("Routine goal", "$it ${if (it == 1) "run" else "runs"} each week")
        }
        SettingRow(
            "Runs recorded",
            if (planned > 0) "$recorded of $planned this week" else recorded.toString(),
        )
        if (extras > 0) {
            SettingRow("On scheduled days", plannedRecorded.toString())
            SettingRow("On other days", extras.toString())
        }
        if ((week.skippedRuns ?: 0) > 0) SettingRow("Marked skipped", week.skippedRuns.toString())
        if ((week.missedRuns ?: 0) > 0) SettingRow("Not recorded", week.missedRuns.toString())
        week.completedDistanceMeters?.takeIf { it > 0 }?.let {
            SettingRow("Recorded distance", formatDistance(it))
        }
        week.completedDurationSeconds?.takeIf { it > 0 }?.let {
            SettingRow("Recorded time", formatDuration(it))
        }
        Text(
            "Missing or extra runs do not move future routine days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun routineWeekIsWaiting(week: NativeWeekSummary?): Boolean =
    week == null || (
        (week.plannedRuns ?: 0) == 0 &&
            (week.completedRuns ?: 0) == 0 &&
            (week.completedDistanceMeters ?: 0.0) <= 0.0 &&
            (week.completedDurationSeconds ?: 0.0) <= 0.0
        )

internal fun routineWaitingMessage(startDate: String?, todayIso: String?): String {
    val start = startDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val today = todayIso?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    return if (start != null && today != null && start > today) {
        "Your first routine week starts ${friendlyDate(start.toString())}. Calendar shows the open runs."
    } else {
        "Calendar shows the next open run in your routine."
    }
}

internal fun routineCurrentWeek(history: NativeTrainingHistory?): NativeWeekSummary? {
    val today = history?.todayIso?.takeIf(String::isNotBlank) ?: return null
    return history.weeklySummaries.firstOrNull { week ->
        week.startDate?.let { start ->
            runCatching {
                val startDate = java.time.LocalDate.parse(start)
                val current = java.time.LocalDate.parse(today)
                current in startDate..startDate.plusDays(6)
            }.getOrDefault(false)
        } == true
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
        SettingCard(notice.heading.orEmpty().ifBlank { "Running limits" }) {
            Text(notice.message.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (signal != null && history.hasAcceptedActivities == true) {
        val assessment = if (signal.source == "plan") {
            nativeRampAssessment(signal.risk)
        } else {
            nativeLoadAssessment(signal.risk)
        }
        SettingCard("Current status") {
            Text(assessment.label, style = MaterialTheme.typography.titleMedium)
            Text(assessment.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (signal.reasons.isNotEmpty()) {
                Text("Reasons", style = MaterialTheme.typography.labelLarge)
                signal.reasons.forEach {
                    Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
