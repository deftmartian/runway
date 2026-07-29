package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class NativeHistoryLifecycleState(
    val targetReached: Boolean,
    val canCompletePlan: Boolean,
    val canConfirmBaseline: Boolean,
    val canContinuePhase: Boolean,
    val canChooseGoal: Boolean,
)

internal fun nativeHistoryLifecycleState(
    activePlan: NativePlan?,
    today: String?,
    phaseReview: NativePhaseReview?,
): NativeHistoryLifecycleState {
    val targetDate = activePlan?.targetDate?.takeIf(String::isNotBlank)
    val currentDate = today?.takeIf(String::isNotBlank)
    val targetReached =
        activePlan?.status == "active" &&
            targetDate != null &&
            currentDate != null &&
            targetDate <= currentDate
    return NativeHistoryLifecycleState(
        targetReached = targetReached,
        canCompletePlan =
            targetReached &&
                (phaseReview == null || phaseReview.goalKind == "foundation"),
        canConfirmBaseline =
            activePlan?.status == "active" &&
                phaseReview?.racePlan != null &&
                "confirm_race_baseline" in phaseReview.options,
        canContinuePhase =
            activePlan?.status == "active" &&
                phaseReview?.options.orEmpty().any {
                    it == "another_foundation_week" || it == "continue_calibration"
                },
        canChooseGoal =
            activePlan?.status == "active" &&
                phaseReview?.options.orEmpty().any {
                    it == "later_date" || it == "shorter_goal"
                },
    )
}

private enum class NativeHistoryConfirmation {
    UseBaseline,
    ContinuePhase,
    Complete,
    Stop,
}

@Composable
internal fun HistoryScreen(
    payload: NativeHistoryPayload?,
    loading: Boolean,
    onLoadMore: () -> Unit,
    onOpenPlan: (String) -> Unit,
    actionPending: Boolean = false,
    onDestinationSelected: (NativeDestination) -> Unit = {},
    onAction: (MobileCommand) -> Unit = {},
) {
    val history = payload?.history
    val active = payload?.activeItem
    val activePlanId = active?.plan?.id
    val pastPlans = history?.items.orEmpty().filter { item ->
        item.plan?.status != "active" &&
            (
                activePlanId.isNullOrBlank() ||
                    item.plan?.id != activePlanId
                )
    }
    val lifecycle = nativeHistoryLifecycleState(
        activePlan = active?.plan,
        today = history?.today,
        phaseReview = payload?.phaseReview,
    )
    var confirmation by rememberSaveable {
        mutableStateOf<NativeHistoryConfirmation?>(null)
    }
    NativeList(loading) {
        item {
            ScreenIntro(
                "History",
                "Current and past training plans.",
            )
        }
        if (payload == null) {
            item { EmptyCard("Loading history…") }
        } else {
            if (active == null) {
                item {
                    NoActivePlanCard(
                        hasPastPlans = pastPlans.isNotEmpty(),
                        actionPending = actionPending,
                        onBuildPlan = {
                            onDestinationSelected(NativeDestination.Setup)
                        },
                    )
                }
            } else {
                item { SectionLabel("Current plan") }
                item {
                    CurrentPlanRecord(
                        item = active,
                        targetReached = lifecycle.targetReached,
                        actionPending = actionPending,
                        onOpenCalendar = {
                            onDestinationSelected(NativeDestination.Calendar)
                        },
                        onOpenPlan = onOpenPlan,
                        onChangeGoal = {
                            onDestinationSelected(NativeDestination.Setup)
                        },
                    )
                }
                payload.phaseReview?.let { review ->
                    item {
                        PhaseCompletionDecision(
                            review = review,
                            lifecycle = lifecycle,
                            actionPending = actionPending,
                            onRequestBaseline = {
                                confirmation = NativeHistoryConfirmation.UseBaseline
                            },
                            onRequestContinue = {
                                confirmation = NativeHistoryConfirmation.ContinuePhase
                            },
                            onChooseGoal = {
                                onDestinationSelected(NativeDestination.Setup)
                            },
                        )
                    }
                }
                if (lifecycle.canCompletePlan) {
                    item {
                        CompletePlanCard(
                            actionPending = actionPending,
                            onComplete = {
                                confirmation = NativeHistoryConfirmation.Complete
                            },
                        )
                    }
                }
                item {
                    StopPlanCard(
                        goalTitle = active.goal?.title,
                        actionPending = actionPending,
                        onStop = {
                            confirmation = NativeHistoryConfirmation.Stop
                        },
                    )
                }
            }

            item { SectionLabel("Past plans") }
            if (pastPlans.isEmpty()) {
                item { EmptyCard("No past plans yet.") }
            } else {
                items(
                    pastPlans,
                    key = { item -> item.plan?.id.orEmpty() },
                ) { item ->
                    PlanHistoryRecord(item, onOpenPlan)
                }
            }
        }
        if (history?.nextOffset != null) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !loading && !actionPending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (loading) "Loading…" else "Load earlier plans")
                }
            }
        }
    }
    confirmation?.let { requested ->
        HistoryLifecycleConfirmationDialog(
            confirmation = requested,
            goalTitle = active?.goal?.title,
            phase = payload?.phaseReview?.phase,
            actionPending = actionPending,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                onAction(
                    when (requested) {
                        NativeHistoryConfirmation.UseBaseline -> ConfirmPhaseBaselineCommand
                        NativeHistoryConfirmation.ContinuePhase -> ContinueBeginnerPhaseCommand
                        NativeHistoryConfirmation.Complete -> CompletePlanCommand
                        NativeHistoryConfirmation.Stop -> ArchivePlanCommand
                    },
                )
            },
        )
    }
}

