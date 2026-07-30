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
        require(inboxActivities in 1..200)
        require(statsWeeks in 1..104)
        require(statsActivities in 1..2_000)
        require(historyPlans in 1..100)
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

data class LocalPrescriptionReadModel(
    val workoutType: String,
    val prescriptionKind: String,
    val load: LocalLoadReadModel,
    val intensity: String?,
    val purpose: String?,
    val reason: String?,
    val warmupSeconds: Int?,
    val cooldownSeconds: Int?,
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
    val pendingReviewCount: Int,
    val pendingReviewCountIsExact: Boolean,
    val hasMoreActivities: Boolean,
    val days: List<LocalCalendarDayReadModel>,
    val feedback: List<LocalWorkoutFeedbackReadModel> = emptyList(),
)

data class LocalInboxReadModel(
    val reviewCount: Int,
    val reviewCountIsExact: Boolean,
    val hasMore: Boolean,
    val activities: List<LocalActivityReadModel>,
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
)

data class LocalRecordedTotalsReadModel(
    val provenance: LocalPlanProvenance,
    val runs: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
)

data class LocalStatsReadModel(
    val weeks: List<LocalWeekStatsReadModel>,
    val recordedTotals: List<LocalRecordedTotalsReadModel>,
    val totalRuns: Int,
    val totalDistanceMeters: Int,
    val totalDurationSeconds: Int,
    val longestRunMeters: Int?,
    val weightedPaceSecondsPerKilometre: Double?,
    val durationWeightedHeartRateBpm: Int?,
    val isComplete: Boolean,
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
    val scheduledEpochDay: Long,
    val status: String,
    val current: LocalPrescriptionReadModel,
    val result: LocalRecordedResultReadModel?,
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
    val weeks: List<LocalHistoryWeekReadModel> = emptyList(),
)

data class LocalHistoryReadModel(
    val plans: List<LocalPlanHistoryReadModel>,
    val unlinkedActivities: List<LocalActivityReadModel>,
    val hasMorePlans: Boolean,
    val hasMoreActivities: Boolean,
)

data class LocalProfileReadModel(
    val timeZone: String,
    val routeDataMode: String,
    val availabilityDays: List<Int>,
    val recentInjury: Boolean,
    val currentPain: Boolean,
    val recurringPain: Boolean,
    val medicalRestriction: Boolean,
    val privateNotes: String?,
    val heartRateSettingsSource: String,
    val maxHeartRateBpm: Int?,
    val zone2FloorBpm: Int?,
    val zone3FloorBpm: Int?,
    val zone4FloorBpm: Int?,
    val zone5FloorBpm: Int?,
)

data class LocalActivePlanReadModel(
    val planId: String,
    val goalTitle: String,
    val phase: LocalPlanPhase,
    val state: LocalPlanState,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val latestLifecycleEvent: LocalLifecycleReadModel?,
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
)

data class LocalActivityLedgerSlice(
    val activity: ActivityEntity,
    val feedback: ActivityFeedbackEntity? = null,
    val route: List<RouteSampleEntity> = emptyList(),
    val heartRate: List<HeartRateSampleEntity> = emptyList(),
)

data class LocalCalendarLedgerSlice(
    val fromEpochDay: Long,
    val throughEpochDay: Long,
    val timeZone: String,
    val pendingReviewCount: Int,
    val pendingReviewCountIsExact: Boolean = true,
    val hasMoreActivities: Boolean = false,
    val plans: List<LocalPlanLedgerSlice>,
    val activities: List<LocalActivityLedgerSlice>,
)

data class LocalInboxLedgerSlice(
    val reviewCount: Int,
    val reviewCountIsExact: Boolean = true,
    val hasMore: Boolean,
    val activities: List<LocalActivityLedgerSlice>,
)

data class LocalStatsLedgerSlice(
    val plans: List<LocalPlanLedgerSlice>,
    val activities: List<LocalActivityLedgerSlice>,
    val hasMorePlans: Boolean = false,
    val hasMoreActivities: Boolean = false,
)

