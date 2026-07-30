package dev.deftmartian.runway.data.importing

import androidx.room.withTransaction
import dev.deftmartian.runway.data.ActivityEntity
import dev.deftmartian.runway.data.HeartRateSampleEntity
import dev.deftmartian.runway.data.ImportDigestEntity
import dev.deftmartian.runway.data.RouteSampleEntity
import dev.deftmartian.runway.data.RunwayLedgerDatabase
import dev.deftmartian.runway.data.isFutureLocalActivity
import java.io.InputStream
import java.security.MessageDigest
import java.time.ZoneId

/**
 * Commits one locally parsed GPX activity as a review item. The raw input is consumed by
 * [LocalGpxIntake] and is never retained by this repository.
 *
 * The import digest is a permanent privacy/deletion barrier: an existing active digest is a
 * duplicate and an existing tombstoned digest cannot be re-imported implicitly.
 */
class LocalGpxImportRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun import(input: InputStream): LocalGpxImportOutcome {
        val parsed = LocalGpxIntake.parse(input)
        return database.withTransaction {
            val now = nowEpochMillis()
            val profile = database.profileSettingsDao().get()
                ?: return@withTransaction LocalGpxImportOutcome.ConfigurationRequired
            val zone = runCatching { ZoneId.of(profile.timeZone) }.getOrNull()
                ?: return@withTransaction LocalGpxImportOutcome.ConfigurationRequired
            if (isFutureLocalActivity(parsed.startedAtEpochMillis, now, zone)) {
                return@withTransaction LocalGpxImportOutcome.FutureActivity
            }
            val importDao = database.importLedgerDao()
            when (val existing = importDao.digest(GPX_SOURCE, parsed.dedupeMaterial)) {
                null -> insertParsed(
                    parsed = parsed,
                    now = now,
                    retainRoute = profile.routeDataMode == ROUTE_MODE_PRIVATE,
                    retainHeartRate = profile.heartRateDataMode == HEART_RATE_MODE_PRIVATE,
                    importDao = importDao,
                )
                else -> existing.asOutcome()
            }
        }
    }

    private suspend fun insertParsed(
        parsed: LocalGpxActivity,
        now: Long,
        retainRoute: Boolean,
        retainHeartRate: Boolean,
        importDao: dev.deftmartian.runway.data.ImportLedgerDao,
    ): LocalGpxImportOutcome {
        val activity = localGpxActivityEntity(parsed, now, retainRoute, retainHeartRate)
        // The insert is the atomic claim. A concurrent successful transaction leaves its digest
        // intact, so re-read rather than attempting a second activity write.
        if (!importDao.recordImportedActivity(activity, GPX_SOURCE, parsed.dedupeMaterial, now)) {
            return requireNotNull(importDao.digest(GPX_SOURCE, parsed.dedupeMaterial)).asOutcome()
        }

        val activityDao = database.activityLedgerDao()
        if (retainRoute) {
            activityDao.replaceRouteSamplesBounded(
                activity.activityId,
                localGpxRouteSamples(activity.activityId, parsed),
                LocalGpxIntake.MAX_RETAINED_POINTS,
            )
        }
        if (retainHeartRate) {
            activityDao.replaceHeartRateSamplesBounded(
                activity.activityId,
                localGpxHeartRateSamples(activity.activityId, parsed),
                LocalGpxIntake.MAX_RETAINED_POINTS,
            )
        }
        return LocalGpxImportOutcome.Imported(activity.activityId)
    }

    private fun ImportDigestEntity.asOutcome(): LocalGpxImportOutcome =
        if (tombstonedAtEpochMillis != null) {
            LocalGpxImportOutcome.Tombstoned
        } else {
            LocalGpxImportOutcome.Duplicate(activityId)
        }

    private companion object {
        const val GPX_SOURCE = "gpx"
        const val ROUTE_MODE_PRIVATE = "private"
        const val HEART_RATE_MODE_PRIVATE = "private"
    }
}

sealed interface LocalGpxImportOutcome {
    data class Imported(val activityId: String) : LocalGpxImportOutcome
    data class Duplicate(val activityId: String?) : LocalGpxImportOutcome
    data object Tombstoned : LocalGpxImportOutcome
    data object ConfigurationRequired : LocalGpxImportOutcome
    data object FutureActivity : LocalGpxImportOutcome
}

internal fun localGpxActivityEntity(
    parsed: LocalGpxActivity,
    nowEpochMillis: Long,
    retainRoute: Boolean,
    retainHeartRate: Boolean = false,
): ActivityEntity =
    ActivityEntity(
        activityId = localGpxActivityId(parsed.dedupeMaterial),
        source = "gpx",
        sourceRecordId = parsed.dedupeMaterial,
        reviewState = "review",
        occurredAtEpochMillis = parsed.startedAtEpochMillis,
        durationSeconds = parsed.durationSeconds,
        distanceMeters = parsed.distanceMeters,
        averageHeartRateBpm = parsed.averageHeartRate.takeIf { retainHeartRate },
        averageCadenceSpm = parsed.averageCadence,
        linkedWorkoutId = null,
        acceptedAtEpochMillis = null,
        createdAtEpochMillis = nowEpochMillis,
        updatedAtEpochMillis = nowEpochMillis,
        maxHeartRateBpm = parsed.maxHeartRate.takeIf { retainHeartRate },
        // Average speed is not maximum speed. Leave this unknown until the parser has a truthful,
        // full-track maximum rather than relabeling another measurement.
        maxSpeedMetersPerSecond = null,
        elevationGainMeters = parsed.elevationGainMeters.toDouble().takeIf { it > 0 },
        routePointCount = if (retainRoute) parsed.route.size else 0,
        heartRatePointCount = if (retainHeartRate) parsed.heartRate.size else 0,
        heartRateSourceSampleCount = if (retainHeartRate) parsed.heartRate.size else 0,
        routeTraceRetained = retainRoute && parsed.route.isNotEmpty(),
        routeStartEndRedacted = !retainRoute && parsed.route.isNotEmpty(),
        heartRateSeriesRetained = retainHeartRate && parsed.heartRate.isNotEmpty(),
    )

internal fun localGpxRouteSamples(activityId: String, parsed: LocalGpxActivity): List<RouteSampleEntity> =
    parsed.route.mapIndexed { ordinal, point ->
        RouteSampleEntity(
            activityId = activityId,
            ordinal = ordinal,
            latitudeE6 = point.latitudeE6,
            longitudeE6 = point.longitudeE6,
            elapsedSeconds = point.elapsedSeconds,
            elevationMeters = null,
            segmentOrdinal = point.segmentIndex,
            speedMetersPerSecond = point.speedMetersPerSecond,
        )
    }

internal fun localGpxHeartRateSamples(
    activityId: String,
    parsed: LocalGpxActivity,
): List<HeartRateSampleEntity> = parsed.heartRate.mapIndexed { ordinal, point ->
    HeartRateSampleEntity(
        activityId = activityId,
        ordinal = ordinal,
        elapsedSeconds = point.elapsedSeconds,
        beatsPerMinute = point.bpm,
    )
}

/** Stable UUID-shaped ID keeps retries from inventing another local activity identity. */
internal fun localGpxActivityId(dedupeMaterial: String): String {
    require(dedupeMaterial.matches(Regex("[0-9a-f]{64}")))
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("runway-local-gpx-activity-v1\u0000$dedupeMaterial".toByteArray(Charsets.UTF_8))
        .copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
}
