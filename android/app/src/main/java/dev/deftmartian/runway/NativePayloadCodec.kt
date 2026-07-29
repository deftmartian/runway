package dev.deftmartian.runway

import org.json.JSONArray
import org.json.JSONObject

/**
 * The only Android-native presentation boundary that knows about org.json.  It is intentionally
 * manual rather than reflection based: payload compatibility is visible in code and malformed or
 * newly-added server fields cannot crash a screen.
 */
internal object NativePayloadCodec {
    fun decodeView(view: String, source: String): NativeViewPayload? =
        runCatching { decodeView(view, JSONObject(source)) }.getOrNull()

    fun decodeView(view: String, source: JSONObject): NativeViewPayload? = when (view) {
        "bootstrap" -> source.bootstrap()
        "onboarding" -> source.onboarding()
        "calendar" -> source.calendarView()
        "review" -> source.review()
        "activity-trace" -> source.activityEvidence()
        "stats" -> source.stats()
        "history" -> source.history()
        "history-detail" -> source.historyDetailPayload()
        "settings" -> source.settings()
        "account-security" -> source.accountSecurity()
        else -> null
    }

    fun decodeAction(source: JSONObject): NativeActionResponse = NativeActionResponse(
        ok = source.optionalBoolean("ok"),
        message = source.optionalString("message"),
        error = source.optionalString("error"),
        preview = source.optionalObject("preview")?.actionPreview(),
        setupComplete = source.optionalBoolean("setupComplete"),
    )

    fun decodeAction(source: String): NativeActionResponse? =
        runCatching { decodeAction(JSONObject(source)) }.getOrNull()

    fun decodeAccountOperation(source: String): NativeAccountOperationResponse? =
        runCatching {
            val payload = JSONObject(source)
            NativeAccountOperationResponse(
                ok = payload.optionalBoolean("ok"),
                message = payload.optionalString("message"),
                error = payload.optionalString("error"),
                sessionToken = payload.optionalString("sessionToken"),
                totpUri = payload.optionalString("totpUri"),
                recoveryCodes = payload.optionalArray("recoveryCodes").strings(),
                accountDeleted = payload.optionalBoolean("accountDeleted"),
            )
        }.getOrNull()

