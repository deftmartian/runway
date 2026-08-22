package dev.deftmartian.runway.data

import java.time.ZoneId

/** Hard bounds shared by every standalone surface read. */
data class LocalSurfaceReadLimits(
    val calendarWorkouts: Int = 128,
    val calendarActivities: Int = 128,
    val inboxActivities: Int = 50,
    val statsWeeks: Int = 52,
    val statsActivities: Int = 512,
    val historyPlans: Int = 50,
    val historyActivities: Int = 512,
    val evidenceSamples: Int = 500,
) {
    init {
        require(calendarWorkouts in 1..512)
        require(calendarActivities in 1..512)
        require(inboxActivities in 1..1_000)
        require(statsWeeks in 1..104)
        require(statsActivities in 1..2_000)
        require(historyPlans in 1..400)
        require(historyActivities in 1..2_000)
        require(evidenceSamples in 1..1_000)
    }
}

enum class LocalPlanState { ACTIVE, COMPLETED, ARCHIVED, OTHER }
enum class LocalPlanPhase { CALIBRATION, DISTANCE, ROUTINE, OTHER }
enum class LocalPlanProvenance { ACTIVE, COMPLETED, ARCHIVED, OTHER, UNLINKED }

data class LocalLoadReadModel(
    val distanceMeters: Int?,
    val durationSeconds: Int?,
)

/** A typed, ordered timed prescription reconstructed from the local ledger. */
data class LocalTimedIntervalStructureReadModel(
    val warmupSeconds: Int?,
    val cooldownSeconds: Int?,
    val blocks: List<LocalTimedBlockReadModel>,
)

data class LocalTimedBlockReadModel(
    val repetitions: Int,
    val segments: List<LocalTimedSegmentReadModel>,
)

data class LocalTimedSegmentReadModel(
    val kind: String,
    val durationSeconds: Int?,
)

data class LocalPrescriptionReadModel(
    val workoutType: String,
    val prescriptionKind: String,
    val load: LocalLoadReadModel,
    val intensity: String?,
    val purpose: String?,
    val reason: String?,
    val warmupSeconds: Int?,
    val cooldownSeconds: Int?,
    val intervalStructure: LocalTimedIntervalStructureReadModel? = null,
)

data class LocalRoutePointReadModel(
    val latitudeE6: Int,
    val longitudeE6: Int,
    val elapsedSeconds: Int?,
    val elevationMeters: Double?,
    val segmentOrdinal: Int?,
    val speedMetersPerSecond: Double?,
)

data class LocalHeartRatePointReadModel(
    val elapsedSeconds: Int,
    val beatsPerMinute: Int,
    val sourceSampleCount: Int,
)

data class LocalActivityEvidenceReadModel(
    val routeRetained: Boolean,
    val routeStartEndRedacted: Boolean,
    val routeSourcePointCount: Int,
    val route: List<LocalRoutePointReadModel>,
    val heartRateSeriesRetained: Boolean,
    val heartRateSourceSampleCount: Int,
    val heartRate: List<LocalHeartRatePointReadModel>,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val averageCadenceSpm: Int?,
)

/** Stored outcome only; Review items never receive one until the runner accepts their role. */
data class LocalConsequenceReadModel(
    val sourceKind: String,
    val sourceId: String,
    val sourceVersion: String,
    val classification: String?,
    val deviation: String?,
    val loadMetric: String?,
    val actualDifference: Int?,
    val actualLoad: LocalLoadReadModel,
    val risk: String?,
    val recommendedDecision: String?,
    val appliedDecision: String?,
    val options: List<String>,
    val comparisonStatus: String?,
    val planChangeAvailable: Boolean,
)

data class LocalActivitySummaryReadModel(
    val activityId: String,
    val occurredAtEpochMillis: Long,
    val load: LocalLoadReadModel,
)

data class LocalHealthConnectPendingReadModel(
    val mappingId: String,
    val provider: String,
    val externalRecordId: String,
    val state: String,
    val current: LocalActivitySummaryReadModel?,
    val proposed: LocalActivitySummaryReadModel?,
)

data class LocalWorkoutLinkCandidateReadModel(
    val planId: String,
    val workoutId: String,
    val scheduledEpochDay: Long,
    val current: LocalPrescriptionReadModel,
)

data class LocalPhaseReviewReadModel(
    val planId: String,
    val phase: LocalPlanPhase,
    val goalKind: String,
    val phaseEndEpochDay: Long,
    val ready: Boolean,
    val activityCount: Int? = null,
    val totalDistanceMeters: Int? = null,
    val weeklyDistanceMeters: Int? = null,
    val totalDurationSeconds: Int? = null,
    val longestActivityMeters: Int? = null,
    val runsPerWeek: Double? = null,
    val recommendedTransition: String? = null,
    val transitionOptions: List<String> = emptyList(),
    val preferredLongRunDay: Int? = null,
    val racePlan: LocalRacePlanPreview? = null,
)

