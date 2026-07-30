package dev.deftmartian.runway.data

import androidx.room.withTransaction

/**
 * The one local boundary for accepting onboarding output. It deliberately owns no planning
 * arithmetic: callers hand it a validated, typed graph and all identities/times. That keeps a
 * retry from inventing another plan and makes replacing an active plan explicit.
 */
class LocalPlanSetupRepository(
    private val database: RunwayLedgerDatabase,
) {
    suspend fun setUp(request: LocalPlanSetupRequest): LocalPlanSetupResult {
        when (val preparation = LocalPlanSetupPreparation.prepare(request)) {
            is LocalPlanSetupPreparation.Invalid -> return LocalPlanSetupResult.Rejected(preparation.error)
            is LocalPlanSetupPreparation.Ready -> Unit
        }

        return database.withTransaction {
            val plans = database.goalPlanDao()
            val currentGoalCount = plans.currentGoalIds(CURRENT_STATE_QUERY_LIMIT).size
            val activePlanCount = plans.activePlanIds(ACTIVE_STATE_QUERY_LIMIT).size
            if (currentGoalCount > 1 || activePlanCount > 1) {
                return@withTransaction LocalPlanSetupResult.Rejected(LocalPlanSetupError.CURRENT_STATE_LIMIT_EXCEEDED)
            }
            if ((currentGoalCount > 0 || activePlanCount > 0) && !request.confirmReplaceCurrent) {
                return@withTransaction LocalPlanSetupResult.ReplacementConfirmationRequired(
                    currentGoalCount = currentGoalCount,
                    activePlanCount = activePlanCount,
                )
            }

            val candidate = request.candidate
            val candidatePlanId = (candidate as? LocalPlanCandidate.Generated)?.graph?.plan?.planId
            if (plans.goalExists(candidate.goal.goalId) ||
                (candidatePlanId != null && plans.planExists(candidatePlanId))
            ) {
                return@withTransaction LocalPlanSetupResult.Rejected(LocalPlanSetupError.IDENTITY_ALREADY_EXISTS)
            }

            if (request.confirmReplaceCurrent) {
                // Archive, never delete: prior workouts, reviews, decisions, and history remain.
                plans.archiveActivePlans(request.archiveAtEpochMillis)
                plans.archiveCurrentGoals(request.archiveAtEpochMillis)
            }

            database.profileSettingsDao().replaceOnboardingInputs(request.profile, request.availabilityDays)
            (candidate as? LocalPlanCandidate.Generated)?.graph?.let { graph ->
                plans.saveGoal(graph.goal)
                plans.createPlanGraph(graph.plan, graph.weeks, graph.workouts, graph.planSummaryWarnings)
                for (reference in graph.planSourceReferences) plans.savePlanSourceReference(reference)
                for (block in graph.blocks) plans.saveBlock(block)
                for (segment in graph.segments) plans.saveSegment(segment)
                for (reference in graph.workoutSourceReferences) plans.saveWorkoutSourceReference(reference)
                LocalPlanSetupResult.Created(graph.goal.goalId, graph.plan.planId)
            } ?: run {
                plans.saveGoal(candidate.goal)
                LocalPlanSetupResult.Created(candidate.goal.goalId, planId = null)
            }
        }
    }

    private companion object {
        /** Two rows are enough to prove the single-runner ledger has become ambiguous. */
        const val CURRENT_STATE_QUERY_LIMIT = 2
        const val ACTIVE_STATE_QUERY_LIMIT = 2
    }
}

data class LocalPlanSetupRequest(
    val profile: ProfileSettingsEntity,
    val availabilityDays: List<Int>,
    val candidate: LocalPlanCandidate,
    /** The caller must set this after showing the existing active or pending goal consequence. */
    val confirmReplaceCurrent: Boolean,
    /** Caller supplied, deterministic audit time used if an existing current graph is archived. */
    val archiveAtEpochMillis: Long,
)

sealed interface LocalPlanCandidate {
    val goal: GoalEntity

