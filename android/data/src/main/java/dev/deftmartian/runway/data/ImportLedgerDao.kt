package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
abstract class ImportLedgerDao {
    @Query("SELECT * FROM import_digests WHERE source = :source AND digest = :digest")
    abstract suspend fun digest(source: String, digest: String): ImportDigestEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDigest(digest: ImportDigestEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertImportedActivity(activity: ActivityEntity)

    @Upsert
    abstract suspend fun saveDigest(digest: ImportDigestEntity)

    @Query("UPDATE import_digests SET activityId = :activityId WHERE source = :source AND digest = :digest")
    abstract suspend fun linkDigest(source: String, digest: String, activityId: String)

    @Query("UPDATE import_digests SET activityId = NULL, tombstonedAtEpochMillis = :tombstonedAtEpochMillis WHERE source = :source AND digest = :digest")
    abstract suspend fun tombstoneDigest(source: String, digest: String, tombstonedAtEpochMillis: Long)

    @Upsert
    abstract suspend fun saveHealthConnectMapping(mapping: HealthConnectMappingEntity)

    @Upsert
    abstract suspend fun savePendingHealthConnectObservation(observation: HealthConnectPendingObservationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPendingHealthConnectRouteSamples(samples: List<HealthConnectPendingRouteSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPendingHealthConnectHeartRateSamples(samples: List<HealthConnectPendingHeartRateSampleEntity>)

    @Query("SELECT * FROM health_connect_pending_observations WHERE mappingId = :mappingId")
    abstract suspend fun pendingHealthConnectObservation(mappingId: String): HealthConnectPendingObservationEntity?

    @Query("SELECT * FROM health_connect_pending_route_samples WHERE mappingId = :mappingId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun pendingHealthConnectRouteSamples(mappingId: String, limit: Int): List<HealthConnectPendingRouteSampleEntity>

    @Query("SELECT * FROM health_connect_pending_heart_rate_samples WHERE mappingId = :mappingId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun pendingHealthConnectHeartRateSamples(mappingId: String, limit: Int): List<HealthConnectPendingHeartRateSampleEntity>

    @Query("DELETE FROM health_connect_pending_observations WHERE mappingId = :mappingId")
    abstract suspend fun clearPendingHealthConnectObservation(mappingId: String)

    @Query("DELETE FROM health_connect_pending_route_samples WHERE mappingId = :mappingId")
    protected abstract suspend fun clearPendingHealthConnectRouteSamples(mappingId: String)

    @Query("DELETE FROM health_connect_pending_route_samples")
    abstract suspend fun clearAllPendingHealthConnectRouteSamples()

    @Query("DELETE FROM health_connect_pending_heart_rate_samples WHERE mappingId = :mappingId")
    protected abstract suspend fun clearPendingHealthConnectHeartRateSamples(mappingId: String)

    @Query("DELETE FROM health_connect_pending_heart_rate_samples")
    abstract suspend fun clearAllPendingHealthConnectHeartRateSamples()

    @Query(
        """
        UPDATE health_connect_pending_observations
        SET averageHeartRateBpm = NULL,
            maxHeartRateBpm = NULL,
            heartRateSourceSampleCount = 0
        """,
    )
    abstract suspend fun redactAllPendingHealthConnectHeartRateData()

    @Transaction
    open suspend fun replacePendingHealthConnectRouteSamplesBounded(
        mappingId: String,
        samples: List<HealthConnectPendingRouteSampleEntity>,
        maximumSamples: Int,
    ) {
        require(maximumSamples > 1)
        clearPendingHealthConnectRouteSamples(mappingId)
        insertPendingHealthConnectRouteSamples(samples.boundEvenly(maximumSamples).mapIndexed { ordinal, sample -> sample.copy(sampleId = 0, mappingId = mappingId, ordinal = ordinal) })
    }

    @Transaction
    open suspend fun replacePendingHealthConnectHeartRateSamplesBounded(
        mappingId: String,
        samples: List<HealthConnectPendingHeartRateSampleEntity>,
        maximumSamples: Int,
    ) {
        require(maximumSamples > 0)
        clearPendingHealthConnectHeartRateSamples(mappingId)
        insertPendingHealthConnectHeartRateSamples(samples.boundEvenly(maximumSamples).mapIndexed { ordinal, sample -> sample.copy(sampleId = 0, mappingId = mappingId, ordinal = ordinal) })
    }

    @Query("SELECT * FROM health_connect_mappings WHERE provider = :provider AND externalRecordId = :externalRecordId")
    abstract suspend fun healthConnectMapping(provider: String, externalRecordId: String): HealthConnectMappingEntity?

    @Query(
        """
        SELECT * FROM health_connect_mappings
        WHERE correctionPending = 1
           OR deletePending = 1
           OR duplicateCandidateActivityId IS NOT NULL
        ORDER BY lastObservedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun pendingHealthConnectMappings(limit: Int): List<HealthConnectMappingEntity>

    @Query(
        """
        SELECT * FROM health_connect_mappings
        WHERE (correctionPending = 1 OR deletePending = 1 OR duplicateCandidateActivityId IS NOT NULL)
          AND (:beforeObservedAt IS NULL OR lastObservedAtEpochMillis < :beforeObservedAt
            OR (lastObservedAtEpochMillis = :beforeObservedAt AND mappingId < :beforeMappingId))
        ORDER BY lastObservedAtEpochMillis DESC, mappingId DESC LIMIT :limit
        """,
    )
    abstract suspend fun pendingHealthConnectMappingsPage(
        beforeObservedAt: Long?, beforeMappingId: String?, limit: Int,
    ): List<HealthConnectMappingEntity>

    @Query("SELECT * FROM health_connect_pending_observations WHERE mappingId IN (:mappingIds) LIMIT :limit")
    abstract suspend fun pendingHealthConnectObservations(
        mappingIds: List<String>,
        limit: Int,
    ): List<HealthConnectPendingObservationEntity>

    @Query("UPDATE health_connect_mappings SET activityId = NULL, lifecycleState = :tombstoneState, correctionPending = 0, deletePending = 0, duplicateCandidateActivityId = NULL, tombstonedAtEpochMillis = :deletedAtEpochMillis, deletedAtEpochMillis = :deletedAtEpochMillis WHERE activityId = :activityId")
    protected abstract suspend fun tombstoneHealthConnectMappingsForActivity(
        activityId: String,
        deletedAtEpochMillis: Long,
        tombstoneState: String = HEALTH_CONNECT_MAPPING_STATE_TOMBSTONED,
    )

    @Query(
        """
        DELETE FROM health_connect_pending_observations
        WHERE mappingId IN (
            SELECT mappingId FROM health_connect_mappings WHERE activityId = :activityId
        )
        """,
    )
    protected abstract suspend fun clearPendingHealthConnectObservationsForActivity(
        activityId: String,
    )

    @Query("DELETE FROM import_digests")
    abstract suspend fun clearDigests()

    @Transaction
    open suspend fun recordImportedActivity(
        activity: ActivityEntity,
        source: String,
        digest: String,
        firstSeenAtEpochMillis: Long,
    ): Boolean {
        require(activity.source == source) { "Import source must match the activity source." }
        val inserted = insertDigest(
            ImportDigestEntity(
                source = source,
                digest = digest,
                activityId = null,
                firstSeenAtEpochMillis = firstSeenAtEpochMillis,
                tombstonedAtEpochMillis = null,
            ),
        )
        if (inserted == -1L) return false
        insertImportedActivity(activity)
        linkDigest(source, digest, activity.activityId)
        return true
    }

    @Transaction
    open suspend fun deleteImportedActivityToTombstone(
        activityId: String,
        source: String,
        digest: String,
        tombstonedAtEpochMillis: Long,
    ) {
        tombstoneDigest(source, digest, tombstonedAtEpochMillis)
        clearPendingHealthConnectObservationsForActivity(activityId)
        tombstoneHealthConnectMappingsForActivity(activityId, tombstonedAtEpochMillis)
        deleteImportedActivity(activityId)
    }

    @Query("DELETE FROM activities WHERE activityId = :activityId")
    protected abstract suspend fun deleteImportedActivity(activityId: String)
}
