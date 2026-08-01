package dev.deftmartian.runway.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlin.math.floor
import kotlin.math.max

/**
 * Repairs the timed consequence ledger divergence released in v0.8.0-v0.8.8.
 *
 * The repair reads complete snapshot/current structures before writing. It fails the enclosing
 * migration transaction when a targeted timed effect is structurally impossible, and changes a
 * live workout only while its complete current state still matches an affected ledger snapshot.
 */
internal object LegacyTimedConsequenceLedgerRepair {
    fun apply(db: SupportSQLiteDatabase) {
        apply(
            query = { sql -> db.query(sql) },
            execSql = { sql, arguments -> db.execSQL(sql, arguments) },
        )
    }

    fun apply(db: SQLiteDatabase) {
        apply(
            query = { sql -> db.rawQuery(sql, emptyArray<String>()) },
            execSql = { sql, arguments -> db.execSQL(sql, arguments) },
        )
    }

    private fun apply(
        query: (String) -> Cursor,
        execSql: (String, Array<out Any?>) -> Unit,
    ) {
        val effects = query(TARGET_EFFECTS_SQL).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.legacyTimedEffect())
                }
            }
        }
        if (effects.isEmpty()) return

        val blocks = query(TARGET_SNAPSHOT_BLOCKS_SQL).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.snapshotBlock())
            }
        }
        val segments = query(TARGET_SNAPSHOT_SEGMENTS_SQL).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.snapshotSegment())
            }
        }
        val snapshotReferences = query(TARGET_SNAPSHOT_REFERENCES_SQL).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.snapshotReference())
            }
        }
        val currentWorkouts = query(TARGET_CURRENT_WORKOUTS_SQL).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val state = cursor.currentWorkoutState()
                    put(state.workoutId, state)
                }
            }
        }
        val currentBlocks = query(TARGET_CURRENT_BLOCKS_SQL).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.currentBlock())
            }
        }
        val currentSegments = query(TARGET_CURRENT_SEGMENTS_SQL).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.currentSegment())
            }
        }
        val currentReferences = query(TARGET_CURRENT_REFERENCES_SQL).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.currentReference())
            }
        }

        val snapshotSegmentsByBlock = segments.groupBy(LegacySegment::blockId)
        val snapshotsByEffectAndState = blocks
            .groupBy { it.ownerId to it.state }
            .mapValues { (_, stateBlocks) ->
                stateBlocks.map { block ->
                    LegacyBlock(
                        id = block.id,
                        ordinal = block.ordinal,
                        blockType = block.blockType,
                        repetitions = block.repetitions,
                        segments = snapshotSegmentsByBlock[block.id].orEmpty(),
                    )
                }
            }
        val currentSegmentsByBlock = currentSegments.groupBy(LegacySegment::blockId)
        val currentStructures = currentBlocks
            .groupBy(CurrentBlock::workoutId)
            .mapValues { (_, workoutBlocks) ->
                workoutBlocks.map { block ->
                    LegacyBlock(
                        id = block.id,
                        ordinal = block.ordinal,
                        blockType = block.blockType,
                        repetitions = block.repetitions,
                        segments = currentSegmentsByBlock[block.id].orEmpty(),
                    )
                }
            }
        val snapshotReferencesByState = snapshotReferences.groupBy { it.ownerId to it.state }
        val currentReferencesByWorkout = currentReferences.groupBy(CurrentReference::workoutId)

        val snapshotRepairs = mutableListOf<SnapshotRepair>()
        val currentRepairs = linkedMapOf<String, CurrentRepair>()
        effects.forEach { effect ->
            listOf(false, true).forEach { after ->
                val state = if (after) AFTER else BEFORE
                val stateValues = effect.values(after)
                val original = snapshotsByEffectAndState[effect.effectId to state].orEmpty()
                val repaired = resizeStructure(
                    effectId = effect.effectId,
                    state = state,
                    warmupSeconds = stateValues.warmupSeconds,
                    cooldownSeconds = stateValues.cooldownSeconds,
                    blocks = original,
                    targetDurationSeconds = stateValues.durationSeconds,
                )
                snapshotRepairs += SnapshotRepair(
                    effectId = effect.effectId,
                    state = state,
                    warmupSeconds = repaired.warmupSeconds,
                    cooldownSeconds = repaired.cooldownSeconds,
                    segments = repaired.segments,
                )

                val workoutId = effect.workoutId
                val current = workoutId?.let(currentWorkouts::get)
                if (workoutId != null && current != null &&
                    current.matches(stateValues) &&
                    sameStructure(currentStructures[workoutId].orEmpty(), original) &&
                    sameReferences(
                        currentReferencesByWorkout[workoutId].orEmpty(),
                        snapshotReferencesByState[effect.effectId to state].orEmpty(),
                    )
                ) {
                    // Effects are read oldest-to-newest. Overwriting chooses the latest exact
                    // ledger state when a chain's after-state equals its successor's before-state.
                    currentRepairs[workoutId] = CurrentRepair(
                        workoutId = workoutId,
                        warmupSeconds = repaired.warmupSeconds,
                        cooldownSeconds = repaired.cooldownSeconds,
                        segments = currentStructures[workoutId].orEmpty()
                            .zip(repaired.blocks)
                            .flatMap { (currentBlock, repairedBlock) ->
                                currentBlock.segments.zip(repairedBlock.segments)
                                    .map { (currentSegment, repairedSegment) ->
                                        SegmentRepair(
                                            currentSegment.id,
                                            requireNotNull(repairedSegment.durationSeconds),
                                        )
                                    }
                            },
                    )
                }
            }
        }

        // Validate every targeted row before the first write so an impossible released ledger
        // fails its enclosing Room/restore transaction without a partially repaired history.
        migrationCheck(blocks.all { it.state in SNAPSHOT_STATES }) {
            "A targeted timed consequence contains an unsupported snapshot state."
        }

        snapshotRepairs.forEach { repair ->
            repair.segments.forEach { segment ->
                execSql(
                    "UPDATE adjustment_effect_segment_snapshots SET targetDurationSeconds = ? WHERE segmentSnapshotId = ?",
                    arrayOf<Any?>(segment.durationSeconds, segment.segmentId),
                )
            }
            val prefix = if (repair.state == AFTER) "new" else "previous"
            execSql(
                "UPDATE adjustment_workout_effects SET ${prefix}WarmupSeconds = ?, ${prefix}CooldownSeconds = ? WHERE effectId = ?",
                arrayOf<Any?>(repair.warmupSeconds, repair.cooldownSeconds, repair.effectId),
            )
        }
        currentRepairs.values.forEach { repair ->
            repair.segments.forEach { segment ->
                execSql(
                    "UPDATE workout_segments SET targetDurationSeconds = ? WHERE segmentId = ?",
                    arrayOf<Any?>(segment.durationSeconds, segment.segmentId),
                )
            }
            execSql(
                "UPDATE workouts SET currentWarmupSeconds = ?, currentCooldownSeconds = ? WHERE workoutId = ?",
                arrayOf<Any?>(repair.warmupSeconds, repair.cooldownSeconds, repair.workoutId),
            )
        }
    }

    private fun resizeStructure(
        effectId: String,
        state: String,
        warmupSeconds: Int?,
        cooldownSeconds: Int?,
        blocks: List<LegacyBlock>,
        targetDurationSeconds: Int?,
    ): ResizedStructure {
        migrationCheck(targetDurationSeconds != null && targetDurationSeconds in 600..21_600) {
            "Timed consequence $effectId $state has no valid headline duration."
        }
        migrationCheck(warmupSeconds != null && warmupSeconds >= 0) {
            "Timed consequence $effectId $state has no valid warmup duration."
        }
        migrationCheck(cooldownSeconds != null && cooldownSeconds >= 0) {
            "Timed consequence $effectId $state has no valid cooldown duration."
        }
        val targetDuration = checkNotNull(targetDurationSeconds)
        val warmup = checkNotNull(warmupSeconds)
        val cooldown = checkNotNull(cooldownSeconds)
        migrationCheck(blocks.isNotEmpty() && blocks.size <= MAX_BLOCKS) {
            "Timed consequence $effectId $state has no valid interval blocks."
        }
        migrationCheck(blocks.map(LegacyBlock::ordinal) == blocks.indices.toList()) {
            "Timed consequence $effectId $state has non-contiguous block ordinals."
        }
        blocks.forEach { block ->
            migrationCheck(block.blockType == "timed" && block.repetitions in 1..100) {
                "Timed consequence $effectId $state has an invalid interval block."
            }
            migrationCheck(block.segments.isNotEmpty() && block.segments.size <= MAX_SEGMENTS_PER_BLOCK) {
                "Timed consequence $effectId $state has no valid interval segments."
            }
            migrationCheck(block.segments.map(LegacySegment::ordinal) == block.segments.indices.toList()) {
                "Timed consequence $effectId $state has non-contiguous segment ordinals."
            }
            block.segments.forEach { segment ->
                migrationCheck(
                    segment.segmentType in SEGMENT_TYPES &&
                        segment.distanceMeters == null &&
                        segment.durationSeconds != null &&
                        segment.durationSeconds > 0,
                ) {
                    "Timed consequence $effectId $state has an invalid timed segment."
                }
            }
        }

        val oldTotal = warmup.toLong() + cooldown.toLong() +
            blocks.sumOf { block ->
                block.repetitions.toLong() * block.segments.sumOf {
                    requireNotNull(it.durationSeconds).toLong()
                }
            }
        migrationCheck(oldTotal in 1..Int.MAX_VALUE.toLong()) {
            "Timed consequence $effectId $state has an invalid interval total."
        }
        val factor = targetDuration.toDouble() / oldTotal.toDouble()
        val resizedWarmup = resizeValue(warmup, factor, minimum = 0)
        val initiallyResizedCooldown = resizeValue(cooldown, factor, minimum = 0)
        val resizedBlocks = blocks.map { block ->
            block.copy(
                segments = block.segments.map { segment ->
                    segment.copy(
                        durationSeconds = resizeValue(
                            requireNotNull(segment.durationSeconds),
                            factor,
                            minimum = 1,
                        ),
                    )
                },
            )
        }
        val initiallyResizedTotal = resizedWarmup.toLong() + initiallyResizedCooldown.toLong() +
            resizedBlocks.sumOf { block ->
                block.repetitions.toLong() * block.segments.sumOf {
                    requireNotNull(it.durationSeconds).toLong()
                }
            }
        val resizedCooldown = max(
            0L,
            initiallyResizedCooldown.toLong() + targetDuration - initiallyResizedTotal,
        )
        migrationCheck(resizedCooldown <= Int.MAX_VALUE) {
            "Timed consequence $effectId $state has an overflowing residual."
        }
        val finalTotal = resizedWarmup.toLong() + resizedCooldown + resizedBlocks.sumOf { block ->
            block.repetitions.toLong() * block.segments.sumOf {
                requireNotNull(it.durationSeconds).toLong()
            }
        }
        migrationCheck(finalTotal == targetDuration.toLong()) {
            "Timed consequence $effectId $state cannot be resized to its headline duration."
        }
        return ResizedStructure(resizedWarmup, resizedCooldown.toInt(), resizedBlocks)
    }

    private fun resizeValue(value: Int, factor: Double, minimum: Int): Int {
        val rounded = floor(value * factor + 0.5)
        migrationCheck(rounded.isFinite() && rounded <= Int.MAX_VALUE) {
            "Timed consequence resize overflowed."
        }
        return max(minimum, rounded.toInt())
    }

    private fun sameStructure(first: List<LegacyBlock>, second: List<LegacyBlock>): Boolean =
        first.map(LegacyBlock::content) == second.map(LegacyBlock::content)

    private fun sameReferences(
        current: List<CurrentReference>,
        snapshot: List<SnapshotReference>,
    ): Boolean = current.map(CurrentReference::content) == snapshot.map(SnapshotReference::content)

    private inline fun migrationCheck(condition: Boolean, message: () -> String) {
        if (!condition) throw IllegalStateException(message())
    }

    private const val BEFORE = "before"
    private const val AFTER = "after"
    private const val MAX_BLOCKS = 100
    private const val MAX_SEGMENTS_PER_BLOCK = 100
    private val SNAPSHOT_STATES = setOf(BEFORE, AFTER)
    private val SEGMENT_TYPES = setOf("run", "walk")

    private const val TARGET_JOIN =
        """
        INNER JOIN adjustment_effect_groups AS effect_group ON effect_group.groupId = effect.groupId
        INNER JOIN plan_adjustments AS adjustment ON adjustment.adjustmentId = effect_group.adjustmentId
        """
    private const val TARGET_WHERE =
        """
        effect_group.effectType = 'consequence_decision'
        AND effect.previousPrescriptionKind = 'timed'
        AND effect.newPrescriptionKind = 'timed'
        AND effect.previousDurationSeconds IS NOT NULL
        AND effect.newDurationSeconds IS NOT NULL
        AND effect.previousDurationSeconds != effect.newDurationSeconds
        """
    private const val TARGET_EFFECTS_SQL =
        """
        SELECT effect.effectId, effect.workoutId,
               effect.previousScheduledEpochDay, effect.newScheduledEpochDay,
               effect.previousWorkoutType, effect.newWorkoutType,
               effect.previousStatus, effect.newStatus,
               effect.previousDistanceMeters, effect.newDistanceMeters,
               effect.previousDurationSeconds, effect.newDurationSeconds,
               effect.previousIntensity, effect.newIntensity,
               effect.previousPurpose, effect.newPurpose,
               effect.previousReason, effect.newReason,
               effect.previousTombstonedAtEpochMillis, effect.newTombstonedAtEpochMillis,
               effect.previousWarmupSeconds, effect.newWarmupSeconds,
               effect.previousCooldownSeconds, effect.newCooldownSeconds,
               effect.previousPrescriptionKind, effect.newPrescriptionKind,
               effect.previousWeekId, effect.newWeekId
        FROM adjustment_workout_effects AS effect
        $TARGET_JOIN
        WHERE $TARGET_WHERE
        ORDER BY adjustment.createdAtEpochMillis, adjustment.adjustmentId,
                 effect_group.ordinal, effect.ordinal, effect.effectId
        """
    private const val TARGET_SNAPSHOT_BLOCKS_SQL =
        """
        SELECT block.blockSnapshotId, block.effectId, block.snapshotState,
               block.ordinal, block.blockType, block.repetitions
        FROM adjustment_effect_block_snapshots AS block
        INNER JOIN adjustment_workout_effects AS effect ON effect.effectId = block.effectId
        $TARGET_JOIN
        WHERE $TARGET_WHERE
        ORDER BY block.effectId, block.snapshotState, block.ordinal
        """
    private const val TARGET_SNAPSHOT_SEGMENTS_SQL =
        """
        SELECT segment.segmentSnapshotId, segment.blockSnapshotId, segment.ordinal,
               segment.segmentType, segment.targetDistanceMeters, segment.targetDurationSeconds
        FROM adjustment_effect_segment_snapshots AS segment
        INNER JOIN adjustment_effect_block_snapshots AS block
            ON block.blockSnapshotId = segment.blockSnapshotId
        INNER JOIN adjustment_workout_effects AS effect ON effect.effectId = block.effectId
        $TARGET_JOIN
        WHERE $TARGET_WHERE
        ORDER BY block.effectId, block.snapshotState, block.ordinal, segment.ordinal
        """
    private const val TARGET_SNAPSHOT_REFERENCES_SQL =
        """
        SELECT reference.effectId, reference.snapshotState, reference.ordinal,
               reference.sourceName, reference.sourceLocator
        FROM adjustment_effect_source_reference_snapshots AS reference
        INNER JOIN adjustment_workout_effects AS effect ON effect.effectId = reference.effectId
        $TARGET_JOIN
        WHERE $TARGET_WHERE
        ORDER BY reference.effectId, reference.snapshotState, reference.ordinal
        """
    private const val TARGET_CURRENT_WORKOUTS_SQL =
        """
        SELECT DISTINCT workout.workoutId, workout.currentScheduledEpochDay,
               workout.currentWorkoutType, workout.currentStatus,
               workout.currentDistanceMeters, workout.currentDurationSeconds,
               workout.currentIntensity, workout.currentPurpose, workout.currentReason,
               workout.tombstonedAtEpochMillis, workout.currentWarmupSeconds,
               workout.currentCooldownSeconds, workout.currentPrescriptionKind, workout.weekId
        FROM workouts AS workout
        INNER JOIN adjustment_workout_effects AS effect ON effect.workoutId = workout.workoutId
        $TARGET_JOIN
        WHERE $TARGET_WHERE
        ORDER BY workout.workoutId
        """
    private const val TARGET_CURRENT_BLOCKS_SQL =
        """
        SELECT DISTINCT block.blockId, block.workoutId, block.ordinal,
               block.blockType, block.repetitions
        FROM workout_blocks AS block
        INNER JOIN adjustment_workout_effects AS effect ON effect.workoutId = block.workoutId
        $TARGET_JOIN
        WHERE block.prescriptionVersion = 'current' AND $TARGET_WHERE
        ORDER BY block.workoutId, block.ordinal
        """
    private const val TARGET_CURRENT_SEGMENTS_SQL =
        """
        SELECT DISTINCT segment.segmentId, segment.blockId, segment.ordinal,
               segment.segmentType, segment.targetDistanceMeters, segment.targetDurationSeconds,
               block.workoutId
        FROM workout_segments AS segment
        INNER JOIN workout_blocks AS block ON block.blockId = segment.blockId
        INNER JOIN adjustment_workout_effects AS effect ON effect.workoutId = block.workoutId
        $TARGET_JOIN
        WHERE block.prescriptionVersion = 'current' AND $TARGET_WHERE
        ORDER BY block.workoutId, block.ordinal, segment.ordinal
        """
    private const val TARGET_CURRENT_REFERENCES_SQL =
        """
        SELECT DISTINCT reference.workoutId, reference.ordinal,
               reference.sourceName, reference.sourceLocator
        FROM workout_source_references AS reference
        INNER JOIN adjustment_workout_effects AS effect ON effect.workoutId = reference.workoutId
        $TARGET_JOIN
        WHERE reference.prescriptionVersion = 'current' AND $TARGET_WHERE
        ORDER BY reference.workoutId, reference.ordinal
        """

    private data class LegacyTimedEffect(
        val effectId: String,
        val workoutId: String?,
        val before: EffectState,
        val after: EffectState,
    ) {
        fun values(after: Boolean): EffectState = if (after) this.after else before
    }

    private data class EffectState(
        val scheduledEpochDay: Long?,
        val workoutType: String?,
        val status: String?,
        val distanceMeters: Int?,
        val durationSeconds: Int?,
        val intensity: String?,
        val purpose: String?,
        val reason: String?,
        val tombstonedAtEpochMillis: Long?,
        val warmupSeconds: Int?,
        val cooldownSeconds: Int?,
        val prescriptionKind: String?,
        val weekId: String?,
    )

    private data class SnapshotBlock(
        val id: String,
        val ownerId: String,
        val state: String,
        val ordinal: Int,
        val blockType: String,
        val repetitions: Int,
    )

    private data class CurrentBlock(
        val id: String,
        val workoutId: String,
        val ordinal: Int,
        val blockType: String,
        val repetitions: Int,
    )

    private data class LegacyBlock(
        val id: String,
        val ordinal: Int,
        val blockType: String,
        val repetitions: Int,
        val segments: List<LegacySegment>,
    ) {
        fun content() = BlockContent(
            ordinal,
            blockType,
            repetitions,
            segments.map(LegacySegment::content),
        )
    }

    private data class LegacySegment(
        val id: String,
        val blockId: String,
        val ordinal: Int,
        val segmentType: String,
        val distanceMeters: Int?,
        val durationSeconds: Int?,
    ) {
        fun content() = SegmentContent(ordinal, segmentType, distanceMeters, durationSeconds)
    }

    private data class BlockContent(
        val ordinal: Int,
        val blockType: String,
        val repetitions: Int,
        val segments: List<SegmentContent>,
    )

    private data class SegmentContent(
        val ordinal: Int,
        val segmentType: String,
        val distanceMeters: Int?,
        val durationSeconds: Int?,
    )

    private data class SnapshotReference(
        val ownerId: String,
        val state: String,
        val ordinal: Int,
        val sourceName: String,
        val sourceLocator: String?,
    ) {
        fun content() = ReferenceContent(ordinal, sourceName, sourceLocator)
    }

    private data class CurrentReference(
        val workoutId: String,
        val ordinal: Int,
        val sourceName: String,
        val sourceLocator: String?,
    ) {
        fun content() = ReferenceContent(ordinal, sourceName, sourceLocator)
    }

    private data class ReferenceContent(
        val ordinal: Int,
        val sourceName: String,
        val sourceLocator: String?,
    )

    private data class CurrentWorkoutState(
        val workoutId: String,
        val values: EffectState,
    ) {
        fun matches(other: EffectState): Boolean = values == other
    }

    private data class ResizedStructure(
        val warmupSeconds: Int,
        val cooldownSeconds: Int,
        val blocks: List<LegacyBlock>,
    ) {
        val segments: List<SegmentRepair> = blocks.flatMap { block ->
            block.segments.map { SegmentRepair(it.id, requireNotNull(it.durationSeconds)) }
        }
    }

    private data class SegmentRepair(val segmentId: String, val durationSeconds: Int)
    private data class SnapshotRepair(
        val effectId: String,
        val state: String,
        val warmupSeconds: Int,
        val cooldownSeconds: Int,
        val segments: List<SegmentRepair>,
    )
    private data class CurrentRepair(
        val workoutId: String,
        val warmupSeconds: Int,
        val cooldownSeconds: Int,
        val segments: List<SegmentRepair>,
    )

    private fun Cursor.legacyTimedEffect(): LegacyTimedEffect = LegacyTimedEffect(
        effectId = string("effectId"),
        workoutId = nullableString("workoutId"),
        before = effectState("previous"),
        after = effectState("new"),
    )

    private fun Cursor.effectState(prefix: String) = EffectState(
        scheduledEpochDay = nullableLong("${prefix}ScheduledEpochDay"),
        workoutType = nullableString("${prefix}WorkoutType"),
        status = nullableString("${prefix}Status"),
        distanceMeters = nullableInt("${prefix}DistanceMeters"),
        durationSeconds = nullableInt("${prefix}DurationSeconds"),
        intensity = nullableString("${prefix}Intensity"),
        purpose = nullableString("${prefix}Purpose"),
        reason = nullableString("${prefix}Reason"),
        tombstonedAtEpochMillis = nullableLong("${prefix}TombstonedAtEpochMillis"),
        warmupSeconds = nullableInt("${prefix}WarmupSeconds"),
        cooldownSeconds = nullableInt("${prefix}CooldownSeconds"),
        prescriptionKind = nullableString("${prefix}PrescriptionKind"),
        weekId = nullableString("${prefix}WeekId"),
    )

    private fun Cursor.snapshotBlock() = SnapshotBlock(
        id = string("blockSnapshotId"),
        ownerId = string("effectId"),
        state = string("snapshotState"),
        ordinal = int("ordinal"),
        blockType = string("blockType"),
        repetitions = int("repetitions"),
    )

    private fun Cursor.currentBlock() = CurrentBlock(
        id = string("blockId"),
        workoutId = string("workoutId"),
        ordinal = int("ordinal"),
        blockType = string("blockType"),
        repetitions = int("repetitions"),
    )

    private fun Cursor.snapshotSegment() = LegacySegment(
        id = string("segmentSnapshotId"),
        blockId = string("blockSnapshotId"),
        ordinal = int("ordinal"),
        segmentType = string("segmentType"),
        distanceMeters = nullableInt("targetDistanceMeters"),
        durationSeconds = nullableInt("targetDurationSeconds"),
    )

    private fun Cursor.currentSegment() = LegacySegment(
        id = string("segmentId"),
        blockId = string("blockId"),
        ordinal = int("ordinal"),
        segmentType = string("segmentType"),
        distanceMeters = nullableInt("targetDistanceMeters"),
        durationSeconds = nullableInt("targetDurationSeconds"),
    )

    private fun Cursor.snapshotReference() = SnapshotReference(
        ownerId = string("effectId"),
        state = string("snapshotState"),
        ordinal = int("ordinal"),
        sourceName = string("sourceName"),
        sourceLocator = nullableString("sourceLocator"),
    )

    private fun Cursor.currentReference() = CurrentReference(
        workoutId = string("workoutId"),
        ordinal = int("ordinal"),
        sourceName = string("sourceName"),
        sourceLocator = nullableString("sourceLocator"),
    )

    private fun Cursor.currentWorkoutState() = CurrentWorkoutState(
        workoutId = string("workoutId"),
        values = EffectState(
            scheduledEpochDay = nullableLong("currentScheduledEpochDay"),
            workoutType = nullableString("currentWorkoutType"),
            status = nullableString("currentStatus"),
            distanceMeters = nullableInt("currentDistanceMeters"),
            durationSeconds = nullableInt("currentDurationSeconds"),
            intensity = nullableString("currentIntensity"),
            purpose = nullableString("currentPurpose"),
            reason = nullableString("currentReason"),
            tombstonedAtEpochMillis = nullableLong("tombstonedAtEpochMillis"),
            warmupSeconds = nullableInt("currentWarmupSeconds"),
            cooldownSeconds = nullableInt("currentCooldownSeconds"),
            prescriptionKind = nullableString("currentPrescriptionKind"),
            weekId = nullableString("weekId"),
        ),
    )

    private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.nullableString(name: String): String? = column(name) { getString(it) }
    private fun Cursor.nullableInt(name: String): Int? = column(name) { getInt(it) }
    private fun Cursor.nullableLong(name: String): Long? = column(name) { getLong(it) }
    private inline fun <T> Cursor.column(name: String, read: Cursor.(Int) -> T): T? {
        val index = getColumnIndexOrThrow(name)
        return if (isNull(index)) null else read(index)
    }
}
