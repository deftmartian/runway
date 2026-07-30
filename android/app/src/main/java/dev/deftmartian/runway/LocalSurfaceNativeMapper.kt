package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalActivityEvidenceReadModel
import dev.deftmartian.runway.data.LocalActivityReadModel
import dev.deftmartian.runway.data.LocalActivitySummaryReadModel
import dev.deftmartian.runway.data.LocalCalendarReadModel
import dev.deftmartian.runway.data.LocalConsequenceReadModel
import dev.deftmartian.runway.data.LocalHistoryReadModel
import dev.deftmartian.runway.data.LocalInboxReadModel
import dev.deftmartian.runway.data.LocalPhaseReviewReadModel
import dev.deftmartian.runway.data.LocalPlanHistoryReadModel
import dev.deftmartian.runway.data.LocalPlanProvenance
import dev.deftmartian.runway.data.LocalPlanState
import dev.deftmartian.runway.data.LocalSettingsReadModel
import dev.deftmartian.runway.data.LocalStatsReadModel
import dev.deftmartian.runway.data.LocalTimedIntervalStructureReadModel
import dev.deftmartian.runway.data.LocalWorkoutLinkCandidateReadModel
import dev.deftmartian.runway.data.LocalWorkoutReadModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Projects persisted local read models into the Compose presentation model. */
internal fun LocalCalendarReadModel.toNativeCalendar(): NativeCalendarPayload {
    val zone = ZoneId.of(timeZone)
    val today = LocalDate.ofEpochDay(todayEpochDay)
    val workouts = days.flatMap { it.workouts }
        .sortedWith(compareBy(LocalWorkoutReadModel::scheduledEpochDay, LocalWorkoutReadModel::workoutId))
    val workoutById = workouts.associateBy(LocalWorkoutReadModel::workoutId)
    val activities = (
        days.flatMap { it.unlinkedActivities } +
            workouts.mapNotNull(LocalWorkoutReadModel::actual)
        ).distinctBy(LocalActivityReadModel::activityId)
        .map { activity ->
            activity.toNativeActivity(zone, activity.linkedWorkoutId?.let(workoutById::get))
        }
    val nativeWorkouts = workouts.map(LocalWorkoutReadModel::toNativeWorkout)
    return NativeCalendarPayload(
        onboardingRequired = activePlanId == null,
        hasActivePlan = activePlanId != null,
        calendar = NativeCalendar(
            month = YearMonth.from(LocalDate.ofEpochDay(fromEpochDay)).toString(),
            today = today.toString(),
            previousMonth = YearMonth.from(LocalDate.ofEpochDay(fromEpochDay)).minusMonths(1).toString(),
            nextMonth = YearMonth.from(LocalDate.ofEpochDay(fromEpochDay)).plusMonths(1).toString(),
            workouts = nativeWorkouts,
            activities = activities,
            feedback = feedback.map { record ->
                val workout = workoutById[record.workoutId]
                NativeWorkoutFeedback(
                    id = record.feedbackId,
                    workoutId = record.workoutId,
                    completedDistanceMeters = record.completedDistanceMeters?.toDouble(),
                    completedDurationSeconds = record.completedDurationSeconds?.toDouble(),
                    feltHard = record.feltHard,
                    pain = record.pain,
                    consequence = workout?.consequence?.toNativeConsequence(),
                    canDelete =
                        record.sourceActivityId == null &&
                            workout != null &&
                            workout.scheduledEpochDay <= todayEpochDay &&
                            workout.actual == null &&
                            workout.consequence?.appliedDecision == null,
                )
            },
        ),
        nextWorkout = nextWorkout?.toNativeWorkout(),
        activityCandidates = emptyList(),
    )
}

internal fun LocalInboxReadModel.toNativeInbox(): NativeReviewPayload {
    val zone = ZoneId.of(timeZone)
    return NativeReviewPayload(
        candidates = linkCandidates.map(LocalWorkoutLinkCandidateReadModel::toNativeWorkout),
        activities = activities.map { it.toNativeActivity(zone) },
        healthConnectChanges = pendingHealthConnect.map { pending ->
            NativeHealthConnectChange(
                mappingId = pending.mappingId,
                provider = pending.provider,
                recordId = pending.externalRecordId,
                state = pending.state,
                current = pending.current?.toNativeSummary(zone),
                proposed = pending.proposed?.toNativeSummary(zone),
            )
        },
        hasMore = hasMore,
    )
}

