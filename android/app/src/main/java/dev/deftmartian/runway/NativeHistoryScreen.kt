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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class NativeHistoryLifecycleState(
    val isRoutine: Boolean,
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
    val isRoutine = activePlan?.phase == "routine"
    val targetDate = activePlan?.targetDate?.takeIf(String::isNotBlank)
    val currentDate = today?.takeIf(String::isNotBlank)
    val targetReached =
        activePlan?.status == "active" &&
            targetDate != null &&
            currentDate != null &&
            targetDate <= currentDate
    return NativeHistoryLifecycleState(
        isRoutine = isRoutine,
        targetReached = targetReached && !isRoutine,
        canCompletePlan =
            !isRoutine && targetReached &&
                (phaseReview == null || phaseReview.goalKind == "foundation"),
        canConfirmBaseline =
            !isRoutine && activePlan?.status == "active" &&
                phaseReview?.racePlan != null &&
                "confirm_race_baseline" in phaseReview.options,
        canContinuePhase =
            !isRoutine && activePlan?.status == "active" &&
                phaseReview?.options.orEmpty().any {
                    it == "another_foundation_week" || it == "continue_calibration"
                },
        canChooseGoal =
            !isRoutine && activePlan?.status == "active" &&
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
            ScreenContext("Current training schedule and past records.")
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
                item { SectionLabel(if (lifecycle.isRoutine) "Weekly running routine" else "Current plan") }
                item {
                    CurrentPlanRecord(
                        item = active,
                        isRoutine = lifecycle.isRoutine,
                        targetReached = lifecycle.targetReached,
                        actionPending = actionPending,
                        onOpenCalendar = {
                            onDestinationSelected(NativeDestination.Calendar)
                        },
                        onOpenPlan = onOpenPlan,
                        onStop = {
                            confirmation = NativeHistoryConfirmation.Stop
                        },
                    )
                }
                payload.phaseReview?.takeUnless { lifecycle.isRoutine }?.let { review ->
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

            item { SectionLabel("Past plans and routines") }
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
            if (payload.activitiesOutsidePlans.isNotEmpty()) {
                item { SectionLabel("Runs outside a plan") }
                items(
                    payload.activitiesOutsidePlans,
                    key = { activity -> activity.id.orEmpty() },
                ) { activity ->
                    OutsidePlanActivityRecord(activity)
                }
            }
        }
        if (history?.nextOffset != null || history?.nextActivityOffset != null) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !loading && !actionPending,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(if (loading) "Loading…" else "Load earlier history")
                }
            }
        }
    }
    confirmation?.let { requested ->
        HistoryLifecycleConfirmationDialog(
            confirmation = requested,
            goalTitle = active?.goal?.title,
            phase = active?.plan?.phase ?: payload?.phaseReview?.phase,
            actionPending = actionPending,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                onAction(
                    when (requested) {
                        NativeHistoryConfirmation.UseBaseline -> ConfirmPhaseBaselineCommand(
                            expectedPreviewToken = checkNotNull(
                                payload?.phaseReview?.racePlan?.previewToken,
                            ) {
                                "The race-plan preview is no longer available."
                            },
                        )
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
private fun OutsidePlanActivityRecord(activity: NativeActivity) {
    LedgerSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(ledgerDate(activity.activityDate))
            LedgerState("Accepted", LedgerEmphasis.Actual)
        }
        Text(
            listOfNotNull(
                activity.distanceMeters?.let(::formatDistance),
                activity.durationSeconds?.let(::formatDuration),
            ).joinToString(" · ").orDash(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when (activity.source) {
                "gpx" -> "GPX import"
                "health_connect" -> "Health Connect"
                "manual" -> "Manual run"
                else -> activity.source.orDash()
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoActivePlanCard(
    hasPastPlans: Boolean,
    actionPending: Boolean,
    onBuildPlan: () -> Unit,
) {
    SettingCard("No active schedule") {
        Text(
            if (hasPastPlans) {
                "The last schedule is closed. Its recorded work remains below."
            } else {
                "Set up a training plan or a weekly running routine when you are ready."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onBuildPlan,
            enabled = !actionPending,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Set up running")
        }
    }
}

@Composable
private fun CurrentPlanRecord(
    item: NativePlanHistoryItem,
    isRoutine: Boolean,
    targetReached: Boolean,
    actionPending: Boolean,
    onOpenCalendar: () -> Unit,
    onOpenPlan: (String) -> Unit,
    onStop: () -> Unit,
) {
    val plan = item.plan
    val summary = item.summary
    val shownWeeks = plan?.weeks ?: 0
    var planOptionsOpen by rememberSaveable(plan?.id) { mutableStateOf(false) }
    var endPlanOptionOpen by rememberSaveable(plan?.id) { mutableStateOf(false) }
    SettingCard(if (isRoutine) "Weekly running routine" else item.goal?.title.orDash()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (isRoutine) {
                    listOfNotNull(
                        plan?.sessionsPerWeek?.let {
                            "$it ${if (it == 1) "run" else "runs"} each week"
                        },
                        plan?.startDate?.let { "ongoing since ${ledgerDate(it)}" },
                    ).joinToString(" · ").orDash()
                } else {
                    listOfNotNull(plan?.startDate, plan?.targetDate)
                        .map(::ledgerDate)
                        .joinToString(" → ")
                        .orDash()
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LedgerState(
                when {
                    isRoutine -> "Ongoing"
                    targetReached -> "Target date reached"
                    else -> "In progress"
                },
                if (targetReached) LedgerEmphasis.Review else LedgerEmphasis.Planned,
            )
        }
        if (isRoutine && shownWeeks > 0) {
            Text(
                "Counts below cover the $shownWeeks most recent routine ${if (shownWeeks == 1) "week" else "weeks"} shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CurrentPlanMetrics(
            completedRuns = summary?.completedRuns ?: 0,
            plannedRunsRecorded = summary?.plannedRunsRecorded ?: 0,
            extraRuns = summary?.extraRuns ?: 0,
            completedDistance = summary?.completedDistanceMeters?.let(::formatDistance).orDash(),
            missedRuns = summary?.missedRuns ?: 0,
            skippedRuns = summary?.skippedRuns ?: 0,
            painFlags = summary?.painFlags ?: 0,
            isRoutine = isRoutine,
        )
        Button(
            onClick = onOpenCalendar,
            enabled = !actionPending,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Open calendar")
        }
        if (isRoutine) {
            plan?.id?.takeIf(String::isNotBlank)?.let { planId ->
                TextButton(
                    onClick = { onOpenPlan(planId) },
                    enabled = !actionPending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open routine record") }
            }
            TextButton(
                onClick = onStop,
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("End routine") }
        } else {
            OutlinedButton(
                onClick = { planOptionsOpen = !planOptionsOpen },
                enabled = !actionPending,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        stateDescription = if (planOptionsOpen) "Expanded" else "Collapsed"
                    },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(if (planOptionsOpen) "Hide plan options" else "Plan options")
            }
        }
        if (!isRoutine && planOptionsOpen) {
            plan?.id?.takeIf(String::isNotBlank)?.let { planId ->
                TextButton(
                    onClick = { onOpenPlan(planId) },
                    enabled = !actionPending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open plan record") }
            }
            TextButton(
                onClick = { endPlanOptionOpen = !endPlanOptionOpen },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (endPlanOptionOpen) "Hide end option" else "End plan") }
            if (endPlanOptionOpen) {
                Text(
                    "Stopping closes the future schedule without marking the goal complete. History keeps your recorded runs.",
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
}

@Composable
private fun CurrentPlanMetrics(
    completedRuns: Int,
    plannedRunsRecorded: Int,
    extraRuns: Int,
    completedDistance: String,
    missedRuns: Int,
    skippedRuns: Int,
    painFlags: Int,
    isRoutine: Boolean,
) {
    val metrics = buildList {
        add(
            Triple(
                if (isRoutine) "Runs recorded" else "Runs completed",
                completedRuns.toString(),
                if (completedRuns > 0) {
                    LedgerEmphasis.Actual
                } else {
                    LedgerEmphasis.Neutral
                },
            ),
        )
        if (isRoutine && extraRuns > 0) {
            add(Triple("On scheduled days", plannedRunsRecorded.toString(), LedgerEmphasis.Actual))
            add(Triple("On other days", extraRuns.toString(), LedgerEmphasis.Actual))
        }
        add(
            Triple(
                "Distance recorded",
                completedDistance,
                if (completedDistance != "0 km" && completedDistance != "—") {
                    LedgerEmphasis.Actual
                } else {
                    LedgerEmphasis.Neutral
                },
            ),
        )
        add(
            Triple(
                if (isRoutine) "Not recorded" else "Missed",
                missedRuns.toString(),
                if (!isRoutine && missedRuns > 0) LedgerEmphasis.Review else LedgerEmphasis.Neutral,
            ),
        )
        add(
            Triple(
                "Skipped",
                skippedRuns.toString(),
                if (!isRoutine && skippedRuns > 0) LedgerEmphasis.Review else LedgerEmphasis.Neutral,
            ),
        )
        add(
            Triple(
                "Pain reports",
                painFlags.toString(),
                if (painFlags > 0) LedgerEmphasis.Review else LedgerEmphasis.Neutral,
            ),
        )
    }

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
    val shownWeeks = plan?.weeks ?: 0
    val isRoutine = plan?.phase == "routine"
    val state = plan?.status.orEmpty().replaceFirstChar(Char::uppercase).ifBlank { "Recorded" }
    val closedOn = plan?.completedAt?.takeIf(String::isNotBlank)?.let { "Completed ${ledgerDate(it)}" }
        ?: plan?.archivedAt?.takeIf(String::isNotBlank)?.let {
            "${if (isRoutine) "Ended" else "Stopped"} ${ledgerDate(it)}"
        }
        ?: if (plan?.status == "active") "Current plan" else null
    SettingCard(item.goal?.title.orDash()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (isRoutine) {
                    plan.startDate?.let { "Started ${ledgerDate(it)}" }.orDash()
                } else {
                    listOfNotNull(plan?.startDate, plan?.targetDate)
                        .map(::ledgerDate)
                        .joinToString(" → ")
                        .orDash()
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                if (isRoutine) "Runs recorded" else "Recorded / planned",
                if (isRoutine) {
                    (summary?.completedRuns ?: 0).toString()
                } else {
                    "${summary?.completedRuns ?: 0} / ${summary?.plannedRuns ?: 0}"
                },
                LedgerEmphasis.Actual,
                Modifier.fillMaxWidth(),
            )
            if (isRoutine && (summary?.extraRuns ?: 0) > 0) {
                MeasurementReadout(
                    "On scheduled days",
                    (summary?.plannedRunsRecorded ?: 0).toString(),
                    LedgerEmphasis.Actual,
                    Modifier.fillMaxWidth(),
                )
                MeasurementReadout(
                    "On other days",
                    summary?.extraRuns.toString(),
                    LedgerEmphasis.Actual,
                    Modifier.fillMaxWidth(),
                )
            }
            MeasurementReadout(
                "Actual distance",
                summary?.completedDistanceMeters?.let(::formatDistance).orDash(),
                LedgerEmphasis.Actual,
                Modifier.fillMaxWidth(),
            )
        }
        val exceptions = buildList {
            summary?.missedRuns?.takeIf { it > 0 }?.let {
                add(if (isRoutine) "$it not recorded" else "$it missed")
            }
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
        if (isRoutine && shownWeeks > 0) {
            Text(
                "Summary covers the $shownWeeks most recent routine ${if (shownWeeks == 1) "week" else "weeks"} shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