@Composable
private fun NoActivePlanCard(
    hasPastPlans: Boolean,
    actionPending: Boolean,
    onBuildPlan: () -> Unit,
) {
    SettingCard("No active plan") {
        Text(
            if (hasPastPlans) {
                "The last plan is closed. Its recorded work remains below."
            } else {
                "Create a plan when you are ready to schedule a goal."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onBuildPlan,
            enabled = !actionPending,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Build plan")
        }
    }
}

@Composable
private fun CurrentPlanRecord(
    item: NativePlanHistoryItem,
    targetReached: Boolean,
    actionPending: Boolean,
    onOpenCalendar: () -> Unit,
    onOpenPlan: (String) -> Unit,
    onChangeGoal: () -> Unit,
) {
    val plan = item.plan
    val summary = item.summary
    SettingCard(item.goal?.title.orDash()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                listOfNotNull(plan?.startDate, plan?.targetDate)
                    .joinToString(" → ")
                    .orDash(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            LedgerState(
                if (targetReached) "Target date reached" else "In progress",
                if (targetReached) LedgerEmphasis.Review else LedgerEmphasis.Planned,
            )
        }
        CurrentPlanMetrics(
            completedRuns = summary?.completedRuns ?: 0,
            completedDistance = summary?.completedDistanceMeters?.let(::formatDistance).orDash(),
            missedRuns = summary?.missedRuns ?: 0,
            skippedRuns = summary?.skippedRuns ?: 0,
            painFlags = summary?.painFlags ?: 0,
        )
        Button(
            onClick = onOpenCalendar,
            enabled = !actionPending,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Open calendar")
        }
        plan?.id?.takeIf(String::isNotBlank)?.let { planId ->
            OutlinedButton(
                onClick = { onOpenPlan(planId) },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Open plan record")
            }
        }
        Text(
            "A replacement goal archives this plan only after you confirm the new schedule.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onChangeGoal,
            enabled = !actionPending,
        ) {
            Text("Change goal")
        }
    }
}

@Composable
private fun CurrentPlanMetrics(
    completedRuns: Int,
    completedDistance: String,
    missedRuns: Int,
    skippedRuns: Int,
    painFlags: Int,
) {
    val metrics = buildList {
        add("Runs completed" to completedRuns.toString())
        add("Distance recorded" to completedDistance)
        add("Missed" to missedRuns.toString())
        add("Skipped" to skippedRuns.toString())
        add("Pain flags" to painFlags.toString())
    }

    metrics.forEach { (label, value) ->
        MeasurementReadout(
            label = label,
            value = value,
            emphasis = if (label in setOf("Missed", "Skipped", "Pain flags")) {
                LedgerEmphasis.Review
            } else {
                LedgerEmphasis.Actual
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PhaseCompletionDecision(
    review: NativePhaseReview,
    lifecycle: NativeHistoryLifecycleState,
    actionPending: Boolean,
    onRequestBaseline: () -> Unit,
    onRequestContinue: () -> Unit,
    onChooseGoal: () -> Unit,
) {
    val baseline = review.baseline
    SettingCard("Confirm the recorded starting point") {
        Text(
            "These accepted activities are the proposed starting point for the next phase.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingRow(
            "Activities",
            baseline?.activityCount?.toString().orDash(),
        )
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
            review.preferredLongRunDay
                ?.let(dayLabels::getOrNull)
                ?.let { longRunDay ->
                    Text(
                        "Preferred long-run day: $longRunDay",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            racePlan.warnings.forEach { warning ->
                Text(
                    "• $warning",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (review.goalKind == "race" && !lifecycle.canConfirmBaseline) {
            Notice(
                "The recorded work does not support the retained race ramp yet. No baseline will be applied.",
            )
        }
        if (lifecycle.canConfirmBaseline) {
            Button(
                onClick = onRequestBaseline,
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Confirm and build race phase")
            }
        }
        if (lifecycle.canContinuePhase) {
            OutlinedButton(
                onClick = onRequestContinue,
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    if (review.phase == "calibration") {
                        "Continue calibration"
                    } else {
                        "Add another beginner week"
                    },
                )
            }
        }
        if (lifecycle.canChooseGoal) {
            TextButton(
                onClick = onChooseGoal,
                enabled = !actionPending,
            ) {
                Text("Choose a later date or shorter goal")
            }
        }
    }
}

@Composable
private fun CompletePlanCard(
    actionPending: Boolean,
    onComplete: () -> Unit,
) {
    SettingCard("Close this plan as completed") {
        Text(
            "Use this after the target date when the training block reached its intended end. This does not claim every workout was done.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onComplete,
            enabled = !actionPending,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Mark plan complete")
        }
    }
}

@Composable
private fun StopPlanCard(
    goalTitle: String?,
    actionPending: Boolean,
    onStop: () -> Unit,
) {
    SettingCard("Stop this plan") {
        Text(
            "Use this when ending the goal without marking it complete. Recorded work stays in History.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onStop,
            enabled = !actionPending,
        ) {
            Text(
                goalTitle
                    ?.takeIf(String::isNotBlank)
                    ?.let { "Stop $it" }
                    ?: "Stop plan",
            )
        }
    }
}

@Composable
private fun HistoryLifecycleConfirmationDialog(
    confirmation: NativeHistoryConfirmation,
    goalTitle: String?,
    phase: String?,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (confirmation) {
        NativeHistoryConfirmation.UseBaseline -> "Use this recorded starting point?"
        NativeHistoryConfirmation.ContinuePhase ->
            if (phase == "calibration") "Continue calibration?" else "Add another beginner week?"
        NativeHistoryConfirmation.Complete -> "Mark this plan complete?"
        NativeHistoryConfirmation.Stop -> "Stop this plan?"
    }
    val explanation = when (confirmation) {
        NativeHistoryConfirmation.UseBaseline ->
            "runway will build the proposed race phase from these accepted activities. The resulting workouts remain editable."
        NativeHistoryConfirmation.ContinuePhase ->
            "runway will extend the current phase using its latest schedule. No race phase will be created yet."
        NativeHistoryConfirmation.Complete ->
            "The schedule will close as completed. Recorded work remains in History, even when some workouts were not done."
        NativeHistoryConfirmation.Stop ->
            "The goal will stop without being marked complete. Recorded work remains in History, and no plan becomes active until you build another one."
    }
    val confirmLabel = when (confirmation) {
        NativeHistoryConfirmation.UseBaseline -> "Confirm and build"
        NativeHistoryConfirmation.ContinuePhase ->
            if (phase == "calibration") "Continue calibration" else "Add week"
        NativeHistoryConfirmation.Complete -> "Mark complete"
        NativeHistoryConfirmation.Stop ->
            goalTitle
                ?.takeIf(String::isNotBlank)
                ?.let { "Stop $it" }
                ?: "Stop plan"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(explanation) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !actionPending,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !actionPending,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PlanHistoryRecord(item: NativePlanHistoryItem, onOpenPlan: (String) -> Unit) {
    val plan = item.plan
    val summary = item.summary
    val state = plan?.status.orEmpty().replaceFirstChar(Char::uppercase).ifBlank { "Recorded" }
    val closedOn = plan?.completedAt?.takeIf(String::isNotBlank)?.let { "Completed $it" }
        ?: plan?.archivedAt?.takeIf(String::isNotBlank)?.let { "Stopped $it" }
        ?: if (plan?.status == "active") "Current plan" else null
    SettingCard(item.goal?.title.orDash()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                listOfNotNull(plan?.startDate, plan?.targetDate).joinToString(" → ").orDash(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            LedgerState(
                state,
                if (plan?.status == "active") LedgerEmphasis.Planned else LedgerEmphasis.Neutral,
            )
        }
        closedOn?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MeasurementReadout(
                "Recorded / planned",
                "${summary?.completedRuns ?: 0} / ${summary?.plannedRuns ?: 0}",
                LedgerEmphasis.Actual,
                Modifier.fillMaxWidth(),
            )
            MeasurementReadout(
                "Actual distance",
                summary?.completedDistanceMeters?.let(::formatDistance).orDash(),
                LedgerEmphasis.Actual,
                Modifier.fillMaxWidth(),
            )
        }
        val exceptions = buildList {
            summary?.missedRuns?.takeIf { it > 0 }?.let { add("$it missed") }
            summary?.skippedRuns?.takeIf { it > 0 }?.let { add("$it skipped") }
            summary?.painFlags?.takeIf { it > 0 }?.let { add("$it pain reports") }
        }
        if (exceptions.isNotEmpty()) {
            Text(
                exceptions.joinToString(" · "),
                color = if ((summary?.painFlags ?: 0) > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        plan?.lifecycleReason?.takeIf(String::isNotBlank)?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        plan?.id?.takeIf(String::isNotBlank)?.let { planId ->
            TextButton(onClick = { onOpenPlan(planId) }) { Text("Open plan record") }
        }
    }
}