internal fun LocalStatsReadModel.toNativeStats(): NativeStatsPayload {
    val activeWeeks = weeks.filter { it.planState.name == "ACTIVE" }
    val activePlanId = activeWeeks.firstOrNull()?.planId
    val activePlannedRuns = activeWeeks.sumOf { it.plannedRuns }
    val activeCompletedRuns = activeWeeks.sumOf { it.completedRuns }
    val currentTotals = recordedTotals.firstOrNull { it.provenance == LocalPlanProvenance.ACTIVE }
    val archivedTotals = recordedTotals
        .filter { it.provenance in setOf(LocalPlanProvenance.COMPLETED, LocalPlanProvenance.ARCHIVED) }
        .fold(0 to 0) { total, item -> (total.first + item.runs) to (total.second + item.distanceMeters) }
    val unlinkedTotals = recordedTotals.firstOrNull { it.provenance == LocalPlanProvenance.UNLINKED }
    val zoneToday = LocalDate.ofEpochDay(todayEpochDay).toString()
    val activeItem = activePlanId?.let { id ->
        NativePlanHistoryItem(
            plan = NativePlan(
                id = id,
                status = "active",
                startDate = activeWeeks.minOfOrNull { it.startEpochDay }?.let(LocalDate::ofEpochDay)?.toString(),
                targetDate = activeWeeks.maxOfOrNull { it.startEpochDay + 6 }?.let(LocalDate::ofEpochDay)?.toString(),
                weeks = activeWeeks.size,
                risk = null,
                summaryKind = null,
                completedAt = null,
                archivedAt = null,
                lifecycleReason = null,
            ),
            goal = null,
            summary = NativePlanSummary(
                plannedRuns = activePlannedRuns,
                completedRuns = activeCompletedRuns,
                missedRuns = activeWeeks.sumOf { it.missedRuns },
                skippedRuns = 0,
                painFlags = activeWeeks.sumOf { it.painFlags },
                completedDistanceMeters = activeWeeks.sumOf { it.actual.distanceMeters ?: 0 }.toDouble(),
            ),
        )
    }
    return NativeStatsPayload(
        onboardingRequired = activePlanId == null,
        active = activeItem,
        detail = NativePlanDetail(
            activeWeeks.map {
                NativeWeek(
                    id = "${it.planId}:${it.weekOrdinal}",
                    weekNumber = it.weekOrdinal,
                    startDate = LocalDate.ofEpochDay(it.startEpochDay).toString(),
                    targetDistanceMeters = it.current.distanceMeters?.toDouble(),
                    targetDurationSeconds = it.current.durationSeconds?.toDouble(),
                    completedDistanceMeters = it.actual.distanceMeters?.toDouble(),
                    risk = null,
                    hasMixedLoad =
                        it.current.distanceMeters != null &&
                            it.current.durationSeconds != null,
                )
            },
        ),
        history = NativeTrainingHistory(
            weeklySummaries = activeWeeks.map {
                NativeWeekSummary(
                    weekNumber = it.weekOrdinal,
                    startDate = LocalDate.ofEpochDay(it.startEpochDay).toString(),
                    targetDistanceMeters = it.current.distanceMeters?.toDouble(),
                    completedDistanceMeters = it.actual.distanceMeters?.toDouble(),
                    completedDurationSeconds = it.actual.durationSeconds?.toDouble(),
                    plannedRuns = it.plannedRuns,
                    completedRuns = it.completedRuns,
                    missedRuns = it.missedRuns,
                    skippedRuns = it.skippedRuns,
                    painFlags = it.painFlags,
                    hardFlags = it.hardFlags,
                    averagePaceSecondsPerKm = it.weightedPaceSecondsPerKilometre,
                    averageHeartRate = it.durationWeightedHeartRateBpm,
                )
            },
            todayIso = zoneToday,
            currentSignal = currentSignal?.let {
                NativeCurrentSignal(
                    risk = it.risk,
                    reasons = it.reasons,
                    source = it.source,
                    healthNotice = it.healthNotice?.let { notice ->
                        NativeHealthNotice(
                            level = notice.level,
                            heading = notice.heading,
                            message = notice.message,
                        )
                    },
                )
            },
            hasAcceptedActivities = totalRuns > 0,
            recordedSummary = NativeRecordedHistorySummary(
                totalRuns = totalRuns,
                totalDistanceMeters = totalDistanceMeters.toDouble(),
                totalDurationSeconds = totalDurationSeconds.toDouble(),
                longestRunMeters = longestRunMeters?.toDouble(),
                currentPlanRuns = currentTotals?.runs,
                currentPlanDistanceMeters = currentTotals?.distanceMeters?.toDouble(),
                archivedPlanRuns = archivedTotals.first,
                archivedPlanDistanceMeters = archivedTotals.second.toDouble(),
                unlinkedRuns = unlinkedTotals?.runs,
                unlinkedDistanceMeters = unlinkedTotals?.distanceMeters?.toDouble(),
            ),
            heartRateSample = durationWeightedHeartRateBpm?.let {
                NativeHeartRateSample(
                    windowDays = null,
                    windowStart = null,
                    windowEnd = null,
                    sampleCount = null,
                    averageHeartRate = it,
                    highZoneSeconds = null,
                    latest = null,
                    oldest = null,
                )
            },
        ),
        planTrace = activeWeeks.map {
            NativePlanTraceWeek(
                id = "${it.planId}:${it.weekOrdinal}",
                weekNumber = it.weekOrdinal,
                startDate = LocalDate.ofEpochDay(it.startEpochDay).toString(),
                recommendedDistanceMeters = it.generated.distanceMeters?.toDouble(),
                recommendedDurationSeconds = it.generated.durationSeconds?.toDouble(),
                currentDistanceMeters = it.current.distanceMeters?.toDouble(),
                currentDurationSeconds = it.current.durationSeconds?.toDouble(),
            )
        },
        planHistory = null,
        phaseReview = phaseReview?.toNativePhaseReview(),
    )
}

