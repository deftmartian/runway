package dev.deftmartian.runway.data.healthconnect

import java.security.MessageDigest

/**
 * Pure reconciliation contract between Health Connect acquisition and local persistence.
 *
 * It deliberately does not call Health Connect or Room. The caller must apply the returned
 * outcome with the mapping, activity, and tombstone writes in one database transaction.
 */
object LocalHealthConnectReconciler {
    fun reduce(
        observation: HealthConnectObservation,
        state: LocalHealthConnectRecordState,
    ): LocalHealthConnectOutcome = when (observation) {
        is HealthConnectObservation.Deleted -> reduceDeletion(observation, state)
        is HealthConnectObservation.RunningUpsert -> reduceUpsert(observation, state)
    }

    private fun reduceDeletion(
        observation: HealthConnectObservation.Deleted,
        state: LocalHealthConnectRecordState,
    ): LocalHealthConnectOutcome {
        val mapping = state.mapping ?: return LocalHealthConnectOutcome.Unchanged(observation.recordId)
        if (mapping.deletedAtEpochMillis != null) return LocalHealthConnectOutcome.Unchanged(observation.recordId)
        val activity = state.activity ?: return LocalHealthConnectOutcome.Unchanged(observation.recordId)
        return if (activity.reviewState == LocalActivityReviewState.Accepted) {
            LocalHealthConnectOutcome.PendingDelete(
                mapping = mapping.copy(
                    pendingAction = LocalHealthConnectPendingAction.SourceDelete,
                    deletedAtEpochMillis = observation.observedAtEpochMillis,
                ),
                activityId = activity.activityId,
                decisions = setOf(
                    LocalHealthConnectDeleteDecision.DeleteFromRunway,
                    LocalHealthConnectDeleteDecision.RetainInRunway,
                ),
            )
        } else {
            LocalHealthConnectOutcome.DeleteReview(
                mapping = mapping.copy(
                    pendingAction = LocalHealthConnectPendingAction.None,
                    deletedAtEpochMillis = observation.observedAtEpochMillis,
                ),
                activityId = activity.activityId,
            )
        }
    }

    private fun reduceUpsert(
        observation: HealthConnectObservation.RunningUpsert,
        state: LocalHealthConnectRecordState,
    ): LocalHealthConnectOutcome {
        if (state.tombstoned) return LocalHealthConnectOutcome.Unchanged(observation.recordId)
        val fingerprint = healthConnectFingerprint(observation)
        val mapping = state.mapping
        if (mapping != null && mapping.fingerprint == fingerprint && mapping.deletedAtEpochMillis == null) {
            return LocalHealthConnectOutcome.Unchanged(observation.recordId)
        }

        val proposed = localHealthConnectActivity(observation)
        val existing = state.activity
        if (existing?.reviewState == LocalActivityReviewState.Accepted) {
            return LocalHealthConnectOutcome.PendingCorrection(
                mapping = requireNotNull(mapping) {
                    "An accepted local Health Connect activity must retain its external mapping."
                }.copy(
                    fingerprint = fingerprint,
                    pendingAction = LocalHealthConnectPendingAction.Correction,
                    pendingActivity = proposed,
                    deletedAtEpochMillis = null,
                ),
                activityId = existing.activityId,
                proposed = proposed,
            )
        }

        val refreshedMapping = LocalHealthConnectMapping(
            mappingId = mapping?.mappingId ?: localHealthConnectMappingId(observation.provider, observation.recordId),
            provider = observation.provider,
            externalRecordId = observation.recordId,
            fingerprint = fingerprint,
            activityId = existing?.activityId ?: proposed.activityId,
            pendingAction = LocalHealthConnectPendingAction.None,
            pendingActivity = null,
            duplicateCandidateActivityId = state.duplicateCandidateActivityId.takeIf { existing == null },
            deletedAtEpochMillis = null,
        )
        if (existing != null) {
            return LocalHealthConnectOutcome.ReviewUpdate(refreshedMapping, proposed.copy(activityId = existing.activityId))
        }
        return state.duplicateCandidateActivityId?.let { candidateId ->
            LocalHealthConnectOutcome.DuplicateCandidate(refreshedMapping, proposed, candidateId)
        } ?: LocalHealthConnectOutcome.NewReview(refreshedMapping, proposed)
    }
}

/** Only running and treadmill-running observations enter the local running product. */
sealed interface HealthConnectObservation {
    val recordId: String

    data class RunningUpsert(
        override val recordId: String,
        val provider: String,
        val runningType: LocalHealthConnectRunningType,
        val originKey: String,
        val originLabel: String,
        val startedAtEpochMillis: Long,
        val durationSeconds: Int,
        val distanceMeters: Int,
        val averageHeartRateBpm: Int? = null,
        val maxHeartRateBpm: Int? = null,
        val averageCadenceSpm: Int? = null,
        val elevationGainMeters: Double? = null,
        val averageSpeedMetersPerSecond: Double? = null,
        val heartRate: List<LocalHealthConnectHeartRatePoint> = emptyList(),
        val heartRateSourceSampleCount: Int = heartRate.size,
        val route: List<LocalHealthConnectRoutePoint> = emptyList(),
        val routeSourcePointCount: Int = route.size,
    ) : HealthConnectObservation

    data class Deleted(
        override val recordId: String,
        val observedAtEpochMillis: Long,
    ) : HealthConnectObservation
}

enum class LocalHealthConnectRunningType { Running, TreadmillRunning }
enum class LocalActivityReviewState { Review, Accepted }
enum class LocalHealthConnectPendingAction { None, Correction, SourceDelete }
enum class LocalHealthConnectDeleteDecision { DeleteFromRunway, RetainInRunway }

