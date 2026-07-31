package dev.deftmartian.runway

/** Local presentation records for the native training ledger. */

internal data class NativeOnboardingPayload(
    val initialValues: NativePlanInitialValues?,
    val minimumTargetDate: String?,
    val minimumCalibrationTargetDate: String?,
    val minimumFoundationTargetDate: String?,
    val maximumTargetDate: String?,
    val currentGoal: NativeGoalSummary?,
)

internal data class NativeCalendarPayload(
    val onboardingRequired: Boolean?,
    val hasActivePlan: Boolean,
    val calendar: NativeCalendar?,
    val nextWorkout: NativeWorkout?,
    val activityCandidates: List<NativeWorkout>,
    val pendingDecisionCount: Int = 0,
    val pendingDecisionCountIsExact: Boolean = true,
)

internal data class NativeReviewPayload(
    val candidates: List<NativeWorkout>,
    val activities: List<NativeActivity>,
    val workoutDecisions: List<NativeWorkoutFeedback> = emptyList(),
    val healthConnectChanges: List<NativeHealthConnectChange> = emptyList(),
    val hasMore: Boolean = false,
)

internal data class NativeHealthConnectChange(
    val mappingId: String,
    val provider: String,
    val recordId: String,
    val state: String,
    val current: NativeActivitySummary?,
    val proposed: NativeActivitySummary?,
)

internal data class NativeActivitySummary(
    val date: String,
    val distanceMeters: Double?,
    val durationSeconds: Double?,
)
internal data class NativeActivityEvidence(
    val routeTrace: NativeRouteTrace?,
    val heartRateSeries: NativeHeartRateSeries?,
    val averageCadence: Int?,
    val disclosure: NativeActivityDisclosure?,
)

internal data class NativeStatsPayload(
    val onboardingRequired: Boolean?,
    val active: NativePlanHistoryItem?,
    val detail: NativePlanDetail?,
    val history: NativeTrainingHistory?,
    val planTrace: List<NativePlanTraceWeek>,
    val planHistory: NativePlanHistoryPage?,
    val phaseReview: NativePhaseReview?,
)

internal data class NativeHistoryPayload(
    val onboardingRequired: Boolean?,
    val history: NativePlanHistoryPage?,
    val activeItem: NativePlanHistoryItem?,
    val phaseReview: NativePhaseReview?,
    val offset: Int?,
    val pageSize: Int?,
    val activitiesOutsidePlans: List<NativeActivity> = emptyList(),
)

internal data class NativeHistoryDetailPayload(
    val onboardingRequired: Boolean?,
    val detail: NativeHistoryDetail?,
)

internal data class NativeHistoryDetail(
    val plan: NativeHistoryDetailPlan?,
    val goal: NativeHistoryDetailGoal?,
    val cutoffDate: String?,
    val timeline: List<NativeHistoryTimelineItem>,
    val weeks: List<NativeHistoryWeek>,
)
internal data class NativeHistoryDetailPlan(
    val id: String?, val status: String?, val phase: String?, val startDate: String?,
    val targetDate: String?, val weeks: Int?, val risk: String?, val completedAt: String?,
    val archivedAt: String?, val lifecycleReason: String?,
)
internal data class NativeHistoryDetailGoal(val title: String?, val distance: String?, val priority: String?)
internal data class NativeHistoryTimelineItem(
    val id: String?, val triggerType: String?, val createdAt: String?, val reversedAt: String?,
    val reversalReason: String?, val reason: String?, val newState: NativeHistoryWorkoutState?,
)
internal data class NativeHistoryWorkoutState(
    val scheduledDate: String?, val type: String?, val prescriptionKind: String?,
    val targetDistanceMeters: Double?, val targetDurationSeconds: Double?, val isRemoved: Boolean?,
)
internal data class NativeHistoryWeek(
    val id: String?, val weekNumber: Int?, val startDate: String?, val targetDistanceMeters: Double?,
    val targetDurationSeconds: Double?, val risk: String?, val isDownWeek: Boolean?, val isTaper: Boolean?,
    val workouts: List<NativeHistoryWorkout>,
    val extraActivities: List<NativeActivity> = emptyList(),
)
internal data class NativeHistoryWorkout(
    val id: String?,
    val status: String?,
    val generated: NativeHistoryPrescription,
    val current: NativeHistoryPrescription,
    val isRemoved: Boolean?,
    val result: NativeHistoryResult?,
)
internal data class NativeHistoryPrescription(
    val scheduledDate: String?,
    val type: String?,
    val prescriptionKind: String?,
    val targetDistanceMeters: Double?,
    val targetDurationSeconds: Double?,
    val purpose: String?,
    val intervalStructure: TimedIntervalStructureDto? = null,
)
internal data class NativeHistoryResult(
    val source: String?, val completedDistanceMeters: Double?, val completedDurationSeconds: Double?,
    val feltHard: Boolean?, val pain: Boolean?, val consequence: NativeConsequence?,
)

