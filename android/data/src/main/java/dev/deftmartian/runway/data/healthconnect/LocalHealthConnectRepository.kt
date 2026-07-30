package dev.deftmartian.runway.data.healthconnect

import androidx.room.withTransaction
import dev.deftmartian.runway.data.ACTIVITY_REVIEW_STATE_ACCEPTED
import dev.deftmartian.runway.data.ActivityEntity
import dev.deftmartian.runway.data.HealthConnectMappingEntity
import dev.deftmartian.runway.data.HealthConnectPendingHeartRateSampleEntity
import dev.deftmartian.runway.data.HealthConnectPendingObservationEntity
import dev.deftmartian.runway.data.HealthConnectPendingRouteSampleEntity
import dev.deftmartian.runway.data.HeartRateSampleEntity
import dev.deftmartian.runway.data.ImportLedgerDao
import dev.deftmartian.runway.data.ProfileSettingsEntity
import dev.deftmartian.runway.data.RouteSampleEntity
import dev.deftmartian.runway.data.RunwayLedgerDatabase
import dev.deftmartian.runway.data.isFutureLocalActivity
import java.time.ZoneId

/**
 * Local, transactional persistence boundary for Health Connect observations.
 *
 * Acquisition belongs outside this class. This class deliberately receives a normalized running
 * observation and writes either a review item or an explicit pending correction/delete. It never
 * turns a provider update into accepted training evidence by itself.
 */
class LocalHealthConnectRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile(
        provider: String,
        observation: HealthConnectObservation,
        duplicateCandidateActivityId: String? = null,
    ): LocalHealthConnectPersistenceResult = database.withTransaction {
        require(provider.isNotBlank()) { "Health Connect provider must not be blank." }
        if (observation is HealthConnectObservation.RunningUpsert && observation.provider != provider) {
            return@withTransaction LocalHealthConnectPersistenceResult.InvalidProvider
        }

        val profile = database.profileSettingsDao().get()
            ?: return@withTransaction LocalHealthConnectPersistenceResult.ProfileNotConfigured
        val now = nowEpochMillis()
        val zone = runCatching { ZoneId.of(profile.timeZone) }.getOrNull()
            ?: return@withTransaction LocalHealthConnectPersistenceResult.ProfileNotConfigured
        if (
            observation is HealthConnectObservation.RunningUpsert &&
            isFutureLocalActivity(observation.startedAtEpochMillis, now, zone)
        ) {
            return@withTransaction LocalHealthConnectPersistenceResult.FutureActivity
        }
        val importDao = database.importLedgerDao()
        val storedMapping = importDao.healthConnectMapping(provider, observation.recordId)
        val candidate = duplicateCandidateActivityId ?: storedMapping?.duplicateCandidateActivityId
        if (candidate != null && database.activityLedgerDao().activity(candidate) == null) {
            return@withTransaction LocalHealthConnectPersistenceResult.DuplicateCandidateMissing(candidate)
        }

        val state = storedMapping.toRecordState(database, candidate)
        val outcome = LocalHealthConnectReconciler.reduce(observation, state)
        persist(
            importDao = importDao,
            existingMapping = storedMapping,
            profile = profile,
            provider = provider,
            observation = observation,
            outcome = outcome,
            now = now,
        )
        LocalHealthConnectPersistenceResult.Applied(outcome)
    }

    private suspend fun persist(
        importDao: ImportLedgerDao,
        existingMapping: HealthConnectMappingEntity?,
        profile: ProfileSettingsEntity,
        provider: String,
        observation: HealthConnectObservation,
        outcome: LocalHealthConnectOutcome,
        now: Long,
    ) {
        when (outcome) {
            is LocalHealthConnectOutcome.Unchanged -> Unit
            is LocalHealthConnectOutcome.NewReview -> persistReview(
                importDao, existingMapping, profile, provider, observation.requireUpsert(), outcome.mapping, outcome.activity, now,
            )
            is LocalHealthConnectOutcome.DuplicateCandidate -> persistReview(
                importDao, existingMapping, profile, provider, observation.requireUpsert(), outcome.mapping, outcome.activity, now,
            )
            is LocalHealthConnectOutcome.ReviewUpdate -> persistReview(
                importDao, existingMapping, profile, provider, observation.requireUpsert(), outcome.mapping, outcome.activity, now,
            )
            is LocalHealthConnectOutcome.PendingCorrection -> persistCorrection(
                importDao, existingMapping, profile, provider, observation.requireUpsert(), outcome.mapping, outcome.proposed, now,
            )
            is LocalHealthConnectOutcome.PendingDelete -> persistPendingDelete(
                importDao, requireNotNull(existingMapping), outcome.mapping, observation, now,
            )
            is LocalHealthConnectOutcome.DeleteReview -> persistReviewDeletion(
                importDao, requireNotNull(existingMapping), outcome.mapping, observation, now,
            )
        }
    }

    private suspend fun persistReview(
        importDao: ImportLedgerDao,
        existing: HealthConnectMappingEntity?,
        profile: ProfileSettingsEntity,
        provider: String,
        observation: HealthConnectObservation.RunningUpsert,
        mapping: LocalHealthConnectMapping,
        activity: LocalHealthConnectActivity,
        now: Long,
    ) {
        val retainRoute = profile.routeDataMode == ROUTE_MODE_PRIVATE
        val route = if (retainRoute) activity.route.boundEvenly(MAX_RETAINED_ROUTE_SAMPLES) else emptyList()
        val heartRate = activity.heartRate.boundEvenly(MAX_RETAINED_HEART_RATE_SAMPLES)
        val existingActivity = database.activityLedgerDao().activity(activity.activityId)
        val entity = activity.toReviewEntity(
            now = now,
            route = route,
            heartRate = heartRate,
            retainRoute = retainRoute,
            sourceRecordId = observation.recordId,
            createdAtEpochMillis = existingActivity?.createdAtEpochMillis ?: now,
        )
        database.activityLedgerDao().saveActivity(entity)
        database.activityLedgerDao().replaceRouteSamplesBounded(
            entity.activityId,
            route.toRouteEntities(entity.activityId),
            MAX_RETAINED_ROUTE_SAMPLES,
        )
        database.activityLedgerDao().replaceHeartRateSamplesBounded(
            entity.activityId,
            heartRate.toHeartRateEntities(entity.activityId, activity.heartRateSourceSampleCount),
            MAX_RETAINED_HEART_RATE_SAMPLES,
        )
        importDao.clearPendingHealthConnectObservation(mapping.mappingId)
        importDao.saveHealthConnectMapping(
            mapping.toEntity(existing, observation, provider, now),
        )
    }

    private suspend fun persistCorrection(
        importDao: ImportLedgerDao,
        existing: HealthConnectMappingEntity?,
        profile: ProfileSettingsEntity,
        provider: String,
        observation: HealthConnectObservation.RunningUpsert,
        mapping: LocalHealthConnectMapping,
        proposed: LocalHealthConnectActivity,
        now: Long,
    ) {
        val retainRoute = profile.routeDataMode == ROUTE_MODE_PRIVATE
        val route = if (retainRoute) proposed.route.boundEvenly(MAX_RETAINED_ROUTE_SAMPLES) else emptyList()
        val heartRate = proposed.heartRate.boundEvenly(MAX_RETAINED_HEART_RATE_SAMPLES)
        importDao.saveHealthConnectMapping(mapping.toEntity(existing, observation, provider, now))
        importDao.savePendingHealthConnectObservation(
            HealthConnectPendingObservationEntity(
                mappingId = mapping.mappingId,
                observedAtEpochMillis = now,
                occurredAtEpochMillis = proposed.startedAtEpochMillis,
                durationSeconds = proposed.durationSeconds,
                distanceMeters = proposed.distanceMeters,
                averageHeartRateBpm = proposed.averageHeartRateBpm,
                maxHeartRateBpm = proposed.maxHeartRateBpm,
                averageCadenceSpm = proposed.averageCadenceSpm,
                elevationGainMeters = proposed.elevationGainMeters,
                heartRateSourceSampleCount = proposed.heartRateSourceSampleCount,
                routeSourcePointCount = proposed.routeSourcePointCount,
                fingerprint = mapping.fingerprint,
                originKey = observation.originKey,
                originLabel = observation.originLabel,
                runningType = observation.runningType.storageValue,
                duplicateCandidateActivityId = mapping.duplicateCandidateActivityId,
            ),
        )
        // The parent observation is replaced before children. Both replacement calls stay in the
        // outer database transaction, so a crash cannot expose a partial pending correction.
        importDao.replacePendingHealthConnectRouteSamplesBounded(
            mapping.mappingId,
            route.toPendingRouteEntities(mapping.mappingId),
            MAX_RETAINED_ROUTE_SAMPLES,
        )
        importDao.replacePendingHealthConnectHeartRateSamplesBounded(
            mapping.mappingId,
            heartRate.toPendingHeartRateEntities(mapping.mappingId),
            MAX_RETAINED_HEART_RATE_SAMPLES,
        )
    }

    private suspend fun persistPendingDelete(
        importDao: ImportLedgerDao,
        existing: HealthConnectMappingEntity,
        mapping: LocalHealthConnectMapping,
        observation: HealthConnectObservation,
        now: Long,
    ) {
        // A source deletion supersedes any unreviewed correction. Keeping both would let a later
        // resolution apply coordinates or metrics for a record the provider has already deleted.
        importDao.clearPendingHealthConnectObservation(mapping.mappingId)
        importDao.saveHealthConnectMapping(
            mapping.toDeletionEntity(existing, observation, now, pending = true),
        )
    }

    private suspend fun persistReviewDeletion(
        importDao: ImportLedgerDao,
        existing: HealthConnectMappingEntity,
        mapping: LocalHealthConnectMapping,
        observation: HealthConnectObservation,
        now: Long,
    ) {
        importDao.saveHealthConnectMapping(
            mapping.toDeletionEntity(existing, observation, now, pending = false),
        )
        importDao.clearPendingHealthConnectObservation(mapping.mappingId)
        // This is intentionally the existing guarded delete path: it tombstones the provider
        // mapping in the same Room transaction before removing a review-only activity. The digest
        // is only a no-op for Health Connect today; no synthetic digest is retained.
        importDao.deleteImportedActivityToTombstone(
            activityId = requireNotNull(existing.activityId),
            source = HEALTH_CONNECT_SOURCE,
            digest = existing.externalRecordId,
            tombstonedAtEpochMillis = observation.deletedAtEpochMillis(),
        )
    }

    private companion object {
        const val HEALTH_CONNECT_SOURCE = "health_connect"
        const val ROUTE_MODE_PRIVATE = "private"
        const val MAX_RETAINED_ROUTE_SAMPLES = 600
        const val MAX_RETAINED_HEART_RATE_SAMPLES = 600
    }
}