internal fun LocalHistoryReadModel.toNativeHistory() = NativeHistoryPayload(
    onboardingRequired = false,
    history = NativePlanHistoryPage(
        items = plans.map { it.toNativeHistoryItem(timeZone) },
        nextOffset = if (hasMorePlans) plans.size else null,
        today = LocalDate.ofEpochDay(todayEpochDay).toString(),
    ),
    activeItem = plans.firstOrNull { it.state.name == "ACTIVE" }?.toNativeHistoryItem(timeZone),
    phaseReview = phaseReview?.toNativePhaseReview(),
    offset = 0,
    pageSize = plans.size,
    activitiesOutsidePlans = unlinkedActivities.map {
        it.toNativeActivity(ZoneId.of(timeZone))
    },
)

internal fun LocalPlanHistoryReadModel.toNativeHistoryDetail(
    timeZone: String,
    todayEpochDay: Long,
): NativeHistoryDetailPayload {
    val today = LocalDate.ofEpochDay(todayEpochDay).toString()
    val cutoffDate = if (state == LocalPlanState.ACTIVE) {
        today
    } else {
        listOfNotNull(completedAtEpochMillis, archivedAtEpochMillis)
            .minOrNull()
            ?.toIsoDate(timeZone)
            ?: today
    }
    return NativeHistoryDetailPayload(
        onboardingRequired = false,
        detail = NativeHistoryDetail(
            plan = NativeHistoryDetailPlan(
                id = planId,
                status = state.name.lowercase(),
                phase = phase.name.lowercase(),
                startDate = LocalDate.ofEpochDay(startEpochDay).toString(),
                targetDate = endEpochDay?.let(LocalDate::ofEpochDay)?.toString(),
                weeks = weeks.size,
                risk = weeks.mapNotNull { it.riskAssessment }.lastOrNull(),
                completedAt = completedAtEpochMillis?.toIsoDate(timeZone),
                archivedAt = archivedAtEpochMillis?.toIsoDate(timeZone),
                lifecycleReason = lifecycle.lastOrNull()?.note,
            ),
            goal = NativeHistoryDetailGoal(goalTitle, null, null),
            cutoffDate = cutoffDate,
            timeline = (
                lifecycle.map {
                    it.occurredAtEpochMillis to NativeHistoryTimelineItem(
                        id = "${it.eventType}:${it.occurredAtEpochMillis}",
                        triggerType = it.eventType,
                        createdAt = it.occurredAtEpochMillis.toIsoDate(timeZone),
                        reversedAt = null,
                        reversalReason = null,
                        reason = it.note,
                        newState = null,
                    )
                } +
                    adjustments.map { adjustment ->
                        adjustment.createdAtEpochMillis to NativeHistoryTimelineItem(
                            id = adjustment.id,
                            triggerType = adjustment.triggerType,
                            createdAt = adjustment.createdAtEpochMillis.toIsoDate(timeZone),
                            reversedAt = adjustment.reversedAtEpochMillis?.toIsoDate(timeZone),
                            reversalReason = adjustment.reversalReason,
                            reason = adjustment.reason,
                            newState = if (
                                adjustment.scheduledEpochDay != null ||
                                adjustment.workoutType != null ||
                                adjustment.prescriptionKind != null ||
                                adjustment.distanceMeters != null ||
                                adjustment.durationSeconds != null ||
                                adjustment.removed != null
                            ) {
                                NativeHistoryWorkoutState(
                                    scheduledDate = adjustment.scheduledEpochDay
                                        ?.let(LocalDate::ofEpochDay)
                                        ?.toString(),
                                    type = adjustment.workoutType,
                                    prescriptionKind = adjustment.prescriptionKind,
                                    targetDistanceMeters = adjustment.distanceMeters?.toDouble(),
                                    targetDurationSeconds = adjustment.durationSeconds?.toDouble(),
                                    isRemoved = adjustment.removed,
                                )
                            } else {
                                null
                            },
                        )
                    }
                )
                .sortedBy { it.first }
                .map { it.second },
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
                            intervalStructure = workout.current.intervalStructure
                                ?.toNativeIntervalStructure(),
                            result = workout.result?.let {
                                NativeHistoryResult(
                                    source = it.source,
                                    completedDistanceMeters = it.distanceMeters?.toDouble(),
                                    completedDurationSeconds = it.durationSeconds?.toDouble(),
                                    feltHard = it.feltHard,
                                    pain = it.pain,
                                    consequence = workout.consequence?.toNativeConsequence(),
                                )
                            },
                        )
                    },
                    extraActivities = week.extraActivities.map {
                        it.toNativeActivity(ZoneId.of(timeZone))
                    },
                )
            },
        ),
    )
}

