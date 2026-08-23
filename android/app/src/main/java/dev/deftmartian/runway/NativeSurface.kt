package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalConsequenceDecisionPreparation
import dev.deftmartian.runway.data.RetentionRepairNotice

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

internal fun RetentionRepairNotice.message(): String = when {
    routeModeRestored && heartRateModeRestored ->
        "Runway found private route and heart-rate data retained by an earlier setup. " +
            "Both saved-detail choices were restored to Keep private. Review Imports in Settings."
    routeModeRestored ->
        "Runway found private route data retained by an earlier setup. The route-detail choice " +
            "was restored to Keep private. Review Imports in Settings."
    else ->
        "Runway found private heart-rate data retained by an earlier setup. The heart-rate-detail " +
            "choice was restored to Keep private. Review Imports in Settings."
}

internal fun RetentionRepairNotice.settingsMessage(): String = when {
    routeModeRestored && heartRateModeRestored ->
        "An earlier setup left private route and heart-rate data in storage while both settings " +
            "said Discard. Runway kept the data and restored both settings to Keep private."
    routeModeRestored ->
        "An earlier setup left private route data in storage while Imported route details said Discard. " +
            "Runway kept the data and restored the setting to Keep private."
    else ->
        "An earlier setup left private heart-rate data in storage while Imported heart-rate details said " +
            "Discard. Runway kept the data and restored the setting to Keep private."
}

internal fun NativeSurface.withRetentionRepair(
    repair: RetentionRepairNotice?,
): NativeSurface = when (this) {
    is NativeSurface.Settings -> copy(payload = payload?.copy(retentionRepair = repair))
    else -> this
}

internal fun appendInboxSurface(previous: NativeSurface?, page: NativeSurface): NativeSurface {
    val existing = previous as? NativeSurface.Inbox ?: return page
    val incoming = page as? NativeSurface.Inbox ?: return page
    val before = existing.payload ?: return page
    val next = incoming.payload ?: return page
    return NativeSurface.Inbox(
        before.copy(
            candidates = (before.candidates + next.candidates).distinctBy(NativeWorkout::id),
            activities = (before.activities + next.activities).distinctBy(NativeActivity::id),
            workoutDecisions = (before.workoutDecisions + next.workoutDecisions).distinctBy(NativeWorkoutFeedback::id),
            healthConnectChanges = (before.healthConnectChanges + next.healthConnectChanges)
                .distinctBy(NativeHealthConnectChange::mappingId),
            hasMore = next.hasMore,
            nextPage = next.nextPage,
        ),
    )
}
