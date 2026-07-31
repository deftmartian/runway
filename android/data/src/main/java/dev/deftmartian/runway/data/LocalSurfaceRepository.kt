package dev.deftmartian.runway.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

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
enum class LocalPlanPhase { FOUNDATION, CALIBRATION, DISTANCE, OTHER }
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
)

data class LocalHealthNoticeReadModel(
    val level: String,
    val heading: String,
    val message: String,
)

data class LocalCurrentSignalReadModel(
    val risk: String,
    val reasons: List<String>,
    val source: String,
    val healthNotice: LocalHealthNoticeReadModel?,
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
    val recentInjury: Boolean,
    val currentPain: Boolean,
    val recurringPain: Boolean,
    val medicalRestriction: Boolean,
    val privateNotes: String?,
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
    val calibrationDurationSeconds: Int?,
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
)

/**
 * Consolidated rows required by the surface projections. A Room adapter should populate each slice
 * inside one read transaction with the limits supplied by [LocalSurfaceReadLimits].
 */
data class LocalPlanLedgerSlice(
    val goal: GoalEntity?,
    val plan: PlanEntity,
    val weeks: List<PlanWeekEntity>,
    val workouts: List<WorkoutEntity>,
    val feedback: List<WorkoutFeedbackEntity> = emptyList(),
    val lifecycle: List<PlanLifecycleEventEntity> = emptyList(),
    val workoutConsequences: List<WorkoutFeedbackConsequenceEntity> = emptyList(),
    val workoutConsequenceOptions: List<WorkoutFeedbackConsequenceOptionEntity> = emptyList(),
    val undoableWorkoutAdjustments: List<LocalWorkoutAdjustmentReadModel> = emptyList(),
    val summaryWarnings: List<PlanSummaryWarningEntity> = emptyList(),
    val historyAdjustments: List<HistoryAdjustmentRow> = emptyList(),
    /** Both generated and current versions; no surface infers a timed structure from totals. */
    val workoutBlocks: List<WorkoutBlockEntity> = emptyList(),
    val workoutSegments: List<WorkoutSegmentEntity> = emptyList(),
)

data class LocalActivityLedgerSlice(
    val activity: ActivityEntity,
    val feedback: ActivityFeedbackEntity? = null,
    val route: List<RouteSampleEntity> = emptyList(),
    val heartRate: List<HeartRateSampleEntity> = emptyList(),
    val consequence: ActivityConsequenceEntity? = null,
    val consequenceOptions: List<ActivityConsequenceOptionEntity> = emptyList(),
    val linkedWorkoutFeedback: WorkoutFeedbackEntity? = null,
    val linkedWorkoutConsequence: WorkoutFeedbackConsequenceEntity? = null,
    val linkedWorkoutConsequenceOptions: List<WorkoutFeedbackConsequenceOptionEntity> = emptyList(),
)

data class LocalPendingWorkoutFeedbackLedgerSlice(
    val feedback: WorkoutFeedbackEntity,
    val workout: WorkoutEntity,
    val consequence: WorkoutFeedbackConsequenceEntity,
    val consequenceOptions: List<WorkoutFeedbackConsequenceOptionEntity> = emptyList(),
)

data class LocalCalendarLedgerSlice(
    val fromEpochDay: Long,
    val throughEpochDay: Long,
    val timeZone: String,
    val pendingDecisionCount: Int,
    val pendingDecisionCountIsExact: Boolean = true,
    val hasMoreActivities: Boolean = false,
    val plans: List<LocalPlanLedgerSlice>,
    val activities: List<LocalActivityLedgerSlice>,
    val profileExists: Boolean,
    val todayEpochDay: Long = 0,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val nextWorkout: WorkoutEntity? = null,
)

data class LocalInboxLedgerSlice(
    val reviewCount: Int,
    val reviewCountIsExact: Boolean = true,
    val hasMore: Boolean,
    val activities: List<LocalActivityLedgerSlice>,
    val pendingWorkoutFeedback: List<LocalPendingWorkoutFeedbackLedgerSlice> = emptyList(),
    val linkCandidates: List<WorkoutEntity> = emptyList(),
    val linkCandidateBlocks: List<WorkoutBlockEntity> = emptyList(),
    val linkCandidateSegments: List<WorkoutSegmentEntity> = emptyList(),
    val pendingHealthConnect: List<LocalHealthConnectPendingReadModel> = emptyList(),
    val timeZone: String = ZoneId.systemDefault().id,
    val todayEpochDay: Long = 0,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val nextPage: LocalInboxPagingCursor? = null,
)

data class LocalStatsLedgerSlice(
    val plans: List<LocalPlanLedgerSlice>,
    val activities: List<LocalActivityLedgerSlice>,
    val profileExists: Boolean,
    val hasMorePlans: Boolean = false,
    val hasMoreActivities: Boolean = false,
    val timeZone: String = ZoneId.systemDefault().id,
    val todayEpochDay: Long = 0,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val profile: ProfileSettingsEntity? = null,
    val recordedAggregates: List<LocalRecordedAggregateLedgerRow> = emptyList(),
    val weekActualAggregates: List<LocalWeekActualAggregateLedgerRow> = emptyList(),
)

data class LocalHistoryLedgerSlice(
    val plans: List<LocalPlanLedgerSlice>,
    val activities: List<LocalActivityLedgerSlice>,
    val hasMorePlans: Boolean,
    val hasMoreActivities: Boolean,
    val timeZone: String = ZoneId.systemDefault().id,
    val todayEpochDay: Long = 0,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val nextPlanOffset: Int? = null,
    val nextActivityOffset: Int? = null,
    val weekActualAggregates: List<LocalWeekActualAggregateLedgerRow> = emptyList(),
)

data class LocalSettingsLedgerSlice(
    val profile: ProfileSettingsEntity?,
    val availabilityDays: List<ProfileAvailabilityDayEntity>,
    val activePlan: LocalPlanLedgerSlice?,
    val versionName: String?,
    val buildRevision: String?,
    val pendingGoal: GoalEntity? = null,
    val phaseReview: LocalPhaseReviewReadModel? = null,
    val pendingHealthConnect: List<LocalHealthConnectPendingReadModel> = emptyList(),
)

interface LocalSurfaceLedgerReader {
    suspend fun calendar(
        fromEpochDay: Long,
        throughEpochDay: Long,
        limits: LocalSurfaceReadLimits,
    ): LocalCalendarLedgerSlice

    suspend fun inbox(limits: LocalSurfaceReadLimits): LocalInboxLedgerSlice
    suspend fun inboxPage(
        limits: LocalSurfaceReadLimits,
        cursor: LocalInboxPagingCursor,
    ): LocalInboxLedgerSlice = inbox(limits)
    suspend fun stats(limits: LocalSurfaceReadLimits): LocalStatsLedgerSlice
    suspend fun history(
        limits: LocalSurfaceReadLimits,
        planOffset: Int = 0,
        activityOffset: Int = 0,
    ): LocalHistoryLedgerSlice
    suspend fun historyPlan(
        planId: String,
        limits: LocalSurfaceReadLimits,
    ): LocalHistoryLedgerSlice?
    suspend fun settings(limits: LocalSurfaceReadLimits): LocalSettingsLedgerSlice
    suspend fun activityEvidence(
        activityId: String,
        limits: LocalSurfaceReadLimits,
    ): LocalActivityLedgerSlice?
}

