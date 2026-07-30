package dev.deftmartian.runway.data

import androidx.room.withTransaction
import dev.deftmartian.runway.domain.BaselineObservation
import dev.deftmartian.runway.domain.EstablishedTrainingIntake
import dev.deftmartian.runway.domain.Experience
import dev.deftmartian.runway.domain.GeneratedDistancePlan
import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.GoalPriority
import dev.deftmartian.runway.domain.InjuryFlags
import dev.deftmartian.runway.domain.PhaseBaseline
import dev.deftmartian.runway.domain.PhaseTransition
import dev.deftmartian.runway.domain.PhaseTransitions
import dev.deftmartian.runway.domain.PlanPhase
import dev.deftmartian.runway.domain.RaceDistance
import dev.deftmartian.runway.domain.RiskRating
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TrainingPlanner
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Local-only lifecycle boundary. All reads, validation, plan generation, and writes for one
 * operation occur under one Room transaction. Caller-supplied operation/new-plan identities make
 * a retry return the committed result instead of creating another history record or plan.
 */
class LocalPlanLifecycleRepository(
    private val database: RunwayLedgerDatabase,
) {
    suspend fun complete(request: LocalPlanEndRequest): LocalPlanLifecycleResult =
        end(request, completed = true)

    suspend fun archive(request: LocalPlanEndRequest): LocalPlanLifecycleResult =
        end(request, completed = false)

    suspend fun phaseReview(
        planId: String,
        todayEpochDay: Long,
    ): LocalPhaseReviewResult = database.withTransaction {
        val context = loadPhaseContext(planId, todayEpochDay)
            ?: return@withTransaction LocalPhaseReviewResult.Unavailable
        val prepared = LocalPlanLifecyclePreparation.prepareReview(
            phaseType = context.plan.phaseType,
            goalKind = context.goal.kind,
            acceptedLinkedActivities = context.activities,
            observedWeekCount = context.observedWeekCount,
            availabilityDays = context.availabilityDays,
        )
        LocalPhaseReviewResult.Available(
            LocalPhaseReview(
                planId = planId,
                phase = prepared.phase,
                goalKind = prepared.goalKind,
                baseline = prepared.baseline,
                transition = prepared.transition,
                preferredLongRunDay = prepared.preferredLongRunDay,
                racePlanGenerationRequiresConfirmation =
                    prepared.goalKind == GoalKind.RACE &&
                        PhaseTransitions.canUseDistancePlannerBaseline(prepared.baseline),
            ),
        )
    }

    suspend fun confirmRaceBaseline(
        request: LocalRaceBaselineConfirmationRequest,
    ): LocalPlanLifecycleResult = database.withTransaction {
        if (!LocalPlanLifecyclePreparation.validOperationId(request.operationId) ||
            !LocalPlanLifecyclePreparation.validOperationId(request.phasePlanId) ||
            !LocalPlanLifecyclePreparation.validOperationId(request.newPlanId) ||
            request.phasePlanId == request.newPlanId ||
            request.occurredAtEpochMillis < 0
        ) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.INVALID_REQUEST)
        }
        val eventId = stableId("lifecycle", request.operationId)
        val plans = database.goalPlanDao()
        if (plans.planExists(request.newPlanId)) {
            return@withTransaction if (
                lifecycleEventMatches(
                    plans,
                    eventId,
                    request.phasePlanId,
                    "phase_transitioned",
                    request.newPlanId,
                )
            ) {
                LocalPlanLifecycleResult.RacePlanStarted(request.newPlanId, alreadyApplied = true)
            } else {
                LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.IDENTITY_CONFLICT)
            }
        }
        if (plans.lifecycleEvent(eventId) != null) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.IDENTITY_CONFLICT)
        }
        if (!request.explicitlyConfirmed) {
            return@withTransaction LocalPlanLifecycleResult.ConfirmationRequired
        }
        val context = loadPhaseContext(request.phasePlanId, request.todayEpochDay)
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.PHASE_NOT_READY)
        if (context.goal.kind != "race") {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.RACE_GOAL_REQUIRED)
        }
        val prepared = LocalPlanLifecyclePreparation.prepareReview(
            context.plan.phaseType,
            context.goal.kind,
            context.activities,
            context.observedWeekCount,
            context.availabilityDays,
        )
        if (!PhaseTransitions.canUseDistancePlannerBaseline(prepared.baseline)) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.BASELINE_NOT_SUPPORTED)
        }
        val targetEpochDay = context.goal.targetDateEpochDay
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.RACE_GOAL_REQUIRED)
        val raceDistance = LocalPlanLifecyclePreparation.raceDistance(context.goal.raceDistanceMeters)
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.RACE_GOAL_REQUIRED)
        val profile = context.profile
        val generated = runCatching {
            TrainingPlanner.generatePlan(
                EstablishedTrainingIntake(
                    priority = if (context.goal.priority == "consistency") GoalPriority.CONSISTENCY else GoalPriority.FINISH_HEALTHY,
                    experience = when (profile.experienceLevel) {
                        "new" -> Experience.NEW
                        "comfortable" -> Experience.COMFORTABLE
                        else -> Experience.RETURNING
                    },
                    availability = context.availabilityDays,
                    injuryFlags = InjuryFlags(
                        recentInjury = profile.recentInjury,
                        currentPain = profile.currentPain,
                        recurringPain = profile.recurringPain,
                        medicalRestriction = profile.medicalRestriction,
                        notes = profile.privateNotes.orEmpty(),
                    ),
                    raceDistance = raceDistance,
                    targetDate = LocalDate.ofEpochDay(targetEpochDay).toString(),
                    currentWeeklyDistanceMeters = prepared.baseline.weeklyDistanceMeters,
                    currentRunsPerWeek = LocalPlanLifecyclePreparation.normalizedRuns(prepared.baseline),
                    longestRecentRunMeters = prepared.baseline.longestActivityMeters,
                    preferredLongRunDay = prepared.preferredLongRunDay,
                    // A confirmation can happen after the phase ended.  Do not resurrect the
                    // old phase boundary: planner weeks must begin at the current scheduling
                    // boundary so that the newly persisted graph contains no past work.
                    startDate = LocalDate.ofEpochDay(
                        nextSchedulingBoundary(request.todayEpochDay),
                    ).toString(),
                ),
            )
        }.getOrNull() as? GeneratedDistancePlan
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.RACE_PLAN_UNSUPPORTED)
        if (generated.risk == RiskRating.UNSAFE || generated.weeks.size > MAX_PLAN_WEEKS) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.RACE_PLAN_UNSUPPORTED)
        }
        val graph = GeneratedPlanPersistenceMapper.map(
            generated,
            GeneratedPlanGoalMetadata(
                goalId = context.goal.goalId,
                planId = request.newPlanId,
                title = context.goal.title,
                goalKind = GoalKind.RACE,
                startMode = StartMode.ESTABLISHED,
                goalTargetDate = LocalDate.ofEpochDay(targetEpochDay).toString(),
                targetDistanceMeters = context.goal.raceDistanceMeters,
                priority = if (context.goal.priority == "consistency") GoalPriority.CONSISTENCY else GoalPriority.FINISH_HEALTHY,
                createdAtEpochMillis = request.occurredAtEpochMillis,
            ),
        )
        val changed = plans.completePlanIfActive(context.plan.planId, request.occurredAtEpochMillis)
        if (changed != 1) {
            return@withTransaction if (plans.planExists(request.newPlanId)) {
                LocalPlanLifecycleResult.RacePlanStarted(request.newPlanId, alreadyApplied = true)
            } else {
                LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.CONCURRENT_CHANGE)
            }
        }
        database.profileSettingsDao().save(
            profile.copy(
                baselineDistanceMeters = prepared.baseline.weeklyDistanceMeters,
                baselineDurationSeconds = prepared.baseline.totalDurationSeconds,
                baselineConfirmed = true,
                currentRunsPerWeek = LocalPlanLifecyclePreparation.normalizedRuns(prepared.baseline),
                longestRecentRunMeters = prepared.baseline.longestActivityMeters,
                preferredLongRunDay = prepared.preferredLongRunDay,
                updatedAtEpochMillis = request.occurredAtEpochMillis,
            ),
        )
        persistGeneratedPlanGraph(graph)
        insertLifecycleEvent(
            plans,
            eventId,
            context.plan.planId,
            "phase_transitioned",
            request.occurredAtEpochMillis,
            note = request.newPlanId,
        )
        LocalPlanLifecycleResult.RacePlanStarted(request.newPlanId, alreadyApplied = false)
    }

    suspend fun continueBeginnerPhase(
        request: LocalContinuePhaseRequest,
    ): LocalPlanLifecycleResult = database.withTransaction {
        if (!LocalPlanLifecyclePreparation.validOperationId(request.operationId) ||
            !LocalPlanLifecyclePreparation.validOperationId(request.planId) ||
            request.occurredAtEpochMillis < 0
        ) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.INVALID_REQUEST)
        }
        val eventId = stableId("lifecycle", request.operationId)
        val plans = database.goalPlanDao()
        val existingEvent = plans.lifecycleEvent(eventId)
        if (existingEvent != null) {
            return@withTransaction if (
                lifecycleEventMatches(plans, eventId, request.planId, "phase_continued")
            ) {
                LocalPlanLifecycleResult.PhaseContinued(
                    request.planId,
                    existingEvent.note?.toLongOrNull(),
                    alreadyApplied = true,
                )
            } else {
                LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.IDENTITY_CONFLICT)
            }
        }
        val plan = plans.plan(request.planId)
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.PLAN_NOT_FOUND)
        val goal = plans.goal(plan.goalId)
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.PLAN_NOT_FOUND)
        if (goal.state != "active") {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.INVALID_PLAN_STATE)
        }
        if (plan.state != "active" || plan.phaseType !in setOf("foundation", "calibration")) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.BEGINNER_PHASE_REQUIRED)
        }
        val oldEnd = plan.endEpochDay
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.INVALID_PLAN_STATE)
        if (oldEnd > request.todayEpochDay) {
            return@withTransaction LocalPlanLifecycleResult.NotYetAvailable(oldEnd)
        }
        val weeks = plans.weeksForPlan(plan.planId, MAX_PLAN_WEEKS + 1)
        if (weeks.size >= MAX_PLAN_WEEKS) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.PLAN_WEEK_LIMIT_REACHED)
        }
        val lastWeek = weeks.maxByOrNull { it.ordinal }
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.INVALID_PLAN_STATE)
        val oldWorkouts = plans.workoutsForWeek(lastWeek.weekId, limit = MAX_WORKOUTS_PER_WEEK)
        // A delayed continuation must not copy the final phase week into an already elapsed
        // calendar week.  Continue from today when it is a boundary, otherwise from the next
        // Monday; keep the copied weekday offsets within that new week.
        val newWeekStart = nextSchedulingBoundary(request.todayEpochDay)
        val newWeek = copyContinuationWeek(plan, lastWeek, oldWorkouts, request, newWeekStart)
        val newEnd = newWeekStart + 6
        val updated = plans.extendPlanIfCurrentEnd(
            plan.planId,
            oldEnd,
            newEnd,
            request.occurredAtEpochMillis,
        )
        if (updated != 1) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.CONCURRENT_CHANGE)
        }
        plans.insertWeek(newWeek)
        for (oldWorkout in oldWorkouts) {
            val newWorkout = copyContinuationWorkout(oldWorkout, lastWeek, newWeek, request)
            plans.saveWorkout(newWorkout)
            copyEffectiveStructure(plans, oldWorkout, newWorkout)
        }
        if (goal.kind == "foundation") {
            plans.updateActiveGoalTargetDate(goal.goalId, newEnd, request.occurredAtEpochMillis)
        }
        insertLifecycleEvent(
            plans,
            eventId,
            plan.planId,
            "phase_continued",
            request.occurredAtEpochMillis,
            note = newEnd.toString(),
        )
        LocalPlanLifecycleResult.PhaseContinued(plan.planId, newEnd, alreadyApplied = false)
    }

    private suspend fun end(
        request: LocalPlanEndRequest,
        completed: Boolean,
    ): LocalPlanLifecycleResult = database.withTransaction {
        if (!LocalPlanLifecyclePreparation.validOperationId(request.operationId) ||
            !LocalPlanLifecyclePreparation.validOperationId(request.planId) ||
            request.occurredAtEpochMillis < 0 ||
            (!completed && request.reason !in setOf("abandoned", "changed_goal"))
        ) return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.INVALID_REQUEST)
        val eventId = stableId("lifecycle", request.operationId)
        val eventType = if (completed) "completed" else "archived"
        val plans = database.goalPlanDao()
        if (plans.lifecycleEvent(eventId) != null) {
            return@withTransaction if (
                lifecycleEventMatches(plans, eventId, request.planId, eventType)
            ) {
                LocalPlanLifecycleResult.Ended(request.planId, completed, alreadyApplied = true)
            } else {
                LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.IDENTITY_CONFLICT)
            }
        }
        val plan = plans.plan(request.planId)
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.PLAN_NOT_FOUND)
        val goal = plans.goal(plan.goalId)
            ?: return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.PLAN_NOT_FOUND)
        if (goal.state != "active") {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.INVALID_PLAN_STATE)
        }
        if (plan.state != "active") {
            val matching = if (completed) plan.completedAtEpochMillis != null else plan.archivedAtEpochMillis != null
            return@withTransaction if (matching) {
                LocalPlanLifecycleResult.Ended(plan.planId, completed, alreadyApplied = true)
            } else {
                LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.INVALID_PLAN_STATE)
            }
        }
        if (completed && (plan.endEpochDay ?: Long.MAX_VALUE) > request.todayEpochDay) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.TARGET_NOT_REACHED)
        }
        if (completed && plan.phaseType != "distance" && goal.kind == "race") {
            return@withTransaction LocalPlanLifecycleResult.ConfirmationRequired
        }
        val changed = if (completed) {
            plans.completePlanIfActive(plan.planId, request.occurredAtEpochMillis)
        } else {
            plans.archivePlanIfActive(plan.planId, request.occurredAtEpochMillis)
        }
        if (changed != 1) {
            return@withTransaction LocalPlanLifecycleResult.Rejected(LocalPlanLifecycleError.CONCURRENT_CHANGE)
        }
        plans.updateActiveGoalState(
            goal.goalId,
            if (completed) "completed" else "archived",
            request.occurredAtEpochMillis,
        )
        insertLifecycleEvent(
            plans,
            eventId,
            plan.planId,
            eventType,
            request.occurredAtEpochMillis,
            note = if (completed) "completed" else request.reason,
        )
        LocalPlanLifecycleResult.Ended(plan.planId, completed, alreadyApplied = false)
    }

    private suspend fun loadPhaseContext(planId: String, todayEpochDay: Long): PhaseContext? {
        val plans = database.goalPlanDao()
        val plan = plans.plan(planId) ?: return null
        if (plan.state != "active" ||
            plan.phaseType !in setOf("foundation", "calibration") ||
            plan.endEpochDay == null ||
            plan.endEpochDay > todayEpochDay
        ) return null
        val goal = plans.goal(plan.goalId) ?: return null
        if (goal.state != "active") return null
        val profile = database.profileSettingsDao().get() ?: return null
        val availability = database.profileSettingsDao().availabilityDays(limit = 7).map { it.dayOfWeek }
        val weeks = plans.weeksForPlan(plan.planId, MAX_PLAN_WEEKS + 1)
        if (weeks.isEmpty()) return null
        val observedWeeks = minOf(2, weeks.size)
        val fromEpochDay = plan.endEpochDay - (observedWeeks * 7L - 1)
        return PhaseContext(
            plan,
            goal,
            profile,
            availability,
            observedWeeks,
            plans.acceptedLinkedActivitiesForPlan(
                plan.planId,
                fromEpochDay,
                plan.endEpochDay,
                MAX_BASELINE_ACTIVITIES,
            ),
        )
    }

    private suspend fun persistGeneratedPlanGraph(graph: GeneratedPlanPersistenceGraph) {
        val plans = database.goalPlanDao()
        plans.createPlanGraph(graph.plan, graph.weeks, graph.workouts, graph.planSummaryWarnings)
        graph.planSourceReferences.forEach { plans.savePlanSourceReference(it) }
        graph.blocks.forEach { plans.saveBlock(it) }
        graph.segments.forEach { plans.saveSegment(it) }
        graph.workoutSourceReferences.forEach { plans.saveWorkoutSourceReference(it) }
    }

    private suspend fun copyEffectiveStructure(
        plans: GoalPlanDao,
        old: WorkoutEntity,
        new: WorkoutEntity,
    ) {
        val oldBlocks = plans.blocksForWorkout(old.workoutId, "current", MAX_BLOCKS_PER_WORKOUT)
        for (version in listOf("generated", "current")) {
            oldBlocks.forEachIndexed { blockOrdinal, block ->
                val newBlockId = stableId("continued-block", new.workoutId, version, blockOrdinal.toString())
                plans.saveBlock(block.copy(blockId = newBlockId, workoutId = new.workoutId, prescriptionVersion = version))
                plans.segmentsForBlock(block.blockId, MAX_SEGMENTS_PER_BLOCK).forEachIndexed { segmentOrdinal, segment ->
                    plans.saveSegment(
                        segment.copy(
                            segmentId = stableId("continued-segment", newBlockId, segmentOrdinal.toString()),
                            blockId = newBlockId,
                            ordinal = segmentOrdinal,
                        ),
                    )
                }
            }
            val references = plans.workoutSourceReferences(old.workoutId, "current", MAX_SOURCE_REFS)
                .ifEmpty { plans.workoutSourceReferences(old.workoutId, "generated", MAX_SOURCE_REFS) }
            references.forEachIndexed { ordinal, reference ->
                plans.saveWorkoutSourceReference(
                    reference.copy(
                        referenceId = stableId("continued-source", new.workoutId, version, ordinal.toString()),
                        workoutId = new.workoutId,
                        prescriptionVersion = version,
                        ordinal = ordinal,
                    ),
                )
            }
        }
    }

    private fun copyContinuationWeek(
        plan: PlanEntity,
        lastWeek: PlanWeekEntity,
        workouts: List<WorkoutEntity>,
        request: LocalContinuePhaseRequest,
        startEpochDay: Long,
    ): PlanWeekEntity {
        val loadWorkouts = workouts.filter {
            it.currentStatus != WORKOUT_STATE_TOMBSTONED &&
                it.currentWorkoutType !in setOf("rest", "race")
        }
        return PlanWeekEntity(
            weekId = stableId("continued-week", plan.planId, request.operationId),
            planId = plan.planId,
            ordinal = lastWeek.ordinal + 1,
            startEpochDay = startEpochDay,
            generatedLoadMeters = loadWorkouts.sumOf { it.currentDistanceMeters ?: 0 },
            generatedLoadDurationSeconds = loadWorkouts.sumOf { it.currentDurationSeconds ?: 0 },
            riskAssessment = lastWeek.riskAssessment,
            isDownWeek = lastWeek.isDownWeek,
            isTaperWeek = false,
            eventName = null,
            eventEpochDay = null,
            generatedLongRunDistanceMeters = loadWorkouts
                .filter { it.currentWorkoutType == "long" }
                .maxOfOrNull { it.currentDistanceMeters ?: 0 } ?: 0,
        )
    }

    private fun copyContinuationWorkout(
        old: WorkoutEntity,
        lastWeek: PlanWeekEntity,
        newWeek: PlanWeekEntity,
        request: LocalContinuePhaseRequest,
    ): WorkoutEntity {
        val reason = "Repeated after the runner reviewed the completed phase."
        val weekdayOffset = (old.currentScheduledEpochDay - lastWeek.startEpochDay)
            .coerceIn(0, 6)
        val scheduledEpochDay = newWeek.startEpochDay + weekdayOffset
        return WorkoutEntity(
            workoutId = stableId("continued-workout", old.planId, request.operationId, old.workoutId),
            planId = old.planId,
            weekId = newWeek.weekId,
            position = old.position,
            generatedPurpose = old.currentPurpose,
            generatedDistanceMeters = old.currentDistanceMeters,
            generatedDurationSeconds = old.currentDurationSeconds,
            currentPurpose = old.currentPurpose,
            currentDistanceMeters = old.currentDistanceMeters,
            currentDurationSeconds = old.currentDurationSeconds,
            tombstonedAtEpochMillis = null,
            updatedAtEpochMillis = request.occurredAtEpochMillis,
            generatedScheduledEpochDay = scheduledEpochDay,
            currentScheduledEpochDay = scheduledEpochDay,
            generatedWorkoutType = old.currentWorkoutType,
            currentWorkoutType = old.currentWorkoutType,
            generatedPrescriptionKind = old.currentPrescriptionKind,
            currentPrescriptionKind = old.currentPrescriptionKind,
            generatedIntensity = old.currentIntensity,
            currentIntensity = old.currentIntensity,
            generatedReason = reason,
            currentReason = reason,
            currentStatus = "planned",
            generatedWarmupSeconds = old.currentWarmupSeconds,
            generatedCooldownSeconds = old.currentCooldownSeconds,
            currentWarmupSeconds = old.currentWarmupSeconds,
            currentCooldownSeconds = old.currentCooldownSeconds,
        )
    }

    private suspend fun insertLifecycleEvent(
        plans: GoalPlanDao,
        eventId: String,
        planId: String,
        eventType: String,
        occurredAtEpochMillis: Long,
        note: String?,
    ) {
        plans.insertLifecycleEvent(
            PlanLifecycleEventEntity(
                eventId,
                planId,
                eventType,
                occurredAtEpochMillis,
                completedWorkoutCount = null,
                completedActivityCount = null,
                note,
            ),
        )
    }

    private suspend fun lifecycleEventMatches(
        plans: GoalPlanDao,
        eventId: String,
        planId: String,
        eventType: String,
        note: String? = null,
    ): Boolean {
        val event = plans.lifecycleEvent(eventId) ?: return false
        return event.planId == planId &&
            event.eventType == eventType &&
            (note == null || event.note == note)
    }

    /** Monday is the plan boundary; a later day starts at the following Monday. */
    private fun nextSchedulingBoundary(todayEpochDay: Long): Long {
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val daysSinceMonday = today.dayOfWeek.value - 1L
        val currentMonday = today.minusDays(daysSinceMonday)
        return if (currentMonday == today) todayEpochDay else currentMonday.plusDays(7).toEpochDay()
    }

    private data class PhaseContext(
        val plan: PlanEntity,
        val goal: GoalEntity,
        val profile: ProfileSettingsEntity,
        val availabilityDays: List<Int>,
        val observedWeekCount: Int,
        val activities: List<LocalAcceptedLinkedActivity>,
    )

    private companion object {
        const val MAX_PLAN_WEEKS = 52
        const val MAX_WORKOUTS_PER_WEEK = 32
        const val MAX_BLOCKS_PER_WORKOUT = 128
        const val MAX_SEGMENTS_PER_BLOCK = 128
        const val MAX_SOURCE_REFS = 128
        const val MAX_BASELINE_ACTIVITIES = 512
    }
}

