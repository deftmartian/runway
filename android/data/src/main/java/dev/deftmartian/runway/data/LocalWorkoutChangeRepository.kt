package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.EditPreview
import dev.deftmartian.runway.domain.EditWeek
import dev.deftmartian.runway.domain.EffectiveWorkoutState
import dev.deftmartian.runway.domain.PrescriptionKind
import dev.deftmartian.runway.domain.PrescriptionSegment
import dev.deftmartian.runway.domain.Risk
import dev.deftmartian.runway.domain.RunWalkBlock
import dev.deftmartian.runway.domain.SegmentKind
import dev.deftmartian.runway.domain.TimedIntervalStructure
import dev.deftmartian.runway.domain.WorkoutChange
import dev.deftmartian.runway.domain.WorkoutEdits
import dev.deftmartian.runway.domain.WorkoutProposal
import dev.deftmartian.runway.domain.WorkoutType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate

private const val GENERATED = "generated"
private const val CURRENT = "current"
private const val BEFORE = "before"
private const val AFTER = "after"

data class StoredWorkoutSourceReference(
    val sourceName: String,
    val sourceLocator: String?,
)

data class StoredWorkoutBlock(
    val blockType: String,
    val repetitions: Int,
    val segments: List<StoredWorkoutSegment>,
)

data class StoredWorkoutSegment(
    val segmentType: String,
    val targetDistanceMeters: Int?,
    val targetDurationSeconds: Int?,
)

/**
 * One complete persistence projection. [addedByAdjustmentId] distinguishes a runner-added workout
 * from a generated workout without pretending the added workout existed in the generated plan.
 */
data class StoredWorkout(
    val entity: WorkoutEntity,
    val generatedBlocks: List<StoredWorkoutBlock>,
    val currentBlocks: List<StoredWorkoutBlock>,
    val generatedSourceReferences: List<StoredWorkoutSourceReference>,
    val currentSourceReferences: List<StoredWorkoutSourceReference>,
    val addedByAdjustmentId: String? = null,
    val hasResult: Boolean = false,
)

data class WorkoutChangeLedgerSnapshot(
    val plan: PlanEntity,
    val weeks: List<PlanWeekEntity>,
    val workouts: List<StoredWorkout>,
)

sealed interface LocalWorkoutChangeRequest {
    val rebalanceCompatibleWeek: Boolean

    data class Edit(
        val workoutId: String,
        val proposed: WorkoutProposal,
        val recommended: WorkoutProposal? = null,
        override val rebalanceCompatibleWeek: Boolean = false,
    ) : LocalWorkoutChangeRequest

    data class Add(
        val workoutId: String,
        val proposed: WorkoutProposal,
        val recommended: WorkoutProposal? = null,
        override val rebalanceCompatibleWeek: Boolean = false,
    ) : LocalWorkoutChangeRequest

    data class Remove(
        val workoutId: String,
        override val rebalanceCompatibleWeek: Boolean = false,
    ) : LocalWorkoutChangeRequest

    data class Reset(
        val workoutId: String,
        override val rebalanceCompatibleWeek: Boolean = false,
    ) : LocalWorkoutChangeRequest
}

data class PreparedLocalWorkoutChange(
    val planId: String,
    val operation: String,
    val preview: EditPreview,
    val previewToken: String,
    val selectedWorkoutId: String,
    val existingById: Map<String, StoredWorkout>,
)

data class ApplyLocalWorkoutChangeCommand(
    val adjustmentId: String,
    val decisionId: String,
    val request: LocalWorkoutChangeRequest,
    val expectedPreviewToken: String,
    val riskConfirmed: Boolean,
    val nowEpochMillis: Long,
)

sealed interface ApplyLocalWorkoutChangeResult {
    data class Applied(
        val adjustmentId: String,
        val decisionId: String,
        val preview: EditPreview,
        val affectedWorkoutIds: List<String>,
    ) : ApplyLocalWorkoutChangeResult

    data class AlreadyApplied(val adjustmentId: String) : ApplyLocalWorkoutChangeResult
}

data class PersistedWorkoutMutation(
    val workout: WorkoutEntity,
    val currentBlocks: List<WorkoutBlockEntity>,
    val currentSegments: List<WorkoutSegmentEntity>,
    val currentSourceReferences: List<WorkoutSourceReferenceEntity>,
    val generatedBlocks: List<WorkoutBlockEntity> = emptyList(),
    val generatedSegments: List<WorkoutSegmentEntity> = emptyList(),
    val generatedSourceReferences: List<WorkoutSourceReferenceEntity> = emptyList(),
)

data class PersistedEffectWeekTransition(
    val effectId: String,
    val previousWeekId: String,
    val newWeekId: String,
)

data class PersistedLocalWorkoutChange(
    val adjustment: PlanAdjustmentEntity,
    val effectGroup: AdjustmentEffectGroupEntity,
    val effects: List<AdjustmentWorkoutEffectEntity>,
    val effectWeekTransitions: List<PersistedEffectWeekTransition>,
    val blockSnapshots: List<AdjustmentEffectBlockSnapshotEntity>,
    val segmentSnapshots: List<AdjustmentEffectSegmentSnapshotEntity>,
    val sourceReferenceSnapshots: List<AdjustmentEffectSourceReferenceSnapshotEntity>,
    val decision: PlanDecisionEntity,
    val mutations: List<PersistedWorkoutMutation>,
)

