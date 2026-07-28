package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
) {
    val candidates = payload?.candidates.orEmpty()
    val activities = payload?.activities.orEmpty()
    var selectedActivity by remember { mutableStateOf<NativeActivity?>(null) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedAction) {
        if (completedAction == submittedDialogAction) {
            if (completedAction == "apply_plan_decision") {
                pendingDecision = null
            } else {
                selectedActivity = null
            }
            submittedDialogAction = null
        }
    }
    NativeList(loading) {
        item { ScreenIntro("Review", "Imported and unplanned activity, kept separate until you decide where it belongs.") }
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
                                onClick = { selectedActivity = activity },
                                enabled = !actionPending,
                            ) {
                                Text("Decide")
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
                        actions = if (!activity.workoutId.isNullOrBlank()) {
                            {
                                TextButton(
                                    onClick = {
                                        onAction(UnlinkActivityCommand(activity.id.orEmpty()))
                                    },
                                    enabled = !actionPending,
                                ) {
                                    Text("Unlink from planned run")
                                }
                            }
                        } else {
                            null
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
    }
    selectedActivity?.let { activity ->
        ActivityReviewDialog(
            activity = activity,
            candidates = candidates,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { selectedActivity = null },
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
                submittedDialogAction = "apply_plan_decision"
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
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    NativeList(loading) {
        item { ScreenIntro("Progress", "A clear view of the ramp, with recovery visible too.") }
        when {
            payload == null -> item { EmptyCard("Loading plan progress…") }
            payload.onboardingRequired == true -> item {
                EmptyCard("Finish your training setup to see progress.")
            }
            active == null -> item { EmptyCard("There is no active plan. Your history is still kept here.") }
            weeks.isEmpty() -> item { EmptyCard("This plan does not have weekly progress yet.") }
            else -> items(weeks, key = { it.id.orEmpty() }) { week ->
                WeekCard(week, completedByStart[week.startDate.orEmpty()])
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
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            it.goal?.title.orDash(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${plan?.startDate.orDash()} – ${plan?.targetDate.orDash()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
