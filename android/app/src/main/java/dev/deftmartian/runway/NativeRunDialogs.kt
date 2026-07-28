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
    val timed = workout.prescriptionKind == "timed"
    var status by rememberSaveable { mutableStateOf("done") }
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
        status == "skipped" ||
            (timed && (duration.toDoubleOrNull() ?: 0.0) > 0) ||
            (!timed && (distance.toDoubleOrNull() ?: 0.0) > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record this run") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    Text(
                        "${workout.scheduledDate.orEmpty()} · ${workout.purpose.orEmpty()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    ChoiceRow("I completed a run", status != "skipped") { status = "done" }
                    ChoiceRow("I skipped this run", status == "skipped") { status = "skipped" }
                }
                if (status != "skipped") {
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
                    CheckRow("Pain changed or limited this run", painDuringOrAfter) {
                        painDuringOrAfter = it
                    }
                }
                item {
                    Text(
                        "Hard effort changes the next-run advice. Pain adds health guidance. Select both when both were true.",
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
                                if (status != "skipped" && !timed) distance.toDouble() else null,
                            completedDurationMinutes =
                                if (status != "skipped" && timed) duration.toDouble() else null,
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
        title = { Text("Record an unplanned run") },
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
                    CheckRow("Felt harder than expected", harderThanExpected) {
                        harderThanExpected = it
                    }
                }
                item {
                    CheckRow("Pain occurred during or after this run", painDuringOrAfter) {
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
                Text("Record run")
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
    var type by rememberSaveable { mutableStateOf("easy") }
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
                    listOf("easy" to "Easy", "long" to "Long", "recovery" to "Recovery")
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
                                prescriptionKind = "distance",
                                targetDistanceMeters = (distanceNumber!! * 1_000).toInt(),
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
    val timed = workout.prescriptionKind == "timed"
    var scheduledDate by rememberSaveable { mutableStateOf(workout.scheduledDate.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(workout.type.orEmpty().ifBlank { "easy" }) }
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
    val isRest = type == "rest"
    val valid =
        runCatching { LocalDate.parse(scheduledDate) }.isSuccess &&
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
                        "easy" to "Easy run",
                        "long" to "Long run",
                        "recovery" to "Recovery run",
                        "rest" to "Rest",
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
                    val targetDurationSeconds =
                        if (!isRest && timed) (loadNumber!! * 60).toInt() else null
                    onSubmit(
                        PreviewWorkoutEditCommand(
                            workoutId = workout.id.orEmpty(),
                            mutation = WorkoutMutation(
                                scheduledDate = scheduledDate,
                                type = type,
                                prescriptionKind =
                                    if (isRest) "rest" else if (timed) "timed" else "distance",
                                targetDistanceMeters =
                                    if (isRest || timed) 0 else (loadNumber!! * 1_000).toInt(),
                                targetDurationSeconds = targetDurationSeconds,
                                intervalStructure =
                                    if (!isRest && timed) {
                                        resizeIntervalStructure(
                                            workout.intervalStructure,
                                            targetDurationSeconds!!,
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