data class StoredUndoEffect(
    val effect: AdjustmentWorkoutEffectEntity,
    val previousWeekId: String,
    val newWeekId: String,
    val beforeBlocks: List<StoredWorkoutBlock>,
    val afterBlocks: List<StoredWorkoutBlock>,
    val beforeSourceReferences: List<StoredWorkoutSourceReference>,
    val afterSourceReferences: List<StoredWorkoutSourceReference>,
)

data class StoredUndoChange(
    val adjustment: PlanAdjustmentEntity,
    val decision: PlanDecisionEntity,
    val effects: List<StoredUndoEffect>,
    val alreadyReversed: Boolean,
)

sealed interface UndoLocalWorkoutChangeResult {
    data class Undone(val adjustmentId: String, val affectedWorkoutIds: List<String>) :
        UndoLocalWorkoutChangeResult

    data class AlreadyUndone(val adjustmentId: String) : UndoLocalWorkoutChangeResult
}

/**
 * The Room-facing boundary needed by [LocalWorkoutChangeRepository].
 *
 * Implementations must use one Room `withTransaction` for [transaction]. Persisting a change must
 * fail atomically when the adjustment ID already exists. Claiming a reversal must use
 * insert-ignore semantics on the unique decision reversal.
 */
interface LocalWorkoutChangeStore {
    suspend fun <T> transaction(block: suspend LocalWorkoutChangeStore.() -> T): T
    suspend fun loadLedger(planId: String, maximumWorkouts: Int): WorkoutChangeLedgerSnapshot
    suspend fun adjustmentExists(adjustmentId: String): Boolean
    suspend fun persistChange(change: PersistedLocalWorkoutChange)
    suspend fun loadUndoChange(adjustmentId: String): StoredUndoChange?
    suspend fun claimReversal(reversal: PlanReversalEntity): Boolean
    suspend fun replaceCurrentWorkout(mutation: PersistedWorkoutMutation)
}

data class LocalWorkoutChangePolicy(
    val maximumWorkoutsPerPlan: Int = 512,
    val maximumBlocksPerWorkoutVersion: Int = 100,
    val maximumSegmentsPerBlock: Int = 100,
    val maximumSourceReferencesPerWorkoutVersion: Int = 100,
)

/** Undo is only safe while every affected prescription is still actionable. */
internal fun assertUndoEligible(
    plan: PlanEntity,
    workout: StoredWorkout,
    today: LocalDate,
) {
    require(plan.state == "active") { "Only the current active plan can be undone." }
    require(workout.entity.currentScheduledEpochDay >= today.toEpochDay()) {
        "A past workout change cannot be undone."
    }
}

class LocalWorkoutChangeRepository(
    private val store: LocalWorkoutChangeStore,
    private val policy: LocalWorkoutChangePolicy = LocalWorkoutChangePolicy(),
) {
    suspend fun preview(
        planId: String,
        request: LocalWorkoutChangeRequest,
        today: LocalDate,
        hasInjuryRisk: Boolean,
    ): PreparedLocalWorkoutChange = store.transaction {
        LocalWorkoutChangePreparer(policy).prepare(
            loadLedger(planId, policy.maximumWorkoutsPerPlan + 1),
            request,
            today,
            hasInjuryRisk,
        )
    }

    suspend fun apply(
        planId: String,
        command: ApplyLocalWorkoutChangeCommand,
        today: LocalDate,
        hasInjuryRisk: Boolean,
    ): ApplyLocalWorkoutChangeResult = store.transaction {
        if (adjustmentExists(command.adjustmentId)) {
            return@transaction ApplyLocalWorkoutChangeResult.AlreadyApplied(command.adjustmentId)
        }
        val prepared = LocalWorkoutChangePreparer(policy).prepare(
            loadLedger(planId, policy.maximumWorkoutsPerPlan + 1),
            command.request,
            today,
            hasInjuryRisk,
        )
        require(prepared.previewToken == command.expectedPreviewToken) {
            "The workout change preview is stale; preview the current plan again."
        }
        require(!prepared.preview.requiresConfirmation || command.riskConfirmed) {
            "This workout change requires explicit risk confirmation."
        }
        val persisted = LocalWorkoutChangePersistenceMapper(policy).map(
            prepared,
            command.adjustmentId,
            command.decisionId,
            command.nowEpochMillis,
        )
        persistChange(persisted)
        ApplyLocalWorkoutChangeResult.Applied(
            adjustmentId = command.adjustmentId,
            decisionId = command.decisionId,
            preview = prepared.preview,
            affectedWorkoutIds = persisted.mutations.map { it.workout.workoutId },
        )
    }

    /**
     * Undo restores only workout current-state columns, blocks, and source references. It never
     * rewrites feedback or activities. A changed workout must still equal the persisted "after"
     * snapshot, preventing undo from overwriting a later workout edit.
     */
    suspend fun undo(
        adjustmentId: String,
        reversalId: String,
        reversedAtEpochMillis: Long,
        today: LocalDate,
    ): UndoLocalWorkoutChangeResult = store.transaction {
        val stored = requireNotNull(loadUndoChange(adjustmentId)) { "Workout change was not found." }
        if (stored.alreadyReversed) return@transaction UndoLocalWorkoutChangeResult.AlreadyUndone(adjustmentId)
        require(stored.adjustment.state == "applied") { "Only an applied workout change can be undone." }

        val ledger = loadLedger(stored.adjustment.planId, policy.maximumWorkoutsPerPlan + 1)
        val byId = ledger.workouts.associateBy { it.entity.workoutId }
        val mutations = stored.effects.map { storedEffect ->
            val workoutId = requireNotNull(storedEffect.effect.workoutId) {
                "Undo history no longer identifies its workout."
            }
            val current = requireNotNull(byId[workoutId]) { "Undo target workout was not found." }
            assertUndoEligible(ledger.plan, current, today)
            val expectedAfter = LocalWorkoutChangeMapper.proposalFromEffect(
                storedEffect.effect,
                storedEffect.previousWeekId,
                storedEffect.newWeekId,
                storedEffect.afterBlocks,
                storedEffect.afterSourceReferences,
                after = true,
            )
            require(LocalWorkoutChangeMapper.currentProposal(current) == expectedAfter) {
                "A later workout edit exists; refusing to overwrite it."
            }
            val before = LocalWorkoutChangeMapper.proposalFromEffect(
                storedEffect.effect,
                storedEffect.previousWeekId,
                storedEffect.newWeekId,
                storedEffect.beforeBlocks,
                storedEffect.beforeSourceReferences,
                after = false,
            )
            LocalWorkoutChangePersistenceMapper(policy).restoreMutation(
                existing = current,
                proposal = before,
                effect = storedEffect.effect,
                sourceReferences = storedEffect.beforeSourceReferences,
                idSeed = "$reversalId:$workoutId",
                planId = current.entity.planId,
                workoutId = workoutId,
                position = current.entity.position,
                updatedAtEpochMillis = reversedAtEpochMillis,
            )
        }

        val claimed = claimReversal(
            PlanReversalEntity(
                reversalId = reversalId,
                decisionId = stored.decision.decisionId,
                reason = "Undo workout change",
                reversedAtEpochMillis = reversedAtEpochMillis,
            ),
        )
        if (!claimed) return@transaction UndoLocalWorkoutChangeResult.AlreadyUndone(adjustmentId)
        mutations.forEach { replaceCurrentWorkout(it) }
        UndoLocalWorkoutChangeResult.Undone(adjustmentId, mutations.map { it.workout.workoutId })
    }
}

