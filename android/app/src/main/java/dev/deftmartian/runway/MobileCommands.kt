package dev.deftmartian.runway

internal sealed interface MobileCommand {
    val action: String
}

internal sealed interface PreviewableMobileCommand : MobileCommand {
    fun confirmed(): MobileCommand
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
    val experience: String,
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
    val distanceKm: Double,
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

internal data class ApplyPlanDecisionCommand(
    val source: String,
    val sourceId: String,
    val decision: String,
    val confirmRisk: Boolean = true,
) : MobileCommand {
    override val action = "apply_plan_decision"
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
) : PreviewableMobileCommand {
    override val action = "preview_workout_edit"

    override fun confirmed(): MobileCommand =
        ApplyWorkoutEditCommand(workoutId, mutation.copy(confirmRisk = true))
}

internal data class ApplyWorkoutEditCommand(
    val workoutId: String,
    val mutation: WorkoutMutation,
) : MobileCommand {
    override val action = "apply_workout_edit"
}

internal data class PreviewWorkoutAddCommand(
    val mutation: WorkoutMutation,
) : PreviewableMobileCommand {
    override val action = "preview_workout_add"

    override fun confirmed(): MobileCommand =
        ApplyWorkoutAddCommand(mutation.copy(confirmRisk = true))
}

internal data class ApplyWorkoutAddCommand(
    val mutation: WorkoutMutation,
) : MobileCommand {
    override val action = "apply_workout_add"
}

internal data class PreviewWorkoutRemovalCommand(
    val workoutId: String,
) : PreviewableMobileCommand {
    override val action = "preview_workout_removal"

    override fun confirmed(): MobileCommand = RemoveWorkoutCommand(workoutId)
}

internal data class RemoveWorkoutCommand(val workoutId: String) : MobileCommand {
    override val action = "remove_workout"
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

internal data object ConfirmPhaseBaselineCommand : MobileCommand {
    override val action = "confirm_phase_baseline"
}

internal data object ContinueBeginnerPhaseCommand : MobileCommand {
    override val action = "continue_beginner_phase"
}

internal data object ArchivePlanCommand : MobileCommand {
    override val action = "archive_plan"
}

internal data class UpdateTimeZoneCommand(val timeZone: String) : MobileCommand {
    override val action = "update_time_zone"
}

internal data class UpdateRouteDataModeCommand(val routeDataMode: String) : MobileCommand {
    override val action = "update_route_data_mode"
}

internal data class UpdateHealthContextCommand(
    val recentInjury: Boolean,
    val currentPain: Boolean,
    val recurringPain: Boolean,
    val medicalRestriction: Boolean,
    val injuryNotes: String,
) : MobileCommand {
    override val action = "update_health_context"
}

internal data class UpdateTrainingProfileCommand(
    val sexForEstimates: String,
    val ageYears: Int?,
    val heartRateSettingsSource: String,
    val maxHeartRateBpm: Int,
    val zone2FloorBpm: Int,
    val zone3FloorBpm: Int,
    val zone4FloorBpm: Int,
    val zone5FloorBpm: Int,
) : MobileCommand {
    override val action = "update_training_profile"
}

internal data class ConnectNextcloudCommand(
    val label: String,
    val shareUrl: String,
    val sharePassword: String,
) : MobileCommand {
    override val action = "connect_nextcloud"
}

internal data class TestNextcloudCommand(val sourceId: String) : MobileCommand {
    override val action = "test_nextcloud"
}

internal data class SyncNextcloudCommand(val sourceId: String) : MobileCommand {
    override val action = "sync_nextcloud"
}

internal data class DisconnectNextcloudCommand(val sourceId: String) : MobileCommand {
    override val action = "disconnect_nextcloud"
}
