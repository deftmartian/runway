package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ReviewScreen(
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
                "Imports",
                "Review new activity before it changes your training record.",
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
                    enabled = !loading,
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
internal fun ProgressScreen(
    payload: NativeStatsPayload?,
    loading: Boolean,
    actionPending: Boolean,
    onDestinationSelected: (NativeDestination) -> Unit,
    onAction: (MobileCommand) -> Unit,
) {
    val weeks = payload?.detail?.weeks.orEmpty()
    val completedByStart = payload?.history?.weeklySummaries.orEmpty()
        .associateBy { it.startDate.orEmpty() }
    val active = payload?.active
    val phaseReview = payload?.phaseReview
    val targetDate = active?.plan?.targetDate?.takeIf(String::isNotBlank)
    val today = payload?.history?.todayIso?.takeIf(String::isNotBlank)
    val targetReached = targetDate != null && today != null && targetDate <= today
    val planHistory = payload?.planHistory?.items.orEmpty()
    val noActiveSummary = noActiveProgressSummary(payload?.history, planHistory.size)
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    NativeList(loading) {
        item { ScreenIntro("Progress", "Planned work beside accepted actual work. Recovery stays in the record.") }
        when {
            payload == null -> item { EmptyCard("Loading plan progress…") }
            payload.onboardingRequired == true -> item {
                EmptyCard("Finish your training setup to see progress.")
            }
            active == null -> {
                item { NativeNoActiveProgress(noActiveSummary) }
                item {
                    PlanSetupEntryCard(
                        entry = planSetupEntry(active),
                        onOpenSetup = { onDestinationSelected(NativeDestination.Setup) },
                    )
                }
            }
            weeks.isEmpty() -> item { EmptyCard("This plan does not have weekly progress yet.") }
            else -> {
                item { ProgressAssessment(payload.history) }
                item { NativeStatsTraces(payload.planTrace, payload.history?.weeklySummaries.orEmpty()) }
                items(weeks, key = { it.id.orEmpty() }) { week ->
                    WeekCard(week, completedByStart[week.startDate.orEmpty()])
                }
            }
        }
        phaseReview?.let { review ->
            item {
                PhaseCompletionCard(
                    review = review,
                    actionPending = actionPending,
                    onUseBaseline = { onAction(ConfirmPhaseBaselineCommand) },
                    onContinuePhase = { onAction(ContinueBeginnerPhaseCommand) },
                    onChooseGoal = { onDestinationSelected(NativeDestination.Setup) },
                )
            }
        }
        if (active != null) {
            item {
                PlanSetupEntryCard(
                    entry = planSetupEntry(active),
                    onOpenSetup = { onDestinationSelected(NativeDestination.Setup) },
                )
            }
            item {
                SettingCard("Plan controls") {
                    if (targetReached && (phaseReview == null || phaseReview.goalKind == "foundation")) {
                        OutlinedButton(
                            onClick = { onAction(CompletePlanCommand) },
                            enabled = !actionPending,
                        ) {
                            Text("Complete plan")
                        }
                    }
                    TextButton(onClick = { confirmArchive = true }, enabled = !actionPending) {
                        Text("Archive plan")
                    }
                }
            }
        }
        if (planHistory.isNotEmpty()) {
            item { SectionLabel("Plan history") }
            items(planHistory, key = { it.plan?.id.orEmpty() }) {
                val plan = it.plan
                SettingCard(it.goal?.title.orDash()) {
                    Text(
                        "${plan?.startDate.orDash()} – ${plan?.targetDate.orDash()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { onDestinationSelected(NativeDestination.History) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open full history")
                }
            }
        }
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive this plan?") },
            text = { Text("Completed work stays in history. Planned runs will no longer be active.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmArchive = false
                        onAction(ArchivePlanCommand)
                    },
                    enabled = !actionPending,
                ) {
                    Text("Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }) { Text("Cancel") }
            },
        )
    }
}

internal data class NativePlanSetupEntry(
    val title: String,
    val description: String,
    val actionLabel: String,
)

internal fun planSetupEntry(activePlan: NativePlanHistoryItem?): NativePlanSetupEntry =
    if (activePlan == null) {
        NativePlanSetupEntry(
            title = "Next plan",
            description = "Create a new goal and schedule when you are ready.",
            actionLabel = "Build plan",
        )
    } else {
        NativePlanSetupEntry(
            title = "Goal",
            description = "Review a replacement goal. Your current plan is archived only after you confirm it.",
            actionLabel = "Change goal",
        )
    }

@Composable
private fun PlanSetupEntryCard(
    entry: NativePlanSetupEntry,
    onOpenSetup: () -> Unit,
) {
    SettingCard(entry.title) {
        Text(entry.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
            Text(entry.actionLabel)
        }
    }
}

@Composable
private fun ProgressAssessment(history: NativeTrainingHistory?) {
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

@Composable
private fun PhaseCompletionCard(
    review: NativePhaseReview,
    actionPending: Boolean,
    onUseBaseline: () -> Unit,
    onContinuePhase: () -> Unit,
    onChooseGoal: () -> Unit,
) {
    val baseline = review.baseline
    val canUseBaseline =
        review.racePlan != null && "confirm_race_baseline" in review.options
    val canContinue =
        review.options.any { it == "another_foundation_week" || it == "continue_calibration" }
    val canChooseGoal =
        review.options.any { it == "later_date" || it == "shorter_goal" }
    SettingCard("Confirm the recorded starting point") {
        Text(
            "These accepted activities are the proposed starting point. Review them before runway builds the next phase.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingRow("Activities", baseline?.activityCount?.toString().orDash())
        SettingRow(
            "Total time",
            baseline?.totalDurationSeconds?.let(::formatDuration).orDash(),
        )
        SettingRow(
            "Total distance",
            baseline?.totalDistanceMeters?.let(::formatDistance).orDash(),
        )
        SettingRow(
            "Longest activity",
            baseline?.longestActivityMeters?.let(::formatDistance).orDash(),
        )
        SettingRow(
            "Recent weekly average",
            baseline?.weeklyDistanceMeters?.let(::formatDistance).orDash(),
        )
        SettingRow(
            "Activities per week",
            baseline?.runsPerWeek?.let(::formatDecimal).orDash(),
        )
        review.racePlan?.let { racePlan ->
            Text(
                "Proposed race phase",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    racePlan.weeks?.let { "$it weeks" },
                    racePlan.startDate?.takeIf(String::isNotBlank),
                    racePlan.targetDate?.takeIf(String::isNotBlank),
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val longRunDay = review.preferredLongRunDay
                ?.let(dayLabels::getOrNull)
            if (longRunDay != null) {
                Text(
                    "Preferred long-run day: $longRunDay",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            racePlan.warnings.forEach { warning ->
                Text("• $warning", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (review.goalKind == "race" && !canUseBaseline) {
            Notice(
                "The recorded work does not support the retained race ramp yet. No baseline will be applied.",
                isError = false,
            )
        }
        if (canUseBaseline) {
            Button(onClick = onUseBaseline, enabled = !actionPending) {
                Text("Confirm and build race phase")
            }
        }
        if (canContinue) {
            OutlinedButton(onClick = onContinuePhase, enabled = !actionPending) {
                Text(
                    if (review.phase == "calibration") {
                        "Continue calibration"
                    } else {
                        "Add another beginner week"
                    },
                )
            }
        }
        if (canChooseGoal) {
            TextButton(onClick = onChooseGoal, enabled = !actionPending) {
                Text("Choose a later date or shorter goal")
            }
        }
    }
}
