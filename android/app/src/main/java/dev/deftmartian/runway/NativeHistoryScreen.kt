package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
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
                        onStop = {
                            confirmation = NativeHistoryConfirmation.Stop
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
                    shape = MaterialTheme.shapes.small,
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
    onStop: () -> Unit,
) {
    val plan = item.plan
    val summary = item.summary
    var endPlanOptionOpen by rememberSaveable(plan?.id) { mutableStateOf(false) }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onChangeGoal,
                enabled = !actionPending,
                modifier = Modifier.weight(1f),
            ) {
                Text("Change goal")
            }
            TextButton(
                onClick = { endPlanOptionOpen = !endPlanOptionOpen },
                enabled = !actionPending,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (endPlanOptionOpen) "Hide end option" else "End plan")
            }
        }
        if (endPlanOptionOpen) {
            Text(
                "Stopping closes the future schedule without marking the goal complete. Recorded work stays in History.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onStop,
                enabled = !actionPending,
            ) {
                Text(
                    item.goal?.title
                        ?.takeIf(String::isNotBlank)
                        ?.let { "Stop $it" }
                        ?: "Stop plan",
                )
            }
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
    val metrics = listOf(
        Triple(
            "Runs completed",
            completedRuns.toString(),
            if (completedRuns > 0) LedgerEmphasis.Actual else LedgerEmphasis.Neutral,
        ),
        Triple(
            "Distance recorded",
            completedDistance,
            if (completedDistance != "0 km" && completedDistance != "—") {
                LedgerEmphasis.Actual
            } else {
                LedgerEmphasis.Neutral
            },
        ),
        Triple(
            "Missed",
            missedRuns.toString(),
            if (missedRuns > 0) LedgerEmphasis.Review else LedgerEmphasis.Neutral,
        ),
        Triple(
            "Skipped",
            skippedRuns.toString(),
            if (skippedRuns > 0) LedgerEmphasis.Review else LedgerEmphasis.Neutral,
        ),
        Triple(
            "Pain flags",
            painFlags.toString(),
            if (painFlags > 0) LedgerEmphasis.Review else LedgerEmphasis.Neutral,
        ),
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                rowMetrics.forEach { (label, value, emphasis) ->
                    MeasurementReadout(
                        label = label,
                        value = value,
                        emphasis = emphasis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowMetrics.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
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
