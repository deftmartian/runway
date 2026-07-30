package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalActivityEvidenceReadModel
import dev.deftmartian.runway.data.LocalActivityReadModel
import dev.deftmartian.runway.data.LocalCalendarReadModel
import dev.deftmartian.runway.data.LocalHistoryReadModel
import dev.deftmartian.runway.data.LocalInboxReadModel
import dev.deftmartian.runway.data.LocalLoadReadModel
import dev.deftmartian.runway.data.LocalPlanHistoryReadModel
import dev.deftmartian.runway.data.LocalSettingsReadModel
import dev.deftmartian.runway.data.LocalStatsReadModel
import dev.deftmartian.runway.data.LocalWorkoutReadModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Explicit local-read-model to Compose-model projection; no transport compatibility layer. */
internal fun LocalCalendarReadModel.toNativeCalendar() = NativeCalendarPayload(
    onboardingRequired = activePlanId == null,
    hasActivePlan = activePlanId != null,
    calendar = NativeCalendar(
        month = LocalDate.ofEpochDay(fromEpochDay).toString().take(7),
        today = LocalDate.now().toString(),
        previousMonth = LocalDate.ofEpochDay(fromEpochDay).minusMonths(1).toString().take(7),
        nextMonth = LocalDate.ofEpochDay(fromEpochDay).plusMonths(1).toString().take(7),
        workouts = days.flatMap { it.workouts }.map(LocalWorkoutReadModel::toNativeWorkout),
        activities = days.flatMap { it.unlinkedActivities }.map(LocalActivityReadModel::toNativeActivity),
        feedback = feedback.map {
            NativeWorkoutFeedback(
                id = it.feedbackId,
                workoutId = it.workoutId,
                completedDistanceMeters = it.completedDistanceMeters?.toDouble(),
                completedDurationSeconds = it.completedDurationSeconds?.toDouble(),
                feltHard = it.feltHard,
                pain = it.pain,
                consequence = null,
                canDelete = true,
            )
        },
        activityOverflow = null,
    ),
    nextWorkout = days.flatMap { it.workouts }.firstOrNull { it.status == "planned" }?.toNativeWorkout(),
    activityCandidates = emptyList(),
)

internal fun LocalInboxReadModel.toNativeInbox() = NativeReviewPayload(
    candidates = emptyList(),
    activities = activities.map(LocalActivityReadModel::toNativeActivity),
    activityPage = if (hasMore) NativeOffsetPage(total = null, nextOffset = activities.size, offset = 0) else null,
    sources = emptyList(),
    androidDevices = emptyList(),
    routeDataMode = null,
)

internal fun LocalStatsReadModel.toNativeStats(): NativeStatsPayload {
    val activeWeeks = weeks.filter { it.planState.name == "ACTIVE" }
    val activePlanId = activeWeeks.firstOrNull()?.planId
    return NativeStatsPayload(
        onboardingRequired = activePlanId == null,
        active = activePlanId?.let { id ->
            NativePlanHistoryItem(
                plan = NativePlan(id, "active", null, null, activeWeeks.size, null, null, null, null, null),
                goal = null,
                summary = null,
            )
        },
        detail = NativePlanDetail(activeWeeks.map {
            NativeWeek(
                id = "${it.planId}:${it.weekOrdinal}", weekNumber = it.weekOrdinal,
                startDate = LocalDate.ofEpochDay(it.startEpochDay).toString(),
                targetDistanceMeters = it.current.distanceMeters?.toDouble(), targetDurationSeconds = it.current.durationSeconds?.toDouble(),
                completedDistanceMeters = it.actual.distanceMeters?.toDouble(), risk = null, hasMixedLoad = null,
            )
        }),
        history = NativeTrainingHistory(
            weeklySummaries = activeWeeks.map {
                NativeWeekSummary(
                    weekNumber = it.weekOrdinal, startDate = LocalDate.ofEpochDay(it.startEpochDay).toString(),
                    targetDistanceMeters = it.current.distanceMeters?.toDouble(), completedDistanceMeters = it.actual.distanceMeters?.toDouble(),
                    completedDurationSeconds = it.actual.durationSeconds?.toDouble(), plannedRuns = it.plannedRuns,
                    completedRuns = it.completedRuns, missedRuns = it.missedRuns, skippedRuns = 0,
                    painFlags = null, hardFlags = null, averagePaceSecondsPerKm = null, averageHeartRate = null,
                )
            },
            todayIso = LocalDate.now().toString(), currentSignal = null, hasAcceptedActivities = totalRuns > 0,
            recordedSummary = NativeRecordedHistorySummary(totalRuns, totalDistanceMeters.toDouble(), totalDurationSeconds.toDouble(), longestRunMeters?.toDouble(), null, null, null, null, null, null),
            heartRateSample = durationWeightedHeartRateBpm?.let { NativeHeartRateSample(null, null, null, null, it, null, null, null) },
        ),
        planTrace = activeWeeks.map {
            NativePlanTraceWeek("${it.planId}:${it.weekOrdinal}", it.weekOrdinal, LocalDate.ofEpochDay(it.startEpochDay).toString(), it.generated.distanceMeters?.toDouble(), it.generated.durationSeconds?.toDouble(), it.current.distanceMeters?.toDouble(), it.current.durationSeconds?.toDouble())
        },
        planHistory = null, phaseReview = null,
    )
}