internal data class NativePlanInitialValues(
    val startMode: String?,
    val raceDistance: String?,
    val targetDate: String?,
    val priority: String?,
    val currentWeeklyDistanceKm: String?,
    val currentRunsPerWeek: String?,
    val longestRecentRunKm: String?,
    val calibrationDurationMinutes: String?,
    val preferredLongRunDay: String?,
    val timeZone: String?,
    val availability: List<Int>,
    val recentInjury: Boolean?,
    val currentPain: Boolean?,
    val recurringPain: Boolean?,
    val medicalRestriction: Boolean?,
    val injuryNotes: String?,
)

internal data class NativeGoalSummary(
    val id: String? = null,
    val title: String?,
    val distance: String? = null,
    val targetDate: String?,
    val priority: String? = null,
    val state: String?,
    val risk: String?,
)

internal data class NativeCalendar(
    val month: String?,
    val today: String?,
    val previousMonth: String?,
    val nextMonth: String?,
    val workouts: List<NativeWorkout>,
    val activities: List<NativeActivity>,
    val feedback: List<NativeWorkoutFeedback>,
)

internal data class NativeWorkout(
    val id: String?,
    val weekId: String?,
    val weekNumber: Int?,
    val scheduledDate: String?,
    val type: String?,
    val status: String?,
    val targetDistanceMeters: Double?,
    val targetDurationSeconds: Double?,
    val prescriptionKind: String?,
    val intervalStructure: TimedIntervalStructureDto?,
    val generatedIntervalStructure: TimedIntervalStructureDto? = null,
    val intensity: String?,
    val purpose: String?,
    val reason: String?,
    val isRemoved: Boolean?,
    val isEdited: Boolean?,
    val adjustment: NativeAdjustment?,
)

internal data class TimedIntervalStructureDto(
    val warmupSeconds: Int?,
    val cooldownSeconds: Int?,
    val blocks: List<TimedBlockDto>,
)
internal data class TimedBlockDto(val repetitions: Int?, val segments: List<TimedSegmentDto>)
internal data class TimedSegmentDto(val kind: String?, val durationSeconds: Int?)
internal data class NativeAdjustment(val id: String?, val kind: String?, val reason: String?)

internal data class NativeWorkoutFeedback(
    val id: String?,
    val workoutId: String?,
    val completedDistanceMeters: Double?,
    val completedDurationSeconds: Double?,
    val feltHard: Boolean?,
    val pain: Boolean?,
    val consequence: NativeConsequence?,
    val canDelete: Boolean?,
    val completionState: String? = null,
    val scheduledDate: String? = null,
    val workoutPurpose: String? = null,
)

internal data class NativeActivity(
    val id: String?,
    val workoutId: String?,
    val source: String?,
    val reviewState: String?,
    val occurredDate: String?,
    val activityDate: String?,
    val distanceMeters: Double?,
    val durationSeconds: Double?,
    val averagePaceSecondsPerKm: Double?,
    val averageHeartRate: Int?,
    val maxHeartRate: Int?,
    val heartRateSummary: NativeHeartRateSummary?,
    val feltHard: Boolean?,
    val pain: Boolean?,
    val extraPlanImpactConfirmed: Boolean?,
    val consequence: NativeConsequence?,
    val routeSummary: NativeRouteSummary?,
    val matchedWorkoutPurpose: String?,
    val matchedWorkoutDate: String?,
)

internal data class NativeHeartRateSummary(
    val highSeconds: Int?,
    val highShare: Double?,
    val secondsByZone: NativeHeartRateZones?,
    val settingsSource: String?,
)
internal data class NativeHeartRateZones(
    val z1: Int?, val z2: Int?, val z3: Int?, val z4: Int?, val z5: Int?,
)
internal data class NativeRouteSummary(
    val pointCount: Int?,
    val startEndRedacted: Boolean?,
    val hasElevation: Boolean?,
    val traceRetained: Boolean?,
)
internal data class NativeRouteTrace(
    val sourcePointCount: Int?,
    val points: List<NativeRoutePoint>,
)
internal data class NativeRoutePoint(
    val latitudeE6: Int?, val longitudeE6: Int?, val elapsedSeconds: Int?,
    val segmentIndex: Int?, val speedMetersPerSecond: Double?,
)
internal data class NativeHeartRateSeries(
    val sourceSampleCount: Int?, val points: List<NativeHeartRatePoint>,
)
internal data class NativeHeartRatePoint(val elapsedSeconds: Int?, val bpm: Int?)
internal data class NativeActivityDisclosure(
    val routeTraceRetained: Boolean?, val routePointCount: Int?, val startEndRedacted: Boolean?,
    val hasElevation: Boolean?, val heartRateSeriesRetained: Boolean?, val heartRateSampleCount: Int?,
)

internal data class NativeConsequence(
    val kind: String?,
    val appliedDecision: String?,
    val recommendedDecision: String?,
    val deviation: String?,
    val risk: String?,
    val planChangeAvailable: Boolean?,
    val options: List<String>,
    val comparisonStatus: String?,
    val sourceKind: String? = null,
    val sourceId: String? = null,
)

