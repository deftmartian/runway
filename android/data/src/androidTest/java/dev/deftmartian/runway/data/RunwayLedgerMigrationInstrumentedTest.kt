package dev.deftmartian.runway.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises every released database predecessor, then verifies a second open is stable. */
@RunWith(AndroidJUnit4::class)
class RunwayLedgerMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RunwayLedgerDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun v1LedgerUpgradesWithoutChangingExistingRetentionAndSecondOpenIsStable() = runBlocking<Unit> {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO profile_settings (
                    singletonId, timeZone, routeDataMode, heartRateSettingsSource,
                    maxHeartRateBpm, zone2FloorBpm, zone3FloorBpm, zone4FloorBpm, zone5FloorBpm,
                    recentInjury, currentPain, recurringPain, medicalRestriction, privateNotes,
                    updatedAtEpochMillis, baselineConfirmed, confirmConcentratedSchedule,
                    experienceLevel, sexForEstimates
                ) VALUES (
                    1, 'America/Halifax', 'private', 'custom',
                    190, 114, 133, 152, 171,
                    0, 0, 0, 0, 'existing private note',
                    1700000000000, 1, 0,
                    'comfortable', 'female'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO activities (
                    activityId, source, sourceRecordId, reviewState, occurredAtEpochMillis,
                    durationSeconds, distanceMeters, averageHeartRateBpm, averageCadenceSpm,
                    linkedWorkoutId, acceptedAtEpochMillis, createdAtEpochMillis,
                    updatedAtEpochMillis, maxHeartRateBpm, maxSpeedMetersPerSecond,
                    elevationGainMeters, elevationLossMeters, routePointCount,
                    heartRatePointCount, elevationPointCount, heartRateSourceSampleCount,
                    routeTraceRetained, routeStartEndRedacted, heartRateSeriesRetained,
                    extraPlanImpactConfirmed
                ) VALUES (
                    'preserved-activity', 'gpx', 'released-v1-source', 'review',
                    1700000000000, 1800, 5000, 145, 170,
                    NULL, NULL, 1700000000000,
                    1700000000000, 166, NULL,
                    20.0, NULL, 0,
                    1, 0, 1,
                    0, 1, 1,
                    0
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO heart_rate_samples (
                    activityId, ordinal, elapsedSeconds, beatsPerMinute, sourceSampleCount
                ) VALUES ('preserved-activity', 0, 0, 145, 1)
                """.trimIndent(),
            )
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val upgraded = Room.databaseBuilder(
            context,
            RunwayLedgerDatabase::class.java,
            TEST_DATABASE,
        )
            .addMigrations(
                RunwayLedgerMigrations.V1_TO_V2,
                RunwayLedgerMigrations.V2_TO_V3,
                RunwayLedgerMigrations.V3_TO_V4,
                RunwayLedgerMigrations.V4_TO_V5,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val profile = requireNotNull(upgraded.profileSettingsDao().get())
            assertEquals("private", profile.routeDataMode)
            assertEquals("private", profile.heartRateDataMode)
            assertEquals("female", profile.sexForEstimates)
            assertEquals("existing private note", profile.privateNotes)
            val activity = requireNotNull(
                upgraded.activityLedgerDao().activity("preserved-activity"),
            )
            assertEquals(145, activity.averageHeartRateBpm)
            assertEquals(
                listOf(145),
                upgraded.activityLedgerDao()
                    .heartRateSamples("preserved-activity", 10)
                    .map { it.beatsPerMinute },
            )
            assertEquals(5, upgraded.openHelper.writableDatabase.version)
        } finally {
            upgraded.close()
        }

        val reopened = Room.databaseBuilder(
            context,
            RunwayLedgerDatabase::class.java,
            TEST_DATABASE,
        )
            .addMigrations(
                RunwayLedgerMigrations.V1_TO_V2,
                RunwayLedgerMigrations.V2_TO_V3,
                RunwayLedgerMigrations.V3_TO_V4,
                RunwayLedgerMigrations.V4_TO_V5,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val profile = reopened.profileSettingsDao().get()
            assertNotNull(profile)
            assertEquals("private", profile?.heartRateDataMode)
            assertTrue(reopened.openHelper.writableDatabase.version == 5)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun v1BackupCandidateUsesTheSameMigrationAndPreparationIsIdempotent() = runBlocking<Unit> {
        helper.createDatabase(TEST_BACKUP_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO profile_settings (
                    singletonId, timeZone, routeDataMode, heartRateSettingsSource,
                    maxHeartRateBpm, zone2FloorBpm, zone3FloorBpm, zone4FloorBpm, zone5FloorBpm,
                    recentInjury, currentPain, recurringPain, medicalRestriction, privateNotes,
                    updatedAtEpochMillis, baselineConfirmed, confirmConcentratedSchedule,
                    experienceLevel, sexForEstimates
                ) VALUES (
                    1, 'America/Halifax', 'private', 'custom',
                    190, 114, 133, 152, 171,
                    0, 0, 0, 0, 'restore me',
                    1700000000000, 1, 0,
                    'comfortable', 'not_specified'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO goals (
                    goalId, title, targetDateEpochDay, state, createdAtEpochMillis,
                    updatedAtEpochMillis, kind, startMode, raceDistanceMeters, priority
                ) VALUES (
                    'preserved-goal', 'Preserved 5K', 21000, 'active', 1700000000000,
                    1700000000000, 'race', 'established', 5000, 'finish_healthy'
                )
                """.trimIndent(),
            )
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidate = context.getDatabasePath(TEST_BACKUP_DATABASE)
        assertNull(LocalRestoreCandidate.prepare(candidate))
        assertNull(LocalRestoreCandidate.prepare(candidate))

        val restored = Room.databaseBuilder(
            context,
            RunwayLedgerDatabase::class.java,
            TEST_BACKUP_DATABASE,
        )
            .addMigrations(
                RunwayLedgerMigrations.V1_TO_V2,
                RunwayLedgerMigrations.V2_TO_V3,
                RunwayLedgerMigrations.V3_TO_V4,
                RunwayLedgerMigrations.V4_TO_V5,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val profile = requireNotNull(restored.profileSettingsDao().get())
            assertEquals("private", profile.heartRateDataMode)
            assertEquals("restore me", profile.privateNotes)
            assertEquals(
                "Preserved 5K",
                restored.goalPlanDao().goal("preserved-goal")?.title,
            )
            assertEquals(5, restored.openHelper.writableDatabase.version)
        } finally {
            restored.close()
        }
    }

