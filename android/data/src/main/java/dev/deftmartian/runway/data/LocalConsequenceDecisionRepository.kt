package dev.deftmartian.runway.data

import androidx.room.withTransaction
import dev.deftmartian.runway.domain.Consequence
import dev.deftmartian.runway.domain.ConsequenceKind
import dev.deftmartian.runway.domain.Deviation
import dev.deftmartian.runway.domain.ExtraActivityInput
import dev.deftmartian.runway.domain.ExtraActivityTargets
import dev.deftmartian.runway.domain.LoadDelta
import dev.deftmartian.runway.domain.LoadMetric
import dev.deftmartian.runway.domain.PlanDecision
import dev.deftmartian.runway.domain.Risk
import dev.deftmartian.runway.domain.calculateExtraActivityConsequence
import dev.deftmartian.runway.domain.historicalExtraActivityReview
import dev.deftmartian.runway.domain.isHistoricalExtraActivity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Persists a runner's explicit consequence choice after re-reading the mutable plan state. */
class LocalConsequenceDecisionRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun preview(input: LocalDecisionInput): LocalDecisionResult =
        LocalConsequenceDecisionEngine.preview(input)

    /**
     * Builds a preview from current ledger evidence instead of trusting presentation-layer copies
     * of a consequence, source version, or future plan.
     */
    suspend fun prepare(
        kind: LocalDecisionSourceKind,
        sourceId: String,
        decision: PlanDecision,
    ): LocalConsequenceDecisionPreparation = database.withTransaction {
        val source = currentSource(kind, sourceId)
            ?: return@withTransaction LocalConsequenceDecisionPreparation.Rejected(
                LocalDecisionIssue.PROPOSAL_NOT_AVAILABLE,
            )
        val input = canonicalInput(source, decision)
            ?: return@withTransaction LocalConsequenceDecisionPreparation.Rejected(
                LocalDecisionIssue.PROPOSAL_NOT_AVAILABLE,
            )
        when (val preview = LocalConsequenceDecisionEngine.preview(input)) {
            is LocalDecisionResult.Preview ->
                LocalConsequenceDecisionPreparation.Prepared(input, preview)
            is LocalDecisionResult.Rejected ->
                LocalConsequenceDecisionPreparation.Rejected(preview.issue)
        }
    }

    suspend fun apply(
        preview: LocalDecisionResult.Preview,
        input: LocalDecisionInput,
        adjustmentId: String,
        decisionId: String,
        appliedAtEpochMillis: Long,
    ): LocalConsequenceDecisionPersistenceResult = database.withTransaction {
        require(adjustmentId.isNotBlank() && decisionId.isNotBlank())
        val adjustmentDao = database.adjustmentDao()
        val source = preview.source
        if (input.source.kind != source.kind || input.source.sourceId != source.sourceId ||
            input.decision != preview.decision
        ) {
            return@withTransaction LocalConsequenceDecisionPersistenceResult.Rejected(LocalDecisionIssue.STALE_PREVIEW)
        }
        if (adjustmentDao.adjustmentExistsForTrigger(source.kind.name, source.sourceId, source.version)) {
            return@withTransaction LocalConsequenceDecisionPersistenceResult.AlreadyApplied
        }
        val current = canonicalInput(source, preview.decision)
            ?: return@withTransaction LocalConsequenceDecisionPersistenceResult.Rejected(LocalDecisionIssue.PROPOSAL_NOT_AVAILABLE)
        val planDao = database.goalPlanDao()
        val planId = current.originWorkout?.planId
            ?: planDao.activePlans(limit = 2).singleOrNull()?.planId
            ?: return@withTransaction LocalConsequenceDecisionPersistenceResult.Rejected(LocalDecisionIssue.NO_FUTURE_WORKOUT)
        val ready = LocalConsequenceDecisionEngine.apply(preview, current, alreadyApplied = false)
        if (ready is LocalDecisionApplyResult.Rejected) {
            return@withTransaction LocalConsequenceDecisionPersistenceResult.Rejected(ready.issue)
        }
        ready as LocalDecisionApplyResult.Ready
        if (!sourceOffersDecision(source, ready.decision)) {
            return@withTransaction LocalConsequenceDecisionPersistenceResult.Rejected(LocalDecisionIssue.DECISION_NOT_OFFERED)
        }
        // This guarded update is the commit claim. Any concurrent or repeated submit changes zero rows.
        if (!claimSource(source, ready.decision, appliedAtEpochMillis)) {
            return@withTransaction LocalConsequenceDecisionPersistenceResult.AlreadyApplied
        }
        val groupId = "consequence-effects-$adjustmentId"
        adjustmentDao.insertAdjustment(
            PlanAdjustmentEntity(
                adjustmentId = adjustmentId,
                planId = planId,
                workoutId = current.originWorkout?.workoutId,
                sourceActivityId = source.sourceId.takeIf { source.kind == LocalDecisionSourceKind.Activity },
                adjustmentType = ready.decision.toStorageValue(),
                state = "applied",
                measuredLoadSharePercent = null,
                projectedRampPercent = null,
                affectedWorkoutCount = ready.changes.size,
                createdAtEpochMillis = appliedAtEpochMillis,
                triggerKind = source.kind.name,
                triggerId = source.sourceId,
                triggerVersion = source.version,
            ),
        )
        adjustmentDao.saveEffectGroup(
            AdjustmentEffectGroupEntity(groupId, adjustmentId, 0, "consequence_decision", null, null),
        )
        ready.changes.forEachIndexed { ordinal, change ->
            val after = change.after.copy(updatedAtEpochMillis = appliedAtEpochMillis)
            val effectId = "$adjustmentId-effect-$ordinal"
            adjustmentDao.saveWorkoutEffect(effect(effectId, groupId, ordinal, change.before, after))
            saveCurrentStructureSnapshot(
                effectId = effectId,
                snapshotState = "before",
                workoutId = change.before.workoutId,
            )
            planDao.saveWorkout(after)
            replaceCurrentStructure(
                planDao = planDao,
                before = change.before,
                after = after,
                repeatSource = current.originWorkout,
                seed = "$adjustmentId-$ordinal",
            )
            saveCurrentStructureSnapshot(
                effectId = effectId,
                snapshotState = "after",
                workoutId = after.workoutId,
            )
        }
        adjustmentDao.saveDecision(
            PlanDecisionEntity(
                decisionId = decisionId,
                adjustmentId = adjustmentId,
                decisionType = ready.decision.toStorageValue(),
                affectedWorkoutCount = ready.changes.size,
                effectiveFromEpochDay = ready.changes.minOfOrNull { it.after.currentScheduledEpochDay },
                decidedAtEpochMillis = appliedAtEpochMillis,
            ),
        )
        adjustmentDao.saveDecisionConsequence(
            DecisionConsequenceEntity(decisionId, null, null, null, appliedAtEpochMillis),
        )
        LocalConsequenceDecisionPersistenceResult.Applied(adjustmentId, decisionId, ready.changes.map { it.after.workoutId })
    }

    /**
     * The caller may carry a preview, but never the authority to choose its evidence, origin, or
     * target plan. Those values are rebuilt from the immutable feedback/activity ledger each time
     * an apply is attempted.
     */
    private suspend fun canonicalInput(
        source: LocalDecisionSource,
        decision: PlanDecision,
    ): LocalDecisionInput? {
        val profile = database.profileSettingsDao().get() ?: return null
        val zone = runCatching { ZoneId.of(profile.timeZone) }.getOrNull() ?: return null
        val today = Instant.ofEpochMilli(nowEpochMillis()).atZone(zone).toLocalDate()
        val activityDao = database.activityLedgerDao()
        val planDao = database.goalPlanDao()
        return when (source.kind) {
            LocalDecisionSourceKind.WorkoutFeedback -> {
                val feedback = activityDao.workoutFeedbackById(source.sourceId) ?: return null
                if (feedback.recordedAtEpochMillis.toString() != source.version) return null
                val stored = activityDao.workoutFeedbackConsequence(source.sourceId) ?: return null
                val workout = planDao.workout(feedback.workoutId) ?: return null
                val plan = planDao.plan(workout.planId)?.takeIf { it.state == "active" } ?: return null
                val consequence = stored.toCanonicalConsequence(
                    activityDao.workoutFeedbackConsequenceOptions(source.sourceId, MAX_OPTIONS),
                ) ?: return null
                LocalDecisionInput(
                    source = source,
                    decision = decision,
                    consequence = consequence,
                    originEpochDay = workout.currentScheduledEpochDay,
                    todayEpochDay = today.toEpochDay(),
                    originWorkout = workout,
                    candidates = planDao.visibleWorkoutsForPlan(plan.planId, limit = MAX_PLAN_WORKOUTS),
                )
            }
            LocalDecisionSourceKind.Activity -> {
                val activity = activityDao.activity(source.sourceId)
                    ?.takeIf {
                        it.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED &&
                            it.linkedWorkoutId == null &&
                            it.extraPlanImpactConfirmed
                    } ?: return null
                val feedback = activityDao.activityFeedback(activity.activityId)
                val currentVersion =
                    (feedback?.recordedAtEpochMillis ?: activity.updatedAtEpochMillis).toString()
                if (currentVersion != source.version) return null
                val stored = activityDao.activityConsequence(activity.activityId) ?: return null
                val plan = planDao.activePlans(limit = 2).singleOrNull() ?: return null
                val candidates = planDao.visibleWorkoutsForPlan(plan.planId, limit = MAX_PLAN_WORKOUTS)
                val activityDate = Instant.ofEpochMilli(activity.occurredAtEpochMillis).atZone(zone).toLocalDate()
                val next = eligibleFutureDecisionWorkouts(
                    candidates = candidates,
                    originEpochDay = activityDate.toEpochDay(),
                    todayEpochDay = today.toEpochDay(),
                ).firstOrNull()
                val weekStart = activityDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val week = candidates.filter {
                    it.currentScheduledEpochDay in weekStart.toEpochDay()..weekStart.plusDays(6).toEpochDay()
                }
                val calculated = calculateExtraActivityConsequence(
                    ExtraActivityInput(
                        distanceMeters = activity.distanceMeters ?: 0,
                        durationSeconds = activity.durationSeconds,
                        feltHard = feedback?.feltHard == true,
                        pain = feedback?.pain == true,
                    ),
                    ExtraActivityTargets(
                        nextRunTargetDistanceMeters = next?.currentDistanceMeters ?: 0,
                        nextRunTargetDurationSeconds = next?.currentDurationSeconds,
                        weekTargetDistanceMeters = week.canonicalDistanceLoad(),
                        weekTargetDurationSeconds = week.canonicalDurationLoad(),
                    ),
                ).let { consequence ->
                    if (next == null || isHistoricalExtraActivity(activityDate, today)) {
                        historicalExtraActivityReview(consequence)
                    } else {
                        consequence
                    }
                }
                val options = activityDao.activityConsequenceOptions(activity.activityId, MAX_OPTIONS)
                if (!stored.matches(calculated, options)) return null
                LocalDecisionInput(
                    source = source,
                    decision = decision,
                    consequence = calculated,
                    originEpochDay = activityDate.toEpochDay(),
                    todayEpochDay = today.toEpochDay(),
                    originWorkout = null,
                    candidates = candidates,
                )
            }
        }
    }

    private suspend fun currentSource(
        kind: LocalDecisionSourceKind,
        sourceId: String,
    ): LocalDecisionSource? {
        if (sourceId.isBlank()) return null
        val dao = database.activityLedgerDao()
        val version = when (kind) {
            LocalDecisionSourceKind.WorkoutFeedback ->
                dao.workoutFeedbackById(sourceId)?.recordedAtEpochMillis
            LocalDecisionSourceKind.Activity -> {
                val activity = dao.activity(sourceId) ?: return null
                dao.activityFeedback(sourceId)?.recordedAtEpochMillis
                    ?: activity.updatedAtEpochMillis
            }
        } ?: return null
        return LocalDecisionSource(kind, sourceId, version.toString())
    }

    private suspend fun sourceOffersDecision(source: LocalDecisionSource, decision: PlanDecision): Boolean {
        val value = decision.toStorageValue()
        val dao = database.activityLedgerDao()
        return when (source.kind) {
            LocalDecisionSourceKind.WorkoutFeedback ->
                dao.workoutFeedbackConsequenceOptions(source.sourceId, MAX_OPTIONS).any { it.decision == value }
            LocalDecisionSourceKind.Activity ->
                dao.activityConsequenceOptions(source.sourceId, MAX_OPTIONS).any { it.decision == value }
        }
    }

    private suspend fun claimSource(source: LocalDecisionSource, decision: PlanDecision, now: Long): Boolean = when (source.kind) {
        LocalDecisionSourceKind.WorkoutFeedback ->
            database.activityLedgerDao().claimWorkoutFeedbackConsequence(
                source.sourceId,
                decision.toStorageValue(),
            ) == 1
        LocalDecisionSourceKind.Activity ->
            database.activityLedgerDao().claimActivityConsequence(
                source.sourceId,
                decision.toStorageValue(),
                now,
            ) == 1
    }

    private fun effect(
        effectId: String,
        groupId: String,
        ordinal: Int,
        before: WorkoutEntity,
        after: WorkoutEntity,
    ) = AdjustmentWorkoutEffectEntity(
        effectId = effectId,
        groupId = groupId,
        workoutId = before.workoutId,
        ordinal = ordinal,
        previousScheduledEpochDay = before.currentScheduledEpochDay,
        newScheduledEpochDay = after.currentScheduledEpochDay,
        previousWorkoutType = before.currentWorkoutType,
        newWorkoutType = after.currentWorkoutType,
        previousStatus = before.currentStatus,
        newStatus = after.currentStatus,
        previousDistanceMeters = before.currentDistanceMeters,
        newDistanceMeters = after.currentDistanceMeters,
        previousDurationSeconds = before.currentDurationSeconds,
        newDurationSeconds = after.currentDurationSeconds,
        previousIntensity = before.currentIntensity,
        newIntensity = after.currentIntensity,
        previousPurpose = before.currentPurpose,
        newPurpose = after.currentPurpose,
        previousReason = before.currentReason,
        newReason = after.currentReason,
        previousTombstonedAtEpochMillis = before.tombstonedAtEpochMillis,
        newTombstonedAtEpochMillis = after.tombstonedAtEpochMillis,
        previousWarmupSeconds = before.currentWarmupSeconds,
        newWarmupSeconds = after.currentWarmupSeconds,
        previousCooldownSeconds = before.currentCooldownSeconds,
        newCooldownSeconds = after.currentCooldownSeconds,
        previousPrescriptionKind = before.currentPrescriptionKind,
        newPrescriptionKind = after.currentPrescriptionKind,
        previousWeekId = before.weekId,
        newWeekId = after.weekId,
    )

    /** Writes the complete structured current prescription, including the intentionally empty rest case. */
    private suspend fun replaceCurrentStructure(
        planDao: GoalPlanDao,
        before: WorkoutEntity,
        after: WorkoutEntity,
        repeatSource: WorkoutEntity?,
        seed: String,
    ) {
        val sourceWorkoutId = when {
            after.currentPrescriptionKind == "rest" -> null
            after.currentReason?.contains(
                "repeat the earlier prescription",
                ignoreCase = true,
            ) == true -> repeatSource?.workoutId
            else -> before.workoutId
        }
        val sourceBlocks = sourceWorkoutId?.let { planDao.blocksForWorkout(it, "current", MAX_BLOCKS) }.orEmpty()
        val blocks = sourceBlocks.mapIndexed { ordinal, block ->
            block.copy(blockId = "$seed-block-$ordinal", workoutId = after.workoutId, prescriptionVersion = "current", ordinal = ordinal)
        }
        val segments = sourceBlocks.flatMapIndexed { blockOrdinal, block ->
            planDao.segmentsForBlock(block.blockId, MAX_SEGMENTS).mapIndexed { ordinal, segment ->
                segment.copy(segmentId = "$seed-segment-$blockOrdinal-$ordinal", blockId = blocks[blockOrdinal].blockId, ordinal = ordinal)
            }
        }
        val references = sourceWorkoutId?.let { planDao.workoutSourceReferences(it, "current", MAX_REFERENCES) }.orEmpty()
            .mapIndexed { ordinal, reference ->
                reference.copy(referenceId = "$seed-source-$ordinal", workoutId = after.workoutId, prescriptionVersion = "current", ordinal = ordinal)
            }
        planDao.clearSegmentsForWorkoutVersion(after.workoutId, "current")
        planDao.clearBlocksForWorkoutVersion(after.workoutId, "current")
        planDao.clearWorkoutSourceReferencesForVersion(after.workoutId, "current")
        if (blocks.isNotEmpty()) planDao.insertBlocks(blocks)
        if (segments.isNotEmpty()) planDao.insertSegments(segments)
        if (references.isNotEmpty()) planDao.insertWorkoutSourceReferences(references)
    }

    /** Records the exact structured state that the shared guarded undo path compares and restores. */
    private suspend fun saveCurrentStructureSnapshot(
        effectId: String,
        snapshotState: String,
        workoutId: String,
    ) {
        val planDao = database.goalPlanDao()
        val adjustmentDao = database.adjustmentDao()
        val blocks = planDao.blocksForWorkout(workoutId, "current", MAX_BLOCKS)
        blocks.forEachIndexed { blockOrdinal, block ->
            val blockSnapshotId = "$effectId-$snapshotState-block-$blockOrdinal"
            adjustmentDao.saveEffectBlockSnapshot(
                AdjustmentEffectBlockSnapshotEntity(
                    blockSnapshotId = blockSnapshotId,
                    effectId = effectId,
                    snapshotState = snapshotState,
                    ordinal = blockOrdinal,
                    blockType = block.blockType,
                    repetitions = block.repetitions,
                ),
            )
            planDao.segmentsForBlock(block.blockId, MAX_SEGMENTS)
                .forEachIndexed { segmentOrdinal, segment ->
                    adjustmentDao.saveEffectSegmentSnapshot(
                        AdjustmentEffectSegmentSnapshotEntity(
                            segmentSnapshotId =
                                "$effectId-$snapshotState-segment-$blockOrdinal-$segmentOrdinal",
                            blockSnapshotId = blockSnapshotId,
                            ordinal = segmentOrdinal,
                            segmentType = segment.segmentType,
                            targetDistanceMeters = segment.targetDistanceMeters,
                            targetDurationSeconds = segment.targetDurationSeconds,
                        ),
                    )
                }
        }
        planDao.workoutSourceReferences(workoutId, "current", MAX_REFERENCES)
            .forEachIndexed { referenceOrdinal, reference ->
                adjustmentDao.saveEffectSourceReferenceSnapshot(
                    AdjustmentEffectSourceReferenceSnapshotEntity(
                        sourceReferenceSnapshotId =
                            "$effectId-$snapshotState-source-$referenceOrdinal",
                        effectId = effectId,
                        snapshotState = snapshotState,
                        ordinal = referenceOrdinal,
                        sourceName = reference.sourceName,
                        sourceUrl = reference.sourceUrl,
                        sourceLocator = reference.sourceLocator,
                    ),
                )
            }
    }

    private companion object {
        const val MAX_PLAN_WORKOUTS = 1_024
        const val MAX_OPTIONS = 16
        const val MAX_BLOCKS = 100
        const val MAX_SEGMENTS = 100
        const val MAX_REFERENCES = 100
    }
}

