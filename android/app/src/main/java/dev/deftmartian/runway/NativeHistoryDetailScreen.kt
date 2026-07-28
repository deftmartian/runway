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
                    HistoryWeekRecord(week, detail.cutoffDate)
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
        plan?.summary?.takeIf { it.kind == "distance" }?.let { summary ->
            val required = summary.requiredWeeklyIncreasePercent
            val default = summary.defaultWeeklyIncreasePercent
            if (required != null && default != null) {
                SettingRow("Ramp evidence", "${formatPercent(required)} vs ${formatPercent(default)} default")
            }
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
        "manual_edit", "manual_add", "manual_remove", "rebalance" -> LedgerEmphasis.Review
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
        Text(changeStateLabel(change.newState), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
private fun HistoryWeekRecord(week: NativeHistoryWeek, cutoffDate: String?) {
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
            week.workouts.forEach { workout -> HistoryWorkoutRecord(workout, cutoffDate) }
        }
    }
}

@Composable
private fun HistoryWorkoutRecord(workout: NativeHistoryWorkout, cutoffDate: String?) {
    val result = workout.result
    val state = workoutStateLabel(workout, cutoffDate)
    val emphasis = when {
        result?.pain == true -> LedgerEmphasis.Danger
        result?.feltHard == true || state == "Short" || state == "Missed" || state == "Skipped" -> LedgerEmphasis.Review
        result != null -> LedgerEmphasis.Actual
        else -> LedgerEmphasis.Planned
    }
    LedgerSurface {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${workout.scheduledDate.orDash()} · ${workoutTypeLabel(workout.type)}", fontWeight = FontWeight.SemiBold)
                workout.purpose?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LedgerState(state, emphasis)
        }
        MeasurementReadout(
            if (workout.type == "rest") "Schedule" else "Planned",
            if (workout.type == "rest") "Recovery" else weekMeasurement(workout.targetDistanceMeters, workout.targetDurationSeconds),
            LedgerEmphasis.Planned,
        )
        result?.let {
            MeasurementReadout(
                "Actual",
                weekMeasurement(it.completedDistanceMeters, it.completedDurationSeconds),
                emphasis,
            )
            val flags = listOfNotNull(if (it.feltHard == true) "Hard effort" else null, if (it.pain == true) "Pain noted" else null)
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

private fun lifecycleLabel(status: String?, reason: String?): String = when {
    status == "active" -> "In progress"
    reason == "completed" -> "Completed"
    reason == "changed_goal" -> "Goal changed"
    reason == "abandoned" -> "Stopped"
    else -> status.orDash()
}
private fun goalLabel(distance: String?): String = when (distance) {
    "5k" -> "5K"; "10k" -> "10K"; "half" -> "Half marathon"; "marathon" -> "Marathon"
    null, "" -> "Run continuously for 30 minutes"
    else -> distance
}
private fun changeLabel(trigger: String?): String = when (trigger) {
    "manual_edit" -> "User edit"; "manual_add" -> "Workout added"; "manual_remove" -> "Workout removed"
    "rebalance" -> "Week rebalanced"; "feedback" -> "Feedback change"; "link", "import_match" -> "Activity-linked change"
    "decision" -> "Confirmed decision"; else -> "Plan change"
}
private fun changeStateLabel(state: NativeHistoryWorkoutState?): String = when {
    state?.isRemoved == true -> "Removed from current plan"
    state?.prescriptionKind == "rest" || state?.type == "rest" -> "${state.scheduledDate.orDash()} · Rest"
    state?.prescriptionKind == "timed" -> "${state.scheduledDate.orDash()} · ${state.targetDurationSeconds?.let(::formatDuration).orDash()}"
    else -> "${state?.scheduledDate.orDash()} · ${state?.targetDistanceMeters?.let(::formatDistance).orDash()}"
}
private fun workoutStateLabel(workout: NativeHistoryWorkout, cutoffDate: String?): String = when {
    workout.isRemoved == true -> "Removed"
    workout.type == "rest" -> "Rest"
    workout.status == "done" -> "Completed"
    workout.status == "shortened" -> "Short"
    workout.status == "skipped" -> "Skipped"
    workout.status == "planned" && cutoffDate != null && workout.scheduledDate != null && workout.scheduledDate < cutoffDate -> "Missed"
    else -> "Planned"
}
private fun workoutTypeLabel(type: String?): String = when (type) {
    "race" -> "Goal event"; "rest" -> "Rest"; null, "" -> "Run"; else -> "${type.replaceFirstChar(Char::uppercase)} run"
}
private fun weekMeasurement(distance: Double?, duration: Double?): String = when {
    distance != null && distance > 0 -> formatDistance(distance)
    duration != null && duration > 0 -> formatDuration(duration)
    else -> "—"
}
private fun formatPercent(value: Double): String = "${(value * 10).toInt() / 10.0}%"