class LocalWorkoutChangePreparer(
    private val policy: LocalWorkoutChangePolicy = LocalWorkoutChangePolicy(),
) {
    fun prepare(
        ledger: WorkoutChangeLedgerSnapshot,
        request: LocalWorkoutChangeRequest,
        today: LocalDate,
        hasInjuryRisk: Boolean,
    ): PreparedLocalWorkoutChange {
        require(ledger.plan.state == "active") { "Only the active plan can be changed." }
        require(ledger.workouts.size <= policy.maximumWorkoutsPerPlan) {
            "Plan workout cap exceeded."
        }
        require(ledger.weeks.isNotEmpty()) { "Active plan has no weeks." }
        require(ledger.weeks.all { it.planId == ledger.plan.planId }) { "Plan week ownership is inconsistent." }
        val weeks = ledger.weeks.sortedBy { it.ordinal }
        val weekById = weeks.associateBy { it.weekId }
        val existingById = ledger.workouts.associateBy { it.entity.workoutId }
        require(existingById.size == ledger.workouts.size) { "Duplicate workout IDs were loaded." }
        val states = ledger.workouts.map(LocalWorkoutChangeMapper::state)

        val target: EffectiveWorkoutState
        val proposed: WorkoutProposal
        val recommended: WorkoutProposal?
        val operation: String
        when (request) {
            is LocalWorkoutChangeRequest.Edit -> {
                val existing = target(existingById, request.workoutId, today)
                target = LocalWorkoutChangeMapper.state(existing)
                proposed = request.proposed
                recommended = request.recommended
                operation = "edit"
            }
            is LocalWorkoutChangeRequest.Add -> {
                require(request.workoutId !in existingById) { "Workout ID already exists." }
                require(ledger.workouts.count { it.entity.currentStatus != WORKOUT_STATE_TOMBSTONED } < policy.maximumWorkoutsPerPlan) {
                    "Plan workout cap exceeded."
                }
                proposed = request.proposed
                target = EffectiveWorkoutState(
                    id = request.workoutId,
                    status = "planned",
                    generated = proposed.copy(isRemoved = true),
                    current = proposed.copy(isRemoved = true),
                )
                recommended = request.recommended
                operation = "add"
            }
            is LocalWorkoutChangeRequest.Remove -> {
                val existing = target(existingById, request.workoutId, today)
                target = LocalWorkoutChangeMapper.state(existing)
                proposed = target.current.copy(isRemoved = true)
                recommended = null
                operation = "remove"
            }
            is LocalWorkoutChangeRequest.Reset -> {
                val existing = target(existingById, request.workoutId, today)
                target = LocalWorkoutChangeMapper.state(existing)
                proposed = if (existing.addedByAdjustmentId != null) {
                    target.current.copy(isRemoved = true)
                } else {
                    target.generated
                }
                recommended = null
                operation = "reset"
            }
        }

        guardProposal(proposed, ledger.plan, weekById, today)
        val previewStates = if (target.id in existingById) states else states + target
        val preview = WorkoutEdits.preview(
            current = target,
            recommended = recommended,
            proposed = proposed,
            states = previewStates,
            weeks = weeks.map { EditWeek(it.weekId, it.ordinal) },
            today = today,
            rebalance = request.rebalanceCompatibleWeek,
            hasInjuryRisk = hasInjuryRisk,
            operation = operation,
        )
        preview.workoutChanges.forEach { change ->
            val existing = existingById[change.workoutId]
            if (existing != null) {
                require(!existing.hasResult) { "A workout with a result cannot be changed." }
                require(existing.entity.currentScheduledEpochDay >= today.toEpochDay()) {
                    "A past workout cannot be changed."
                }
                require(existing.entity.currentWorkoutType != "race") { "Race workouts cannot be changed here." }
            }
            guardProposal(change.after, ledger.plan, weekById, today)
        }
        if (request.rebalanceCompatibleWeek) {
            preview.workoutChanges.filterNot { it.selected }.forEach { change ->
                require(change.before.weekId == proposed.weekId && change.after.weekId == proposed.weekId)
                require(change.before.prescriptionKind == proposed.prescriptionKind)
                require(change.after.prescriptionKind == proposed.prescriptionKind)
            }
        } else {
            require(preview.workoutChanges.count { !it.selected } == 0) {
                "A non-selected workout changed without explicit rebalance."
            }
        }
        return PreparedLocalWorkoutChange(
            planId = ledger.plan.planId,
            operation = operation,
            preview = preview,
            previewToken = previewToken(ledger.plan.planId, operation, preview),
            selectedWorkoutId = target.id,
            existingById = existingById,
        )
    }

    private fun target(
        byId: Map<String, StoredWorkout>,
        workoutId: String,
        today: LocalDate,
    ): StoredWorkout {
        val workout = requireNotNull(byId[workoutId]) { "Workout was not found." }
        require(workout.entity.currentStatus != WORKOUT_STATE_TOMBSTONED) {
            "A removed workout cannot be changed without undo."
        }
        require(workout.entity.currentStatus == "planned") {
            "Only a planned workout can be changed."
        }
        require(!workout.hasResult) { "A workout with a result cannot be changed." }
        require(workout.entity.currentScheduledEpochDay >= today.toEpochDay()) {
            "A past workout cannot be changed."
        }
        require(workout.entity.currentWorkoutType != "race") { "Race workouts cannot be changed here." }
        return workout
    }

    private fun guardProposal(
        proposal: WorkoutProposal,
        plan: PlanEntity,
        weekById: Map<String, PlanWeekEntity>,
        today: LocalDate,
    ) {
        WorkoutEdits.assertProposal(proposal)
        val week = requireNotNull(weekById[proposal.weekId]) { "Workout week is not in the active plan." }
        require(week.planId == plan.planId)
        require(!proposal.scheduledDate.isBefore(today)) { "Workout date must be today or later." }
        require(proposal.scheduledDate.toEpochDay() >= plan.startEpochDay)
        require(plan.endEpochDay == null || proposal.scheduledDate.toEpochDay() <= plan.endEpochDay)
        require(proposal.type != WorkoutType.RACE)
        val blockCount = proposal.intervalStructure?.blocks?.size ?: 0
        require(blockCount <= policy.maximumBlocksPerWorkoutVersion)
        require(proposal.intervalStructure?.blocks.orEmpty().all {
            it.segments.size <= policy.maximumSegmentsPerBlock
        })
        require(proposal.sourceRefs.size <= policy.maximumSourceReferencesPerWorkoutVersion)
    }
}

