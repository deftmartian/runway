package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.PrescriptionKind
import dev.deftmartian.runway.domain.PrescriptionSegment
import dev.deftmartian.runway.domain.RunWalkBlock
import dev.deftmartian.runway.domain.SegmentKind
import dev.deftmartian.runway.domain.TimedIntervalStructure
import dev.deftmartian.runway.domain.WorkoutProposal
import dev.deftmartian.runway.domain.WorkoutType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkoutChangeRepositoryPreparationTest {
    private val today = LocalDate.of(2026, 6, 1)

    @Test
    fun `edit preview is deterministic explicit and maps exact before after history`() {
        val current = distance("week-1", today.plusDays(2), 5_000, "Current source")
        val stored = stored("workout-1", current, generated = current, sourceUrl = "https://source.test")
        val proposed = current.copy(targetDistanceMeters = 10_000, reason = "Explicit edit")
        val request = LocalWorkoutChangeRequest.Edit(stored.entity.workoutId, proposed)
        val preparer = LocalWorkoutChangePreparer()

        val first = preparer.prepare(ledger(listOf(stored)), request, today, hasInjuryRisk = false)
        val second = preparer.prepare(ledger(listOf(stored)), request, today, hasInjuryRisk = false)

        assertEquals(first.previewToken, second.previewToken)
        assertTrue(first.preview.requiresConfirmation)
        assertEquals(listOf("workout-1"), first.preview.workoutChanges.map { it.workoutId })

        val persisted = LocalWorkoutChangePersistenceMapper().map(first, "adjustment-1", "decision-1", 123)
        assertEquals(1, persisted.effects.size)
        assertEquals("week-1", persisted.effectWeekTransitions.single().previousWeekId)
        assertEquals("week-1", persisted.effectWeekTransitions.single().newWeekId)
        assertEquals(5_000, persisted.effects.single().previousDistanceMeters)
        assertEquals(10_000, persisted.effects.single().newDistanceMeters)
        assertTrue(persisted.sourceReferenceSnapshots.any {
            it.snapshotState == "before" && it.sourceUrl == "https://source.test"
        })
        assertEquals(10_000, persisted.mutations.single().workout.currentDistanceMeters)
    }

    @Test
    fun `reset restores generated state while preserving its immutable projection`() {
        val generated = distance("week-1", today.plusDays(2), 5_000, "Generated")
        val current = generated.copy(
            scheduledDate = today.plusDays(3),
            targetDistanceMeters = 7_000,
            purpose = "Edited",
        )
        val stored = stored("workout-1", current, generated)

        val prepared = LocalWorkoutChangePreparer().prepare(
            ledger(listOf(stored)),
            LocalWorkoutChangeRequest.Reset("workout-1"),
            today,
            hasInjuryRisk = false,
        )

        assertEquals(generated, prepared.preview.proposed)
        val mutation = LocalWorkoutChangePersistenceMapper()
            .map(prepared, "adjustment-reset", "decision-reset", 456)
            .mutations.single()
        assertEquals(stored.entity.generatedDistanceMeters, mutation.workout.generatedDistanceMeters)
        assertEquals(5_000, mutation.workout.currentDistanceMeters)
        assertEquals(today.plusDays(2).toEpochDay(), mutation.workout.currentScheduledEpochDay)
    }

    @Test
    fun `timed mapper preserves complete run walk structure and source metadata`() {
        val timed = WorkoutProposal(
            weekId = "week-1",
            scheduledDate = today.plusDays(2),
            type = WorkoutType.EASY,
            prescriptionKind = PrescriptionKind.TIMED,
            targetDistanceMeters = 0,
            targetDurationSeconds = 1_200,
            intervalStructure = TimedIntervalStructure(
                warmupSeconds = 120,
                cooldownSeconds = 120,
                blocks = listOf(
                    RunWalkBlock(
                        repetitions = 4,
                        segments = listOf(
                            PrescriptionSegment(SegmentKind.RUN, 150),
                            PrescriptionSegment(SegmentKind.WALK, 90),
                        ),
                    ),
                ),
            ),
            purpose = "Timed run",
            sourceRefs = listOf("source-key"),
        )
        val stored = stored(
            id = "timed-1",
            current = timed,
            generated = timed,
            sourceUrl = "https://source.test/timed",
        )

        val roundTrip = LocalWorkoutChangeMapper.currentProposal(stored)

        assertEquals(timed, roundTrip)
        val prepared = LocalWorkoutChangePreparer().prepare(
            ledger(listOf(stored)),
            LocalWorkoutChangeRequest.Edit(
                "timed-1",
                timed.copy(
                    targetDurationSeconds = 1_440,
                    intervalStructure = TimedIntervalStructure(
                        120,
                        120,
                        listOf(
                            RunWalkBlock(
                                5,
                                listOf(
                                    PrescriptionSegment(SegmentKind.RUN, 150),
                                    PrescriptionSegment(SegmentKind.WALK, 90),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            today,
            false,
        )
        val persisted = LocalWorkoutChangePersistenceMapper()
            .map(prepared, "adjustment-timed", "decision-timed", 789)
        assertEquals(2, persisted.blockSnapshots.size)
        assertEquals(4, persisted.segmentSnapshots.size)
        assertTrue(persisted.mutations.single().currentBlocks.isNotEmpty())
    }

    @Test
    fun `rebalance changes compatible future workouts only when explicitly requested`() {
        val selected = stored(
            "selected",
            distance("week-1", today.plusDays(2), 5_000, "Selected"),
        )
        val compatible = stored(
            "compatible",
            distance("week-1", today.plusDays(4), 5_000, "Compatible"),
        )
        val proposed = LocalWorkoutChangeMapper.currentProposal(selected)
            .copy(targetDistanceMeters = 7_000)
        val noRebalance = LocalWorkoutChangePreparer().prepare(
            ledger(listOf(selected, compatible)),
            LocalWorkoutChangeRequest.Edit("selected", proposed, rebalanceCompatibleWeek = false),
            today,
            false,
        )
        val withRebalance = LocalWorkoutChangePreparer().prepare(
            ledger(listOf(selected, compatible)),
            LocalWorkoutChangeRequest.Edit("selected", proposed, rebalanceCompatibleWeek = true),
            today,
            false,
        )

        assertEquals(1, noRebalance.preview.workoutChanges.size)
        assertTrue(withRebalance.preview.workoutChanges.size > 1)
        assertTrue(withRebalance.preview.workoutChanges.filterNot { it.selected }.all {
            it.before.prescriptionKind == PrescriptionKind.DISTANCE &&
                it.after.weekId == "week-1"
        })
    }

    @Test
    fun `guards reject results past dates races inactive plans and caps`() {
        val valid = stored("workout-1", distance("week-1", today.plusDays(2), 5_000, "Valid"))
        val preparer = LocalWorkoutChangePreparer(LocalWorkoutChangePolicy(maximumWorkoutsPerPlan = 1))

        assertFailure {
            preparer.prepare(
                ledger(listOf(valid.copy(hasResult = true))),
                LocalWorkoutChangeRequest.Remove("workout-1"),
                today,
                false,
            )
        }
        assertFailure {
            preparer.prepare(
                ledger(listOf(valid), state = "archived"),
                LocalWorkoutChangeRequest.Remove("workout-1"),
                today,
                false,
            )
        }
        assertFailure {
            preparer.prepare(
                ledger(listOf(valid)),
                LocalWorkoutChangeRequest.Add(
                    "new-workout",
                    distance("week-1", today.plusDays(3), 1_000, "New"),
                ),
                today,
                false,
            )
        }
        val race = stored(
            "race",
            distance("week-1", today.plusDays(2), 5_000, "Race").copy(type = WorkoutType.RACE),
        )
        assertFailure {
            preparer.prepare(
                ledger(listOf(race)),
                LocalWorkoutChangeRequest.Remove("race"),
                today,
                false,
            )
        }
        val past = stored("past", distance("week-1", today.minusDays(1), 5_000, "Past"))
        assertFailure {
            preparer.prepare(
                ledger(listOf(past)),
                LocalWorkoutChangeRequest.Remove("past"),
                today,
                false,
            )
        }
    }

    @Test
    fun `undo eligibility refuses archived plans and past affected workouts`() {
        val future = stored("future", distance("week-1", today.plusDays(1), 5_000, "Future"))
        val past = stored("past", distance("week-1", today.minusDays(1), 5_000, "Past"))

        assertFailure { assertUndoEligible(ledger(emptyList(), state = "archived").plan, future, today) }
        assertFailure { assertUndoEligible(ledger(emptyList()).plan, past, today) }
        assertUndoEligible(ledger(emptyList()).plan, future, today)
    }

    @Test
    fun `add uses a removed generated baseline and creates both prescription versions`() {
        val proposed = distance("week-1", today.plusDays(3), 2_000, "Added")
        val prepared = LocalWorkoutChangePreparer().prepare(
            ledger(emptyList()),
            LocalWorkoutChangeRequest.Add("added-1", proposed),
            today,
            false,
        )
        val change = prepared.preview.workoutChanges.single()
        assertTrue(change.before.isRemoved)
        assertFalse(change.after.isRemoved)

        val persisted = LocalWorkoutChangePersistenceMapper()
            .map(prepared, "adjustment-add", "decision-add", 999)
        val mutation = persisted.mutations.single()
        assertEquals("added-1", mutation.workout.workoutId)
        assertEquals("plan-1", mutation.workout.planId)
        assertEquals(proposed.targetDistanceMeters, mutation.workout.currentDistanceMeters)
        assertNotEquals(WORKOUT_STATE_TOMBSTONED, mutation.workout.currentStatus)
    }

    private fun ledger(
        workouts: List<StoredWorkout>,
        state: String = "active",
    ) = WorkoutChangeLedgerSnapshot(
        plan = PlanEntity(
            planId = "plan-1",
            goalId = "goal-1",
            phaseType = "distance",
            state = state,
            startEpochDay = today.minusDays(7).toEpochDay(),
            endEpochDay = today.plusDays(100).toEpochDay(),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        ),
        weeks = listOf(
            PlanWeekEntity(
                weekId = "week-1",
                planId = "plan-1",
                ordinal = 1,
                startEpochDay = today.toEpochDay(),
                generatedLoadMeters = 10_000,
            ),
        ),
        workouts = workouts,
    )

    private fun stored(
        id: String,
        current: WorkoutProposal,
        generated: WorkoutProposal = current,
        sourceUrl: String? = null,
    ): StoredWorkout {
        fun blocks(proposal: WorkoutProposal) = LocalWorkoutChangeMapper.blocks(proposal)
        val refs = current.sourceRefs.map {
            StoredWorkoutSourceReference(it, sourceUrl, it)
        }
        return StoredWorkout(
            entity = WorkoutEntity(
                workoutId = id,
                planId = "plan-1",
                weekId = current.weekId,
                position = id.hashCode(),
                generatedPurpose = generated.purpose,
                generatedDistanceMeters = generated.targetDistanceMeters,
                generatedDurationSeconds = generated.targetDurationSeconds,
                currentPurpose = current.purpose,
                currentDistanceMeters = current.targetDistanceMeters,
                currentDurationSeconds = current.targetDurationSeconds,
                tombstonedAtEpochMillis = null,
                updatedAtEpochMillis = 1,
                generatedScheduledEpochDay = generated.scheduledDate.toEpochDay(),
                currentScheduledEpochDay = current.scheduledDate.toEpochDay(),
                generatedWorkoutType = generated.type.name.lowercase(),
                currentWorkoutType = current.type.name.lowercase(),
                generatedPrescriptionKind = generated.prescriptionKind.name.lowercase(),
                currentPrescriptionKind = current.prescriptionKind.name.lowercase(),
                generatedIntensity = generated.intensity,
                currentIntensity = current.intensity,
                generatedReason = generated.reason,
                currentReason = current.reason,
                generatedWarmupSeconds = generated.intervalStructure?.warmupSeconds,
                generatedCooldownSeconds = generated.intervalStructure?.cooldownSeconds,
                currentWarmupSeconds = current.intervalStructure?.warmupSeconds,
                currentCooldownSeconds = current.intervalStructure?.cooldownSeconds,
            ),
            generatedBlocks = blocks(generated),
            currentBlocks = blocks(current),
            generatedSourceReferences = generated.sourceRefs.map {
                StoredWorkoutSourceReference(it, sourceUrl, it)
            },
            currentSourceReferences = refs,
        )
    }

    private fun distance(
        weekId: String,
        date: LocalDate,
        meters: Int,
        purpose: String,
    ) = WorkoutProposal(
        weekId = weekId,
        scheduledDate = date,
        type = WorkoutType.EASY,
        prescriptionKind = PrescriptionKind.DISTANCE,
        targetDistanceMeters = meters,
        purpose = purpose,
        sourceRefs = listOf("source-key"),
    )

    private fun assertFailure(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }
}
