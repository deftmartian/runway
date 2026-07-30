package dev.deftmartian.runway

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.deftmartian.runway.data.LocalActivityEvidenceReadModel
import dev.deftmartian.runway.data.LocalCalendarReadModel
import dev.deftmartian.runway.data.LocalHistoryReadModel
import dev.deftmartian.runway.data.LocalInboxReadModel
import dev.deftmartian.runway.data.LocalSettingsReadModel
import dev.deftmartian.runway.data.LocalStatsReadModel
import dev.deftmartian.runway.data.LocalActivityReviewResult
import dev.deftmartian.runway.data.LocalManualRunCommand
import dev.deftmartian.runway.data.LocalPlanSetupResult
import dev.deftmartian.runway.data.LocalProfileUpdateResult
import dev.deftmartian.runway.data.LocalWorkoutFeedbackCommand
import dev.deftmartian.runway.data.LocalPlanEndRequest
import dev.deftmartian.runway.data.LocalWorkoutChangeRequest
import dev.deftmartian.runway.data.ApplyLocalWorkoutChangeCommand
import dev.deftmartian.runway.domain.WorkoutProposal
import dev.deftmartian.runway.domain.WorkoutType
import dev.deftmartian.runway.domain.PrescriptionKind
import dev.deftmartian.runway.data.RouteDataMode
import dev.deftmartian.runway.data.SexForEstimate
import dev.deftmartian.runway.data.HeartRateSettingsSource
import dev.deftmartian.runway.data.LocalHeartRateProfile
import dev.deftmartian.runway.data.LocalHealthContext
import dev.deftmartian.runway.domain.FeedbackStatus
import dev.deftmartian.runway.domain.PlanDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.security.MessageDigest

internal enum class NativeDestination(
    val label: String,
    val view: String,
    val iconRes: Int,
    val primaryNavigation: Boolean = true,
    val navigationParent: NativeDestination? = null,
) {
    Setup("Set up", "onboarding", R.drawable.ic_nav_calendar, primaryNavigation = false),
    Calendar("Calendar", "calendar", R.drawable.ic_nav_calendar),
    Inbox("Inbox", "review", R.drawable.ic_nav_inbox),
    Stats("Stats", "stats", R.drawable.ic_nav_stats),
    History("History", "history", R.drawable.ic_nav_history),
    Settings("Settings", "settings", R.drawable.ic_nav_settings),
    HistoryDetail(
        "Plan record",
        "history-detail",
        R.drawable.ic_nav_history,
        primaryNavigation = false,
        navigationParent = History,
    ),
}

internal fun NativeDestination.primaryNavigationDestination(): NativeDestination? =
    generateSequence(this) { it.navigationParent }.firstOrNull(NativeDestination::primaryNavigation)

internal data class NativeNotice(val message: String, val isError: Boolean = false)
internal sealed interface RunwayUiState {
    data object Loading : RunwayUiState
    data class Ready(
        val bootstrap: NativeBootstrapPayload,
        val destination: NativeDestination,
        val payload: Any?,
        val loading: Boolean,
        val actionPending: Boolean = false,
        val notice: NativeNotice? = null,
        val completedAction: String? = null,
        val activityEvidence: Map<String, NativeActivityEvidence> = emptyMap(),
        val activityEvidenceLoading: Set<String> = emptySet(),
        val activityEvidenceFailures: Set<String> = emptySet(),
        val workoutPreview: LocalWorkoutChangePreview? = null,
    ) : RunwayUiState

    data class Failed(val message: String) : RunwayUiState
}

