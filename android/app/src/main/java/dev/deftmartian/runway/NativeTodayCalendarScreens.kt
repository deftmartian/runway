package dev.deftmartian.runway

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
internal fun TodayScreen(
    payload: NativeCalendarPayload?,
    loading: Boolean,
    actionPending: Boolean,
    actionNotice: NativeNotice?,
    completedAction: String?,
    activityEvidence: Map<String, NativeActivityEvidence>,
    activityEvidenceLoading: Set<String>,
    activityEvidenceFailures: Set<String>,
    onLoadActivityTrace: (String) -> Unit,
    onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit,
    onAction: (MobileCommand) -> Unit,
) {
    val calendar = payload?.calendar
    val workouts = calendar?.workouts.orEmpty()
    val feedbackByWorkout = calendar?.feedback.orEmpty()
        .associateBy { it.workoutId.orEmpty() }
    val today = calendar?.today.orEmpty()
    val todaysWorkouts = workouts.filter {
        it.scheduledDate == today && it.isRemoved != true
    }
    val todaysActivities = calendar?.activities.orEmpty().filter {
        (it.occurredDate ?: it.activityDate) == today
    }
    val activityPlacement = placeCalendarActivities(todaysWorkouts, todaysActivities)
    var feedbackWorkout by remember { mutableStateOf<NativeWorkout?>(null) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var selectedActivity by remember { mutableStateOf<NativeActivity?>(null) }
    var showManualRun by rememberSaveable { mutableStateOf(false) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedAction) {
        if (completedAction != submittedDialogAction) return@LaunchedEffect
        when (completedAction) {
            "record_feedback" -> feedbackWorkout = null
            "record_manual_run" -> showManualRun = false
            "preview_plan_decision", "apply_plan_decision" -> pendingDecision = null
            else -> if (selectedActivity != null) selectedActivity = null
        }
        submittedDialogAction = null
    }
    NativeList(loading) {
        item {
            ScreenIntro(
                "Today",
                if (today.isBlank()) "Your next training decision." else friendlyDate(today),
            )
        }
        when {
            payload == null -> item { EmptyCard("Loading today’s plan…") }
            payload.onboardingRequired == true -> item {
                EmptyCard("Finish your training setup to see today’s plan.")
            }
            todaysWorkouts.isEmpty() && todaysActivities.isEmpty() -> item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmptyCard("No run is scheduled today. Rest remains part of the plan.")
                    payload.nextWorkout?.let { next ->
                        SettingCard("Next run") {
                            Text(
                                friendlyDate(next.scheduledDate.orEmpty()),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                next.purpose.orEmpty().ifBlank {
                                    next.type.orEmpty().replaceFirstChar(Char::uppercase)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val amount = formatPrescriptionMeasurement(
                                next.targetDistanceMeters,
                                next.targetDurationSeconds,
                            )
                            if (amount != "Plan details") {
                                MeasurementReadout("Planned", amount, LedgerEmphasis.Planned)
                            }
                            Button(
                                onClick = {
                                    next.scheduledDate
                                        ?.takeIf { it.length >= 7 }
                                        ?.take(7)
                                        ?.let(onCalendarMonthSelected)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text("Open next run")
                            }
                        }
                    }
                }
            }
            else -> items(todaysWorkouts, key = { it.id.orEmpty() }) { workout ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkoutCard(
                        workout = workout,
                        onRecord = if (
                            workout.type != "rest" &&
                            workout.status == "planned"
                        ) {
                            { feedbackWorkout = workout }
                        } else {
                            null
                        },
                    )
                    feedbackByWorkout[workout.id.orEmpty()]?.let { feedback ->
                        FeedbackOutcomeCard(
                            feedback = feedback,
                            actionPending = actionPending,
                            onDecision = { decision ->
                                pendingDecision = pendingPlanDecision("feedback", feedback, decision)
                            },
                            onDelete = if (feedback.canDelete == true) {
                                {
                                    onAction(DeleteFeedbackCommand(workout.id.orEmpty()))
                                }
                            } else {
                                null
                            },
                        )
                    }
                    activityPlacement.byWorkoutId[workout.id.orEmpty()]
                        .orEmpty()
                        .forEach { activity ->
                            TodayActivity(
                                activity = activity,
                                actionPending = actionPending,
                                onOpenDetails = {
                                    selectedActivity = activity
                                    activity.id?.let(onLoadActivityTrace)
                                },
                                onDecision = { decision ->
                                    pendingDecision = PendingPlanDecision(
                                        source = "activity",
                                        sourceId = activity.id.orEmpty(),
                                        decision = decision,
                                        consequence = requireNotNull(activity.consequence),
                                    )
                                },
                            )
                        }
                }
            }
        }
        if (activityPlacement.unplaced.isNotEmpty()) {
            item { SectionLabel(if (todaysWorkouts.isEmpty()) "Recorded today" else "Other activity today") }
            items(
                activityPlacement.unplaced,
                key = { "today-activity-${it.id.orEmpty()}" },
            ) { activity ->
                TodayActivity(
                    activity = activity,
                    actionPending = actionPending,
                    onOpenDetails = {
                        selectedActivity = activity
                        activity.id?.let(onLoadActivityTrace)
                    },
                    onDecision = { decision ->
                        pendingDecision = PendingPlanDecision(
                            source = "activity",
                            sourceId = activity.id.orEmpty(),
                            decision = decision,
                            consequence = requireNotNull(activity.consequence),
                        )
                    },
                )
            }
        }
        calendar?.activityOverflow?.takeIf { it.truncated == true }?.let {
            item { Notice("Only the most recent activities are shown for this month.") }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Other actions",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { showManualRun = true },
                        enabled = !actionPending,
                    ) {
                        Text("Record an unplanned run")
                    }
                    TextButton(onClick = { onDestinationSelected(NativeDestination.Calendar) }) {
                        Text("Open calendar")
                    }
                }
            }
        }
    }
    feedbackWorkout?.let { workout ->
        FeedbackDialog(
            workout = workout,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { feedbackWorkout = null },
            onSubmit = {
                submittedDialogAction = "record_feedback"
                onAction(it)
            },
        )
    }
    if (showManualRun) {
        ManualRunDialog(
            actionPending = actionPending,
            defaultDate = today.ifBlank { LocalDate.now().toString() },
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { showManualRun = false },
            onSubmit = {
                submittedDialogAction = "record_manual_run"
                onAction(it)
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
    selectedActivity?.let { activity ->
        ImportedActivityDetailSheet(
            activity = activity,
            candidates = payload?.activityCandidates.orEmpty(),
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
}

@Composable
private fun TodayActivity(
    activity: NativeActivity,
    actionPending: Boolean,
    onOpenDetails: () -> Unit,
    onDecision: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityCard(
            activity = activity,
            title = if (activity.workoutId.isNullOrBlank()) "Unplanned activity" else "Recorded activity",
            actions = {
                TextButton(onClick = onOpenDetails) {
                    Text("Open activity details")
                }
            },
        )
        activity.consequence?.let { consequence ->
            ConsequenceChoices(
                consequence = consequence,
                actionPending = actionPending,
                onDecision = onDecision,
            )
        }
    }
}

@Composable
internal fun CalendarScreen(
    payload: NativeCalendarPayload?,
    loading: Boolean,
    actionPending: Boolean,
    actionNotice: NativeNotice?,
    completedAction: String?,
    onCalendarMonthSelected: (String) -> Unit,
    activityEvidence: Map<String, NativeActivityEvidence>,
    activityEvidenceLoading: Set<String>,
    activityEvidenceFailures: Set<String>,
    onLoadActivityTrace: (String) -> Unit,
    onAction: (MobileCommand) -> Unit,
) {
    val calendar = payload?.calendar
    val month = calendar?.month.orEmpty()
    val today = calendar?.today.orEmpty()
    var feedbackWorkout by remember { mutableStateOf<NativeWorkout?>(null) }
    var editWorkout by remember { mutableStateOf<NativeWorkout?>(null) }
    var addWorkoutDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var selectedActivity by remember { mutableStateOf<NativeActivity?>(null) }
    var selectedDay by rememberSaveable(month) { mutableStateOf<String?>(null) }
    var showSelectedDay by rememberSaveable(month) { mutableStateOf(false) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedAction) {
        if (completedAction != submittedDialogAction) return@LaunchedEffect
        when (completedAction) {
            "record_feedback" -> feedbackWorkout = null
            "preview_workout_edit", "preview_workout_removal" -> editWorkout = null
            "preview_workout_add" -> addWorkoutDate = null
            "preview_plan_decision", "apply_plan_decision" -> pendingDecision = null
            else -> if (selectedActivity != null) selectedActivity = null
        }
        submittedDialogAction = null
    }
    NativeList(loading) {
        item { ScreenIntro("Calendar", "Your plan and completed runs by date.") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        calendar?.previousMonth?.takeIf(String::isNotBlank)
                            ?.let(onCalendarMonthSelected)
                    },
                    enabled = calendar != null,
                ) { Text("Earlier") }
                Text(monthLabel(month), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = {
                        calendar?.nextMonth?.takeIf(String::isNotBlank)
                            ?.let(onCalendarMonthSelected)
                    },
                    enabled = calendar != null,
                ) { Text("Later") }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        addWorkoutDate = runCatching {
                            LocalDate.parse(today).plusDays(1).toString()
                        }.getOrDefault(LocalDate.now().plusDays(1).toString())
                    },
                    enabled = !actionPending && payload?.onboardingRequired != true,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Add a future run")
                }
            }
        }
        when {
            payload == null -> item { EmptyCard("Loading the calendar…") }
            payload.onboardingRequired == true -> item {
                EmptyCard("Finish your training setup to build a calendar.")
            }
            else -> {
                val workouts = calendar?.workouts.orEmpty()
                val activities = calendar?.activities.orEmpty()
                val feedbackByWorkout = calendar?.feedback.orEmpty()
                    .associateBy { it.workoutId.orEmpty() }
                item {
                    CalendarMonthLedger(
                        month = month,
                        today = today,
                        selectedDay = selectedDay,
                        workouts = workouts,
                        activities = activities,
                        feedbackByWorkout = feedbackByWorkout,
                        onDaySelected = { day ->
                            selectedDay = day
                            showSelectedDay = true
                        },
                    )
                }
                if (workouts.isEmpty() && activities.isEmpty()) {
                    item { EmptyCard("Nothing is scheduled in this month yet. Select a day to add a future run.") }
                }
            }
        }
    }
    feedbackWorkout?.let { workout ->
        FeedbackDialog(
            workout = workout,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { feedbackWorkout = null },
            onSubmit = {
                submittedDialogAction = "record_feedback"
                onAction(it)
            },
        )
    }
    editWorkout?.let { workout ->
        WorkoutEditDialog(
            workout = workout,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { editWorkout = null },
            onSubmit = {
                submittedDialogAction = "preview_workout_edit"
                onAction(it)
            },
            onRemove = {
                submittedDialogAction = "preview_workout_removal"
                onAction(PreviewWorkoutRemovalCommand(workout.id.orEmpty()))
            },
        )
    }
    addWorkoutDate?.let { date ->
        WorkoutAddDialog(
            defaultDate = date,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { addWorkoutDate = null },
            onSubmit = {
                submittedDialogAction = "preview_workout_add"
                onAction(it)
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
    selectedActivity?.let { activity ->
        ImportedActivityDetailSheet(
            activity = activity,
            candidates = payload?.activityCandidates.orEmpty(),
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
    selectedDay?.takeIf { showSelectedDay }?.let { date ->
        CalendarDayDetailSheet(
            date = date,
            today = today,
            workouts = calendar?.workouts.orEmpty().filter { it.scheduledDate == date },
            activities = calendar?.activities.orEmpty().filter {
                (it.occurredDate ?: it.activityDate) == date
            },
            feedbackByWorkout = calendar?.feedback.orEmpty().associateBy { it.workoutId.orEmpty() },
            actionPending = actionPending,
            onDismiss = { showSelectedDay = false },
            onRecordFeedback = { feedbackWorkout = it },
            onEditWorkout = { editWorkout = it },
            onResetWorkout = { onAction(ResetWorkoutCommand(it.id.orEmpty())) },
            onUndoWorkout = { adjustmentId -> onAction(UndoWorkoutAdjustmentCommand(adjustmentId)) },
            onDeleteFeedback = { workoutId -> onAction(DeleteFeedbackCommand(workoutId)) },
            onOpenActivity = { activity ->
                selectedActivity = activity
                activity.id?.let(onLoadActivityTrace)
            },
            onPlanDecision = { pendingDecision = it },
            onAddWorkout = {
                showSelectedDay = false
                addWorkoutDate = date
            },
        )
    }
}

@Composable
private fun CalendarMonthLedger(
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
    val borderColor = when {
        isSelected || isToday -> MaterialTheme.colorScheme.primary
        presentation.emphasis == CalendarCellEmphasis.Actual -> MaterialTheme.colorScheme.tertiary
        presentation.emphasis == CalendarCellEmphasis.Review -> RunwayThemeTokens.review
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        presentation.emphasis == CalendarCellEmphasis.Actual ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        presentation.emphasis == CalendarCellEmphasis.Review ->
            RunwayThemeTokens.review.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
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
        border = BorderStroke(
            if (isSelected || isToday) 2.dp else 1.dp,
            borderColor,
        ),
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
                Text(
                    presentation.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = presentation.emphasis.color(),
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
    CalendarCellEmphasis.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    CalendarCellEmphasis.Planned -> MaterialTheme.colorScheme.primary
    CalendarCellEmphasis.Actual -> MaterialTheme.colorScheme.tertiary
    CalendarCellEmphasis.Review -> RunwayThemeTokens.review
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDayDetailSheet(
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
                        TodayActivity(
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
                    TodayActivity(
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
