package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.deftmartian.runway.domain.FeedbackStatus
import dev.deftmartian.runway.domain.PrescriptionKind
import dev.deftmartian.runway.domain.WorkoutType
import java.time.LocalDate
import java.util.Locale

internal fun isoDateInputError(value: String): String? = when {
    !value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) ->
        "Use a complete date in YYYY-MM-DD format."
    runCatching { LocalDate.parse(value) }.isFailure ->
        "That date does not exist."
    else -> null
}

internal fun boundedRunMeasurementError(
    value: String,
    required: Boolean,
    minimum: Double,
    maximum: Double,
    unit: String,
): String? {
    if (value.isBlank()) return if (required) "Enter a value in $unit." else null
    val number = value.toDoubleOrNull()
    return if (number != null && number in minimum..maximum) {
        null
    } else {
        "Use a value from ${formatInputBound(minimum)} to ${formatInputBound(maximum)} $unit."
    }
}

internal fun positiveRunMeasurementError(value: String, unit: String): String? {
    val number = value.toDoubleOrNull()
    return if (number != null && number > 0.0) null else "Enter a value greater than 0 $unit."
}

internal fun feedbackPlanChangeMessage(planPhase: String?): String =
    if (planPhase == "routine") {
        "An unusually hard effort is saved with this result. Use Calendar to change the open-run goal for an affected week."
    } else {
        "An unusually hard effort can offer a smaller next run. You can also move the next run later in Calendar. Nothing changes until you choose it."
    }

internal fun workoutPurposeInputError(value: String): String? =
    if (value.trim().length in 2..120) null else "Enter a purpose of 2 to 120 characters."