data class LocalActivityReadModel(
    val activityId: String,
    val source: String,
    val occurredAtEpochMillis: Long,
    val linkedWorkoutId: String?,
    val load: LocalLoadReadModel,
    val feltHard: Boolean?,
    val pain: Boolean?,
    val evidence: LocalActivityEvidenceReadModel,
    val reviewState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    val consequence: LocalConsequenceReadModel? = null,
    val extraPlanImpactConfirmed: Boolean = false,
)

data class LocalWorkoutFeedbackReadModel(
    val feedbackId: String,
    val workoutId: String,
    val completionState: String,
    val completedDistanceMeters: Int?,
    val completedDurationSeconds: Int?,
    val feltHard: Boolean,
    val pain: Boolean,
    val recordedAtEpochMillis: Long,
    val sourceActivityId: String? = null,
)

/** A persisted manual workout change that is still safe to offer for undo. */
data class LocalWorkoutAdjustmentReadModel(
    val workoutId: String,
    val adjustmentId: String,
    val kind: String,
    val createdAtEpochMillis: Long,
)

data class LocalWorkoutReadModel(
    val workoutId: String,
    val planId: String,
    val weekOrdinal: Int,
    val scheduledEpochDay: Long,
    val status: String,
    val isRest: Boolean,
    val isEdited: Boolean,
    val generated: LocalPrescriptionReadModel,
    val current: LocalPrescriptionReadModel,
    val actual: LocalActivityReadModel?,
    val consequence: LocalConsequenceReadModel? = null,
    val adjustment: LocalWorkoutAdjustmentReadModel? = null,
    val planPhase: LocalPlanPhase = LocalPlanPhase.OTHER,
)

data class LocalCalendarDayReadModel(
    val epochDay: Long,
    val workouts: List<LocalWorkoutReadModel>,
    val unlinkedActivities: List<LocalActivityReadModel>,
)

data class LocalCalendarReadModel(
    val fromEpochDay: Long,
    val throughEpochDay: Long,
    val activePlanId: String?,
    val profileExists: Boolean,
    val pendingDecisionCount: Int,
    val pendingDecisionCountIsExact: Boolean,
    val hasMoreActivities: Boolean,
    val days: List<LocalCalendarDayReadModel>,
    val feedback: List<LocalWorkoutFeedbackReadModel> = emptyList(),
    val timeZone: String = ZoneId.systemDefault().id,
    val todayEpochDay: Long = 0,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val nextWorkout: LocalWorkoutReadModel? = null,
    val activePlanPhase: LocalPlanPhase? = null,
    val routineWeekGoals: List<LocalRoutineWeekGoalReadModel> = emptyList(),
)

data class LocalRoutineWeekGoalReadModel(
    val weekId: String,
    val startEpochDay: Long,
    val targetDistanceMeters: Int?,
    val targetDurationSeconds: Int?,
    val scheduledRuns: Int,
    val runs: List<LocalRoutineRunTargetReadModel> = emptyList(),
)

data class LocalRoutineRunTargetReadModel(
    val workoutId: String,
    val scheduledEpochDay: Long,
    val targetDistanceMeters: Int?,
    val targetDurationSeconds: Int?,
)

data class LocalInboxReadModel(
    val reviewCount: Int,
    val reviewCountIsExact: Boolean,
    val hasMore: Boolean,
    val activities: List<LocalActivityReadModel>,
    val pendingWorkoutFeedback: List<LocalPendingWorkoutFeedbackReadModel> = emptyList(),
    val linkCandidates: List<LocalWorkoutLinkCandidateReadModel> = emptyList(),
    val pendingHealthConnect: List<LocalHealthConnectPendingReadModel> = emptyList(),
    val timeZone: String = ZoneId.systemDefault().id,
    val todayEpochDay: Long = 0,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val nextPage: LocalInboxPagingCursor? = null,
)

/** One independent, descending keyset per Inbox decision source. */
data class LocalInboxPagingCursor(
    val review: LocalInboxActivityCursor = LocalInboxActivityCursor(),
    val acceptedExtra: LocalInboxActivityCursor = LocalInboxActivityCursor(),
    val acceptedLinked: LocalInboxActivityCursor = LocalInboxActivityCursor(),
    val directFeedback: LocalInboxFeedbackCursor = LocalInboxFeedbackCursor(),
    val healthConnect: LocalInboxHealthConnectCursor = LocalInboxHealthConnectCursor(),
) {
    val exhausted: Boolean get() = review.exhausted && acceptedExtra.exhausted && acceptedLinked.exhausted &&
        directFeedback.exhausted && healthConnect.exhausted
}