data class LocalPlanEndRequest(
    val planId: String,
    val operationId: String,
    val todayEpochDay: Long,
    val occurredAtEpochMillis: Long,
    val reason: String = "abandoned",
)

data class LocalRaceBaselineConfirmationRequest(
    val phasePlanId: String,
    val newPlanId: String,
    val operationId: String,
    val todayEpochDay: Long,
    val occurredAtEpochMillis: Long,
    val explicitlyConfirmed: Boolean,
)

data class LocalContinuePhaseRequest(
    val planId: String,
    val operationId: String,
    val todayEpochDay: Long,
    val occurredAtEpochMillis: Long,
)

sealed interface LocalPlanLifecycleResult {
    data class Ended(val planId: String, val completed: Boolean, val alreadyApplied: Boolean) : LocalPlanLifecycleResult
    data class RacePlanStarted(val planId: String, val alreadyApplied: Boolean) : LocalPlanLifecycleResult
    data class PhaseContinued(val planId: String, val targetEpochDay: Long?, val alreadyApplied: Boolean) : LocalPlanLifecycleResult
    data class NotYetAvailable(val targetEpochDay: Long) : LocalPlanLifecycleResult
    data object ConfirmationRequired : LocalPlanLifecycleResult
    data class Rejected(val error: LocalPlanLifecycleError) : LocalPlanLifecycleResult
}

