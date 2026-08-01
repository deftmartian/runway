package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunwayLedgerDatabaseInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunwayLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun currentSchemaContainsNormalizedLedgerTablesAndForeignKeys() {
        val sqlite = database.openHelper.writableDatabase
        val tableNames = buildSet {
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertTrue(
            tableNames.containsAll(
                setOf(
                    "profile_settings",
                    "profile_availability_days",
                    "goals",
                    "plans",
                    "routine_schedule_days",
                    "plan_summary_warnings",
                    "plan_weeks",
                    "plan_source_references",
                    "plan_lifecycle_events",
                    "workouts",
                    "workout_blocks",
                    "workout_segments",
                    "workout_source_references",
                    "activities",
                    "workout_feedback",
                    "workout_feedback_consequences",
                    "workout_feedback_consequence_options",
                    "route_samples",
                    "heart_rate_samples",
                    "activity_consequences",
                    "activity_consequence_options",
                    "plan_adjustments",
                    "adjustment_effect_groups",
                    "adjustment_workout_effects",
                    "adjustment_effect_block_snapshots",
                    "adjustment_effect_segment_snapshots",
                    "adjustment_effect_source_reference_snapshots",
                    "adjustment_consequences",
                    "plan_decisions",
                    "decision_consequences",
                    "plan_reversals",
                    "health_connect_mappings",
                    "health_connect_pending_observations",
                    "health_connect_pending_route_samples",
                    "health_connect_pending_heart_rate_samples",
                    "import_digests",
                    "app_metadata",
                    "plan_setup_receipts",
                ),
            ),
        )
        val workoutParents = buildSet {
            sqlite.query("PRAGMA foreign_key_list(workouts)").use { cursor ->
                val tableColumn = cursor.getColumnIndexOrThrow("table")
                while (cursor.moveToNext()) add(cursor.getString(tableColumn))
            }
        }
        assertEquals(setOf("plan_weeks"), workoutParents)
        val mappingIndexes = buildSet {
            sqlite.query("PRAGMA index_list(health_connect_mappings)").use { cursor ->
                val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) {
                    if (cursor.getInt(uniqueColumn) == 1) add(cursor.getString(1))
                }
            }
        }
        assertTrue(mappingIndexes.isNotEmpty())
        assertEquals(RunwayLedgerDatabase.SCHEMA_VERSION, sqlite.version)
        val identity = sqlite.query(
            "SELECT identity_hash FROM room_master_table WHERE id = 42",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        assertEquals(RunwayLedgerDatabase.SCHEMA_IDENTITY_HASH, identity)
    }

    @Test
    fun graphImportAndBoundedSamplesAreTransactionalAndClearAllRemovesLedger() = runBlocking {
        val now = 1_700_000_000_000L
        val goal = GoalEntity("goal-1", "Autumn 10K", 20_000, "active", now, now, "race", "established", 10_000, "finish_healthy")
        val plan = PlanEntity("plan-1", goal.goalId, "distance", "active", 19_900, 20_000, now, now)
        val week = PlanWeekEntity("week-1", plan.planId, 1, 19_900, 12_000)
        val workout = WorkoutEntity(
            workoutId = "workout-1",
            planId = plan.planId,
            weekId = week.weekId,
            position = 0,
            generatedPurpose = "easy",
            generatedDistanceMeters = 5_000,
            generatedDurationSeconds = null,
            currentPurpose = "easy",
            currentDistanceMeters = 5_000,
            currentDurationSeconds = null,
            tombstonedAtEpochMillis = null,
            updatedAtEpochMillis = now,
            generatedScheduledEpochDay = 19_901,
            currentScheduledEpochDay = 19_901,
            generatedWorkoutType = "easy",
            currentWorkoutType = "easy",
            generatedPrescriptionKind = "distance",
            currentPrescriptionKind = "distance",
        )
        database.profileSettingsDao().save(
            ProfileSettingsEntity(
                timeZone = "America/Halifax",
                routeDataMode = "private",
                heartRateSettingsSource = "custom",
                maxHeartRateBpm = 190,
                zone2FloorBpm = 114,
                zone3FloorBpm = 133,
                zone4FloorBpm = 152,
                zone5FloorBpm = 171,
                recentInjury = false,
                currentPain = false,
                recurringPain = false,
                medicalRestriction = false,
                privateNotes = null,
                updatedAtEpochMillis = now,
                baselineDistanceMeters = 12_000,
                baselineDurationSeconds = 4_200,
                baselineConfirmed = true,
                currentRunsPerWeek = 3,
                longestRecentRunMeters = 8_000,
                calibrationDurationSeconds = 1_200,
                confirmConcentratedSchedule = true,
            ),
        )
        database.profileSettingsDao().replaceOnboardingInputs(
            database.profileSettingsDao().get()!!,
            listOf(1, 3, 6),
        )
        assertEquals(listOf(1, 3, 6), database.profileSettingsDao().availabilityDays(limit = 10).map { it.dayOfWeek })
        database.goalPlanDao().saveGoal(goal)
        database.goalPlanDao().createPlanGraph(plan, listOf(week), listOf(workout))
        assertTrue(
            runCatching {
                database.goalPlanDao().saveWorkout(workout.copy(workoutId = "invalid-workout", planId = "other-plan"))
            }.isFailure,
        )
        database.goalPlanDao().savePlanSourceReference(
            PlanSourceReferenceEntity("plan-source-1", plan.planId, 0, "NHS Couch to 5K", "week 1"),
        )
        database.goalPlanDao().saveLifecycleEvent(
            PlanLifecycleEventEntity("lifecycle-1", plan.planId, "created", now, 0, 0, null),
        )
        database.goalPlanDao().saveBlock(
            WorkoutBlockEntity("block-1", workout.workoutId, "current", 0, "interval", 6),
        )
        database.goalPlanDao().saveSegment(
            WorkoutSegmentEntity("segment-1", "block-1", 0, "run", null, 300),
        )
        database.goalPlanDao().saveWorkoutSourceReference(
            WorkoutSourceReferenceEntity("workout-source-1", workout.workoutId, "current", 0, "Plan source", "week 1"),
        )

        val activity = ActivityEntity(
            activityId = "activity-1",
            source = "health_connect",
            sourceRecordId = "record-1",
            reviewState = "review",
            occurredAtEpochMillis = now,
            durationSeconds = 1_800,
            distanceMeters = 5_000,
            averageHeartRateBpm = 144,
            averageCadenceSpm = 170,
            linkedWorkoutId = workout.workoutId,
            acceptedAtEpochMillis = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            maxHeartRateBpm = 163,
            maxSpeedMetersPerSecond = 4.5,
            elevationGainMeters = 25.0,
            routePointCount = 4,
            heartRatePointCount = 3,
            heartRateSourceSampleCount = 6,
            routeTraceRetained = true,
            heartRateSeriesRetained = true,
        )
        assertTrue(database.importLedgerDao().recordImportedActivity(activity, activity.source, "digest-1", now))
        assertFalse(database.importLedgerDao().recordImportedActivity(activity, activity.source, "digest-1", now))
        assertTrue(database.activityLedgerDao().acceptedActivitiesInRange(now - 1, now + 1, 10).isEmpty())
        database.activityLedgerDao().saveActivity(activity.copy(reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED, acceptedAtEpochMillis = now))
        assertEquals(listOf(activity.activityId), database.activityLedgerDao().acceptedActivitiesInRange(now - 1, now + 1, 10).map { it.activityId })
        database.activityLedgerDao().replaceRouteSamplesBounded(
            activity.activityId,
            listOf(
                RouteSampleEntity(activityId = activity.activityId, ordinal = 9, latitudeE6 = 1, longitudeE6 = 2, elapsedSeconds = 0, elevationMeters = null),
                RouteSampleEntity(activityId = activity.activityId, ordinal = 10, latitudeE6 = 3, longitudeE6 = 4, elapsedSeconds = 10, elevationMeters = null),
                RouteSampleEntity(activityId = activity.activityId, ordinal = 11, latitudeE6 = 5, longitudeE6 = 6, elapsedSeconds = 20, elevationMeters = null),
                RouteSampleEntity(activityId = activity.activityId, ordinal = 12, latitudeE6 = 7, longitudeE6 = 8, elapsedSeconds = 30, elevationMeters = null),
            ),
            maximumSamples = 3,
        )
        assertEquals(listOf(1, 3, 7), database.activityLedgerDao().routeSamples(activity.activityId, 10).map { it.latitudeE6 })
        database.activityLedgerDao().clearAllRouteSamples()
        assertTrue(database.activityLedgerDao().routeSamples(activity.activityId, 10).isEmpty())
        assertTrue(database.activityLedgerDao().activity(activity.activityId) != null)
        database.activityLedgerDao().replaceHeartRateSamplesBounded(
            activity.activityId,
            listOf(
                HeartRateSampleEntity(activityId = activity.activityId, ordinal = 0, elapsedSeconds = 0, beatsPerMinute = 120, sourceSampleCount = 2),
                HeartRateSampleEntity(activityId = activity.activityId, ordinal = 1, elapsedSeconds = 10, beatsPerMinute = 130, sourceSampleCount = 2),
            ),
            maximumSamples = 1,
        )
        assertEquals(listOf(120), database.activityLedgerDao().heartRateSamples(activity.activityId, 10).map { it.beatsPerMinute })
        database.activityLedgerDao().saveWorkoutFeedback(
            WorkoutFeedbackEntity("workout-feedback-1", workout.workoutId, "short", true, false, null, now),
        )
        database.activityLedgerDao().saveWorkoutFeedbackConsequence(
            WorkoutFeedbackConsequenceEntity("workout-feedback-1", "short", -500, null, 12_000, 11_500, "Above default", 1, "reduce_next", "rest", true),
        )
        database.activityLedgerDao().saveActivityConsequence(
            ActivityConsequenceEntity(activity.activityId, "Needs review", null, null, 5_000, "Needs review", "count_extra", null),
        )

        database.adjustmentDao().saveAdjustment(
            PlanAdjustmentEntity("adjustment-1", plan.planId, workout.workoutId, activity.activityId, "reduce_next", "applied", 12.5, 8.0, 1, now),
        )
        database.adjustmentDao().saveAdjustmentConsequence(
            AdjustmentConsequenceEntity("adjustment-1", 12_000, 11_000, 0, "Within default", "run"),
        )
        database.adjustmentDao().saveEffectGroup(
            AdjustmentEffectGroupEntity("effect-group-1", "adjustment-1", 0, "workout_change", 12_000, 11_000),
        )
        database.adjustmentDao().saveWorkoutEffect(
            AdjustmentWorkoutEffectEntity(
                "effect-1", "effect-group-1", workout.workoutId, 0,
                19_901, 19_902, "run", "run", "planned", "planned", 5_000, 4_500,
                null, null, "easy", "easy", "foundation", "foundation", null, "reduce load", null, null,
            ),
        )
        database.adjustmentDao().saveDecision(
            PlanDecisionEntity("decision-1", "adjustment-1", "reduce_next", 1, 19_901, now),
        )
        database.adjustmentDao().saveDecisionConsequence(
            DecisionConsequenceEntity("decision-1", 11_000, "Within default", "run", now),
        )
        database.adjustmentDao().saveReversal(PlanReversalEntity("reversal-1", "decision-1", null, now))
        database.importLedgerDao().saveHealthConnectMapping(
            HealthConnectMappingEntity("mapping-1", "health_connect", "record-1", activity.activityId, now, now, correctionPending = true),
        )
        database.appMetadataDao().save(AppMetadataEntity("schema_owner", "local", now))

        database.importLedgerDao().deleteImportedActivityToTombstone(activity.activityId, activity.source, "digest-1", now + 1)
        database.importLedgerDao().healthConnectMapping("health_connect", "record-1")!!.also { mapping ->
            assertNull(mapping.activityId)
            assertEquals(HEALTH_CONNECT_MAPPING_STATE_TOMBSTONED, mapping.lifecycleState)
            assertEquals(now + 1, mapping.deletedAtEpochMillis)
        }

        database.maintenanceDao().clearAll()

        assertNull(database.profileSettingsDao().get())
        assertNull(database.activityLedgerDao().activity(activity.activityId))
        assertNull(database.importLedgerDao().digest(activity.source, "digest-1"))
        assertNull(database.importLedgerDao().healthConnectMapping("health_connect", "record-1"))
        assertNull(database.appMetadataDao().value("schema_owner"))
        assertTrue(database.goalPlanDao().weeksForPlan(plan.planId, 10).isEmpty())
    }

    @Test
    fun activityRangesIncludeTheStartAndExcludeTheExactEndBoundary() = runBlocking {
        val dao = database.activityLedgerDao()
        fun activity(id: String, occurredAt: Long) = ActivityEntity(
            activityId = id,
            source = "manual",
            sourceRecordId = null,
            reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED,
            occurredAtEpochMillis = occurredAt,
            durationSeconds = 600,
            distanceMeters = 1_000,
            averageHeartRateBpm = null,
            averageCadenceSpm = null,
            linkedWorkoutId = null,
            acceptedAtEpochMillis = occurredAt,
            createdAtEpochMillis = occurredAt,
            updatedAtEpochMillis = occurredAt,
        )
        listOf(
            activity("before", 999),
            activity("start", 1_000),
            activity("inside", 1_999),
            activity("exact-end", 2_000),
        ).forEach { dao.saveActivity(it) }

        assertEquals(
            listOf("inside", "start"),
            dao.activitiesInRange(1_000, 2_000, 10).map(ActivityEntity::activityId),
        )
        assertEquals(
            listOf("inside", "start"),
            dao.acceptedActivitiesInRange(1_000, 2_000, 10).map(ActivityEntity::activityId),
        )
    }
}
