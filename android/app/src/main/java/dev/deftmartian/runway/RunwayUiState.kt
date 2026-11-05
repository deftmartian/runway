package dev.deftmartian.runway

internal sealed interface RunwayUiState {
    data object Loading : RunwayUiState
    data class Ready(
        val surface: NativeSurface,
        val loading: Boolean,
        val actionPending: Boolean = false,
        val notice: NativeNotice? = null,
        val completedAction: NativeCompletedAction? = null,
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

/**
 * The last action that finished. The serial makes each completion distinct, so a sheet keyed on it
 * closes even when the same action completes twice in a row.
 */
internal data class NativeCompletedAction(val action: String, val serial: Long)

internal fun RunwayUiState.Ready.withoutActiveEvidenceRequests(): RunwayUiState.Ready =
    if (activityEvidenceLoading.isEmpty()) {
        this
    } else {
        copy(activityEvidenceLoading = emptySet())
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
