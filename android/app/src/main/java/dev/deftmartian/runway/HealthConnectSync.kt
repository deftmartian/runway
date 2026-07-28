package dev.deftmartian.runway

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.SortedSet

internal const val HEALTH_CONNECT_SCHEMA_VERSION = 1
internal val HEALTH_CONNECT_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(SpeedRecord::class),
    HealthPermission.getReadPermission(StepsCadenceRecord::class),
    HealthPermission.getReadPermission(ElevationGainedRecord::class),
)
internal const val HEALTH_CONNECT_BACKGROUND_PERMISSION =
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

internal enum class HealthConnectAvailability { Available, Unavailable, UpdateRequired }

internal data class HealthConnectRun(
    val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val sourcePackage: String,
    val distanceMeters: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val averageSpeedMetersPerSecond: Double? = null,
    val averageCadenceRpm: Double? = null,
    val elevationGainMeters: Double? = null,
    val maxHeartRateBpm: Double? = null,
    val heartRateSamples: List<HealthConnectHeartRateSample> = emptyList(),
    val heartRateSourceSampleCount: Int = heartRateSamples.size,
    val routePoints: List<HealthConnectRoutePoint> = emptyList(),
    val routeSourcePointCount: Int = routePoints.size,
)

internal data class HealthConnectHeartRateSample(val elapsedSeconds: Int, val bpm: Int)
internal data class HealthConnectRoutePoint(
    val elapsedSeconds: Int,
    val latitudeE6: Int,
    val longitudeE6: Int,
    val speedMetersPerSecond: Double?,
)

internal data class HealthConnectBatch(
    val upserts: List<HealthConnectRun>,
    val deletes: List<String>,
    val nextToken: String,
    val hasMore: Boolean,
    val expired: Boolean = false,
    val metricChanged: Boolean = false,
)

/** A narrow seam keeps Android Health Connect IPC out of the sync decision and its unit tests. */
internal interface HealthConnectGateway {
    fun availability(): HealthConnectAvailability
    fun hasPermissions(): Boolean
    fun initialRuns(since: Instant): List<HealthConnectRun>
    fun newChangesToken(): String
    fun changes(token: String): HealthConnectBatch
}