object LocalWorkoutChangeMapper {
    fun state(workout: StoredWorkout): EffectiveWorkoutState = EffectiveWorkoutState(
        id = workout.entity.workoutId,
        status = workout.entity.currentStatus,
        generated = proposal(workout, GENERATED).copy(isRemoved = workout.addedByAdjustmentId != null),
        current = currentProposal(workout),
    )

    fun currentProposal(workout: StoredWorkout): WorkoutProposal =
        proposal(workout, CURRENT).copy(
            isRemoved = workout.entity.currentStatus == WORKOUT_STATE_TOMBSTONED ||
                workout.entity.tombstonedAtEpochMillis != null,
        )

    private fun proposal(workout: StoredWorkout, version: String): WorkoutProposal {
        val generated = version == GENERATED
        val entity = workout.entity
        val blocks = if (generated) workout.generatedBlocks else workout.currentBlocks
        val references =
            if (generated) workout.generatedSourceReferences else workout.currentSourceReferences
        val prescription = enumValue<PrescriptionKind>(
            if (generated) entity.generatedPrescriptionKind else entity.currentPrescriptionKind,
        )
        return WorkoutProposal(
            weekId = entity.weekId,
            scheduledDate = LocalDate.ofEpochDay(
                if (generated) entity.generatedScheduledEpochDay else entity.currentScheduledEpochDay,
            ),
            type = enumValue(
                if (generated) entity.generatedWorkoutType else entity.currentWorkoutType,
            ),
            prescriptionKind = prescription,
            targetDistanceMeters =
                if (generated) entity.generatedDistanceMeters ?: 0 else entity.currentDistanceMeters ?: 0,
            targetDurationSeconds =
                if (generated) entity.generatedDurationSeconds else entity.currentDurationSeconds,
            intervalStructure = if (prescription == PrescriptionKind.TIMED) {
                TimedIntervalStructure(
                    warmupSeconds =
                        if (generated) entity.generatedWarmupSeconds ?: 0 else entity.currentWarmupSeconds ?: 0,
                    cooldownSeconds =
                        if (generated) entity.generatedCooldownSeconds ?: 0 else entity.currentCooldownSeconds ?: 0,
                    blocks = blocks.map { block ->
                        RunWalkBlock(
                            repetitions = block.repetitions,
                            segments = block.segments.map { segment ->
                                PrescriptionSegment(
                                    kind = enumValue(segment.segmentType),
                                    durationSeconds = requireNotNull(segment.targetDurationSeconds),
                                )
                            },
                        )
                    },
                )
            } else {
                null
            },
            intensity =
                (if (generated) entity.generatedIntensity else entity.currentIntensity)
                    ?: if (prescription == PrescriptionKind.REST) "rest" else "easy",
            purpose = (if (generated) entity.generatedPurpose else entity.currentPurpose).orEmpty(),
            reason = (if (generated) entity.generatedReason else entity.currentReason).orEmpty(),
            sourceRefs = references.map { it.sourceLocator ?: it.sourceName },
        )
    }

