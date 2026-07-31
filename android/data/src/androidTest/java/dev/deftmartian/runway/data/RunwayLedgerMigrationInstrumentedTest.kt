package dev.deftmartian.runway.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
            assertEquals(3, upgraded.openHelper.writableDatabase.version)
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
            )
            .allowMainThreadQueries()
            .build()
        try {
            val profile = reopened.profileSettingsDao().get()
            assertNotNull(profile)
            assertEquals("private", profile?.heartRateDataMode)
            assertTrue(reopened.openHelper.writableDatabase.version == 3)
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
            assertEquals(3, restored.openHelper.writableDatabase.version)
        } finally {
            restored.close()
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
                close()
            }

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val upgraded = Room.databaseBuilder(
                context,
                RunwayLedgerDatabase::class.java,
                TEST_V2_DATABASE,
            )
                .addMigrations(RunwayLedgerMigrations.V2_TO_V3)
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
                assertEquals(3, upgraded.openHelper.writableDatabase.version)
            } finally {
                upgraded.close()
            }

            val reopened = Room.databaseBuilder(
                context,
                RunwayLedgerDatabase::class.java,
                TEST_V2_DATABASE,
            )
                .addMigrations(RunwayLedgerMigrations.V2_TO_V3)
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
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(
                "v2 backup",
                requireNotNull(restored.profileSettingsDao().get()).privateNotes,
            )
            assertEquals(3, restored.openHelper.writableDatabase.version)
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

    private companion object {
        const val TEST_DATABASE = "runway-ledger-v1-to-v2"
        const val TEST_BACKUP_DATABASE = "runway-backup-v1-to-v2"
        const val TEST_V2_DATABASE = "runway-ledger-v2-to-v3"
        const val TEST_V2_BACKUP_DATABASE = "runway-backup-v2-to-v3"
        const val TEST_V2_COLLISION_DATABASE = "runway-backup-v2-collision"
    }
}
