package dev.deftmartian.runway

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

internal enum class NativeHistoryConfirmation {
    UseBaseline,
    ContinuePhase,
    Complete,
    Stop,
}

@Composable
internal fun PhaseCompletionDecision(
    review: NativePhaseReview,
    lifecycle: NativeHistoryLifecycleState,
    actionPending: Boolean,
    onRequestBaseline: () -> Unit,
    onRequestContinue: () -> Unit,
    onChooseGoal: () -> Unit,
) {
    val baseline = review.baseline
    SettingCard("Confirm the recorded starting point") {
        Text(
            "These accepted activities are the proposed starting point for the next phase.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingRow("Activities", baseline?.activityCount?.toString().orDash())
        SettingRow("Total time", baseline?.totalDurationSeconds?.let(::formatDuration).orDash())
        SettingRow("Total distance", baseline?.totalDistanceMeters?.let(::formatDistance).orDash())
        SettingRow("Longest activity", baseline?.longestActivityMeters?.let(::formatDistance).orDash())
        SettingRow("Recent weekly average", baseline?.weeklyDistanceMeters?.let(::formatDistance).orDash())
        SettingRow("Activities per week", baseline?.runsPerWeek?.let(::formatDecimal).orDash())
        review.racePlan?.let { racePlan ->
            Text(
                "Proposed race phase",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    racePlan.weeks?.let { "$it weeks" },
                    racePlan.startDate?.takeIf(String::isNotBlank),
                    racePlan.targetDate?.takeIf(String::isNotBlank),
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            racePlan.risk?.let {
                SettingRow("Ramp assessment", it.replaceFirstChar(Char::uppercase))
            }
            racePlan.summary?.let { summary ->
                SettingRow(
                    "Starting week",
                    summary.baselineMeters?.let(::formatDistance).orDash(),
                )
                SettingRow(
                    "Peak week",
                    summary.peakMeters?.let(::formatDistance).orDash(),
                )
                SettingRow(
                    "Peak long run",
                    summary.longRunPeakMeters?.let(::formatDistance).orDash(),
                )
                SettingRow(
                    "Required weekly increase",
                    summary.requiredWeeklyIncreasePercent
                        ?.let { "${formatDecimal(it)}%" }
                        .orDash(),
                )
            }
            review.preferredLongRunDay
                ?.let(dayLabels::getOrNull)
                ?.let { longRunDay ->
                    Text(
                        "Preferred long-run day: $longRunDay",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            racePlan.warnings.forEach { warning ->
                Text("• $warning", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (review.goalKind == "race" && !lifecycle.canConfirmBaseline) {
            Notice(
                "The current goal, date, and recorded work do not produce a supported race phase yet. No baseline will be applied.",
            )
        }
        if (lifecycle.canConfirmBaseline) {
            Button(
                onClick = onRequestBaseline,
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Confirm and build race phase")
            }
        }
        if (lifecycle.canContinuePhase) {
            OutlinedButton(
                onClick = onRequestContinue,
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(if (review.phase == "calibration") "Continue calibration" else "Add another beginner week")
            }
        }
        if (lifecycle.canChooseGoal) {
            TextButton(onClick = onChooseGoal, enabled = !actionPending) {
                Text("Choose a later date or shorter goal")
            }
        }
    }
}

@Composable
internal fun CompletePlanCard(
    actionPending: Boolean,
    onComplete: () -> Unit,
) {
    SettingCard("Close this plan as completed") {
        Text(
            "Use this after the target date when the training block reached its intended end. This does not claim every workout was done.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onComplete,
            enabled = !actionPending,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Mark plan complete")
        }
    }
}

@Composable
internal fun HistoryLifecycleConfirmationDialog(
    confirmation: NativeHistoryConfirmation,
    goalTitle: String?,
    phase: String?,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (confirmation) {
        NativeHistoryConfirmation.UseBaseline -> "Use this recorded starting point?"
        NativeHistoryConfirmation.ContinuePhase ->
            if (phase == "calibration") "Continue calibration?" else "Add another beginner week?"
        NativeHistoryConfirmation.Complete -> "Mark this plan complete?"
        NativeHistoryConfirmation.Stop -> "Stop this plan?"
    }
    val explanation = when (confirmation) {
        NativeHistoryConfirmation.UseBaseline ->
            "runway will build the proposed race phase from these accepted activities. The resulting workouts remain editable."
        NativeHistoryConfirmation.ContinuePhase ->
            "runway will extend the current phase using its latest schedule. No race phase will be created yet."
        NativeHistoryConfirmation.Complete ->
            "The schedule will close as completed. Recorded work remains in History, even when some workouts were not done."
        NativeHistoryConfirmation.Stop ->
            "The goal will stop without being marked complete. Recorded work remains in History, and no plan becomes active until you build another one."
    }
    val confirmLabel = when (confirmation) {
        NativeHistoryConfirmation.UseBaseline -> "Confirm and build"
        NativeHistoryConfirmation.ContinuePhase ->
            if (phase == "calibration") "Continue calibration" else "Add week"
        NativeHistoryConfirmation.Complete -> "Mark complete"
        NativeHistoryConfirmation.Stop ->
            goalTitle?.takeIf(String::isNotBlank)?.let { "Stop $it" } ?: "Stop plan"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(explanation) },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !actionPending) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !actionPending) { Text("Cancel") }
        },
    )
}
