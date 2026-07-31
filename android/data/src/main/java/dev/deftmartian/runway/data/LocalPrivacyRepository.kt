package dev.deftmartian.runway.data

import androidx.room.withTransaction

enum class RouteDataMode(val storageValue: String) {
    Private("private"),
    Discard("discard"),
}

/** Imported heart-rate evidence is optional and remains local when retained. */
enum class HeartRateDataMode(val storageValue: String) {
    Private("private"),
    Discard("discard"),
}

sealed interface RouteDataModeUpdate {
    data class Updated(val mode: RouteDataMode) : RouteDataModeUpdate
    data class Unchanged(val mode: RouteDataMode) : RouteDataModeUpdate
    data object ProfileNotConfigured : RouteDataModeUpdate
}

sealed interface HeartRateDataModeUpdate {
    data class Updated(val mode: HeartRateDataMode) : HeartRateDataModeUpdate
    data class Unchanged(val mode: HeartRateDataMode) : HeartRateDataModeUpdate
    data object ProfileNotConfigured : HeartRateDataModeUpdate
}

data class RetentionRepairNotice(
    val routeModeRestored: Boolean,
    val heartRateModeRestored: Boolean,
)

/**
 * Owns the privacy boundary for route retention.
 *
 * Switching to [RouteDataMode.Discard] is intentionally destructive: retained activity routes
 * and route samples waiting in a Health Connect correction are erased in the same transaction as
 * the setting change. Switching back to private affects only observations imported afterwards.
 */
class LocalPrivacyRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun updateRouteDataMode(mode: RouteDataMode): RouteDataModeUpdate =
        database.withTransaction {
            val profileDao = database.profileSettingsDao()
            val profile = profileDao.get() ?: return@withTransaction RouteDataModeUpdate.ProfileNotConfigured

            if (profile.routeDataMode == mode.storageValue) {
                return@withTransaction RouteDataModeUpdate.Unchanged(mode)
            }
            if (mode == RouteDataMode.Discard) {
                database.activityLedgerDao().markAllRouteTracesDiscarded()
                database.activityLedgerDao().clearAllRouteSamples()
                database.importLedgerDao().clearAllPendingHealthConnectRouteSamples()
            }
            profileDao.save(
                profile.copy(
                    routeDataMode = mode.storageValue,
                    updatedAtEpochMillis = nowEpochMillis(),
                ),
            )
            RouteDataModeUpdate.Updated(mode)
        }

    /**
     * Switching to discard irreversibly removes activity-level imported heart-rate aggregates,
     * counts, and detailed samples, including values held for a pending Health Connect correction.
     * Zone and profile settings remain intact.
     */
    suspend fun updateHeartRateDataMode(mode: HeartRateDataMode): HeartRateDataModeUpdate =
        database.withTransaction {
            val profileDao = database.profileSettingsDao()
            val profile = profileDao.get() ?: return@withTransaction HeartRateDataModeUpdate.ProfileNotConfigured

            if (profile.heartRateDataMode == mode.storageValue) {
                return@withTransaction HeartRateDataModeUpdate.Unchanged(mode)
            }
            if (mode == HeartRateDataMode.Discard) {
                database.activityLedgerDao().markAllHeartRateDataDiscarded()
                database.activityLedgerDao().clearAllHeartRateSamples()
                database.importLedgerDao().clearAllPendingHealthConnectHeartRateSamples()
                database.importLedgerDao().redactAllPendingHealthConnectHeartRateData()
            }
            profileDao.save(
                profile.copy(
                    heartRateDataMode = mode.storageValue,
                    updatedAtEpochMillis = nowEpochMillis(),
                ),
            )
            HeartRateDataModeUpdate.Updated(mode)
        }

    suspend fun pendingRetentionRepairNotice(): RetentionRepairNotice? {
        val metadata = database.appMetadataDao()
        val route = metadata.value(RunwayLedgerMigrations.ROUTE_RETENTION_REPAIR_KEY) != null
        val heartRate =
            metadata.value(RunwayLedgerMigrations.HEART_RATE_RETENTION_REPAIR_KEY) != null
        return if (route || heartRate) RetentionRepairNotice(route, heartRate) else null
    }

    suspend fun acknowledgeRetentionRepairNotice() {
        database.withTransaction {
            database.appMetadataDao().delete(RunwayLedgerMigrations.ROUTE_RETENTION_REPAIR_KEY)
            database.appMetadataDao().delete(
                RunwayLedgerMigrations.HEART_RATE_RETENTION_REPAIR_KEY,
            )
        }
    }
}
