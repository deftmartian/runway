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
    /**
     * Applies the exact correction currently waiting for this provider record.
     *
     * The mapping, pending observation, samples, and target activity are reloaded inside the same
     * transaction. A stale UI action therefore cannot apply an older correction after a newer
     * provider observation, a source deletion, or an activity-state change.
     */
    suspend fun acceptPendingCorrection(
        provider: String,
        recordId: String,
    ): LocalHealthConnectPendingResolutionResult = database.withTransaction {
        resolvePendingCorrection(provider, recordId, accept = true)
    }

    /** Records that the runner intentionally keeps the accepted local activity unchanged. */
    suspend fun rejectPendingCorrection(
        provider: String,
        recordId: String,
    ): LocalHealthConnectPendingResolutionResult = database.withTransaction {
        resolvePendingCorrection(provider, recordId, accept = false)
    }

    /** Removes an accepted activity after its provider record was deleted and tombstones the mapping. */
    suspend fun deleteFromRunwayAfterProviderDeletion(
        provider: String,
        recordId: String,
    ): LocalHealthConnectPendingResolutionResult = database.withTransaction {
        val resolved = pendingProviderDeletion(provider, recordId)
            ?: return@withTransaction pendingResolutionFailure(provider, recordId)
        when (resolved) {
            is PendingProviderDeletion.Failure -> resolved.result
            is PendingProviderDeletion.Ready -> {
                database.importLedgerDao().deleteImportedActivityToTombstone(
                    activityId = resolved.activity.activityId,
                    source = HEALTH_CONNECT_SOURCE,
                    digest = resolved.mapping.externalRecordId,
                    tombstonedAtEpochMillis = resolved.mapping.deletedAtEpochMillis ?: nowEpochMillis(),
                )
                LocalHealthConnectPendingResolutionResult.ProviderDeletionDeleted(
                    resolved.activity.activityId,
                )
            }
        }
    }

    /**
     * Keeps the accepted local activity after its provider record was deleted.
     *
     * The mapping becomes terminal while retaining the local activity link. This prevents a stale
     * provider replay from turning a deliberately retained local record back into a correction.
     */
    suspend fun retainLocallyAfterProviderDeletion(
        provider: String,
        recordId: String,
    ): LocalHealthConnectPendingResolutionResult = database.withTransaction {
        val resolved = pendingProviderDeletion(provider, recordId)
            ?: return@withTransaction pendingResolutionFailure(provider, recordId)
        when (resolved) {
            is PendingProviderDeletion.Failure -> resolved.result
            is PendingProviderDeletion.Ready -> {
                val deletedAt = resolved.mapping.deletedAtEpochMillis ?: nowEpochMillis()
                database.importLedgerDao().clearPendingHealthConnectObservation(resolved.mapping.mappingId)
                database.importLedgerDao().saveHealthConnectMapping(
                    resolved.mapping.copy(
                        lifecycleState = HEALTH_CONNECT_MAPPING_STATE_DETACHED,
                        correctionPending = false,
                        deletePending = false,
                        tombstonedAtEpochMillis = deletedAt,
                        deletedAtEpochMillis = deletedAt,
                        lastObservedAtEpochMillis = nowEpochMillis(),
                    ),
                )
                LocalHealthConnectPendingResolutionResult.ProviderDeletionRetained(
                    resolved.activity.activityId,
                )
            }
        }
    }

    suspend fun reconcile(
        provider: String,
        observation: HealthConnectObservation,
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
        val activityDao = database.activityLedgerDao()
        val storedMapping = importDao.healthConnectMapping(provider, observation.recordId)
        // A candidate may have been deleted through its own local workflow. It is not a reason to
        // block a later provider reconciliation; clear only that stale relationship.
        val canonicalMapping = storedMapping?.let { mapping ->
            if (
                mapping.duplicateCandidateActivityId != null &&
                activityDao.activity(mapping.duplicateCandidateActivityId) == null
            ) {
                val cleared = mapping.copy(duplicateCandidateActivityId = null)
                importDao.saveHealthConnectMapping(cleared)
                cleared
            } else {
                mapping
            }
        }
        val candidate = when (observation) {
            // Duplicate detection is deliberately a new-record decision. A subsequent provider
            // update reconciles the already-owned Health Connect review rather than discovering a
            // second relationship after the fact.
            is HealthConnectObservation.RunningUpsert -> canonicalMapping?.duplicateCandidateActivityId
                ?: if (canonicalMapping == null) activityDao.findConservativeDuplicateCandidate(observation) else null
            is HealthConnectObservation.Deleted -> canonicalMapping?.duplicateCandidateActivityId
        }

        val state = canonicalMapping.toRecordState(database, candidate)
        val routeChanged = observation is HealthConnectObservation.RunningUpsert &&
            observation.routeObserved &&
            profile.routeDataMode == ROUTE_MODE_PRIVATE &&
            state.activity != null &&
            canonicalMapping?.activityId?.let { activityId ->
                val activity = activityDao.activity(activityId)
                if (activity?.routeStartEndRedacted == true) {
                    false
                } else {
                    val retained = observation.route.boundEvenly(MAX_RETAINED_ROUTE_SAMPLES)
                    val existing = activityDao.routeSamples(activityId, MAX_RETAINED_ROUTE_SAMPLES)
                        .map(RouteSampleEntity::toLocalHealthConnectRoutePoint)
                    retained != existing
                }
            } == true
        val outcome = LocalHealthConnectReconciler.reduce(
            observation = observation,
            state = state,
            routeChanged = routeChanged,
        )
        persist(
            importDao = importDao,
            existingMapping = canonicalMapping,
            profile = profile,
            provider = provider,
            observation = observation,
            outcome = outcome,
            now = now,
        )
        LocalHealthConnectPersistenceResult.Applied(outcome)
    }

    /** Resolves a duplicate candidate without ever merging or mutating the existing local run. */
    suspend fun resolveDuplicateCandidate(
        provider: String,
        recordId: String,
        decision: LocalHealthConnectDuplicateDecision,
    ): LocalHealthConnectDuplicateResolutionResult = database.withTransaction {
        require(provider.isNotBlank()) { "Health Connect provider must not be blank." }
        require(recordId.isNotBlank()) { "Health Connect record id must not be blank." }
        val importDao = database.importLedgerDao()
        val mapping = importDao.healthConnectMapping(provider, recordId)
            ?: return@withTransaction LocalHealthConnectDuplicateResolutionResult.MappingMissing(provider, recordId)
        val candidateId = mapping.duplicateCandidateActivityId
            ?: return@withTransaction LocalHealthConnectDuplicateResolutionResult.AlreadyResolved(mapping.mappingId)
        if (mapping.correctionPending || mapping.deletePending || mapping.lifecycleState != HEALTH_CONNECT_MAPPING_STATE_ACTIVE) {
            return@withTransaction LocalHealthConnectDuplicateResolutionResult.UnexpectedMappingState(
                mapping.mappingId,
                mapping.lifecycleState,
            )
        }
        val healthConnectActivityId = mapping.activityId
            ?: return@withTransaction LocalHealthConnectDuplicateResolutionResult.HealthConnectReviewMissing(mapping.mappingId)
        val healthConnectActivity = database.activityLedgerDao().activity(healthConnectActivityId)
            ?: return@withTransaction LocalHealthConnectDuplicateResolutionResult.HealthConnectReviewMissing(mapping.mappingId)
        if (
            healthConnectActivity.source != HEALTH_CONNECT_SOURCE ||
            healthConnectActivity.reviewState != REVIEW_STATE
        ) {
            return@withTransaction LocalHealthConnectDuplicateResolutionResult.UnexpectedHealthConnectReview(
                mapping.mappingId,
                healthConnectActivity.source,
                healthConnectActivity.reviewState,
            )
        }
        val existing = database.activityLedgerDao().activity(candidateId)
            ?: return@withTransaction LocalHealthConnectDuplicateResolutionResult.ExistingActivityMissing(
                mapping.mappingId,
                candidateId,
            )
        if (existing.source == HEALTH_CONNECT_SOURCE) {
            return@withTransaction LocalHealthConnectDuplicateResolutionResult.UnexpectedExistingActivitySource(
                mapping.mappingId,
                existing.source,
            )
        }

        when (decision) {
            LocalHealthConnectDuplicateDecision.KeepBoth -> {
                importDao.saveHealthConnectMapping(mapping.copy(duplicateCandidateActivityId = null))
                LocalHealthConnectDuplicateResolutionResult.KeptBoth(
                    healthConnectActivityId = healthConnectActivity.activityId,
                    existingActivityId = existing.activityId,
                )
            }
            LocalHealthConnectDuplicateDecision.UseExisting -> {
                importDao.deleteImportedActivityToTombstone(
                    activityId = healthConnectActivity.activityId,
                    source = HEALTH_CONNECT_SOURCE,
                    digest = mapping.externalRecordId,
                    tombstonedAtEpochMillis = nowEpochMillis(),
                )
                LocalHealthConnectDuplicateResolutionResult.UsedExisting(
                    removedHealthConnectActivityId = healthConnectActivity.activityId,
                    existingActivityId = existing.activityId,
                )
            }
        }
    }

    private suspend fun resolvePendingCorrection(
        provider: String,
        recordId: String,
        accept: Boolean,
    ): LocalHealthConnectPendingResolutionResult {
        require(provider.isNotBlank()) { "Health Connect provider must not be blank." }
        require(recordId.isNotBlank()) { "Health Connect record id must not be blank." }
        val importDao = database.importLedgerDao()
        val mapping = importDao.healthConnectMapping(provider, recordId)
            ?: return LocalHealthConnectPendingResolutionResult.MappingMissing(provider, recordId)
        val pendingAction = mapping.pendingAction()
        if (pendingAction != LocalHealthConnectPendingAction.Correction) {
            return pendingActionResult(mapping, pendingAction, LocalHealthConnectPendingAction.Correction)
        }
        val activity = mapping.activityId?.let { activityId ->
            database.activityLedgerDao().activity(activityId)
        }
            ?: return LocalHealthConnectPendingResolutionResult.ActivityMissing(mapping.mappingId)
        if (activity.reviewState != ACTIVITY_REVIEW_STATE_ACCEPTED) {
            return LocalHealthConnectPendingResolutionResult.UnexpectedActivityState(
                mapping.mappingId,
                activity.reviewState,
            )
        }
        if (activity.source != HEALTH_CONNECT_SOURCE) {
            return LocalHealthConnectPendingResolutionResult.UnexpectedActivitySource(
                mapping.mappingId,
                activity.source,
            )
        }
        val pending = importDao.pendingHealthConnectObservation(mapping.mappingId)
            ?: return LocalHealthConnectPendingResolutionResult.IncompletePendingState(mapping.mappingId)
        val route = importDao.pendingHealthConnectRouteSamples(mapping.mappingId, MAX_RETAINED_ROUTE_SAMPLES)
        val heartRate = importDao.pendingHealthConnectHeartRateSamples(mapping.mappingId, MAX_RETAINED_HEART_RATE_SAMPLES)
        if (accept) {
            val now = nowEpochMillis()
            database.activityLedgerDao().saveActivity(
                activity.copy(
                    occurredAtEpochMillis = pending.occurredAtEpochMillis,
                    durationSeconds = pending.durationSeconds,
                    distanceMeters = pending.distanceMeters,
                    averageHeartRateBpm = pending.averageHeartRateBpm,
                    maxHeartRateBpm = pending.maxHeartRateBpm,
                    averageCadenceSpm = pending.averageCadenceSpm,
                    elevationGainMeters = pending.elevationGainMeters,
                    routePointCount = route.size,
                    heartRatePointCount = heartRate.size,
                    heartRateSourceSampleCount = pending.heartRateSourceSampleCount,
                    routeTraceRetained = route.isNotEmpty(),
                    routeStartEndRedacted = route.isEmpty() && pending.routeSourcePointCount > 0,
                    heartRateSeriesRetained = heartRate.isNotEmpty(),
                    updatedAtEpochMillis = now,
                ),
            )
            database.activityLedgerDao().replaceRouteSamplesBounded(
                activity.activityId,
                route.mapIndexed { ordinal, sample ->
                    RouteSampleEntity(
                        activityId = activity.activityId,
                        ordinal = ordinal,
                        latitudeE6 = sample.latitudeE6,
                        longitudeE6 = sample.longitudeE6,
                        elapsedSeconds = sample.elapsedSeconds,
                        elevationMeters = sample.elevationMeters,
                        segmentOrdinal = sample.segmentOrdinal,
                        speedMetersPerSecond = sample.speedMetersPerSecond,
                    )
                },
                MAX_RETAINED_ROUTE_SAMPLES,
            )
            database.activityLedgerDao().replaceHeartRateSamplesBounded(
                activity.activityId,
                heartRate.mapIndexed { ordinal, sample ->
                    HeartRateSampleEntity(
                        activityId = activity.activityId,
                        ordinal = ordinal,
                        elapsedSeconds = sample.elapsedSeconds,
                        beatsPerMinute = sample.beatsPerMinute,
                        sourceSampleCount = pending.heartRateSourceSampleCount,
                    )
                },
                MAX_RETAINED_HEART_RATE_SAMPLES,
            )
        }
        // A rejected correction intentionally keeps the observed fingerprint. Replaying the same
        // provider observation is then a no-op; only a genuinely newer observation can reopen it.
        importDao.clearPendingHealthConnectObservation(mapping.mappingId)
        importDao.saveHealthConnectMapping(
            mapping.copy(
                correctionPending = false,
                deletePending = false,
                lifecycleState = HEALTH_CONNECT_MAPPING_STATE_ACTIVE,
                deletedAtEpochMillis = null,
            ),
        )
        return if (accept) {
            LocalHealthConnectPendingResolutionResult.CorrectionAccepted(activity.activityId)
        } else {
            LocalHealthConnectPendingResolutionResult.CorrectionRejected(activity.activityId)
        }
    }

    private suspend fun pendingProviderDeletion(
        provider: String,
        recordId: String,
    ): PendingProviderDeletion? {
        require(provider.isNotBlank()) { "Health Connect provider must not be blank." }
        require(recordId.isNotBlank()) { "Health Connect record id must not be blank." }
        val mapping = database.importLedgerDao().healthConnectMapping(provider, recordId)
            ?: return null
        val pendingAction = mapping.pendingAction()
        if (pendingAction != LocalHealthConnectPendingAction.SourceDelete) {
            return PendingProviderDeletion.Failure(
                pendingActionResult(mapping, pendingAction, LocalHealthConnectPendingAction.SourceDelete),
            )
        }
        val activity = mapping.activityId?.let { activityId ->
            database.activityLedgerDao().activity(activityId)
        }
            ?: return PendingProviderDeletion.Failure(
                LocalHealthConnectPendingResolutionResult.ActivityMissing(mapping.mappingId),
            )
        if (activity.reviewState != ACTIVITY_REVIEW_STATE_ACCEPTED) {
            return PendingProviderDeletion.Failure(
                LocalHealthConnectPendingResolutionResult.UnexpectedActivityState(
                    mapping.mappingId,
                    activity.reviewState,
                ),
            )
        }
        if (activity.source != HEALTH_CONNECT_SOURCE) {
            return PendingProviderDeletion.Failure(
                LocalHealthConnectPendingResolutionResult.UnexpectedActivitySource(
                    mapping.mappingId,
                    activity.source,
                ),
            )
        }
        return PendingProviderDeletion.Ready(mapping, activity)
    }

    private fun pendingResolutionFailure(
        provider: String,
        recordId: String,
    ): LocalHealthConnectPendingResolutionResult =
        LocalHealthConnectPendingResolutionResult.MappingMissing(provider, recordId)

    private fun pendingActionResult(
        mapping: HealthConnectMappingEntity,
        actual: LocalHealthConnectPendingAction,
        expected: LocalHealthConnectPendingAction,
    ): LocalHealthConnectPendingResolutionResult = when (actual) {
        LocalHealthConnectPendingAction.None -> LocalHealthConnectPendingResolutionResult.AlreadyResolved(
            mapping.mappingId,
            expected,
        )
        else -> LocalHealthConnectPendingResolutionResult.WrongPendingAction(
            mapping.mappingId,
            expected,
            actual,
        )
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
        val existingActivity = database.activityLedgerDao().activity(activity.activityId)
        val preserveExistingRoute = existingActivity != null &&
            (!observation.routeObserved || existingActivity.routeStartEndRedacted)
        val route = when {
            !retainRoute -> emptyList()
            preserveExistingRoute -> database.activityLedgerDao()
                .routeSamples(activity.activityId, MAX_RETAINED_ROUTE_SAMPLES)
                .map(RouteSampleEntity::toLocalHealthConnectRoutePoint)
            observation.routeObserved -> activity.route.boundEvenly(MAX_RETAINED_ROUTE_SAMPLES)
            else -> emptyList()
        }
        val retainHeartRate = profile.heartRateDataMode == HEART_RATE_MODE_PRIVATE
        val heartRate = if (retainHeartRate) {
            activity.heartRate.boundEvenly(MAX_RETAINED_HEART_RATE_SAMPLES)
        } else {
            emptyList()
        }
        val proposedEntity = activity.toReviewEntity(
            now = now,
            route = route,
            heartRate = heartRate,
            retainRoute = retainRoute,
            retainHeartRate = retainHeartRate,
            sourceRecordId = observation.recordId,
            createdAtEpochMillis = existingActivity?.createdAtEpochMillis ?: now,
        )
        val entity = if (preserveExistingRoute) {
            proposedEntity.copy(
                routePointCount = requireNotNull(existingActivity).routePointCount,
                routeTraceRetained = existingActivity.routeTraceRetained,
                routeStartEndRedacted = existingActivity.routeStartEndRedacted,
            )
        } else {
            proposedEntity
        }
        database.activityLedgerDao().saveActivity(entity)
        if (!preserveExistingRoute) {
            database.activityLedgerDao().replaceRouteSamplesBounded(
                entity.activityId,
                route.toRouteEntities(entity.activityId),
                MAX_RETAINED_ROUTE_SAMPLES,
            )
        }
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
        val existingActivity = mapping.activityId?.let { activityId ->
            database.activityLedgerDao().activity(activityId)
        }
        val preserveExistingRoute = existingActivity != null &&
            (!observation.routeObserved || existingActivity.routeStartEndRedacted)
        val route = when {
            !retainRoute -> emptyList()
            preserveExistingRoute -> database.activityLedgerDao()
                .routeSamples(requireNotNull(existingActivity).activityId, MAX_RETAINED_ROUTE_SAMPLES)
                .map(RouteSampleEntity::toLocalHealthConnectRoutePoint)
            observation.routeObserved -> proposed.route.boundEvenly(MAX_RETAINED_ROUTE_SAMPLES)
            else -> emptyList()
        }
        val routeSourcePointCount = if (preserveExistingRoute) {
            when {
                requireNotNull(existingActivity).routeStartEndRedacted -> 1
                existingActivity.routeTraceRetained -> existingActivity.routePointCount
                else -> 0
            }
        } else {
            proposed.routeSourcePointCount
        }
        val retainHeartRate = profile.heartRateDataMode == HEART_RATE_MODE_PRIVATE
        val heartRate = if (retainHeartRate) {
            proposed.heartRate.boundEvenly(MAX_RETAINED_HEART_RATE_SAMPLES)
        } else {
            emptyList()
        }
        importDao.saveHealthConnectMapping(mapping.toEntity(existing, observation, provider, now))
        importDao.savePendingHealthConnectObservation(
            HealthConnectPendingObservationEntity(
                mappingId = mapping.mappingId,
                observedAtEpochMillis = now,
                occurredAtEpochMillis = proposed.startedAtEpochMillis,
                durationSeconds = proposed.durationSeconds,
                distanceMeters = proposed.distanceMeters,
                averageHeartRateBpm = proposed.averageHeartRateBpm.takeIf { retainHeartRate },
                maxHeartRateBpm = proposed.maxHeartRateBpm.takeIf { retainHeartRate },
                averageCadenceSpm = proposed.averageCadenceSpm,
                elevationGainMeters = proposed.elevationGainMeters,
                heartRateSourceSampleCount = if (retainHeartRate) proposed.heartRateSourceSampleCount else 0,
                routeSourcePointCount = routeSourcePointCount,
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
        const val HEART_RATE_MODE_PRIVATE = "private"
        const val MAX_RETAINED_ROUTE_SAMPLES = 600
        const val MAX_RETAINED_HEART_RATE_SAMPLES = 600
        const val HEALTH_CONNECT_MAPPING_STATE_ACTIVE = "active"
        const val HEALTH_CONNECT_MAPPING_STATE_DETACHED = "detached"
    }
}

sealed interface LocalHealthConnectPendingResolutionResult {
    data class CorrectionAccepted(val activityId: String) : LocalHealthConnectPendingResolutionResult
    data class CorrectionRejected(val activityId: String) : LocalHealthConnectPendingResolutionResult
    data class ProviderDeletionDeleted(val activityId: String) : LocalHealthConnectPendingResolutionResult
    data class ProviderDeletionRetained(val activityId: String) : LocalHealthConnectPendingResolutionResult
    data class MappingMissing(val provider: String, val recordId: String) : LocalHealthConnectPendingResolutionResult
    data class ActivityMissing(val mappingId: String) : LocalHealthConnectPendingResolutionResult
    data class UnexpectedActivityState(val mappingId: String, val state: String) : LocalHealthConnectPendingResolutionResult
    data class UnexpectedActivitySource(val mappingId: String, val source: String) : LocalHealthConnectPendingResolutionResult
    data class IncompletePendingState(val mappingId: String) : LocalHealthConnectPendingResolutionResult
    data class AlreadyResolved(
        val mappingId: String,
        val expected: LocalHealthConnectPendingAction,
    ) : LocalHealthConnectPendingResolutionResult
    data class WrongPendingAction(
        val mappingId: String,
        val expected: LocalHealthConnectPendingAction,
        val actual: LocalHealthConnectPendingAction,
    ) : LocalHealthConnectPendingResolutionResult
}

private sealed interface PendingProviderDeletion {
    data class Ready(
        val mapping: HealthConnectMappingEntity,
        val activity: ActivityEntity,
    ) : PendingProviderDeletion
    data class Failure(val result: LocalHealthConnectPendingResolutionResult) : PendingProviderDeletion
}

sealed interface LocalHealthConnectPersistenceResult {
    data class Applied(val outcome: LocalHealthConnectOutcome) : LocalHealthConnectPersistenceResult
    data object ProfileNotConfigured : LocalHealthConnectPersistenceResult
    data object InvalidProvider : LocalHealthConnectPersistenceResult
    data object FutureActivity : LocalHealthConnectPersistenceResult
}

enum class LocalHealthConnectDuplicateDecision { KeepBoth, UseExisting }

sealed interface LocalHealthConnectDuplicateResolutionResult {
    data class KeptBoth(
        val healthConnectActivityId: String,
        val existingActivityId: String,
    ) : LocalHealthConnectDuplicateResolutionResult

    data class UsedExisting(
        val removedHealthConnectActivityId: String,
        val existingActivityId: String,
    ) : LocalHealthConnectDuplicateResolutionResult

    data class MappingMissing(val provider: String, val recordId: String) : LocalHealthConnectDuplicateResolutionResult
    data class AlreadyResolved(val mappingId: String) : LocalHealthConnectDuplicateResolutionResult
    data class UnexpectedMappingState(val mappingId: String, val lifecycleState: String) : LocalHealthConnectDuplicateResolutionResult
    data class HealthConnectReviewMissing(val mappingId: String) : LocalHealthConnectDuplicateResolutionResult
    data class ExistingActivityMissing(val mappingId: String, val activityId: String) : LocalHealthConnectDuplicateResolutionResult
    data class UnexpectedHealthConnectReview(
        val mappingId: String,
        val source: String,
        val reviewState: String,
    ) : LocalHealthConnectDuplicateResolutionResult
    data class UnexpectedExistingActivitySource(
        val mappingId: String,
        val source: String,
    ) : LocalHealthConnectDuplicateResolutionResult
}

private suspend fun dev.deftmartian.runway.data.ActivityLedgerDao.findConservativeDuplicateCandidate(
    observation: HealthConnectObservation.RunningUpsert,
): String? = potentialDuplicateActivities(
    targetEpochMillis = observation.startedAtEpochMillis,
    fromInclusive = observation.startedAtEpochMillis - DUPLICATE_TIME_WINDOW_MILLIS,
    throughInclusive = observation.startedAtEpochMillis + DUPLICATE_TIME_WINDOW_MILLIS,
    excludedSource = HEALTH_CONNECT_SOURCE,
    limit = MAX_DUPLICATE_CANDIDATES,
).firstOrNull { candidate -> candidate.isConservativeDuplicateOf(observation) }?.activityId

internal fun ActivityEntity.isConservativeDuplicateOf(
    observation: HealthConnectObservation.RunningUpsert,
): Boolean {
    val candidateDistance = distanceMeters ?: return false
    val candidateDuration = durationSeconds ?: return false
    if (source == HEALTH_CONNECT_SOURCE) return false
    if (kotlin.math.abs(occurredAtEpochMillis - observation.startedAtEpochMillis) > DUPLICATE_TIME_WINDOW_MILLIS) return false
    val allowedDistanceDifference = maxOf(
        DUPLICATE_MIN_DISTANCE_DIFFERENCE_METERS,
        (maxOf(candidateDistance, observation.distanceMeters) * DUPLICATE_DISTANCE_DIFFERENCE_PERCENT) / 100,
    )
    if (kotlin.math.abs(candidateDistance - observation.distanceMeters) > allowedDistanceDifference) return false
    val allowedDurationDifference = maxOf(
        DUPLICATE_MIN_DURATION_DIFFERENCE_SECONDS,
        (maxOf(candidateDuration, observation.durationSeconds) * DUPLICATE_DURATION_DIFFERENCE_PERCENT) / 100,
    )
    return kotlin.math.abs(candidateDuration - observation.durationSeconds) <= allowedDurationDifference
}

private const val HEALTH_CONNECT_SOURCE = "health_connect"
private const val REVIEW_STATE = "review"
private const val DUPLICATE_TIME_WINDOW_MILLIS = 10 * 60 * 1_000L
private const val DUPLICATE_MIN_DISTANCE_DIFFERENCE_METERS = 200
private const val DUPLICATE_DISTANCE_DIFFERENCE_PERCENT = 3
private const val DUPLICATE_MIN_DURATION_DIFFERENCE_SECONDS = 180
private const val DUPLICATE_DURATION_DIFFERENCE_PERCENT = 5
private const val MAX_DUPLICATE_CANDIDATES = 20

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

private fun HealthConnectMappingEntity.pendingAction(): LocalHealthConnectPendingAction = when {
    correctionPending && deletePending -> error("A Health Connect mapping cannot wait for correction and deletion at once.")
    correctionPending -> LocalHealthConnectPendingAction.Correction
    deletePending -> LocalHealthConnectPendingAction.SourceDelete
    else -> LocalHealthConnectPendingAction.None
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
    retainHeartRate: Boolean,
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
    averageHeartRateBpm = averageHeartRateBpm.takeIf { retainHeartRate },
    averageCadenceSpm = averageCadenceSpm,
    linkedWorkoutId = null,
    acceptedAtEpochMillis = null,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = now,
    maxHeartRateBpm = maxHeartRateBpm.takeIf { retainHeartRate },
    // A provider average is not a maximum. Never promote it to max speed.
    maxSpeedMetersPerSecond = null,
    elevationGainMeters = elevationGainMeters,
    routePointCount = route.size,
    heartRatePointCount = heartRate.size,
    heartRateSourceSampleCount = if (retainHeartRate) heartRateSourceSampleCount else 0,
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

private fun RouteSampleEntity.toLocalHealthConnectRoutePoint(): LocalHealthConnectRoutePoint =
    LocalHealthConnectRoutePoint(
        elapsedSeconds = elapsedSeconds ?: 0,
        latitudeE6 = latitudeE6,
        longitudeE6 = longitudeE6,
        segmentIndex = segmentOrdinal ?: 0,
        speedMetersPerSecond = speedMetersPerSecond,
    )

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
