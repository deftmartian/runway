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
        "Hard effort and pain are saved in your record. A pain report also updates the running check-in; neither changes later routine days."
    } else {
        "These reports can offer conservative next-step options. Nothing changes until you choose and apply it."
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
    val storedFormatIssue =
        if (prescriptionKind == null || prescriptionKind == PrescriptionKind.REST) {
            "This planned run uses an unsupported format, so its result cannot be recorded."
        } else {
            null
        }
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
    var painDuringOrAfter by rememberSaveable { mutableStateOf(false) }
    val distanceError = when {
        status == FeedbackStatus.SKIPPED || !open -> null
        else -> boundedRunMeasurementError(distance, false, 0.1, 100.0, "km")
    }
    val durationError = when {
        status == FeedbackStatus.SKIPPED || !open -> null
        else -> boundedRunMeasurementError(duration, false, 1.0, 600.0, "minutes")
    }
    val measurementError = when {
        status == FeedbackStatus.SKIPPED -> null
        open -> distanceError ?: durationError
        timed -> positiveRunMeasurementError(duration, "minutes")
        else -> positiveRunMeasurementError(distance, "km")
    }
    val measurementValid =
        storedFormatIssue == null &&
            measurementError == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record this run") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                storedFormatIssue?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    Text(
                        "${workout.scheduledDate.orEmpty()} · ${workout.purpose.orEmpty()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    ChoiceRow("I completed a run", status != FeedbackStatus.SKIPPED) {
                        status = FeedbackStatus.DONE
                    }
                    ChoiceRow("I skipped this run", status == FeedbackStatus.SKIPPED) {
                        status = FeedbackStatus.SKIPPED
                    }
                }
                if (status != FeedbackStatus.SKIPPED) {
                    item {
                        if (open) {
                            NumberField(
                                "Distance (km, optional)",
                                distance,
                                errorMessage = distanceError,
                            ) { distance = it }
                            NumberField(
                                "Duration (minutes, optional)",
                                duration,
                                errorMessage = durationError,
                            ) { duration = it }
                        } else {
                            NumberField(
                                if (timed) "Completed duration (minutes)" else "Completed distance (km)",
                                if (timed) duration else distance,
                                errorMessage = measurementError,
                            ) {
                                if (timed) duration = it else distance = it
                            }
                        }
                        Text(
                            if (open && routine) {
                                "Record either measurement if it is useful. This routine tracks that you ran; it does not compare the amount with a target."
                            } else if (open) {
                                "Record either measurement if it is useful. This run has no target to compare."
                            } else {
                                "Enter what happened. Runway will compare it with the planned amount and show whether it was near, under, or over plan."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    CheckRow("Effort was unusually hard", harderThanExpected) {
                        harderThanExpected = it
                    }
                }
                item {
                    CheckRow("Pain during or after this run", painDuringOrAfter) {
                        painDuringOrAfter = it
                    }
                }
                item {
                    Text(
                        feedbackPlanChangeMessage(workout.planPhase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        RecordFeedbackCommand(
                            workoutId = workout.id.orEmpty(),
                            status = status,
                            feltHard = harderThanExpected,
                            pain = painDuringOrAfter,
                            completedDistanceKm =
                                if (status != FeedbackStatus.SKIPPED && (open || !timed)) {
                                    distance.toDoubleOrNull()
                                } else {
                                    null
                                },
                            completedDurationMinutes =
                                if (status != FeedbackStatus.SKIPPED && (open || timed)) {
                                    duration.toDoubleOrNull()
                                } else {
                                    null
                                },
                        ),
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
    var painDuringOrAfter by rememberSaveable { mutableStateOf(false) }
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
                item {
                    CheckRow("Pain during or after this run", painDuringOrAfter) {
                        painDuringOrAfter = it
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
                            pain = painDuringOrAfter,
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
                            "This adds one open run. It does not change your recurring days or any other week."
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
                Text("Review addition")
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
        mutableStateOf(
            if (currentPrescriptionKind == PrescriptionKind.DISTANCE) {
                String.format(
                    Locale.US,
                    "%.1f",
                    (workout.targetDistanceMeters ?: 0.0) / 1_000,
                )
            } else {
                ""
            },
        )
    }
    var durationTarget by rememberSaveable {
        mutableStateOf(
            if (currentPrescriptionKind == PrescriptionKind.TIMED) {
                String.format(
                    Locale.US,
                    "%.0f",
                    (workout.targetDurationSeconds ?: 0.0) / 60,
                )
            } else {
                ""
            },
        )
    }
    var purpose by rememberSaveable { mutableStateOf(workout.purpose.orEmpty()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var rebalance by rememberSaveable { mutableStateOf(false) }
    val load = if (timed) durationTarget else distanceTarget
    val loadNumber = load.toDoubleOrNull()
    val isRest = type == WorkoutType.REST
    val storedFormatIssue =
        when {
            currentPrescriptionKind == null ->
                "This planned run uses an unsupported format and cannot be changed."
            currentWorkoutType == WorkoutType.RACE ->
                "Race events are changed through goal setup."
            type == null ->
                "Choose a workout type before saving."
            else -> null
        }
    val dateError = isoDateInputError(scheduledDate)
    val loadError = if (isRest || open) {
        null
    } else {
        boundedRunMeasurementError(
            value = load,
            required = true,
            minimum = if (timed) 10.0 else 0.1,
            maximum = if (timed) 360.0 else 100.0,
            unit = if (timed) "minutes" else "km",
        )
    }
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                storedFormatIssue?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    Text(
                        if (open) {
                            "This run has no prescribed amount. You can move it, change its purpose, or give this one run a distance or time target."
                        } else {
                            "Runway will show the weekly-load effect before applying this change."
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
                item {
                    Text("Workout type", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        WorkoutType.EASY to "Easy run",
                        WorkoutType.LONG to "Long run",
                        WorkoutType.RECOVERY to "Recovery run",
                        WorkoutType.REST to "Rest",
                    )
                        .forEach { (value, label) ->
                            ChoiceRow(label, type == value) {
                                type = value
                                if (open && value !in setOf(WorkoutType.EASY, WorkoutType.REST)) {
                                    prescriptionKind = PrescriptionKind.DISTANCE
                                }
                            }
                        }
                }
                if (!isRest) {
                    item {
                        Text("Target", style = MaterialTheme.typography.labelLarge)
                        ChoiceRow("Open run", open) {
                            type = WorkoutType.EASY
                            prescriptionKind = PrescriptionKind.OPEN
                            rebalance = false
                        }
                        ChoiceRow("Distance", prescriptionKind == PrescriptionKind.DISTANCE) {
                            prescriptionKind = PrescriptionKind.DISTANCE
                        }
                        ChoiceRow("Time", timed) {
                            prescriptionKind = PrescriptionKind.TIMED
                        }
                    }
                    if (!open) {
                        item {
                            NumberField(
                                if (timed) "Duration (minutes)" else "Distance (km)",
                                load,
                                errorMessage = loadError,
                            ) {
                                if (timed) durationTarget = it else distanceTarget = it
                            }
                        }
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
                        label = { Text("Why you’re changing it (optional)") },
                    )
                }
                item {
                    if (!isRest && !open) {
                        CheckRow("Rebalance the rest of this week", rebalance) { rebalance = it }
                    }
                }
                item {
                    TextButton(onClick = onRemove, enabled = !actionPending) {
                        Text("Remove this workout")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetDurationSeconds = if (!isRest && timed) {
                        (requireNotNull(loadNumber) * 60).toInt()
                    } else {
                        null
                    }
                    onSubmit(
                        PreviewWorkoutEditCommand(
                            workoutId = workout.id.orEmpty(),
                            mutation = WorkoutMutation(
                                scheduledDate = scheduledDate,
                                type = requireNotNull(type),
                                prescriptionKind =
                                    if (isRest) {
                                        PrescriptionKind.REST
                                    } else {
                                        prescriptionKind
                                    },
                                targetDistanceMeters =
                                    if (isRest || timed || open) 0
                                    else (requireNotNull(loadNumber) * 1_000).toInt(),
                                targetDurationSeconds = targetDurationSeconds,
                                intervalStructure =
                                    if (!isRest && timed) {
                                        resizeIntervalStructure(
                                            workout.intervalStructure,
                                            requireNotNull(targetDurationSeconds),
                                        )
                                    } else {
                                        null
                                    },
                                intensity = if (isRest) "rest" else "easy",
                                purpose = purpose.trim(),
                                userReason = reason.trim(),
                                rebalance = rebalance && !isRest && !open,
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
