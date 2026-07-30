package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deftmartian.runway.domain.FoundationIntake
import dev.deftmartian.runway.domain.GeneratedFoundationPlan
import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.InjuryFlags
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TrainingPlanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlanSetupRepositoryInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase

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
    fun setupWritesTheCompleteGeneratedGraphAndProfileAsOneLedgerBoundary() = runBlocking {
        val generated = TrainingPlanner.generatePlan(
            FoundationIntake(
                startMode = StartMode.FOUNDATION_ONLY,
                goalKind = GoalKind.FOUNDATION,
                raceDistance = null,
                availability = listOf(1, 3, 6),
                injuryFlags = InjuryFlags(),
                startDate = "2026-06-01",
            ),
        ) as GeneratedFoundationPlan
        val graph = GeneratedPlanPersistenceMapper.map(
            generated,
            GeneratedPlanGoalMetadata(
                goalId = "foundation-goal",
                planId = "foundation-plan",
                title = "Foundation",
                goalKind = GoalKind.FOUNDATION,
                startMode = StartMode.FOUNDATION_ONLY,
                createdAtEpochMillis = 100,
            ),
        )

        val result = LocalPlanSetupRepository(database).setUp(
            LocalPlanSetupRequest(
                profile = profile(),
                availabilityDays = listOf(1, 3, 6),
                candidate = LocalPlanCandidate.Generated(graph),
                confirmReplaceCurrent = false,
                archiveAtEpochMillis = 100,
            ),
        )

        assertEquals(LocalPlanSetupResult.Created("foundation-goal", "foundation-plan"), result)
        assertEquals(listOf(1, 3, 6), database.profileSettingsDao().availabilityDays(limit = 10).map { it.dayOfWeek })
        val sqlite = database.openHelper.writableDatabase
        assertEquals(1, count(sqlite, "goals"))
        assertEquals(1, count(sqlite, "plans"))
        assertEquals(graph.weeks.size, count(sqlite, "plan_weeks"))
        assertEquals(graph.workouts.size, count(sqlite, "workouts"))
        assertEquals(graph.blocks.size, count(sqlite, "workout_blocks"))
        assertEquals(graph.segments.size, count(sqlite, "workout_segments"))
        assertEquals(graph.planSourceReferences.size, count(sqlite, "plan_source_references"))
        assertEquals(graph.workoutSourceReferences.size, count(sqlite, "workout_source_references"))
        assertTrue(database.goalPlanDao().planSummaryWarnings(graph.plan.planId, 100).isNotEmpty() || graph.planSummaryWarnings.isEmpty())
    }

    @Test
    fun replacingPendingGoalAfterPainClearsRequiresConfirmationAndArchivesRatherThanDeletes() = runBlocking {
        val generated = TrainingPlanner.generatePlan(
            FoundationIntake(
                startMode = StartMode.FOUNDATION_ONLY,
                goalKind = GoalKind.FOUNDATION,
                raceDistance = null,
                availability = listOf(1, 3, 6),
                injuryFlags = InjuryFlags(),
                startDate = "2026-06-01",
            ),
        ) as GeneratedFoundationPlan
        val replacement = GeneratedPlanPersistenceMapper.map(
            generated,
            GeneratedPlanGoalMetadata(
                goalId = "replacement-goal",
                planId = "replacement-plan",
                title = "Foundation",
                goalKind = GoalKind.FOUNDATION,
                startMode = StartMode.FOUNDATION_ONLY,
                createdAtEpochMillis = 100,
            ),
        )
        val existing = GoalEntity(
            goalId = "existing-goal",
            title = "Existing",
            targetDateEpochDay = null,
            state = "pending",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            kind = "foundation",
            startMode = "foundation_only",
            raceDistanceMeters = null,
            priority = "consistency",
        )
        database.goalPlanDao().saveGoal(existing)
        val request = LocalPlanSetupRequest(
            profile = profile(),
            availabilityDays = listOf(1, 3, 6),
            candidate = LocalPlanCandidate.Generated(replacement),
            confirmReplaceCurrent = false,
            archiveAtEpochMillis = 100,
        )
        val repository = LocalPlanSetupRepository(database)

        assertEquals(LocalPlanSetupResult.ReplacementConfirmationRequired(1, 0), repository.setUp(request))
        assertEquals(listOf("existing-goal"), database.goalPlanDao().currentGoalIds(10))

        assertEquals(
            LocalPlanSetupResult.Created("replacement-goal", "replacement-plan"),
            repository.setUp(request.copy(confirmReplaceCurrent = true)),
        )
        val sqlite = database.openHelper.writableDatabase
        assertEquals(2, count(sqlite, "goals"))
        assertEquals(1, count(sqlite, "plans"))
        sqlite.query("SELECT state FROM goals WHERE goalId = 'existing-goal'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("archived", cursor.getString(0))
        }
        assertEquals(listOf("replacement-goal"), database.goalPlanDao().currentGoalIds(10))
    }

    @Test
    fun ambiguousCurrentGoalsFailClosedWithoutChangingTheLedger() = runBlocking {
        database.goalPlanDao().saveGoal(
            GoalEntity("pending-one", "One", null, "pending", 1, 1, "race", "foundation_to_goal", 5_000, "finish_healthy"),
        )
        database.goalPlanDao().saveGoal(
            GoalEntity("pending-two", "Two", null, "pending", 2, 2, "race", "foundation_to_goal", 5_000, "finish_healthy"),
        )
        val request = LocalPlanSetupRequest(
            profile = profile(),
            availabilityDays = listOf(1, 3, 6),
            candidate = LocalPlanCandidate.Pending(
                GoalEntity("new-pending", "Later", null, "pending", 100, 100, "race", "foundation_to_goal", 5_000, "finish_healthy"),
            ),
            confirmReplaceCurrent = true,
            archiveAtEpochMillis = 100,
        )

        assertEquals(
            LocalPlanSetupResult.Rejected(LocalPlanSetupError.CURRENT_STATE_LIMIT_EXCEEDED),
            LocalPlanSetupRepository(database).setUp(request),
        )
        assertEquals(listOf("pending-two", "pending-one"), database.goalPlanDao().currentGoalIds(10))
        assertEquals(2, count(database.openHelper.writableDatabase, "goals"))
    }

    private fun count(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun profile() = ProfileSettingsEntity(
        timeZone = "America/Halifax",
        routeDataMode = "private",
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
        updatedAtEpochMillis = 100,
    )
}