data class LocalHistoryLedgerSlice(
    val plans: List<LocalPlanLedgerSlice>,
    val activities: List<LocalActivityLedgerSlice>,
    val hasMorePlans: Boolean,
    val hasMoreActivities: Boolean,
)

data class LocalSettingsLedgerSlice(
    val profile: ProfileSettingsEntity?,
    val availabilityDays: List<ProfileAvailabilityDayEntity>,
    val activePlan: LocalPlanLedgerSlice?,
    val versionName: String?,
    val buildRevision: String?,
)

interface LocalSurfaceLedgerReader {
    suspend fun calendar(
        fromEpochDay: Long,
        throughEpochDay: Long,
        limits: LocalSurfaceReadLimits,
    ): LocalCalendarLedgerSlice

    suspend fun inbox(limits: LocalSurfaceReadLimits): LocalInboxLedgerSlice
    suspend fun stats(limits: LocalSurfaceReadLimits): LocalStatsLedgerSlice
    suspend fun history(limits: LocalSurfaceReadLimits): LocalHistoryLedgerSlice
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

    suspend fun inbox(): LocalInboxReadModel = LocalSurfaceMappers.inbox(ledger.inbox(limits))
    suspend fun stats(): LocalStatsReadModel = LocalSurfaceMappers.stats(ledger.stats(limits))
    suspend fun history(): LocalHistoryReadModel = LocalSurfaceMappers.history(ledger.history(limits))
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
        return LocalCalendarReadModel(
            fromEpochDay = slice.fromEpochDay,
            throughEpochDay = slice.throughEpochDay,
            activePlanId = activePlans.singleOrNull()?.plan?.planId,
            pendingReviewCount = slice.pendingReviewCount,
            pendingReviewCountIsExact = slice.pendingReviewCountIsExact,
            hasMoreActivities = slice.hasMoreActivities,
            days = days,
            feedback = feedback,
        )
    }

    fun inbox(slice: LocalInboxLedgerSlice): LocalInboxReadModel = LocalInboxReadModel(
        reviewCount = slice.reviewCount,
        reviewCountIsExact = slice.reviewCountIsExact,
        hasMore = slice.hasMore,
        activities = slice.activities
            .filter { it.activity.reviewState == "review" }
            .sortedByDescending { it.activity.occurredAtEpochMillis }
            .map(::activity),
    )

    fun stats(slice: LocalStatsLedgerSlice): LocalStatsReadModel {
        val accepted = slice.activities.filter { it.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        val acceptedByWorkout = accepted
            .filter { it.activity.linkedWorkoutId != null }
            .groupBy { requireNotNull(it.activity.linkedWorkoutId) }
        val weeks = slice.plans.flatMap { plan ->
            val feedbackByWorkout = plan.feedback.associateBy(WorkoutFeedbackEntity::workoutId)
            plan.weeks.map { week ->
                val workouts = plan.workouts.filter {
                    it.weekId == week.weekId && it.currentStatus != WORKOUT_STATE_TOMBSTONED
                }
                val actuals = workouts.flatMap { workout ->
                    acceptedByWorkout[workout.workoutId].orEmpty().map { it.activity }
                }
                val directFeedback = workouts.mapNotNull { feedbackByWorkout[it.workoutId] }
                    .filter { it.sourceActivityId == null }
                LocalWeekStatsReadModel(
                    planId = plan.plan.planId,
                    planState = planState(plan.plan.state),
                    phase = planPhase(plan.plan.phaseType),
                    weekOrdinal = week.ordinal,
                    startEpochDay = week.startEpochDay,
                    generated = loads(workouts.map { it.generatedDistanceMeters to it.generatedDurationSeconds }),
                    current = loads(workouts.map { it.currentDistanceMeters to it.currentDurationSeconds }),
                    actual = loads(
                        actuals.map { it.distanceMeters to it.durationSeconds } +
                            directFeedback.map { it.completedDistanceMeters to it.completedDurationSeconds },
                    ),
                    plannedRuns = workouts.count { it.currentWorkoutType != "rest" },
                    completedRuns = workouts.count {
                        it.currentWorkoutType != "rest" &&
                            (it.currentStatus in setOf("done", "shortened", "completed", "overrun") ||
                                acceptedByWorkout.containsKey(it.workoutId) ||
                                feedbackByWorkout[it.workoutId]?.completionState in
                                setOf("done", "shortened", "completed", "overrun"))
                    },
                    missedRuns = workouts.count { it.currentStatus in setOf("missed", "skipped") },
                )
            }
        }
        val planByWorkout = slice.plans.flatMap { plan ->
            plan.workouts.map { it.workoutId to planState(plan.plan.state) }
        }.toMap()
        val totals = accepted
            .groupBy {
                val workoutId = it.activity.linkedWorkoutId
                if (workoutId == null) {
                    LocalPlanProvenance.UNLINKED
                } else {
                    planByWorkout[workoutId]?.let(::provenance) ?: LocalPlanProvenance.OTHER
                }
            }
            .map { (origin, rows) ->
                LocalRecordedTotalsReadModel(
                    provenance = origin,
                    runs = rows.size,
                    distanceMeters = rows.sumOf { it.activity.distanceMeters ?: 0 },
                    durationSeconds = rows.sumOf { it.activity.durationSeconds ?: 0 },
                )
            }
            .sortedBy { it.provenance.ordinal }
        val paired = accepted.map { it.activity }
            .filter { (it.distanceMeters ?: 0) > 0 && (it.durationSeconds ?: 0) > 0 }
        val heartRate = accepted.map { it.activity }
            .filter { it.averageHeartRateBpm != null && (it.durationSeconds ?: 0) > 0 }
        return LocalStatsReadModel(
            weeks = weeks.sortedWith(compareBy(LocalWeekStatsReadModel::startEpochDay, LocalWeekStatsReadModel::weekOrdinal)),
            recordedTotals = totals,
            totalRuns = accepted.size,
            totalDistanceMeters = accepted.sumOf { it.activity.distanceMeters ?: 0 },
            totalDurationSeconds = accepted.sumOf { it.activity.durationSeconds ?: 0 },
            longestRunMeters = accepted.mapNotNull { it.activity.distanceMeters }.maxOrNull(),
            weightedPaceSecondsPerKilometre = paired
                .takeIf { it.isNotEmpty() }
                ?.let { rows -> rows.sumOf { requireNotNull(it.durationSeconds) }.toDouble() * 1_000 / rows.sumOf { requireNotNull(it.distanceMeters) } },
            durationWeightedHeartRateBpm = heartRate
                .takeIf { it.isNotEmpty() }
                ?.let { rows ->
                    val seconds = rows.sumOf { requireNotNull(it.durationSeconds) }
                    (rows.sumOf { requireNotNull(it.averageHeartRateBpm).toLong() * requireNotNull(it.durationSeconds) }.toDouble() / seconds).roundToInt()
                },
            isComplete = !slice.hasMorePlans && !slice.hasMoreActivities,
        )
    }

    fun history(slice: LocalHistoryLedgerSlice): LocalHistoryReadModel {
        val accepted = slice.activities.filter { it.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        val plans = slice.plans.map { plan ->
            val workoutIds = plan.workouts.mapTo(mutableSetOf(), WorkoutEntity::workoutId)
            val linked = accepted.filter { it.activity.linkedWorkoutId in workoutIds }
            val direct = plan.feedback.filter { it.sourceActivityId == null }
            val feedbackByWorkout = plan.feedback.associateBy(WorkoutFeedbackEntity::workoutId)
            val linkedByWorkout = linked
                .groupBy { requireNotNull(it.activity.linkedWorkoutId) }
            val historyWeeks = plan.weeks.map { week ->
                val weekWorkouts = plan.workouts
                    .filter {
                        it.weekId == week.weekId &&
                            it.currentStatus != WORKOUT_STATE_TOMBSTONED
                    }
                    .sortedBy { it.position }
                val workoutRows = weekWorkouts.map { workout ->
                    val linkedResult = linkedByWorkout[workout.workoutId]?.singleOrNull()
                    val directResult = feedbackByWorkout[workout.workoutId]
                        ?.takeIf { it.sourceActivityId == null }
                    LocalHistoryWorkoutReadModel(
                        workoutId = workout.workoutId,
                        scheduledEpochDay = workout.currentScheduledEpochDay,
                        status = workout.currentStatus,
                        current = prescription(workout, generated = false),
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
                        weekWorkouts.map {
                            it.currentDistanceMeters to it.currentDurationSeconds
                        },
                    ),
                    actual = loads(
                        workoutRows.mapNotNull { workout ->
                            workout.result?.let {
                                it.distanceMeters to it.durationSeconds
                            }
                        },
                    ),
                    riskAssessment = week.riskAssessment,
                    isDownWeek = week.isDownWeek,
                    isTaperWeek = week.isTaperWeek,
                    workouts = workoutRows,
                )
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
                    it.currentWorkoutType != "rest" &&
                        (it.currentStatus in setOf("done", "shortened", "completed", "overrun") ||
                            linked.any { row -> row.activity.linkedWorkoutId == it.workoutId } ||
                            direct.any { feedback ->
                                feedback.workoutId == it.workoutId &&
                                    feedback.completionState in
                                    setOf("done", "shortened", "completed", "overrun")
                            })
                },
                actual = loads(
                    linked.map { it.activity.distanceMeters to it.activity.durationSeconds } +
                        direct.map { it.completedDistanceMeters to it.completedDurationSeconds },
                ),
                lifecycle = plan.lifecycle.sortedByDescending { it.occurredAtEpochMillis }.map(::lifecycle),
                weeks = historyWeeks,
            )
        }
        return LocalHistoryReadModel(
            plans = plans.sortedByDescending {
                it.completedAtEpochMillis ?: it.archivedAtEpochMillis ?: it.startEpochDay
            },
            unlinkedActivities = accepted.filter { it.activity.linkedWorkoutId == null }.map(::activity),
            hasMorePlans = slice.hasMorePlans,
            hasMoreActivities = slice.hasMoreActivities,
        )
    }

    fun settings(slice: LocalSettingsLedgerSlice): LocalSettingsReadModel {
        val profile = slice.profile?.let {
            LocalProfileReadModel(
                timeZone = it.timeZone,
                routeDataMode = it.routeDataMode,
                availabilityDays = slice.availabilityDays.map(ProfileAvailabilityDayEntity::dayOfWeek).sorted(),
                recentInjury = it.recentInjury,
                currentPain = it.currentPain,
                recurringPain = it.recurringPain,
                medicalRestriction = it.medicalRestriction,
                privateNotes = it.privateNotes,
                heartRateSettingsSource = it.heartRateSettingsSource,
                maxHeartRateBpm = it.maxHeartRateBpm,
                zone2FloorBpm = it.zone2FloorBpm,
                zone3FloorBpm = it.zone3FloorBpm,
                zone4FloorBpm = it.zone4FloorBpm,
                zone5FloorBpm = it.zone5FloorBpm,
            )
        }
        val activePlan = slice.activePlan?.let {
            LocalActivePlanReadModel(
                planId = it.plan.planId,
                goalTitle = it.goal?.title ?: "Training goal",
                phase = planPhase(it.plan.phaseType),
                state = planState(it.plan.state),
                startEpochDay = it.plan.startEpochDay,
                endEpochDay = it.plan.endEpochDay,
                latestLifecycleEvent = it.lifecycle.maxByOrNull(PlanLifecycleEventEntity::occurredAtEpochMillis)?.let(::lifecycle),
            )
        }
        return LocalSettingsReadModel(
            profile = profile,
            activePlan = activePlan,
            about = LocalAboutReadModel(
                versionName = slice.versionName,
                buildRevision = slice.buildRevision,
            ),
        )
    }

    private fun workout(
        entity: WorkoutEntity,
        weekOrdinal: Int,
        actual: LocalActivityLedgerSlice?,
    ) = LocalWorkoutReadModel(
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
            entity.generatedReason != entity.currentReason,
        generated = prescription(entity, generated = true),
        current = prescription(entity, generated = false),
        actual = actual?.let(::activity),
    )

    private fun prescription(
        entity: WorkoutEntity,
        generated: Boolean,
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
        )
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

    private fun localEpochDay(epochMillis: Long, timeZone: String): Long =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(timeZone)).toLocalDate().toEpochDay()
}