private fun formatInputBound(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@Composable
internal fun FeedbackDialog(
    workout: NativeWorkout,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (RecordFeedbackCommand) -> Unit,
) {
    val prescriptionKind = workout.prescriptionKind.toPrescriptionKindOrNull()
    val timed = prescriptionKind == PrescriptionKind.TIMED
    val open = prescriptionKind == PrescriptionKind.OPEN
    val routine = workout.planPhase == "routine"
    val storedFormatIssue = feedbackStoredFormatIssue(prescriptionKind)
    var status by rememberSaveable { mutableStateOf(FeedbackStatus.DONE) }
    var distance by rememberSaveable {
        mutableStateOf(
            workout.targetDistanceMeters
                ?.div(1_000)
                ?.takeIf { it > 0 }
                ?.let { String.format(Locale.US, "%.1f", it) }
                .orEmpty(),
        )
    }
    var duration by rememberSaveable {
        mutableStateOf(
            workout.targetDurationSeconds
                ?.div(60)
                ?.takeIf { it > 0 }
                ?.let { String.format(Locale.US, "%.0f", it) }
                .orEmpty(),
        )
    }
    var harderThanExpected by rememberSaveable { mutableStateOf(false) }
    val validation = feedbackMeasurementValidation(status, open, timed, distance, duration)
    val measurementValid =
        storedFormatIssue == null &&
            validation.measurementError == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record this run") },
        text = {
            FeedbackDialogContent(
                workout = workout,
                errorMessage = errorMessage,
                storedFormatIssue = storedFormatIssue,
                status = status,
                onStatusChange = { status = it },
                open = open,
                timed = timed,
                routine = routine,
                distance = distance,
                onDistanceChange = { distance = it },
                duration = duration,
                onDurationChange = { duration = it },
                validation = validation,
                harderThanExpected = harderThanExpected,
                onHarderThanExpectedChange = { harderThanExpected = it },
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        feedbackCommand(workout, status, harderThanExpected, open, timed, distance, duration),
                    )
                },
                enabled = !actionPending && measurementValid,
            ) {
                Text("Save result")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private data class FeedbackMeasurementValidation(
    val distanceError: String?,
    val durationError: String?,
    val measurementError: String?,
)

private fun feedbackStoredFormatIssue(kind: PrescriptionKind?) =
    "This planned run uses an unsupported format, so its result cannot be recorded."
        .takeIf { kind == null || kind == PrescriptionKind.REST }

private fun feedbackMeasurementValidation(
    status: FeedbackStatus,
    open: Boolean,
    timed: Boolean,
    distance: String,
    duration: String,
): FeedbackMeasurementValidation {
    val skipped = status == FeedbackStatus.SKIPPED
    val distanceError = boundedRunMeasurementError(distance, false, 0.1, 100.0, "km")
        .takeIf { !skipped && open }
    val durationError = boundedRunMeasurementError(duration, false, 1.0, 600.0, "minutes")
        .takeIf { !skipped && open }
    val measurementError = when {
        skipped -> null
        open -> distanceError ?: durationError
        timed -> positiveRunMeasurementError(duration, "minutes")
        else -> positiveRunMeasurementError(distance, "km")
    }
    return FeedbackMeasurementValidation(distanceError, durationError, measurementError)
}

@Composable
private fun FeedbackDialogContent(
    workout: NativeWorkout,
    errorMessage: String?,
    storedFormatIssue: String?,
    status: FeedbackStatus,
    onStatusChange: (FeedbackStatus) -> Unit,
    open: Boolean,
    timed: Boolean,
    routine: Boolean,
    distance: String,
    onDistanceChange: (String) -> Unit,
    duration: String,
    onDurationChange: (String) -> Unit,
    validation: FeedbackMeasurementValidation,
    harderThanExpected: Boolean,
    onHarderThanExpectedChange: (Boolean) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { FeedbackNotices(errorMessage, storedFormatIssue) }
        item {
            Text(
                "${workout.scheduledDate.orEmpty()} · ${workout.purpose.orEmpty()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ChoiceRow("I completed a run", status != FeedbackStatus.SKIPPED) {
                onStatusChange(FeedbackStatus.DONE)
            }
            ChoiceRow("I skipped this run", status == FeedbackStatus.SKIPPED) {
                onStatusChange(FeedbackStatus.SKIPPED)
            }
        }
        if (status != FeedbackStatus.SKIPPED) {
            item {
                FeedbackMeasurementFields(
                    open, timed, routine, distance, onDistanceChange, duration,
                    onDurationChange, validation,
                )
            }
        }
        item {
            CheckRow("Effort was unusually hard", harderThanExpected, onHarderThanExpectedChange)
        }
        item {
            Text(
                feedbackPlanChangeMessage(workout.planPhase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeedbackNotices(errorMessage: String?, storedFormatIssue: String?) {
    errorMessage?.let { Notice(it, isError = true) }
    storedFormatIssue?.let { Notice(it, isError = true) }
}

@Composable
private fun FeedbackMeasurementFields(
    open: Boolean,
    timed: Boolean,
    routine: Boolean,
    distance: String,
    onDistanceChange: (String) -> Unit,
    duration: String,
    onDurationChange: (String) -> Unit,
    validation: FeedbackMeasurementValidation,
) {
    if (open) {
        NumberField("Distance (km, optional)", distance, validation.distanceError, onDistanceChange)
        NumberField("Duration (minutes, optional)", duration, validation.durationError, onDurationChange)
    } else {
        NumberField(
            if (timed) "Completed duration (minutes)" else "Completed distance (km)",
            if (timed) duration else distance,
            validation.measurementError,
            if (timed) onDurationChange else onDistanceChange,
        )
    }
    Text(
        feedbackMeasurementExplanation(open, routine),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun feedbackMeasurementExplanation(open: Boolean, routine: Boolean) = when {
    open && routine -> "Record either measurement if it is useful. This routine tracks that you ran; it does not compare the amount with a target."
    open -> "Record either measurement if it is useful. This run has no target to compare."
    else -> "Enter what happened. Runway will compare it with the planned amount and show whether it was near, under, or over plan."
}

private fun feedbackCommand(
    workout: NativeWorkout,
    status: FeedbackStatus,
    harderThanExpected: Boolean,
    open: Boolean,
    timed: Boolean,
    distance: String,
    duration: String,
) = RecordFeedbackCommand(
    workoutId = workout.id.orEmpty(),
    status = status,
    feltHard = harderThanExpected,
    completedDistanceKm = distance.toDoubleOrNull()
        .takeIf { status != FeedbackStatus.SKIPPED && (open || !timed) },
    completedDurationMinutes = duration.toDoubleOrNull()
        .takeIf { status != FeedbackStatus.SKIPPED && (open || timed) },
)
@Composable
internal fun ManualRunDialog(
    actionPending: Boolean,
    defaultDate: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (RecordManualRunCommand) -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(defaultDate) }
    var distance by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf("") }
    var harderThanExpected by rememberSaveable { mutableStateOf(false) }
    val distanceNumber = distance.toDoubleOrNull()
    val durationNumber = duration.toDoubleOrNull()
    val dateError = isoDateInputError(date)
    val distanceError = boundedRunMeasurementError(
        value = distance,
        required = false,
        minimum = 0.1,
        maximum = 100.0,
        unit = "km",
    )
    val durationError = boundedRunMeasurementError(
        value = duration,
        required = false,
        minimum = 1.0,
        maximum = 600.0,
        unit = "minutes",
    )
    val valid =
        dateError == null &&
            distanceError == null &&
            durationError == null &&
            (distanceNumber != null || durationNumber != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a run manually") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it.take(10) },
                        label = { Text("Date (YYYY-MM-DD)") },
                        isError = dateError != null,
                        supportingText = dateError?.let { message -> { Text(message) } },
                        singleLine = true,
                    )
                }
                item {
                    NumberField(
                        "Distance (km, optional)",
                        distance,
                        errorMessage = distanceError,
                    ) { distance = it }
                }
                item {
                    NumberField(
                        "Duration (minutes, optional)",
                        duration,
                        errorMessage = durationError,
                    ) { duration = it }
                }
                item {
                    Text(
                        "Enter the distance, duration, or both.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text(
                        "After saving, link this run to planned work or count it as extra in Inbox. It does not affect totals or future workouts until you choose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    CheckRow("This run felt harder than expected", harderThanExpected) {
                        harderThanExpected = it
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        RecordManualRunCommand(
                            occurredDate = date,
                            distanceKm = distanceNumber,
                            durationMinutes = durationNumber,
                            feltHard = harderThanExpected,
                        ),
                    )
                },
                enabled = !actionPending && valid,
            ) {
                Text("Save for review")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
@Composable
internal fun WorkoutAddDialog(
    defaultDate: String,
    routine: Boolean = false,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (PreviewWorkoutAddCommand) -> Unit,
) {
    var scheduledDate by rememberSaveable { mutableStateOf(defaultDate) }
    var type by rememberSaveable { mutableStateOf(WorkoutType.EASY) }
    var distance by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable(routine) {
        mutableStateOf(if (routine) "Open run" else "Easy aerobic run")
    }
    var reason by rememberSaveable { mutableStateOf("") }
    var rebalance by rememberSaveable { mutableStateOf(false) }
    val distanceNumber = distance.toDoubleOrNull()
    val dateError = isoDateInputError(scheduledDate)
    val distanceError = if (routine) {
        null
    } else {
        boundedRunMeasurementError(
            value = distance,
            required = true,
            minimum = 0.1,
            maximum = 100.0,
            unit = "km",
        )
    }
    val purposeError = workoutPurposeInputError(purpose)
    val valid =
        dateError == null &&
            distanceError == null &&
            purposeError == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a future run") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    Text(
                        if (routine) {
                            "This adds one open run to this week's goal. It does not change any other week."
                        } else {
                            "Runway will show the weekly-load effect before adding it."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = scheduledDate,
                        onValueChange = { scheduledDate = it.take(10) },
                        label = { Text("Date (YYYY-MM-DD)") },
                        isError = dateError != null,
                        supportingText = dateError?.let { message -> { Text(message) } },
                        singleLine = true,
                    )
                }
                if (!routine) {
                    item {
                        listOf(
                            WorkoutType.EASY to "Easy",
                            WorkoutType.LONG to "Long",
                            WorkoutType.RECOVERY to "Recovery",
                        )
                            .forEach { (value, label) ->
                                ChoiceRow(label, type == value) { type = value }
                            }
                    }
                    item {
                        NumberField(
                            "Distance (km)",
                            distance,
                            errorMessage = distanceError,
                        ) { distance = it }
                    }
                }
                item {
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Purpose") },
                        isError = purposeError != null,
                        supportingText = purposeError?.let { message -> { Text(message) } },
                    )
                }
                item {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Why you’re adding it (optional)") },
                    )
                }
                if (!routine) {
                    item {
                        CheckRow("Rebalance the rest of this week", rebalance) { rebalance = it }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        PreviewWorkoutAddCommand(
                            workoutAddMutation(
                                scheduledDate = scheduledDate,
                                routine = routine,
                                type = type,
                                distanceKm = distanceNumber,
                                purpose = purpose,
                                reason = reason,
                                rebalance = rebalance,
                            ),
                        ),
                    )
                },
                enabled = !actionPending && valid,
            ) {
                Text(if (routine) "Review weekly goal" else "Review addition")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun workoutAddMutation(
    scheduledDate: String,
    routine: Boolean,
    type: WorkoutType,
    distanceKm: Double?,
    purpose: String,
    reason: String,
    rebalance: Boolean,
): WorkoutMutation {
    require(routine || distanceKm != null)
    return WorkoutMutation(
        scheduledDate = scheduledDate,
        type = if (routine) WorkoutType.EASY else type,
        prescriptionKind = if (routine) PrescriptionKind.OPEN else PrescriptionKind.DISTANCE,
        targetDistanceMeters = if (routine) 0 else (requireNotNull(distanceKm) * 1_000).toInt(),
        targetDurationSeconds = null,
        intervalStructure = null,
        intensity = "easy",
        purpose = purpose.trim(),
        userReason = reason.trim(),
        rebalance = !routine && rebalance,
    )
}

@Composable
internal fun WorkoutEditDialog(
    workout: NativeWorkout,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (PreviewWorkoutEditCommand) -> Unit,
    onRemove: () -> Unit,
) {
    val currentPrescriptionKind = workout.prescriptionKind.toPrescriptionKindOrNull()
    val currentWorkoutType = workout.type.toWorkoutTypeOrNull()
    val routine = workout.planPhase == "routine"
    var scheduledDate by rememberSaveable { mutableStateOf(workout.scheduledDate.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(currentWorkoutType) }
    var prescriptionKind by rememberSaveable {
        mutableStateOf(
            currentPrescriptionKind?.takeUnless { it == PrescriptionKind.REST }
                ?: PrescriptionKind.DISTANCE,
        )
    }
    val timed = prescriptionKind == PrescriptionKind.TIMED
    val open = prescriptionKind == PrescriptionKind.OPEN
    var distanceTarget by rememberSaveable {
        mutableStateOf(initialDistanceTarget(currentPrescriptionKind, workout))
    }
    var durationTarget by rememberSaveable {
        mutableStateOf(initialDurationTarget(currentPrescriptionKind, workout))
    }
    var purpose by rememberSaveable { mutableStateOf(workout.purpose.orEmpty()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var rebalance by rememberSaveable { mutableStateOf(false) }
    val load = if (timed) durationTarget else distanceTarget
    val loadNumber = load.toDoubleOrNull()
    val isRest = type == WorkoutType.REST
    val storedFormatIssue = workoutEditStoredFormatIssue(currentPrescriptionKind, currentWorkoutType, type)
    val dateError = isoDateInputError(scheduledDate)
    val loadError = workoutEditLoadError(isRest, open, timed, load)
    val purposeError = workoutPurposeInputError(purpose)
    val valid =
        dateError == null &&
            storedFormatIssue == null &&
            loadError == null &&
            purposeError == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust this workout") },
        text = {
            WorkoutEditDialogContent(
                errorMessage, storedFormatIssue, routine, open, timed, isRest,
                scheduledDate, { scheduledDate = it }, dateError,
                type, { type = it },
                prescriptionKind, { prescriptionKind = it },
                load, loadError,
                { if (timed) durationTarget = it else distanceTarget = it },
                purpose, { purpose = it }, purposeError,
                reason, { reason = it },
                rebalance, { rebalance = it },
                actionPending, onRemove,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        PreviewWorkoutEditCommand(
                            workoutId = workout.id.orEmpty(),
                            mutation = workoutEditMutation(
                                workout, scheduledDate, type, prescriptionKind, loadNumber,
                                routine, isRest, timed, open, purpose, reason, rebalance,
                            ),
                        ),
                    )
                },
                enabled = !actionPending && valid,
            ) {
                Text("Review change")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun initialDistanceTarget(kind: PrescriptionKind?, workout: NativeWorkout) =
    if (kind == PrescriptionKind.DISTANCE) {
        String.format(Locale.US, "%.1f", (workout.targetDistanceMeters ?: 0.0) / 1_000)
    } else ""

private fun initialDurationTarget(kind: PrescriptionKind?, workout: NativeWorkout) =
    if (kind == PrescriptionKind.TIMED) {
        String.format(Locale.US, "%.0f", (workout.targetDurationSeconds ?: 0.0) / 60)
    } else ""

private fun workoutEditStoredFormatIssue(
    prescriptionKind: PrescriptionKind?,
    workoutType: WorkoutType?,
    selectedType: WorkoutType?,
) = when {
    prescriptionKind == null -> "This planned run uses an unsupported format and cannot be changed."
    workoutType == WorkoutType.RACE -> "Race events are changed through goal setup."
    selectedType == null -> "Choose a workout type before saving."
    else -> null
}

private fun workoutEditLoadError(isRest: Boolean, open: Boolean, timed: Boolean, load: String): String? {
    if (isRest || open) return null
    return boundedRunMeasurementError(
        load,
        required = true,
        minimum = if (timed) 10.0 else 0.1,
        maximum = if (timed) 360.0 else 100.0,
        unit = if (timed) "minutes" else "km",
    )
}

@Composable
private fun WorkoutEditDialogContent(
    errorMessage: String?,
    storedFormatIssue: String?,
    routine: Boolean,
    open: Boolean,
    timed: Boolean,
    isRest: Boolean,
    scheduledDate: String,
    onScheduledDateChange: (String) -> Unit,
    dateError: String?,
    type: WorkoutType?,
    onTypeChange: (WorkoutType) -> Unit,
    prescriptionKind: PrescriptionKind,
    onPrescriptionKindChange: (PrescriptionKind) -> Unit,
    load: String,
    loadError: String?,
    onLoadChange: (String) -> Unit,
    purpose: String,
    onPurposeChange: (String) -> Unit,
    purposeError: String?,
    reason: String,
    onReasonChange: (String) -> Unit,
    rebalance: Boolean,
    onRebalanceChange: (Boolean) -> Unit,
    actionPending: Boolean,
    onRemove: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { FeedbackNotices(errorMessage, storedFormatIssue) }
        item { Text(workoutEditExplanation(routine, open), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { WorkoutDateField(scheduledDate, onScheduledDateChange, dateError) }
        if (!routine) item {
            WorkoutTypeChoices(type, open, onTypeChange, onPrescriptionKindChange)
        }
        if (!isRest && !routine) item {
            WorkoutTargetFields(
                open, timed, prescriptionKind, onTypeChange, onPrescriptionKindChange,
                load, loadError, onLoadChange, onRebalanceChange,
            )
        }
        if (!routine) item { WorkoutPurposeField(purpose, onPurposeChange, purposeError) }
        item { WorkoutReasonField(reason, onReasonChange) }
        item {
            if (!isRest && !open) {
                CheckRow("Rebalance the rest of this week", rebalance, onRebalanceChange)
            }
        }
        item {
            TextButton(onClick = onRemove, enabled = !actionPending) {
                Text(if (routine) "Remove from this week's goal" else "Remove this workout")
            }
        }
    }
}

private fun workoutEditExplanation(routine: Boolean, open: Boolean) = when {
    routine -> "Move this open run to another day, or remove it from this week's goal. Distance and duration stay unprescribed."
    open -> "This run has no prescribed amount. You can move it or change its purpose."
    else -> "Runway will show the weekly-load effect before applying this change."
}

@Composable
private fun WorkoutDateField(value: String, onValueChange: (String) -> Unit, error: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(10)) },
        label = { Text("Date (YYYY-MM-DD)") },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        singleLine = true,
    )
}

@Composable
private fun WorkoutTypeChoices(
    type: WorkoutType?,
    open: Boolean,
    onTypeChange: (WorkoutType) -> Unit,
    onPrescriptionKindChange: (PrescriptionKind) -> Unit,
) {
    Text("Workout type", style = MaterialTheme.typography.labelLarge)
    listOf(
        WorkoutType.EASY to "Easy run",
        WorkoutType.LONG to "Long run",
        WorkoutType.RECOVERY to "Recovery run",
        WorkoutType.REST to "Rest",
    ).forEach { (value, label) ->
        ChoiceRow(label, type == value) {
            onTypeChange(value)
            if (open && value !in setOf(WorkoutType.EASY, WorkoutType.REST)) {
                onPrescriptionKindChange(PrescriptionKind.DISTANCE)
            }
        }
    }
}

@Composable
private fun WorkoutTargetFields(
    open: Boolean,
    timed: Boolean,
    prescriptionKind: PrescriptionKind,
    onTypeChange: (WorkoutType) -> Unit,
    onPrescriptionKindChange: (PrescriptionKind) -> Unit,
    load: String,
    loadError: String?,
    onLoadChange: (String) -> Unit,
    onRebalanceChange: (Boolean) -> Unit,
) {
    Text("Target", style = MaterialTheme.typography.labelLarge)
    ChoiceRow("Open run", open) {
        onTypeChange(WorkoutType.EASY)
        onPrescriptionKindChange(PrescriptionKind.OPEN)
        onRebalanceChange(false)
    }
    ChoiceRow("Distance", prescriptionKind == PrescriptionKind.DISTANCE) {
        onPrescriptionKindChange(PrescriptionKind.DISTANCE)
    }
    ChoiceRow("Time", timed) { onPrescriptionKindChange(PrescriptionKind.TIMED) }
    if (!open) {
        NumberField(if (timed) "Duration (minutes)" else "Distance (km)", load, loadError, onLoadChange)
    }
}

@Composable
private fun WorkoutPurposeField(value: String, onValueChange: (String) -> Unit, error: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(120)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Purpose") },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
    )
}

@Composable
private fun WorkoutReasonField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(500)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Why you’re changing it (optional)") },
    )
}

private fun workoutEditMutation(
    workout: NativeWorkout,
    scheduledDate: String,
    type: WorkoutType?,
    prescriptionKind: PrescriptionKind,
    loadNumber: Double?,
    routine: Boolean,
    isRest: Boolean,
    timed: Boolean,
    open: Boolean,
    purpose: String,
    reason: String,
    rebalance: Boolean,
): WorkoutMutation {
    val targetDurationSeconds = workoutEditDurationSeconds(routine, isRest, timed, loadNumber)
    return WorkoutMutation(
        scheduledDate = scheduledDate,
        type = if (routine) WorkoutType.EASY else requireNotNull(type),
        prescriptionKind = workoutEditPrescription(routine, isRest, prescriptionKind),
        targetDistanceMeters = workoutEditDistanceMeters(routine, isRest, timed, open, loadNumber),
        targetDurationSeconds = targetDurationSeconds,
        intervalStructure = workoutEditIntervals(workout, isRest, timed, targetDurationSeconds),
        intensity = if (isRest && !routine) "rest" else "easy",
        purpose = purpose.trim(),
        userReason = reason.trim(),
        rebalance = !routine && rebalance && !isRest && !open,
    )
}

private fun workoutEditPrescription(
    routine: Boolean,
    isRest: Boolean,
    selected: PrescriptionKind,
) = when {
    routine -> PrescriptionKind.OPEN
    isRest -> PrescriptionKind.REST
    else -> selected
}

private fun workoutEditDistanceMeters(
    routine: Boolean,
    isRest: Boolean,
    timed: Boolean,
    open: Boolean,
    loadNumber: Double?,
) = if (routine || isRest || timed || open) 0 else (requireNotNull(loadNumber) * 1_000).toInt()

private fun workoutEditDurationSeconds(
    routine: Boolean,
    isRest: Boolean,
    timed: Boolean,
    loadNumber: Double?,
) = if (!routine && !isRest && timed) (requireNotNull(loadNumber) * 60).toInt() else null

private fun workoutEditIntervals(
    workout: NativeWorkout,
    isRest: Boolean,
    timed: Boolean,
    targetDurationSeconds: Int?,
) = if (!isRest && timed) {
    resizeIntervalStructure(workout.intervalStructure, requireNotNull(targetDurationSeconds))
} else null
