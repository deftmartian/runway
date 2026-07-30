package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun ActivityReviewDialog(
    activity: NativeActivity,
    candidates: List<NativeWorkout>,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onAction: (MobileCommand) -> Unit,
) {
    var harderThanExpected by rememberSaveable(activity.feltHard) {
        mutableStateOf(activity.feltHard == true)
    }
    var painDuringOrAfter by rememberSaveable(activity.pain) {
        mutableStateOf(activity.pain == true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where does this run belong?") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    ActivityCard(activity)
                }
                item {
                    CheckRow("Felt harder than expected", harderThanExpected) {
                        harderThanExpected = it
                    }
                    CheckRow("Pain occurred during or after this run", painDuringOrAfter) {
                        painDuringOrAfter = it
                    }
                    OutlinedButton(
                        onClick = {
                            onAction(
                                UpdateActivityFeedbackCommand(
                                    activityId = activity.id.orEmpty(),
                                    feltHard = harderThanExpected,
                                    pain = painDuringOrAfter,
                                ),
                            )
                        },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save how it felt")
                    }
                }
                if (candidates.isNotEmpty()) {
                    item { Text("Match to a planned run", fontWeight = FontWeight.SemiBold) }
                    items(candidates, key = { it.id.orEmpty() }) { workout ->
                        OutlinedButton(
                            onClick = {
                                onAction(
                                    LinkActivityCommand(
                                        activityId = activity.id.orEmpty(),
                                        workoutId = workout.id.orEmpty(),
                                    ),
                                )
                            },
                            enabled = !actionPending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${workout.scheduledDate.orEmpty()} · " +
                                    workout.purpose.orEmpty().ifBlank { "Planned run" },
                            )
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            onAction(ConfirmActivityExtraCommand(activity.id.orEmpty()))
                        },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Count as an extra run")
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            onAction(DeleteActivityCommand(activity.id.orEmpty()))
                        },
                        enabled = !actionPending,
                    ) {
                        Text("Delete imported activity")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun WorkoutPreviewDialog(
    preview: LocalWorkoutChangeDisplay,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val assessment = nativeLoadAssessment(preview.risk.name.lowercase())
    val weeklyChange = preview.weeklyLoadChangePercent
    val spacingConflicts = preview.spacingConflicts.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review the plan effect") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                errorMessage?.let { Notice(it, isError = true) }
                SettingRow("Load assessment", assessment.label)
                preview.recommended?.let { PreviewPrescriptionRow("Generated", it) }
                PreviewPrescriptionRow("Current", preview.current)
                PreviewPrescriptionRow("Proposed", preview.proposed)
                SettingRow("Largest weekly load change", "${String.format(Locale.US, "%.1f", weeklyChange)}%")
                val ramp = nativeRampAssessment(preview.projectedRampRisk.name.lowercase())
                SettingRow("Projected ramp", "${String.format(Locale.US, "%.1f", preview.projectedRampPercent)}% · ${ramp.label}")
                preview.weeks.forEach { week ->
                    val distance = "${formatDistance(week.distanceBefore.toDouble())} → ${formatDistance(week.distanceAfter.toDouble())}"
                    val duration = "${formatDuration(week.durationBefore.toDouble())} → ${formatDuration(week.durationAfter.toDouble())}"
                    SettingRow(
                        week.label,
                        "$distance · $duration",
                    )
                }
                preview.changes.forEach { change ->
                    val before = previewPrescriptionMeasurement(change.before)
                    val after = previewPrescriptionMeasurement(change.after)
                    SettingRow("Affected workout", "$before → $after")
                }
                if (preview.requiresConfirmation) Notice("This change needs your explicit confirmation because it affects load, spacing, or prescription basis.")
                if (spacingConflicts > 0) {
                    Notice(
                        "$spacingConflicts nearby run${if (spacingConflicts == 1) "" else "s"} may leave little recovery time.",
                        isError = true,
                    )
                }
                Text(
                    "The original recommendation stays in the adjustment ledger, so this can be undone later.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !actionPending) { Text("Apply change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Go back") } },
    )
}

@Composable
private fun PreviewPrescriptionRow(label: String, prescription: dev.deftmartian.runway.domain.WorkoutProposal) {
    SettingRow(label, previewPrescriptionMeasurement(prescription))
}

private fun previewPrescriptionMeasurement(prescription: dev.deftmartian.runway.domain.WorkoutProposal): String =
    formatPrescriptionMeasurement(
        prescription.targetDistanceMeters.toDouble(),
        prescription.targetDurationSeconds?.toDouble(),
        prescription.prescriptionKind == dev.deftmartian.runway.domain.PrescriptionKind.REST || prescription.type == dev.deftmartian.runway.domain.WorkoutType.REST,
    ).let { amount ->
        listOf(prescription.scheduledDate.toString(), prescription.purpose.takeIf { it.isNotBlank() }, amount)
            .joinToString(" · ")
    }

@Composable
internal fun FeedbackOutcomeCard(
    feedback: NativeWorkoutFeedback,
    actionPending: Boolean,
    onDecision: (String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recorded result", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val distance = feedback.completedDistanceMeters?.let(::formatDistance)
            val duration = feedback.completedDurationSeconds?.let(::formatDuration)
            if (distance != null || duration != null) {
                Text(
                    listOfNotNull(distance, duration).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (feedback.pain == true) {
                Text("Pain was reported", color = MaterialTheme.colorScheme.error)
            } else if (feedback.feltHard == true) {
                Text("This felt harder than expected", color = MaterialTheme.colorScheme.primary)
            }
            feedback.consequence?.let {
                ConsequenceChoices(it, actionPending, onDecision)
            }
            onDelete?.let {
                TextButton(onClick = it, enabled = !actionPending) {
                    Text("Remove saved result")
                }
            }
        }
    }
}

@Composable
internal fun ConsequenceChoices(
    consequence: NativeConsequence,
    actionPending: Boolean,
    onDecision: (String) -> Unit,
) {
    val applied = consequence.appliedDecision?.takeIf(String::isNotBlank)
    val options = consequence.options
    val recommended = consequence.recommendedDecision?.takeIf(String::isNotBlank)
    val deviation = consequence.deviation?.takeIf(String::isNotBlank)
    val assessment = nativeConsequenceAssessment(
        consequence.kind,
        consequence.risk,
        consequence.comparisonStatus,
    )
    if (deviation != null) {
        SettingRow("Plan difference", deviation.replace('_', ' ').replaceFirstChar { it.uppercase() })
    }
    SettingRow("Assessment", assessment.label)
    if (consequence.kind == "pain_reported") {
        Notice(
            "A plan adjustment is not clearance to continue. Seek qualified guidance if pain is sharp, persists, worsens, or changes your gait.",
            isError = true,
        )
    }
    when {
        applied != null -> {
            Text(
                "Next choice: ${planDecisionLabel(applied)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        consequence.planChangeAvailable != false && options.isNotEmpty() -> {
            Text(
                "Choose what changes next. The recorded work is already counted.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            options.forEach { decision ->
                val label = planDecisionLabel(decision) +
                    if (decision == recommended) " · Recommended" else ""
                if (decision == recommended) {
                    Button(
                        onClick = { onDecision(decision) },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onDecision(decision) },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlanDecisionDialog(
    pending: PendingPlanDecision,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val assessment = nativeConsequenceAssessment(
        pending.consequence.kind,
        pending.consequence.risk,
        pending.consequence.comparisonStatus,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(planDecisionLabel(pending.decision)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { Notice(it, isError = true) }
                Text(planDecisionExplanation(pending.decision))
                SettingRow("Current assessment", assessment.label)
                Text(
                    "Only future planned work changes. This recorded result remains unchanged.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !actionPending) { Text("Apply choice") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Go back") } },
    )
}

@Composable
internal fun PlanDecisionPreviewDialog(
    preview: LocalPlanDecisionPreview,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review the future plan") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                errorMessage?.let { Notice(it, isError = true) }
                SettingRow("Decision", planDecisionLabel(preview.decision))
                SettingRow("Based on", preview.sourceLabel)
                if (preview.changes.isEmpty()) {
                    Text(
                        "No future workout changes. The recorded result remains in the training log.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    preview.changes.forEach { change ->
                        SettingCard(change.scheduledDate) {
                            SettingRow("Current", change.before)
                            SettingRow("After choice", change.after)
                        }
                    }
                    Text(
                        "Only these future workouts change. The recorded result and original recommendation remain in the ledger.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !actionPending) {
                Text("Apply choice")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !actionPending) {
                Text("Go back")
            }
        },
    )
}

@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
internal fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.length <= 10 && next.matches(Regex("\\d*(\\.\\d*)?"))) {
                onValueChange(next)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}
