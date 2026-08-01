package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deftmartian.runway.domain.FoundationIntake
import dev.deftmartian.runway.domain.GeneratedFoundationPlan
import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.InjuryFlags
import dev.deftmartian.runway.domain.PlanDecision
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TrainingPlanner
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the core standalone decision loop through production Room repositories and surfaces. */
@RunWith(AndroidJUnit4::class)
class LocalDecisionLoopInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase
    private val today = LocalDate.of(2026, 7, 27)
    private val zone = ZoneId.of("America/Halifax")
    private val now = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunwayLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun setupReviewExtraAndPlanDecisionStayConsistentAcrossEveryLedgerSurface() = runBlocking {
        val graph = generatedFoundationGraph()
        val setupResult = LocalPlanSetupRepository(database).setUp(
            LocalPlanSetupRequest(
                operationId = "decision-loop-setup",
                operationFingerprint = "7".repeat(64),
                profile = profile(),
                availabilityDays = listOf(1, 3, 6),
                candidate = LocalPlanCandidate.Generated(graph),
                confirmReplaceCurrent = false,
                archiveAtEpochMillis = now,
            ),
        )
        assertEquals(
            LocalPlanSetupResult.Created("decision-loop-goal", "decision-loop-plan"),
            setupResult,
        )

        val surfaces = LocalSurfaceRepository(
            RoomLocalSurfaceLedgerReader(database, nowEpochMillis = { now }),
        )
        val from = today.toEpochDay()
        val through = today.plusDays(42).toEpochDay()
        val initialCalendar = surfaces.calendar(from, through)
        assertEquals("decision-loop-plan", initialCalendar.activePlanId)
        assertEquals(0, initialCalendar.pendingDecisionCount)
        assertTrue(initialCalendar.days.flatMap(LocalCalendarDayReadModel::workouts).isNotEmpty())
        assertEquals(0, surfaces.stats().totalRuns)
        assertEquals(0, surfaces.history().plans.single().actual.distanceMeters)

        val recorded = LocalTrainingMutationRepository(
            database = database,
            nowEpochMillis = { now },
            newId = { "decision-loop-activity" },
        ).recordManualRun(
            LocalManualRunCommand(
                occurredDate = today,
                distanceMeters = 1_000,
                durationSeconds = 600,
                pain = true,
            ),
        )
        assertTrue(recorded is LocalTrainingMutationResult.ManualRunRecorded)

        val reviewInbox = surfaces.inbox(LocalInboxPagingCursor())
        assertEquals(1, reviewInbox.reviewCount)
        assertEquals("review", reviewInbox.activities.single().reviewState)
        assertEquals(1, surfaces.calendar(from, through).pendingDecisionCount)
        assertEquals(0, surfaces.stats().totalRuns)
        assertEquals(0, surfaces.history().plans.single().actual.distanceMeters)

        val accepted = LocalActivityReviewRepository(database, nowEpochMillis = { now })
            .confirmAsExtra("decision-loop-activity")
        assertTrue(accepted is LocalActivityReviewResult.AcceptedExtra)

        val decisionInbox = surfaces.inbox(LocalInboxPagingCursor())
        val acceptedActivity = decisionInbox.activities.single()
        assertEquals(ACTIVITY_REVIEW_STATE_ACCEPTED, acceptedActivity.reviewState)
        assertTrue(
            acceptedActivity.consequence?.options.orEmpty()
                .containsAll(listOf("keep_plan", "next_rest")),
        )
        assertEquals(1, surfaces.calendar(from, through).pendingDecisionCount)
        assertEquals(1, surfaces.stats().totalRuns)
        assertEquals(1_000, surfaces.stats().totalDistanceMeters)

        val decisions = LocalConsequenceDecisionRepository(database, nowEpochMillis = { now })
        val prepared = decisions.prepare(
            LocalDecisionSourceKind.Activity,
            "decision-loop-activity",
            PlanDecision.NEXT_REST,
        ) as LocalConsequenceDecisionPreparation.Prepared
        val applied = decisions.apply(
            preview = prepared.preview,
            input = prepared.input,
            adjustmentId = "decision-loop-adjustment",
            decisionId = "decision-loop-decision",
            appliedAtEpochMillis = now,
        ) as LocalConsequenceDecisionPersistenceResult.Applied
        assertEquals(1, applied.workoutIds.size)
        assertEquals("rest", database.goalPlanDao().workout(applied.workoutIds.single())?.currentWorkoutType)

        val finalCalendar = surfaces.calendar(from, through)
        assertEquals(0, finalCalendar.pendingDecisionCount)
        assertTrue(surfaces.inbox(LocalInboxPagingCursor()).activities.isEmpty())
        assertEquals(1, surfaces.stats().totalRuns)
        assertEquals(1_000, surfaces.stats().totalDistanceMeters)
        val finalPlan = surfaces.history().plans.single()
        assertEquals(1_000, finalPlan.actual.distanceMeters)
        assertEquals(600, finalPlan.actual.durationSeconds)
        val detailedPlan = requireNotNull(surfaces.historyPlan("decision-loop-plan"))
            .plans.single()
        assertTrue(detailedPlan.weeks.flatMap(LocalHistoryWeekReadModel::extraActivities).any {
            it.activityId == "decision-loop-activity"
        })
        assertTrue(detailedPlan.adjustments.any { it.triggerType == "next_rest" })

        assertEquals(
            LocalConsequenceDecisionPersistenceResult.AlreadyApplied,
            decisions.apply(
                preview = prepared.preview,
                input = prepared.input,
                adjustmentId = "decision-loop-adjustment-rerun",
                decisionId = "decision-loop-decision-rerun",
                appliedAtEpochMillis = now + 1,
            ),
        )
        assertEquals(1, surfaces.stats().totalRuns)
        assertEquals(0, surfaces.calendar(from, through).pendingDecisionCount)
    }

    private fun generatedFoundationGraph(): GeneratedPlanPersistenceGraph {
        val generated = TrainingPlanner.generatePlan(
            FoundationIntake(
                startMode = StartMode.FOUNDATION_ONLY,
                goalKind = GoalKind.FOUNDATION,
                raceDistance = null,
                availability = listOf(1, 3, 6),
                injuryFlags = InjuryFlags(),
                startDate = today.toString(),
            ),
        ) as GeneratedFoundationPlan
        return GeneratedPlanPersistenceMapper.map(
            generated,
            GeneratedPlanGoalMetadata(
                goalId = "decision-loop-goal",
                planId = "decision-loop-plan",
                title = "Foundation",
                goalKind = GoalKind.FOUNDATION,
                startMode = StartMode.FOUNDATION_ONLY,
                createdAtEpochMillis = now,
            ),
        )
    }

    private fun profile() = ProfileSettingsEntity(
        timeZone = zone.id,
        routeDataMode = "discard",
        heartRateDataMode = "discard",
        heartRateSettingsSource = "none",
        maxHeartRateBpm = null,
        zone2FloorBpm = null,
        zone3FloorBpm = null,
        zone4FloorBpm = null,
        zone5FloorBpm = null,
        recentInjury = false,
        currentPain = false,
        recurringPain = false,
        medicalRestriction = false,
        privateNotes = null,
        updatedAtEpochMillis = now,
    )
}