sealed interface LocalConsequenceDecisionPreparation {
    data class Prepared(
        val input: LocalDecisionInput,
        val preview: LocalDecisionResult.Preview,
    ) : LocalConsequenceDecisionPreparation

    data class Rejected(val issue: LocalDecisionIssue) : LocalConsequenceDecisionPreparation
}

private fun WorkoutFeedbackConsequenceEntity.toCanonicalConsequence(
    options: List<WorkoutFeedbackConsequenceOptionEntity>,
): Consequence? {
    val metric = loadMetric() ?: return null
    val parsedOptions = options.mapNotNull { it.decision.toStoredPlanDecision() }.toSet()
    val kind = classification?.toConsequenceKind() ?: return null
    val deviation = deviation?.toDeviation() ?: return null
    val risk = risk?.toRisk() ?: return null
    val recommended = recommendedDecision?.toStoredPlanDecision() ?: return null
    if (recommended !in parsedOptions || appliedDecision != null) return null
    val difference = when (metric) {
        LoadMetric.DISTANCE -> distanceDifferenceMeters ?: 0
        LoadMetric.DURATION -> durationDifferenceSeconds ?: 0
        LoadMetric.NONE -> 0
    }
    val adjustment = when (metric) {
        LoadMetric.DISTANCE -> (projectedWeekLoadMeters ?: currentWeekLoadMeters)?.minus(currentWeekLoadMeters ?: 0)
        LoadMetric.DURATION -> (projectedWeekLoadDurationSeconds ?: currentWeekLoadDurationSeconds)
            ?.minus(currentWeekLoadDurationSeconds ?: 0)
        LoadMetric.NONE -> null
    }
    return Consequence(
        kind = kind,
        deviation = deviation,
        metric = metric,
        actualDifference = difference,
        weeklyLoadDelta = difference.takeIf { metric != LoadMetric.NONE }?.let { LoadDelta(metric, it) },
        nextRunAdjustment = adjustment?.let { LoadDelta(metric, it) },
        risk = risk,
        recommendedDecision = recommended,
        options = parsedOptions,
        comparisonStatus = comparisonStatus,
        planChangeAvailable = planChangeAvailable,
    )
}

