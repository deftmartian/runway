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
    data object ProfileNotConfigured : RouteDataModeUpdate
}

sealed interface HeartRateDataModeUpdate {
    data class Updated(val mode: HeartRateDataMode) : HeartRateDataModeUpdate
    data object ProfileNotConfigured : HeartRateDataModeUpdate
}

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
}
