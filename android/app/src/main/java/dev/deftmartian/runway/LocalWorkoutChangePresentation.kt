package dev.deftmartian.runway

import dev.deftmartian.runway.data.PreparedLocalWorkoutChange
import dev.deftmartian.runway.data.LocalWorkoutChangeRequest
import dev.deftmartian.runway.domain.EditPreview
import dev.deftmartian.runway.domain.Risk
import dev.deftmartian.runway.domain.WorkoutProposal

/** A local UI projection, deliberately separate from the retired mobile API DTOs. */
internal data class LocalWorkoutChangePreview(
    val prepared: PreparedLocalWorkoutChange,
    val planId: String,
    val actionId: String,
    val request: LocalWorkoutChangeRequest,
    val operationLabel: String,
    val display: LocalWorkoutChangeDisplay,
)

internal data class LocalWorkoutChangeDisplay(
    val risk: Risk,
    val requiresConfirmation: Boolean,
    val weeklyLoadChangePercent: Double,
    val projectedRampPercent: Double,
    val projectedRampRisk: Risk,
    val recommended: WorkoutProposal?,
    val current: WorkoutProposal,
    val proposed: WorkoutProposal,
    val changes: List<LocalWorkoutChangeLine>,
    val weeks: List<LocalWorkoutWeekLoad>,
    val spacingConflicts: List<String>,
)
internal data class LocalWorkoutChangeLine(val before: WorkoutProposal, val after: WorkoutProposal)
internal data class LocalWorkoutWeekLoad(val label: String, val distanceBefore: Int, val distanceAfter: Int, val durationBefore: Int, val durationAfter: Int)

internal fun EditPreview.toLocalWorkoutChangeDisplay() = LocalWorkoutChangeDisplay(
    risk = risk,
    requiresConfirmation = requiresConfirmation,
    weeklyLoadChangePercent = weeklyLoadChangePercent,
    projectedRampPercent = projectedRampPercent,
    projectedRampRisk = projectedRampRisk,
    recommended = recommended,
    current = current,
    proposed = proposed,
    changes = workoutChanges.map { LocalWorkoutChangeLine(it.before, it.after) },
    weeks = weekLoads.entries.map { (week, loads) ->
        LocalWorkoutWeekLoad(week, loads.first.distanceMeters, loads.second.distanceMeters, loads.first.durationSeconds, loads.second.durationSeconds)
    },
    spacingConflicts = spacingConflicts.map { "${it.scheduledDate}: ${it.purpose}" },
)