    fun encodeCommand(command: MobileCommand): String = when (command) {
        is CreatePlanCommand -> JSONObject()
            .put("goalKind", command.goalKind)
            .put("startMode", command.startMode)
            .put("raceDistance", command.raceDistance)
            .put("targetDate", command.targetDate)
            .put("priority", command.priority)
            .put("currentWeeklyDistanceKm", command.currentWeeklyDistanceKm)
            .put("currentRunsPerWeek", command.currentRunsPerWeek)
            .put("longestRecentRunKm", command.longestRecentRunKm)
            .put("experience", command.experience)
            .put("calibrationDurationMinutes", command.calibrationDurationMinutes)
            .put("availability", JSONArray(command.availability))
            .put("preferredLongRunDay", command.preferredLongRunDay)
            .put("timeZone", command.timeZone)
            .put("recentInjury", command.recentInjury)
            .put("currentPain", command.currentPain)
            .put("recurringPain", command.recurringPain)
            .put("medicalRestriction", command.medicalRestriction)
            .put("injuryNotes", command.injuryNotes)
            .put("confirmConcentratedSchedule", command.confirmConcentratedSchedule)
            .put("confirmReplace", command.confirmReplace)
        is RecordFeedbackCommand -> JSONObject()
            .put("workoutId", command.workoutId)
            .put("status", command.status)
            .put("feltHard", command.feltHard)
            .put("pain", command.pain)
            .put("choice", command.choice)
            .apply {
                command.completedDistanceKm?.let { put("completedDistanceKm", it) }
                command.completedDurationMinutes?.let { put("completedDurationMinutes", it) }
            }
        is DeleteFeedbackCommand -> idBody("workoutId", command.workoutId)
        is RecordManualRunCommand -> JSONObject()
            .put("occurredDate", command.occurredDate)
            .put("feltHard", command.feltHard)
            .put("pain", command.pain)
            .apply {
                command.distanceKm?.let { put("distanceKm", it) }
                command.durationMinutes?.let { put("durationMinutes", it) }
            }
        is LinkActivityCommand -> JSONObject()
            .put("activityId", command.activityId)
            .put("workoutId", command.workoutId)
        is UnlinkActivityCommand -> idBody("activityId", command.activityId)
        is ConfirmActivityExtraCommand -> idBody("activityId", command.activityId)
        is UpdateActivityFeedbackCommand -> JSONObject()
            .put("activityId", command.activityId)
            .put("feltHard", command.feltHard)
            .put("pain", command.pain)
        is DeleteActivityCommand -> idBody("activityId", command.activityId)
        is ResolveHealthConnectRecordCommand -> JSONObject()
            .put("mappingId", command.mappingId)
            .put("decision", command.decision.wireValue)
        is ResolveHealthConnectDuplicateCommand -> JSONObject()
            .put("mappingId", command.mappingId)
            .put("decision", command.decision.wireValue)
        is ApplyPlanDecisionCommand -> JSONObject()
            .put("source", command.source)
            .put("sourceId", command.sourceId)
            .put("decision", command.decision)
            .put("confirmRisk", command.confirmRisk)
        is PreviewPlanDecisionCommand -> JSONObject()
            .put("source", command.source)
            .put("sourceId", command.sourceId)
            .put("decision", command.decision)
            .put("confirmRisk", false)
        is PreviewWorkoutEditCommand -> workoutMutation(command.mutation)
            .put("workoutId", command.workoutId)
        is ApplyWorkoutEditCommand -> workoutMutation(command.mutation)
            .put("workoutId", command.workoutId)
        is PreviewWorkoutAddCommand -> workoutMutation(command.mutation)
        is ApplyWorkoutAddCommand -> workoutMutation(command.mutation)
        is PreviewWorkoutRemovalCommand -> idBody("workoutId", command.workoutId)
        is RemoveWorkoutCommand -> idBody("workoutId", command.workoutId)
        is ResetWorkoutCommand -> idBody("workoutId", command.workoutId)
        is UndoWorkoutAdjustmentCommand -> idBody("adjustmentId", command.adjustmentId)
        CompletePlanCommand,
        ConfirmPhaseBaselineCommand,
        ContinueBeginnerPhaseCommand,
        -> JSONObject()
        ArchivePlanCommand -> JSONObject().put("confirmation", "ARCHIVE")
        is UpdateTimeZoneCommand -> JSONObject().put("timeZone", command.timeZone)
        is UpdateRouteDataModeCommand -> JSONObject()
            .put("routeDataMode", command.routeDataMode)
        is UpdateHealthContextCommand -> JSONObject()
            .put("recentInjury", command.recentInjury)
            .put("currentPain", command.currentPain)
            .put("recurringPain", command.recurringPain)
            .put("medicalRestriction", command.medicalRestriction)
            .put("injuryNotes", command.injuryNotes)
        is UpdateTrainingProfileCommand -> JSONObject()
            .put("sexForEstimates", command.sexForEstimates)
            .put("heartRateSettingsSource", command.heartRateSettingsSource)
            .put("maxHeartRateBpm", command.maxHeartRateBpm)
            .put("zone2FloorBpm", command.zone2FloorBpm)
            .put("zone3FloorBpm", command.zone3FloorBpm)
            .put("zone4FloorBpm", command.zone4FloorBpm)
            .put("zone5FloorBpm", command.zone5FloorBpm)
            .apply { command.ageYears?.let { put("ageYears", it) } }
        is ConnectNextcloudCommand -> JSONObject()
            .put("label", command.label)
            .put("shareUrl", command.shareUrl)
            .put("sharePassword", command.sharePassword)
        is TestNextcloudCommand -> idBody("sourceId", command.sourceId)
        is SyncNextcloudCommand -> idBody("sourceId", command.sourceId)
        is DisconnectNextcloudCommand -> idBody("sourceId", command.sourceId)
        is RevokeAndroidDeviceCommand -> idBody("deviceId", command.deviceId)
        DeleteImportedActivityDataCommand -> JSONObject()
            .put("confirmation", "DELETE IMPORTED ACTIVITY DATA")
    }.toString()

    private fun JSONObject.bootstrap() = NativeBootstrapPayload(
        user = optionalObject("user")?.user(),
        setupComplete = optionalBoolean("setupComplete"),
        timeZone = optionalString("timeZone"), release = optionalString("release"),
        commit = optionalString("commit"), serverOrigin = optionalString("serverOrigin"),
        androidApi = optionalInt("androidApi"), features = optionalObject("features")?.features(),
    )

    private fun JSONObject.onboarding() = NativeOnboardingPayload(
        initialValues = optionalObject("initialValues")?.initialValues(),
        minimumTargetDate = optionalString("minimumTargetDate"),
        minimumCalibrationTargetDate = optionalString("minimumCalibrationTargetDate"),
        minimumFoundationTargetDate = optionalString("minimumFoundationTargetDate"),
        maximumTargetDate = optionalString("maximumTargetDate"), activeGoal = optionalObject("activeGoal")?.goal(),
    )

    private fun JSONObject.calendarView() = NativeCalendarPayload(
        onboardingRequired = optionalBoolean("onboardingRequired"),
        hasActivePlan = optionalObject("activePlan") != null,
        calendar = optionalObject("calendar")?.calendar(),
        nextWorkout = optionalObject("nextWorkout")?.workout(),
        activityCandidates = optionalArray("activityCandidates").objects { it.workout() },
    )