internal class AndroidHealthConnectGateway(
    private val context: Context,
    private val includeRoutes: Boolean = false,
    private val routeOverrides: Map<String, ExerciseRoute> = emptyMap(),
) : HealthConnectGateway {
    var routeConsentRecordId: String? = null
        private set
    override fun availability(): HealthConnectAvailability = when (
        HealthConnectClient.getSdkStatus(context)
    ) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
        else -> HealthConnectAvailability.Unavailable
    }

    override fun hasPermissions(): Boolean = runBlocking {
        client().permissionController.getGrantedPermissions().containsAll(HEALTH_CONNECT_PERMISSIONS)
    }

    fun hasBackgroundPermission(): Boolean = runBlocking {
        client().permissionController.getGrantedPermissions().contains(HEALTH_CONNECT_BACKGROUND_PERMISSION)
    }

    fun revokeAllPermissions(): Boolean = runCatching {
        if (availability() == HealthConnectAvailability.Available) {
            runBlocking { client().permissionController.revokeAllPermissions() }
        }
        true
    }.getOrDefault(false)

    fun supportsBackgroundRead(): Boolean =
        HealthConnectBackgroundPolicy.supported(
            client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND),
        )

    override fun initialRuns(since: Instant): List<HealthConnectRun> = runBlocking {
        readAllRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(since, Instant.now()),
            ),
        ).mapNotNullSuspend(::asRunningRun)
    }

    override fun newChangesToken(): String = runBlocking {
        client().getChangesToken(ChangesTokenRequest(CHANGE_RECORD_TYPES))
    }

    override fun changes(token: String): HealthConnectBatch = runBlocking {
        val response = client().getChanges(token)
        HealthConnectBatch(
            upserts = response.changes.mapNotNullSuspend { change ->
                val record = (change as? androidx.health.connect.client.changes.UpsertionChange)
                    ?.record as? ExerciseSessionRecord
                record?.let { asRunningRun(it) }
            },
            deletes = response.changes.mapNotNull { change ->
                (change as? androidx.health.connect.client.changes.DeletionChange)?.recordId
            },
            nextToken = response.nextChangesToken,
            hasMore = response.hasMore,
            expired = response.changesTokenExpired,
            // 1.1 DeletionChange exposes only recordId, not record type. Treat every deletion as
            // potentially metric-affecting and re-read the bounded session window. We still send
            // every deletion so real session deletes are preserved; an unknown metric deletion is
            // a harmless unmatched server delete rather than stale metrics.
            metricChanged = response.changes.any { change ->
                change is androidx.health.connect.client.changes.DeletionChange ||
                    ((change as? androidx.health.connect.client.changes.UpsertionChange)?.record
                        ?.let { it !is ExerciseSessionRecord } == true)
            },
        )
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    /**
     * Health Connect paginates every record type, not only exercise sessions. A single session can
     * span multiple metric pages on high-frequency sources, so derive summaries only after the
     * complete, bounded result is available.
     */
    private suspend fun <T : Record> readAllRecords(request: ReadRecordsRequest<T>): List<T> =
        collectHealthConnectPages(readPage = { pageToken ->
            val page = client().readRecords(
                ReadRecordsRequest(
                    recordType = request.recordType,
                    timeRangeFilter = request.timeRangeFilter,
                    dataOriginFilter = request.dataOriginFilter,
                    ascendingOrder = request.ascendingOrder,
                    pageSize = request.pageSize,
                    pageToken = pageToken,
                ),
            )
            HealthConnectPage(page.records, page.pageToken)
        })

    private suspend fun asRunningRun(record: ExerciseSessionRecord): HealthConnectRun? {
        if (!HealthConnectRunningPolicy.accepts(record.exerciseType)) return null
        val range = TimeRangeFilter.between(record.startTime, record.endTime)
        val origin = setOf(DataOrigin(record.metadata.dataOrigin.packageName))
        val distances = readAllRecords(
            ReadRecordsRequest(DistanceRecord::class, timeRangeFilter = range, dataOriginFilter = origin),
        )
        val distanceMeters = distances.sumOf { it.distance.inMeters }.takeIf { it > 0.0 } ?: return null
        val heartRateSamples = readAllRecords(
            ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = range, dataOriginFilter = origin),
        ).flatMap { it.samples }
            .filter { it.time >= record.startTime && it.time <= record.endTime }
            .sortedBy { it.time }
        val boundedHeartRateSamples = downsampleHeartRate(
            heartRateSamples.map { sample ->
                HealthConnectHeartRateSample(
                    elapsedSeconds = ((sample.time.toEpochMilli() - record.startTime.toEpochMilli()) / 1_000)
                        .toInt().coerceAtLeast(0),
                    bpm = sample.beatsPerMinute.toInt(),
                )
            },
        )
        val speeds = readAllRecords(
            ReadRecordsRequest(SpeedRecord::class, timeRangeFilter = range, dataOriginFilter = origin),
        ).flatMap { it.samples }.filter { it.time >= record.startTime && it.time <= record.endTime }
        val cadence = readAllRecords(
            ReadRecordsRequest(StepsCadenceRecord::class, timeRangeFilter = range, dataOriginFilter = origin),
        ).flatMap { it.samples }.filter { it.time >= record.startTime && it.time <= record.endTime }
        val elevation = readAllRecords(
            ReadRecordsRequest(ElevationGainedRecord::class, timeRangeFilter = range, dataOriginFilter = origin),
        ).sumOf { it.elevation.inMeters }
        val route = if (includeRoutes) {
            when (val routeResult = record.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> routeResult.exerciseRoute.route
                is ExerciseRouteResult.ConsentRequired -> {
                    routeOverrides[record.metadata.id]?.route ?: run {
                        routeConsentRecordId = record.metadata.id
                        emptyList()
                    }
                }
                else -> emptyList()
            }
        } else {
            emptyList()
        }
        val routePoints = downsampleRoute(route.map { point ->
            HealthConnectRoutePoint(
                elapsedSeconds = ((point.time.toEpochMilli() - record.startTime.toEpochMilli()) / 1_000)
                    .toInt().coerceAtLeast(0),
                latitudeE6 = (point.latitude * 1_000_000).toInt(),
                longitudeE6 = (point.longitude * 1_000_000).toInt(),
                speedMetersPerSecond = speeds.minByOrNull { kotlin.math.abs(
                    it.time.toEpochMilli() - point.time.toEpochMilli()
                ) }?.speed?.inMetersPerSecond,
            )
        })
        return HealthConnectRun(
            id = record.metadata.id,
            startEpochMs = record.startTime.toEpochMilli(),
            endEpochMs = record.endTime.toEpochMilli(),
            sourcePackage = record.metadata.dataOrigin.packageName,
            distanceMeters = distanceMeters,
            averageHeartRateBpm = heartRateSamples.map { it.beatsPerMinute.toDouble() }.average()
                .takeIf { !it.isNaN() },
            maxHeartRateBpm = heartRateSamples.maxOfOrNull { it.beatsPerMinute.toDouble() },
            averageSpeedMetersPerSecond = speeds.map { it.speed.inMetersPerSecond }.average()
                .takeIf { !it.isNaN() },
            averageCadenceRpm = cadence.map { it.rate }.average().takeIf { !it.isNaN() },
            elevationGainMeters = elevation.takeIf { it > 0.0 },
            heartRateSamples = boundedHeartRateSamples,
            heartRateSourceSampleCount = heartRateSamples.size,
            routePoints = routePoints,
            routeSourcePointCount = route.size,
        )
    }

    private companion object {
        const val MAX_SAMPLES_PER_RUN = 600
        val CHANGE_RECORD_TYPES = setOf(
            ExerciseSessionRecord::class, DistanceRecord::class, HeartRateRecord::class,
            SpeedRecord::class, StepsCadenceRecord::class, ElevationGainedRecord::class,
        )
    }
}