internal data class NativePlanDetail(val weeks: List<NativeWeek>)
internal data class NativeWeek(
    val id: String?,
    val weekNumber: Int?,
    val startDate: String?,
    val targetDistanceMeters: Double?,
    val targetDurationSeconds: Double?,
    val completedDistanceMeters: Double?,
    val risk: String?,
    val hasMixedLoad: Boolean?,
)
internal data class NativePlanTraceWeek(
    val id: String?,
    val weekNumber: Int?,
    val startDate: String?,
    val recommendedDistanceMeters: Double?,
    val recommendedDurationSeconds: Double?,
    val currentDistanceMeters: Double?,
    val currentDurationSeconds: Double?,
)
internal data class NativePhaseReview(
    val planId: String?,
    val phase: String?,
    val goalKind: String?,
    val goalTitle: String?,
    val baseline: NativePhaseBaseline?,
    val recommended: String?,
    val options: List<String>,
    val preferredLongRunDay: Int?,
    val racePlan: NativeRacePlan?,
)
internal data class NativePhaseBaseline(
    val activityCount: Int?,
    val totalDurationSeconds: Double?,
    val totalDistanceMeters: Double?,
    val longestActivityMeters: Double?,
    val weeklyDistanceMeters: Double?,
    val runsPerWeek: Double?,
)
internal data class NativeRacePlan(
    val previewToken: String,
    val risk: String?,
    val weeks: Int?,
    val startDate: String?,
    val targetDate: String?,
    val summary: NativeDistanceSummary?,
    val warnings: List<String>,
)
internal data class NativeDistanceSummary(
    val baselineMeters: Double?,
    val peakMeters: Double?,
    val requiredWeeklyIncreasePercent: Double?,
    val defaultWeeklyIncreasePercent: Double?,
    val longRunPeakMeters: Double?,
    val warnings: List<String>,
)
internal data class NativeTrainingHistory(
    val weeklySummaries: List<NativeWeekSummary>,
    val todayIso: String?,
    val currentSignal: NativeCurrentSignal?,
    val hasAcceptedActivities: Boolean?,
    val recordedSummary: NativeRecordedHistorySummary?,
    val heartRateSample: NativeHeartRateSample?,
    /** Kept as a count because Stats only needs evidence that feedback exists. */
    val recentFeedbackCount: Int? = null,
)
internal data class NativeCurrentSignal(
    val risk: String?, val reasons: List<String>, val source: String?, val healthNotice: NativeHealthNotice?,
)
internal data class NativeHealthNotice(val level: String?, val heading: String?, val message: String?)
internal data class NativeRecordedHistorySummary(
    val totalRuns: Int?,
    val totalDistanceMeters: Double?,
    val totalDurationSeconds: Double?,
    val longestRunMeters: Double?,
    val currentPlanRuns: Int?,
    val currentPlanDistanceMeters: Double?,
    val archivedPlanRuns: Int?,
    val archivedPlanDistanceMeters: Double?,
    val unlinkedRuns: Int?,
    val unlinkedDistanceMeters: Double?,
)
internal data class NativeHeartRateSample(
    val windowDays: Int?,
    val windowStart: String?,
    val windowEnd: String?,
    val sampleCount: Int?,
    val averageHeartRate: Int?,
    val highZoneSeconds: Double?,
    val latest: NativeHeartRateObservation?,
    val oldest: NativeHeartRateObservation?,
)
internal data class NativeHeartRateObservation(
    val activityDate: String?,
    val averageHeartRate: Int?,
    val maxHeartRate: Int?,
)
internal data class NativeWeekSummary(
    val weekNumber: Int?,
    val startDate: String?,
    val targetDistanceMeters: Double?,
    val completedDistanceMeters: Double?,
    val completedDurationSeconds: Double?,
    val plannedRuns: Int?,
    val completedRuns: Int?,
    val missedRuns: Int?,
    val skippedRuns: Int?,
    val painFlags: Int?,
    val hardFlags: Int?,
    val averagePaceSecondsPerKm: Double?,
    val averageHeartRate: Int?,
)
internal data class NativePlanHistoryPage(val items: List<NativePlanHistoryItem>, val nextOffset: Int?, val today: String?)
internal data class NativePlanHistoryItem(
    val plan: NativePlan?,
    val goal: NativeGoalSummary?,
    val summary: NativePlanSummary?,
)
internal data class NativePlan(
    val id: String?,
    val status: String?,
    val startDate: String?,
    val targetDate: String?,
    val weeks: Int?,
    val risk: String?,
    val summaryKind: String?,
    val completedAt: String?,
    val archivedAt: String?,
    val lifecycleReason: String?,
)
internal data class NativePlanSummary(
    val plannedRuns: Int?,
    val completedRuns: Int?,
    val missedRuns: Int?,
    val skippedRuns: Int?,
    val painFlags: Int?,
    val completedDistanceMeters: Double?,
)