    fun proposalFromEffect(
        effect: AdjustmentWorkoutEffectEntity,
        previousWeekId: String,
        newWeekId: String,
        blocks: List<StoredWorkoutBlock>,
        references: List<StoredWorkoutSourceReference>,
        after: Boolean,
    ): WorkoutProposal {
        val prescription = enumValue<PrescriptionKind>(
            if (after) effect.newPrescriptionKind else effect.previousPrescriptionKind,
        )
        return WorkoutProposal(
            weekId = if (after) newWeekId else previousWeekId,
            scheduledDate = LocalDate.ofEpochDay(
                requireNotNull(if (after) effect.newScheduledEpochDay else effect.previousScheduledEpochDay),
            ),
            type = enumValue(if (after) effect.newWorkoutType else effect.previousWorkoutType),
            prescriptionKind = prescription,
            targetDistanceMeters =
                (if (after) effect.newDistanceMeters else effect.previousDistanceMeters) ?: 0,
            targetDurationSeconds =
                if (after) effect.newDurationSeconds else effect.previousDurationSeconds,
            intervalStructure = if (prescription == PrescriptionKind.TIMED) {
                TimedIntervalStructure(
                    warmupSeconds =
                        (if (after) effect.newWarmupSeconds else effect.previousWarmupSeconds) ?: 0,
                    cooldownSeconds =
                        (if (after) effect.newCooldownSeconds else effect.previousCooldownSeconds) ?: 0,
                    blocks = blocks.map { block ->
                        RunWalkBlock(
                            block.repetitions,
                            block.segments.map {
                                PrescriptionSegment(
                                    enumValue(it.segmentType),
                                    requireNotNull(it.targetDurationSeconds),
                                )
                            },
                        )
                    },
                )
            } else null,
            intensity = (if (after) effect.newIntensity else effect.previousIntensity)
                ?: if (prescription == PrescriptionKind.REST) "rest" else "easy",
            purpose = (if (after) effect.newPurpose else effect.previousPurpose).orEmpty(),
            reason = (if (after) effect.newReason else effect.previousReason).orEmpty(),
            sourceRefs = references.map { it.sourceLocator ?: it.sourceName },
            isRemoved =
                (if (after) effect.newStatus else effect.previousStatus) == WORKOUT_STATE_TOMBSTONED,
        )
    }

    fun blocks(proposal: WorkoutProposal): List<StoredWorkoutBlock> =
        proposal.intervalStructure?.blocks.orEmpty().map {
            StoredWorkoutBlock(
                blockType = "timed",
                repetitions = it.repetitions,
                segments = it.segments.map { segment ->
                    StoredWorkoutSegment(
                        segmentType = segment.kind.name.lowercase(),
                        targetDistanceMeters = null,
                        targetDurationSeconds = segment.durationSeconds,
                    )
                },
            )
        }

    fun sourceReferences(
        proposal: WorkoutProposal,
        existing: List<StoredWorkoutSourceReference> = emptyList(),
    ): List<StoredWorkoutSourceReference> =
        proposal.sourceRefs.map { locator ->
            existing.firstOrNull {
                it.sourceLocator == locator || it.sourceName == locator
            } ?: StoredWorkoutSourceReference(locator, locator)
        }

    private inline fun <reified T : Enum<T>> enumValue(value: String?): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("Unsupported ${T::class.simpleName} value: $value")
}