    private fun JSONObject.review() = NativeReviewPayload(
        candidates = optionalArray("candidates").objects { it.workout() },
        activities = optionalArray("activities").objects { it.activity() },
        activityPage = optionalObject("activityPage")?.offsetPage(),
        sources = optionalArray("sources").objects { it.importSource() },
        androidDevices = optionalArray("androidDevices").objects { it.androidDevice() },
        routeDataMode = optionalString("routeDataMode"),
    )
    private fun JSONObject.activityEvidence() = NativeActivityEvidencePayload(
        optionalString("activityId"),
        NativeActivityEvidence(
            routeTrace = optionalObject("routeTrace")?.routeTrace(),
            heartRateSeries = optionalObject("heartRateSeries")?.heartRateSeries(),
            averageCadence = optionalInt("averageCadence"),
            disclosure = optionalObject("disclosure")?.activityDisclosure(),
        ),
    )

    private fun JSONObject.stats() = NativeStatsPayload(
        onboardingRequired = optionalBoolean("onboardingRequired"),
        active = optionalObject("active")?.planHistoryItem(),
        detail = optionalObject("detail")?.planDetail(),
        history = optionalObject("history")?.trainingHistory(),
        planTrace = optionalArray("planTrace").objects { it.planTraceWeek() },
        planHistory = optionalObject("planHistory")?.planHistoryPage(),
        phaseReview = optionalObject("phaseReview")?.phaseReview(),
    )

    private fun JSONObject.history() = NativeHistoryPayload(
        onboardingRequired = optionalBoolean("onboardingRequired"),
        history = optionalObject("history")?.planHistoryPage(),
        activeItem = optionalObject("activeItem")?.planHistoryItem(),
        phaseReview = optionalObject("phaseReview")?.phaseReview(),
        offset = optionalInt("offset"), pageSize = optionalInt("pageSize"),
    )

    private fun JSONObject.historyDetailPayload() = NativeHistoryDetailPayload(
        onboardingRequired = optionalBoolean("onboardingRequired"),
        detail = optionalObject("detail")?.historyDetail(),
    )

    private fun JSONObject.historyDetail() = NativeHistoryDetail(
        plan = optionalObject("plan")?.historyDetailPlan(),
        goal = optionalObject("goal")?.historyDetailGoal(),
        cutoffDate = optionalString("cutoffDate"),
        timeline = optionalArray("timeline").objects { it.historyTimelineItem() },
        weeks = optionalArray("weeks").objects { it.historyWeek() },
    )

    private fun JSONObject.historyDetailPlan() = NativeHistoryDetailPlan(
        id = optionalString("id"), status = optionalString("status"), phase = optionalString("phase"),
        startDate = optionalString("startDate"), targetDate = optionalString("targetDate"),
        weeks = optionalInt("weeks"), risk = optionalString("risk"), completedAt = optionalString("completedAt"),
        archivedAt = optionalString("archivedAt"), lifecycleReason = optionalString("lifecycleReason"),
        summary = optionalObject("summary")?.historyPlanSummary(),
    )
    private fun JSONObject.historyPlanSummary() = NativeHistoryPlanSummary(
        kind = optionalString("kind"),
        requiredWeeklyIncreasePercent = optionalDouble("requiredWeeklyIncreasePercent"),
        defaultWeeklyIncreasePercent = optionalDouble("defaultWeeklyIncreasePercent"),
    )
    private fun JSONObject.historyDetailGoal() = NativeHistoryDetailGoal(
        title = optionalString("title"), distance = optionalString("distance"), priority = optionalString("priority"),
    )
    private fun JSONObject.historyTimelineItem() = NativeHistoryTimelineItem(
        id = optionalString("id"), triggerType = optionalString("triggerType"), createdAt = optionalString("createdAt"),
        reversedAt = optionalString("reversedAt"), reversalReason = optionalString("reversalReason"),
        reason = optionalString("reason"), newState = optionalObject("newState")?.historyWorkoutState(),
    )
    private fun JSONObject.historyWorkoutState() = NativeHistoryWorkoutState(
        scheduledDate = optionalString("scheduledDate"), type = optionalString("type"),
        prescriptionKind = optionalString("prescriptionKind"), targetDistanceMeters = optionalDouble("targetDistanceMeters"),
        targetDurationSeconds = optionalDouble("targetDurationSeconds"), isRemoved = optionalBoolean("isRemoved"),
    )
    private fun JSONObject.historyWeek() = NativeHistoryWeek(
        id = optionalString("id"), weekNumber = optionalInt("weekNumber"), startDate = optionalString("startDate"),
        targetDistanceMeters = optionalDouble("targetDistanceMeters"), targetDurationSeconds = optionalDouble("targetDurationSeconds"),
        risk = optionalString("risk"), isDownWeek = optionalBoolean("isDownWeek"), isTaper = optionalBoolean("isTaper"),
        workouts = optionalArray("workouts").objects { it.historyWorkout() },
    )
    private fun JSONObject.historyWorkout() = NativeHistoryWorkout(
        id = optionalString("id"), scheduledDate = optionalString("scheduledDate"), type = optionalString("type"),
        status = optionalString("status"), prescriptionKind = optionalString("prescriptionKind"),
        targetDistanceMeters = optionalDouble("targetDistanceMeters"), targetDurationSeconds = optionalDouble("targetDurationSeconds"),
        purpose = optionalString("purpose"), isRemoved = optionalBoolean("isRemoved"),
        result = optionalObject("result")?.historyResult(),
    )
    private fun JSONObject.historyResult() = NativeHistoryResult(
        source = optionalString("source"), completedDistanceMeters = optionalDouble("completedDistanceMeters"),
        completedDurationSeconds = optionalDouble("completedDurationSeconds"), feltHard = optionalBoolean("feltHard"),
        pain = optionalBoolean("pain"), consequence = optionalObject("consequence")?.consequence(),
    )