sealed interface LocalHealthConnectPersistenceResult {
    data class Applied(val outcome: LocalHealthConnectOutcome) : LocalHealthConnectPersistenceResult
    data object ProfileNotConfigured : LocalHealthConnectPersistenceResult
    data object InvalidProvider : LocalHealthConnectPersistenceResult
    data object FutureActivity : LocalHealthConnectPersistenceResult
    data class DuplicateCandidateMissing(val activityId: String) : LocalHealthConnectPersistenceResult
}

private suspend fun HealthConnectMappingEntity?.toRecordState(
    database: RunwayLedgerDatabase,
    duplicateCandidateActivityId: String?,
): LocalHealthConnectRecordState {
    val mapping = this ?: return LocalHealthConnectRecordState(null, null, duplicateCandidateActivityId = duplicateCandidateActivityId)
    require(!(mapping.correctionPending && mapping.deletePending)) {
        "A Health Connect mapping cannot wait for correction and deletion at once."
    }
    val action = when {
        mapping.correctionPending -> LocalHealthConnectPendingAction.Correction
        mapping.deletePending -> LocalHealthConnectPendingAction.SourceDelete
        else -> LocalHealthConnectPendingAction.None
    }
    val activity = mapping.activityId?.let { activityId ->
        database.activityLedgerDao().activity(activityId)?.let {
            LocalHealthConnectMappedActivity(
                activityId = it.activityId,
                reviewState = when (it.reviewState) {
                    ACTIVITY_REVIEW_STATE_ACCEPTED -> LocalActivityReviewState.Accepted
                    "review" -> LocalActivityReviewState.Review
                    else -> error("Unsupported local activity review state for Health Connect mapping.")
                },
            )
        }
    }
    return LocalHealthConnectRecordState(
        mapping = LocalHealthConnectMapping(
            mappingId = mapping.mappingId,
            provider = mapping.provider,
            externalRecordId = mapping.externalRecordId,
            fingerprint = mapping.fingerprint.orEmpty(),
            activityId = mapping.activityId,
            pendingAction = action,
            duplicateCandidateActivityId = mapping.duplicateCandidateActivityId,
            deletedAtEpochMillis = mapping.deletedAtEpochMillis,
        ),
        activity = activity,
        tombstoned = mapping.lifecycleState == "tombstoned" || mapping.tombstonedAtEpochMillis != null,
        duplicateCandidateActivityId = duplicateCandidateActivityId,
    )
}

private fun LocalHealthConnectMapping.toEntity(
    existing: HealthConnectMappingEntity?,
    observation: HealthConnectObservation.RunningUpsert,
    provider: String,
    now: Long,
): HealthConnectMappingEntity = HealthConnectMappingEntity(
    mappingId = mappingId,
    provider = provider,
    externalRecordId = externalRecordId,
    activityId = activityId,
    importedAtEpochMillis = existing?.importedAtEpochMillis ?: now,
    lastObservedAtEpochMillis = now,
    lifecycleState = "active",
    correctionPending = pendingAction == LocalHealthConnectPendingAction.Correction,
    deletePending = pendingAction == LocalHealthConnectPendingAction.SourceDelete,
    tombstonedAtEpochMillis = null,
    deletedAtEpochMillis = deletedAtEpochMillis,
    lastCorrectionAtEpochMillis = if (pendingAction == LocalHealthConnectPendingAction.Correction) now else existing?.lastCorrectionAtEpochMillis,
    fingerprint = fingerprint,
    originKey = observation.originKey,
    originLabel = observation.originLabel,
    runningType = observation.runningType.storageValue,
    duplicateCandidateActivityId = duplicateCandidateActivityId,
)