    data class Generated(val graph: GeneratedPlanPersistenceGraph) : LocalPlanCandidate {
        override val goal: GoalEntity get() = graph.goal
    }

    /** A health-blocked or not-yet-schedulable goal intentionally has no plan rows. */
    data class Pending(override val goal: GoalEntity) : LocalPlanCandidate
}

sealed interface LocalPlanSetupResult {
    data class Created(val goalId: String, val planId: String?) : LocalPlanSetupResult
    data class ReplacementConfirmationRequired(
        val currentGoalCount: Int,
        val activePlanCount: Int,
    ) : LocalPlanSetupResult

    data class Rejected(val error: LocalPlanSetupError) : LocalPlanSetupResult
}

enum class LocalPlanSetupError {
    INVALID_AVAILABILITY,
    INVALID_IDENTITY,
    INVALID_GENERATED_GRAPH,
    PENDING_GOAL_MUST_USE_PENDING_STATE,
    GENERATED_GRAPH_MUST_USE_ACTIVE_STATE,
    IDENTITY_ALREADY_EXISTS,
    CURRENT_STATE_LIMIT_EXCEEDED,
}

/** Pure, side-effect-free gate used before opening a Room transaction. */
object LocalPlanSetupPreparation {
    sealed interface Result
    data object Ready : Result
    data class Invalid(val error: LocalPlanSetupError) : Result

    fun prepare(request: LocalPlanSetupRequest): Result {
        if (request.availabilityDays.distinct().size != request.availabilityDays.size ||
            request.availabilityDays.any { it !in 0..6 }
        ) return Invalid(LocalPlanSetupError.INVALID_AVAILABILITY)
        if (request.archiveAtEpochMillis < 0 || !validId(request.candidate.goal.goalId)) {
            return Invalid(LocalPlanSetupError.INVALID_IDENTITY)
        }

        return when (val candidate = request.candidate) {
            is LocalPlanCandidate.Pending -> {
                if (candidate.goal.state != "pending") Invalid(LocalPlanSetupError.PENDING_GOAL_MUST_USE_PENDING_STATE) else Ready
            }
            is LocalPlanCandidate.Generated -> validateGraph(candidate.graph)
        }
    }

    private fun validateGraph(graph: GeneratedPlanPersistenceGraph): Result {
        if (graph.goal.state != "active" || graph.plan.state != "active") {
            return Invalid(LocalPlanSetupError.GENERATED_GRAPH_MUST_USE_ACTIVE_STATE)
        }
        if (!validId(graph.plan.planId) || graph.plan.goalId != graph.goal.goalId ||
            graph.weeks.any { !validId(it.weekId) || it.planId != graph.plan.planId } ||
            graph.workouts.any { !validId(it.workoutId) || it.planId != graph.plan.planId } ||
            graph.workouts.any { workout -> graph.weeks.none { it.weekId == workout.weekId } } ||
            graph.planSummaryWarnings.any { it.planId != graph.plan.planId } ||
            graph.planSourceReferences.any { it.planId != graph.plan.planId } ||
            graph.blocks.any { block -> graph.workouts.none { it.workoutId == block.workoutId } } ||
            graph.segments.any { segment -> graph.blocks.none { it.blockId == segment.blockId } } ||
            graph.workoutSourceReferences.any { ref -> graph.workouts.none { it.workoutId == ref.workoutId } }
        ) return Invalid(LocalPlanSetupError.INVALID_GENERATED_GRAPH)

        val identities = graph.weeks.map { it.weekId } + graph.workouts.map { it.workoutId } +
            graph.blocks.map { it.blockId } + graph.segments.map { it.segmentId } +
            graph.planSummaryWarnings.map { it.warningId } + graph.planSourceReferences.map { it.referenceId } +
            graph.workoutSourceReferences.map { it.referenceId }
        return if (identities.size == identities.distinct().size) Ready else Invalid(LocalPlanSetupError.INVALID_GENERATED_GRAPH)
    }

    private fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 256
}
