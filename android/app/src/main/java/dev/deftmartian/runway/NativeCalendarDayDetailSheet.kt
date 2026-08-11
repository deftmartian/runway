package dev.deftmartian.runway

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarDayDetailSheet(
    date: String,
    today: String,
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onRecordFeedback: (NativeWorkout) -> Unit,
    onEditWorkout: (NativeWorkout) -> Unit,
    onResetWorkout: (NativeWorkout) -> Unit,
    onUndoWorkout: (String) -> Unit,
    onDeleteFeedback: (String) -> Unit,
    onOpenActivity: (NativeActivity) -> Unit,
    onPlanDecision: (PendingPlanDecision) -> Unit,
    canAddWorkout: Boolean,
    routine: Boolean,
    routineWeekGoal: NativeRoutineWeekGoal?,
    onChangeRoutineWeekGoal: (NativeRoutineWeekGoal) -> Unit,
    onBalanceRoutineWeek: (NativeRoutineWeekGoal) -> Unit,
    onAddWorkout: () -> Unit,
) {
    val activityPlacement = placeCalendarActivities(workouts, activities)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                friendlyDate(date),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (workouts.isEmpty() && activities.isEmpty()) {
                Text("No run is planned or recorded for this day.")
            }
            if (routine && date > today) {
                Text(
                    "Each scheduled run has a distance or time prescription. Together the week's runs match its selected goal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            routineWeekGoal?.let { goal ->
                Text(
                    "Weekly running goal · ${routineGoalLabel(goal)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (routineGoalCanChange(goal, today)) {
                    OutlinedButton(
                        onClick = { onChangeRoutineWeekGoal(goal) },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Change this week's goal")
                    }
                    if (goal.runs.size > 1) {
                        OutlinedButton(
                            onClick = { onBalanceRoutineWeek(goal) },
                            enabled = !actionPending,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Balance this week's runs")
                        }
                    }
                }
            }
            if (date > today && canAddWorkout) {
                OutlinedButton(
                    onClick = onAddWorkout,
                    enabled = !actionPending,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(if (workouts.any { it.type != "rest" }) "Add another run" else "Add a run here")
                }
            } else if (date > today) {
                Text(
                    "Set up a plan or routine before scheduling future runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            workouts.forEach { workout ->
                val canRecord =
                    workout.type != "rest" &&
                        workout.status == "planned" &&
                        workout.scheduledDate.orEmpty() <= today
                val canEdit =
                    workout.type != "race" &&
                        workout.status == "planned" &&
                        workout.scheduledDate.orEmpty() > today &&
                        workout.isRemoved != true
                WorkoutCard(
                    workout = workout,
                    onRecord = if (canRecord) ({ onRecordFeedback(workout) }) else null,
                    onEdit = if (canEdit) ({ onEditWorkout(workout) }) else null,
                    onReset = if (canEdit && workout.isEdited == true) {
                        { onResetWorkout(workout) }
                    } else {
                        null
                    },
                    onUndo = workout.adjustment?.id
                        ?.takeIf { canEdit && it.isNotBlank() }
                        ?.let { adjustmentId -> { onUndoWorkout(adjustmentId) } },
                )
                feedbackByWorkout[workout.id.orEmpty()]?.let { feedback ->
                    FeedbackOutcomeCard(
                        feedback = feedback,
                        actionPending = actionPending,
                        onDecision = { decision ->
                            pendingPlanDecision("feedback", feedback, decision)?.let(onPlanDecision)
                        },
                        onDelete = if (feedback.canDelete == true) {
                            { onDeleteFeedback(workout.id.orEmpty()) }
                        } else {
                            null
                        },
                    )
                }
                activityPlacement.byWorkoutId[workout.id.orEmpty()]
                    .orEmpty()
                    .forEach { activity ->
                        CalendarActivityRecord(
                            activity = activity,
                            actionPending = actionPending,
                            onOpenDetails = { onOpenActivity(activity) },
                            onDecision = { decision ->
                                pendingActivityPlanDecision(activity, decision)?.let(onPlanDecision)
                            },
                        )
                    }
            }
            if (activityPlacement.unplaced.isNotEmpty()) {
                SectionLabel("Other completed activity")
                activityPlacement.unplaced.forEach { activity ->
                    CalendarActivityRecord(
                        activity = activity,
                        actionPending = actionPending,
                        onOpenDetails = { onOpenActivity(activity) },
                        onDecision = { decision ->
                            pendingActivityPlanDecision(activity, decision)?.let(onPlanDecision)
                        },
                    )
                }
            }
        }
    }
}