class LocalWorkoutChangePersistenceMapper(
    private val policy: LocalWorkoutChangePolicy = LocalWorkoutChangePolicy(),
) {
    /**
     * Rebuilds the typed prescription, then restores nullable ledger scalars and source metadata
     * exactly. The domain proposal intentionally normalizes absent values such as a timed workout's
     * distance to zero; undo must not write those normalized values back over its before-snapshot.
     */
    fun restoreMutation(
        existing: StoredWorkout,
        proposal: WorkoutProposal,
        effect: AdjustmentWorkoutEffectEntity,
        sourceReferences: List<StoredWorkoutSourceReference>,
        idSeed: String,
        planId: String,
        workoutId: String,
        position: Int,
        updatedAtEpochMillis: Long,
    ): PersistedWorkoutMutation {
        val restored = mutation(
            existing = existing,
            proposal = proposal,
            idSeed = idSeed,
            planId = planId,
            workoutId = workoutId,
            position = position,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
        return restored.copy(
            workout = restored.workout.copy(
                currentPurpose = effect.previousPurpose,
                currentDistanceMeters = effect.previousDistanceMeters,
                currentDurationSeconds = effect.previousDurationSeconds,
                tombstonedAtEpochMillis = effect.previousTombstonedAtEpochMillis,
                currentIntensity = effect.previousIntensity,
                currentReason = effect.previousReason,
                currentStatus = requireNotNull(effect.previousStatus),
                currentWarmupSeconds = effect.previousWarmupSeconds,
                currentCooldownSeconds = effect.previousCooldownSeconds,
            ),
            currentSourceReferences = sourceReferenceEntities(
                workoutId = workoutId,
                version = CURRENT,
                idSeed = idSeed,
                references = sourceReferences,
            ),
        )
    }

    fun map(
        prepared: PreparedLocalWorkoutChange,
        adjustmentId: String,
        decisionId: String,
        nowEpochMillis: Long,
    ): PersistedLocalWorkoutChange {
        require(adjustmentId.isNotBlank() && decisionId.isNotBlank())
        val changes = prepared.preview.workoutChanges
        val groupId = stableId("effect-group", adjustmentId)
        val effects = mutableListOf<AdjustmentWorkoutEffectEntity>()
        val blockSnapshots = mutableListOf<AdjustmentEffectBlockSnapshotEntity>()
        val segmentSnapshots = mutableListOf<AdjustmentEffectSegmentSnapshotEntity>()
        val sourceSnapshots = mutableListOf<AdjustmentEffectSourceReferenceSnapshotEntity>()
        val weekTransitions = mutableListOf<PersistedEffectWeekTransition>()
        val mutations = mutableListOf<PersistedWorkoutMutation>()

        changes.forEachIndexed { ordinal, change ->
            val effectId = stableId("effect", adjustmentId, change.workoutId)
            val existing = prepared.existingById[change.workoutId]
            effects += effect(effectId, groupId, ordinal, change).copy(
                previousWeekId = change.before.weekId,
                newWeekId = change.after.weekId,
            )
            weekTransitions += PersistedEffectWeekTransition(
                effectId,
                change.before.weekId,
                change.after.weekId,
            )
            snapshot(
                effectId,
                BEFORE,
                LocalWorkoutChangeMapper.blocks(change.before),
                existing?.currentSourceReferences ?: LocalWorkoutChangeMapper.sourceReferences(change.before),
                blockSnapshots,
                segmentSnapshots,
                sourceSnapshots,
            )
            snapshot(
                effectId,
                AFTER,
                LocalWorkoutChangeMapper.blocks(change.after),
                LocalWorkoutChangeMapper.sourceReferences(
                    change.after,
                    existing?.currentSourceReferences.orEmpty(),
                ),
                blockSnapshots,
                segmentSnapshots,
                sourceSnapshots,
            )
            mutations += mutation(
                existing = existing,
                proposal = change.after,
                idSeed = "$adjustmentId:${change.workoutId}",
                planId = prepared.planId,
                workoutId = change.workoutId,
                position = existing?.entity?.position
                    ?: (prepared.existingById.values.maxOfOrNull { it.entity.position } ?: -1) + ordinal + 1,
                updatedAtEpochMillis = nowEpochMillis,
            )
        }
        val distanceDelta = changes.sumOf {
            (if (it.after.isRemoved) 0 else it.after.targetDistanceMeters) -
                (if (it.before.isRemoved) 0 else it.before.targetDistanceMeters)
        }
        val durationDelta = changes.sumOf {
            (if (it.after.isRemoved) 0 else it.after.targetDurationSeconds ?: 0) -
                (if (it.before.isRemoved) 0 else it.before.targetDurationSeconds ?: 0)
        }
        return PersistedLocalWorkoutChange(
            adjustment = PlanAdjustmentEntity(
                adjustmentId = adjustmentId,
                planId = prepared.planId,
                workoutId = prepared.selectedWorkoutId,
                sourceActivityId = null,
                adjustmentType = prepared.operation,
                state = "applied",
                measuredLoadSharePercent = prepared.preview.weeklyLoadChangePercent,
                projectedRampPercent = prepared.preview.projectedRampPercent,
                affectedWorkoutCount = changes.size,
                createdAtEpochMillis = nowEpochMillis,
            ),
            effectGroup = AdjustmentEffectGroupEntity(
                groupId = groupId,
                adjustmentId = adjustmentId,
                ordinal = 0,
                effectType = "workout_change",
                sourceWeekLoadMeters = prepared.preview.weekLoads.values.sumOf { it.first.distanceMeters },
                destinationWeekLoadMeters = prepared.preview.weekLoads.values.sumOf { it.second.distanceMeters },
                sourceWeekLoadDurationSeconds = prepared.preview.weekLoads.values.sumOf { it.first.durationSeconds },
                destinationWeekLoadDurationSeconds = prepared.preview.weekLoads.values.sumOf { it.second.durationSeconds },
            ),
            effects = effects,
            effectWeekTransitions = weekTransitions,
            blockSnapshots = blockSnapshots,
            segmentSnapshots = segmentSnapshots,
            sourceReferenceSnapshots = sourceSnapshots,
            decision = PlanDecisionEntity(
                decisionId = decisionId,
                adjustmentId = adjustmentId,
                decisionType = prepared.operation,
                affectedWorkoutCount = changes.size,
                effectiveFromEpochDay = changes.minOfOrNull { it.after.scheduledDate.toEpochDay() },
                decidedAtEpochMillis = nowEpochMillis,
                affectedDistanceMeters = distanceDelta,
                affectedDurationSeconds = durationDelta,
            ),
            mutations = mutations,
        )
    }

    fun mutation(
        existing: StoredWorkout?,
        proposal: WorkoutProposal,
        idSeed: String,
        planId: String,
        workoutId: String,
        position: Int,
        updatedAtEpochMillis: Long,
    ): PersistedWorkoutMutation {
        val base = existing?.entity ?: WorkoutEntity(
            workoutId = workoutId,
            planId = planId,
            weekId = proposal.weekId,
            position = position,
            generatedPurpose = proposal.purpose,
            generatedDistanceMeters = proposal.targetDistanceMeters,
            generatedDurationSeconds = proposal.targetDurationSeconds,
            currentPurpose = proposal.purpose,
            currentDistanceMeters = proposal.targetDistanceMeters,
            currentDurationSeconds = proposal.targetDurationSeconds,
            tombstonedAtEpochMillis = null,
            updatedAtEpochMillis = updatedAtEpochMillis,
            generatedScheduledEpochDay = proposal.scheduledDate.toEpochDay(),
            currentScheduledEpochDay = proposal.scheduledDate.toEpochDay(),
            generatedWorkoutType = proposal.type.name.lowercase(),
            currentWorkoutType = proposal.type.name.lowercase(),
            generatedPrescriptionKind = proposal.prescriptionKind.name.lowercase(),
            currentPrescriptionKind = proposal.prescriptionKind.name.lowercase(),
            generatedIntensity = proposal.intensity,
            currentIntensity = proposal.intensity,
            generatedReason = proposal.reason,
            currentReason = proposal.reason,
            generatedWarmupSeconds = proposal.intervalStructure?.warmupSeconds,
            generatedCooldownSeconds = proposal.intervalStructure?.cooldownSeconds,
            currentWarmupSeconds = proposal.intervalStructure?.warmupSeconds,
            currentCooldownSeconds = proposal.intervalStructure?.cooldownSeconds,
            addedByAdjustmentId = idSeed.substringBefore(':').takeIf { it.isNotBlank() },
        )
        val entity = base.copy(
            weekId = proposal.weekId,
            currentPurpose = proposal.purpose,
            currentDistanceMeters = proposal.targetDistanceMeters,
            currentDurationSeconds = proposal.targetDurationSeconds,
            tombstonedAtEpochMillis = if (proposal.isRemoved) updatedAtEpochMillis else null,
            updatedAtEpochMillis = updatedAtEpochMillis,
            currentScheduledEpochDay = proposal.scheduledDate.toEpochDay(),
            currentWorkoutType = proposal.type.name.lowercase(),
            currentPrescriptionKind = proposal.prescriptionKind.name.lowercase(),
            currentIntensity = proposal.intensity,
            currentReason = proposal.reason,
            currentStatus = if (proposal.isRemoved) WORKOUT_STATE_TOMBSTONED else "planned",
            currentWarmupSeconds = proposal.intervalStructure?.warmupSeconds,
            currentCooldownSeconds = proposal.intervalStructure?.cooldownSeconds,
        )
        val blocks = LocalWorkoutChangeMapper.blocks(proposal)
        require(blocks.size <= policy.maximumBlocksPerWorkoutVersion)
        val blockEntities = blockEntities(entity.workoutId, CURRENT, idSeed, blocks)
        val segments = segmentEntities(CURRENT, idSeed, blockEntities, blocks)
        val references = sourceReferenceEntities(
            entity.workoutId,
            CURRENT,
            idSeed,
            proposal,
            existing?.currentSourceReferences.orEmpty(),
        )
        val generatedBlocks = if (existing == null) {
            blockEntities(entity.workoutId, GENERATED, idSeed, blocks)
        } else emptyList()
        val generatedSegments = if (existing == null) {
            segmentEntities(GENERATED, idSeed, generatedBlocks, blocks)
        } else emptyList()
        val generatedReferences = if (existing == null) {
            sourceReferenceEntities(entity.workoutId, GENERATED, idSeed, proposal, emptyList())
        } else emptyList()
        return PersistedWorkoutMutation(
            entity,
            blockEntities,
            segments,
            references,
            generatedBlocks,
            generatedSegments,
            generatedReferences,
        )
    }

    private fun blockEntities(
        workoutId: String,
        version: String,
        idSeed: String,
        blocks: List<StoredWorkoutBlock>,
    ): List<WorkoutBlockEntity> = blocks.mapIndexed { ordinal, block ->
            WorkoutBlockEntity(
                blockId = stableId("$version-block", idSeed, ordinal.toString()),
                workoutId = workoutId,
                prescriptionVersion = version,
                ordinal = ordinal,
                blockType = block.blockType,
                repetitions = block.repetitions,
            )
        }

    private fun segmentEntities(
        version: String,
        idSeed: String,
        blockEntities: List<WorkoutBlockEntity>,
        blocks: List<StoredWorkoutBlock>,
    ): List<WorkoutSegmentEntity> = blockEntities.flatMapIndexed { blockOrdinal, block ->
            blocks[blockOrdinal].segments.mapIndexed { segmentOrdinal, segment ->
                WorkoutSegmentEntity(
                    segmentId = stableId("$version-segment", idSeed, blockOrdinal.toString(), segmentOrdinal.toString()),
                    blockId = block.blockId,
                    ordinal = segmentOrdinal,
                    segmentType = segment.segmentType,
                    targetDistanceMeters = segment.targetDistanceMeters,
                    targetDurationSeconds = segment.targetDurationSeconds,
                )
            }
        }

    private fun sourceReferenceEntities(
        workoutId: String,
        version: String,
        idSeed: String,
        proposal: WorkoutProposal,
        existing: List<StoredWorkoutSourceReference>,
    ): List<WorkoutSourceReferenceEntity> {
        val references = LocalWorkoutChangeMapper.sourceReferences(proposal, existing)
        return references.mapIndexed { ordinal, source ->
            WorkoutSourceReferenceEntity(
                referenceId = stableId("$version-source", idSeed, ordinal.toString(), source.sourceLocator ?: source.sourceName),
                workoutId = workoutId,
                prescriptionVersion = version,
                ordinal = ordinal,
                sourceName = source.sourceName,
                sourceLocator = source.sourceLocator,
            )
        }
    }

    private fun sourceReferenceEntities(
        workoutId: String,
        version: String,
        idSeed: String,
        references: List<StoredWorkoutSourceReference>,
    ): List<WorkoutSourceReferenceEntity> = references.mapIndexed { ordinal, source ->
        WorkoutSourceReferenceEntity(
            referenceId = stableId(
                "$version-source",
                idSeed,
                ordinal.toString(),
                source.sourceLocator ?: source.sourceName,
            ),
            workoutId = workoutId,
            prescriptionVersion = version,
            ordinal = ordinal,
            sourceName = source.sourceName,
            sourceLocator = source.sourceLocator,
        )
    }

    private fun effect(
        effectId: String,
        groupId: String,
        ordinal: Int,
        change: WorkoutChange,
    ) = AdjustmentWorkoutEffectEntity(
        effectId = effectId,
        groupId = groupId,
        workoutId = change.workoutId,
        ordinal = ordinal,
        previousScheduledEpochDay = change.before.scheduledDate.toEpochDay(),
        newScheduledEpochDay = change.after.scheduledDate.toEpochDay(),
        previousWorkoutType = change.before.type.name.lowercase(),
        newWorkoutType = change.after.type.name.lowercase(),
        previousStatus = if (change.before.isRemoved) WORKOUT_STATE_TOMBSTONED else "planned",
        newStatus = if (change.after.isRemoved) WORKOUT_STATE_TOMBSTONED else "planned",
        previousDistanceMeters = change.before.targetDistanceMeters,
        newDistanceMeters = change.after.targetDistanceMeters,
        previousDurationSeconds = change.before.targetDurationSeconds,
        newDurationSeconds = change.after.targetDurationSeconds,
        previousIntensity = change.before.intensity,
        newIntensity = change.after.intensity,
        previousPurpose = change.before.purpose,
        newPurpose = change.after.purpose,
        previousReason = change.before.reason,
        newReason = change.after.reason,
        previousTombstonedAtEpochMillis = null,
        newTombstonedAtEpochMillis = null,
        previousWarmupSeconds = change.before.intervalStructure?.warmupSeconds,
        newWarmupSeconds = change.after.intervalStructure?.warmupSeconds,
        previousCooldownSeconds = change.before.intervalStructure?.cooldownSeconds,
        newCooldownSeconds = change.after.intervalStructure?.cooldownSeconds,
        previousPrescriptionKind = change.before.prescriptionKind.name.lowercase(),
        newPrescriptionKind = change.after.prescriptionKind.name.lowercase(),
    )

    private fun snapshot(
        effectId: String,
        state: String,
        blocks: List<StoredWorkoutBlock>,
        references: List<StoredWorkoutSourceReference>,
        blockOutput: MutableList<AdjustmentEffectBlockSnapshotEntity>,
        segmentOutput: MutableList<AdjustmentEffectSegmentSnapshotEntity>,
        referenceOutput: MutableList<AdjustmentEffectSourceReferenceSnapshotEntity>,
    ) {
        blocks.forEachIndexed { blockOrdinal, block ->
            val blockId = stableId("effect-block", effectId, state, blockOrdinal.toString())
            blockOutput += AdjustmentEffectBlockSnapshotEntity(
                blockSnapshotId = blockId,
                effectId = effectId,
                snapshotState = state,
                ordinal = blockOrdinal,
                blockType = block.blockType,
                repetitions = block.repetitions,
            )
            block.segments.forEachIndexed { segmentOrdinal, segment ->
                segmentOutput += AdjustmentEffectSegmentSnapshotEntity(
                    segmentSnapshotId = stableId("effect-segment", blockId, segmentOrdinal.toString()),
                    blockSnapshotId = blockId,
                    ordinal = segmentOrdinal,
                    segmentType = segment.segmentType,
                    targetDistanceMeters = segment.targetDistanceMeters,
                    targetDurationSeconds = segment.targetDurationSeconds,
                )
            }
        }
        references.forEachIndexed { ordinal, reference ->
            referenceOutput += AdjustmentEffectSourceReferenceSnapshotEntity(
                sourceReferenceSnapshotId = stableId("effect-source", effectId, state, ordinal.toString()),
                effectId = effectId,
                snapshotState = state,
                ordinal = ordinal,
                sourceName = reference.sourceName,
                sourceLocator = reference.sourceLocator,
            )
        }
    }
}

private fun previewToken(planId: String, operation: String, preview: EditPreview): String =
    sha256(
        buildList {
            add(planId)
            add(operation)
            add(preview.risk.name)
            add(preview.projectedRampRisk.name)
            add(preview.requiresConfirmation.toString())
            preview.workoutChanges.sortedBy { it.workoutId }.forEach {
                add(it.workoutId)
                add(proposalFingerprint(it.before))
                add(proposalFingerprint(it.after))
            }
        }.joinToString("\u0000"),
    )

private fun proposalFingerprint(value: WorkoutProposal): String =
    buildList {
        add(value.weekId)
        add(value.scheduledDate.toString())
        add(value.type.name)
        add(value.prescriptionKind.name)
        add(value.targetDistanceMeters.toString())
        add(value.targetDurationSeconds?.toString().orEmpty())
        add(value.intensity)
        add(value.purpose)
        add(value.reason)
        add(value.isRemoved.toString())
        value.sourceRefs.forEach(::add)
        value.intervalStructure?.let { structure ->
            add(structure.warmupSeconds.toString())
            add(structure.cooldownSeconds.toString())
            structure.blocks.forEach { block ->
                add(block.repetitions.toString())
                block.segments.forEach {
                    add(it.kind.name)
                    add(it.durationSeconds.toString())
                }
            }
        }
    }.joinToString("|")

private fun stableId(prefix: String, vararg values: String): String =
    "$prefix-${sha256(values.joinToString("\u0000"))}"

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