enum class LocalPlanLifecycleError {
    INVALID_REQUEST,
    PLAN_NOT_FOUND,
    INVALID_PLAN_STATE,
    TARGET_NOT_REACHED,
    BEGINNER_PHASE_REQUIRED,
    PHASE_NOT_READY,
    RACE_GOAL_REQUIRED,
    BASELINE_NOT_SUPPORTED,
    RACE_PLAN_UNSUPPORTED,
    PLAN_WEEK_LIMIT_REACHED,
    CONCURRENT_CHANGE,
    IDENTITY_CONFLICT,
}

data class LocalAcceptedLinkedActivity(
    val activityId: String,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
)

data class LocalPhaseReview(
    val planId: String,
    val phase: PlanPhase,
    val goalKind: GoalKind,
    val baseline: PhaseBaseline,
    val transition: PhaseTransition,
    val preferredLongRunDay: Int,
    val racePlanGenerationRequiresConfirmation: Boolean,
)

sealed interface LocalPhaseReviewResult {
    data class Available(val review: LocalPhaseReview) : LocalPhaseReviewResult
    data object Unavailable : LocalPhaseReviewResult
}

data class PreparedLocalPhaseReview(
    val phase: PlanPhase,
    val goalKind: GoalKind,
    val baseline: PhaseBaseline,
    val transition: PhaseTransition,
    val preferredLongRunDay: Int,
)