/** Local-only app coordinator. It deliberately has no session, server, or browser state. */
internal class RunwayViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.runwayServices
    private val mutableState = MutableStateFlow<RunwayUiState>(RunwayUiState.Loading)
    private var calendarMonth: YearMonth = YearMonth.now()
    private var history: LocalHistoryReadModel? = null
    private var evidenceByActivity: Map<String, LocalActivityEvidenceReadModel> = emptyMap()

    val state: StateFlow<RunwayUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun selectDestination(destination: NativeDestination) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.destination == destination && ready.payload != null) return
        mutableState.value = ready.copy(destination = destination, payload = null, loading = true, notice = null)
        load(destination)
    }

    fun loadCalendarMonth(month: String) {
        calendarMonth = runCatching { YearMonth.parse(month) }.getOrDefault(calendarMonth)
        selectDestination(NativeDestination.Calendar)
        load(NativeDestination.Calendar)
    }

    fun loadMoreHistory() = refresh()
    fun loadMoreInbox() = refresh()

    fun openHistoryDetail(planId: String) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        val item = history?.plans?.firstOrNull { it.planId == planId }
        mutableState.value = ready.copy(
            destination = NativeDestination.HistoryDetail,
            loading = false,
            payload = item?.toNativeHistoryDetail(),
            notice = if (item == null) NativeNotice("That local plan record is no longer available.", true) else null,
        )
    }

    fun loadActivityTrace(activityId: String) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (activityId in ready.activityEvidenceLoading) return
        mutableState.value = ready.copy(activityEvidenceLoading = ready.activityEvidenceLoading + activityId)
        viewModelScope.launch {
            val evidence = withContext(Dispatchers.IO) { services.surfaces.activityEvidence(activityId) }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            mutableState.value = if (evidence == null) current.copy(
                activityEvidenceLoading = current.activityEvidenceLoading - activityId,
                activityEvidenceFailures = current.activityEvidenceFailures + activityId,
                notice = NativeNotice("This activity no longer has local evidence.", true),
            ) else current.copy(
                activityEvidenceLoading = current.activityEvidenceLoading - activityId,
                activityEvidence = current.activityEvidence + (activityId to evidence.evidence.toNativeEvidence()),
            )
        }
    }

    fun refresh() {
        val destination = (mutableState.value as? RunwayUiState.Ready)?.destination ?: NativeDestination.Calendar
        load(destination)
    }

    fun submitAction(command: MobileCommand) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { execute(command) } }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            result.onSuccess { message ->
                mutableState.value = current.copy(actionPending = false, completedAction = command.action, notice = NativeNotice(message))
                refresh()
            }.onFailure { error ->
                mutableState.value = current.copy(actionPending = false, notice = NativeNotice(error.message ?: "That local change could not be saved.", true))
            }
        }
    }

    fun dismissWorkoutPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(workoutPreview = null)
    }

    fun applyWorkoutPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        val pending = ready.workoutPreview ?: return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                services.workoutChanges.apply(
                    pending.planId,
                    ApplyLocalWorkoutChangeCommand(
                        adjustmentId = "workout-adjustment-${pending.actionId}",
                        decisionId = "workout-decision-${pending.actionId}",
                        request = pending.request,
                        expectedPreviewToken = pending.prepared.previewToken,
                        riskConfirmed = true,
                        nowEpochMillis = now,
                    ),
                    localToday(), hasInjuryRisk(),
                )
            } }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            result.onSuccess {
                mutableState.value = current.copy(actionPending = false, workoutPreview = null, completedAction = "apply_workout_change", notice = NativeNotice("Workout change applied to your local plan."))
                refresh()
            }.onFailure {
                mutableState.value = current.copy(actionPending = false, notice = NativeNotice(it.message ?: "The workout preview is stale. Review the current plan again.", true))
            }
        }
    }

    fun updateTimeZone(value: String) = mutateSetting { services.profile.updateTimeZone(value) }
    fun updateRoutePrivacy(value: NativeRoutePrivacy) = mutateSetting { services.privacy.updateRouteDataMode(if (value == NativeRoutePrivacy.KeepPrivate) RouteDataMode.Private else RouteDataMode.Discard) }
    fun updateHeartRate(value: NativeHeartRateProfile) = mutateSetting {
        services.profile.updateHeartRateProfile(LocalHeartRateProfile(
            sexForEstimate = SexForEstimate.NotSpecified, ageYears = null,
            source = if (value.source == NativeHeartRateSource.Custom) HeartRateSettingsSource.Custom else HeartRateSettingsSource.Estimated,
            maxHeartRateBpm = requireNotNull(value.maxHeartRateBpm), zone2FloorBpm = requireNotNull(value.zone2FloorBpm),
            zone3FloorBpm = requireNotNull(value.zone3FloorBpm), zone4FloorBpm = requireNotNull(value.zone4FloorBpm), zone5FloorBpm = requireNotNull(value.zone5FloorBpm),
        ))
    }
    fun updateHealthContext(value: NativeHealthContext) = mutateSetting {
        services.profile.updateHealthContext(LocalHealthContext(value.recentInjury, value.currentPain, value.recurringPain, value.clinicianRestriction, value.notes))
    }
    fun eraseAllData() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { services.dataManagement.eraseAllTrainingData() } }
                .onSuccess { mutableState.value = RunwayUiState.Loading; load(NativeDestination.Setup) }
                .onFailure { mutableState.value = ready.copy(actionPending = false, notice = NativeNotice(it.message ?: "Local data could not be erased.", true)) }
        }
    }

    fun importGpx(context: Context, uri: Uri) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { OneOffGpxImport.importUri(context, uri) }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            mutableState.value = current.copy(actionPending = false, notice = NativeNotice(result.message()))
            refresh()
        }
    }

    fun backup(context: Context, uri: Uri) = documentMutation("Backup created.") { services.dataManagement.backupToDocument(context, uri) }
    fun restore(context: Context, uri: Uri) = documentMutation("Backup restored. Runway was reopened from the restored local data.") { services.dataManagement.restoreFromDocument(context, uri) }
    fun export(context: Context, uri: Uri) = documentMutation("Training data exported.") { services.dataManagement.exportTrainingJson(context, uri) }

    private fun documentMutation(success: String, block: suspend () -> Any) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { mutableState.value = ready.copy(actionPending = false, notice = NativeNotice(success)); refresh() }
                .onFailure { mutableState.value = ready.copy(actionPending = false, notice = NativeNotice(it.message ?: "The local document operation failed.", true)) }
        }
    }

    private fun mutateSetting(block: suspend () -> Any) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess {
                mutableState.value = ready.copy(actionPending = false, notice = NativeNotice("Saved.")); refresh()
            }.onFailure { mutableState.value = ready.copy(actionPending = false, notice = NativeNotice(it.message ?: "Could not save that setting.", true)) }
        }
    }

    private suspend fun execute(command: MobileCommand): String = when (command) {
        is CreatePlanCommand -> createPlan(command)
        is RecordFeedbackCommand -> services.trainingMutations.recordWorkoutFeedback(LocalWorkoutFeedbackCommand(command.workoutId, feedbackStatus(command.status), command.completedDistanceKm?.let { (it * 1000).toInt() }, command.completedDurationMinutes?.let { (it * 60).toInt() }, command.feltHard, command.pain, skipChoice = planDecision(command.choice))).message()
        is RecordManualRunCommand -> services.trainingMutations.recordManualRun(LocalManualRunCommand(LocalDate.parse(command.occurredDate), command.distanceKm?.let { (it * 1000).toInt() }, command.durationMinutes?.let { (it * 60).toInt() }, command.feltHard, command.pain)).message()
        is LinkActivityCommand -> services.activityReview.link(command.activityId, command.workoutId).message()
        is ConfirmActivityExtraCommand -> services.activityReview.confirmAsExtra(command.activityId).message()
        is UpdateActivityFeedbackCommand -> services.activityReview.updateFeedback(command.activityId, command.feltHard, command.pain).message()
        is UnlinkActivityCommand -> services.activityReview.unlink(command.activityId).message()
        is DeleteActivityCommand -> services.activityReview.delete(command.activityId).message()
        is PreviewWorkoutEditCommand -> { previewWorkoutChange(command); "Review the plan effect before applying it." }
        is PreviewWorkoutAddCommand -> { previewWorkoutChange(command); "Review the plan effect before applying it." }
        is PreviewWorkoutRemovalCommand -> { previewWorkoutChange(command); "Review the plan effect before applying it." }
        is ResetWorkoutCommand -> { previewWorkoutChange(command); "Review the plan effect before applying it." }
        is UndoWorkoutAdjustmentCommand -> undoWorkoutChange(command)
        CompletePlanCommand -> endActivePlan(completed = true)
        ArchivePlanCommand -> endActivePlan(completed = false)
        else -> throw IllegalStateException("${command.action.replace('_', ' ')} is not available in this local screen yet.")
    }

    private suspend fun previewWorkoutChange(command: MobileCommand) {
        val (planId, request, actionId) = workoutChangeRequest(command)
        val prepared = services.workoutChanges.preview(planId, request, localToday(), hasInjuryRisk())
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(
            actionPending = false,
            completedAction = null,
            workoutPreview = LocalWorkoutChangePreview(prepared, planId, actionId, request, prepared.preview.operation, prepared.preview.toLocalWorkoutChangeDisplay()),
        )
    }

    private suspend fun undoWorkoutChange(command: UndoWorkoutAdjustmentCommand): String {
        services.workoutChanges.undo(command.adjustmentId, "workout-reversal-${stableId(command.adjustmentId)}", System.currentTimeMillis(), localToday())
        return "Workout change undone."
    }

    private suspend fun workoutChangeRequest(command: MobileCommand): Triple<String, LocalWorkoutChangeRequest, String> {
        suspend fun selected(id: String) =
            requireNotNull(services.trainingContext.workout(id)) { "Workout was not found." }
        requireNotNull(services.trainingContext.profile()) {
            "Finish local setup before changing a workout."
        }
        val active = services.trainingContext.activePlan()
            ?: error("There is no single active local plan.")
        return when (command) {
            is PreviewWorkoutEditCommand -> {
                val workout = selected(command.workoutId)
                require(workout.planId == active.planId) { "Only workouts in the active plan can be changed." }
                Triple(active.planId, LocalWorkoutChangeRequest.Edit(command.workoutId, command.mutation.toProposal(workout.weekId), rebalanceCompatibleWeek = command.mutation.rebalance), stableId("edit:${command.workoutId}:${command.mutation}"))
            }
            is PreviewWorkoutRemovalCommand -> {
                val workout = selected(command.workoutId)
                require(workout.planId == active.planId) { "Only workouts in the active plan can be changed." }
                Triple(active.planId, LocalWorkoutChangeRequest.Remove(command.workoutId), stableId("remove:${command.workoutId}"))
            }
            is ResetWorkoutCommand -> {
                val workout = selected(command.workoutId)
                require(workout.planId == active.planId) { "Only workouts in the active plan can be changed." }
                Triple(active.planId, LocalWorkoutChangeRequest.Reset(command.workoutId), stableId("reset:${command.workoutId}"))
            }
            is PreviewWorkoutAddCommand -> {
                val date = LocalDate.parse(command.mutation.scheduledDate)
                val week = services.trainingContext.weeks(active.planId)
                    .firstOrNull { date.toEpochDay() in it.startEpochDay..(it.startEpochDay + 6) }
                    ?: error("Choose a date within the active plan.")
                val id = "added-${stableId("add:${active.planId}:${command.mutation}")}".take(128)
                Triple(active.planId, LocalWorkoutChangeRequest.Add(id, command.mutation.toProposal(week.weekId), rebalanceCompatibleWeek = command.mutation.rebalance), stableId("add:${active.planId}:${command.mutation}"))
            }
            else -> error("This is not a workout change request.")
        }
    }


    private suspend fun endActivePlan(completed: Boolean): String {
        val active = services.trainingContext.activePlan()
            ?: throw IllegalStateException("There is no single active local plan to ${if (completed) "complete" else "archive"}.")
        val now = System.currentTimeMillis()
        val request = LocalPlanEndRequest(
            planId = active.planId,
            operationId = "${if (completed) "complete" else "archive"}:${active.planId}",
            todayEpochDay = LocalDate.now().toEpochDay(), occurredAtEpochMillis = now,
            reason = if (completed) "completed" else "archived by runner",
        )
        val result = if (completed) services.planLifecycle.complete(request) else services.planLifecycle.archive(request)
        return when (result) {
            is dev.deftmartian.runway.data.LocalPlanLifecycleResult.Ended -> if (completed) "Plan completed and saved to history." else "Plan archived; its history is still available."
            is dev.deftmartian.runway.data.LocalPlanLifecycleResult.Rejected -> throw IllegalStateException("Plan lifecycle was rejected: ${result.error.name.lowercase().replace('_', ' ')}")
            else -> throw IllegalStateException("That plan lifecycle operation is not available in the current local state.")
        }
    }

    private suspend fun createPlan(command: CreatePlanCommand): String {
        val outcome = StandaloneOnboardingAdapter.adapt(command)
        if (outcome is StandaloneOnboardingOutcome.Invalid) throw IllegalArgumentException(outcome.fieldErrors.entries.joinToString("; ") { "${it.key.name.lowercase().replace('_', ' ')}: ${it.value.joinToString { error -> error.name.lowercase().replace('_', ' ') }}" })
        val operation = "setup:${command.goalKind}:${command.targetDate}:${command.timeZone}:${command.availability.sorted()}"
        return when (val result = services.planSetup.setUp(StandaloneOnboardingPersistenceMapper.map(command, outcome, operation, System.currentTimeMillis()))) {
            is LocalPlanSetupResult.Created -> "Your local plan is ready."
            is LocalPlanSetupResult.ReplacementConfirmationRequired -> throw IllegalStateException("An existing local plan remains. Confirm replacing it to archive the prior plan and create this one.")
            is LocalPlanSetupResult.Rejected -> throw IllegalStateException("Plan setup was rejected: ${result.error.name.lowercase().replace('_', ' ')}")
        }
    }

    private fun feedbackStatus(value: String) = when (value.lowercase()) { "skipped" -> FeedbackStatus.SKIPPED; "shortened" -> FeedbackStatus.SHORTENED; else -> FeedbackStatus.DONE }
    private fun planDecision(value: String): PlanDecision? = runCatching { PlanDecision.valueOf(value.uppercase()) }.getOrNull()
    private fun Any.message(): String = when (this) {
        is LocalActivityReviewResult.Rejected -> throw IllegalStateException("Review was rejected: ${issue.name.lowercase().replace('_', ' ')}")
        is dev.deftmartian.runway.data.LocalTrainingMutationResult.Rejected -> throw IllegalStateException("Run was rejected: ${issue.name.lowercase().replace('_', ' ')}")
        else -> "Saved to your local training log."
    }

    private fun OneOffGpxImportOutcome.message(): String = when (this) {
        OneOffGpxImportOutcome.Imported -> "GPX activity saved for review."
        OneOffGpxImportOutcome.Duplicate -> "That GPX activity is already in your local log."
        OneOffGpxImportOutcome.DeletedPreviously -> "That GPX activity was previously removed and was not restored."
        OneOffGpxImportOutcome.ConfigurationRequired -> "Set up a local training plan before importing GPX activity."
        OneOffGpxImportOutcome.FutureActivity -> "Future-dated activity was not imported."
        OneOffGpxImportOutcome.TooLarge -> "That GPX file is too large to import on this phone."
        OneOffGpxImportOutcome.Rejected -> "That file could not be read as a GPX activity."
    }

    private fun WorkoutMutation.toProposal(weekId: String): WorkoutProposal {
        val type = when (type.lowercase()) {
            "long" -> WorkoutType.LONG
            "recovery" -> WorkoutType.RECOVERY
            "rest" -> WorkoutType.REST
            else -> WorkoutType.EASY
        }
        val kind = when (prescriptionKind.lowercase()) {
            "timed" -> PrescriptionKind.TIMED
            "rest" -> PrescriptionKind.REST
            else -> PrescriptionKind.DISTANCE
        }
        return WorkoutProposal(
            weekId = weekId, scheduledDate = LocalDate.parse(scheduledDate), type = type,
            prescriptionKind = kind, targetDistanceMeters = targetDistanceMeters,
            targetDurationSeconds = targetDurationSeconds, intervalStructure = intervalStructure?.toDomain(),
            intensity = intensity, purpose = purpose, reason = userReason,
        )
    }

    private fun TimedIntervalStructureDto.toDomain() = dev.deftmartian.runway.domain.TimedIntervalStructure(
        warmupSeconds ?: 0, cooldownSeconds ?: 0,
        blocks.map { block -> dev.deftmartian.runway.domain.RunWalkBlock(requireNotNull(block.repetitions), block.segments.map { segment -> dev.deftmartian.runway.domain.PrescriptionSegment(if (segment.kind == "walk") dev.deftmartian.runway.domain.SegmentKind.WALK else dev.deftmartian.runway.domain.SegmentKind.RUN, requireNotNull(segment.durationSeconds)) }) },
    )

    private suspend fun localToday(): LocalDate {
        val zone = services.trainingContext.profile()?.timeZone
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: throw IllegalStateException("A valid local training time zone is required.")
        return java.time.Instant.now().atZone(zone).toLocalDate()
    }

    private suspend fun hasInjuryRisk(): Boolean = services.trainingContext.profile()?.let {
        it.currentPain || it.recentInjury || it.recurringPain || it.medicalRestriction
    } ?: false

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        .take(48)

    private fun load(destination: NativeDestination) {
        val previous = mutableState.value as? RunwayUiState.Ready
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    when (destination) {
                        NativeDestination.Calendar -> services.surfaces.calendar(
                            calendarMonth.atDay(1).toEpochDay(),
                            calendarMonth.atEndOfMonth().toEpochDay(),
                        ).also { cacheEvidence(it.days.flatMap { day -> day.unlinkedActivities } + it.days.flatMap { day -> day.workouts.mapNotNull { workout -> workout.actual } }) }.toNativeCalendar()
                        NativeDestination.Inbox -> services.surfaces.inbox().also { cacheEvidence(it.activities) }.toNativeInbox()
                        NativeDestination.Stats -> services.surfaces.stats().toNativeStats()
                        NativeDestination.History -> services.surfaces.history().also { history = it; cacheEvidence(it.unlinkedActivities) }.toNativeHistory()
                        NativeDestination.Settings -> services.surfaces.settings().toNativeSettingsState()
                        NativeDestination.Setup -> NativeOnboardingPayload(
                            initialValues = null,
                            minimumTargetDate = null,
                            minimumCalibrationTargetDate = null,
                            minimumFoundationTargetDate = null,
                            maximumTargetDate = null,
                            activeGoal = null,
                        )
                        NativeDestination.HistoryDetail -> previous?.payload
                            ?: error("A plan record must be selected before opening detail.")
                    }
                }
            }
            result.onSuccess { payload ->
                val resolvedDestination = if (destination == NativeDestination.Calendar &&
                    (payload as? NativeCalendarPayload)?.onboardingRequired == true
                ) NativeDestination.Setup else destination
                mutableState.value = RunwayUiState.Ready(
                    bootstrap = localBootstrap(),
                    destination = resolvedDestination,
                    payload = payload,
                    loading = false,
                )
            }.onFailure { error ->
                mutableState.value = RunwayUiState.Failed(
                    "Local training data could not be opened: ${error.message ?: "unknown error"}",
                )
            }
        }
    }

    private fun localBootstrap() = NativeBootstrapPayload(
        user = null,
        setupComplete = false,
        timeZone = java.time.ZoneId.systemDefault().id,
        release = BuildConfig.VERSION_NAME,
        commit = BuildConfig.SOURCE_COMMIT,
        serverOrigin = null,
        androidApi = null,
        features = NativeFeatures(nativeUi = true, deviceAuthorization = false, healthConnect = true, backgroundFolderImport = true),
    )

    private fun cacheEvidence(activities: List<dev.deftmartian.runway.data.LocalActivityReadModel>) {
        evidenceByActivity = activities.associate { it.activityId to it.evidence }
    }
}
