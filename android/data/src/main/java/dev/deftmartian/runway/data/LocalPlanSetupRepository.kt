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
            val candidate = request.candidate
            val candidatePlanId = candidate.planId
            database.planSetupReceiptDao().receipt(request.operationId)?.let { receipt ->
                if (receipt.operationFingerprint != request.operationFingerprint) {
                    return@withTransaction LocalPlanSetupResult.Rejected(
                        LocalPlanSetupError.OPERATION_ID_REUSED,
                    )
                }
                if (
                    receipt.goalId != candidate.goal.goalId ||
                    receipt.planId != candidatePlanId ||
                    !plans.goalExists(receipt.goalId) ||
                    (receipt.planId != null && !plans.planExists(receipt.planId))
                ) {
                    return@withTransaction LocalPlanSetupResult.Rejected(
                        LocalPlanSetupError.OPERATION_RECEIPT_INVALID,
                    )
                }
                return@withTransaction LocalPlanSetupResult.AlreadyCreated(
                    receipt.goalId,
                    receipt.planId,
                )
            }

            val currentGoalIds = plans.currentGoalIds(CURRENT_STATE_QUERY_LIMIT)
            val activePlanIds = plans.activePlanIds(ACTIVE_STATE_QUERY_LIMIT)
            val currentGoalCount = currentGoalIds.size
            val activePlanCount = activePlanIds.size
            if (currentGoalCount > 1 || activePlanCount > 1) {
                return@withTransaction LocalPlanSetupResult.Rejected(LocalPlanSetupError.CURRENT_STATE_LIMIT_EXCEEDED)
            }

            if ((currentGoalCount > 0 || activePlanCount > 0) && !request.confirmReplaceCurrent) {
                return@withTransaction LocalPlanSetupResult.ReplacementConfirmationRequired(
                    currentGoalCount = currentGoalCount,
                    activePlanCount = activePlanCount,
                )
            }

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

            val profiles = database.profileSettingsDao()
            profiles.replaceOnboardingInputs(
                request.profile.preservingLocalPreferencesFrom(profiles.get()),
                request.availabilityDays,
            )
            when (candidate) {
                is LocalPlanCandidate.Generated -> {
                    val graph = candidate.graph
                    plans.saveGoal(graph.goal)
                    plans.createPlanGraph(graph.plan, graph.weeks, graph.workouts, graph.planSummaryWarnings)
                    for (reference in graph.planSourceReferences) plans.savePlanSourceReference(reference)
                    for (block in graph.blocks) plans.saveBlock(block)
                    for (segment in graph.segments) plans.saveSegment(segment)
                    for (reference in graph.workoutSourceReferences) plans.saveWorkoutSourceReference(reference)
                }
                is LocalPlanCandidate.Routine -> {
                    plans.saveGoal(candidate.graph.goal)
                    plans.createRoutineGraph(candidate.graph)
                }
                is LocalPlanCandidate.Pending -> plans.saveGoal(candidate.goal)
            }
            database.planSetupReceiptDao().insert(
                PlanSetupReceiptEntity(
                    operationId = request.operationId,
                    operationFingerprint = request.operationFingerprint,
                    goalId = candidate.goal.goalId,
                    planId = candidatePlanId,
                    committedAtEpochMillis = request.archiveAtEpochMillis,
                ),
            )
            LocalPlanSetupResult.Created(candidate.goal.goalId, candidatePlanId)
        }
    }

    private companion object {
        /** Two rows are enough to prove the single-runner ledger has become ambiguous. */
        const val CURRENT_STATE_QUERY_LIMIT = 2
        const val ACTIVE_STATE_QUERY_LIMIT = 2
    }
}

/**
 * Setup owns schedule, baseline, time-zone, and health-context inputs. Settings owns privacy and
 * heart-rate configuration, so creating or replacing a plan must not reset those explicit choices.
 */
internal fun ProfileSettingsEntity.preservingLocalPreferencesFrom(
    existing: ProfileSettingsEntity?,
): ProfileSettingsEntity = existing?.let {
    copy(
        routeDataMode = it.routeDataMode,
        heartRateDataMode = it.heartRateDataMode,
        heartRateSettingsSource = it.heartRateSettingsSource,
        maxHeartRateBpm = it.maxHeartRateBpm,
        zone2FloorBpm = it.zone2FloorBpm,
        zone3FloorBpm = it.zone3FloorBpm,
        zone4FloorBpm = it.zone4FloorBpm,
        zone5FloorBpm = it.zone5FloorBpm,
        experienceLevel = it.experienceLevel,
        sexForEstimates = it.sexForEstimates,
        ageYears = it.ageYears,
    )
} ?: this

