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
    val storedFormatIssue =
        if (prescriptionKind == null || prescriptionKind == PrescriptionKind.REST) {
            "This workout does not have a supported run prescription, so its result cannot be recorded."
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
    val measurementValid =
        storedFormatIssue == null &&
            (
                status == FeedbackStatus.SKIPPED ||
                    (timed && (duration.toDoubleOrNull() ?: 0.0) > 0) ||
                    (!timed && (distance.toDoubleOrNull() ?: 0.0) > 0)
            )
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
                        NumberField(
                            if (timed) "Completed duration (minutes)" else "Completed distance (km)",
                            if (timed) duration else distance,
                        ) {
                            if (timed) duration = it else distance = it
                        }
                        Text(
                            "Enter what happened. Runway will compare it with the prescription and show whether it was near, under, or over plan.",
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
                        "These reports can offer conservative next-step options. Nothing changes until you choose and apply it.",
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
                                if (status != FeedbackStatus.SKIPPED && !timed) {
                                    distance.toDouble()
                                } else {
                                    null
                                },
                            completedDurationMinutes =
                                if (status != FeedbackStatus.SKIPPED && timed) {
                                    duration.toDouble()
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
    val valid =
        runCatching { LocalDate.parse(date) }.isSuccess &&
            (distance.isBlank() || (distanceNumber ?: 0.0) in 0.1..100.0) &&
            (duration.isBlank() || (durationNumber ?: 0.0) in 1.0..600.0) &&
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
                        singleLine = true,
                    )
                }
                item { NumberField("Distance (km, optional)", distance) { distance = it } }
                item { NumberField("Duration (minutes, optional)", duration) { duration = it } }
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
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (PreviewWorkoutAddCommand) -> Unit,
) {
    var scheduledDate by rememberSaveable { mutableStateOf(defaultDate) }
    var type by rememberSaveable { mutableStateOf(WorkoutType.EASY) }
    var distance by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("Easy aerobic run") }
    var reason by rememberSaveable { mutableStateOf("") }
    var rebalance by rememberSaveable { mutableStateOf(false) }
    val distanceNumber = distance.toDoubleOrNull()
    val valid =
        runCatching { LocalDate.parse(scheduledDate) }.isSuccess &&
            distanceNumber != null &&
            distanceNumber >= 0.1 &&
            purpose.trim().length >= 2
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
                        "Runway will show the weekly-load effect before adding it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = scheduledDate,
                        onValueChange = { scheduledDate = it.take(10) },
                        label = { Text("Date (YYYY-MM-DD)") },
                        singleLine = true,
                    )
                }
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
                item { NumberField("Distance (km)", distance) { distance = it } }
                item {
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Purpose") },
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
                item {
                    CheckRow("Rebalance the rest of this week", rebalance) { rebalance = it }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        PreviewWorkoutAddCommand(
                            WorkoutMutation(
                                scheduledDate = scheduledDate,
                                type = type,
                                prescriptionKind = PrescriptionKind.DISTANCE,
                                targetDistanceMeters =
                                    (requireNotNull(distanceNumber) * 1_000).toInt(),
                                targetDurationSeconds = null,
                                intervalStructure = null,
                                intensity = "easy",
                                purpose = purpose.trim(),
                                userReason = reason.trim(),
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
    val timed = currentPrescriptionKind == PrescriptionKind.TIMED
    var scheduledDate by rememberSaveable { mutableStateOf(workout.scheduledDate.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(currentWorkoutType) }
    var load by rememberSaveable {
        mutableStateOf(
            if (timed) {
                String.format(
                    Locale.US,
                    "%.0f",
                    (workout.targetDurationSeconds ?: 0.0) / 60,
                )
            } else {
                String.format(
                    Locale.US,
                    "%.1f",
                    (workout.targetDistanceMeters ?: 0.0) / 1_000,
                )
            },
        )
    }
    var purpose by rememberSaveable { mutableStateOf(workout.purpose.orEmpty()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var rebalance by rememberSaveable { mutableStateOf(false) }
    val loadNumber = load.toDoubleOrNull()
    val isRest = type == WorkoutType.REST
    val storedFormatIssue =
        when {
            currentPrescriptionKind == null ->
                "This workout has an unsupported stored prescription and cannot be changed."
            currentWorkoutType == WorkoutType.RACE ->
                "Race events are changed through goal setup."
            type == null ->
                "Choose a workout type before saving."
            else -> null
        }
    val valid =
        runCatching { LocalDate.parse(scheduledDate) }.isSuccess &&
            storedFormatIssue == null &&
            (isRest || (loadNumber != null && loadNumber > 0)) &&
            purpose.trim().length >= 2
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
                        "Runway will show the weekly-load effect before applying this change.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = scheduledDate,
                        onValueChange = { scheduledDate = it.take(10) },
                        label = { Text("Date (YYYY-MM-DD)") },
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
                            ChoiceRow(label, type == value) { type = value }
                        }
                }
                if (!isRest) {
                    item {
                        NumberField(
                            if (timed) "Duration (minutes)" else "Distance (km)",
                            load,
                        ) { load = it }
                    }
                }
                item {
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Purpose") },
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
                    CheckRow("Rebalance the rest of this week", rebalance) { rebalance = it }
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
                                    } else if (timed) {
                                        PrescriptionKind.TIMED
                                    } else {
                                        PrescriptionKind.DISTANCE
                                    },
                                targetDistanceMeters =
                                    if (isRest || timed) 0
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
                                rebalance = rebalance,
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