internal fun ActivityConsequenceEntity.matches(
    consequence: Consequence,
    options: List<ActivityConsequenceOptionEntity>,
): Boolean =
    appliedDecision == null &&
        classification == consequence.kind.name.lowercase() &&
        deviation == consequence.deviation.name.lowercase() &&
        loadMetric == consequence.metric.name.lowercase() &&
        risk == consequence.risk.name.lowercase() &&
        recommendedDecision == consequence.recommendedDecision.toStorageValue() &&
        comparisonStatus == consequence.comparisonStatus &&
        planChangeAvailable == consequence.planChangeAvailable &&
        actualLoadMeters == consequence.actualDifference.takeIf { consequence.metric == LoadMetric.DISTANCE } &&
        actualLoadDurationSeconds == consequence.actualDifference.takeIf { consequence.metric == LoadMetric.DURATION } &&
        distanceDifferenceMeters == consequence.actualDifference.takeIf { consequence.metric == LoadMetric.DISTANCE } &&
        durationDifferenceSeconds == consequence.actualDifference.takeIf { consequence.metric == LoadMetric.DURATION } &&
        options.mapNotNull { it.decision.toStoredPlanDecision() }.toSet() == consequence.options

private fun WorkoutFeedbackConsequenceEntity.loadMetric(): LoadMetric? = loadMetric?.toLoadMetric()
private fun String.toLoadMetric(): LoadMetric? = enumValues<LoadMetric>().firstOrNull { it.name.equals(this, ignoreCase = true) }
private fun String.toConsequenceKind(): ConsequenceKind? = enumValues<ConsequenceKind>().firstOrNull { it.name.equals(this, ignoreCase = true) }
private fun String.toDeviation(): Deviation? = enumValues<Deviation>().firstOrNull { it.name.equals(this, ignoreCase = true) }
private fun String.toRisk(): Risk? = enumValues<Risk>().firstOrNull { it.name.equals(this, ignoreCase = true) }
private fun List<WorkoutEntity>.canonicalDistanceLoad(): Int = sumOf { workout ->
    workout.currentDistanceMeters?.takeIf {
        workout.currentStatus != WORKOUT_STATE_TOMBSTONED && workout.currentWorkoutType != "rest"
    } ?: 0
}

private fun List<WorkoutEntity>.canonicalDurationLoad(): Int = sumOf { workout ->
    workout.currentDurationSeconds?.takeIf {
        workout.currentStatus != WORKOUT_STATE_TOMBSTONED && workout.currentWorkoutType != "rest"
    } ?: 0
}

sealed interface LocalConsequenceDecisionPersistenceResult {
    data class Applied(val adjustmentId: String, val decisionId: String, val workoutIds: List<String>) :
        LocalConsequenceDecisionPersistenceResult
    data object AlreadyApplied : LocalConsequenceDecisionPersistenceResult
    data class Rejected(val issue: LocalDecisionIssue) : LocalConsequenceDecisionPersistenceResult
}
