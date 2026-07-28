package dev.deftmartian.runway

/**
 * Typed representation of the versioned mobile API.  These records deliberately keep optional
 * server fields nullable: `null` means absent or JSON null, while an empty string remains an
 * empty string.  Compose can therefore apply its own display defaults without the transport
 * layer silently changing meaning.
 */
internal sealed interface NativeViewPayload {
    val onboardingRequired: Boolean?
}

internal data class NativeBootstrapPayload(
    val user: NativeUser?,
    val setupComplete: Boolean?,
    val timeZone: String?,
    val release: String?,
    val commit: String?,
    val serverOrigin: String?,
    val androidApi: Int?,
    val features: NativeFeatures?,
) : NativeViewPayload {
    override val onboardingRequired: Boolean? = null
}

internal data class NativeOnboardingPayload(
    val initialValues: NativePlanInitialValues?,
    val minimumTargetDate: String?,
    val minimumCalibrationTargetDate: String?,
    val minimumFoundationTargetDate: String?,
    val maximumTargetDate: String?,
    val activeGoal: NativeGoalSummary?,
) : NativeViewPayload {
    override val onboardingRequired: Boolean? = null
}

internal data class NativeCalendarPayload(
    override val onboardingRequired: Boolean?,
    val calendar: NativeCalendar?,
    val activityCandidates: List<NativeWorkout>,
) : NativeViewPayload

internal data class NativeReviewPayload(
    val candidates: List<NativeWorkout>,
    val activities: List<NativeActivity>,
    val activityPage: NativeOffsetPage?,
    val sources: List<NativeImportSource>,
    val androidDevices: List<NativeAndroidDevice>,
    val routeDataMode: String?,
) : NativeViewPayload {
    override val onboardingRequired: Boolean? = null
}

internal data class NativeStatsPayload(
    override val onboardingRequired: Boolean?,
    val active: NativePlanHistoryItem?,
    val detail: NativePlanDetail?,
    val history: NativeTrainingHistory?,
    val planTrace: List<NativePlanTraceWeek>,
    val planHistory: NativePlanHistoryPage?,
    val phaseReview: NativePhaseReview?,
) : NativeViewPayload

internal data class NativeHistoryPayload(
    override val onboardingRequired: Boolean?,
    val history: NativePlanHistoryPage?,
    val activeItem: NativePlanHistoryItem?,
    val phaseReview: NativePhaseReview?,
    val offset: Int?,
    val pageSize: Int?,
) : NativeViewPayload

internal data class NativeSettingsPayload(
    val profile: NativeProfile?,
    val healthConnect: NativeHealthConnectStatus?,
    val androidDevices: List<NativeAndroidDevice>,
    val sources: List<NativeImportSource>,
    val about: NativeAbout?,
    val accountSecurityUrl: String?,
    val localAuthEnabled: Boolean?,
) : NativeViewPayload {
    override val onboardingRequired: Boolean? = null
}

internal data class NativeUser(val id: String?, val name: String?, val email: String?)
internal data class NativeFeatures(
    val nativeUi: Boolean?,
    val deviceAuthorization: Boolean?,
    val healthConnect: Boolean?,
    val backgroundFolderImport: Boolean?,
)

internal data class NativePlanInitialValues(
    val startMode: String?,
    val raceDistance: String?,
    val targetDate: String?,
    val priority: String?,
    val currentWeeklyDistanceKm: String?,
    val currentRunsPerWeek: String?,
    val longestRecentRunKm: String?,
    val experience: String?,
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
    val activityOverflow: NativeActivityOverflow?,
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
    val feltHard: Boolean?,
    val pain: Boolean?,
    val extraPlanImpactConfirmed: Boolean?,
    val consequence: NativeConsequence?,
    val matchedWorkoutPurpose: String?,
    val matchedWorkoutDate: String?,
    val healthConnect: NativeHealthConnectActivity?,
)

internal data class NativeHealthConnectActivity(
    val mappingId: String?,
    val recordState: String?,
    val originLabel: String?,
    val recordedAt: String?,
    val duplicateCandidate: NativeDuplicateCandidate?,
)
internal data class NativeDuplicateCandidate(
    val activityId: String?,
    val activityDate: String?,
    val distanceMeters: Double?,
    val sourceLabel: String?,
)

internal data class NativeConsequence(
    val kind: String?,
    val appliedDecision: String?,
    val recommendedDecision: String?,
    val deviation: String?,
    val risk: String?,
    val planChangeAvailable: Boolean?,
    val options: List<String>,
)
internal data class NativeActivityOverflow(val limit: Int?, val truncated: Boolean?)
internal data class NativeOffsetPage(val total: Int?, val nextOffset: Int?, val offset: Int?)

internal data class NativePlanDetail(val weeks: List<NativeWeek>)
internal data class NativeWeek(
    val id: String?,
    val weekNumber: Int?,
    val startDate: String?,
    val targetDistanceMeters: Double?,
    val completedDistanceMeters: Double?,
    val risk: String?,
)
internal data class NativePlanTraceWeek(val id: String?, val weekNumber: Int?, val startDate: String?)
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
)
internal data class NativeWeekSummary(
    val weekNumber: Int?,
    val startDate: String?,
    val targetDistanceMeters: Double?,
    val completedDistanceMeters: Double?,
    val plannedRuns: Int?,
    val completedRuns: Int?,
    val missedRuns: Int?,
    val skippedRuns: Int?,
    val painFlags: Int?,
    val hardFlags: Int?,
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

internal data class NativeProfile(
    val timeZone: String?,
    val routeDataMode: String?,
    val sexForEstimates: String?,
    val ageYears: Int?,
    val heartRateSettingsSource: String?,
    val maxHeartRateBpm: Int?,
    val zone2FloorBpm: Int?,
    val zone3FloorBpm: Int?,
    val zone4FloorBpm: Int?,
    val zone5FloorBpm: Int?,
    val injuryFlags: NativeInjuryFlags?,
)
internal data class NativeInjuryFlags(
    val recentInjury: Boolean?,
    val currentPain: Boolean?,
    val recurringPain: Boolean?,
    val medicalRestriction: Boolean?,
    val notes: String?,
)
internal data class NativeHealthConnectStatus(
    val state: String?,
    val message: String?,
    val lastSyncAt: String?,
    val permissions: List<String>,
)
internal data class NativeAndroidDevice(
    val id: String?,
    val label: String?,
    val lastSeenAt: String?,
    val status: String?,
)
internal data class NativeImportSource(
    val id: String?,
    val label: String?,
    val enabled: Boolean?,
    val lastError: String?,
    val lastImportedAt: String?,
)
internal data class NativeAbout(val release: String?, val commit: String?, val serverOrigin: String?)

/** Action response and preview models keep apply payload separate from the presentation preview. */
internal data class NativeActionResponse(
    val ok: Boolean?,
    val message: String?,
    val error: String?,
    val preview: NativeActionPreviewDto?,
    val setupComplete: Boolean?,
)
internal data class NativeActionPreviewDto(
    val risk: String?,
    val weeklyLoadChangePercent: Double?,
    val spacingConflicts: List<NativeSpacingConflict>,
)
internal data class NativeSpacingConflict(
    val workoutId: String?,
    val scheduledDate: String?,
    val purpose: String?,
)
