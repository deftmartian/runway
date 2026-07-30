package dev.deftmartian.runway.data

import androidx.room.withTransaction

/**
 * Room implementation of the workout-change boundary. All callers enter through [transaction],
 * so an adjustment ledger row, its immutable before/after record, and its current prescription
 * replacement either commit together or not at all.
 */
class RoomLocalWorkoutChangeStore(
    private val database: RunwayLedgerDatabase,
) : LocalWorkoutChangeStore {
    override suspend fun <T> transaction(block: suspend LocalWorkoutChangeStore.() -> T): T =
        database.withTransaction { block(this@RoomLocalWorkoutChangeStore) }

    override suspend fun loadLedger(planId: String, maximumWorkouts: Int): WorkoutChangeLedgerSnapshot {
        val planDao = database.goalPlanDao()
        val plan = requireNotNull(planDao.plan(planId)) { "Plan was not found." }
        val workoutEntities = planDao.allWorkoutsForPlan(planId, maximumWorkouts)
        val workouts = if (workoutEntities.isEmpty()) {
            emptyList()
        } else {
            storedWorkouts(planDao, workoutEntities)
        }
        return WorkoutChangeLedgerSnapshot(
            plan = plan,
            weeks = planDao.weeksForPlan(planId, maximumWorkouts),
            workouts = workouts,
        )
    }

    override suspend fun adjustmentExists(adjustmentId: String): Boolean =
        database.adjustmentDao().adjustmentExists(adjustmentId)

    override suspend fun persistChange(change: PersistedLocalWorkoutChange) {
        val adjustmentDao = database.adjustmentDao()
        // New runner-added workouts must exist before the adjustment/effect foreign keys refer to
        // them. This remains one enclosing transaction, so no partial current prescription leaks.
        for (mutation in change.mutations) replaceCurrentWorkout(mutation)
        // ABORT, rather than upsert, protects the immutable adjustment and its trigger history.
        adjustmentDao.insertAdjustment(change.adjustment)
        adjustmentDao.saveEffectGroup(change.effectGroup)
        for (effect in change.effects) adjustmentDao.saveWorkoutEffect(effect)
        for (snapshot in change.blockSnapshots) adjustmentDao.saveEffectBlockSnapshot(snapshot)
        for (snapshot in change.segmentSnapshots) adjustmentDao.saveEffectSegmentSnapshot(snapshot)
        for (snapshot in change.sourceReferenceSnapshots) {
            adjustmentDao.saveEffectSourceReferenceSnapshot(snapshot)
        }
        adjustmentDao.saveDecision(change.decision)
    }

    override suspend fun loadUndoChange(adjustmentId: String): StoredUndoChange? {
        val adjustmentDao = database.adjustmentDao()
        val adjustment = adjustmentDao.adjustment(adjustmentId) ?: return null
        val decision = adjustmentDao.decisionForAdjustment(adjustmentId)
            ?: error("Workout change has no decision record.")
        val effects = adjustmentDao.effectGroups(adjustmentId, MAX_EFFECTS).flatMap { group ->
            adjustmentDao.workoutEffects(group.groupId, MAX_EFFECTS)
        }.map { effect ->
            val previousWeekId = requireNotNull(effect.previousWeekId) { "Undo history has no previous week." }
            val newWeekId = requireNotNull(effect.newWeekId) { "Undo history has no new week." }
            StoredUndoEffect(
                effect = effect,
                previousWeekId = previousWeekId,
                newWeekId = newWeekId,
                beforeBlocks = snapshots(effect.effectId, "before"),
                afterBlocks = snapshots(effect.effectId, "after"),
                beforeSourceReferences = references(effect.effectId, "before"),
                afterSourceReferences = references(effect.effectId, "after"),
            )
        }
        return StoredUndoChange(
            adjustment = adjustment,
            decision = decision,
            effects = effects,
            alreadyReversed = adjustmentDao.reversalExistsForDecision(decision.decisionId),
        )
    }

    override suspend fun claimReversal(reversal: PlanReversalEntity): Boolean =
        database.adjustmentDao().insertReversalIfAbsent(reversal) != -1L

    override suspend fun replaceCurrentWorkout(mutation: PersistedWorkoutMutation) {
        val planDao = database.goalPlanDao()
        // Save the row before children so foreign keys apply. Replace every current child table,
        // including the empty case, so prior interval/source structure cannot survive silently.
        planDao.saveWorkout(mutation.workout)
        replaceVersion(
            planDao = planDao,
            workoutId = mutation.workout.workoutId,
            version = "current",
            blocks = mutation.currentBlocks,
            segments = mutation.currentSegments,
            references = mutation.currentSourceReferences,
        )
        if (mutation.generatedBlocks.isNotEmpty() || mutation.generatedSourceReferences.isNotEmpty()) {
            replaceVersion(
                planDao = planDao,
                workoutId = mutation.workout.workoutId,
                version = "generated",
                blocks = mutation.generatedBlocks,
                segments = mutation.generatedSegments,
                references = mutation.generatedSourceReferences,
            )
        }
    }

    private suspend fun storedWorkouts(
        planDao: GoalPlanDao,
        workouts: List<WorkoutEntity>,
    ): List<StoredWorkout> {
        val workoutIds = workouts.map(WorkoutEntity::workoutId)
        val blocks = planDao.blocksForWorkouts(workoutIds, MAX_BLOCKS)
        val blockIds = blocks.map(WorkoutBlockEntity::blockId)
        val segmentsByBlock = if (blockIds.isEmpty()) {
            emptyMap()
        } else {
            planDao.segmentsForBlocks(blockIds, MAX_SEGMENTS).groupBy(WorkoutSegmentEntity::blockId)
        }
        val blocksByWorkoutAndVersion = blocks.groupBy { it.workoutId to it.prescriptionVersion }
        val referencesByWorkoutAndVersion =
            planDao.workoutSourceReferencesForWorkouts(workoutIds, MAX_REFERENCES)
                .groupBy { it.workoutId to it.prescriptionVersion }
        val activityDao = database.activityLedgerDao()
        val workoutsWithFeedback = activityDao.workoutFeedbackForWorkouts(workoutIds, limit = workoutIds.size)
            .mapTo(mutableSetOf(), WorkoutFeedbackEntity::workoutId)
        val workoutsWithActivities = activityDao.activitiesLinkedToWorkouts(workoutIds, limit = workoutIds.size * 2)
            .mapNotNullTo(mutableSetOf(), ActivityEntity::linkedWorkoutId)

        fun storedBlocks(workoutId: String, version: String): List<StoredWorkoutBlock> =
            blocksByWorkoutAndVersion[workoutId to version].orEmpty().map { block ->
                StoredWorkoutBlock(
                    blockType = block.blockType,
                    repetitions = block.repetitions,
                    segments = segmentsByBlock[block.blockId].orEmpty().map { segment ->
                        StoredWorkoutSegment(
                            segment.segmentType,
                            segment.targetDistanceMeters,
                            segment.targetDurationSeconds,
                        )
                    },
                )
            }

        fun storedReferences(workoutId: String, version: String): List<StoredWorkoutSourceReference> =
            referencesByWorkoutAndVersion[workoutId to version].orEmpty().map {
                StoredWorkoutSourceReference(it.sourceName, it.sourceUrl, it.sourceLocator)
            }

        return workouts.map { workout ->
            StoredWorkout(
                entity = workout,
                generatedBlocks = storedBlocks(workout.workoutId, "generated"),
                currentBlocks = storedBlocks(workout.workoutId, "current"),
                generatedSourceReferences = storedReferences(workout.workoutId, "generated"),
                currentSourceReferences = storedReferences(workout.workoutId, "current"),
                addedByAdjustmentId = workout.addedByAdjustmentId,
                hasResult = workout.workoutId in workoutsWithFeedback ||
                    workout.workoutId in workoutsWithActivities,
            )
        }
    }

    private suspend fun snapshots(effectId: String, state: String): List<StoredWorkoutBlock> {
        val dao = database.adjustmentDao()
        val segments = dao.effectSegmentSnapshots(effectId, state, MAX_SNAPSHOTS).groupBy { it.blockSnapshotId }
        return dao.effectBlockSnapshots(effectId, state, MAX_SNAPSHOTS).map { block ->
            StoredWorkoutBlock(
                block.blockType,
                block.repetitions,
                segments[block.blockSnapshotId].orEmpty().map {
                    StoredWorkoutSegment(it.segmentType, it.targetDistanceMeters, it.targetDurationSeconds)
                },
            )
        }
    }

    private suspend fun references(effectId: String, state: String): List<StoredWorkoutSourceReference> =
        database.adjustmentDao().effectSourceReferenceSnapshots(effectId, state, MAX_REFERENCES).map {
            StoredWorkoutSourceReference(it.sourceName, it.sourceUrl, it.sourceLocator)
        }

    private suspend fun replaceVersion(
        planDao: GoalPlanDao,
        workoutId: String,
        version: String,
        blocks: List<WorkoutBlockEntity>,
        segments: List<WorkoutSegmentEntity>,
        references: List<WorkoutSourceReferenceEntity>,
    ) {
        planDao.clearSegmentsForWorkoutVersion(workoutId, version)
        planDao.clearBlocksForWorkoutVersion(workoutId, version)
        planDao.clearWorkoutSourceReferencesForVersion(workoutId, version)
        if (blocks.isNotEmpty()) planDao.insertBlocks(blocks)
        if (segments.isNotEmpty()) planDao.insertSegments(segments)
        if (references.isNotEmpty()) planDao.insertWorkoutSourceReferences(references)
    }

    private companion object {
        const val MAX_EFFECTS = 1_024
        const val MAX_BLOCKS = 10_240
        const val MAX_SEGMENTS = 102_400
        const val MAX_REFERENCES = 10_240
        const val MAX_SNAPSHOTS = 102_400
    }
}

fun RunwayLedgerDatabase.localWorkoutChangeRepository(): LocalWorkoutChangeRepository =
    LocalWorkoutChangeRepository(RoomLocalWorkoutChangeStore(this))