data class LocalPlanSetupRequest(
    /** Stable for one form submission, including retries after process recreation. */
    val operationId: String,
    /** SHA-256 of normalized setup fields, excluding confirmation and attempt time. */
    val operationFingerprint: String,
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
    val planId: String?

    data class Generated(val graph: GeneratedPlanPersistenceGraph) : LocalPlanCandidate {
        override val goal: GoalEntity get() = graph.goal
        override val planId: String get() = graph.plan.planId
    }

    data class Routine(val graph: RoutinePlanPersistenceGraph) : LocalPlanCandidate {
        override val goal: GoalEntity get() = graph.goal
        override val planId: String get() = graph.plan.planId
    }

    /** A health-blocked or not-yet-schedulable goal intentionally has no plan rows. */
    data class Pending(override val goal: GoalEntity) : LocalPlanCandidate {
        override val planId: String? = null
    }
}

sealed interface LocalPlanSetupResult {
    data class Created(val goalId: String, val planId: String?) : LocalPlanSetupResult
    data class AlreadyCreated(val goalId: String, val planId: String?) : LocalPlanSetupResult
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
    INVALID_ROUTINE_GRAPH,
    PENDING_GOAL_MUST_USE_PENDING_STATE,
    GENERATED_GRAPH_MUST_USE_ACTIVE_STATE,
    IDENTITY_ALREADY_EXISTS,
    CURRENT_STATE_LIMIT_EXCEEDED,
    OPERATION_ID_REUSED,
    OPERATION_RECEIPT_INVALID,
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
        if (
            request.archiveAtEpochMillis < 0 ||
            !validId(request.operationId) ||
            !SHA_256.matches(request.operationFingerprint) ||
            !validId(request.candidate.goal.goalId)
        ) {
            return Invalid(LocalPlanSetupError.INVALID_IDENTITY)
        }

        return when (val candidate = request.candidate) {
            is LocalPlanCandidate.Pending -> {
                if (candidate.goal.state != "pending") Invalid(LocalPlanSetupError.PENDING_GOAL_MUST_USE_PENDING_STATE) else Ready
            }
            is LocalPlanCandidate.Generated -> validateGraph(candidate.graph)
            is LocalPlanCandidate.Routine -> validateRoutineGraph(candidate.graph)
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

    private fun validateRoutineGraph(graph: RoutinePlanPersistenceGraph): Result {
        if (
            graph.goal.state != "active" || graph.goal.kind != "routine" ||
            graph.goal.startMode != "routine" || graph.goal.targetDateEpochDay != null ||
            graph.plan.state != "active" || graph.plan.goalId != graph.goal.goalId ||
            graph.plan.phaseType != "routine" || graph.plan.endEpochDay != null ||
            !validId(graph.plan.planId) ||
            graph.days.size !in 1..7 ||
            graph.days.map { it.dayOfWeek }.distinct().size != graph.days.size ||
            graph.days.any { it.planId != graph.plan.planId || it.dayOfWeek !in 0..6 } ||
            graph.weeks.size != RoutinePlanPersistenceMapper.INITIAL_HORIZON_WEEKS ||
            graph.weeks.any { !validId(it.weekId) || it.planId != graph.plan.planId } ||
            graph.workouts.any { !validId(it.workoutId) || it.planId != graph.plan.planId } ||
            graph.workouts.any { workout -> graph.weeks.none { it.weekId == workout.weekId } } ||
            graph.workouts.any {
                it.generatedPrescriptionKind != "open" || it.currentPrescriptionKind != "open" ||
                    it.generatedDistanceMeters != null || it.currentDistanceMeters != null ||
                    it.generatedDurationSeconds != null || it.currentDurationSeconds != null
            }
        ) return Invalid(LocalPlanSetupError.INVALID_ROUTINE_GRAPH)
        val weeks = graph.weeks.sortedBy(PlanWeekEntity::ordinal)
        if (weeks.map(PlanWeekEntity::ordinal) != (1..RoutinePlanPersistenceMapper.INITIAL_HORIZON_WEEKS).toList() ||
            weeks.zipWithNext().any { (before, after) -> after.startEpochDay != before.startEpochDay + 7 }
        ) return Invalid(LocalPlanSetupError.INVALID_ROUTINE_GRAPH)
        val expected = runCatching {
            RoutinePlanPersistenceMapper.map(
                goalId = graph.goal.goalId,
                planId = graph.plan.planId,
                title = graph.goal.title,
                priority = graph.goal.priority,
                startEpochDay = graph.plan.startEpochDay,
                selectedDays = graph.days.map(RoutineScheduleDayEntity::dayOfWeek),
                createdAtEpochMillis = graph.goal.createdAtEpochMillis,
            )
        }.getOrNull()
        if (expected != graph) return Invalid(LocalPlanSetupError.INVALID_ROUTINE_GRAPH)
        val identities = graph.weeks.map(PlanWeekEntity::weekId) + graph.workouts.map(WorkoutEntity::workoutId)
        return if (graph.workouts.isNotEmpty() && graph.workouts.all { it.currentScheduledEpochDay >= graph.plan.startEpochDay } &&
            graph.workouts.size <= graph.days.size * graph.weeks.size && identities.size == identities.distinct().size
        ) {
            Ready
        } else {
            Invalid(LocalPlanSetupError.INVALID_ROUTINE_GRAPH)
        }
    }

    private fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 256
    private val SHA_256 = Regex("[0-9a-f]{64}")
}