internal data class HealthConnectPage<T>(val records: List<T>, val nextPageToken: String?)

/**
 * Bounded, token-driven collection keeps provider pagination explicit and testable outside Android
 * IPC. The cap matches the largest source count the upload contract can represent.
 */
internal suspend fun <T> collectHealthConnectPages(
    readPage: suspend (pageToken: String?) -> HealthConnectPage<T>,
    maximumRecords: Int = MAX_HEALTH_CONNECT_RECORDS_PER_QUERY,
): List<T> {
    val records = mutableListOf<T>()
    var pageToken: String? = null
    do {
        val page = readPage(pageToken)
        if (page.records.size > maximumRecords - records.size) {
            throw IllegalStateException("Health Connect record query exceeded its bounded source limit")
        }
        records += page.records
        pageToken = page.nextPageToken
    } while (pageToken != null)
    return records
}

internal const val MAX_HEALTH_CONNECT_RECORDS_PER_QUERY = 100_000

internal object HealthConnectRunningPolicy {
    fun accepts(exerciseType: Int): Boolean = exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
        exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
}

internal fun downsampleHeartRate(samples: List<HealthConnectHeartRateSample>): List<HealthConnectHeartRateSample> {
    if (samples.size <= 600) return samples
    val required = sortedSetOf(0, samples.lastIndex, samples.indices.maxBy { samples[it].bpm })
    return samples.selectRepresentative(600, required)
}

internal fun downsampleRoute(points: List<HealthConnectRoutePoint>): List<HealthConnectRoutePoint> {
    if (points.size <= 600) return points
    return points.selectRepresentative(600, sortedSetOf(0, points.lastIndex))
}

private fun <T> List<T>.selectRepresentative(limit: Int, required: SortedSet<Int>): List<T> {
    for (slot in 0 until limit) {
        if (required.size >= limit) break
        required += ((slot.toLong() * lastIndex) / (limit - 1)).toInt()
    }
    return required.take(limit).map(::get)
}

private suspend fun <T, R : Any> Iterable<T>.mapNotNullSuspend(transform: suspend (T) -> R?): List<R> {
    val result = ArrayList<R>()
    for (item in this) transform(item)?.let(result::add)
    return result
}

internal data class HealthConnectCursor(
    val origin: String,
    val deviceId: String,
    val credentialGeneration: Long,
    val importGeneration: Long,
    val token: String,
)