    @Test
    fun v4LedgerAddsAnEmptyRoutineScheduleWithoutRewritingExistingGoals() = runBlocking<Unit> {
        helper.createDatabase(TEST_V4_ROUTINE_DATABASE, 4).apply {
            execSQL(
                """
                INSERT INTO goals (
                    goalId, title, targetDateEpochDay, state, createdAtEpochMillis,
                    updatedAtEpochMillis, kind, startMode, raceDistanceMeters, priority
                ) VALUES (
                    'v4-goal', 'Existing plan', 21000, 'active', 1700000000000,
                    1700000000000, 'race', 'established', 5000, 'finish_healthy'
                )
                """.trimIndent(),
            )
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val upgraded = Room.databaseBuilder(
            context,
            RunwayLedgerDatabase::class.java,
            TEST_V4_ROUTINE_DATABASE,
        )
            .addMigrations(RunwayLedgerMigrations.V4_TO_V5)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("Existing plan", upgraded.goalPlanDao().goal("v4-goal")?.title)
            assertTrue(upgraded.goalPlanDao().routineScheduleDays("missing").isEmpty())
            assertEquals(5, upgraded.openHelper.writableDatabase.version)
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun v2LedgerRepairsConflictingRetentionModesWithoutDeletingPrivateEvidence() =
        runBlocking<Unit> {
            helper.createDatabase(TEST_V2_DATABASE, 2).apply {
                execSQL(
                    """
                    INSERT INTO profile_settings (
                        singletonId, timeZone, routeDataMode, heartRateDataMode,
                        heartRateSettingsSource, maxHeartRateBpm, zone2FloorBpm,
                        zone3FloorBpm, zone4FloorBpm, zone5FloorBpm,
                        recentInjury, currentPain, recurringPain, medicalRestriction,
                        privateNotes, updatedAtEpochMillis, baselineConfirmed,
                        confirmConcentratedSchedule, experienceLevel, sexForEstimates
                    ) VALUES (
                        1, 'America/Halifax', 'discard', 'discard',
                        'custom', 190, 114,
                        133, 152, 171,
                        0, 0, 0, 0,
                        NULL, 1700000000000, 1,
                        0, 'comfortable', 'female'
                    )
                    """.trimIndent(),
                )
                execSQL(
                    """
                    INSERT INTO activities (
                        activityId, source, sourceRecordId, reviewState, occurredAtEpochMillis,
                        durationSeconds, distanceMeters, averageHeartRateBpm, averageCadenceSpm,
                        linkedWorkoutId, acceptedAtEpochMillis, createdAtEpochMillis,
                        updatedAtEpochMillis, maxHeartRateBpm, maxSpeedMetersPerSecond,
                        elevationGainMeters, elevationLossMeters, routePointCount,
                        heartRatePointCount, elevationPointCount, heartRateSourceSampleCount,
                        routeTraceRetained, routeStartEndRedacted, heartRateSeriesRetained,
                        extraPlanImpactConfirmed
                    ) VALUES (
                        'repair-activity', 'gpx', 'synthetic-v2-source', 'review',
                        1700000000000, 1800, 5000, 145, 170,
                        NULL, NULL, 1700000000000,
                        1700000000000, 166, NULL,
                        20.0, NULL, 1,
                        1, 0, 1,
                        1, 0, 1,
                        0
                    )
                    """.trimIndent(),
                )
                execSQL(
                    """
                    INSERT INTO route_samples (
                        activityId, ordinal, latitudeE6, longitudeE6, elapsedSeconds
                    ) VALUES ('repair-activity', 0, 0, 0, 0)
                    """.trimIndent(),
                )
                execSQL(
                    """
                    INSERT INTO heart_rate_samples (
                        activityId, ordinal, elapsedSeconds, beatsPerMinute, sourceSampleCount
                    ) VALUES ('repair-activity', 0, 0, 145, 1)
                    """.trimIndent(),
                )
                seedV2SourceReferences("ledger")
                close()
            }

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val upgraded = Room.databaseBuilder(
                context,
                RunwayLedgerDatabase::class.java,
                TEST_V2_DATABASE,
            )
                .addMigrations(
                    RunwayLedgerMigrations.V2_TO_V3,
                    RunwayLedgerMigrations.V3_TO_V4,
                    RunwayLedgerMigrations.V4_TO_V5,
                )
                .allowMainThreadQueries()
                .build()
            try {
                val profile = requireNotNull(upgraded.profileSettingsDao().get())
                assertEquals("private", profile.routeDataMode)
                assertEquals("private", profile.heartRateDataMode)
                assertEquals(
                    1,
                    upgraded.activityLedgerDao().routeSamples("repair-activity", 10).size,
                )
                assertEquals(
                    listOf(145),
                    upgraded.activityLedgerDao()
                        .heartRateSamples("repair-activity", 10)
                        .map { it.beatsPerMinute },
                )
                assertEquals(
                    RetentionRepairNotice(
                        routeModeRestored = true,
                        heartRateModeRestored = true,
                    ),
                    LocalPrivacyRepository(upgraded).pendingRetentionRepairNotice(),
                )
                assertV2SourceReferencesPreserved(upgraded, "ledger")
                assertEquals(5, upgraded.openHelper.writableDatabase.version)
            } finally {
                upgraded.close()
            }

            val reopened = Room.databaseBuilder(
                context,
                RunwayLedgerDatabase::class.java,
                TEST_V2_DATABASE,
            )
                .addMigrations(
                    RunwayLedgerMigrations.V2_TO_V3,
                    RunwayLedgerMigrations.V3_TO_V4,
                    RunwayLedgerMigrations.V4_TO_V5,
                )
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals(
                    "private",
                    requireNotNull(reopened.profileSettingsDao().get()).routeDataMode,
                )
                assertEquals(
                    1,
                    reopened.activityLedgerDao().routeSamples("repair-activity", 10).size,
                )
                assertV2SourceReferencesPreserved(reopened, "ledger")
                LocalPrivacyRepository(reopened).acknowledgeRetentionRepairNotice()
                assertNull(
                    LocalPrivacyRepository(reopened).pendingRetentionRepairNotice(),
                )
            } finally {
                reopened.close()
            }
        }

    @Test
    fun v2BackupCandidateUpgradesOnceAndSecondPreparationIsANoOp() = runBlocking<Unit> {
        helper.createDatabase(TEST_V2_BACKUP_DATABASE, 2).apply {
            execSQL(
                """
                INSERT INTO profile_settings (
                    singletonId, timeZone, routeDataMode, heartRateDataMode,
                    heartRateSettingsSource, maxHeartRateBpm, zone2FloorBpm,
                    zone3FloorBpm, zone4FloorBpm, zone5FloorBpm,
                    recentInjury, currentPain, recurringPain, medicalRestriction,
                    privateNotes, updatedAtEpochMillis, baselineConfirmed,
                    confirmConcentratedSchedule, experienceLevel, sexForEstimates
                ) VALUES (
                    1, 'America/Halifax', 'private', 'private',
                    'none', NULL, NULL,
                    NULL, NULL, NULL,
                    0, 0, 0, 0,
                    'v2 backup', 1700000000000, 0,
                    0, 'not_specified', 'not_specified'
                )
                """.trimIndent(),
            )
            seedV2SourceReferences("restore")
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidate = context.getDatabasePath(TEST_V2_BACKUP_DATABASE)
        assertNull(LocalRestoreCandidate.prepare(candidate))
        assertNull(LocalRestoreCandidate.prepare(candidate))

        val restored = Room.databaseBuilder(
            context,
            RunwayLedgerDatabase::class.java,
            TEST_V2_BACKUP_DATABASE,
        )
            .addMigrations(
                RunwayLedgerMigrations.V1_TO_V2,
                RunwayLedgerMigrations.V2_TO_V3,
                RunwayLedgerMigrations.V3_TO_V4,
                RunwayLedgerMigrations.V4_TO_V5,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(
                "v2 backup",
                requireNotNull(restored.profileSettingsDao().get()).privateNotes,
            )
            assertV2SourceReferencesPreserved(restored, "restore")
            assertEquals(5, restored.openHelper.writableDatabase.version)
        } finally {
            restored.close()
        }
    }

    @Test
    fun v2BackupWithCollidingReceiptTableFailsWithoutBlessingTheSchema() {
        helper.createDatabase(TEST_V2_COLLISION_DATABASE, 2).apply {
            execSQL(
                """
                CREATE TABLE plan_setup_receipts (
                    operationId TEXT NOT NULL PRIMARY KEY,
                    goalId TEXT NOT NULL
                )
                """.trimIndent(),
            )
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidate = context.getDatabasePath(TEST_V2_COLLISION_DATABASE)
        assertEquals(
            "This backup could not be upgraded safely. The original file was not installed.",
            LocalRestoreCandidate.prepare(candidate),
        )

        SQLiteDatabase.openDatabase(
            candidate.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            assertEquals(2, sqlite.version)
            sqlite.rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
                emptyArray(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(RunwayLedgerMigrations.V2_IDENTITY_HASH, cursor.getString(0))
            }
        }
    }

    @Test
    fun v3LedgerRepairsChainedTimedConsequenceStructuresAndGuardedUndoRemainsViable() =
        runBlocking<Unit> {
            helper.createDatabase(TEST_V3_TIMED_CONSEQUENCE_DATABASE, 3).apply {
                seedV3DivergentTimedConsequenceChain()
                close()
            }

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val upgraded = Room.databaseBuilder(
                context,
                RunwayLedgerDatabase::class.java,
                TEST_V3_TIMED_CONSEQUENCE_DATABASE,
            )
                .addMigrations(RunwayLedgerMigrations.V3_TO_V4, RunwayLedgerMigrations.V4_TO_V5)
                .allowMainThreadQueries()
                .build()
            try {
                assertTimedConsequenceRepair(upgraded)
                assertEquals(5, upgraded.openHelper.writableDatabase.version)
            } finally {
                upgraded.close()
            }

            val reopened = Room.databaseBuilder(
                context,
                RunwayLedgerDatabase::class.java,
                TEST_V3_TIMED_CONSEQUENCE_DATABASE,
            )
                .addMigrations(RunwayLedgerMigrations.V3_TO_V4, RunwayLedgerMigrations.V4_TO_V5)
                .allowMainThreadQueries()
                .build()
            try {
                // Opening the already-repaired v4 ledger cannot resize it a second time.
                assertTimedConsequenceRepair(reopened)

                val undone = reopened.localWorkoutChangeRepository().undo(
                    adjustmentId = "timed-adjustment-2",
                    reversalId = "timed-reversal-2",
                    reversedAtEpochMillis = 1_700_000_003_000,
                    today = LocalDate.ofEpochDay(22_000),
                )
                assertEquals(
                    UndoLocalWorkoutChangeResult.Undone(
                        "timed-adjustment-2",
                        listOf("timed-workout"),
                    ),
                    undone,
                )
                val restored = requireNotNull(reopened.goalPlanDao().workout("timed-workout"))
                assertEquals(1_201, restored.currentDurationSeconds)
                assertEquals(100, restored.currentWarmupSeconds)
                assertEquals(101, restored.currentCooldownSeconds)
                assertEquals(1_201, currentTimedTotal(reopened, "timed-workout"))
            } finally {
                reopened.close()
            }
        }

    @Test
    fun v3TimedConsequenceWithImpossibleStructureFailsClosedAtVersion3() {
        helper.createDatabase(TEST_V3_INVALID_TIMED_CONSEQUENCE_DATABASE, 3).apply {
            seedV3DivergentTimedConsequenceChain()
            execSQL(
                "UPDATE adjustment_effect_segment_snapshots SET targetDurationSeconds = NULL " +
                    "WHERE segmentSnapshotId = 'timed-effect-2-after-segment-1'",
            )
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val opening = Room.databaseBuilder(
            context,
            RunwayLedgerDatabase::class.java,
            TEST_V3_INVALID_TIMED_CONSEQUENCE_DATABASE,
        )
            .addMigrations(RunwayLedgerMigrations.V3_TO_V4, RunwayLedgerMigrations.V4_TO_V5)
            .allowMainThreadQueries()
            .build()
        try {
            assertNotNull(runCatching { opening.openHelper.writableDatabase }.exceptionOrNull())
        } finally {
            opening.close()
        }

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_V3_INVALID_TIMED_CONSEQUENCE_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            assertEquals(3, sqlite.version)
            sqlite.rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
                emptyArray(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(RunwayLedgerMigrations.V3_IDENTITY_HASH, cursor.getString(0))
            }
        }
    }

    @Test
    fun v3DivergentBackupUsesTheSameRepairAndSecondPreparationIsANoOp() = runBlocking<Unit> {
        helper.createDatabase(TEST_V3_TIMED_CONSEQUENCE_BACKUP_DATABASE, 3).apply {
            seedV3DivergentTimedConsequenceChain()
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidate = context.getDatabasePath(TEST_V3_TIMED_CONSEQUENCE_BACKUP_DATABASE)
        assertNull(LocalRestoreCandidate.prepare(candidate))
        assertNull(LocalRestoreCandidate.prepare(candidate))

        val restored = Room.databaseBuilder(
            context,
            RunwayLedgerDatabase::class.java,
            TEST_V3_TIMED_CONSEQUENCE_BACKUP_DATABASE,
        )
            .addMigrations(RunwayLedgerMigrations.V3_TO_V4, RunwayLedgerMigrations.V4_TO_V5)
            .allowMainThreadQueries()
            .build()
        try {
            assertTimedConsequenceRepair(restored)
            assertEquals(5, restored.openHelper.writableDatabase.version)
        } finally {
            restored.close()
        }
    }

    private fun SupportSQLiteDatabase.seedV3DivergentTimedConsequenceChain() {
        execSQL(
            """
            INSERT INTO goals (
                goalId, title, targetDateEpochDay, state, createdAtEpochMillis,
                updatedAtEpochMillis, kind, startMode, raceDistanceMeters, priority
            ) VALUES (
                'timed-goal', 'Timed migration goal', 23000, 'active', 1700000000000,
                1700000000000, 'foundation', 'timed_calibration', NULL, 'finish_healthy'
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO plans (
                planId, goalId, phaseType, state, startEpochDay,
                createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'timed-plan', 'timed-goal', 'foundation', 'active', 22000,
                1700000000000, 1700000000000
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO plan_weeks (
                weekId, planId, ordinal, startEpochDay, isDownWeek, isTaperWeek
            ) VALUES ('timed-week', 'timed-plan', 0, 22000, 0, 0)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO workouts (
                workoutId, planId, weekId, position,
                generatedPurpose, generatedDistanceMeters, generatedDurationSeconds,
                currentPurpose, currentDistanceMeters, currentDurationSeconds,
                updatedAtEpochMillis, generatedScheduledEpochDay, currentScheduledEpochDay,
                generatedWorkoutType, currentWorkoutType,
                generatedPrescriptionKind, currentPrescriptionKind,
                generatedIntensity, currentIntensity, generatedReason, currentReason,
                currentStatus, generatedWarmupSeconds, generatedCooldownSeconds,
                currentWarmupSeconds, currentCooldownSeconds
            ) VALUES (
                'timed-workout', 'timed-plan', 'timed-week', 0,
                'Easy intervals', 0, 1200,
                'Easy intervals', 0, 1500,
                1700000002000, 22001, 22001,
                'easy', 'easy',
                'timed', 'timed',
                'easy', 'easy', 'Generated timed', 'legacy-two',
                'planned', 100, 100,
                100, 100
            )
            """.trimIndent(),
        )
        listOf("generated", "current").forEach { version ->
            execSQL(
                """
                INSERT INTO workout_blocks (
                    blockId, workoutId, prescriptionVersion, ordinal, blockType, repetitions
                ) VALUES (
                    'timed-$version-block', 'timed-workout', '$version', 0, 'timed', 2
                )
                """.trimIndent(),
            )
            listOf("run", "walk").forEachIndexed { ordinal, type ->
                execSQL(
                    """
                    INSERT INTO workout_segments (
                        segmentId, blockId, ordinal, segmentType,
                        targetDistanceMeters, targetDurationSeconds
                    ) VALUES (
                        'timed-$version-segment-$ordinal', 'timed-$version-block', $ordinal,
                        '$type', NULL, 250
                    )
                    """.trimIndent(),
                )
            }
        }
        execSQL(
            """
            INSERT INTO workout_source_references (
                referenceId, workoutId, prescriptionVersion, ordinal, sourceName, sourceLocator
            ) VALUES
                ('timed-generated-source', 'timed-workout', 'generated', 0,
                 'Generated source', 'generated-locator'),
                ('timed-current-source', 'timed-workout', 'current', 0,
                 'Current source', 'current-locator')
            """.trimIndent(),
        )

        seedV3TimedEffect(
            number = 1,
            previousDuration = 1_200,
            newDuration = 1_201,
            previousReason = "Generated timed",
            newReason = "legacy-one",
            createdAtEpochMillis = 1_700_000_001_000,
        )
        seedV3TimedEffect(
            number = 2,
            previousDuration = 1_201,
            newDuration = 1_500,
            previousReason = "legacy-one",
            newReason = "legacy-two",
            createdAtEpochMillis = 1_700_000_002_000,
        )
    }

    private fun SupportSQLiteDatabase.seedV3TimedEffect(
        number: Int,
        previousDuration: Int,
        newDuration: Int,
        previousReason: String,
        newReason: String,
        createdAtEpochMillis: Long,
    ) {
        val adjustmentId = "timed-adjustment-$number"
        val groupId = "timed-group-$number"
        val effectId = "timed-effect-$number"
        execSQL(
            """
            INSERT INTO plan_adjustments (
                adjustmentId, planId, workoutId, adjustmentType, state,
                affectedWorkoutCount, createdAtEpochMillis,
                triggerKind, triggerId, triggerVersion
            ) VALUES (
                '$adjustmentId', 'timed-plan', 'timed-workout', 'reduce_next', 'applied',
                1, $createdAtEpochMillis,
                'Activity', 'timed-trigger-$number', '$createdAtEpochMillis'
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO adjustment_effect_groups (
                groupId, adjustmentId, ordinal, effectType
            ) VALUES ('$groupId', '$adjustmentId', 0, 'consequence_decision')
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO adjustment_workout_effects (
                effectId, groupId, workoutId, ordinal,
                previousScheduledEpochDay, newScheduledEpochDay,
                previousWorkoutType, newWorkoutType, previousStatus, newStatus,
                previousDistanceMeters, newDistanceMeters,
                previousDurationSeconds, newDurationSeconds,
                previousIntensity, newIntensity, previousPurpose, newPurpose,
                previousReason, newReason,
                previousWarmupSeconds, newWarmupSeconds,
                previousCooldownSeconds, newCooldownSeconds,
                previousPrescriptionKind, newPrescriptionKind,
                previousWeekId, newWeekId
            ) VALUES (
                '$effectId', '$groupId', 'timed-workout', 0,
                22001, 22001,
                'easy', 'easy', 'planned', 'planned',
                0, 0,
                $previousDuration, $newDuration,
                'easy', 'easy', 'Easy intervals', 'Easy intervals',
                '$previousReason', '$newReason',
                100, 100,
                100, 100,
                'timed', 'timed',
                'timed-week', 'timed-week'
            )
            """.trimIndent(),
        )
        listOf("before", "after").forEach { state ->
            val blockId = "$effectId-$state-block"
            execSQL(
                """
                INSERT INTO adjustment_effect_block_snapshots (
                    blockSnapshotId, effectId, snapshotState, ordinal, blockType, repetitions
                ) VALUES ('$blockId', '$effectId', '$state', 0, 'timed', 2)
                """.trimIndent(),
            )
            listOf("run", "walk").forEachIndexed { ordinal, type ->
                execSQL(
                    """
                    INSERT INTO adjustment_effect_segment_snapshots (
                        segmentSnapshotId, blockSnapshotId, ordinal, segmentType,
                        targetDistanceMeters, targetDurationSeconds
                    ) VALUES (
                        '$effectId-$state-segment-$ordinal', '$blockId', $ordinal,
                        '$type', NULL, 250
                    )
                    """.trimIndent(),
                )
            }
            execSQL(
                """
                INSERT INTO adjustment_effect_source_reference_snapshots (
                    sourceReferenceSnapshotId, effectId, snapshotState, ordinal,
                    sourceName, sourceLocator
                ) VALUES (
                    '$effectId-$state-source', '$effectId', '$state', 0,
                    'Current source', 'current-locator'
                )
                """.trimIndent(),
            )
        }
        execSQL(
            """
            INSERT INTO plan_decisions (
                decisionId, adjustmentId, decisionType, affectedWorkoutCount,
                effectiveFromEpochDay, decidedAtEpochMillis
            ) VALUES (
                'timed-decision-$number', '$adjustmentId', 'reduce_next', 1,
                22001, $createdAtEpochMillis
            )
            """.trimIndent(),
        )
    }

    private suspend fun assertTimedConsequenceRepair(database: RunwayLedgerDatabase) {
        val first = database.adjustmentDao().workoutEffects("timed-group-1", 10).single()
        assertEquals(100, first.previousWarmupSeconds)
        assertEquals(100, first.previousCooldownSeconds)
        assertEquals(100, first.newWarmupSeconds)
        assertEquals(101, first.newCooldownSeconds)
        assertEquals(1_200, snapshotTimedTotal(database, first, "before"))
        assertEquals(1_201, snapshotTimedTotal(database, first, "after"))

        val second = database.adjustmentDao().workoutEffects("timed-group-2", 10).single()
        assertEquals(100, second.previousWarmupSeconds)
        assertEquals(101, second.previousCooldownSeconds)
        assertEquals(125, second.newWarmupSeconds)
        assertEquals(123, second.newCooldownSeconds)
        assertEquals(1_201, snapshotTimedTotal(database, second, "before"))
        assertEquals(1_500, snapshotTimedTotal(database, second, "after"))
        assertEquals(
            listOf(313, 313),
            database.adjustmentDao()
                .effectSegmentSnapshots(second.effectId, "after", 10)
                .map { requireNotNull(it.targetDurationSeconds) },
        )

        val workout = requireNotNull(database.goalPlanDao().workout("timed-workout"))
        assertEquals(1_500, workout.currentDurationSeconds)
        assertEquals(125, workout.currentWarmupSeconds)
        assertEquals(123, workout.currentCooldownSeconds)
        assertEquals(1_500, currentTimedTotal(database, workout.workoutId))

        // Generated recommendations and both current/history source references are not repair data.
        assertEquals(1_200, workout.generatedDurationSeconds)
        assertEquals(100, workout.generatedWarmupSeconds)
        assertEquals(100, workout.generatedCooldownSeconds)
        val generatedBlock = database.goalPlanDao()
            .blocksForWorkout(workout.workoutId, "generated", 10)
            .single()
        assertEquals(
            listOf(250, 250),
            database.goalPlanDao().segmentsForBlock(generatedBlock.blockId, 10)
                .map { requireNotNull(it.targetDurationSeconds) },
        )
        assertEquals(
            "generated-locator",
            database.goalPlanDao()
                .workoutSourceReferences(workout.workoutId, "generated", 10)
                .single()
                .sourceLocator,
        )
        assertEquals(
            "current-locator",
            database.goalPlanDao()
                .workoutSourceReferences(workout.workoutId, "current", 10)
                .single()
                .sourceLocator,
        )
        assertEquals(
            "current-locator",
            database.adjustmentDao()
                .effectSourceReferenceSnapshots(second.effectId, "after", 10)
                .single()
                .sourceLocator,
        )
    }

    private suspend fun snapshotTimedTotal(
        database: RunwayLedgerDatabase,
        effect: AdjustmentWorkoutEffectEntity,
        state: String,
    ): Int {
        val warmup = if (state == "after") effect.newWarmupSeconds else effect.previousWarmupSeconds
        val cooldown = if (state == "after") effect.newCooldownSeconds else effect.previousCooldownSeconds
        val blocks = database.adjustmentDao().effectBlockSnapshots(effect.effectId, state, 10)
        val segments = database.adjustmentDao().effectSegmentSnapshots(effect.effectId, state, 100)
            .groupBy(AdjustmentEffectSegmentSnapshotEntity::blockSnapshotId)
        return requireNotNull(warmup) + requireNotNull(cooldown) + blocks.sumOf { block ->
            block.repetitions * segments.getValue(block.blockSnapshotId)
                .sumOf { requireNotNull(it.targetDurationSeconds) }
        }
    }

    private suspend fun currentTimedTotal(
        database: RunwayLedgerDatabase,
        workoutId: String,
    ): Int {
        val workout = requireNotNull(database.goalPlanDao().workout(workoutId))
        var blockTotal = 0
        database.goalPlanDao().blocksForWorkout(workoutId, "current", 10).forEach { block ->
            blockTotal += block.repetitions *
                database.goalPlanDao().segmentsForBlock(block.blockId, 100)
                    .sumOf { requireNotNull(it.targetDurationSeconds) }
        }
        return requireNotNull(workout.currentWarmupSeconds) +
            requireNotNull(workout.currentCooldownSeconds) + blockTotal
    }

    private fun SupportSQLiteDatabase.seedV2SourceReferences(prefix: String) {
        execSQL(
            """
            INSERT INTO goals (
                goalId, title, targetDateEpochDay, state, createdAtEpochMillis,
                updatedAtEpochMillis, kind, startMode, raceDistanceMeters, priority
            ) VALUES (
                '$prefix-goal', 'Source migration goal', 22000, 'active', 1700000000000,
                1700000000000, 'race', 'established', 5000, 'finish_healthy'
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO plans (
                planId, goalId, phaseType, state, startEpochDay,
                createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                '$prefix-plan', '$prefix-goal', 'race', 'active', 21000,
                1700000000000, 1700000000000
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO plan_weeks (
                weekId, planId, ordinal, startEpochDay, isDownWeek, isTaperWeek
            ) VALUES ('$prefix-week', '$prefix-plan', 0, 21000, 0, 0)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO workouts (
                workoutId, planId, weekId, position, updatedAtEpochMillis,
                generatedScheduledEpochDay, currentScheduledEpochDay,
                generatedWorkoutType, currentWorkoutType,
                generatedPrescriptionKind, currentPrescriptionKind, currentStatus
            ) VALUES (
                '$prefix-workout', '$prefix-plan', '$prefix-week', 0, 1700000000000,
                21001, 21001,
                'easy', 'easy',
                'distance', 'distance', 'planned'
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO plan_adjustments (
                adjustmentId, planId, workoutId, adjustmentType, state,
                affectedWorkoutCount, createdAtEpochMillis
            ) VALUES (
                '$prefix-adjustment', '$prefix-plan', '$prefix-workout', 'edit', 'applied',
                1, 1700000000000
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO adjustment_effect_groups (
                groupId, adjustmentId, ordinal, effectType
            ) VALUES ('$prefix-group', '$prefix-adjustment', 0, 'workout')
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO adjustment_workout_effects (
                effectId, groupId, workoutId, ordinal
            ) VALUES ('$prefix-effect', '$prefix-group', '$prefix-workout', 0)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO plan_source_references (
                referenceId, planId, ordinal, sourceName, sourceUrl, sourceLocator
            ) VALUES (
                '$prefix-plan-reference', '$prefix-plan', 0, 'Plan source',
                'https://v2.example/plan', 'plan-locator'
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO workout_source_references (
                referenceId, workoutId, prescriptionVersion, ordinal,
                sourceName, sourceUrl, sourceLocator
            ) VALUES (
                '$prefix-workout-reference', '$prefix-workout', 'current', 0,
                'Workout source', 'https://v2.example/workout', 'workout-locator'
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO adjustment_effect_source_reference_snapshots (
                sourceReferenceSnapshotId, effectId, snapshotState, ordinal,
                sourceName, sourceUrl, sourceLocator
            ) VALUES (
                '$prefix-snapshot-reference', '$prefix-effect', 'before', 0,
                'Snapshot source', 'https://v2.example/snapshot', 'snapshot-locator'
            )
            """.trimIndent(),
        )
    }

    private suspend fun assertV2SourceReferencesPreserved(
        database: RunwayLedgerDatabase,
        prefix: String,
    ) {
        val planReference = database.goalPlanDao()
            .planSourceReferences("$prefix-plan", 10)
            .single()
        assertEquals("$prefix-plan-reference", planReference.referenceId)
        assertEquals("Plan source", planReference.sourceName)
        assertEquals("plan-locator", planReference.sourceLocator)

        val workoutReference = database.goalPlanDao()
            .workoutSourceReferences("$prefix-workout", "current", 10)
            .single()
        assertEquals("$prefix-workout-reference", workoutReference.referenceId)
        assertEquals("Workout source", workoutReference.sourceName)
        assertEquals("workout-locator", workoutReference.sourceLocator)

        val snapshotReference = database.adjustmentDao()
            .effectSourceReferenceSnapshots("$prefix-effect", "before", 10)
            .single()
        assertEquals("$prefix-snapshot-reference", snapshotReference.sourceReferenceSnapshotId)
        assertEquals("Snapshot source", snapshotReference.sourceName)
        assertEquals("snapshot-locator", snapshotReference.sourceLocator)

        listOf(
            "plan_source_references",
            "workout_source_references",
            "adjustment_effect_source_reference_snapshots",
        ).forEach { table ->
            database.openHelper.writableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val columns = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertTrue("sourceUrl must be removed from $table", "sourceUrl" !in columns)
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "runway-ledger-v1-to-v2"
        const val TEST_BACKUP_DATABASE = "runway-backup-v1-to-v2"
        const val TEST_V4_ROUTINE_DATABASE = "runway-ledger-v4-to-v5-routine"
        const val TEST_V2_DATABASE = "runway-ledger-v2-to-v3"
        const val TEST_V2_BACKUP_DATABASE = "runway-backup-v2-to-v3"
        const val TEST_V2_COLLISION_DATABASE = "runway-backup-v2-collision"
        const val TEST_V3_TIMED_CONSEQUENCE_DATABASE = "runway-ledger-v3-to-v4-timed"
        const val TEST_V3_INVALID_TIMED_CONSEQUENCE_DATABASE =
            "runway-ledger-v3-to-v4-invalid-timed"
        const val TEST_V3_TIMED_CONSEQUENCE_BACKUP_DATABASE =
            "runway-backup-v3-to-v4-timed"
    }
}