internal fun LocalHistoryReadModel.toNativeHistory() = NativeHistoryPayload(
    onboardingRequired = false,
    history = NativePlanHistoryPage(plans.map(LocalPlanHistoryReadModel::toNativeHistoryItem), if (hasMorePlans) plans.size else null, LocalDate.now().toString()),
    activeItem = plans.firstOrNull { it.state.name == "ACTIVE" }?.toNativeHistoryItem(),
    phaseReview = null,
    offset = 0,
    pageSize = plans.size,
)

internal fun LocalPlanHistoryReadModel.toNativeHistoryDetail() = NativeHistoryDetailPayload(
    onboardingRequired = false,
    detail = NativeHistoryDetail(
        plan = NativeHistoryDetailPlan(planId, state.name.lowercase(), phase.name.lowercase(), LocalDate.ofEpochDay(startEpochDay).toString(), endEpochDay?.let { LocalDate.ofEpochDay(it).toString() }, null, null, completedAtEpochMillis?.toIsoDate(), archivedAtEpochMillis?.toIsoDate(), lifecycle.lastOrNull()?.note, null),
        goal = NativeHistoryDetailGoal(goalTitle, null, null),
        cutoffDate = LocalDate.now().toString(),
        timeline = lifecycle.map { NativeHistoryTimelineItem(it.eventType, it.eventType, it.occurredAtEpochMillis.toIsoDate(), null, null, it.note, null) },
        weeks = weeks.map { week ->
            NativeHistoryWeek(
                id = week.weekId,
                weekNumber = week.ordinal,
                startDate = LocalDate.ofEpochDay(week.startEpochDay).toString(),
                targetDistanceMeters = week.current.distanceMeters?.toDouble(),
                targetDurationSeconds = week.current.durationSeconds?.toDouble(),
                risk = week.riskAssessment,
                isDownWeek = week.isDownWeek,
                isTaper = week.isTaperWeek,
                workouts = week.workouts.map { workout ->
                    NativeHistoryWorkout(
                        id = workout.workoutId,
                        scheduledDate = LocalDate.ofEpochDay(workout.scheduledEpochDay).toString(),
                        type = workout.current.workoutType,
                        status = workout.status,
                        prescriptionKind = workout.current.prescriptionKind,
                        targetDistanceMeters = workout.current.load.distanceMeters?.toDouble(),
                        targetDurationSeconds = workout.current.load.durationSeconds?.toDouble(),
                        purpose = workout.current.purpose,
                        isRemoved = false,
                        result = workout.result?.let {
                            NativeHistoryResult(
                                source = it.source,
                                completedDistanceMeters = it.distanceMeters?.toDouble(),
                                completedDurationSeconds = it.durationSeconds?.toDouble(),
                                feltHard = it.feltHard,
                                pain = it.pain,
                                consequence = null,
                            )
                        },
                    )
                },
            )
        },
    ),
)

private fun LocalPlanHistoryReadModel.toNativeHistoryItem() = NativePlanHistoryItem(
    plan = NativePlan(planId, state.name.lowercase(), LocalDate.ofEpochDay(startEpochDay).toString(), endEpochDay?.let { LocalDate.ofEpochDay(it).toString() }, null, null, null, completedAtEpochMillis?.toIsoDate(), archivedAtEpochMillis?.toIsoDate(), lifecycle.lastOrNull()?.note),
    goal = NativeGoalSummary(goalId, goalTitle, null, null, null, state.name.lowercase(), null),
    summary = NativePlanSummary(plannedRuns, completedRuns, null, null, null, actual.distanceMeters?.toDouble()),
)