data class LocalHealthConnectHeartRatePoint(val elapsedSeconds: Int, val bpm: Int)
data class LocalHealthConnectRoutePoint(
    val elapsedSeconds: Int,
    val latitudeE6: Int,
    val longitudeE6: Int,
    val segmentIndex: Int = 0,
    val speedMetersPerSecond: Double? = null,
)

data class LocalHealthConnectActivity(
    val activityId: String,
    val startedAtEpochMillis: Long,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val averageCadenceSpm: Int?,
    val elevationGainMeters: Double?,
    val averageSpeedMetersPerSecond: Double?,
    val heartRate: List<LocalHealthConnectHeartRatePoint>,
    val heartRateSourceSampleCount: Int,
    val route: List<LocalHealthConnectRoutePoint>,
    val routeSourcePointCount: Int,
)

data class LocalHealthConnectMapping(
    val mappingId: String,
    val provider: String,
    val externalRecordId: String,
    val fingerprint: String,
    val activityId: String?,
    val pendingAction: LocalHealthConnectPendingAction = LocalHealthConnectPendingAction.None,
    val pendingActivity: LocalHealthConnectActivity? = null,
    val duplicateCandidateActivityId: String? = null,
    val deletedAtEpochMillis: Long? = null,
)

data class LocalHealthConnectMappedActivity(
    val activityId: String,
    val reviewState: LocalActivityReviewState,
)

data class LocalHealthConnectRecordState(
    val mapping: LocalHealthConnectMapping?,
    val activity: LocalHealthConnectMappedActivity?,
    val tombstoned: Boolean = false,
    val duplicateCandidateActivityId: String? = null,
)

sealed interface LocalHealthConnectOutcome {
    data class NewReview(
        val mapping: LocalHealthConnectMapping,
        val activity: LocalHealthConnectActivity,
    ) : LocalHealthConnectOutcome

    data class DuplicateCandidate(
        val mapping: LocalHealthConnectMapping,
        val activity: LocalHealthConnectActivity,
        val existingActivityId: String,
    ) : LocalHealthConnectOutcome

    data class ReviewUpdate(
        val mapping: LocalHealthConnectMapping,
        val activity: LocalHealthConnectActivity,
    ) : LocalHealthConnectOutcome

    data class PendingCorrection(
        val mapping: LocalHealthConnectMapping,
        val activityId: String,
        val proposed: LocalHealthConnectActivity,
    ) : LocalHealthConnectOutcome

    data class DeleteReview(
        val mapping: LocalHealthConnectMapping,
        val activityId: String,
    ) : LocalHealthConnectOutcome

    data class PendingDelete(
        val mapping: LocalHealthConnectMapping,
        val activityId: String,
        val decisions: Set<LocalHealthConnectDeleteDecision>,
    ) : LocalHealthConnectOutcome

    data class Unchanged(val recordId: String) : LocalHealthConnectOutcome
}

internal fun localHealthConnectActivity(
    observation: HealthConnectObservation.RunningUpsert,
): LocalHealthConnectActivity = LocalHealthConnectActivity(
    activityId = localHealthConnectActivityId(observation.provider, observation.recordId),
    startedAtEpochMillis = observation.startedAtEpochMillis,
    durationSeconds = observation.durationSeconds,
    distanceMeters = observation.distanceMeters,
    averageHeartRateBpm = observation.averageHeartRateBpm,
    maxHeartRateBpm = observation.maxHeartRateBpm,
    averageCadenceSpm = observation.averageCadenceSpm,
    elevationGainMeters = observation.elevationGainMeters,
    averageSpeedMetersPerSecond = observation.averageSpeedMetersPerSecond,
    heartRate = observation.heartRate,
    heartRateSourceSampleCount = observation.heartRateSourceSampleCount,
    route = observation.route,
    routeSourcePointCount = observation.routeSourcePointCount,
)

/** Every field is included in order, including omitted values and retained metric samples. */
internal fun healthConnectFingerprint(observation: HealthConnectObservation.RunningUpsert): String =
    sha256(
        buildList {
            add("runway-local-health-connect-v1")
            add(observation.startedAtEpochMillis.toString())
            add(observation.durationSeconds.toString())
            add(observation.distanceMeters.toString())
            add(observation.averageHeartRateBpm?.toString())
            add(observation.maxHeartRateBpm?.toString())
            add(observation.averageCadenceSpm?.toString())
            add(observation.elevationGainMeters?.toString())
            add(observation.averageSpeedMetersPerSecond?.toString())
            add(observation.heartRateSourceSampleCount.toString())
            observation.heartRate.forEach { add("${it.elapsedSeconds},${it.bpm}") }
            add(observation.routeSourcePointCount.toString())
            observation.route.forEach {
                add("${it.elapsedSeconds},${it.latitudeE6},${it.longitudeE6},${it.segmentIndex},${it.speedMetersPerSecond}")
            }
        }.joinToString("\u0000") { it ?: "<null>" },
    )

internal fun localHealthConnectMappingId(provider: String, recordId: String): String =
    stableUuid("runway-local-health-connect-mapping-v1", provider, recordId)

internal fun localHealthConnectActivityId(provider: String, recordId: String): String =
    stableUuid("runway-local-health-connect-activity-v1", provider, recordId)

private fun stableUuid(namespace: String, provider: String, recordId: String): String {
    require(provider.isNotBlank())
    require(recordId.isNotBlank())
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("$namespace\u0000$provider\u0000$recordId".toByteArray(Charsets.UTF_8))
        .copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val compact = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "${compact.substring(0, 8)}-${compact.substring(8, 12)}-${compact.substring(12, 16)}-${compact.substring(16, 20)}-${compact.substring(20)}"
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
