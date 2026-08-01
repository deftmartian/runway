package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

internal data class NativeCalendarDecisionSummary(
    val todayStatus: String,
    val todayDate: String?,
    val todayPlan: String?,
    val nextStatus: String,
    val nextDate: String?,
    val nextMeasurement: String?,
    val reviewCount: Int,
    val reviewDate: String?,
    val inboxDecisionCount: Int,
    val inboxDecisionCountIsExact: Boolean,
)

internal fun nativeCalendarDecisionSummary(
    calendar: NativeCalendar?,
    nextWorkout: NativeWorkout?,
    inboxDecisionCount: Int = 0,
    inboxDecisionCountIsExact: Boolean = true,
): NativeCalendarDecisionSummary {
    val today = calendar?.today?.takeIf(String::isNotBlank)
    val workouts = calendar?.workouts.orEmpty().filter { it.isRemoved != true }
    val activities = calendar?.activities.orEmpty()
    val todaysActivity = today?.let { date ->
        activities.firstOrNull {
            (it.occurredDate ?: it.activityDate) == date
        }
    }
    val todaysWorkouts = today?.let { date ->
        workouts.filter { it.scheduledDate == date }
    }.orEmpty()
    val todaysWorkout =
        todaysActivity?.workoutId?.let { activityWorkoutId ->
            todaysWorkouts.firstOrNull { it.id == activityWorkoutId }
        }
            ?: todaysWorkouts.firstOrNull { it.type != "rest" }
            ?: todaysWorkouts.firstOrNull()
    val todaysFeedback = calendar?.feedback.orEmpty()
        .firstOrNull { it.workoutId == todaysWorkout?.id }
    val todayStatus = when {
        todaysActivity?.reviewState == "review" -> "Activity needs review"
        todaysWorkout?.type == "rest" && todaysActivity != null -> "Recorded on a rest day"
        todaysActivity != null ->
            todaysActivity.distanceMeters?.let { "Recorded ${formatDistance(it)}" } ?: "Recorded"
        todaysWorkout?.status == "skipped" && todaysWorkout.planPhase == "routine" -> "Skipped"
        todaysWorkout?.status == "skipped" -> "Skipped — review the next run"
        todaysWorkout?.status == "shortened" ->
            todaysFeedback?.completedDistanceMeters
                ?.let { "Shortened to ${formatDistance(it)}" }
                ?: "Shortened"
        todaysWorkout?.status == "done" ->
            todaysFeedback?.completedDistanceMeters
                ?.let { "Completed ${formatDistance(it)}" }
                ?: "Completed"
        todaysFeedback != null -> "Recorded"
        todaysWorkout?.type == "rest" -> "Recovery day"
        todaysWorkout != null -> "Planned"
        else -> "No run planned"
    }
    val linkedWorkoutIds = activities.mapNotNullTo(mutableSetOf(), NativeActivity::workoutId)
    val feedbackWorkoutIds = calendar?.feedback.orEmpty()
        .mapNotNullTo(mutableSetOf(), NativeWorkoutFeedback::workoutId)
    val missedWorkouts = if (today == null) {
        emptyList()
    } else {
        workouts.filter {
            it.type != "rest" &&
                it.planPhase != "routine" &&
                it.status == "planned" &&
                it.scheduledDate?.let { date -> date < today } == true &&
                it.id !in linkedWorkoutIds &&
                it.id !in feedbackWorkoutIds
        }
    }
    val nextStatus = nextWorkout?.purpose.orEmpty().ifBlank {
        nextWorkout?.type.orEmpty().replaceFirstChar(Char::uppercase)
    }.ifBlank { "No run planned" }
    val nextMeasurement = nextWorkout
        ?.let(::calendarWorkoutMeasurement)
        ?.takeUnless { it.equals(nextStatus, ignoreCase = true) }
    return NativeCalendarDecisionSummary(
        todayStatus = todayStatus,
        todayDate = today,
        todayPlan = todaysWorkout
            ?.let(::calendarWorkoutPlanSummary)
            ?.takeUnless { it == todayStatus || it.startsWith("$todayStatus ·") },
        nextStatus = nextStatus,
        nextDate = nextWorkout?.scheduledDate?.takeIf(String::isNotBlank),
        nextMeasurement = nextMeasurement,
        reviewCount = missedWorkouts.size,
        reviewDate = missedWorkouts.firstOrNull()?.scheduledDate,
        inboxDecisionCount = inboxDecisionCount.coerceAtLeast(0),
        inboxDecisionCountIsExact = inboxDecisionCountIsExact,
    )
}

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
    val routine = payload?.activePlanPhase == "routine"
    var feedbackWorkout by remember { mutableStateOf<NativeWorkout?>(null) }
    var editWorkout by remember { mutableStateOf<NativeWorkout?>(null) }
    var addWorkoutDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var selectedActivity by remember { mutableStateOf<NativeActivity?>(null) }
    var selectedDay by rememberSaveable(month) { mutableStateOf<String?>(null) }
    var showSelectedDay by rememberSaveable(month) { mutableStateOf(false) }
    var pendingOpenDay by rememberSaveable { mutableStateOf<String?>(null) }
    var showManualRun by rememberSaveable { mutableStateOf(false) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    val decision = nativeCalendarDecisionSummary(
        calendar = calendar,
        nextWorkout = payload?.nextWorkout,
        inboxDecisionCount = payload?.pendingDecisionCount ?: 0,
        inboxDecisionCountIsExact = payload?.pendingDecisionCountIsExact ?: true,
    )
    fun openDay(date: String?) {
        val validDate = date?.takeIf { it.length >= 10 } ?: return
        if (validDate.take(7) == month) {
            selectedDay = validDate
            showSelectedDay = true
        } else {
            pendingOpenDay = validDate
            onCalendarMonthSelected(validDate.take(7))
        }
    }
    LaunchedEffect(month, pendingOpenDay) {
        val pending = pendingOpenDay ?: return@LaunchedEffect
        if (pending.take(7) == month) {
            selectedDay = pending
            showSelectedDay = true
            pendingOpenDay = null
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
    NativeList(
        loading = loading,
        horizontalContentPadding = 8.dp,
        maxContentWidth = 1_240.dp,
    ) {
        if (payload != null && payload.onboardingRequired != true) {
            item {
                CalendarDecisionCard(
                    summary = decision,
                    actionPending = actionPending,
                    onOpenToday = { openDay(decision.todayDate) },
                    onOpenNext = { openDay(decision.nextDate) },
                    onOpenReview = { openDay(decision.reviewDate) },
                    onOpenInbox = { onDestinationSelected(NativeDestination.Inbox) },
                )
            }
            item {
                CalendarSecondaryPlanActions(
                    actionPending = actionPending,
                    hasActivePlan = payload.hasActivePlan,
                    routine = routine,
                    onRecordRun = { showManualRun = true },
                    onChangeGoal = { onDestinationSelected(NativeDestination.Setup) },
                )
            }
        }
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
                    item {
                        EmptyCard(
                            if (payload.hasActivePlan) {
                                "Nothing is scheduled in this month yet. Select a day to add a future run."
                            } else {
                                "No schedule is active. Recorded runs will still appear here."
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
            canAddWorkout = payload?.hasActivePlan == true,
            onAddWorkout = {
                showSelectedDay = false
                addWorkoutDate = date
            },
        )
    }
}

@Composable
private fun CalendarDecisionCard(
    summary: NativeCalendarDecisionSummary,
    actionPending: Boolean,
    onOpenToday: () -> Unit,
    onOpenNext: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenInbox: () -> Unit,
) {
    val missedRunStatus =
        "${summary.reviewCount} missed run${if (summary.reviewCount == 1) "" else "s"}"
    val nextStatus = summary.nextDate?.let { "${summary.nextStatus} · ${friendlyDate(it)}" }
        ?: summary.nextStatus
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            summary.todayDate?.let {
                Surface(
                    onClick = onOpenToday,
                    enabled = !actionPending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics {
                            contentDescription = listOfNotNull(
                                "Today",
                                summary.todayStatus,
                                summary.todayPlan,
                                "View day details",
                            ).joinToString(". ")
                        },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Today", style = MaterialTheme.typography.labelLarge)
                        Text(summary.todayStatus, style = MaterialTheme.typography.titleMedium)
                        summary.todayPlan?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (summary.todayDate == null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Today. ${summary.todayStatus}"
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Today", style = MaterialTheme.typography.labelLarge)
                        Text(summary.todayStatus, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            summary.nextDate?.let {
                TextButton(
                    onClick = onOpenNext,
                    enabled = !actionPending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics {
                            contentDescription = listOfNotNull(
                                "Next",
                                nextStatus,
                                summary.nextMeasurement,
                                "Open next run",
                            ).joinToString(". ")
                        },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Next", style = MaterialTheme.typography.labelLarge)
                        Text(nextStatus, style = MaterialTheme.typography.bodyMedium)
                        summary.nextMeasurement?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (summary.nextDate == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Next. $nextStatus"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Next", style = MaterialTheme.typography.labelLarge)
                    Text(
                        nextStatus,
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (summary.inboxDecisionCount > 0) {
                val count = summary.inboxDecisionCount
                val countLabel =
                    "${if (summary.inboxDecisionCountIsExact) count else "$count+"} " +
                        "decision${if (count == 1 && summary.inboxDecisionCountIsExact) "" else "s"} waiting"
                TextButton(
                    onClick = onOpenInbox,
                    enabled = !actionPending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "$countLabel. Open Inbox."
                        },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Inbox", style = MaterialTheme.typography.labelLarge)
                        Text(countLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (summary.reviewCount > 0) {
                TextButton(
                    onClick = onOpenReview,
                    enabled = !actionPending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "$missedRunStatus. Open the first missed run."
                        },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Missed runs", style = MaterialTheme.typography.labelLarge)
                        Text(missedRunStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarSecondaryPlanActions(
    actionPending: Boolean,
    hasActivePlan: Boolean,
    routine: Boolean,
    onRecordRun: () -> Unit,
    onChangeGoal: () -> Unit,
) {
    val changeLabel = when {
        !hasActivePlan -> "Set up running"
        routine -> "Change routine"
        else -> "Change goal"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stackActions = usesStackedCalendarPlanActions(
                availableWidthDp = maxWidth.value,
                fontScale = LocalDensity.current.fontScale,
            )
            if (stackActions) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CalendarPlanAction(
                        label = "Record a run",
                        enabled = !actionPending,
                        onClick = onRecordRun,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CalendarPlanAction(
                        label = changeLabel,
                        enabled = !actionPending,
                        onClick = onChangeGoal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CalendarPlanAction(
                        label = "Record a run",
                        enabled = !actionPending,
                        onClick = onRecordRun,
                        modifier = Modifier.weight(1f),
                    )
                    CalendarPlanAction(
                        label = changeLabel,
                        enabled = !actionPending,
                        onClick = onChangeGoal,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarPlanAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
    ) { Text(label) }
}

internal fun usesStackedCalendarPlanActions(
    availableWidthDp: Float,
    fontScale: Float,
): Boolean = availableWidthDp < 360f || fontScale > 1f
