package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class LedgerMaintenanceDao {
    @Query("DELETE FROM goals")
    protected abstract suspend fun clearGoals()

    @Query("DELETE FROM activities")
    protected abstract suspend fun clearActivities()

    @Query("DELETE FROM import_digests")
    protected abstract suspend fun clearDigests()

    @Query("DELETE FROM health_connect_mappings")
    protected abstract suspend fun clearHealthConnectMappings()

    @Query("DELETE FROM profile_settings")
    protected abstract suspend fun clearProfile()

    @Query("DELETE FROM app_metadata")
    protected abstract suspend fun clearMetadata()

    @Query("DELETE FROM notification_deliveries")
    protected abstract suspend fun clearNotificationDeliveries()

    @Query("DELETE FROM notification_preferences")
    protected abstract suspend fun clearNotificationPreferences()

    @Query(
        """
        UPDATE import_digests
        SET activityId = NULL,
            tombstonedAtEpochMillis = COALESCE(tombstonedAtEpochMillis, :erasedAtEpochMillis)
        WHERE activityId IS NOT NULL
        """,
    )
    protected abstract suspend fun tombstoneImportedDigests(erasedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE health_connect_mappings
        SET activityId = NULL,
            lifecycleState = :tombstoneState,
            correctionPending = 0,
            deletePending = 0,
            duplicateCandidateActivityId = NULL,
            tombstonedAtEpochMillis = COALESCE(tombstonedAtEpochMillis, :erasedAtEpochMillis),
            deletedAtEpochMillis = COALESCE(deletedAtEpochMillis, :erasedAtEpochMillis)
        WHERE lifecycleState != :tombstoneState
           OR activityId IS NOT NULL
           OR correctionPending = 1
           OR deletePending = 1
           OR duplicateCandidateActivityId IS NOT NULL
        """,
    )
    protected abstract suspend fun tombstoneImportedHealthConnectMappings(
        erasedAtEpochMillis: Long,
        tombstoneState: String = HEALTH_CONNECT_MAPPING_STATE_TOMBSTONED,
    ): Int

    @Query("DELETE FROM health_connect_pending_observations")
    protected abstract suspend fun clearPendingHealthConnectImportData(): Int

    @Query(
        """
        DELETE FROM activities
        WHERE source != :manualSource
        """,
    )
    protected abstract suspend fun deleteImportedActivities(
        manualSource: String = "manual",
    ): Int

    /** Deletes runner-owned ledger records in one transaction, including retained import tombstones. */
    @Transaction
    open suspend fun clearAll() {
        clearGoals()
        clearActivities()
        clearDigests()
        clearHealthConnectMappings()
        clearProfile()
        clearMetadata()
        clearNotificationDeliveries()
        clearNotificationPreferences()
    }

    /**
     * Keeps manual activities and all plan/audit rows. The mapping update precedes the activity
     * delete so retained provider identities remain terminal even if the app is reconnected.
     */
    @Transaction
    open suspend fun clearImportedActivityData(
        erasedAtEpochMillis: Long,
    ): LocalImportedActivityEraseResult {
        require(erasedAtEpochMillis > 0) { "Imported-data erase time must be positive." }
        tombstoneImportedHealthConnectMappings(erasedAtEpochMillis)
        clearPendingHealthConnectImportData()
        val tombstones = tombstoneImportedDigests(erasedAtEpochMillis)
        val erased = deleteImportedActivities()
        return LocalImportedActivityEraseResult(
            activitiesErased = erased,
            retainedImportTombstones = tombstones,
        )
    }
}