    private fun JSONObject.settings() = NativeSettingsPayload(
        profile = optionalObject("profile")?.profile(),
        healthConnect = optionalObject("healthConnect")?.healthConnectStatus(),
        androidDevices = optionalArray("androidDevices").objects { it.androidDevice() },
        sources = optionalArray("sources").objects { it.importSource() },
        about = optionalObject("about")?.about(),
        accountSecurityAvailable = optionalBoolean("accountSecurityAvailable"),
    )

    private fun JSONObject.accountSecurity() = NativeAccountSecurityPayload(
        authentication = optionalObject("authentication")?.authenticationSecurity(),
        passkeys = optionalArray("passkeys").objects { it.passkeySecurity() },
        sessions = optionalObject("sessions")?.sessionSecurity(),
        importDevices = optionalArray("importDevices").objects { it.accountImportDevice() },
    )

    private fun JSONObject.passkeySecurity() = NativePasskeySecurity(
        id = optionalString("id"), name = optionalString("name"),
        deviceType = optionalString("deviceType"), backedUp = optionalBoolean("backedUp"),
        createdAt = optionalString("createdAt"),
    )

    private fun JSONObject.user() = NativeUser(
        optionalString("id"),
        optionalString("name"),
        optionalString("email"),
    )
    private fun JSONObject.features() = NativeFeatures(
        optionalBoolean("nativeUi"),
        optionalBoolean("deviceAuthorization"),
        optionalBoolean("healthConnect"),
        optionalBoolean("backgroundFolderImport"),
    )
    private fun JSONObject.initialValues() = NativePlanInitialValues(
        startMode = optionalString("startMode"),
        raceDistance = optionalString("raceDistance"),
        targetDate = optionalString("targetDate"),
        priority = optionalString("priority"),
        currentWeeklyDistanceKm = optionalString("currentWeeklyDistanceKm"),
        currentRunsPerWeek = optionalString("currentRunsPerWeek"),
        longestRecentRunKm = optionalString("longestRecentRunKm"),
        experience = optionalString("experience"),
        calibrationDurationMinutes = optionalString("calibrationDurationMinutes"),
        preferredLongRunDay = optionalString("preferredLongRunDay"),
        timeZone = optionalString("timeZone"),
        availability = optionalArray("availability").ints().filter { it in 0..6 },
        recentInjury = optionalBoolean("recentInjury"),
        currentPain = optionalBoolean("currentPain"),
        recurringPain = optionalBoolean("recurringPain"),
        medicalRestriction = optionalBoolean("medicalRestriction"),
        injuryNotes = optionalString("injuryNotes"),
    )
    private fun JSONObject.goal() = NativeGoalSummary(
        optionalString("id"),
        optionalString("title"),
        optionalString("distance"),
        optionalString("targetDate"),
        optionalString("priority"),
        optionalString("state"),
        optionalString("risk"),
    )
    private fun JSONObject.calendar() = NativeCalendar(
        optionalString("month"),
        optionalString("today"),
        optionalString("previousMonth"),
        optionalString("nextMonth"),
        optionalArray("workouts").objects { it.workout() },
        optionalArray("activities").objects { it.activity() },
        optionalArray("feedback").objects { it.feedback() },
        optionalObject("activityOverflow")?.activityOverflow(),
    )
    private fun JSONObject.workout() = NativeWorkout(
        optionalString("id"),
        optionalString("weekId"),
        optionalInt("weekNumber"),
        optionalString("scheduledDate"),
        optionalString("type"),
        optionalString("status"),
        optionalDouble("targetDistanceMeters"),
        optionalDouble("targetDurationSeconds"),
        optionalString("prescriptionKind"),
        optionalObject("intervalStructure")?.intervalStructure(),
        optionalString("intensity"),
        optionalString("purpose"),
        optionalString("reason"),
        optionalBoolean("isRemoved"),
        optionalBoolean("isEdited"),
        optionalObject("adjustment")?.adjustment(),
    )
    private fun JSONObject.intervalStructure() = TimedIntervalStructureDto(
        optionalInt("warmupSeconds"),
        optionalInt("cooldownSeconds"),
        optionalArray("blocks").objects { it.intervalBlock() },
    )
    private fun JSONObject.intervalBlock() = TimedBlockDto(
        optionalInt("repetitions"),
        optionalArray("segments").objects { it.intervalSegment() },
    )
    private fun JSONObject.intervalSegment() = TimedSegmentDto(
        optionalString("kind"),
        optionalInt("durationSeconds"),
    )
    private fun JSONObject.adjustment() = NativeAdjustment(
        optionalString("id"),
        optionalString("kind"),
        optionalString("reason"),
    )
    private fun JSONObject.feedback() = NativeWorkoutFeedback(
        optionalString("id"),
        optionalString("workoutId"),
        optionalDouble("completedDistanceMeters"),
        optionalDouble("completedDurationSeconds"),
        optionalBoolean("feltHard"),
        optionalBoolean("pain"),
        optionalObject("consequence")?.consequence(),
        optionalBoolean("canDelete"),
    )
    private fun JSONObject.activity() = NativeActivity(
        optionalString("id"),
        optionalString("workoutId"),
        optionalString("source"),
        optionalString("reviewState"),
        optionalString("occurredDate"),
        optionalString("activityDate"),
        optionalDouble("distanceMeters"),
        optionalDouble("durationSeconds"),
        optionalDouble("averagePaceSecondsPerKm"),
        optionalInt("averageHeartRate"),
        optionalInt("maxHeartRate"),
        optionalObject("heartRateSummary")?.heartRateSummary(),
        optionalBoolean("feltHard"),
        optionalBoolean("pain"),
        optionalBoolean("extraPlanImpactConfirmed"),
        optionalObject("consequence")?.consequence(),
        optionalObject("routeSummary")?.routeSummary(),
        optionalString("matchedWorkoutPurpose"),
        optionalString("matchedWorkoutDate"),
        optionalObject("healthConnect")?.healthConnectActivity(),
    )
    private fun JSONObject.heartRateSummary() = NativeHeartRateSummary(
        optionalInt("highSeconds"),
        optionalDouble("highShare"),
        optionalObject("secondsByZone")?.heartRateZones(),
        optionalString("settingsSource"),
    )
    private fun JSONObject.heartRateZones() = NativeHeartRateZones(
        optionalInt("z1"), optionalInt("z2"), optionalInt("z3"), optionalInt("z4"), optionalInt("z5"),
    )
    private fun JSONObject.routeSummary() = NativeRouteSummary(
        optionalInt("pointCount"), optionalBoolean("startEndRedacted"), optionalBoolean("hasElevation"),
        optionalBoolean("traceRetained"),
    )
    private fun JSONObject.routeTrace() = NativeRouteTrace(
        optionalInt("sourcePointCount"),
        optionalArray("points").objects { it.routePoint() },
    )
    private fun JSONObject.routePoint() = NativeRoutePoint(
        optionalInt("latitudeE6"), optionalInt("longitudeE6"), optionalInt("elapsedSeconds"),
        optionalInt("segmentIndex"), optionalDouble("speedMetersPerSecond"),
    )
    private fun JSONObject.heartRateSeries() = NativeHeartRateSeries(
        optionalInt("sourceSampleCount"), optionalArray("points").objects { it.heartRatePoint() },
    )
    private fun JSONObject.heartRatePoint() = NativeHeartRatePoint(
        optionalInt("elapsedSeconds"), optionalInt("bpm"),
    )
    private fun JSONObject.activityDisclosure() = NativeActivityDisclosure(
        optionalBoolean("routeTraceRetained"), optionalInt("routePointCount"),
        optionalBoolean("startEndRedacted"), optionalBoolean("hasElevation"),
        optionalBoolean("heartRateSeriesRetained"), optionalInt("heartRateSampleCount"),
    )
    private fun JSONObject.healthConnectActivity() = NativeHealthConnectActivity(
        optionalString("mappingId"),
        optionalString("recordState"),
        optionalString("originLabel"),
        optionalString("recordedAt"),
        optionalObject("duplicateCandidate")?.duplicateCandidate(),
    )
    private fun JSONObject.duplicateCandidate() = NativeDuplicateCandidate(
        optionalString("activityId"),
        optionalString("activityDate"),
        optionalDouble("distanceMeters"),
        optionalString("sourceLabel"),
    )
    private fun JSONObject.consequence() = NativeConsequence(
        optionalString("kind"),
        optionalString("appliedDecision"),
        optionalString("recommendedDecision"),
        optionalString("deviation"),
        optionalString("risk"),
        optionalBoolean("planChangeAvailable"),
        optionalArray("options").strings(),
        optionalString("comparisonStatus"),
    )
    private fun JSONObject.activityOverflow() = NativeActivityOverflow(
        optionalInt("limit"),
        optionalBoolean("truncated"),
    )
    private fun JSONObject.offsetPage() = NativeOffsetPage(
        optionalInt("total"),
        optionalInt("nextOffset"),
        optionalInt("offset"),
    )
    private fun JSONObject.planDetail() = NativePlanDetail(
        optionalArray("weeks").objects { it.week() },
    )
    private fun JSONObject.week() = NativeWeek(
        optionalString("id"),
        optionalInt("weekNumber"),
        optionalString("startDate"),
        optionalDouble("targetDistanceMeters"),
        optionalDouble("targetDurationSeconds"),
        optionalDouble("completedDistanceMeters"),
        optionalString("risk"),
        optionalBoolean("hasMixedLoad"),
    )
    private fun JSONObject.planTraceWeek() = NativePlanTraceWeek(
        optionalString("id"),
        optionalInt("weekNumber"),
        optionalString("startDate"),
        optionalDouble("recommendedDistanceMeters"),
        optionalDouble("recommendedDurationSeconds"),
        optionalDouble("currentDistanceMeters"),
        optionalDouble("currentDurationSeconds"),
    )
    private fun JSONObject.phaseReview() = NativePhaseReview(
        optionalString("planId"),
        optionalString("phase"),
        optionalString("goalKind"),
        optionalString("goalTitle"),
        optionalObject("baseline")?.phaseBaseline(),
        optionalString("recommended"),
        optionalArray("options").strings(),
        optionalInt("preferredLongRunDay"),
        optionalObject("racePlan")?.racePlan(),
    )
    private fun JSONObject.phaseBaseline() = NativePhaseBaseline(
        optionalInt("activityCount"),
        optionalDouble("totalDurationSeconds"),
        optionalDouble("totalDistanceMeters"),
        optionalDouble("longestActivityMeters"),
        optionalDouble("weeklyDistanceMeters"),
        optionalDouble("runsPerWeek"),
    )
    private fun JSONObject.racePlan() = NativeRacePlan(
        optionalString("risk"),
        optionalInt("weeks"),
        optionalString("startDate"),
        optionalString("targetDate"),
        optionalObject("summary")?.distanceSummary(),
        optionalArray("warnings").strings(),
    )
    private fun JSONObject.distanceSummary() = NativeDistanceSummary(
        optionalDouble("baselineMeters"),
        optionalDouble("peakMeters"),
        optionalDouble("requiredWeeklyIncreasePercent"),
        optionalDouble("defaultWeeklyIncreasePercent"),
        optionalDouble("longRunPeakMeters"),
        optionalArray("warnings").strings(),
    )
    private fun JSONObject.trainingHistory() = NativeTrainingHistory(
        optionalArray("weeklySummaries").objects { it.weekSummary() },
        optionalString("todayIso"),
        optionalObject("currentSignal")?.currentSignal(),
        optionalBoolean("hasAcceptedActivities"),
        optionalObject("recordedSummary")?.recordedHistorySummary(),
        optionalObject("heartRateSample")?.heartRateSample(),
        optionalArray("recentFeedback")?.length() ?: 0,
    )
    private fun JSONObject.currentSignal() = NativeCurrentSignal(
        optionalString("risk"), optionalArray("reasons").strings(), optionalString("source"),
        optionalObject("healthNotice")?.healthNotice(),
    )
    private fun JSONObject.healthNotice() = NativeHealthNotice(
        optionalString("level"), optionalString("heading"), optionalString("message"),
    )
    private fun JSONObject.recordedHistorySummary() = NativeRecordedHistorySummary(
        optionalInt("totalRuns"),
        optionalDouble("totalDistanceMeters"),
        optionalDouble("totalDurationSeconds"),
        optionalDouble("longestRunMeters"),
        optionalInt("currentPlanRuns"),
        optionalDouble("currentPlanDistanceMeters"),
        optionalInt("archivedPlanRuns"),
        optionalDouble("archivedPlanDistanceMeters"),
        optionalInt("unlinkedRuns"),
        optionalDouble("unlinkedDistanceMeters"),
    )
    private fun JSONObject.heartRateSample() = NativeHeartRateSample(
        optionalInt("windowDays"),
        optionalString("windowStart"),
        optionalString("windowEnd"),
        optionalInt("sampleCount"),
        optionalInt("averageHeartRate"),
        optionalDouble("highZoneSeconds"),
        optionalObject("latest")?.heartRateObservation(),
        optionalObject("oldest")?.heartRateObservation(),
    )
    private fun JSONObject.heartRateObservation() = NativeHeartRateObservation(
        optionalString("activityDate"),
        optionalInt("averageHeartRate"),
        optionalInt("maxHeartRate"),
    )
    private fun JSONObject.weekSummary() = NativeWeekSummary(
        optionalInt("weekNumber"),
        optionalString("startDate"),
        optionalDouble("targetDistanceMeters"),
        optionalDouble("completedDistanceMeters"),
        optionalDouble("completedDurationSeconds"),
        optionalInt("plannedRuns"),
        optionalInt("completedRuns"),
        optionalInt("missedRuns"),
        optionalInt("skippedRuns"),
        optionalInt("painFlags"),
        optionalInt("hardFlags"),
        optionalDouble("averagePaceSecondsPerKm"),
        optionalInt("averageHeartRate"),
    )
    private fun JSONObject.planHistoryPage() = NativePlanHistoryPage(
        optionalArray("items").objects { it.planHistoryItem() },
        optionalInt("nextOffset"),
        optionalString("today"),
    )
    private fun JSONObject.planHistoryItem() = NativePlanHistoryItem(
        optionalObject("plan")?.plan(),
        optionalObject("goal")?.goal(),
        optionalObject("summary")?.planSummary(),
    )
    private fun JSONObject.plan() = NativePlan(
        optionalString("id"),
        optionalString("status"),
        optionalString("startDate"),
        optionalString("targetDate"),
        optionalInt("weeks"),
        optionalString("risk"),
        optionalObject("summary")?.optionalString("kind"),
        optionalString("completedAt"),
        optionalString("archivedAt"),
        optionalString("lifecycleReason"),
    )
    private fun JSONObject.planSummary() = NativePlanSummary(
        optionalInt("plannedRuns"),
        optionalInt("completedRuns"),
        optionalInt("missedRuns"),
        optionalInt("skippedRuns"),
        optionalInt("painFlags"),
        optionalDouble("completedDistanceMeters"),
    )
    private fun JSONObject.profile() = NativeProfile(
        optionalString("timeZone"),
        optionalString("routeDataMode"),
        optionalString("sexForEstimates"),
        optionalInt("ageYears"),
        optionalString("heartRateSettingsSource"),
        optionalInt("maxHeartRateBpm"),
        optionalInt("zone2FloorBpm"),
        optionalInt("zone3FloorBpm"),
        optionalInt("zone4FloorBpm"),
        optionalInt("zone5FloorBpm"),
        optionalObject("injuryFlags")?.injuryFlags(),
    )
    private fun JSONObject.injuryFlags() = NativeInjuryFlags(
        optionalBoolean("recentInjury"),
        optionalBoolean("currentPain"),
        optionalBoolean("recurringPain"),
        optionalBoolean("medicalRestriction"),
        optionalString("notes"),
    )
    private fun JSONObject.healthConnectStatus() = NativeHealthConnectStatus(
        optionalString("state"),
        optionalString("message"),
        optionalString("lastSyncAt"),
        optionalArray("permissions").strings(),
    )
    private fun JSONObject.androidDevice() = NativeAndroidDevice(
        optionalString("id"),
        optionalString("label"),
        optionalString("lastSeenAt"),
        optionalString("status"),
    )
    private fun JSONObject.importSource() = NativeImportSource(
        optionalString("id"),
        optionalString("label"),
        optionalBoolean("enabled"),
        optionalString("lastError"),
        optionalString("lastImportedAt"),
    )
    private fun JSONObject.about() = NativeAbout(
        optionalString("release"),
        optionalString("commit"),
        optionalString("serverOrigin"),
    )
    private fun JSONObject.authenticationSecurity() = NativeAuthenticationSecurity(
        optionalBoolean("localPassword"), optionalBoolean("oidc"), optionalBoolean("twoFactor"),
        optionalInt("passkeyCount"),
    )
    private fun JSONObject.sessionSecurity() = NativeSessionSecurity(
        optionalInt("activeCount"), optionalBoolean("currentIsNative"),
        optionalBoolean("requiresFreshSession"),
        optionalArray("items").objects { it.accountSession() },
    )
    private fun JSONObject.accountSession() = NativeAccountSession(
        optionalString("id"), optionalString("client"), optionalBoolean("current"),
        optionalString("createdAt"), optionalString("updatedAt"), optionalString("expiresAt"),
    )
    private fun JSONObject.accountImportDevice() = NativeAccountImportDevice(
        optionalString("id"), optionalString("label"), optionalString("lastSeenAt"),
        optionalString("lastImportedAt"), optionalString("expiresAt"),
    )
    private fun JSONObject.actionPreview() = NativeActionPreviewDto(
        optionalString("operation"),
        optionalString("risk"),
        optionalDouble("weeklyLoadChangePercent"),
        optionalArray("spacingConflicts").objects { it.spacingConflict() },
        optionalObject("recommended")?.workoutPreviewPrescription(),
        optionalObject("current")?.workoutPreviewPrescription(),
        optionalObject("proposed")?.workoutPreviewPrescription(),
        optionalArray("workoutChanges").objects { it.workoutPreviewChange() },
        optionalArray("weekChanges").objects { it.workoutPreviewWeek() },
        optionalDouble("projectedRampPercent"),
        optionalString("projectedRampRisk"),
        optionalArray("guardrails").objects { it.workoutPreviewGuardrail() },
        optionalObject("consequenceDecision")?.consequenceDecisionPreview(),
    )
    private fun JSONObject.workoutPreviewPrescription() = NativeWorkoutPreviewPrescription(
        optionalString("scheduledDate"), optionalString("type"), optionalString("prescriptionKind"),
        optionalDouble("targetDistanceMeters"), optionalDouble("targetDurationSeconds"), optionalString("purpose"),
    )
    private fun JSONObject.workoutPreviewChange() = NativeWorkoutPreviewChange(
        optionalString("workoutId"), optionalBoolean("isSelected"),
        optionalObject("before")?.workoutPreviewPrescription(), optionalObject("after")?.workoutPreviewPrescription(),
        optionalDouble("relativeChangePercent"), optionalDouble("changeShareOfWeekPercent"), optionalString("risk"),
    )
    private fun JSONObject.workoutPreviewWeek() = NativeWorkoutPreviewWeek(
        optionalString("weekId"), optionalInt("weekNumber"), optionalDouble("distanceBeforeMeters"),
        optionalDouble("distanceAfterMeters"), optionalDouble("durationBeforeSeconds"), optionalDouble("durationAfterSeconds"),
    )
    private fun JSONObject.workoutPreviewGuardrail() = NativeWorkoutPreviewGuardrail(
        optionalString("kind"), optionalString("label"), optionalString("description"),
    )
    private fun JSONObject.consequenceDecisionPreview() = NativeConsequenceDecisionPreview(
        optionalString("decision"), optionalArray("changes").objects { it.consequenceDecisionChange() },
    )
    private fun JSONObject.consequenceDecisionChange() = NativeConsequenceDecisionChange(
        optionalString("workoutId"), optionalString("scheduledDate"), optionalString("purpose"),
        optionalObject("before")?.workoutPreviewPrescription(), optionalObject("after")?.workoutPreviewPrescription(),
    )
    private fun JSONObject.spacingConflict() = NativeSpacingConflict(
        optionalString("workoutId"),
        optionalString("scheduledDate"),
        optionalString("purpose"),
    )
    private fun idBody(key: String, value: String) = JSONObject().put(key, value)
    private fun workoutMutation(mutation: WorkoutMutation) = JSONObject()
        .put("scheduledDate", mutation.scheduledDate)
        .put("type", mutation.type)
        .put("prescriptionKind", mutation.prescriptionKind)
        .put("targetDistanceMeters", mutation.targetDistanceMeters)
        .put("targetDurationSeconds", mutation.targetDurationSeconds ?: JSONObject.NULL)
        .put(
            "intervalStructure",
            mutation.intervalStructure?.toJson() ?: JSONObject.NULL,
        )
        .put("intensity", mutation.intensity)
        .put("purpose", mutation.purpose)
        .put("userReason", mutation.userReason)
        .put("rebalance", mutation.rebalance)
        .put("confirmRisk", mutation.confirmRisk)
    private fun TimedIntervalStructureDto.toJson() = JSONObject()
        .put("warmupSeconds", warmupSeconds ?: 0)
        .put("cooldownSeconds", cooldownSeconds ?: 0)
        .put(
            "blocks",
            JSONArray(
                blocks.map { block ->
                    JSONObject()
                        .put("repetitions", block.repetitions ?: 1)
                        .put(
                            "segments",
                            JSONArray(
                                block.segments.map { segment ->
                                    JSONObject()
                                        .put("kind", segment.kind.orEmpty())
                                        .put("durationSeconds", segment.durationSeconds ?: 0)
                                },
                            ),
                        )
                },
            ),
        )