private fun LocalHealthConnectMapping.toDeletionEntity(
    existing: HealthConnectMappingEntity,
    observation: HealthConnectObservation,
    now: Long,
    pending: Boolean,
): HealthConnectMappingEntity = existing.copy(
    activityId = activityId,
    lastObservedAtEpochMillis = now,
    lifecycleState = "active",
    correctionPending = false,
    deletePending = pending,
    deletedAtEpochMillis = observation.deletedAtEpochMillis(),
    fingerprint = fingerprint.ifBlank { existing.fingerprint },
)

private fun LocalHealthConnectActivity.toReviewEntity(
    now: Long,
    route: List<LocalHealthConnectRoutePoint>,
    heartRate: List<LocalHealthConnectHeartRatePoint>,
    retainRoute: Boolean,
    sourceRecordId: String,
    createdAtEpochMillis: Long,
): ActivityEntity = ActivityEntity(
    activityId = activityId,
    source = "health_connect",
    sourceRecordId = sourceRecordId,
    reviewState = "review",
    occurredAtEpochMillis = startedAtEpochMillis,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    averageHeartRateBpm = averageHeartRateBpm,
    averageCadenceSpm = averageCadenceSpm,
    linkedWorkoutId = null,
    acceptedAtEpochMillis = null,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = now,
    maxHeartRateBpm = maxHeartRateBpm,
    // A provider average is not a maximum. Never promote it to max speed.
    maxSpeedMetersPerSecond = null,
    elevationGainMeters = elevationGainMeters,
    routePointCount = route.size,
    heartRatePointCount = heartRate.size,
    heartRateSourceSampleCount = heartRateSourceSampleCount,
    routeTraceRetained = retainRoute && route.isNotEmpty(),
    routeStartEndRedacted = !retainRoute && routeSourcePointCount > 0,
    heartRateSeriesRetained = heartRate.isNotEmpty(),
)

private fun List<LocalHealthConnectRoutePoint>.toRouteEntities(activityId: String): List<RouteSampleEntity> =
    mapIndexed { ordinal, point ->
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

private fun List<LocalHealthConnectHeartRatePoint>.toHeartRateEntities(
    activityId: String,
    sourceSampleCount: Int,
): List<HeartRateSampleEntity> = mapIndexed { ordinal, point ->
    HeartRateSampleEntity(
        activityId = activityId,
        ordinal = ordinal,
        elapsedSeconds = point.elapsedSeconds,
        beatsPerMinute = point.bpm,
        sourceSampleCount = sourceSampleCount,
    )
}

private fun List<LocalHealthConnectRoutePoint>.toPendingRouteEntities(
    mappingId: String,
): List<HealthConnectPendingRouteSampleEntity> = mapIndexed { ordinal, point ->
    HealthConnectPendingRouteSampleEntity(
        mappingId = mappingId,
        ordinal = ordinal,
        latitudeE6 = point.latitudeE6,
        longitudeE6 = point.longitudeE6,
        elapsedSeconds = point.elapsedSeconds,
        elevationMeters = null,
        segmentOrdinal = point.segmentIndex,
        speedMetersPerSecond = point.speedMetersPerSecond,
    )
}

private fun List<LocalHealthConnectHeartRatePoint>.toPendingHeartRateEntities(
    mappingId: String,
): List<HealthConnectPendingHeartRateSampleEntity> = mapIndexed { ordinal, point ->
    HealthConnectPendingHeartRateSampleEntity(
        mappingId = mappingId,
        ordinal = ordinal,
        elapsedSeconds = point.elapsedSeconds,
        beatsPerMinute = point.bpm,
    )
}

private fun <T> List<T>.boundEvenly(limit: Int): List<T> {
    require(limit > 0)
    if (size <= limit) return this
    if (limit == 1) return listOf(first())
    val last = lastIndex
    return List(limit) { index -> this[(index * last) / (limit - 1)] }
}

private fun HealthConnectObservation.requireUpsert(): HealthConnectObservation.RunningUpsert =
    this as? HealthConnectObservation.RunningUpsert ?: error("This persistence outcome requires a running observation.")

private fun HealthConnectObservation.deletedAtEpochMillis(): Long = when (this) {
    is HealthConnectObservation.Deleted -> observedAtEpochMillis
    is HealthConnectObservation.RunningUpsert -> error("Only a deleted Health Connect observation has deletion time.")
}

private val LocalHealthConnectRunningType.storageValue: String
    get() = when (this) {
        LocalHealthConnectRunningType.Running -> "running"
        LocalHealthConnectRunningType.TreadmillRunning -> "treadmill_running"
    }