class LocalSurfaceRepository(
    private val ledger: LocalSurfaceLedgerReader,
    private val limits: LocalSurfaceReadLimits = LocalSurfaceReadLimits(),
) {
    suspend fun calendar(fromEpochDay: Long, throughEpochDay: Long): LocalCalendarReadModel {
        require(throughEpochDay >= fromEpochDay)
        require(throughEpochDay - fromEpochDay <= 42) { "Calendar reads are limited to 43 days." }
        return LocalSurfaceMappers.calendar(ledger.calendar(fromEpochDay, throughEpochDay, limits))
    }

    suspend fun inbox(cursor: LocalInboxPagingCursor): LocalInboxReadModel = LocalSurfaceMappers.inbox(
        ledger.inboxPage(limits, cursor),
    )
    suspend fun stats(): LocalStatsReadModel = LocalSurfaceMappers.stats(ledger.stats(limits))
    suspend fun history(
        planOffset: Int = 0,
        activityOffset: Int = 0,
    ): LocalHistoryReadModel = LocalSurfaceMappers.history(
        ledger.history(limits, planOffset, activityOffset),
    )
    suspend fun historyPlan(planId: String): LocalHistoryReadModel? {
        require(planId.isNotBlank())
        return ledger.historyPlan(planId, limits)?.let(LocalSurfaceMappers::history)
    }
    suspend fun settings(): LocalSettingsReadModel = LocalSurfaceMappers.settings(ledger.settings(limits))
    suspend fun activityEvidence(activityId: String): LocalActivityReadModel? {
        require(activityId.isNotBlank())
        return ledger.activityEvidence(activityId, limits)?.let(LocalSurfaceMappers::activityEvidence)
    }
}

object LocalSurfaceMappers {
    fun activityEvidence(row: LocalActivityLedgerSlice): LocalActivityReadModel = activity(row)