internal fun LocalSettingsReadModel.toNativeSettingsState() = NativeSettingsState(
    timeZone = profile?.timeZone.orEmpty(),
    routePrivacy = if (profile?.routeDataMode == "private") NativeRoutePrivacy.KeepPrivate else NativeRoutePrivacy.Discard,
    heartRate = NativeHeartRateProfile(
        source = when (profile?.heartRateSettingsSource) {
            "estimated" -> NativeHeartRateSource.Estimated
            "custom" -> NativeHeartRateSource.Custom
            else -> NativeHeartRateSource.NotConfigured
        },
        maxHeartRateBpm = profile?.maxHeartRateBpm,
        zone2FloorBpm = profile?.zone2FloorBpm,
        zone3FloorBpm = profile?.zone3FloorBpm,
        zone4FloorBpm = profile?.zone4FloorBpm,
        zone5FloorBpm = profile?.zone5FloorBpm,
    ),
    healthContext = NativeHealthContext(
        recentInjury = profile?.recentInjury == true,
        currentPain = profile?.currentPain == true,
        recurringPain = profile?.recurringPain == true,
        clinicianRestriction = profile?.medicalRestriction == true,
        notes = profile?.privateNotes.orEmpty(),
    ),
    appVersion = about.versionName ?: BuildConfig.VERSION_NAME,
    sourceCommit = about.buildRevision ?: BuildConfig.SOURCE_COMMIT,
)

/** Transitional mapper for the retained calendar/history Compose records. */
internal fun LocalSettingsReadModel.toNativeSettings() = NativeSettingsPayload(
    profile = profile?.let {
        NativeProfile(it.timeZone, it.routeDataMode, null, null, it.heartRateSettingsSource, it.maxHeartRateBpm, it.zone2FloorBpm, it.zone3FloorBpm, it.zone4FloorBpm, it.zone5FloorBpm, NativeInjuryFlags(it.recentInjury, it.currentPain, it.recurringPain, it.medicalRestriction, it.privateNotes))
    },
    healthConnect = null,
    androidDevices = emptyList(),
    sources = emptyList(),
    about = NativeAbout(about.versionName, about.buildRevision, null),
    accountSecurityAvailable = false,
)

internal fun LocalWorkoutReadModel.toNativeWorkout() = NativeWorkout(
    id = workoutId, weekId = null, weekNumber = weekOrdinal, scheduledDate = LocalDate.ofEpochDay(scheduledEpochDay).toString(),
    type = current.workoutType, status = status, targetDistanceMeters = current.load.distanceMeters?.toDouble(), targetDurationSeconds = current.load.durationSeconds?.toDouble(),
    prescriptionKind = current.prescriptionKind, intervalStructure = null, intensity = current.intensity, purpose = current.purpose, reason = current.reason,
    isRemoved = false, isEdited = isEdited, adjustment = null,
)

internal fun LocalActivityReadModel.toNativeActivity() = NativeActivity(
    id = activityId, workoutId = linkedWorkoutId, source = source, reviewState = reviewState,
    occurredDate = occurredAtEpochMillis.toIsoDate(), activityDate = occurredAtEpochMillis.toIsoDate(),
    distanceMeters = load.distanceMeters?.toDouble(), durationSeconds = load.durationSeconds?.toDouble(), averagePaceSecondsPerKm = null,
    averageHeartRate = evidence.averageHeartRateBpm, maxHeartRate = evidence.maxHeartRateBpm, heartRateSummary = null,
    feltHard = feltHard, pain = pain, extraPlanImpactConfirmed = null, consequence = null,
    routeSummary = NativeRouteSummary(evidence.routeSourcePointCount, evidence.routeStartEndRedacted, evidence.route.any { it.elevationMeters != null }, evidence.routeRetained),
    matchedWorkoutPurpose = null, matchedWorkoutDate = null, healthConnect = null,
)

internal fun LocalActivityEvidenceReadModel.toNativeEvidence() = NativeActivityEvidence(
    routeTrace = if (routeRetained) NativeRouteTrace(routeSourcePointCount, route.map { NativeRoutePoint(it.latitudeE6, it.longitudeE6, it.elapsedSeconds, it.segmentOrdinal, it.speedMetersPerSecond) }) else null,
    heartRateSeries = if (heartRateSeriesRetained) NativeHeartRateSeries(heartRateSourceSampleCount, heartRate.map { NativeHeartRatePoint(it.elapsedSeconds, it.beatsPerMinute) }) else null,
    averageCadence = averageCadenceSpm,
    disclosure = NativeActivityDisclosure(routeRetained, routeSourcePointCount, routeStartEndRedacted, route.any { it.elevationMeters != null }, heartRateSeriesRetained, heartRateSourceSampleCount),
)

private fun Long.toIsoDate(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()