internal fun routineGoalCanChange(goal: NativeRoutineWeekGoal, today: String): Boolean =
    runCatching { LocalDate.parse(goal.startDate) > LocalDate.parse(today) }.getOrDefault(false)

@Composable
internal fun RoutineWeekGoalDialog(
    goal: NativeRoutineWeekGoal,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (UpdateRoutineWeekGoalCommand) -> Unit,
) {
    var targetMetric by rememberSaveable(goal.weekId) {
        mutableStateOf(if (goal.targetDurationSeconds != null) "time" else "distance")
    }
    var targetKm by rememberSaveable(goal.weekId) {
        mutableStateOf(routineGoalKilometreInput(goal.targetDistanceMeters))
    }
    var targetMinutes by rememberSaveable(goal.weekId) {
        mutableStateOf(routineGoalMinuteInput(goal.targetDurationSeconds))
    }
    val operationId = rememberSaveable(goal.weekId) { UUID.randomUUID().toString() }
    val occurredAtEpochMillis = rememberSaveable(goal.weekId) {
        mutableLongStateOf(System.currentTimeMillis())
    }.longValue
    val issue = routineWeeklyGoalValidation(
        targetMetric,
        targetKm,
        targetMinutes,
        goal.scheduledRuns.coerceAtLeast(1),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change weekly running goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This changes only the future week of ${friendlyDate(goal.startDate)} and recalculates each scheduled run in that week.",
                )
                ChoiceRow("Distance", targetMetric == "distance") { targetMetric = "distance" }
                ChoiceRow("Time", targetMetric == "time") { targetMetric = "time" }
                if (targetMetric == "distance") {
                    NumberField(
                        label = "Weekly distance goal (km)",
                        value = targetKm,
                        errorMessage = issue,
                    ) { targetKm = it }
                } else {
                    NumberField(
                        label = "Weekly running time (minutes)",
                        value = targetMinutes,
                        errorMessage = issue,
                    ) { targetMinutes = it }
                }
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !actionPending && issue == null,
                onClick = {
                    onSubmit(
                        UpdateRoutineWeekGoalCommand(
                            weekId = goal.weekId,
                            targetMetric = targetMetric,
                            targetValue = if (targetMetric == "time") targetMinutes else targetKm,
                            operationId = operationId,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                        ),
                    )
                },
            ) {
                Text(if (actionPending) "Saving…" else "Save goal")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RoutineWeekBalanceDialog(
    goal: NativeRoutineWeekGoal,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (UpdateRoutineWeekDistributionCommand) -> Unit,
) {
    val distance = goal.targetDistanceMeters != null
    val unitAmount = if (distance) 500 else 300
    val minimumUnits = if (distance) 1 else 2
    val maximumUnits = if (distance) 200 else 72
    val evenUnits = routineEvenAllocationUnits(goal)
    val currentUnits = routineCurrentAllocationUnits(goal)
    var allocationUnits by rememberSaveable(goal.weekId, currentUnits, evenUnits) {
        mutableStateOf(currentUnits.ifEmpty { evenUnits })
    }
    val operationId = rememberSaveable(goal.weekId) { UUID.randomUUID().toString() }
    val occurredAtEpochMillis = rememberSaveable(goal.weekId) {
        mutableLongStateOf(System.currentTimeMillis())
    }.longValue
    val changed = allocationUnits != currentUnits &&
        allocationUnits != emptyList<Int>()
    val unitLabel = if (distance) "0.5 km" else "5 min"
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("routine-week-balance-dialog"),
        title = { Text("Balance weekly runs") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Move $unitLabel at a time between scheduled days. The weekly goal stays ${routineGoalLabel(goal)}.",
                )
                goal.runs.forEachIndexed { index, run ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                friendlyDate(run.scheduledDate),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(routineAllocationLabel(allocationUnits[index], distance))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    allocationUnits = transferRoutineAllocationUnit(
                                        allocationUnits,
                                        index,
                                        increase = false,
                                        minimumUnits = minimumUnits,
                                        maximumUnits = maximumUnits,
                                    )
                                },
                                enabled = !actionPending && allocationUnits[index] > minimumUnits,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "Move $unitLabel away from ${friendlyDate(run.scheduledDate)}"
                                    },
                                shape = MaterialTheme.shapes.small,
                            ) { Text("− $unitLabel") }
                            OutlinedButton(
                                onClick = {
                                    allocationUnits = transferRoutineAllocationUnit(
                                        allocationUnits,
                                        index,
                                        increase = true,
                                        minimumUnits = minimumUnits,
                                        maximumUnits = maximumUnits,
                                    )
                                },
                                enabled = !actionPending &&
                                    allocationUnits[index] < maximumUnits &&
                                    allocationUnits.indices.any {
                                        it != index && allocationUnits[it] > minimumUnits
                                    },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "Move $unitLabel to ${friendlyDate(run.scheduledDate)}"
                                    },
                                shape = MaterialTheme.shapes.small,
                            ) { Text("+ $unitLabel") }
                        }
                    }
                }
                TextButton(
                    onClick = { allocationUnits = evenUnits },
                    enabled = !actionPending && allocationUnits != evenUnits,
                ) { Text("Use even split") }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !actionPending && changed,
                onClick = {
                    onSubmit(
                        UpdateRoutineWeekDistributionCommand(
                            weekId = goal.weekId,
                            allocations = goal.runs.mapIndexed { index, run ->
                                RoutineRunAllocation(
                                    workoutId = run.workoutId,
                                    targetDistanceMeters = if (distance) allocationUnits[index] * unitAmount else null,
                                    targetDurationSeconds = if (distance) null else allocationUnits[index] * unitAmount,
                                )
                            },
                            operationId = operationId,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                        ),
                    )
                },
            ) { Text(if (actionPending) "Saving…" else "Save balance") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun routineCurrentAllocationUnits(goal: NativeRoutineWeekGoal): List<Int> {
    val distance = goal.targetDistanceMeters != null
    val unitAmount = if (distance) 500 else 300
    val values = goal.runs.map { run ->
        if (distance) run.targetDistanceMeters else run.targetDurationSeconds
    }
    val target = if (distance) {
        requireNotNull(goal.targetDistanceMeters).toInt()
    } else {
        goal.targetDurationSeconds?.toInt()
    }
    val minimumUnits = if (distance) 1 else 2
    return values.filterNotNull()
        .takeIf { amounts ->
            amounts.size == goal.runs.size &&
                amounts.all { it % unitAmount == 0 && it / unitAmount >= minimumUnits } &&
                amounts.sum() == target
        }
        ?.map { it / unitAmount }
        .orEmpty()
}

internal fun routineEvenAllocationUnits(goal: NativeRoutineWeekGoal): List<Int> {
    val distance = goal.targetDistanceMeters != null
    val unitAmount = if (distance) 500 else 300
    val target = requireNotNull(
        if (distance) requireNotNull(goal.targetDistanceMeters).toInt() else goal.targetDurationSeconds?.toInt(),
    )
    val totalUnits = target / unitAmount
    return List(goal.runs.size) { index ->
        totalUnits / goal.runs.size + if (index < totalUnits % goal.runs.size) 1 else 0
    }
}

internal fun transferRoutineAllocationUnit(
    amounts: List<Int>,
    index: Int,
    increase: Boolean,
    minimumUnits: Int,
    maximumUnits: Int,
): List<Int> {
    if (index !in amounts.indices) return amounts
    val otherIndices = amounts.indices.filter { it != index }
    val counterpart = if (increase) {
        if (amounts[index] >= maximumUnits) {
            null
        } else {
            otherIndices.filter { amounts[it] > minimumUnits }.maxByOrNull(amounts::get)
        }
    } else {
        if (amounts[index] <= minimumUnits) {
            null
        } else {
            otherIndices.filter { amounts[it] < maximumUnits }.minByOrNull(amounts::get)
        }
    } ?: return amounts
    return amounts.mapIndexed { amountIndex, value ->
        when (amountIndex) {
            index -> value + if (increase) 1 else -1
            counterpart -> value + if (increase) -1 else 1
            else -> value
        }
    }
}

private fun routineAllocationLabel(units: Int, distance: Boolean): String =
    if (distance) formatDistance(units * 500.0) else formatPlannedDurationEstimate(units * 300.0)

private fun routineGoalKilometreInput(meters: Double?): String = meters?.let {
    if (it % 1_000.0 == 0.0) {
        (it / 1_000.0).toInt().toString()
    } else {
        (it / 1_000.0).toString().trimEnd('0').trimEnd('.')
    }
}.orEmpty()

private fun routineGoalMinuteInput(seconds: Double?): String = seconds?.let {
    if (it % 60.0 == 0.0) {
        (it / 60.0).toInt().toString()
    } else {
        (it / 60.0).toString().trimEnd('0').trimEnd('.')
    }
}.orEmpty()

private fun routineGoalLabel(goal: NativeRoutineWeekGoal): String =
    goal.targetDistanceMeters?.let(::formatDistance)
        ?: goal.targetDurationSeconds?.let(::formatPlannedDurationEstimate)
        ?: "Not set"
