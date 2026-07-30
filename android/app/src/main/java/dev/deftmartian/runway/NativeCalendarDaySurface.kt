package dev.deftmartian.runway

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun CalendarMonthLedger(
    month: String,
    today: String,
    selectedDay: String?,
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    onDaySelected: (String) -> Unit,
) {
    val calendarMonth = runCatching { YearMonth.parse(month) }.getOrNull()
    if (calendarMonth == null) {
        EmptyCard("Calendar dates are unavailable for this month.")
        return
    }
    val workoutsByDate = workouts.groupBy { it.scheduledDate.orEmpty() }
    val activitiesByDate = activities.groupBy { it.occurredDate.orEmpty().ifBlank { it.activityDate.orEmpty() } }
    val first = calendarMonth.atDay(1)
    val leadingDays = first.dayOfWeek.value % 7
    val monthDays = (1..calendarMonth.lengthOfMonth()).map(calendarMonth::atDay)
    val cells = buildList<LocalDate?> {
        repeat(leadingDays) { add(null) }
        addAll(monthDays)
        repeat((7 - size % 7) % 7) { add(null) }
    }
    val horizontalScroll = rememberScrollState()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val minimumGridWidth = 320.dp
        val gridWidth = if (maxWidth < minimumGridWidth) minimumGridWidth else maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScroll),
        ) {
            Column(
                modifier = Modifier.width(gridWidth),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    dayLabels.forEach { day ->
                        Text(
                            day.take(3),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                cells.chunked(7).forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        week.forEach { date ->
                            if (date == null) {
                                Text(
                                    "",
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 68.dp),
                                )
                            } else {
                                val dateValue = date.toString()
                                val dayWorkouts = workoutsByDate[dateValue].orEmpty()
                                val dayActivities = activitiesByDate[dateValue].orEmpty()
                                CalendarDayCell(
                                    date = date,
                                    isToday = dateValue == today,
                                    isSelected = dateValue == selectedDay,
                                    workouts = dayWorkouts,
                                    activities = dayActivities,
                                    feedbackByWorkout = feedbackByWorkout,
                                    onClick = { onDaySelected(dateValue) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = calendarDayPresentation(workouts, activities, feedbackByWorkout)
    val description = buildString {
        append(friendlyDate(date.toString()))
        if (isToday) append(", today")
        if (isSelected) append(", selected")
        presentation.stateDescription?.let { append(", $it") }
        append(". Open day details")
    }
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        presentation.emphasis == CalendarCellEmphasis.Actual ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        presentation.emphasis == CalendarCellEmphasis.Review ->
            RunwayThemeTokens.reviewContainer
        presentation.emphasis == CalendarCellEmphasis.Planned ->
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 60.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = if (isSelected || isToday) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            if (presentation.label != null) {
                val labelColor = when {
                    isSelected || isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                    presentation.emphasis == CalendarCellEmphasis.Review ->
                        RunwayThemeTokens.onReviewContainer
                    else -> presentation.emphasis.color()
                }
                Text(
                    presentation.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (isToday) {
                Text(
                    "Today",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private enum class CalendarCellEmphasis { Neutral, Planned, Actual, Review }

private data class CalendarDayPresentation(
    val label: String?,
    val stateDescription: String?,
    val emphasis: CalendarCellEmphasis,
)

private fun calendarDayPresentation(
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
): CalendarDayPresentation {
    val activeWorkouts = workouts.filter { it.isRemoved != true }
    val runWorkouts = activeWorkouts.filter { it.type != "rest" }
    val reviewNeeded = activities.any { it.pain == true || it.reviewState == "review" }
    if (reviewNeeded) {
        return CalendarDayPresentation("! Review", "needs review", CalendarCellEmphasis.Review)
    }
    if (activities.isNotEmpty()) {
        val label = if (activities.size > 1) {
            "✓ ${activities.size} done"
        } else {
            val activity = activities.single()
            val amount = formatPrescriptionMeasurement(
                activity.distanceMeters,
                activity.durationSeconds,
            )
            "✓ ${amount.takeUnless { it == "Plan details" } ?: "Done"}"
        }
        return CalendarDayPresentation(label, "recorded", CalendarCellEmphasis.Actual)
    }
    if (runWorkouts.any { feedbackByWorkout.containsKey(it.id.orEmpty()) }) {
        return CalendarDayPresentation("✓ Done", "feedback recorded", CalendarCellEmphasis.Actual)
    }
    if (runWorkouts.any { it.isEdited == true }) {
        return CalendarDayPresentation("↺ Edited", "edited plan", CalendarCellEmphasis.Review)
    }
    if (activeWorkouts.isNotEmpty() && runWorkouts.isEmpty()) {
        return CalendarDayPresentation("— Rest", "rest", CalendarCellEmphasis.Neutral)
    }
    if (runWorkouts.isNotEmpty()) {
        val label = if (runWorkouts.size > 1) {
            "${runWorkouts.size} runs"
        } else {
            formatPrescriptionMeasurement(
                runWorkouts.single().targetDistanceMeters,
                runWorkouts.single().targetDurationSeconds,
            ).takeUnless { it == "Plan details" } ?: "Run"
        }
        return CalendarDayPresentation(label, "planned", CalendarCellEmphasis.Planned)
    }
    return CalendarDayPresentation(null, null, CalendarCellEmphasis.Neutral)
}

@Composable
private fun CalendarCellEmphasis.color() = when (this) {
    CalendarCellEmphasis.Neutral -> RunwayThemeTokens.neutral
    CalendarCellEmphasis.Planned -> RunwayThemeTokens.planned
    CalendarCellEmphasis.Actual -> RunwayThemeTokens.actual
    CalendarCellEmphasis.Review -> RunwayThemeTokens.review
}

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
                Text("No scheduled or recorded work for this day.")
            }
            if (date > today) {
                OutlinedButton(
                    onClick = onAddWorkout,
                    enabled = !actionPending,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(if (workouts.any { it.type != "rest" }) "Add another run" else "Add a run here")
                }
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
                                activity.consequence?.let { consequence ->
                                    onPlanDecision(
                                        PendingPlanDecision(
                                            source = "activity",
                                            sourceId = activity.id.orEmpty(),
                                            decision = decision,
                                            consequence = consequence,
                                        ),
                                    )
                                }
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
                            activity.consequence?.let { consequence ->
                                onPlanDecision(
                                    PendingPlanDecision(
                                        source = "activity",
                                        sourceId = activity.id.orEmpty(),
                                        decision = decision,
                                        consequence = consequence,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
