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
import dev.deftmartian.runway.data.LocalRoutineWeekGoalRequest
import dev.deftmartian.runway.data.LocalRoutineWeekGoalResult
import dev.deftmartian.runway.data.LocalRoutineRunAllocation
import dev.deftmartian.runway.data.LocalRoutineWeekDistributionRequest
import dev.deftmartian.runway.data.LocalRoutineWeekDistributionResult
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
import dev.deftmartian.runway.data.LocalInboxPagingCursor
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
import java.time.Instant
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
        "Training record",
        "history-detail",
        R.drawable.ic_nav_history,
        primaryNavigation = false,
        navigationParent = History,
    ),
}

internal fun NativeDestination.primaryNavigationDestination(): NativeDestination? =
    generateSequence(this) { it.navigationParent }.firstOrNull(NativeDestination::primaryNavigation)
