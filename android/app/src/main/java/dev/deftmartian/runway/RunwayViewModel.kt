package dev.deftmartian.runway

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.deftmartian.runway.data.LocalActivityEvidenceReadModel
import dev.deftmartian.runway.data.LocalHistoryReadModel
import dev.deftmartian.runway.data.LocalActivityReviewResult
import dev.deftmartian.runway.data.LocalBackupResult
import dev.deftmartian.runway.data.LocalConsequenceDecisionPreparation
import dev.deftmartian.runway.data.LocalConsequenceDecisionPersistenceResult
import dev.deftmartian.runway.data.LocalContinuePhaseRequest
import dev.deftmartian.runway.data.LocalDecisionIssue
import dev.deftmartian.runway.data.LocalDecisionSourceKind
import dev.deftmartian.runway.data.LocalManualRunCommand
import dev.deftmartian.runway.data.LocalPlanSetupResult
import dev.deftmartian.runway.data.LocalProfileUpdateResult
import dev.deftmartian.runway.data.LocalRaceBaselineConfirmationRequest
import dev.deftmartian.runway.data.LocalRestoreResult
import dev.deftmartian.runway.data.LocalTrainingMutationResult
import dev.deftmartian.runway.data.LocalWorkoutFeedbackDeletionResult
import dev.deftmartian.runway.data.LocalWorkoutFeedbackCommand
import dev.deftmartian.runway.data.LocalPlanEndRequest
import dev.deftmartian.runway.data.LocalPhaseReviewResult
import dev.deftmartian.runway.data.LocalPlanLifecycleResult
import dev.deftmartian.runway.data.LocalWorkoutChangeRequest
import dev.deftmartian.runway.data.ApplyLocalWorkoutChangeCommand
import dev.deftmartian.runway.data.RouteDataMode
import dev.deftmartian.runway.data.RouteDataModeUpdate
import dev.deftmartian.runway.data.HeartRateDataMode
import dev.deftmartian.runway.data.HeartRateDataModeUpdate
import dev.deftmartian.runway.data.RetentionRepairNotice
import dev.deftmartian.runway.data.SexForEstimate
import dev.deftmartian.runway.data.HeartRateSettingsSource
import dev.deftmartian.runway.data.LocalHeartRateProfile
import dev.deftmartian.runway.data.LocalHealthContext
import dev.deftmartian.runway.domain.PlanDecision
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectPendingResolutionResult
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectDuplicateDecision
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectDuplicateResolutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
internal data class LocalPlanDecisionChangeDisplay(
    val scheduledDate: String,
    val before: String,
    val after: String,
)

internal data class LocalPlanDecisionPreview(
    val prepared: LocalConsequenceDecisionPreparation.Prepared,
    val sourceLabel: String,
    val decision: String,
    val changes: List<LocalPlanDecisionChangeDisplay>,
)

internal sealed interface NativeSurface {
    val destination: NativeDestination
    val hasContent: Boolean

    data class Setup(val payload: NativeOnboardingPayload?) : NativeSurface {
        override val destination = NativeDestination.Setup
        override val hasContent = payload != null
    }

    data class Calendar(val payload: NativeCalendarPayload?) : NativeSurface {
        override val destination = NativeDestination.Calendar
        override val hasContent = payload != null
    }

    data class Inbox(val payload: NativeReviewPayload?) : NativeSurface {
        override val destination = NativeDestination.Inbox
        override val hasContent = payload != null
    }

    data class Stats(val payload: NativeStatsPayload?) : NativeSurface {
        override val destination = NativeDestination.Stats
        override val hasContent = payload != null
    }

    data class History(val payload: NativeHistoryPayload?) : NativeSurface {
        override val destination = NativeDestination.History
        override val hasContent = payload != null
    }

    data class Settings(val payload: NativeSettingsState?) : NativeSurface {
        override val destination = NativeDestination.Settings
        override val hasContent = payload != null
    }

    data class HistoryDetail(val payload: NativeHistoryDetailPayload?) : NativeSurface {
        override val destination = NativeDestination.HistoryDetail
        override val hasContent = payload != null
    }

    companion object {
        fun empty(destination: NativeDestination): NativeSurface = when (destination) {
            NativeDestination.Setup -> Setup(null)
            NativeDestination.Calendar -> Calendar(null)
            NativeDestination.Inbox -> Inbox(null)
            NativeDestination.Stats -> Stats(null)
            NativeDestination.History -> History(null)
            NativeDestination.Settings -> Settings(null)
            NativeDestination.HistoryDetail -> HistoryDetail(null)
        }
    }
}

internal sealed interface RunwayUiState {
    data object Loading : RunwayUiState
    data class Ready(
        val surface: NativeSurface,
        val loading: Boolean,
        val actionPending: Boolean = false,
        val notice: NativeNotice? = null,
        val completedAction: String? = null,
        val activityEvidence: Map<String, NativeActivityEvidence> = emptyMap(),
        val activityEvidenceLoading: Set<String> = emptySet(),
        val activityEvidenceFailures: Set<String> = emptySet(),
        val workoutPreview: LocalWorkoutChangePreview? = null,
        val planDecisionPreview: LocalPlanDecisionPreview? = null,
    ) : RunwayUiState {
        val destination: NativeDestination
            get() = surface.destination
    }

    data class Failed(val message: String) : RunwayUiState
}

