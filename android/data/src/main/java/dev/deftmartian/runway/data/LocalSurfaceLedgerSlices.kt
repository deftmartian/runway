package dev.deftmartian.runway.data

import java.time.ZoneId


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
    /** False when [activities] is deliberately bounded and exact aggregates are required. */
    val activitiesContainCompleteWeekContext: Boolean = true,
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
    /** False when [activities] is deliberately bounded and exact aggregates are required. */
    val activitiesContainCompleteWeekContext: Boolean = true,
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
    val notificationPreferences: NotificationPreferencesEntity? = null,
)