internal interface HealthConnectCursorRepository {
    fun load(): HealthConnectCursor?
    fun isCurrent(connection: ServerConnection, credentialState: AndroidCredentialState): Boolean
    fun saveIfCurrent(
        cursor: HealthConnectCursor,
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean
    fun clear()
    fun clearIfCurrent(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean
    fun needsAttention(): Boolean
    fun markNeedsAttentionIfCurrent(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean
    fun clearNeedsAttentionIfCurrent(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean
}

internal class HealthConnectCursorStore(context: Context, private val origin: String) : HealthConnectCursorRepository {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "runway_health_connect", Context.MODE_PRIVATE,
    )
    private val key = AndroidCredentialNamespace.originKey(origin)
    private val serverStore by lazy { ServerConnectionStore(appContext) }
    private val credentialStore by lazy { AndroidCredentialStore(appContext, origin) }

    override fun load(): HealthConnectCursor? {
        val token = preferences.getString("token_$key", null) ?: return null
        val savedOrigin = preferences.getString("origin_$key", null) ?: return null
        val deviceId = preferences.getString("device_$key", null) ?: return null
        return HealthConnectCursor(
            savedOrigin,
            deviceId,
            preferences.getLong("generation_$key", -1),
            preferences.getLong("import_generation_$key", -1),
            token,
        )
    }

    override fun isCurrent(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean = currentStateMatches(connection, credentialState)

    override fun saveIfCurrent(
        cursor: HealthConnectCursor,
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean = AndroidStateCoordinator.write {
        if (!currentStateMatches(connection, credentialState)) return@write false
        preferences.edit(commit = true) {
            putString("token_$key", cursor.token)
            putString("origin_$key", cursor.origin)
            putString("device_$key", cursor.deviceId)
            putLong("generation_$key", cursor.credentialGeneration)
            putLong("import_generation_$key", cursor.importGeneration)
        }
        true
    }

    override fun clear() = AndroidStateCoordinator.write {
        preferences.edit(commit = true) { remove("token_$key") }
    }

    fun clearAll() = AndroidStateCoordinator.write {
        preferences.edit(commit = true) {
            remove("token_$key")
            remove("attention_$key")
        }
    }

    override fun clearIfCurrent(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean = AndroidStateCoordinator.write {
        if (!currentStateMatches(connection, credentialState)) return@write false
        preferences.edit(commit = true) { remove("token_$key") }
        true
    }

    override fun needsAttention(): Boolean = preferences.getBoolean("attention_$key", false)

    override fun markNeedsAttentionIfCurrent(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean = setAttentionIfCurrent(true, connection, credentialState)

    override fun clearNeedsAttentionIfCurrent(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean = setAttentionIfCurrent(false, connection, credentialState)

    private fun setAttentionIfCurrent(
        needsAttention: Boolean,
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean = AndroidStateCoordinator.write {
        if (!currentStateMatches(connection, credentialState)) return@write false
        preferences.edit(commit = true) {
            if (needsAttention) putBoolean("attention_$key", true) else remove("attention_$key")
        }
        true
    }

    private fun currentStateMatches(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): Boolean =
        connection.origin == origin &&
            serverStore.isCurrent(connection) &&
            credentialStore.isCurrent(credentialState)
}

internal sealed interface HealthSyncResult {
    data object Synced : HealthSyncResult
    data object PermissionRequired : HealthSyncResult
    data object Unavailable : HealthSyncResult
    data object UpdateRequired : HealthSyncResult
    data object PairingRequired : HealthSyncResult
    data object Retryable : HealthSyncResult
    data object NeedsAttention : HealthSyncResult
}

internal sealed interface HealthCredentialRefresh {
    data class Ready(val state: AndroidCredentialState) : HealthCredentialRefresh
    data object PairingRequired : HealthCredentialRefresh
    data object Retryable : HealthCredentialRefresh
}

internal interface HealthCredentialRepository {
    fun currentIf(expected: AndroidCredentialState): AndroidCredential?
    fun replace(expected: AndroidCredentialState, credential: AndroidCredential): Boolean
    fun clear(expected: AndroidCredentialState): Boolean
    fun snapshot(): AndroidCredentialState
}

internal class AndroidHealthCredentialRepository(private val store: AndroidCredentialStore) : HealthCredentialRepository {
    override fun currentIf(expected: AndroidCredentialState): AndroidCredential? =
        store.useIfCurrent(expected) { it }
    override fun replace(expected: AndroidCredentialState, credential: AndroidCredential): Boolean =
        store.replace(expected, credential)
    override fun clear(expected: AndroidCredentialState): Boolean = store.clearIfCurrent(expected)
    override fun snapshot(): AndroidCredentialState = store.snapshot()
}

/** Refresh server generation before every sync so a server-side erase cannot be replayed locally. */
internal fun refreshHealthCredential(
    store: HealthCredentialRepository,
    expected: AndroidCredentialState,
    cursor: HealthConnectCursorRepository,
    status: (AndroidCredential) -> DeviceStatusApiResult,
): HealthCredentialRefresh {
    val credential = store.currentIf(expected) ?: return HealthCredentialRefresh.Retryable
    return when (val result = status(credential)) {
        is DeviceStatusApiResult.Connected -> {
            if (result.importGeneration == credential.importGeneration) {
                HealthCredentialRefresh.Ready(expected)
            } else if (store.replace(expected, credential.copy(importGeneration = result.importGeneration))) {
                cursor.clear()
                HealthCredentialRefresh.Ready(store.snapshot())
            } else {
                HealthCredentialRefresh.Retryable
            }
        }
        DeviceStatusApiResult.Unauthorized -> {
            if (store.clear(expected)) {
                cursor.clear()
                HealthCredentialRefresh.PairingRequired
            } else {
                HealthCredentialRefresh.Retryable
            }
        }
        DeviceStatusApiResult.Retryable -> HealthCredentialRefresh.Retryable
    }
}

internal class HealthConnectSyncCoordinator(
    private val gateway: HealthConnectGateway,
    private val send: (AndroidCredential, HealthConnectRequestPayload) -> HealthConnectApiResult,
    private val cursor: HealthConnectCursorRepository,
) {
    private fun sendBatches(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
        credential: AndroidCredential,
        upserts: List<HealthConnectRun>,
        deletes: List<String>,
    ): HealthSyncResult? {
        var pendingUpserts = mutableListOf<HealthConnectRun>()
        var pendingDeletes = mutableListOf<String>()
        var pendingPayload: HealthConnectRequestPayload? = null

        fun terminal(): HealthSyncResult =
            if (cursor.markNeedsAttentionIfCurrent(connection, credentialState)) {
                HealthSyncResult.NeedsAttention
            } else {
                HealthSyncResult.Retryable
            }

        fun deliverPending(): HealthSyncResult? {
            val payload = pendingPayload ?: return null
            if (!cursor.isCurrent(connection, credentialState)) return HealthSyncResult.Retryable
            return when (send(credential, payload)) {
                HealthConnectApiResult.Accepted -> {
                    pendingUpserts = mutableListOf()
                    pendingDeletes = mutableListOf()
                    pendingPayload = null
                    null
                }
                HealthConnectApiResult.Unauthorized -> HealthSyncResult.PairingRequired
                HealthConnectApiResult.Retryable -> HealthSyncResult.Retryable
                HealthConnectApiResult.Rejected -> terminal()
            }
        }

        fun add(upsert: HealthConnectRun?, delete: String?): HealthSyncResult? {
            val candidateUpserts = if (upsert == null) pendingUpserts else (pendingUpserts + upsert)
            val candidateDeletes = if (delete == null) pendingDeletes else (pendingDeletes + delete)
            val candidate = when (
                val serialized = HealthConnectPayloadSerializer.serialize(candidateUpserts, candidateDeletes)
            ) {
                is HealthConnectPayloadResult.Prepared -> serialized.payload
                HealthConnectPayloadResult.Invalid -> return terminal()
            }
            if (
                candidate.changeCount <= MAX_CHANGES_PER_REQUEST &&
                candidate.bytes.size <= MAX_HEALTH_CONNECT_PAYLOAD_BYTES
            ) {
                pendingUpserts = candidateUpserts.toMutableList()
                pendingDeletes = candidateDeletes.toMutableList()
                pendingPayload = candidate
                return null
            }
            if (pendingPayload != null) {
                deliverPending()?.let { return it }
                return add(upsert, delete)
            }
            return terminal()
        }

        for (upsert in upserts) add(upsert, null)?.let { return it }
        for (delete in deletes) add(null, delete)?.let { return it }
        return deliverPending()
    }

    fun sync(connection: ServerConnection, credentialState: AndroidCredentialState): HealthSyncResult {
        return try {
            syncProvider(connection, credentialState)
        } catch (_: SecurityException) {
            HealthSyncResult.PermissionRequired
        } catch (_: Exception) {
            HealthSyncResult.Retryable
        }
    }

    private fun syncProvider(
        connection: ServerConnection,
        credentialState: AndroidCredentialState,
    ): HealthSyncResult {
        if (!cursor.isCurrent(connection, credentialState)) return HealthSyncResult.Retryable
        when (gateway.availability()) {
            HealthConnectAvailability.Unavailable -> return HealthSyncResult.Unavailable
            HealthConnectAvailability.UpdateRequired -> return HealthSyncResult.UpdateRequired
            HealthConnectAvailability.Available -> Unit
        }
        val credential = credentialState.credential ?: return HealthSyncResult.PairingRequired
        if (!gateway.hasPermissions()) return HealthSyncResult.PermissionRequired
        val savedCursor = cursor.load()?.takeIf {
            it.origin == connection.origin && it.deviceId == credential.deviceId &&
                it.credentialGeneration == credentialState.generation
                && it.importGeneration == credential.importGeneration
        }
        var resetAttempted = false
        var current = savedCursor ?: run {
            // Capture first: anything written while the initial 30-day read is in progress is
            // recovered by the following change-log pass rather than silently missed.
            val initialToken = gateway.newChangesToken()
            val initial = gateway.initialRuns(Instant.now().minus(30, ChronoUnit.DAYS))
            sendBatches(connection, credentialState, credential, initial, emptyList())?.let { return it }
            val firstCursor = HealthConnectCursor(
                connection.origin, credential.deviceId, credentialState.generation,
                credential.importGeneration, initialToken,
            )
            if (!cursor.saveIfCurrent(firstCursor, connection, credentialState)) {
                return HealthSyncResult.Retryable
            }
            firstCursor
        }
        while (true) {
            val batch = gateway.changes(current.token)
            if (batch.expired) {
                if (resetAttempted) return HealthSyncResult.Retryable
                resetAttempted = true
                if (!cursor.clearIfCurrent(connection, credentialState)) return HealthSyncResult.Retryable
                val resetToken = gateway.newChangesToken()
                val initial = gateway.initialRuns(Instant.now().minus(30, ChronoUnit.DAYS))
                sendBatches(connection, credentialState, credential, initial, emptyList())?.let { return it }
                current = current.copy(token = resetToken)
                if (!cursor.saveIfCurrent(current, connection, credentialState)) {
                    return HealthSyncResult.Retryable
                }
                continue
            }
            // Metric records have no session foreign key. Re-reading the bounded initial window
            // is intentionally conservative: it turns metric-only edits into an idempotent
            // session upsert instead of silently leaving a stale summary.
            if (batch.metricChanged) {
                val initial = gateway.initialRuns(Instant.now().minus(30, ChronoUnit.DAYS))
                sendBatches(connection, credentialState, credential, initial, emptyList())?.let { return it }
            }
            sendBatches(connection, credentialState, credential, batch.upserts, batch.deletes)?.let { return it }
            current = current.copy(token = batch.nextToken)
            if (!cursor.saveIfCurrent(current, connection, credentialState)) {
                return HealthSyncResult.Retryable
            }
            if (!batch.hasMore) break
        }
        if (!cursor.clearNeedsAttentionIfCurrent(connection, credentialState)) {
            return HealthSyncResult.Retryable
        }
        return HealthSyncResult.Synced
    }

    private companion object {
        const val MAX_CHANGES_PER_REQUEST = 100
    }
}

internal object HealthConnectBackgroundPolicy {
    fun supported(featureStatus: Int): Boolean =
        featureStatus == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
}