/** Local app coordinator for UI state and typed repository commands. */
internal class RunwayViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val services = application.runwayServices
    private val surfaceLoader = NativeSurfaceLoader(application, services)
    private val mutableState = MutableStateFlow<RunwayUiState>(RunwayUiState.Loading)
    private var calendarMonth: YearMonth = savedStateHandle.get<String>(SAVED_CALENDAR_MONTH)
        ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?: YearMonth.now()
    private var calendarMonthWasSelected = savedStateHandle.contains(SAVED_CALENDAR_MONTH)
    private var historyPlanLimit = savedStateHandle.get<Int>(SAVED_HISTORY_LIMIT)
        ?.coerceIn(HISTORY_PAGE_SIZE, MAX_HISTORY_PLANS)
        ?: HISTORY_PAGE_SIZE
    private var inboxActivityLimit = savedStateHandle.get<Int>(SAVED_INBOX_LIMIT)
        ?.coerceIn(INBOX_PAGE_SIZE, MAX_INBOX_ACTIVITIES)
        ?: INBOX_PAGE_SIZE
    private var loadGeneration = 0L
    private var surfaceLoadJob: Job? = null
    private var history: LocalHistoryReadModel? = null
    private var retentionRepairChecked = false
    private var retentionRepair: RetentionRepairNotice? = null

    val state: StateFlow<RunwayUiState> = mutableState.asStateFlow()
    val restartAfterRestore: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(SAVED_RESTART_AFTER_RESTORE, false)

    init {
        refresh()
    }

    fun consumeRestartAfterRestore() {
        savedStateHandle[SAVED_RESTART_AFTER_RESTORE] = false
    }

    fun selectDestination(destination: NativeDestination) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.destination == destination && ready.surface.hasContent) return
        savedStateHandle[SAVED_DESTINATION] = destination.name
        if (destination != NativeDestination.HistoryDetail) {
            savedStateHandle.remove<String>(SAVED_HISTORY_PLAN_ID)
        }
        mutableState.value = ready.copy(
            surface = NativeSurface.empty(destination),
            loading = true,
            notice = null,
            activityEvidence = emptyMap(),
            activityEvidenceLoading = emptySet(),
            activityEvidenceFailures = emptySet(),
            workoutPreview = null,
            planDecisionPreview = null,
        )
        load(destination)
    }

    fun loadCalendarMonth(month: String) {
        calendarMonth = runCatching { YearMonth.parse(month) }.getOrDefault(calendarMonth)
        calendarMonthWasSelected = true
        savedStateHandle[SAVED_CALENDAR_MONTH] = calendarMonth.toString()
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.destination == NativeDestination.Calendar) {
            mutableState.value = ready.copy(loading = true, notice = null)
            load(NativeDestination.Calendar)
        } else {
            selectDestination(NativeDestination.Calendar)
        }
    }

    fun loadMoreHistory() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        val payload = (ready.surface as? NativeSurface.History)?.payload ?: return
        if (payload.history?.nextOffset == null || ready.loading) return
        if (historyPlanLimit >= MAX_HISTORY_PLANS) {
            mutableState.value = ready.copy(
                notice = NativeNotice(
                    "The oldest plans are outside this on-device history window.",
                    isError = true,
                ),
            )
            return
        }
        historyPlanLimit = (historyPlanLimit + HISTORY_PAGE_SIZE).coerceAtMost(MAX_HISTORY_PLANS)
        savedStateHandle[SAVED_HISTORY_LIMIT] = historyPlanLimit
        mutableState.value = ready.copy(loading = true, notice = null)
        load(NativeDestination.History)
    }

    fun loadMoreInbox() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        val payload = (ready.surface as? NativeSurface.Inbox)?.payload ?: return
        if (!payload.hasMore || ready.loading) return
        if (inboxActivityLimit >= MAX_INBOX_ACTIVITIES) {
            mutableState.value = ready.copy(
                notice = NativeNotice(
                    "The oldest Inbox items are outside this on-device window.",
                    isError = true,
                ),
            )
            return
        }
        inboxActivityLimit = (inboxActivityLimit + INBOX_PAGE_SIZE)
            .coerceAtMost(MAX_INBOX_ACTIVITIES)
        savedStateHandle[SAVED_INBOX_LIMIT] = inboxActivityLimit
        mutableState.value = ready.copy(loading = true, notice = null)
        load(NativeDestination.Inbox)
    }

    fun openHistoryDetail(planId: String) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        val detail = history?.let { surfaceLoader.cachedHistoryDetail(it, planId) }
        if (detail != null) {
            savedStateHandle[SAVED_DESTINATION] = NativeDestination.HistoryDetail.name
            savedStateHandle[SAVED_HISTORY_PLAN_ID] = planId
        }
        mutableState.value = ready.copy(
            surface = detail ?: ready.surface,
            loading = false,
            notice = if (detail == null) NativeNotice("That local plan record is no longer available.", true) else null,
        )
    }

    fun loadActivityTrace(activityId: String) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (activityId in ready.activityEvidenceLoading) return
        val requestGeneration = loadGeneration
        val requestDestination = ready.destination
        mutableState.value = ready.copy(activityEvidenceLoading = ready.activityEvidenceLoading + activityId)
        viewModelScope.launch {
            val result = localResult {
                withContext(Dispatchers.IO) { services.surfaces.activityEvidence(activityId) }
            }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            if (
                !activityEvidenceRequestIsCurrent(
                    requestGeneration = requestGeneration,
                    requestDestination = requestDestination,
                    currentGeneration = loadGeneration,
                    currentDestination = current.destination,
                ) ||
                activityId !in current.activityEvidenceLoading
            ) {
                return@launch
            }
            mutableState.value = result.fold(
                onSuccess = { evidence ->
                    if (evidence == null) {
                        current.copy(
                            activityEvidenceLoading = current.activityEvidenceLoading - activityId,
                            activityEvidenceFailures = current.activityEvidenceFailures + activityId,
                            notice = NativeNotice("This activity no longer has local evidence.", true),
                        )
                    } else {
                        current.copy(
                            activityEvidenceLoading = current.activityEvidenceLoading - activityId,
                            activityEvidenceFailures = current.activityEvidenceFailures - activityId,
                            activityEvidence =
                                current.activityEvidence + (activityId to evidence.evidence.toNativeEvidence()),
                        )
                    }
                },
                onFailure = {
                    current.copy(
                        activityEvidenceLoading = current.activityEvidenceLoading - activityId,
                        activityEvidenceFailures = current.activityEvidenceFailures + activityId,
                        notice = NativeNotice("Couldn’t read this activity from local storage.", true),
                    )
                },
            )
        }
    }

    fun refresh() {
        val destination = (mutableState.value as? RunwayUiState.Ready)?.destination
            ?: restoredDestination()
        load(destination)
    }

    fun submitAction(command: MobileCommand) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = localResult { withContext(Dispatchers.IO) { execute(command) } }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            result.onSuccess { message ->
                mutableState.value = current.copy(actionPending = false, completedAction = command.action, notice = NativeNotice(message))
                if (command is CreatePlanCommand) {
                    calendarMonthWasSelected = false
                    savedStateHandle[SAVED_DESTINATION] = NativeDestination.Calendar.name
                    savedStateHandle.remove<String>(SAVED_HISTORY_PLAN_ID)
                    val redirected = requireNotNull(mutableState.value as? RunwayUiState.Ready)
                    mutableState.value = redirected.copy(
                        surface = NativeSurface.empty(NativeDestination.Calendar),
                        loading = true,
                    )
                    load(NativeDestination.Calendar)
                } else if (
                    command !is PreviewWorkoutEditCommand &&
                    command !is PreviewWorkoutAddCommand &&
                    command !is PreviewWorkoutRemovalCommand &&
                    command !is ResetWorkoutCommand &&
                    command !is PreviewPlanDecisionCommand
                ) {
                    refresh()
                }
            }.onFailure {
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice(actionFailureMessage(command), true),
                )
            }
        }
    }

    fun dismissWorkoutPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(workoutPreview = null)
    }

    fun dismissPlanDecisionPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        mutableState.value = ready.copy(planDecisionPreview = null)
    }

    fun applyWorkoutPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        val pending = ready.workoutPreview ?: return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            val result = localResult {
                withContext(Dispatchers.IO) {
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
                        localToday(),
                        hasInjuryRisk(),
                    )
                }
            }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            result.onSuccess {
                mutableState.value = current.copy(actionPending = false, workoutPreview = null, completedAction = "apply_workout_change", notice = NativeNotice("Workout change applied to your local plan."))
                refresh()
            }.onFailure {
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        "The workout changed before this edit could be applied. Review it again.",
                        true,
                    ),
                )
            }
        }
    }

    fun applyPlanDecisionPreview() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        val pending = ready.planDecisionPreview ?: return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = localResult {
                withContext(Dispatchers.IO) {
                    val preview = pending.prepared.preview
                    val identity = stableId(
                        "${preview.source.kind}:${preview.source.sourceId}:" +
                            "${preview.source.version}:${preview.decision}:${preview.token}",
                    )
                    services.consequenceDecisions.apply(
                        preview = preview,
                        input = pending.prepared.input,
                        adjustmentId = "consequence-$identity",
                        decisionId = "decision-$identity",
                        appliedAtEpochMillis = System.currentTimeMillis(),
                    )
                }
            }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            result.onSuccess { outcome ->
                when (outcome) {
                    is LocalConsequenceDecisionPersistenceResult.Applied,
                    LocalConsequenceDecisionPersistenceResult.AlreadyApplied,
                    -> {
                        mutableState.value = current.copy(
                            actionPending = false,
                            planDecisionPreview = null,
                            completedAction = "apply_plan_decision",
                            notice = NativeNotice("Your choice was applied to the future plan."),
                        )
                        refresh()
                    }
                    is LocalConsequenceDecisionPersistenceResult.Rejected ->
                        mutableState.value = current.copy(
                            actionPending = false,
                            notice = NativeNotice(
                                decisionIssueMessage(outcome.issue),
                                isError = true,
                            ),
                        )
                }
            }.onFailure {
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        "The plan changed before this choice could be applied. Review it again.",
                        isError = true,
                    ),
                )
            }
        }
    }

    fun updateTimeZone(value: String) = mutateSetting { services.profile.updateTimeZone(value) }
    fun updateRoutePrivacy(value: NativeRoutePrivacy) {
        val mode = if (value == NativeRoutePrivacy.KeepPrivate) {
            RouteDataMode.Private
        } else {
            RouteDataMode.Discard
        }
        mutatePrivacySetting(destructive = mode == RouteDataMode.Discard) {
            requireRoutePrivacyUpdate(services.privacy.updateRouteDataMode(mode))
        }
    }

    fun updateHeartRatePrivacy(value: NativeHeartRatePrivacy) {
        val mode = if (value == NativeHeartRatePrivacy.KeepPrivate) {
            HeartRateDataMode.Private
        } else {
            HeartRateDataMode.Discard
        }
        mutatePrivacySetting(destructive = mode == HeartRateDataMode.Discard) {
            requireHeartRatePrivacyUpdate(services.privacy.updateHeartRateDataMode(mode))
        }
    }

    fun acknowledgeRetentionRepair() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending || retentionRepair == null) return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = localResult {
                withContext(Dispatchers.IO) {
                    services.privacy.acknowledgeRetentionRepairNotice()
                }
            }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            result.onSuccess {
                retentionRepair = null
                mutableState.value = current.copy(
                    surface = current.surface.withRetentionRepair(null),
                    actionPending = false,
                )
            }.onFailure {
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        "The privacy note could not be dismissed. The restored settings remain unchanged.",
                        true,
                    ),
                )
            }
        }
    }

    fun updateHeartRate(value: NativeHeartRateProfile) = mutateSetting {
        if (value.source == NativeHeartRateSource.NotConfigured) {
            services.profile.clearHeartRateProfile()
        } else {
            services.profile.updateHeartRateProfile(
                LocalHeartRateProfile(
                    sexForEstimate = when (value.sexForEstimates) {
                        NativeSexForEstimate.Female -> SexForEstimate.Female
                        NativeSexForEstimate.Male -> SexForEstimate.Male
                        NativeSexForEstimate.NotSpecified -> SexForEstimate.NotSpecified
                    },
                    ageYears = value.ageYears,
                    source = if (value.source == NativeHeartRateSource.Custom) {
                        HeartRateSettingsSource.Custom
                    } else {
                        HeartRateSettingsSource.Estimated
                    },
                    maxHeartRateBpm = requireNotNull(value.maxHeartRateBpm),
                    zone2FloorBpm = requireNotNull(value.zone2FloorBpm),
                    zone3FloorBpm = requireNotNull(value.zone3FloorBpm),
                    zone4FloorBpm = requireNotNull(value.zone4FloorBpm),
                    zone5FloorBpm = requireNotNull(value.zone5FloorBpm),
                ),
            )
        }
    }
    fun updateHealthContext(value: NativeHealthContext) = mutateSetting {
        services.profile.updateHealthContext(LocalHealthContext(value.recentInjury, value.currentPain, value.recurringPain, value.clinicianRestriction, value.notes))
    }
    fun eraseAllData() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            localResult {
                withContext(Dispatchers.IO) {
                    services.importSources.disconnectBeforeErase {
                        services.dataManagement.eraseAllTrainingData()
                    }
                }
            }
                .onSuccess {
                    retentionRepair = null
                    retentionRepairChecked = true
                    mutableState.value = RunwayUiState.Loading
                    load(NativeDestination.Setup)
                }
                .onFailure { error ->
                    val current = mutableState.value as? RunwayUiState.Ready ?: return@onFailure
                    mutableState.value = current.copy(
                        actionPending = false,
                        notice = NativeNotice(
                            destructiveFailureMessage(
                                error,
                                "Local data could not be reset. No training data was removed.",
                            ),
                            true,
                        ),
                    )
                }
        }
    }

    fun eraseImportedActivityData() {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            localResult {
                withContext(Dispatchers.IO) {
                    services.importSources.disconnectBeforeErase {
                        services.dataManagement.eraseImportedActivityData()
                    }
                }
            }.onSuccess { outcome ->
                val current = mutableState.value as? RunwayUiState.Ready
                if (current != null) {
                    mutableState.value = current.copy(
                        actionPending = false,
                        notice = NativeNotice(
                            when {
                                outcome.activitiesErased == 0 ->
                                    "There was no imported activity data to remove. Import sources were disconnected."
                                else ->
                                    "Removed ${outcome.activitiesErased} imported activit${if (outcome.activitiesErased == 1) "y" else "ies"}. Manual entries and plans were kept."
                            },
                        ),
                    )
                    refresh()
                }
            }.onFailure { error ->
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onFailure
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        destructiveFailureMessage(
                            error,
                            "Imported runs could not be removed. No training data was removed.",
                        ),
                        true,
                    ),
                )
            }
        }
    }

    fun importGpx(context: Context, uri: Uri) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { OneOffGpxImport.importUri(context, uri) }
            val current = mutableState.value as? RunwayUiState.Ready ?: return@launch
            mutableState.value = current.copy(actionPending = false, notice = NativeNotice(result.message()))
            refresh()
        }
    }

    fun backup(context: Context, uri: Uri) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = localResult {
                withContext(Dispatchers.IO) {
                    services.dataManagement.backupToDocument(context, uri)
                }
            }
            result.onSuccess { outcome ->
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onSuccess
                when (outcome) {
                    is LocalBackupResult.Created ->
                        mutableState.value = current.copy(
                            actionPending = false,
                            notice = NativeNotice("Backup created. It is not encrypted; store it somewhere you trust."),
                        )
                    is LocalBackupResult.Rejected ->
                        mutableState.value = current.copy(
                            actionPending = false,
                            notice = NativeNotice(outcome.reason, isError = true),
                        )
                }
            }.onFailure {
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onFailure
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        "The backup could not be created. Choose another location and try again.",
                        true,
                    ),
                )
            }
        }
    }

    fun restore(context: Context, uri: Uri) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = localResult {
                withContext(Dispatchers.IO) {
                    services.importSources.disconnectBeforeRestore {
                        services.dataManagement.restoreFromDocument(context, uri)
                    }
                }
            }
            result.onSuccess { outcome ->
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onSuccess
                val (message, isError, restartRequired) = when (outcome) {
                    is LocalRestoreResult.Restored ->
                        Triple("Backup restored. Restarting runway…", false, outcome.restartRequired)
                    is LocalRestoreResult.Rejected ->
                        Triple(
                            outcome.reason +
                                " Import sources were disconnected before restore; reconnect them in Settings.",
                            true,
                            outcome.restartRequired,
                        )
                    is LocalRestoreResult.RecoveryRequired ->
                        Triple(outcome.reason, true, true)
                }
                mutableState.value = current.copy(
                    actionPending = restartRequired,
                    notice = NativeNotice(message, isError),
                )
                if (restartRequired) {
                    savedStateHandle[SAVED_RESTART_AFTER_RESTORE] = true
                }
            }.onFailure { error ->
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onFailure
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        destructiveFailureMessage(
                            error,
                            "The selected backup could not be restored. The current local data was kept.",
                        ),
                        true,
                    ),
                )
            }
        }
    }

    fun export(context: Context, uri: Uri) = documentMutation(
        successMessage = { result -> trainingExportMessage(result.truncatedTables) },
    ) {
        services.dataManagement.exportTrainingJson(context, uri)
    }

    private fun <T> documentMutation(
        successMessage: (T) -> String,
        block: suspend () -> T,
    ) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            localResult { withContext(Dispatchers.IO) { block() } }
                .onSuccess { result ->
                    val current = mutableState.value as? RunwayUiState.Ready ?: return@onSuccess
                    mutableState.value = current.copy(
                        actionPending = false,
                        notice = NativeNotice(successMessage(result)),
                    )
                    refresh()
                }
                .onFailure {
                    val current = mutableState.value as? RunwayUiState.Ready ?: return@onFailure
                    mutableState.value = current.copy(
                        actionPending = false,
                        notice = NativeNotice(
                            "The file could not be written. Choose another location and try again.",
                            true,
                        ),
                    )
                }
        }
    }

    private fun mutateSetting(block: suspend () -> LocalProfileUpdateResult) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true)
        viewModelScope.launch {
            localResult {
                withContext(Dispatchers.IO) {
                    requireProfileUpdate(block())
                }
            }.onSuccess {
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onSuccess
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice("Saved."),
                )
                refresh()
            }.onFailure {
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onFailure
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice("That setting could not be saved. Check the values and try again.", true),
                )
            }
        }
    }

    private fun mutatePrivacySetting(
        destructive: Boolean,
        block: suspend () -> Unit,
    ) {
        val ready = mutableState.value as? RunwayUiState.Ready ?: return
        if (ready.actionPending) return
        mutableState.value = ready.copy(actionPending = true, notice = null)
        viewModelScope.launch {
            val result = localResult {
                withContext(Dispatchers.IO) {
                    if (destructive) {
                        val application = getApplication<Application>()
                        var schedules: ReconciliationScheduleSnapshot? = null
                        try {
                            AndroidStateCoordinator.withDestructiveImportBoundary(
                                closeAcquisition = {
                                    schedules = ReconciliationScheduler.captureSchedule(application)
                                    ReconciliationScheduler.cancelAllAndWait(application)
                                },
                            ) {
                                block()
                            }
                        } finally {
                            schedules?.let {
                                ReconciliationScheduler.restoreSchedule(application, it)
                            }
                        }
                    } else {
                        block()
                    }
                }
            }
            result.onSuccess {
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onSuccess
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice("Privacy setting saved."),
                )
                refresh()
            }.onFailure {
                val current = mutableState.value as? RunwayUiState.Ready ?: return@onFailure
                mutableState.value = current.copy(
                    actionPending = false,
                    notice = NativeNotice(
                        "That privacy change did not complete cleanly. Review the current setting. " +
                            "If background imports stopped, enable them again under Imports.",
                        true,
                    ),
                )
                refresh()
            }
        }
    }

    private suspend fun execute(command: MobileCommand): String = when (command) {
        is CreatePlanCommand -> createPlan(command)
        is RecordFeedbackCommand -> services.trainingMutations.recordWorkoutFeedback(
            LocalWorkoutFeedbackCommand(
                workoutId = command.workoutId,
                status = command.status,
                completedDistanceMeters =
                    command.completedDistanceKm?.let { (it * 1000).toInt() },
                completedDurationSeconds =
                    command.completedDurationMinutes?.let { (it * 60).toInt() },
                feltHard = command.feltHard,
                pain = command.pain,
                skipChoice = null,
            ),
        ).message()
        is RecordManualRunCommand -> services.trainingMutations.recordManualRun(
            LocalManualRunCommand(
                occurredDate = LocalDate.parse(command.occurredDate),
                distanceMeters = command.distanceKm?.let { (it * 1000).toInt() },
                durationSeconds = command.durationMinutes?.let { (it * 60).toInt() },
                feltHard = command.feltHard,
                pain = command.pain,
            ),
        ).message()
        is LinkActivityCommand -> services.activityReview.link(
            activityId = command.activityId,
            workoutId = command.workoutId,
            feltHard = command.feltHard,
            pain = command.pain,
        ).message()
        is ConfirmActivityExtraCommand -> services.activityReview.confirmAsExtra(
            activityId = command.activityId,
            feltHard = command.feltHard,
            pain = command.pain,
        ).message()
        is ReturnExtraActivityToReviewCommand ->
            services.activityReview.returnExtraToReview(command.activityId).message()
        is UpdateActivityFeedbackCommand -> services.activityReview.updateFeedback(command.activityId, command.feltHard, command.pain).message()
        is UnlinkActivityCommand -> services.activityReview.unlink(command.activityId).message()
        is DeleteActivityCommand -> services.activityReview.delete(command.activityId).message()
        is DeleteFeedbackCommand -> deleteWorkoutFeedback(command)
        is ResolveHealthConnectRecordCommand -> resolveHealthConnectRecord(command)
        is ResolveHealthConnectDuplicateCommand -> resolveHealthConnectDuplicate(command)
        is PreviewPlanDecisionCommand -> previewPlanDecision(command)
        is PreviewWorkoutEditCommand -> previewWorkoutChangeMessage(command)
        is PreviewWorkoutAddCommand -> previewWorkoutChangeMessage(command)
        is PreviewWorkoutRemovalCommand -> previewWorkoutChangeMessage(command)
        is ResetWorkoutCommand -> previewWorkoutChangeMessage(command)
        is UndoWorkoutAdjustmentCommand -> undoWorkoutChange(command)
        is ConfirmPhaseBaselineCommand -> confirmPhaseBaseline(command)
        ContinueBeginnerPhaseCommand -> continueBeginnerPhase()
        CompletePlanCommand -> endActivePlan(completed = true)
        ArchivePlanCommand -> endActivePlan(completed = false)
    }

    private suspend fun previewWorkoutChangeMessage(command: MobileCommand): String {
        previewWorkoutChange(command)
        return "Review the plan effect before applying it."
    }

    private suspend fun deleteWorkoutFeedback(
        command: DeleteFeedbackCommand,
    ): String = when (
        val result = services.trainingMutations.deleteWorkoutFeedback(command.workoutId)
    ) {
        is LocalWorkoutFeedbackDeletionResult.Deleted ->
            "The saved result was removed. The planned workout is unchanged."
        is LocalWorkoutFeedbackDeletionResult.Rejected ->
            throw IllegalStateException(
                when (result.issue) {
                    dev.deftmartian.runway.data.LocalWorkoutFeedbackDeletionIssue.NOT_DIRECT_FEEDBACK ->
                        "This result belongs to an imported activity. Change or delete that activity instead."
                    dev.deftmartian.runway.data.LocalWorkoutFeedbackDeletionIssue.WORKOUT_IN_FUTURE ->
                        "A future workout cannot have a saved result removed."
                    dev.deftmartian.runway.data.LocalWorkoutFeedbackDeletionIssue.LINKED_ACCEPTED_ACTIVITY ->
                        "Unlink the accepted activity before removing this result."
                    dev.deftmartian.runway.data.LocalWorkoutFeedbackDeletionIssue.CONSEQUENCE_APPLIED ->
                        "This result already changed the plan and cannot be removed."
                    else -> "The saved result is no longer removable."
                },
            )
    }

    private suspend fun resolveHealthConnectRecord(
        command: ResolveHealthConnectRecordCommand,
    ): String {
        val result = when (command.decision) {
            HealthConnectRecordDecision.AcceptCorrection ->
                services.healthConnect.acceptPendingCorrection(command.provider, command.recordId)
            HealthConnectRecordDecision.KeepCurrent ->
                services.healthConnect.rejectPendingCorrection(command.provider, command.recordId)
            HealthConnectRecordDecision.DeleteFromRunway ->
                services.healthConnect.deleteFromRunwayAfterProviderDeletion(
                    command.provider,
                    command.recordId,
                )
            HealthConnectRecordDecision.RetainInRunway ->
                services.healthConnect.retainLocallyAfterProviderDeletion(
                    command.provider,
                    command.recordId,
                )
        }
        return when (result) {
            is LocalHealthConnectPendingResolutionResult.CorrectionAccepted ->
                "The updated Health Connect record is now the accepted activity."
            is LocalHealthConnectPendingResolutionResult.CorrectionRejected ->
                "The existing accepted activity was kept."
            is LocalHealthConnectPendingResolutionResult.ProviderDeletionDeleted ->
                "The activity was removed from runway."
            is LocalHealthConnectPendingResolutionResult.ProviderDeletionRetained ->
                "The activity remains in runway as a local record."
            is LocalHealthConnectPendingResolutionResult.MappingMissing ->
                throw IllegalStateException("That Health Connect change no longer exists.")
            is LocalHealthConnectPendingResolutionResult.ActivityMissing ->
                throw IllegalStateException("The activity for that Health Connect change is missing.")
            is LocalHealthConnectPendingResolutionResult.UnexpectedActivityState ->
                throw IllegalStateException("Review the activity before resolving its source change.")
            is LocalHealthConnectPendingResolutionResult.UnexpectedActivitySource ->
                throw IllegalStateException("That record no longer belongs to Health Connect.")
            is LocalHealthConnectPendingResolutionResult.IncompletePendingState ->
                throw IllegalStateException("The pending Health Connect record is incomplete. Import again.")
            is LocalHealthConnectPendingResolutionResult.AlreadyResolved ->
                "That Health Connect change was already resolved."
            is LocalHealthConnectPendingResolutionResult.WrongPendingAction ->
                throw IllegalStateException("The Health Connect change has changed. Review it again.")
        }
    }

    private suspend fun resolveHealthConnectDuplicate(
        command: ResolveHealthConnectDuplicateCommand,
    ): String {
        val decision = when (command.decision) {
            HealthConnectDuplicateDecision.KeepBoth ->
                LocalHealthConnectDuplicateDecision.KeepBoth
            HealthConnectDuplicateDecision.UseExisting ->
                LocalHealthConnectDuplicateDecision.UseExisting
        }
        return when (
            val result = services.healthConnect.resolveDuplicateCandidate(
                command.provider,
                command.recordId,
                decision,
            )
        ) {
            is LocalHealthConnectDuplicateResolutionResult.KeptBoth ->
                "Both runs remain in the Inbox for separate review."
            is LocalHealthConnectDuplicateResolutionResult.UsedExisting ->
                "The existing run was kept and the duplicate Health Connect copy was removed."
            is LocalHealthConnectDuplicateResolutionResult.AlreadyResolved ->
                "That possible duplicate was already resolved."
            is LocalHealthConnectDuplicateResolutionResult.MappingMissing ->
                throw IllegalStateException("That Health Connect import no longer exists.")
            is LocalHealthConnectDuplicateResolutionResult.HealthConnectReviewMissing ->
                throw IllegalStateException("The Health Connect review copy is missing.")
            is LocalHealthConnectDuplicateResolutionResult.ExistingActivityMissing ->
                throw IllegalStateException("The existing run is no longer available. Import again to review it.")
            is LocalHealthConnectDuplicateResolutionResult.UnexpectedMappingState,
            is LocalHealthConnectDuplicateResolutionResult.UnexpectedHealthConnectReview,
            is LocalHealthConnectDuplicateResolutionResult.UnexpectedExistingActivitySource,
            -> throw IllegalStateException("Those runs changed after this comparison. Review the Inbox again.")
        }
    }

    private suspend fun previewPlanDecision(
        command: PreviewPlanDecisionCommand,
    ): String {
        val sourceKind = when (command.source) {
            "feedback" -> LocalDecisionSourceKind.WorkoutFeedback
            "activity" -> LocalDecisionSourceKind.Activity
            else -> throw IllegalArgumentException("That plan-decision source is not supported.")
        }
        val decision = planDecision(command.decision)
            ?: throw IllegalArgumentException("That plan choice is not supported.")
        return when (
            val prepared = services.consequenceDecisions.prepare(
                sourceKind,
                command.sourceId,
                decision,
            )
        ) {
            is LocalConsequenceDecisionPreparation.Rejected ->
                throw IllegalStateException(decisionIssueMessage(prepared.issue))
            is LocalConsequenceDecisionPreparation.Prepared -> {
                val current = mutableState.value as? RunwayUiState.Ready
                    ?: throw IllegalStateException("The current screen is no longer available.")
                mutableState.value = current.copy(
                    actionPending = false,
                    planDecisionPreview = LocalPlanDecisionPreview(
                        prepared = prepared,
                        sourceLabel =
                            if (sourceKind == LocalDecisionSourceKind.WorkoutFeedback) {
                                "Recorded workout result"
                            } else {
                                "Extra activity"
                            },
                        decision = command.decision,
                        changes = prepared.preview.changes.map { change ->
                            LocalPlanDecisionChangeDisplay(
                                scheduledDate =
                                    LocalDate.ofEpochDay(change.before.currentScheduledEpochDay)
                                        .toString(),
                                before = workoutDecisionSummary(change.before),
                                after = workoutDecisionSummary(change.after),
                            )
                        },
                    ),
                )
                "Review the exact future workouts before applying this choice."
            }
        }
    }

    private suspend fun confirmPhaseBaseline(
        command: ConfirmPhaseBaselineCommand,
    ): String {
        val active = services.trainingContext.activePlan()
            ?: throw IllegalStateException("There is no active phase to confirm.")
        val today = localToday()
        val review = services.planLifecycle.phaseReview(active.planId, today.toEpochDay())
        if (review !is LocalPhaseReviewResult.Available) {
            throw IllegalStateException("The phase is not ready for a baseline decision.")
        }
        val identity = stableId(
            "phase-baseline:${active.planId}:${review.review.phase}:${review.review.baseline}",
        )
        return when (
            val result = services.planLifecycle.confirmRaceBaseline(
                LocalRaceBaselineConfirmationRequest(
                    phasePlanId = active.planId,
                    newPlanId = "race-plan-$identity",
                    operationId = "confirm-baseline-$identity",
                    expectedPreviewToken = command.expectedPreviewToken,
                    todayEpochDay = today.toEpochDay(),
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    explicitlyConfirmed = true,
                ),
            )
        ) {
            is LocalPlanLifecycleResult.RacePlanStarted ->
                "The recorded baseline was confirmed and the race phase was built."
            LocalPlanLifecycleResult.ConfirmationRequired ->
                throw IllegalStateException("Confirm the recorded baseline before building the race phase.")
            is LocalPlanLifecycleResult.NotYetAvailable ->
                throw IllegalStateException("This phase ends on ${LocalDate.ofEpochDay(result.targetEpochDay)}.")
            is LocalPlanLifecycleResult.Rejected ->
                throw IllegalStateException(
                    "The race phase could not be built: " +
                        result.error.name.lowercase().replace('_', ' '),
                )
            else -> throw IllegalStateException("The race phase could not be built from this state.")
        }
    }

    private suspend fun continueBeginnerPhase(): String {
        val active = services.trainingContext.activePlan()
            ?: throw IllegalStateException("There is no active beginner phase to continue.")
        val today = localToday()
        val identity = stableId("continue-phase:${active.planId}:${active.endEpochDay}")
        return when (
            val result = services.planLifecycle.continueBeginnerPhase(
                LocalContinuePhaseRequest(
                    planId = active.planId,
                    operationId = "continue-phase-$identity",
                    todayEpochDay = today.toEpochDay(),
                    occurredAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        ) {
            is LocalPlanLifecycleResult.PhaseContinued ->
                "Another phase week was added to the calendar."
            is LocalPlanLifecycleResult.NotYetAvailable ->
                throw IllegalStateException("This phase ends on ${LocalDate.ofEpochDay(result.targetEpochDay)}.")
            is LocalPlanLifecycleResult.Rejected ->
                throw IllegalStateException(
                    "The phase could not be continued: " +
                        result.error.name.lowercase().replace('_', ' '),
                )
            else -> throw IllegalStateException("That phase continuation is not available.")
        }
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
                Triple(active.planId, LocalWorkoutChangeRequest.Edit(command.workoutId, command.mutation.toWorkoutProposal(workout.weekId), rebalanceCompatibleWeek = command.mutation.rebalance), stableId("edit:${command.workoutId}:${command.mutation}"))
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
                Triple(active.planId, LocalWorkoutChangeRequest.Add(id, command.mutation.toWorkoutProposal(week.weekId), rebalanceCompatibleWeek = command.mutation.rebalance), stableId("add:${active.planId}:${command.mutation}"))
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
            todayEpochDay = localToday().toEpochDay(), occurredAtEpochMillis = now,
            reason = if (completed) "completed" else "abandoned",
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
        if (outcome is StandaloneOnboardingOutcome.Invalid) {
            val detail = outcome.fieldErrors.entries.joinToString("; ") { (field, errors) ->
                val label = field.name.lowercase().replace('_', ' ')
                val issues = errors.joinToString { error ->
                    error.name.lowercase().replace('_', ' ')
                }
                "$label: $issues"
            }
            throw IllegalArgumentException(detail)
        }
        val request = StandaloneOnboardingPersistenceMapper.map(
            command,
            outcome,
        )
        return when (val result = services.planSetup.setUp(request)) {
            is LocalPlanSetupResult.Created -> "Your local plan is ready."
            is LocalPlanSetupResult.AlreadyCreated -> "Your local plan is ready."
            is LocalPlanSetupResult.ReplacementConfirmationRequired ->
                throw IllegalStateException(
                    "An existing local plan remains. Confirm replacing it to archive the prior " +
                        "plan and create this one.",
                )
            is LocalPlanSetupResult.Rejected ->
                throw IllegalStateException(
                    "Plan setup was rejected: " +
                        result.error.name.lowercase().replace('_', ' '),
                )
        }
    }

    private fun planDecision(value: String): PlanDecision? =
        runCatching { PlanDecision.valueOf(value.uppercase()) }.getOrNull()

    private fun LocalActivityReviewResult.message(): String = when (this) {
        is LocalActivityReviewResult.Rejected ->
            throw IllegalStateException(
                "Review was rejected: ${issue.name.lowercase().replace('_', ' ')}",
            )
        is LocalActivityReviewResult.FeedbackUpdated ->
            if (appliedDecisionPreserved) {
                "Feedback corrected. The earlier plan choice remains in History and was not recalculated."
            } else {
                "Saved to your local training log."
            }
        is LocalActivityReviewResult.AcceptedExtra,
        is LocalActivityReviewResult.Deleted,
        is LocalActivityReviewResult.Linked,
        is LocalActivityReviewResult.ReturnedToReview,
        is LocalActivityReviewResult.Unlinked,
        -> "Saved to your local training log."
    }

    private fun LocalTrainingMutationResult.message(): String = when (this) {
        is LocalTrainingMutationResult.Rejected ->
            throw IllegalStateException(
                "Run was rejected: ${issue.name.lowercase().replace('_', ' ')}",
            )
        is LocalTrainingMutationResult.ManualRunRecorded -> "Manual run saved for review."
        is LocalTrainingMutationResult.WorkoutFeedbackRecorded -> "Saved to your local training log."
    }

    private fun OneOffGpxImportOutcome.message(): String = when (this) {
        OneOffGpxImportOutcome.Imported -> "GPX activity saved for review."
        OneOffGpxImportOutcome.Duplicate -> "That GPX activity is already in your local log."
        OneOffGpxImportOutcome.DeletedPreviously -> "That GPX activity was previously removed and was not restored."
        OneOffGpxImportOutcome.ConfigurationRequired -> "Set up a local training plan before importing GPX activity."
        OneOffGpxImportOutcome.FutureActivity -> "Future-dated activity was not imported."
        OneOffGpxImportOutcome.Interrupted -> "The GPX import stopped while local data was changing. Choose the file again."
        OneOffGpxImportOutcome.TooLarge -> "That GPX file is too large to import on this phone."
        OneOffGpxImportOutcome.Rejected -> "That file could not be read as a GPX activity."
    }

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

    private fun decisionIssueMessage(issue: LocalDecisionIssue): String = when (issue) {
        LocalDecisionIssue.PROPOSAL_NOT_AVAILABLE ->
            "That plan choice is no longer available for this result."
        LocalDecisionIssue.DECISION_NOT_OFFERED ->
            "That choice is not offered for the current result."
        LocalDecisionIssue.NO_FUTURE_WORKOUT ->
            "There is no future planned run for this choice to change."
        LocalDecisionIssue.NO_COMPATIBLE_WORKOUT ->
            "No compatible run remains in this week."
        LocalDecisionIssue.NO_REPEATABLE_PRESCRIPTION ->
            "The original prescription cannot be repeated."
        LocalDecisionIssue.NO_REDUCIBLE_AMOUNT ->
            "The next compatible run cannot be reduced safely."
        LocalDecisionIssue.ALREADY_APPLIED ->
            "A choice was already applied for this result."
        LocalDecisionIssue.STALE_PREVIEW ->
            "The plan changed after this preview. Review the choice again."
    }

    private fun workoutDecisionSummary(
        workout: dev.deftmartian.runway.data.WorkoutEntity,
    ): String {
        if (workout.currentWorkoutType == "rest" || workout.currentPrescriptionKind == "rest") {
            return "Recovery day"
        }
        return listOfNotNull(
            workout.currentDistanceMeters?.let { formatDistance(it.toDouble()) },
            workout.currentDurationSeconds?.let { formatDuration(it.toDouble()) },
            workout.currentPurpose?.takeIf(String::isNotBlank),
        ).joinToString(" · ").ifBlank { "Planned run" }
    }

    private fun load(destination: NativeDestination) {
        val generation = ++loadGeneration
        val previous = mutableState.value as? RunwayUiState.Ready
        if (previous != null) {
            mutableState.value = previous.withoutActiveEvidenceRequests()
        }
        surfaceLoadJob?.cancel()
        surfaceLoadJob = viewModelScope.launch {
            val result = localResult {
                val loaded = surfaceLoader.load(
                    SurfaceLoadRequest(
                        destination = destination,
                        calendarMonth = calendarMonth,
                        calendarMonthWasSelected = calendarMonthWasSelected,
                        historyPlanId = savedStateHandle[SAVED_HISTORY_PLAN_ID],
                        previousHistoryDetail =
                            previous?.surface as? NativeSurface.HistoryDetail,
                        historyPlanLimit = historyPlanLimit,
                        inboxActivityLimit = inboxActivityLimit,
                    ),
                )
                val checkedNow = !retentionRepairChecked
                val persistedRepair = if (checkedNow) {
                    withContext(Dispatchers.IO) {
                        services.privacy.pendingRetentionRepairNotice()
                    }
                } else {
                    retentionRepair
                }
                Triple(loaded, persistedRepair, checkedNow)
            }
            result.onSuccess { (loaded, persistedRepair, checkedNow) ->
                if (generation != loadGeneration) return@onSuccess
                if (checkedNow) {
                    retentionRepairChecked = true
                    retentionRepair = persistedRepair
                }
                calendarMonth = loaded.calendarMonth
                if (destination == NativeDestination.Calendar) {
                    savedStateHandle[SAVED_CALENDAR_MONTH] = calendarMonth.toString()
                }
                loaded.history?.let { history = it }
                savedStateHandle[SAVED_DESTINATION] = loaded.surface.destination.name
                val current = mutableState.value as? RunwayUiState.Ready
                val loadedSurface = loaded.surface.withRetentionRepair(retentionRepair)
                val merged = mergeLoadedSurface(current, previous, loadedSurface)
                mutableState.value = if (!checkedNow || persistedRepair == null) {
                    merged
                } else {
                    merged.copy(notice = NativeNotice(persistedRepair.message()))
                }
            }.onFailure {
                if (generation != loadGeneration) return@onFailure
                val current = mutableState.value as? RunwayUiState.Ready
                mutableState.value = current?.copy(
                    loading = false,
                    notice = NativeNotice(
                        "Local training data could not be refreshed. Try again.",
                        true,
                    ),
                ) ?: RunwayUiState.Failed(
                    "Local training data could not be opened. Close runway, reopen it, and try again.",
                )
            }
        }
    }

    private fun requireProfileUpdate(result: LocalProfileUpdateResult) = when (result) {
        LocalProfileUpdateResult.Updated -> Unit
        LocalProfileUpdateResult.ProfileNotConfigured ->
            throw IllegalStateException("Local setup is not complete.")
        is LocalProfileUpdateResult.Invalid ->
            throw IllegalArgumentException("The profile values are not valid.")
    }

    private fun requireRoutePrivacyUpdate(result: RouteDataModeUpdate) = when (result) {
        is RouteDataModeUpdate.Updated -> Unit
        is RouteDataModeUpdate.Unchanged -> Unit
        RouteDataModeUpdate.ProfileNotConfigured ->
            throw IllegalStateException("Local setup is not complete.")
    }

    private fun requireHeartRatePrivacyUpdate(result: HeartRateDataModeUpdate) = when (result) {
        is HeartRateDataModeUpdate.Updated -> Unit
        is HeartRateDataModeUpdate.Unchanged -> Unit
        HeartRateDataModeUpdate.ProfileNotConfigured ->
            throw IllegalStateException("Local setup is not complete.")
    }

    private fun actionFailureMessage(command: MobileCommand): String = when (command) {
        is CreatePlanCommand ->
            "The plan could not be created. Check the setup details and try again."
        is PreviewWorkoutEditCommand,
        is PreviewWorkoutAddCommand,
        is PreviewWorkoutRemovalCommand,
        is ResetWorkoutCommand,
        is UndoWorkoutAdjustmentCommand,
        -> "That workout changed before the action could be saved. Review the current plan and try again."
        is LinkActivityCommand,
        is UnlinkActivityCommand,
        is ConfirmActivityExtraCommand,
        is ReturnExtraActivityToReviewCommand,
        is UpdateActivityFeedbackCommand,
        is DeleteActivityCommand,
        is ResolveHealthConnectRecordCommand,
        is ResolveHealthConnectDuplicateCommand,
        -> "That activity changed before the action could be saved. Review the Inbox and try again."
        else -> "That change could not be saved. Review the current screen and try again."
    }

    private fun destructiveFailureMessage(error: Throwable, fallback: String): String =
        if (error is ImportSourceBoundaryException) error.safeMessage else fallback

    private fun restoredDestination(): NativeDestination {
        return restoredNativeDestination(
            savedDestination = savedStateHandle[SAVED_DESTINATION],
            savedHistoryPlanId = savedStateHandle[SAVED_HISTORY_PLAN_ID],
        )
    }

    private companion object {
        const val SAVED_DESTINATION = "runway.destination"
        const val SAVED_HISTORY_PLAN_ID = "runway.history.plan-id"
        const val SAVED_CALENDAR_MONTH = "runway.calendar.month"
        const val SAVED_HISTORY_LIMIT = "runway.history.limit"
        const val SAVED_INBOX_LIMIT = "runway.inbox.limit"
        const val SAVED_RESTART_AFTER_RESTORE = "runway.restore.restart-required"
        const val HISTORY_PAGE_SIZE = 50
        const val INBOX_PAGE_SIZE = 50
        const val MAX_HISTORY_PLANS = 400
        const val MAX_INBOX_ACTIVITIES = 1_000
    }
}

internal suspend fun <T> localResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

internal fun activityEvidenceRequestIsCurrent(
    requestGeneration: Long,
    requestDestination: NativeDestination,
    currentGeneration: Long,
    currentDestination: NativeDestination,
): Boolean =
    requestGeneration == currentGeneration &&
        requestDestination == currentDestination

internal fun RetentionRepairNotice.message(): String = when {
    routeModeRestored && heartRateModeRestored ->
        "Runway found private route and heart-rate data retained by an earlier setup. " +
            "Both privacy settings were restored to Keep private. Review Privacy in Settings."
    routeModeRestored ->
        "Runway found private route data retained by an earlier setup. Route privacy was " +
            "restored to Keep private. Review Privacy in Settings."
    else ->
        "Runway found private heart-rate data retained by an earlier setup. Heart-rate privacy " +
            "was restored to Keep private. Review Privacy in Settings."
}

internal fun RetentionRepairNotice.settingsMessage(): String = when {
    routeModeRestored && heartRateModeRestored ->
        "An earlier setup left private route and heart-rate data in storage while both settings " +
            "said Discard. Runway kept the data and restored both settings to Keep private."
    routeModeRestored ->
        "An earlier setup left private route data in storage while Route privacy said Discard. " +
            "Runway kept the data and restored the setting to Keep private."
    else ->
        "An earlier setup left private heart-rate data in storage while Heart-rate privacy said " +
            "Discard. Runway kept the data and restored the setting to Keep private."
}

internal fun NativeSurface.withRetentionRepair(
    repair: RetentionRepairNotice?,
): NativeSurface = when (this) {
    is NativeSurface.Settings -> copy(payload = payload?.copy(retentionRepair = repair))
    else -> this
}

internal fun RunwayUiState.Ready.withoutActiveEvidenceRequests(): RunwayUiState.Ready =
    if (activityEvidenceLoading.isEmpty()) {
        this
    } else {
        copy(activityEvidenceLoading = emptySet())
    }

internal fun trainingExportMessage(truncatedTables: Set<String>): String =
    if (truncatedTables.isEmpty()) {
        "Readable training history exported."
    } else {
        "Readable export created, but some large sections were limited to 2,000 rows. " +
            "Use Backup for a complete copy."
    }

internal fun mergeLoadedSurface(
    current: RunwayUiState.Ready?,
    previous: RunwayUiState.Ready?,
    surface: NativeSurface,
): RunwayUiState.Ready =
    (current ?: previous)?.copy(
        surface = surface,
        loading = false,
    ) ?: RunwayUiState.Ready(
        surface = surface,
        loading = false,
    )