    fun calendar(slice: LocalCalendarLedgerSlice): LocalCalendarReadModel {
        require(slice.throughEpochDay >= slice.fromEpochDay)
        val accepted = slice.activities.filter { it.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        val activityByWorkout = accepted
            .filter { it.activity.linkedWorkoutId != null }
            .associateBy { requireNotNull(it.activity.linkedWorkoutId) }
        val activePlans = slice.plans.filter { it.plan.state == "active" }
        val feedbackByWorkout = activePlans.flatMap { it.feedback }.associateBy(WorkoutFeedbackEntity::workoutId)
        val workoutConsequences = activePlans.flatMap { it.workoutConsequences }.associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
        val workoutOptions = activePlans.flatMap { it.workoutConsequenceOptions }.groupBy(WorkoutFeedbackConsequenceOptionEntity::feedbackId)
        val workouts = activePlans.flatMap { plan ->
            val weekOrdinals = plan.weeks.associate { it.weekId to it.ordinal }
            plan.workouts
                .filter { it.currentStatus != WORKOUT_STATE_TOMBSTONED }
                .filter { it.currentScheduledEpochDay in slice.fromEpochDay..slice.throughEpochDay }
                .map {
                    workout(
                        entity = it,
                        weekOrdinal = weekOrdinals[it.weekId] ?: 0,
                        actual = activityByWorkout[it.workoutId],
                        blocks = plan.workoutBlocks,
                        segments = plan.workoutSegments,
                        consequence = feedbackByWorkout[it.workoutId]?.let { feedback ->
                            workoutConsequences[feedback.feedbackId]?.let { stored ->
                                workoutConsequence(feedback, stored, workoutOptions[feedback.feedbackId].orEmpty())
                            }
                        },
                        adjustment = plan.undoableWorkoutAdjustments.firstOrNull { adjustment ->
                            adjustment.workoutId == it.workoutId
                        },
                    )
                }
        }
        val visibleWorkoutIds = workouts.mapTo(mutableSetOf(), LocalWorkoutReadModel::workoutId)
        val feedback = activePlans
            .flatMap { it.feedback }
            .filter { it.workoutId in visibleWorkoutIds }
            .map {
                LocalWorkoutFeedbackReadModel(
                    feedbackId = it.feedbackId,
                    workoutId = it.workoutId,
                    completionState = it.completionState,
                    completedDistanceMeters = it.completedDistanceMeters,
                    completedDurationSeconds = it.completedDurationSeconds,
                    feltHard = it.feltHard,
                    pain = it.pain,
                    recordedAtEpochMillis = it.recordedAtEpochMillis,
                    sourceActivityId = it.sourceActivityId,
                )
            }
        val unlinkedByDay = accepted
            .filter { it.activity.linkedWorkoutId == null }
            .groupBy { localEpochDay(it.activity.occurredAtEpochMillis, slice.timeZone) }
        val workoutsByDay = workouts.groupBy(LocalWorkoutReadModel::scheduledEpochDay)
        val days = (slice.fromEpochDay..slice.throughEpochDay).mapNotNull { epochDay ->
            val dayWorkouts = workoutsByDay[epochDay].orEmpty()
            val dayActivities = unlinkedByDay[epochDay].orEmpty().map(::activity)
            if (dayWorkouts.isEmpty() && dayActivities.isEmpty()) null
            else LocalCalendarDayReadModel(epochDay, dayWorkouts, dayActivities)
        }
        val nextWorkout = slice.nextWorkout?.let { entity ->
            val plan = activePlans.firstOrNull { it.plan.planId == entity.planId }
            val weekOrdinal = plan?.weeks
                ?.firstOrNull { it.weekId == entity.weekId }
                ?.ordinal
                ?: 0
            workout(
                entity = entity,
                weekOrdinal = weekOrdinal,
                actual = activityByWorkout[entity.workoutId],
                blocks = plan?.workoutBlocks.orEmpty(),
                segments = plan?.workoutSegments.orEmpty(),
                consequence = feedbackByWorkout[entity.workoutId]?.let { feedback ->
                    workoutConsequences[feedback.feedbackId]?.let { stored ->
                        workoutConsequence(feedback, stored, workoutOptions[feedback.feedbackId].orEmpty())
                    }
                },
                adjustment = plan?.undoableWorkoutAdjustments?.firstOrNull { adjustment ->
                    adjustment.workoutId == entity.workoutId
                },
            )
        }
        return LocalCalendarReadModel(
            fromEpochDay = slice.fromEpochDay,
            throughEpochDay = slice.throughEpochDay,
            activePlanId = activePlans.singleOrNull()?.plan?.planId,
            profileExists = slice.profileExists,
            pendingDecisionCount = slice.pendingDecisionCount,
            pendingDecisionCountIsExact = slice.pendingDecisionCountIsExact,
            hasMoreActivities = slice.hasMoreActivities,
            days = days,
            feedback = feedback,
            timeZone = slice.timeZone,
            todayEpochDay = slice.todayEpochDay,
            phaseReview = slice.phaseReview,
            nextWorkout = nextWorkout,
        )
    }

    fun inbox(slice: LocalInboxLedgerSlice): LocalInboxReadModel = LocalInboxReadModel(
        reviewCount = slice.reviewCount,
        reviewCountIsExact = slice.reviewCountIsExact,
        hasMore = slice.hasMore,
        activities = slice.activities
            .filter { row ->
                val reviewActivity =
                    row.activity.reviewState == "review" &&
                        slice.pendingHealthConnect.none { pending ->
                            pending.state == "possible_duplicate" &&
                                pending.current?.activityId == row.activity.activityId
                        }
                val actionableAcceptedExtra =
                    row.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED &&
                        row.activity.linkedWorkoutId == null &&
                        row.activity.extraPlanImpactConfirmed &&
                        row.consequence?.let { consequence ->
                            consequence.planChangeAvailable &&
                                consequence.appliedDecision == null &&
                                consequence.resolvedAtEpochMillis == null
                        } == true
                val actionableLinkedActivity =
                    row.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED &&
                        row.activity.linkedWorkoutId != null &&
                        row.linkedWorkoutConsequence?.let { consequence ->
                            consequence.planChangeAvailable && consequence.appliedDecision == null
                        } == true
                reviewActivity || actionableAcceptedExtra || actionableLinkedActivity
            }
            .sortedByDescending { it.activity.occurredAtEpochMillis }
            .map(::activity),
        pendingWorkoutFeedback = slice.pendingWorkoutFeedback.map { row ->
            LocalPendingWorkoutFeedbackReadModel(
                feedbackId = row.feedback.feedbackId,
                workoutId = row.feedback.workoutId,
                scheduledEpochDay = row.workout.currentScheduledEpochDay,
                workoutPurpose = row.workout.currentPurpose,
                recordedAtEpochMillis = row.feedback.recordedAtEpochMillis,
                completedDistanceMeters = row.feedback.completedDistanceMeters,
                completedDurationSeconds = row.feedback.completedDurationSeconds,
                feltHard = row.feedback.feltHard,
                pain = row.feedback.pain,
                completionState = row.feedback.completionState,
                consequence = workoutConsequence(
                    row.feedback,
                    row.consequence,
                    row.consequenceOptions,
                ),
            )
        },
        linkCandidates = slice.linkCandidates.map {
            LocalWorkoutLinkCandidateReadModel(
                it.planId,
                it.workoutId,
                it.currentScheduledEpochDay,
                prescription(it, generated = false, slice.linkCandidateBlocks, slice.linkCandidateSegments),
            )
        },
        pendingHealthConnect = slice.pendingHealthConnect,
        timeZone = slice.timeZone,
        todayEpochDay = slice.todayEpochDay,
        phaseReview = slice.phaseReview,
        nextPage = slice.nextPage,
    )

    fun stats(slice: LocalStatsLedgerSlice): LocalStatsReadModel {
        val accepted = slice.activities.filter { it.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        val acceptedByWorkout = accepted
            .filter { it.activity.linkedWorkoutId != null }
            .groupBy { requireNotNull(it.activity.linkedWorkoutId) }
        val zone = runCatching { ZoneId.of(slice.timeZone) }.getOrDefault(ZoneId.systemDefault())
        val unlinkedAssignments = assignUnlinkedToPlanWeeks(accepted, slice.plans, zone)
        val unlinkedByPlanWeek = unlinkedAssignments
            .groupBy(
                keySelector = { it.plan.plan.planId to it.week.weekId },
                valueTransform = AcceptedWeekAssignment::activity,
            )
        val exactWeekActuals = slice.weekActualAggregates.associateBy {
            it.planId to it.weekId
        }
        val weeks = slice.plans.flatMap { plan ->
            val feedbackByWorkout = plan.feedback.associateBy(WorkoutFeedbackEntity::workoutId)
            plan.weeks.map { week ->
                val workouts = plan.workouts.filter {
                    it.weekId == week.weekId && it.currentStatus != WORKOUT_STATE_TOMBSTONED
                }
                val acceptedRows = workouts.flatMap { workout ->
                    acceptedByWorkout[workout.workoutId].orEmpty()
                } + unlinkedByPlanWeek[plan.plan.planId to week.weekId].orEmpty()
                val unlinkedRows = unlinkedByPlanWeek[plan.plan.planId to week.weekId].orEmpty()
                val directFeedback = workouts.mapNotNull { feedbackByWorkout[it.workoutId] }
                    .filter { it.sourceActivityId == null }
                val actualLoads =
                    acceptedRows.map { it.activity.distanceMeters to it.activity.durationSeconds } +
                        directFeedback.map { it.completedDistanceMeters to it.completedDurationSeconds }
                val paceLoads = actualLoads.filter { (distance, duration) ->
                    (distance ?: 0) > 0 && (duration ?: 0) > 0
                }
                val heartRateRows = acceptedRows.map(LocalActivityLedgerSlice::activity)
                    .filter { it.averageHeartRateBpm != null && (it.durationSeconds ?: 0) > 0 }
                val acceptedFeedback = acceptedRows.mapNotNull(LocalActivityLedgerSlice::feedback)
                val heartRateDurationSeconds = heartRateRows.sumOf { requireNotNull(it.durationSeconds) }
                val exactActual = exactWeekActuals[plan.plan.planId to week.weekId]
                val workoutCompleted: (WorkoutEntity) -> Boolean = { workout ->
                    workout.currentStatus in setOf("done", "shortened", "completed", "overrun") ||
                        acceptedByWorkout.containsKey(workout.workoutId) ||
                        feedbackByWorkout[workout.workoutId]?.completionState in
                        setOf("done", "shortened", "completed", "overrun")
                }
                LocalWeekStatsReadModel(
                    planId = plan.plan.planId,
                    planState = planState(plan.plan.state),
                    phase = planPhase(plan.plan.phaseType),
                    weekOrdinal = week.ordinal,
                    startEpochDay = week.startEpochDay,
                    generated = loads(workouts.map { it.generatedDistanceMeters to it.generatedDurationSeconds }),
                    current = loads(workouts.map { it.currentDistanceMeters to it.currentDurationSeconds }),
                    actual = exactActual?.let {
                        LocalLoadReadModel(
                            distanceMeters = it.distanceMeters.toSurfaceInt("week distance"),
                            durationSeconds = it.durationSeconds.toSurfaceInt("week duration"),
                        )
                    } ?: loads(actualLoads),
                    plannedRuns = workouts.count { it.currentWorkoutType != "rest" },
                    completedRuns = workouts.count {
                        it.currentWorkoutType != "rest" && workoutCompleted(it)
                    } + (
                        exactActual?.unlinkedRuns?.toSurfaceInt("unlinked week run count")
                            ?: unlinkedRows.size
                        ),
                    missedRuns = workouts.count {
                        it.currentWorkoutType != "rest" &&
                            !workoutCompleted(it) &&
                            (
                                it.currentStatus == "missed" ||
                                    (
                                        it.currentStatus == "planned" &&
                                            it.currentScheduledEpochDay < slice.todayEpochDay
                                        )
                                )
                    },
                    painFlags = exactActual?.painFlags?.toSurfaceInt("week pain flag count")
                        ?: (
                            acceptedFeedback.count(ActivityFeedbackEntity::pain) +
                                directFeedback.count(WorkoutFeedbackEntity::pain)
                            ),
                    hardFlags = exactActual?.hardFlags?.toSurfaceInt("week hard flag count")
                        ?: (
                            acceptedFeedback.count(ActivityFeedbackEntity::feltHard) +
                                directFeedback.count(WorkoutFeedbackEntity::feltHard)
                            ),
                    weightedPaceSecondsPerKilometre = exactActual?.let {
                        if (it.pairedDistanceMeters > 0 && it.pairedDurationSeconds > 0) {
                            it.pairedDurationSeconds.toDouble() * 1_000 /
                                it.pairedDistanceMeters
                        } else {
                            null
                        }
                    } ?: paceLoads
                        .takeIf { it.isNotEmpty() }
                        ?.let { loads ->
                            loads.sumOf { requireNotNull(it.second) }.toDouble() * 1_000 /
                                loads.sumOf { requireNotNull(it.first) }
                        },
                    durationWeightedHeartRateBpm = exactActual?.let {
                        if (it.heartRateDurationSeconds > 0) {
                            (
                                it.heartRateBeatsSeconds.toDouble() /
                                    it.heartRateDurationSeconds
                                ).roundToInt()
                        } else {
                            null
                        }
                    } ?: heartRateRows
                        .takeIf { it.isNotEmpty() && heartRateDurationSeconds > 0 }
                        ?.let { rows ->
                            (
                                rows.sumOf {
                                    requireNotNull(it.averageHeartRateBpm).toLong() *
                                        requireNotNull(it.durationSeconds)
                                }.toDouble() / heartRateDurationSeconds
                            ).roundToInt()
                        },
                    skippedRuns = workouts.count {
                        it.currentWorkoutType != "rest" &&
                            !workoutCompleted(it) &&
                            it.currentStatus == "skipped"
                    },
                )
            }
        }
        val planByWorkout = slice.plans.flatMap { plan ->
            plan.workouts.map { it.workoutId to planState(plan.plan.state) }
        }.toMap()
        // A direct feedback result has no ActivityEntity, while linked activity feedback points
        // back to its ActivityEntity. Combine the former here and exclude the latter so Stats
        // reflects every completed fact exactly once.
        val acceptedEvidence = accepted.map { row ->
            RecordedStatsEvidence(
                provenance = row.activity.linkedWorkoutId
                    ?.let { workoutId -> planByWorkout[workoutId]?.let(::provenance) }
                    ?: if (row.activity.linkedWorkoutId == null) {
                        LocalPlanProvenance.UNLINKED
                    } else {
                        LocalPlanProvenance.OTHER
                    },
                distanceMeters = row.activity.distanceMeters,
                durationSeconds = row.activity.durationSeconds,
            )
        }
        val directFeedbackEvidence = slice.plans.flatMap { plan ->
            plan.feedback
                .asSequence()
                .filter { it.sourceActivityId == null && it.completionState in COMPLETED_FEEDBACK_STATES }
                .map { feedback ->
                    RecordedStatsEvidence(
                        provenance = provenance(planState(plan.plan.state)),
                        distanceMeters = feedback.completedDistanceMeters,
                        durationSeconds = feedback.completedDurationSeconds,
                    )
                }
                .toList()
        }
        val recordedEvidence = acceptedEvidence + directFeedbackEvidence
        val totals = if (slice.recordedAggregates.isNotEmpty()) {
            slice.recordedAggregates.map { row ->
                LocalRecordedTotalsReadModel(
                    provenance = row.provenance,
                    runs = row.runs.toSurfaceInt("recorded run count"),
                    distanceMeters = row.distanceMeters.toSurfaceInt("recorded distance"),
                    durationSeconds = row.durationSeconds.toSurfaceInt("recorded duration"),
                )
            }
        } else {
            recordedEvidence
                .groupBy(RecordedStatsEvidence::provenance)
                .map { (origin, rows) ->
                    LocalRecordedTotalsReadModel(
                        provenance = origin,
                        runs = rows.size,
                        distanceMeters = rows.sumOf { it.distanceMeters ?: 0 },
                        durationSeconds = rows.sumOf { it.durationSeconds ?: 0 },
                    )
                }
        }.sortedBy { it.provenance.ordinal }
        val paired = recordedEvidence
            .filter { (it.distanceMeters ?: 0) > 0 && (it.durationSeconds ?: 0) > 0 }
        val heartRate = accepted.map { it.activity }
            .filter { it.averageHeartRateBpm != null && (it.durationSeconds ?: 0) > 0 }
        val exactRuns = slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::runs)
        val exactDistance = slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::distanceMeters)
        val exactDuration = slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::durationSeconds)
        val exactPairedDistance =
            slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::pairedDistanceMeters)
        val exactPairedDuration =
            slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::pairedDurationSeconds)
        val exactHeartRateDuration =
            slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::heartRateDurationSeconds)
        val exactHeartRateBeats =
            slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::heartRateBeatsSeconds)
        return LocalStatsReadModel(
            weeks = weeks.sortedWith(compareBy(LocalWeekStatsReadModel::startEpochDay, LocalWeekStatsReadModel::weekOrdinal)),
            profileExists = slice.profileExists,
            recordedTotals = totals,
            totalRuns = if (slice.recordedAggregates.isNotEmpty()) {
                exactRuns.toSurfaceInt("total run count")
            } else {
                recordedEvidence.size
            },
            totalDistanceMeters = if (slice.recordedAggregates.isNotEmpty()) {
                exactDistance.toSurfaceInt("total distance")
            } else {
                recordedEvidence.sumOf { it.distanceMeters ?: 0 }
            },
            totalDurationSeconds = if (slice.recordedAggregates.isNotEmpty()) {
                exactDuration.toSurfaceInt("total duration")
            } else {
                recordedEvidence.sumOf { it.durationSeconds ?: 0 }
            },
            longestRunMeters = if (slice.recordedAggregates.isNotEmpty()) {
                slice.recordedAggregates.mapNotNull(LocalRecordedAggregateLedgerRow::longestRunMeters)
                    .maxOrNull()
            } else {
                recordedEvidence.mapNotNull(RecordedStatsEvidence::distanceMeters).maxOrNull()
            },
            weightedPaceSecondsPerKilometre = if (slice.recordedAggregates.isNotEmpty()) {
                if (exactPairedDistance > 0 && exactPairedDuration > 0) {
                    exactPairedDuration.toDouble() * 1_000 / exactPairedDistance
                } else {
                    null
                }
            } else {
                paired.takeIf { it.isNotEmpty() }?.let { rows ->
                    rows.sumOf { requireNotNull(it.durationSeconds) }.toDouble() * 1_000 /
                        rows.sumOf { requireNotNull(it.distanceMeters) }
                }
            },
            durationWeightedHeartRateBpm = if (slice.recordedAggregates.isNotEmpty()) {
                if (exactHeartRateDuration > 0) {
                    (exactHeartRateBeats.toDouble() / exactHeartRateDuration).roundToInt()
                } else {
                    null
                }
            } else {
                heartRate.takeIf { it.isNotEmpty() }?.let { rows ->
                    val seconds = rows.sumOf { requireNotNull(it.durationSeconds) }
                    (
                        rows.sumOf {
                            requireNotNull(it.averageHeartRateBpm).toLong() *
                                requireNotNull(it.durationSeconds)
                        }.toDouble() / seconds
                        ).roundToInt()
                }
            },
            isComplete = slice.recordedAggregates.isNotEmpty() ||
                (!slice.hasMorePlans && !slice.hasMoreActivities),
            currentSignal = slice.plans
                .firstOrNull { it.plan.state == "active" }
                ?.let {
                    currentSignal(
                        plan = it,
                        accepted = accepted,
                        profile = slice.profile,
                        zone = zone,
                        todayEpochDay = slice.todayEpochDay,
                    )
                },
            timeZone = slice.timeZone,
            todayEpochDay = slice.todayEpochDay,
            phaseReview = slice.phaseReview,
        )
    }

    private data class RecordedStatsEvidence(
        val provenance: LocalPlanProvenance,
        val distanceMeters: Int?,
        val durationSeconds: Int?,
    )

    fun history(slice: LocalHistoryLedgerSlice): LocalHistoryReadModel {
        val accepted = slice.activities.filter { it.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        val zone = runCatching { ZoneId.of(slice.timeZone) }.getOrDefault(ZoneId.systemDefault())
        val unlinkedAssignments = assignUnlinkedToPlanWeeks(accepted, slice.plans, zone)
        val unlinkedByPlanWeek = unlinkedAssignments.groupBy(
            keySelector = { it.plan.plan.planId to it.week.weekId },
            valueTransform = AcceptedWeekAssignment::activity,
        )
        val assignedUnlinkedActivityIds =
            unlinkedAssignments.mapTo(mutableSetOf()) { it.activity.activity.activityId }
        val exactWeekActuals = slice.weekActualAggregates.associateBy {
            it.planId to it.weekId
        }
        val plans = slice.plans.map { plan ->
            val workoutIds = plan.workouts.mapTo(mutableSetOf(), WorkoutEntity::workoutId)
            val linked = accepted.filter { it.activity.linkedWorkoutId in workoutIds }
            val direct = plan.feedback.filter { it.sourceActivityId == null }
            val feedbackByWorkout = plan.feedback.associateBy(WorkoutFeedbackEntity::workoutId)
            val consequencesByFeedback = plan.workoutConsequences.associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
            val optionsByFeedback = plan.workoutConsequenceOptions.groupBy(WorkoutFeedbackConsequenceOptionEntity::feedbackId)
            val linkedByWorkout = linked
                .groupBy { requireNotNull(it.activity.linkedWorkoutId) }
            val historyWeeks = plan.weeks.map { week ->
                val weekWorkouts = plan.workouts
                    .filter { it.weekId == week.weekId }
                    .sortedBy { it.position }
                val currentWeekWorkouts = weekWorkouts.filter {
                    it.currentStatus != WORKOUT_STATE_TOMBSTONED
                }
                val extraActivities =
                    unlinkedByPlanWeek[plan.plan.planId to week.weekId].orEmpty()
                val exactActual = exactWeekActuals[plan.plan.planId to week.weekId]
                val workoutRows = weekWorkouts.map { workout ->
                    val linkedResult = linkedByWorkout[workout.workoutId]?.singleOrNull()
                    val directResult = feedbackByWorkout[workout.workoutId]
                        ?.takeIf { it.sourceActivityId == null }
                    LocalHistoryWorkoutReadModel(
                        workoutId = workout.workoutId,
                        status = workout.currentStatus,
                        generatedScheduledEpochDay = workout.generatedScheduledEpochDay,
                        currentScheduledEpochDay = workout.currentScheduledEpochDay,
                        generated = prescription(
                            workout,
                            generated = true,
                            blocks = plan.workoutBlocks,
                            segments = plan.workoutSegments,
                        ),
                        current = prescription(
                            workout,
                            generated = false,
                            blocks = plan.workoutBlocks,
                            segments = plan.workoutSegments,
                        ),
                        isRemoved = workout.currentStatus == WORKOUT_STATE_TOMBSTONED,
                        result = linkedResult?.let {
                            LocalRecordedResultReadModel(
                                source = it.activity.source,
                                distanceMeters = it.activity.distanceMeters,
                                durationSeconds = it.activity.durationSeconds,
                                feltHard = it.feedback?.feltHard,
                                pain = it.feedback?.pain,
                            )
                        } ?: directResult?.let {
                            LocalRecordedResultReadModel(
                                source = "manual_feedback",
                                distanceMeters = it.completedDistanceMeters,
                                durationSeconds = it.completedDurationSeconds,
                                feltHard = it.feltHard,
                                pain = it.pain,
                            )
                        },
                        consequence = feedbackByWorkout[workout.workoutId]?.let { feedback ->
                            consequencesByFeedback[feedback.feedbackId]?.let { stored ->
                                workoutConsequence(feedback, stored, optionsByFeedback[feedback.feedbackId].orEmpty())
                            }
                        },
                    )
                }
                LocalHistoryWeekReadModel(
                    weekId = week.weekId,
                    ordinal = week.ordinal,
                    startEpochDay = week.startEpochDay,
                    generated = LocalLoadReadModel(
                        week.generatedLoadMeters,
                        week.generatedLoadDurationSeconds,
                    ),
                    current = loads(
                        currentWeekWorkouts.map {
                            it.currentDistanceMeters to it.currentDurationSeconds
                        },
                    ),
                    actual = exactActual?.let {
                        LocalLoadReadModel(
                            it.distanceMeters.toSurfaceInt("history week distance"),
                            it.durationSeconds.toSurfaceInt("history week duration"),
                        )
                    } ?: loads(
                        workoutRows.mapNotNull { workout ->
                            workout.result?.let {
                                it.distanceMeters to it.durationSeconds
                            }
                        } + extraActivities.map {
                            it.activity.distanceMeters to it.activity.durationSeconds
                        },
                    ),
                    riskAssessment = week.riskAssessment,
                    isDownWeek = week.isDownWeek,
                    isTaperWeek = week.isTaperWeek,
                    workouts = workoutRows,
                    extraActivities = extraActivities.map(::activity),
                    extraActivityContextIsComplete =
                        exactActual == null ||
                            exactActual.unlinkedRuns == extraActivities.size.toLong(),
                )
            }
            val planExtraActivities = unlinkedAssignments
                .filter { it.plan.plan.planId == plan.plan.planId }
                .map(AcceptedWeekAssignment::activity)
            val exactPlanActuals = plan.weeks.mapNotNull {
                exactWeekActuals[plan.plan.planId to it.weekId]
            }
            val workoutCompleted: (WorkoutEntity) -> Boolean = { workout ->
                workout.currentStatus in setOf("done", "shortened", "completed", "overrun") ||
                    linked.any { row -> row.activity.linkedWorkoutId == workout.workoutId } ||
                    direct.any { feedback ->
                        feedback.workoutId == workout.workoutId &&
                            feedback.completionState in
                            setOf("done", "shortened", "completed", "overrun")
                    }
            }
            val historyCutoffEpochDay = if (plan.plan.state == "active") {
                slice.todayEpochDay
            } else {
                listOfNotNull(
                    plan.plan.completedAtEpochMillis,
                    plan.plan.archivedAtEpochMillis,
                ).minOrNull()?.let { localEpochDay(it, slice.timeZone) } ?: slice.todayEpochDay
            }
            LocalPlanHistoryReadModel(
                planId = plan.plan.planId,
                goalId = plan.goal?.goalId ?: plan.plan.goalId,
                goalTitle = plan.goal?.title ?: "Training goal",
                state = planState(plan.plan.state),
                phase = planPhase(plan.plan.phaseType),
                startEpochDay = plan.plan.startEpochDay,
                endEpochDay = plan.plan.endEpochDay,
                completedAtEpochMillis = plan.plan.completedAtEpochMillis,
                archivedAtEpochMillis = plan.plan.archivedAtEpochMillis,
                plannedRuns = plan.workouts.count {
                    it.currentWorkoutType != "rest" && it.currentStatus != WORKOUT_STATE_TOMBSTONED
                },
                completedRuns = plan.workouts.count {
                    it.currentWorkoutType != "rest" && workoutCompleted(it)
                } + (
                    exactPlanActuals
                        .sumOf(LocalWeekActualAggregateLedgerRow::unlinkedRuns)
                        .takeIf { exactPlanActuals.isNotEmpty() }
                        ?.toSurfaceInt("history plan unlinked run count")
                        ?: planExtraActivities.size
                    ),
                actual = if (exactPlanActuals.isNotEmpty()) {
                    LocalLoadReadModel(
                        exactPlanActuals
                            .sumOf(LocalWeekActualAggregateLedgerRow::distanceMeters)
                            .toSurfaceInt("history plan distance"),
                        exactPlanActuals
                            .sumOf(LocalWeekActualAggregateLedgerRow::durationSeconds)
                            .toSurfaceInt("history plan duration"),
                    )
                } else {
                    loads(
                        linked.map { it.activity.distanceMeters to it.activity.durationSeconds } +
                            direct.map {
                                it.completedDistanceMeters to it.completedDurationSeconds
                            } +
                            planExtraActivities.map {
                                it.activity.distanceMeters to it.activity.durationSeconds
                            },
                    )
                },
                lifecycle = plan.lifecycle.sortedByDescending { it.occurredAtEpochMillis }.map(::lifecycle),
                adjustments = plan.historyAdjustments
                    .map { row ->
                        LocalHistoryAdjustmentReadModel(
                            id = row.effectId ?: row.adjustmentId,
                            triggerType = row.adjustmentType,
                            createdAtEpochMillis = row.createdAtEpochMillis,
                            reversedAtEpochMillis = row.reversedAtEpochMillis,
                            reversalReason = row.reversalReason,
                            reason = row.newReason ?: when (row.triggerKind) {
                                LocalDecisionSourceKind.WorkoutFeedback.name ->
                                    "Recorded after workout feedback."
                                LocalDecisionSourceKind.Activity.name ->
                                    "Recorded after an accepted activity."
                                else -> null
                            },
                            scheduledEpochDay = row.newScheduledEpochDay,
                            workoutType = row.newWorkoutType,
                            prescriptionKind = row.newPrescriptionKind,
                            distanceMeters = row.newDistanceMeters,
                            durationSeconds = row.newDurationSeconds,
                            removed = row.effectId?.let {
                                row.newTombstonedAtEpochMillis != null ||
                                    row.newStatus == WORKOUT_STATE_TOMBSTONED
                            },
                        )
                    }
                    .distinctBy(LocalHistoryAdjustmentReadModel::id)
                    .sortedBy(LocalHistoryAdjustmentReadModel::createdAtEpochMillis),
                weeks = historyWeeks,
                missedRuns = plan.workouts.count {
                    it.currentWorkoutType != "rest" &&
                        it.currentStatus != WORKOUT_STATE_TOMBSTONED &&
                        !workoutCompleted(it) &&
                        (
                            it.currentStatus == "missed" ||
                                (
                                    it.currentStatus == "planned" &&
                                        it.currentScheduledEpochDay < historyCutoffEpochDay
                                    )
                            )
                },
                skippedRuns = plan.workouts.count {
                    it.currentWorkoutType != "rest" &&
                        it.currentStatus != WORKOUT_STATE_TOMBSTONED &&
                        !workoutCompleted(it) &&
                        it.currentStatus == "skipped"
                },
                painFlags = if (exactPlanActuals.isNotEmpty()) {
                    exactPlanActuals
                        .sumOf(LocalWeekActualAggregateLedgerRow::painFlags)
                        .toSurfaceInt("history plan pain flag count")
                } else {
                    linked.mapNotNull(LocalActivityLedgerSlice::feedback)
                        .count(ActivityFeedbackEntity::pain) +
                        direct.count(WorkoutFeedbackEntity::pain) +
                        planExtraActivities.mapNotNull(LocalActivityLedgerSlice::feedback)
                            .count(ActivityFeedbackEntity::pain)
                },
            )
        }
        return LocalHistoryReadModel(
            plans = plans.sortedByDescending {
                it.completedAtEpochMillis ?: it.archivedAtEpochMillis ?: it.startEpochDay
            },
            unlinkedActivities = accepted
                .filter {
                    it.activity.linkedWorkoutId == null &&
                        it.activity.activityId !in assignedUnlinkedActivityIds
                }
                .map(::activity),
            hasMorePlans = slice.hasMorePlans,
            hasMoreActivities = slice.hasMoreActivities,
            nextPlanOffset = slice.nextPlanOffset,
            nextActivityOffset = slice.nextActivityOffset,
            timeZone = slice.timeZone,
            todayEpochDay = slice.todayEpochDay,
            phaseReview = slice.phaseReview,
        )
    }

    fun settings(slice: LocalSettingsLedgerSlice): LocalSettingsReadModel {
        val profile = slice.profile?.let {
            LocalProfileReadModel(
                timeZone = it.timeZone,
                routeDataMode = it.routeDataMode,
                heartRateDataMode = it.heartRateDataMode,
                availabilityDays = slice.availabilityDays.map(ProfileAvailabilityDayEntity::dayOfWeek).sorted(),
                recentInjury = it.recentInjury,
                currentPain = it.currentPain,
                recurringPain = it.recurringPain,
                medicalRestriction = it.medicalRestriction,
                privateNotes = it.privateNotes,
                heartRateSettingsSource = it.heartRateSettingsSource,
                sexForEstimates = it.sexForEstimates,
                ageYears = it.ageYears,
                maxHeartRateBpm = it.maxHeartRateBpm,
                zone2FloorBpm = it.zone2FloorBpm,
                zone3FloorBpm = it.zone3FloorBpm,
                zone4FloorBpm = it.zone4FloorBpm,
                zone5FloorBpm = it.zone5FloorBpm,
                baselineDistanceMeters = it.baselineDistanceMeters,
                baselineDurationSeconds = it.baselineDurationSeconds,
                currentRunsPerWeek = it.currentRunsPerWeek,
                longestRecentRunMeters = it.longestRecentRunMeters,
                calibrationDurationSeconds = it.calibrationDurationSeconds,
                preferredLongRunDay = it.preferredLongRunDay,
            )
        }
        val activePlan = slice.activePlan?.let {
            LocalActivePlanReadModel(
                planId = it.plan.planId,
                goalId = it.goal?.goalId,
                goalTitle = it.goal?.title ?: "Training goal",
                goalKind = it.goal?.kind,
                startMode = it.goal?.startMode,
                raceDistanceMeters = it.goal?.raceDistanceMeters,
                goalTargetEpochDay = it.goal?.targetDateEpochDay,
                goalPriority = it.goal?.priority,
                phase = planPhase(it.plan.phaseType),
                state = planState(it.plan.state),
                startEpochDay = it.plan.startEpochDay,
                endEpochDay = it.plan.endEpochDay,
                riskAssessment = it.plan.riskAssessment,
                latestLifecycleEvent = it.lifecycle.maxByOrNull(PlanLifecycleEventEntity::occurredAtEpochMillis)?.let(::lifecycle),
            )
        }
        val pendingGoal = slice.pendingGoal?.let {
            LocalPendingGoalReadModel(
                goalId = it.goalId,
                title = it.title,
                goalKind = it.kind,
                startMode = it.startMode,
                raceDistanceMeters = it.raceDistanceMeters,
                targetEpochDay = it.targetDateEpochDay,
                priority = it.priority,
            )
        }
        return LocalSettingsReadModel(
            profile = profile,
            activePlan = activePlan,
            about = LocalAboutReadModel(
                versionName = slice.versionName,
                buildRevision = slice.buildRevision,
            ),
            pendingGoal = pendingGoal,
            phaseReview = slice.phaseReview,
            pendingHealthConnect = slice.pendingHealthConnect,
        )
    }

    private fun workout(
        entity: WorkoutEntity,
        weekOrdinal: Int,
        actual: LocalActivityLedgerSlice?,
        blocks: List<WorkoutBlockEntity> = emptyList(),
        segments: List<WorkoutSegmentEntity> = emptyList(),
        consequence: LocalConsequenceReadModel? = null,
        adjustment: LocalWorkoutAdjustmentReadModel? = null,
    ): LocalWorkoutReadModel {
        val generated = prescription(entity, generated = true, blocks = blocks, segments = segments)
        val current = prescription(entity, generated = false, blocks = blocks, segments = segments)
        return LocalWorkoutReadModel(
            workoutId = entity.workoutId,
            planId = entity.planId,
            weekOrdinal = weekOrdinal,
            scheduledEpochDay = entity.currentScheduledEpochDay,
            status = entity.currentStatus,
            isRest = entity.currentWorkoutType == "rest",
            isEdited = entity.generatedScheduledEpochDay != entity.currentScheduledEpochDay ||
                entity.generatedWorkoutType != entity.currentWorkoutType ||
                entity.generatedPrescriptionKind != entity.currentPrescriptionKind ||
                entity.generatedDistanceMeters != entity.currentDistanceMeters ||
                entity.generatedDurationSeconds != entity.currentDurationSeconds ||
                entity.generatedIntensity != entity.currentIntensity ||
                entity.generatedPurpose != entity.currentPurpose ||
                entity.generatedReason != entity.currentReason ||
                generated.intervalStructure != current.intervalStructure,
            generated = generated,
            current = current,
            actual = actual?.let(::activity),
            consequence = consequence,
            adjustment = adjustment,
        )
    }

    private fun prescription(
        entity: WorkoutEntity,
        generated: Boolean,
        blocks: List<WorkoutBlockEntity> = emptyList(),
        segments: List<WorkoutSegmentEntity> = emptyList(),
    ): LocalPrescriptionReadModel = if (generated) {
        LocalPrescriptionReadModel(
            entity.generatedWorkoutType,
            entity.generatedPrescriptionKind,
            LocalLoadReadModel(entity.generatedDistanceMeters, entity.generatedDurationSeconds),
            entity.generatedIntensity,
            entity.generatedPurpose,
            entity.generatedReason,
            entity.generatedWarmupSeconds,
            entity.generatedCooldownSeconds,
            timedStructure(entity, "generated", entity.generatedWarmupSeconds, entity.generatedCooldownSeconds, blocks, segments),
        )
    } else {
        LocalPrescriptionReadModel(
            entity.currentWorkoutType,
            entity.currentPrescriptionKind,
            LocalLoadReadModel(entity.currentDistanceMeters, entity.currentDurationSeconds),
            entity.currentIntensity,
            entity.currentPurpose,
            entity.currentReason,
            entity.currentWarmupSeconds,
            entity.currentCooldownSeconds,
            timedStructure(entity, "current", entity.currentWarmupSeconds, entity.currentCooldownSeconds, blocks, segments),
        )
    }

    private fun timedStructure(
        workout: WorkoutEntity,
        version: String,
        warmupSeconds: Int?,
        cooldownSeconds: Int?,
        blocks: List<WorkoutBlockEntity>,
        segments: List<WorkoutSegmentEntity>,
    ): LocalTimedIntervalStructureReadModel? {
        val timedBlocks = blocks
            .asSequence()
            .filter { it.workoutId == workout.workoutId && it.prescriptionVersion == version }
            .sortedBy(WorkoutBlockEntity::ordinal)
            .map { block ->
                LocalTimedBlockReadModel(
                    repetitions = block.repetitions,
                    segments = segments
                        .asSequence()
                        .filter { it.blockId == block.blockId }
                        .sortedBy(WorkoutSegmentEntity::ordinal)
                        .map { segment -> LocalTimedSegmentReadModel(segment.segmentType, segment.targetDurationSeconds) }
                        .toList(),
                )
            }
            .toList()
        return if (timedBlocks.isEmpty() && warmupSeconds == null && cooldownSeconds == null) {
            null
        } else {
            LocalTimedIntervalStructureReadModel(warmupSeconds, cooldownSeconds, timedBlocks)
        }
    }

    private fun activity(row: LocalActivityLedgerSlice): LocalActivityReadModel {
        val entity = row.activity
        return LocalActivityReadModel(
            activityId = entity.activityId,
            source = entity.source,
            occurredAtEpochMillis = entity.occurredAtEpochMillis,
            linkedWorkoutId = entity.linkedWorkoutId,
            load = LocalLoadReadModel(entity.distanceMeters, entity.durationSeconds),
            feltHard = row.feedback?.feltHard,
            pain = row.feedback?.pain,
            reviewState = entity.reviewState,
            extraPlanImpactConfirmed = entity.extraPlanImpactConfirmed,
            consequence = entity.takeIf { it.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
                ?.let { accepted ->
                    row.consequence?.let { stored ->
                        activityConsequence(accepted, row.feedback, stored, row.consequenceOptions)
                    } ?: row.linkedWorkoutFeedback?.let { feedback ->
                        row.linkedWorkoutConsequence?.let { stored ->
                            workoutConsequence(
                                feedback,
                                stored,
                                row.linkedWorkoutConsequenceOptions,
                            )
                        }
                    }
                },
            evidence = LocalActivityEvidenceReadModel(
                routeRetained = entity.routeTraceRetained,
                routeStartEndRedacted = entity.routeStartEndRedacted,
                routeSourcePointCount = entity.routePointCount,
                route = row.route.sortedBy(RouteSampleEntity::ordinal).map {
                    LocalRoutePointReadModel(
                        it.latitudeE6,
                        it.longitudeE6,
                        it.elapsedSeconds,
                        it.elevationMeters,
                        it.segmentOrdinal,
                        it.speedMetersPerSecond,
                    )
                },
                heartRateSeriesRetained = entity.heartRateSeriesRetained,
                heartRateSourceSampleCount = entity.heartRateSourceSampleCount,
                heartRate = row.heartRate.sortedBy(HeartRateSampleEntity::ordinal).map {
                    LocalHeartRatePointReadModel(it.elapsedSeconds, it.beatsPerMinute, it.sourceSampleCount)
                },
                averageHeartRateBpm = entity.averageHeartRateBpm,
                maxHeartRateBpm = entity.maxHeartRateBpm,
                averageCadenceSpm = entity.averageCadenceSpm,
            ),
        )
    }

    private fun workoutConsequence(
        feedback: WorkoutFeedbackEntity,
        stored: WorkoutFeedbackConsequenceEntity,
        options: List<WorkoutFeedbackConsequenceOptionEntity>,
    ) = LocalConsequenceReadModel(
        sourceKind = LocalDecisionSourceKind.WorkoutFeedback.name,
        sourceId = feedback.feedbackId,
        sourceVersion = feedback.recordedAtEpochMillis.toString(),
        classification = stored.classification,
        deviation = stored.deviation,
        loadMetric = stored.loadMetric,
        actualDifference = stored.distanceDifferenceMeters ?: stored.durationDifferenceSeconds,
        actualLoad = LocalLoadReadModel(feedback.completedDistanceMeters, feedback.completedDurationSeconds),
        risk = stored.risk ?: stored.assessment,
        recommendedDecision = stored.recommendedDecision,
        appliedDecision = stored.appliedDecision,
        options = options.map(WorkoutFeedbackConsequenceOptionEntity::decision),
        comparisonStatus = stored.comparisonStatus,
        planChangeAvailable = stored.planChangeAvailable,
    )

    private fun activityConsequence(
        activity: ActivityEntity,
        feedback: ActivityFeedbackEntity?,
        stored: ActivityConsequenceEntity,
        options: List<ActivityConsequenceOptionEntity>,
    ) = LocalConsequenceReadModel(
        sourceKind = LocalDecisionSourceKind.Activity.name,
        sourceId = activity.activityId,
        sourceVersion = (feedback?.recordedAtEpochMillis ?: activity.updatedAtEpochMillis).toString(),
        classification = stored.classification,
        deviation = stored.deviation,
        loadMetric = stored.loadMetric,
        actualDifference = stored.distanceDifferenceMeters ?: stored.durationDifferenceSeconds,
        actualLoad = LocalLoadReadModel(stored.actualLoadMeters, stored.actualLoadDurationSeconds),
        risk = stored.risk ?: stored.assessment,
        recommendedDecision = stored.recommendedDecision,
        appliedDecision = stored.appliedDecision,
        options = options.map(ActivityConsequenceOptionEntity::decision),
        comparisonStatus = stored.comparisonStatus,
        planChangeAvailable = stored.planChangeAvailable,
    )

    private fun currentSignal(
        plan: LocalPlanLedgerSlice,
        accepted: List<LocalActivityLedgerSlice>,
        profile: ProfileSettingsEntity?,
        zone: ZoneId,
        todayEpochDay: Long,
    ): LocalCurrentSignalReadModel {
        val planRisk = plan.plan.riskAssessment?.takeIf(::knownRisk) ?: "conservative"
        val workoutById = plan.workouts.associateBy(WorkoutEntity::workoutId)
        val consequenceByFeedback =
            plan.workoutConsequences.associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
        val recentStart = todayEpochDay - 28
        val feedbackEvidence = plan.feedback.mapNotNull { feedback ->
            val workout = workoutById[feedback.workoutId] ?: return@mapNotNull null
            val stored = consequenceByFeedback[feedback.feedbackId] ?: return@mapNotNull null
            if (workout.currentScheduledEpochDay !in recentStart..todayEpochDay) {
                return@mapNotNull null
            }
            SignalEvidence(
                risk = (stored.risk ?: stored.assessment)?.takeIf(::knownRisk) ?: planRisk,
                resolved = stored.appliedDecision != null,
                evidenceEpochDay = workout.currentScheduledEpochDay,
                recordedAtEpochMillis = feedback.recordedAtEpochMillis,
                source = if (feedback.sourceActivityId == null) "feedback" else "activity",
                reasons = signalReasons(
                    classification = stored.classification,
                    deviation = stored.deviation,
                    feltHard = feedback.feltHard,
                    pain = feedback.pain,
                    recommendedDecision = stored.recommendedDecision,
                ),
            )
        }
        val activityEvidence = accepted.mapNotNull { row ->
            if (row.activity.linkedWorkoutId != null || !row.activity.extraPlanImpactConfirmed) {
                return@mapNotNull null
            }
            val stored = row.consequence ?: return@mapNotNull null
            val epochDay = Instant.ofEpochMilli(row.activity.occurredAtEpochMillis)
                .atZone(zone)
                .toLocalDate()
                .toEpochDay()
            if (
                epochDay !in recentStart..todayEpochDay ||
                plan.weeks.none { epochDay in it.startEpochDay..(it.startEpochDay + 6) }
            ) {
                return@mapNotNull null
            }
            SignalEvidence(
                risk = (stored.risk ?: stored.assessment)?.takeIf(::knownRisk) ?: planRisk,
                resolved = stored.appliedDecision != null,
                evidenceEpochDay = epochDay,
                recordedAtEpochMillis =
                    row.feedback?.recordedAtEpochMillis ?: row.activity.updatedAtEpochMillis,
                source = "activity",
                reasons = signalReasons(
                    classification = stored.classification,
                    deviation = stored.deviation,
                    feltHard = row.feedback?.feltHard == true,
                    pain = row.feedback?.pain == true,
                    recommendedDecision = stored.recommendedDecision,
                ),
            )
        }
        val latest = (feedbackEvidence + activityEvidence).maxWithOrNull(
            compareBy<SignalEvidence>(
                SignalEvidence::evidenceEpochDay,
                SignalEvidence::recordedAtEpochMillis,
            ),
        )
        val selected = latest?.takeUnless(SignalEvidence::resolved)
            ?.takeIf { riskRank(it.risk) >= riskRank(planRisk) }
        val hasDistance = plan.workouts.any { (it.currentDistanceMeters ?: 0) > 0 }
        val hasDuration = plan.workouts.any { (it.currentDurationSeconds ?: 0) > 0 }
        val hasMixedLoad = hasDistance && hasDuration
        val planReasons = plan.summaryWarnings
            .map(PlanSummaryWarningEntity::message)
            .filterNot(::isStoredHealthContextWarning)
            .filterNot { hasMixedLoad && isStoredNumericRampWarning(it) }
            .distinct()
            .take(3)
            .toMutableList()
            .also { reasons ->
                if (reasons.isEmpty() && planRisk != "conservative" && !hasMixedLoad) {
                    reasons += "The saved plan is above runway's default ramp."
                }
            }
        return LocalCurrentSignalReadModel(
            risk = selected?.risk ?: planRisk,
            reasons = selected?.reasons.orEmpty().ifEmpty { if (selected == null) planReasons else emptyList() },
            source = selected?.source ?: "plan",
            healthNotice = healthNotice(profile),
        )
    }

    private fun assignUnlinkedToPlanWeeks(
        accepted: List<LocalActivityLedgerSlice>,
        plans: List<LocalPlanLedgerSlice>,
        zone: ZoneId,
    ): List<AcceptedWeekAssignment> = accepted
        .asSequence()
        .filter { it.activity.linkedWorkoutId == null }
        .mapNotNull { row ->
            val activityEpochDay = Instant.ofEpochMilli(row.activity.occurredAtEpochMillis)
                .atZone(zone)
                .toLocalDate()
                .toEpochDay()
            plans
                .asSequence()
                .flatMap { plan ->
                    plan.weeks
                        .asSequence()
                        .filter { activityEpochDay in it.startEpochDay..(it.startEpochDay + 6) }
                        .map { week -> AcceptedWeekAssignment(plan, week, row) }
                }
                .sortedWith(
                    compareByDescending<AcceptedWeekAssignment> {
                        it.plan.plan.state == "active"
                    }.thenByDescending { it.plan.plan.startEpochDay },
                )
                .firstOrNull()
        }
        .toList()

    private fun healthNotice(profile: ProfileSettingsEntity?): LocalHealthNoticeReadModel? = when {
        profile?.medicalRestriction == true -> LocalHealthNoticeReadModel(
            level = "paused",
            heading = "Running limit recorded",
            message = "A clinician-imposed running limit is active. The schedule remains recorded, but runway does not treat it as clearance to continue.",
        )
        profile?.currentPain == true -> LocalHealthNoticeReadModel(
            level = "paused",
            heading = "Pain is present now",
            message = "The schedule remains recorded, but runway does not treat it as clearance to continue. Seek qualified guidance if pain persists, worsens, or changes how you move.",
        )
        profile?.recentInjury == true || profile?.recurringPain == true -> LocalHealthNoticeReadModel(
            level = "caution",
            heading = "Health context noted",
            message = "Recovery or recurring pain is recorded. It changes the distance-ramp assessment, but it does not determine whether running is appropriate.",
        )
        else -> null
    }

    private fun signalReasons(
        classification: String?,
        deviation: String?,
        feltHard: Boolean,
        pain: Boolean,
        recommendedDecision: String?,
    ): List<String> = buildList {
        when {
            pain -> add("Pain was reported on the latest accepted run.")
            feltHard -> add("The latest accepted run was marked hard.")
            deviation == "short" -> add("The latest accepted run was shorter than its planned amount.")
            deviation in setOf("over", "long") -> add("The latest accepted run exceeded its planned amount.")
            classification == "completed_as_planned" ->
                add("The latest accepted run was recorded at the planned amount.")
            classification != null ->
                add("The latest accepted run has a stored training consequence.")
        }
        recommendedDecision
            ?.takeUnless { it == "keep_plan" }
            ?.let { add("Recommended choice: ${decisionLabel(it)}.") }
    }.distinct().take(3)

    private fun decisionLabel(value: String): String = when (value) {
        "reduce_next" -> "reduce the next run"
        "next_rest" -> "make the next workout a recovery day"
        "repeat_prescription" -> "repeat the prescription"
        "rebalance_week" -> "rebalance the week"
        else -> value.replace('_', ' ')
    }

    private fun Long.toSurfaceInt(label: String): Int {
        require(this in Int.MIN_VALUE..Int.MAX_VALUE) {
            "$label exceeds the current local surface range."
        }
        return toInt()
    }

    private fun knownRisk(value: String): Boolean =
        value in setOf("conservative", "moderate", "aggressive", "unsafe")

    private fun riskRank(value: String): Int = when (value) {
        "moderate" -> 1
        "aggressive" -> 2
        "unsafe" -> 3
        else -> 0
    }

    private fun isStoredHealthContextWarning(value: String): Boolean =
        value.startsWith("Injury recovery or recurring pain is included") ||
            value.startsWith("Recent injury or recurring pain is noted")

    private fun isStoredNumericRampWarning(value: String): Boolean =
        value.startsWith("The required weekly increase is above") ||
            value.startsWith("Weekly distance growth above 10%")

    private fun loads(values: List<Pair<Int?, Int?>>): LocalLoadReadModel {
        val distances = values.mapNotNull { it.first }
        val durations = values.mapNotNull { it.second }
        return LocalLoadReadModel(
            distanceMeters = distances.takeIf { it.isNotEmpty() }?.sum(),
            durationSeconds = durations.takeIf { it.isNotEmpty() }?.sum(),
        )
    }

    private fun lifecycle(entity: PlanLifecycleEventEntity) = LocalLifecycleReadModel(
        entity.eventType,
        entity.occurredAtEpochMillis,
        entity.completedWorkoutCount,
        entity.completedActivityCount,
        entity.note,
    )

    private fun planState(value: String) = when (value) {
        "active" -> LocalPlanState.ACTIVE
        "completed" -> LocalPlanState.COMPLETED
        "archived" -> LocalPlanState.ARCHIVED
        else -> LocalPlanState.OTHER
    }

    private fun planPhase(value: String) = when (value) {
        "foundation" -> LocalPlanPhase.FOUNDATION
        "calibration" -> LocalPlanPhase.CALIBRATION
        "distance" -> LocalPlanPhase.DISTANCE
        else -> LocalPlanPhase.OTHER
    }

    private fun provenance(state: LocalPlanState) = when (state) {
        LocalPlanState.ACTIVE -> LocalPlanProvenance.ACTIVE
        LocalPlanState.COMPLETED -> LocalPlanProvenance.COMPLETED
        LocalPlanState.ARCHIVED -> LocalPlanProvenance.ARCHIVED
        LocalPlanState.OTHER -> LocalPlanProvenance.OTHER
    }

    private val COMPLETED_FEEDBACK_STATES = setOf("done", "shortened", "completed", "overrun")

    private fun localEpochDay(epochMillis: Long, timeZone: String): Long =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(timeZone)).toLocalDate().toEpochDay()

    private data class AcceptedWeekAssignment(
        val plan: LocalPlanLedgerSlice,
        val week: PlanWeekEntity,
        val activity: LocalActivityLedgerSlice,
    )

    private data class SignalEvidence(
        val risk: String,
        val resolved: Boolean,
        val evidenceEpochDay: Long,
        val recordedAtEpochMillis: Long,
        val source: String,
        val reasons: List<String>,
    )
}
