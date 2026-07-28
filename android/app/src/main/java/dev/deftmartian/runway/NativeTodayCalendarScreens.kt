package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

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
            "apply_plan_decision" -> pendingDecision = null
            else -> if (selectedActivity != null) selectedActivity = null
        }
        submittedDialogAction = null
    }
    NativeList(loading) {
        item { ScreenIntro("Today", if (today.isBlank()) "Your next training decision." else today) }
        when {
            payload == null -> item { EmptyCard("Loading today’s plan…") }
            payload.onboardingRequired == true -> item {
                EmptyCard("Finish your training setup to see today’s plan.")
            }
            todaysWorkouts.isEmpty() && todaysActivities.isEmpty() -> item {
                EmptyCard("Nothing is scheduled or recorded today. Rest remains part of the plan.")
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
            OutlinedButton(
                onClick = { showManualRun = true },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Record an unplanned run")
            }
            TextButton(onClick = { onDestinationSelected(NativeDestination.Calendar) }) {
                Text("See the calendar")
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
                submittedDialogAction = "apply_plan_decision"
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
    var showAddWorkout by rememberSaveable { mutableStateOf(false) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var selectedActivity by remember { mutableStateOf<NativeActivity?>(null) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedAction) {
        if (completedAction != submittedDialogAction) return@LaunchedEffect
        when (completedAction) {
            "record_feedback" -> feedbackWorkout = null
            "preview_workout_edit", "preview_workout_removal" -> editWorkout = null
            "preview_workout_add" -> showAddWorkout = false
            "apply_plan_decision" -> pendingDecision = null
            else -> if (selectedActivity != null) selectedActivity = null
        }
        submittedDialogAction = null
    }
    NativeList(loading) {
        item { ScreenIntro("Calendar", "Generated plan, your edits, and accepted activity by date.") }
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
            OutlinedButton(
                onClick = { showAddWorkout = true },
                enabled = !actionPending && payload?.onboardingRequired != true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add a future run")
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
                val activityPlacement = placeCalendarActivities(workouts, activities)
                val feedbackByWorkout = calendar?.feedback.orEmpty()
                    .associateBy { it.workoutId.orEmpty() }
                if (workouts.isEmpty() && activities.isEmpty()) {
                    item { EmptyCard("Nothing is scheduled in this month yet.") }
                }
                items(workouts, key = { "workout-${it.id.orEmpty()}" }) { workout ->
                    val canRecord =
                        workout.type != "rest" &&
                            workout.status == "planned" &&
                            workout.scheduledDate.orEmpty() <= today
                    val canEdit =
                        workout.type != "race" &&
                            workout.status == "planned" &&
                            workout.scheduledDate.orEmpty() > today &&
                            workout.isRemoved != true
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkoutCard(
                            workout = workout,
                            onRecord = if (canRecord) ({ feedbackWorkout = workout }) else null,
                            onEdit = if (canEdit) ({ editWorkout = workout }) else null,
                            onReset = if (canEdit && workout.isEdited == true) {
                                {
                                    onAction(ResetWorkoutCommand(workout.id.orEmpty()))
                                }
                            } else {
                                null
                            },
                            onUndo = workout.adjustment?.id
                                ?.takeIf { canEdit && it.isNotBlank() }
                                ?.let { adjustmentId ->
                                    {
                                        onAction(UndoWorkoutAdjustmentCommand(adjustmentId))
                                    }
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
                if (activityPlacement.unplaced.isNotEmpty()) {
                    item { SectionLabel("Other completed activity") }
                    items(
                        activityPlacement.unplaced,
                        key = { "activity-${it.id.orEmpty()}" },
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
    if (showAddWorkout) {
        WorkoutAddDialog(
            defaultDate = runCatching { LocalDate.parse(today).plusDays(1).toString() }
                .getOrDefault(LocalDate.now().plusDays(1).toString()),
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { showAddWorkout = false },
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
                submittedDialogAction = "apply_plan_decision"
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