/** Pure preparation used by unit tests and by the transactional repository. */
object LocalPlanLifecyclePreparation {
    fun prepareReview(
        phaseType: String,
        goalKind: String,
        acceptedLinkedActivities: List<LocalAcceptedLinkedActivity>,
        observedWeekCount: Int,
        availabilityDays: List<Int>,
    ): PreparedLocalPhaseReview {
        require(observedWeekCount in 1..2)
        require(acceptedLinkedActivities.size <= 512)
        require(acceptedLinkedActivities.map { it.activityId }.distinct().size == acceptedLinkedActivities.size)
        require(availabilityDays.isNotEmpty() && availabilityDays.distinct().size == availabilityDays.size)
        require(availabilityDays.all { it in 0..6 })
        val phase = when (phaseType) {
            "foundation" -> PlanPhase.FOUNDATION
            "calibration" -> PlanPhase.CALIBRATION
            else -> error("A distance plan has no beginner phase review.")
        }
        val kind = when (goalKind) {
            "race" -> GoalKind.RACE
            "foundation" -> GoalKind.FOUNDATION
            else -> error("Unknown goal kind.")
        }
        val baseline = PhaseTransitions.deriveBaseline(
            acceptedLinkedActivities.map {
                BaselineObservation(it.distanceMeters, it.durationSeconds, completed = true)
            },
            observedWeekCount.toDouble(),
        )
        val preferred = preferredLongRunDay(availabilityDays, normalizedRuns(baseline))
        val canConfirm = kind == GoalKind.RACE && PhaseTransitions.canUseDistancePlannerBaseline(baseline)
        return PreparedLocalPhaseReview(
            phase,
            kind,
            baseline,
            PhaseTransitions.options(phase, kind, baseline, raceRampSupported = canConfirm),
            preferred,
        )
    }

    fun normalizedRuns(baseline: PhaseBaseline): Int =
        baseline.runsPerWeek.toInt().coerceIn(2, 5)

    fun preferredLongRunDay(availability: List<Int>, runCount: Int): Int =
        availability.firstOrNull { day ->
            val recoveryDay = (day + 1) % 7
            availability.count { it != recoveryDay } >= runCount
        } ?: availability.firstOrNull() ?: 6

    fun raceDistance(meters: Int?): RaceDistance? = when (meters) {
        5_000 -> RaceDistance.FIVE_K
        10_000 -> RaceDistance.TEN_K
        21_100 -> RaceDistance.HALF
        42_200 -> RaceDistance.MARATHON
        else -> null
    }

    fun validOperationId(value: String): Boolean = value.isNotBlank() && value.length <= 256
}

private fun stableId(prefix: String, vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "$prefix-$digest"
}
