package dev.deftmartian.runway.data

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

/** Exercises the exported v1 database schema, then verifies a second open is stable at v2. */
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
            .addMigrations(RunwayLedgerMigrations.V1_TO_V2)
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
            assertEquals(2, upgraded.openHelper.writableDatabase.version)
        } finally {
            upgraded.close()
        }

        val reopened = Room.databaseBuilder(
            context,
            RunwayLedgerDatabase::class.java,
            TEST_DATABASE,
        )
            .addMigrations(RunwayLedgerMigrations.V1_TO_V2)
            .allowMainThreadQueries()
            .build()
        try {
            val profile = reopened.profileSettingsDao().get()
            assertNotNull(profile)
            assertEquals("private", profile?.heartRateDataMode)
            assertTrue(reopened.openHelper.writableDatabase.version == 2)
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
            .addMigrations(RunwayLedgerMigrations.V1_TO_V2)
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
            assertEquals(2, restored.openHelper.writableDatabase.version)
        } finally {
            restored.close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "runway-ledger-v1-to-v2"
        const val TEST_BACKUP_DATABASE = "runway-backup-v1-to-v2"
    }
}
