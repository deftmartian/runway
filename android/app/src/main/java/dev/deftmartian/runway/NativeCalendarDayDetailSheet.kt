package dev.deftmartian.runway

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
