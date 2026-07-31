package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun HistoryDetailScreen(
    payload: NativeHistoryDetailPayload?,
    loading: Boolean,
) {
    val detail = payload?.detail
    NativeList(loading) {
        item {
            ScreenIntro(
                detail?.goal?.title.orDash(),
                "Plan phase, changes, recorded work, and their consequences.",
            )
        }
        when {
            payload == null -> item { EmptyCard("Loading plan record…") }
            payload.onboardingRequired == true || detail == null -> item {
                EmptyCard("This plan record is no longer available.")
            }
            else -> {
                item { PlanRecordSummary(detail) }
                item { SectionLabel("Change timeline") }
                item { PhaseStartedRecord(detail) }
                if (detail.timeline.isEmpty()) {
                    item { EmptyCard("No stored plan changes.") }
                } else {
                    items(detail.timeline, key = { it.id.orEmpty() }) { change ->
                        PlanChangeRecord(change)
                    }
                }
                item { SectionLabel("Weeks and results") }
                items(detail.weeks, key = { it.id.orEmpty() }) { week ->
                    HistoryWeekRecord(
                        week = week,
                        cutoffDate = detail.cutoffDate,
                        planClosed = detail.plan?.status != "active",
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanRecordSummary(detail: NativeHistoryDetail) {
    val plan = detail.plan
    SettingCard("Plan summary") {
        SettingRow("Phase", plan?.phase.orDash())
        SettingRow(
            "Dates",
            listOfNotNull(plan?.startDate, plan?.targetDate).joinToString(" → ").orDash(),
        )
        SettingRow("State", lifecycleLabel(plan?.status, plan?.lifecycleReason))
        SettingRow("Goal", goalLabel(detail.goal?.distance))
        plan?.risk?.takeIf(String::isNotBlank)?.let {
            SettingRow("Ramp assessment", nativeRampAssessment(it).label)
        }
    }
}

@Composable
private fun PhaseStartedRecord(detail: NativeHistoryDetail) {
    LedgerSurface {
        LedgerState("Phase started", LedgerEmphasis.Planned)
        Text(
            "${detail.plan?.phase.orDash()} phase · ${detail.plan?.weeks ?: 0} weeks",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        detail.plan?.startDate?.let {
            Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlanChangeRecord(change: NativeHistoryTimelineItem) {
    val reversed = !change.reversedAt.isNullOrBlank()
    val emphasis = if (reversed) LedgerEmphasis.Neutral else when (change.triggerType) {
        "edit", "add", "remove", "reset",
        "manual_edit", "manual_add", "manual_remove",
        "rebalance", "rebalance_week",
        "reduce_next", "next_rest", "repeat_prescription" -> LedgerEmphasis.Review
        else -> LedgerEmphasis.Planned
    }
    LedgerSurface {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LedgerState(
                if (reversed) "${changeLabel(change.triggerType)} · reversed" else changeLabel(change.triggerType),
                emphasis,
            )
            Text(
                change.createdAt?.take(10).orDash(),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        change.newState?.let {
            Text(
                changeStateLabel(it),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        } ?: Text(
            if (change.triggerType == "keep_plan") "No future workout changed" else "Decision recorded",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        change.reason?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (reversed) {
            Text(
                "Reversed ${change.reversedAt.take(10)}${change.reversalReason?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryWeekRecord(
    week: NativeHistoryWeek,
    cutoffDate: String?,
    planClosed: Boolean,
) {
    SettingCard("Week ${week.weekNumber ?: 0}") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(week.startDate.orDash(), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val flags = listOfNotNull(
                if (week.isDownWeek == true) "Down week" else null,
                if (week.isTaper == true) "Taper" else null,
            )
            if (flags.isNotEmpty()) LedgerState(flags.joinToString(" · "), LedgerEmphasis.Neutral)
        }
        MeasurementReadout(
            "Training",
            weekMeasurement(week.targetDistanceMeters, week.targetDurationSeconds),
            LedgerEmphasis.Planned,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            week.workouts.forEach { workout ->
                HistoryWorkoutRecord(workout, cutoffDate, planClosed)
            }
            week.extraActivities.forEach { activity -> HistoryExtraActivityRecord(activity) }
        }
    }
}

@Composable
private fun HistoryExtraActivityRecord(activity: NativeActivity) {
    LedgerSurface {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${activity.activityDate.orDash()} · Extra run", fontWeight = FontWeight.SemiBold)
            LedgerState(
                if (activity.pain == true) "Pain reported" else "Accepted",
                if (activity.pain == true) LedgerEmphasis.Danger else LedgerEmphasis.Actual,
            )
        }
        MeasurementReadout(
            "Actual",
            weekMeasurement(activity.distanceMeters, activity.durationSeconds),
            if (activity.pain == true) LedgerEmphasis.Danger else LedgerEmphasis.Actual,
        )
        if (activity.feltHard == true) {
            Text("Felt harder than expected", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryWorkoutRecord(
    workout: NativeHistoryWorkout,
    cutoffDate: String?,
    planClosed: Boolean,
) {
    val result = workout.result
    val state = workoutStateLabel(workout, cutoffDate, planClosed)
    val displayPrescription = workout.current
    val emphasis = when {
        result?.pain == true -> LedgerEmphasis.Danger
        result?.feltHard == true || state == "Short" || state == "Missed" || state == "Skipped" -> LedgerEmphasis.Review
        result != null -> LedgerEmphasis.Actual
        state == "Not reached" -> LedgerEmphasis.Neutral
        else -> LedgerEmphasis.Planned
    }
    LedgerSurface {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${displayPrescription.scheduledDate.orDash()} · ${workoutTypeLabel(displayPrescription.type)}",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            LedgerState(state, emphasis)
        }
        if (workout.isRemoved == true) {
            if (workout.generated == workout.current) {
                HistoryPrescriptionRecord(
                    "Generated and current",
                    workout.current,
                    showIdentity = false,
                )
            } else {
                HistoryPrescriptionRecord("Generated", workout.generated)
                HistoryPrescriptionRecord(
                    "Before removal",
                    workout.current,
                    showIdentity = false,
                )
            }
            Text(
                "Removed from the current plan",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (workout.generated == workout.current) {
            HistoryPrescriptionRecord("Planned", workout.current, showIdentity = false)
        } else {
            HistoryPrescriptionRecord("Generated", workout.generated)
            HistoryPrescriptionRecord("Current", workout.current, showIdentity = false)
        }
        result?.let {
            MeasurementReadout(
                "Actual",
                weekMeasurement(it.completedDistanceMeters, it.completedDurationSeconds),
                emphasis,
            )
            val flags = listOfNotNull(if (it.feltHard == true) "Felt harder than expected" else null, if (it.pain == true) "Pain reported" else null)
            if (flags.isNotEmpty()) Text(flags.joinToString(" · "), color = if (it.pain == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            it.consequence?.let { consequence ->
                Text(
                    listOfNotNull(consequence.deviation, consequence.appliedDecision ?: consequence.recommendedDecision)
                        .joinToString(" · ").ifBlank { "Recorded consequence" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryPrescriptionRecord(
    label: String,
    prescription: NativeHistoryPrescription,
    showIdentity: Boolean = true,
) {
    Text(
        if (showIdentity) {
            "$label · ${prescription.scheduledDate.orDash()} · ${workoutTypeLabel(prescription.type)}"
        } else {
            label
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    prescription.purpose?.takeIf(String::isNotBlank)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    MeasurementReadout(
        if (prescription.type == "rest") "$label schedule" else label,
        if (prescription.type == "rest") {
            "Recovery"
        } else {
            weekMeasurement(prescription.targetDistanceMeters, prescription.targetDurationSeconds)
        },
        LedgerEmphasis.Planned,
    )
    formatTimedStructure(prescription.intervalStructure)?.let { detail ->
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun lifecycleLabel(status: String?, reason: String?): String = when {
    status == "active" -> "In progress"
    reason == "completed" -> "Completed"
    reason == "changed_goal" -> "Goal changed"
    reason == "abandoned" -> "Stopped"
    else -> status.orDash()
}
private fun goalLabel(distance: String?): String = when (distance) {
    "5k" -> "5K"
    "10k" -> "10K"
    "half" -> "Half marathon"
    "marathon" -> "Marathon"
    null, "" -> "Run continuously for 30 minutes"
    else -> distance
}
private fun changeLabel(trigger: String?): String = when (trigger) {
    "edit", "manual_edit" -> "Workout edited"
    "add", "manual_add" -> "Workout added"
    "remove", "manual_remove" -> "Workout removed"
    "reset" -> "Recommendation restored"
    "rebalance", "rebalance_week" -> "Week rebalanced"
    "reduce_next" -> "Next run reduced"
    "next_rest" -> "Recovery day chosen"
    "repeat_prescription" -> "Prescription repeated"
    "keep_plan" -> "Plan kept"
    "feedback" -> "Feedback change"
    "link", "import_match" -> "Activity-linked change"
    "decision" -> "Confirmed decision"
    else -> "Plan change"
}
private fun changeStateLabel(state: NativeHistoryWorkoutState?): String = when {
    state?.isRemoved == true -> "Removed from current plan"
    state?.prescriptionKind == "rest" || state?.type == "rest" -> "${state.scheduledDate.orDash()} · Rest"
    state?.prescriptionKind == "timed" -> "${state.scheduledDate.orDash()} · ${state.targetDurationSeconds?.let(::formatDuration).orDash()}"
    else -> "${state?.scheduledDate.orDash()} · ${state?.targetDistanceMeters?.let(::formatDistance).orDash()}"
}
private fun workoutStateLabel(
    workout: NativeHistoryWorkout,
    cutoffDate: String?,
    planClosed: Boolean,
): String = when {
    workout.isRemoved == true -> "Removed"
    workout.current.type == "rest" -> "Rest"
    workout.status == "done" -> "Completed"
    workout.status == "shortened" -> "Short"
    workout.status == "skipped" -> "Skipped"
    workout.status == "planned" &&
        cutoffDate != null &&
        workout.current.scheduledDate != null &&
        workout.current.scheduledDate < cutoffDate -> "Missed"
    planClosed &&
        workout.status == "planned" &&
        cutoffDate != null &&
        workout.current.scheduledDate != null &&
        workout.current.scheduledDate >= cutoffDate -> "Not reached"
    else -> "Planned"
}
private fun workoutTypeLabel(type: String?): String = when (type) {
    "race" -> "Goal event"
    "rest" -> "Rest"
    null, "" -> "Run"
    else -> "${type.replaceFirstChar(Char::uppercase)} run"
}
private fun weekMeasurement(distance: Double?, duration: Double?): String = when {
    distance != null && distance > 0 -> formatDistance(distance)
    duration != null && duration > 0 -> formatDuration(duration)
    else -> "—"
}
private fun formatPercent(value: Double): String = "${(value * 10).toInt() / 10.0}%"