    private fun JSONObject.optionalObject(key: String): JSONObject? =
        if (has(key) && !isNull(key)) optJSONObject(key) else null
    private fun JSONObject.optionalArray(key: String): JSONArray? =
        if (has(key) && !isNull(key)) optJSONArray(key) else null
    private fun JSONObject.optionalString(key: String): String? =
        if (has(key) && !isNull(key)) get(key) as? String else null
    private fun JSONObject.optionalBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key) && get(key) is Boolean) optBoolean(key) else null
    private fun JSONObject.optionalInt(key: String): Int? =
        if (has(key) && !isNull(key) && get(key) is Number) optInt(key) else null
    private fun JSONObject.optionalDouble(key: String): Double? =
        if (has(key) && !isNull(key) && get(key) is Number) {
            optDouble(key).takeUnless(Double::isNaN)
        } else {
            null
        }
    private inline fun <T> JSONArray?.objects(map: (JSONObject) -> T): List<T> = buildList {
        if (this@objects == null) return@buildList
        for (index in 0 until this@objects.length()) {
            this@objects.optJSONObject(index)?.let { add(map(it)) }
        }
    }
    private fun JSONArray?.strings(): List<String> = buildList {
        if (this@strings == null) return@buildList
        for (index in 0 until this@strings.length()) {
            if (!this@strings.isNull(index)) add(this@strings.optString(index))
        }
    }
    private fun JSONArray?.ints(): List<Int> = buildList {
        if (this@ints == null) return@buildList
        for (index in 0 until this@ints.length()) (this@ints.opt(index) as? Number)?.toInt()?.let(::add)
    }
}
