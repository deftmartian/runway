package dev.deftmartian.runway

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.heading
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