private fun LocalPlanHistoryReadModel.toNativeHistoryItem(timeZone: String) = NativePlanHistoryItem(
    plan = NativePlan(
        id = planId,
        status = state.name.lowercase(),
        startDate = LocalDate.ofEpochDay(startEpochDay).toString(),
        targetDate = endEpochDay?.let(LocalDate::ofEpochDay)?.toString(),
        weeks = weeks.size,
        risk = weeks.mapNotNull { it.riskAssessment }.lastOrNull(),
        summaryKind = null,
        completedAt = completedAtEpochMillis?.toIsoDate(timeZone),
        archivedAt = archivedAtEpochMillis?.toIsoDate(timeZone),
        lifecycleReason = lifecycle.lastOrNull()?.note,
    ),
    goal = NativeGoalSummary(
        id = goalId,
        title = goalTitle,
        targetDate = endEpochDay?.let(LocalDate::ofEpochDay)?.toString(),
        state = state.name.lowercase(),
        risk = null,
    ),
    summary = NativePlanSummary(
        plannedRuns = plannedRuns,
        completedRuns = completedRuns,
        missedRuns = missedRuns,
        skippedRuns = skippedRuns,
        painFlags = painFlags,
        completedDistanceMeters = actual.distanceMeters?.toDouble(),
    ),
)

