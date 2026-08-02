package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

internal fun calendarWorkoutPlanSummary(workout: NativeWorkout): String {
    val purpose = workout.purpose.orEmpty().ifBlank {
        workout.type.orEmpty().replaceFirstChar(Char::uppercase)
    }.ifBlank { "Planned run" }
    val measurement = calendarWorkoutMeasurement(workout)
    return if (measurement.equals(purpose, ignoreCase = true)) purpose else "$purpose · $measurement"
}

internal fun calendarWorkoutMeasurement(workout: NativeWorkout): String =
    formatPlannedPrescriptionMeasurement(
        distanceMeters = workout.targetDistanceMeters,
        durationSeconds = workout.targetDurationSeconds,
        rest = workout.type == "rest",
        open = workout.prescriptionKind == "open",
    )

internal data class CalendarScrollPosition(val itemIndex: Int, val itemOffset: Int)

internal fun calendarRecordRunFabVisibleAfterScroll(
    previous: CalendarScrollPosition,
    current: CalendarScrollPosition,
    currentlyVisible: Boolean,
): Boolean = when {
    current.itemIndex == 0 && current.itemOffset == 0 -> true
    current.itemIndex > previous.itemIndex -> false
    current.itemIndex < previous.itemIndex -> true
    current.itemOffset > previous.itemOffset -> false
    current.itemOffset < previous.itemOffset -> true
    else -> currentlyVisible
}

@Composable
internal fun CalendarActivityRecord(
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
    workoutPreview: LocalWorkoutChangePreview?,
    onCalendarMonthSelected: (String) -> Unit,
    activityEvidence: Map<String, NativeActivityEvidence>,
    activityEvidenceLoading: Set<String>,
    activityEvidenceFailures: Set<String>,
    onLoadActivityTrace: (String) -> Unit,
    onDestinationSelected: (NativeDestination) -> Unit,
    onAction: (MobileCommand) -> Unit,
    onApplyWorkoutPreview: () -> Unit,
    onDismissWorkoutPreview: () -> Unit,
) {
    val calendar = payload?.calendar
    val month = calendar?.month.orEmpty()
    val today = calendar?.today.orEmpty()
    val visibleWorkouts = calendar?.workouts.orEmpty().filter { it.type != "rest" }
    val routine = payload?.activePlanPhase == "routine"
    val listState = rememberLazyListState()
    var recordRunFabVisible by rememberSaveable { mutableStateOf(true) }
    var feedbackWorkout by remember { mutableStateOf<NativeWorkout?>(null) }
    var editWorkout by remember { mutableStateOf<NativeWorkout?>(null) }
    var addWorkoutDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var selectedActivity by remember { mutableStateOf<NativeActivity?>(null) }
    var selectedDay by rememberSaveable(month) { mutableStateOf<String?>(null) }
    var showSelectedDay by rememberSaveable(month) { mutableStateOf(false) }
    var showManualRun by rememberSaveable { mutableStateOf(false) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState) {
        var previous = CalendarScrollPosition(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
        )
        snapshotFlow {
            CalendarScrollPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { current ->
            recordRunFabVisible = calendarRecordRunFabVisibleAfterScroll(
                previous = previous,
                current = current,
                currentlyVisible = recordRunFabVisible,
            )
            previous = current
        }
    }
    LaunchedEffect(completedAction) {
        if (completedAction != submittedDialogAction) return@LaunchedEffect
        when (completedAction) {
            "record_feedback" -> feedbackWorkout = null
            "preview_workout_edit", "preview_workout_removal" -> editWorkout = null
            "preview_workout_add" -> addWorkoutDate = null
            "record_manual_run" -> showManualRun = false
            "preview_plan_decision", "apply_plan_decision" -> pendingDecision = null
            else -> if (selectedActivity != null) selectedActivity = null
        }
        submittedDialogAction = null
    }
    Box(modifier = Modifier.fillMaxSize()) {
        NativeList(
            loading = loading,
            state = listState,
            horizontalContentPadding = 8.dp,
            bottomContentPadding = 96.dp,
            maxContentWidth = 1_240.dp,
        ) {
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
            when {
                payload == null -> item { EmptyCard("Loading the calendar…") }
                payload.onboardingRequired == true -> {
                    item { EmptyCard("Finish your training setup to build a calendar.") }
                    item {
                        Button(
                            onClick = { onDestinationSelected(NativeDestination.Setup) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Continue setup")
                        }
                    }
                }
                else -> {
                    val activities = calendar?.activities.orEmpty()
                    val feedbackByWorkout = calendar?.feedback.orEmpty()
                        .associateBy { it.workoutId.orEmpty() }
                    if (!payload.hasActivePlan) {
                        item {
                            EmptyCard(
                                "No schedule is active. Recorded runs will still appear here.",
                            )
                        }
                        item {
                            Button(
                                onClick = { onDestinationSelected(NativeDestination.Setup) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text("Set up running")
                            }
                        }
                    }
                    item {
                        CalendarMonthLedger(
                            month = month,
                            today = today,
                            selectedDay = selectedDay,
                            workouts = visibleWorkouts,
                            activities = activities,
                            feedbackByWorkout = feedbackByWorkout,
                            onDaySelected = { day ->
                                selectedDay = day
                                showSelectedDay = true
                            },
                        )
                    }
                    if (
                        payload.hasActivePlan &&
                        visibleWorkouts.isEmpty() &&
                        activities.isEmpty()
                    ) {
                        item {
                            EmptyCard(
                                "Nothing is scheduled in this month yet. Select a day to add a future run.",
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = recordRunFabVisible &&
                payload != null &&
                payload.onboardingRequired != true &&
                !actionPending,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(
                onClick = { showManualRun = true },
                modifier = Modifier.testTag("calendar-record-run-fab"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "Record a run",
                )
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
            routine = routine,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { addWorkoutDate = null },
            onSubmit = {
                submittedDialogAction = "preview_workout_add"
                onAction(it)
            },
        )
    }
    workoutPreview?.let { pending ->
        WorkoutPreviewDialog(
            preview = pending.display,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = onDismissWorkoutPreview,
            onConfirm = onApplyWorkoutPreview,
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
        ActivityDetailSheet(
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
            workouts = visibleWorkouts.filter { it.scheduledDate == date },
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
            canAddWorkout = payload?.hasActivePlan == true,
            onAddWorkout = {
                showSelectedDay = false
                addWorkoutDate = date
            },
        )
    }
}