data class LocalInboxActivityCursor(
    val occurredAtEpochMillis: Long? = null,
    val activityId: String? = null,
    val exhausted: Boolean = false,
)

data class LocalInboxFeedbackCursor(
    val recordedAtEpochMillis: Long? = null,
    val feedbackId: String? = null,
    val exhausted: Boolean = false,
)

data class LocalInboxHealthConnectCursor(
    val observedAtEpochMillis: Long? = null,
    val mappingId: String? = null,
    val exhausted: Boolean = false,
)

data class LocalPendingWorkoutFeedbackReadModel(
    val feedbackId: String,
    val workoutId: String,
    val scheduledEpochDay: Long,
    val workoutPurpose: String?,
    val recordedAtEpochMillis: Long,
    val completedDistanceMeters: Int?,
    val completedDurationSeconds: Int?,
    val feltHard: Boolean,
    val pain: Boolean,
    val completionState: String,
    val consequence: LocalConsequenceReadModel,
)

data class LocalWeekStatsReadModel(
    val planId: String,
    val planState: LocalPlanState,
    val phase: LocalPlanPhase,
    val weekOrdinal: Int,
    val startEpochDay: Long,
    val generated: LocalLoadReadModel,
    val current: LocalLoadReadModel,
    val actual: LocalLoadReadModel,
    val plannedRuns: Int,
    val completedRuns: Int,
    val missedRuns: Int,
    val painFlags: Int,
    val hardFlags: Int,
    val weightedPaceSecondsPerKilometre: Double?,
    val durationWeightedHeartRateBpm: Int?,
    val skippedRuns: Int = 0,
    /** Scheduled workout slots with a recorded completion. */
    val plannedRunsRecorded: Int = 0,
    /** Accepted runs in this plan week that are not linked to a scheduled slot. */
    val extraRuns: Int = 0,
)

data class LocalCurrentSignalReadModel(
    val risk: String,
    val reasons: List<String>,
    val source: String,
)

data class LocalRecordedTotalsReadModel(
    val provenance: LocalPlanProvenance,
    val runs: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
)

data class LocalRecordedAggregateLedgerRow(
    val provenance: LocalPlanProvenance,
    val runs: Long,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val longestRunMeters: Int?,
    val pairedDistanceMeters: Long,
    val pairedDurationSeconds: Long,
    val heartRateDurationSeconds: Long,
    val heartRateBeatsSeconds: Long,
)

data class LocalWeekActualAggregateLedgerRow(
    val planId: String,
    val weekId: String,
    val activityRuns: Long,
    val unlinkedRuns: Long,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val longestRunMeters: Int?,
    val pairedDistanceMeters: Long,
    val pairedDurationSeconds: Long,
    val heartRateDurationSeconds: Long,
    val heartRateBeatsSeconds: Long,
    val painFlags: Long,
    val hardFlags: Long,
)

data class LocalStatsReadModel(
    val weeks: List<LocalWeekStatsReadModel>,
    val profileExists: Boolean,
    val recordedTotals: List<LocalRecordedTotalsReadModel>,
    val totalRuns: Int,
    val totalDistanceMeters: Int,
    val totalDurationSeconds: Int,
    val longestRunMeters: Int?,
    val weightedPaceSecondsPerKilometre: Double?,
    val durationWeightedHeartRateBpm: Int?,
    val isComplete: Boolean,
    val currentSignal: LocalCurrentSignalReadModel? = null,
    val timeZone: String = ZoneId.systemDefault().id,
    val todayEpochDay: Long = 0,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val activeSessionsPerWeek: Int? = null,
)

data class LocalLifecycleReadModel(
    val eventType: String,
    val occurredAtEpochMillis: Long,
    val completedWorkoutCount: Int?,
    val completedActivityCount: Int?,
    val note: String?,
)

data class LocalRecordedResultReadModel(
    val source: String,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val feltHard: Boolean?,
    val pain: Boolean?,
)

data class LocalHistoryWorkoutReadModel(
    val workoutId: String,
    val status: String,
    val generatedScheduledEpochDay: Long,
    val currentScheduledEpochDay: Long,
    val generated: LocalPrescriptionReadModel,
    val current: LocalPrescriptionReadModel,
    val isRemoved: Boolean,
    val result: LocalRecordedResultReadModel?,
    val consequence: LocalConsequenceReadModel? = null,
)

