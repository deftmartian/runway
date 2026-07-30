package dev.deftmartian.runway

internal sealed interface MobileCommand {
    val action: String
}

internal data class CreatePlanCommand(
    val goalKind: String,
    val startMode: String,
    val raceDistance: String,
    val targetDate: String,
    val priority: String,
    val currentWeeklyDistanceKm: String,
    val currentRunsPerWeek: String,
    val longestRecentRunKm: String,
    val calibrationDurationMinutes: String,
    val availability: List<Int>,
    val preferredLongRunDay: String,
    val timeZone: String,
    val recentInjury: Boolean,
    val currentPain: Boolean,
    val recurringPain: Boolean,
    val medicalRestriction: Boolean,
    val injuryNotes: String,
    val confirmConcentratedSchedule: Boolean,
    val confirmReplace: Boolean,
) : MobileCommand {
    override val action = "create_plan"
}

internal data class RecordFeedbackCommand(
    val workoutId: String,
    val status: String,
    val feltHard: Boolean,
    val pain: Boolean,
    val choice: String = "skip_continue",
    val completedDistanceKm: Double? = null,
    val completedDurationMinutes: Double? = null,
) : MobileCommand {
    override val action = "record_feedback"
}

internal data class DeleteFeedbackCommand(val workoutId: String) : MobileCommand {
    override val action = "delete_feedback"
}

internal data class RecordManualRunCommand(
    val occurredDate: String,
    val distanceKm: Double?,
    val durationMinutes: Double?,
    val feltHard: Boolean,
    val pain: Boolean,
) : MobileCommand {
    override val action = "record_manual_run"
}

internal data class LinkActivityCommand(
    val activityId: String,
    val workoutId: String,
) : MobileCommand {
    override val action = "link_activity"
}

internal data class UnlinkActivityCommand(val activityId: String) : MobileCommand {
    override val action = "unlink_activity"
}

internal data class ConfirmActivityExtraCommand(val activityId: String) : MobileCommand {
    override val action = "confirm_activity_extra"
}

internal data class UpdateActivityFeedbackCommand(
    val activityId: String,
    val feltHard: Boolean,
    val pain: Boolean,
) : MobileCommand {
    override val action = "update_activity_feedback"
}

internal data class DeleteActivityCommand(val activityId: String) : MobileCommand {
    override val action = "delete_activity"
}

internal enum class HealthConnectRecordDecision(val wireValue: String) {
    AcceptCorrection("accept_correction"),
    KeepCurrent("keep_current"),
    DeleteFromRunway("delete_from_runway"),
    RetainInRunway("retain_in_runway"),
}

internal data class ResolveHealthConnectRecordCommand(
    val provider: String,
    val recordId: String,
    val decision: HealthConnectRecordDecision,
) : MobileCommand {
    override val action = "resolve_health_connect_record"
}

internal enum class HealthConnectDuplicateDecision {
    KeepBoth,
    UseExisting,
}

internal data class ResolveHealthConnectDuplicateCommand(
    val provider: String,
    val recordId: String,
    val decision: HealthConnectDuplicateDecision,
) : MobileCommand {
    override val action = "resolve_health_connect_duplicate"
}

internal data class PreviewPlanDecisionCommand(
    val source: String,
    val sourceId: String,
    val decision: String,
) : MobileCommand {
    override val action = "preview_plan_decision"
}

internal data class WorkoutMutation(
    val scheduledDate: String,
    val type: String,
    val prescriptionKind: String,
    val targetDistanceMeters: Int,
    val targetDurationSeconds: Int?,
    val intervalStructure: TimedIntervalStructureDto?,
    val intensity: String,
    val purpose: String,
    val userReason: String,
    val rebalance: Boolean,
    val confirmRisk: Boolean = false,
)

internal data class PreviewWorkoutEditCommand(
    val workoutId: String,
    val mutation: WorkoutMutation,
) : MobileCommand {
    override val action = "preview_workout_edit"
}

internal data class PreviewWorkoutAddCommand(
    val mutation: WorkoutMutation,
) : MobileCommand {
    override val action = "preview_workout_add"
}

internal data class PreviewWorkoutRemovalCommand(
    val workoutId: String,
) : MobileCommand {
    override val action = "preview_workout_removal"
}

internal data class ResetWorkoutCommand(val workoutId: String) : MobileCommand {
    override val action = "reset_workout"
}

internal data class UndoWorkoutAdjustmentCommand(val adjustmentId: String) : MobileCommand {
    override val action = "undo_workout_adjustment"
}

internal data object CompletePlanCommand : MobileCommand {
    override val action = "complete_plan"
}

internal data class ConfirmPhaseBaselineCommand(
    val expectedPreviewToken: String,
) : MobileCommand {
    override val action = "confirm_phase_baseline"
}

internal data object ContinueBeginnerPhaseCommand : MobileCommand {
    override val action = "continue_beginner_phase"
}

internal data object ArchivePlanCommand : MobileCommand {
    override val action = "archive_plan"
}