internal fun LocalSettingsReadModel.toNativeSettingsState() = NativeSettingsState(
    timeZone = profile?.timeZone.orEmpty(),
    routePrivacy =
        if (profile?.routeDataMode == "private") NativeRoutePrivacy.KeepPrivate
        else NativeRoutePrivacy.Discard,
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

internal fun LocalWorkoutReadModel.toNativeWorkout() = NativeWorkout(
    id = workoutId,
    weekId = null,
    weekNumber = weekOrdinal,
    scheduledDate = LocalDate.ofEpochDay(scheduledEpochDay).toString(),
    type = current.workoutType,
    status = status,
    targetDistanceMeters = current.load.distanceMeters?.toDouble(),
    targetDurationSeconds = current.load.durationSeconds?.toDouble(),
    prescriptionKind = current.prescriptionKind,
    intervalStructure = current.intervalStructure?.toNativeIntervalStructure(),
    generatedIntervalStructure = generated.intervalStructure?.toNativeIntervalStructure(),
    intensity = current.intensity,
    purpose = current.purpose,
    reason = current.reason,
    isRemoved = false,
    isEdited = isEdited,
    adjustment = adjustment?.let {
        NativeAdjustment(
            id = it.adjustmentId,
            kind = it.kind,
            reason = "Manual workout change",
        )
    },
)

private fun LocalWorkoutLinkCandidateReadModel.toNativeWorkout() = NativeWorkout(
    id = workoutId,
    weekId = null,
    weekNumber = null,
    scheduledDate = LocalDate.ofEpochDay(scheduledEpochDay).toString(),
    type = current.workoutType,
    status = "planned",
    targetDistanceMeters = current.load.distanceMeters?.toDouble(),
    targetDurationSeconds = current.load.durationSeconds?.toDouble(),
    prescriptionKind = current.prescriptionKind,
    intervalStructure = current.intervalStructure?.toNativeIntervalStructure(),
    intensity = current.intensity,
    purpose = current.purpose,
    reason = current.reason,
    isRemoved = false,
    isEdited = false,
    adjustment = null,
)

internal fun LocalActivityReadModel.toNativeActivity(
    timeZone: ZoneId,
    matchedWorkout: LocalWorkoutReadModel? = null,
) = NativeActivity(
    id = activityId,
    workoutId = linkedWorkoutId,
    source = source,
    reviewState = reviewState,
    occurredDate = occurredAtEpochMillis.toIsoDate(timeZone),
    activityDate = occurredAtEpochMillis.toIsoDate(timeZone),
    distanceMeters = load.distanceMeters?.toDouble(),
    durationSeconds = load.durationSeconds?.toDouble(),
    averagePaceSecondsPerKm =
        load.distanceMeters?.takeIf { it > 0 }?.let { distance ->
            load.durationSeconds?.toDouble()?.times(1_000)?.div(distance)
        },
    averageHeartRate = evidence.averageHeartRateBpm,
    maxHeartRate = evidence.maxHeartRateBpm,
    heartRateSummary = null,
    feltHard = feltHard,
    pain = pain,
    extraPlanImpactConfirmed = extraPlanImpactConfirmed,
    consequence = consequence?.toNativeConsequence(),
    routeSummary = NativeRouteSummary(
        pointCount = evidence.routeSourcePointCount,
        startEndRedacted = evidence.routeStartEndRedacted,
        hasElevation = evidence.route.any { it.elevationMeters != null },
        traceRetained = evidence.routeRetained,
    ),
    matchedWorkoutPurpose = matchedWorkout?.current?.purpose,
    matchedWorkoutDate = matchedWorkout?.scheduledEpochDay?.let(LocalDate::ofEpochDay)?.toString(),
)

internal fun LocalActivityEvidenceReadModel.toNativeEvidence() = NativeActivityEvidence(
    routeTrace =
        if (routeRetained) {
            NativeRouteTrace(
                routeSourcePointCount,
                route.map {
                    NativeRoutePoint(
                        it.latitudeE6,
                        it.longitudeE6,
                        it.elapsedSeconds,
                        it.segmentOrdinal,
                        it.speedMetersPerSecond,
                    )
                },
            )
        } else {
            null
        },
    heartRateSeries =
        if (heartRateSeriesRetained) {
            NativeHeartRateSeries(
                heartRateSourceSampleCount,
                heartRate.map { NativeHeartRatePoint(it.elapsedSeconds, it.beatsPerMinute) },
            )
        } else {
            null
        },
    averageCadence = averageCadenceSpm,
    disclosure = NativeActivityDisclosure(
        routeTraceRetained = routeRetained,
        routePointCount = routeSourcePointCount,
        startEndRedacted = routeStartEndRedacted,
        hasElevation = route.any { it.elevationMeters != null },
        heartRateSeriesRetained = heartRateSeriesRetained,
        heartRateSampleCount = heartRateSourceSampleCount,
    ),
)

private fun LocalConsequenceReadModel.toNativeConsequence() = NativeConsequence(
    kind = classification,
    appliedDecision = appliedDecision,
    recommendedDecision = recommendedDecision,
    deviation = deviation,
    risk = risk,
    planChangeAvailable = planChangeAvailable,
    options = options,
    comparisonStatus = comparisonStatus,
)

private fun LocalTimedIntervalStructureReadModel.toNativeIntervalStructure() = TimedIntervalStructureDto(
    warmupSeconds = warmupSeconds,
    cooldownSeconds = cooldownSeconds,
    blocks = blocks.map { block ->
        TimedBlockDto(
            repetitions = block.repetitions,
            segments = block.segments.map { segment ->
                TimedSegmentDto(kind = segment.kind, durationSeconds = segment.durationSeconds)
            },
        )
    },
)

private fun LocalPhaseReviewReadModel.toNativePhaseReview() = NativePhaseReview(
    planId = planId,
    phase = phase.name.lowercase(),
    goalKind = goalKind,
    goalTitle = null,
    baseline =
        if (ready) {
            NativePhaseBaseline(
                activityCount = activityCount,
                totalDurationSeconds = totalDurationSeconds?.toDouble(),
                totalDistanceMeters = totalDistanceMeters?.toDouble(),
                longestActivityMeters = longestActivityMeters?.toDouble(),
                weeklyDistanceMeters = weeklyDistanceMeters?.toDouble(),
                runsPerWeek = runsPerWeek,
            )
        } else {
            null
        },
    recommended = recommendedTransition,
    options = transitionOptions,
    preferredLongRunDay = preferredLongRunDay,
    racePlan = racePlan?.let { preview ->
        NativeRacePlan(
            previewToken = preview.token,
            risk = preview.risk.name.lowercase(),
            weeks = preview.weeks,
            startDate = LocalDate.ofEpochDay(preview.startEpochDay).toString(),
            targetDate = LocalDate.ofEpochDay(preview.targetEpochDay).toString(),
            summary = NativeDistanceSummary(
                baselineMeters = preview.baselineMeters.toDouble(),
                peakMeters = preview.peakMeters.toDouble(),
                requiredWeeklyIncreasePercent = preview.requiredWeeklyIncreasePercent,
                defaultWeeklyIncreasePercent = preview.defaultWeeklyIncreasePercent,
                longRunPeakMeters = preview.longRunPeakMeters.toDouble(),
                warnings = preview.warnings,
            ),
            warnings = preview.warnings,
        )
    },
)

private fun LocalActivitySummaryReadModel.toNativeSummary(zone: ZoneId) = NativeActivitySummary(
    date = occurredAtEpochMillis.toIsoDate(zone),
    distanceMeters = load.distanceMeters?.toDouble(),
    durationSeconds = load.durationSeconds?.toDouble(),
)

private fun Long.toIsoDate(timeZone: String): String =
    toIsoDate(ZoneId.of(timeZone))

private fun Long.toIsoDate(timeZone: ZoneId): String =
    Instant.ofEpochMilli(this).atZone(timeZone).toLocalDate().toString()