data class LocalHistoryWeekReadModel(
    val weekId: String,
    val ordinal: Int,
    val startEpochDay: Long,
    val generated: LocalLoadReadModel,
    val current: LocalLoadReadModel,
    val actual: LocalLoadReadModel,
    val riskAssessment: String?,
    val isDownWeek: Boolean,
    val isTaperWeek: Boolean,
    val workouts: List<LocalHistoryWorkoutReadModel>,
    val extraActivities: List<LocalActivityReadModel> = emptyList(),
    val extraActivityContextIsComplete: Boolean = true,
)

data class LocalHistoryAdjustmentReadModel(
    val id: String,
    val triggerType: String,
    val createdAtEpochMillis: Long,
    val reversedAtEpochMillis: Long?,
    val reversalReason: String?,
    val reason: String?,
    val scheduledEpochDay: Long?,
    val workoutType: String?,
    val prescriptionKind: String?,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val removed: Boolean?,
)

data class LocalPlanHistoryReadModel(
    val planId: String,
    val goalId: String,
    val goalTitle: String,
    val state: LocalPlanState,
    val phase: LocalPlanPhase,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val completedAtEpochMillis: Long?,
    val archivedAtEpochMillis: Long?,
    val plannedRuns: Int,
    val completedRuns: Int,
    val actual: LocalLoadReadModel,
    val lifecycle: List<LocalLifecycleReadModel>,
    val adjustments: List<LocalHistoryAdjustmentReadModel> = emptyList(),
    val weeks: List<LocalHistoryWeekReadModel> = emptyList(),
    val missedRuns: Int = 0,
    val skippedRuns: Int = 0,
    val painFlags: Int = 0,
    val sessionsPerWeek: Int? = null,
    /** Scheduled workout slots with a recorded completion. */
    val plannedRunsRecorded: Int = 0,
    /** Accepted runs in this plan that are not linked to a scheduled slot. */
    val extraRuns: Int = 0,
)

data class LocalHistoryReadModel(
    val plans: List<LocalPlanHistoryReadModel>,
    val unlinkedActivities: List<LocalActivityReadModel>,
    val hasMorePlans: Boolean,
    val hasMoreActivities: Boolean,
    val nextPlanOffset: Int? = null,
    val nextActivityOffset: Int? = null,
    val timeZone: String = ZoneId.systemDefault().id,
    val todayEpochDay: Long = 0,
    val phaseReview: LocalPhaseReviewReadModel? = null,
)

data class LocalProfileReadModel(
    val timeZone: String,
    val routeDataMode: String,
    val heartRateDataMode: String,
    val availabilityDays: List<Int>,
    val heartRateSettingsSource: String,
    val sexForEstimates: String,
    val ageYears: Int?,
    val maxHeartRateBpm: Int?,
    val zone2FloorBpm: Int?,
    val zone3FloorBpm: Int?,
    val zone4FloorBpm: Int?,
    val zone5FloorBpm: Int?,
    val baselineDistanceMeters: Int?,
    val baselineDurationSeconds: Int?,
    val currentRunsPerWeek: Int?,
    val longestRecentRunMeters: Int?,
    val preferredLongRunDay: Int?,
)

data class LocalActivePlanReadModel(
    val planId: String,
    val goalId: String?,
    val goalTitle: String,
    val goalKind: String?,
    val startMode: String?,
    val raceDistanceMeters: Int?,
    val goalTargetEpochDay: Long?,
    val goalPriority: String?,
    val phase: LocalPlanPhase,
    val state: LocalPlanState,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val riskAssessment: String?,
    val latestLifecycleEvent: LocalLifecycleReadModel?,
    val sessionsPerWeek: Int? = null,
    val routineWeeklyTargetMeters: Int? = null,
    val routineWeeklyTargetDurationSeconds: Int? = null,
    val routineWeeklyChangeMeters: Int? = null,
    val routineWeeklyChangeDurationSeconds: Int? = null,
)

/** A health-blocked goal remains a visible local decision even before it has a plan graph. */
data class LocalPendingGoalReadModel(
    val goalId: String,
    val title: String,
    val goalKind: String?,
    val startMode: String?,
    val raceDistanceMeters: Int?,
    val targetEpochDay: Long?,
    val priority: String?,
)

data class LocalAboutReadModel(
    val mode: String = "Standalone",
    val dataLocation: String = "Stored on this device",
    val versionName: String?,
    val buildRevision: String?,
    val exportStatus: String = "Not yet available",
    val restoreStatus: String = "Not yet available",
)

data class LocalSettingsReadModel(
    val profile: LocalProfileReadModel?,
    val activePlan: LocalActivePlanReadModel?,
    val about: LocalAboutReadModel,
    val pendingGoal: LocalPendingGoalReadModel? = null,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val pendingHealthConnect: List<LocalHealthConnectPendingReadModel> = emptyList(),
    val notificationPreferences: LocalNotificationPreferences = LocalNotificationPreferences(),
)
