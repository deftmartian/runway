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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import dev.deftmartian.runway.data.healthconnect.HealthConnectObservation
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectHeartRatePoint
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectPersistenceResult
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectRoutePoint
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectRunningType
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.SortedSet
import kotlin.math.roundToInt

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
    val runningType: LocalHealthConnectRunningType = LocalHealthConnectRunningType.Running,
    val distanceMeters: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val averageSpeedMetersPerSecond: Double? = null,
    val averageCadenceSpm: Double? = null,
    val elevationGainMeters: Double? = null,
    val maxHeartRateBpm: Double? = null,
    val heartRateSamples: List<HealthConnectHeartRateSample> = emptyList(),
    val heartRateSourceSampleCount: Int = heartRateSamples.size,
    val routePoints: List<HealthConnectRoutePoint> = emptyList(),
    val routeSourcePointCount: Int = routePoints.size,
    val routeObserved: Boolean = true,
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
    val sourceChangeCount: Int = upserts.size + deletes.size,
)

/** A narrow seam keeps Android Health Connect IPC out of the sync decision and its unit tests. */
internal interface HealthConnectGateway {
    fun availability(): HealthConnectAvailability
    suspend fun hasPermissions(): Boolean
    suspend fun initialRuns(since: Instant): List<HealthConnectRun>
    suspend fun newChangesToken(): String
    suspend fun changes(token: String): HealthConnectBatch
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

    override suspend fun hasPermissions(): Boolean =
        client().permissionController.getGrantedPermissions().containsAll(HEALTH_CONNECT_PERMISSIONS)

    suspend fun hasBackgroundPermission(): Boolean =
        client().permissionController.getGrantedPermissions().contains(HEALTH_CONNECT_BACKGROUND_PERMISSION)

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

    override suspend fun initialRuns(since: Instant): List<HealthConnectRun> {
        val sessions = readAllRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(since, Instant.now()),
            ),
            maximumRecords = MAX_HEALTH_CONNECT_SESSIONS_PER_WINDOW,
        )
        return enrichRunningSessions(sessions, MAX_HEALTH_CONNECT_SESSIONS_PER_WINDOW)
    }

    override suspend fun newChangesToken(): String =
        client().getChangesToken(ChangesTokenRequest(CHANGE_RECORD_TYPES))

    override suspend fun changes(token: String): HealthConnectBatch {
        val response = client().getChanges(token)
        if (response.changes.size > MAX_HEALTH_CONNECT_CHANGES_PER_PAGE) {
            throw HealthConnectSourceLimitException(
                "Health Connect returned too many changes in one page.",
            )
        }
        val changedSessions = response.changes.mapNotNull { change ->
            val record = (change as? androidx.health.connect.client.changes.UpsertionChange)
                ?.record as? ExerciseSessionRecord
            record
        }
        return HealthConnectBatch(
            upserts = enrichRunningSessions(
                changedSessions,
                MAX_HEALTH_CONNECT_SESSIONS_PER_CHANGE_PAGE,
            ),
            deletes = response.changes.mapNotNull { change ->
                (change as? androidx.health.connect.client.changes.DeletionChange)?.recordId
            },
            nextToken = response.nextChangesToken,
            hasMore = response.hasMore,
            expired = response.changesTokenExpired,
            // 1.1 DeletionChange exposes only recordId, not record type. Treat every deletion as
            // potentially metric-affecting and re-read the bounded session window. We still send
            // every deletion so real session deletes are preserved; an unknown metric deletion is
            // a harmless unmatched source deletion rather than stale metrics.
            metricChanged = response.changes.any { change ->
                change is androidx.health.connect.client.changes.DeletionChange ||
                    ((change as? androidx.health.connect.client.changes.UpsertionChange)?.record
                        ?.let { it !is ExerciseSessionRecord } == true)
            },
            sourceChangeCount = response.changes.size,
        )
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    /**
     * Health Connect paginates every record type. Every query has both record and page caps, and
     * metric types are read once per bounded session window rather than once per session.
     */
    private suspend fun <T : Record> readAllRecords(
        request: ReadRecordsRequest<T>,
        maximumRecords: Int = MAX_HEALTH_CONNECT_METRIC_RECORDS_PER_WINDOW,
    ): List<T> =
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
        }, maximumRecords = maximumRecords)

    private suspend fun enrichRunningSessions(
        records: List<ExerciseSessionRecord>,
        maximumSessions: Int,
    ): List<HealthConnectRun> {
        val accepted = records.filter { HealthConnectRunningPolicy.accepts(it.exerciseType) }
        if (accepted.size > maximumSessions) {
            throw HealthConnectSourceLimitException(
                "Health Connect running-session count exceeded its bounded source limit.",
            )
        }
        return sessionWindows(accepted).flatMap { sessions ->
            val metrics = readMetricWindow(sessions)
            sessions.mapNotNull { asRunningRun(it, metrics) }
        }
    }

    private suspend fun readMetricWindow(
        sessions: List<ExerciseSessionRecord>,
    ): HealthConnectMetricWindow {
        check(sessions.isNotEmpty())
        val range = TimeRangeFilter.between(
            sessions.minOf(ExerciseSessionRecord::startTime),
            sessions.maxOf(ExerciseSessionRecord::endTime),
        )
        val origins = sessions.mapTo(linkedSetOf()) {
            DataOrigin(it.metadata.dataOrigin.packageName)
        }
        return HealthConnectMetricWindow(
            distances = readAllRecords(
                ReadRecordsRequest(
                    DistanceRecord::class,
                    timeRangeFilter = range,
                    dataOriginFilter = origins,
                ),
            ),
            heartRates = readAllRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    timeRangeFilter = range,
                    dataOriginFilter = origins,
                ),
            ),
            speeds = readAllRecords(
                ReadRecordsRequest(
                    SpeedRecord::class,
                    timeRangeFilter = range,
                    dataOriginFilter = origins,
                ),
            ),
            cadence = readAllRecords(
                ReadRecordsRequest(
                    StepsCadenceRecord::class,
                    timeRangeFilter = range,
                    dataOriginFilter = origins,
                ),
            ),
            elevation = readAllRecords(
                ReadRecordsRequest(
                    ElevationGainedRecord::class,
                    timeRangeFilter = range,
                    dataOriginFilter = origins,
                ),
            ),
        )
    }

    private fun sessionWindows(
        records: List<ExerciseSessionRecord>,
    ): List<List<ExerciseSessionRecord>> {
        val windows = mutableListOf<MutableList<ExerciseSessionRecord>>()
        for (record in records.sortedBy(ExerciseSessionRecord::startTime)) {
            val current = windows.lastOrNull()
            val startsNewWindow = current == null ||
                current.size >= MAX_HEALTH_CONNECT_SESSIONS_PER_ENRICHMENT_WINDOW ||
                record.endTime.isAfter(
                    current.first().startTime.plus(
                        MAX_HEALTH_CONNECT_ENRICHMENT_WINDOW_DAYS,
                        ChronoUnit.DAYS,
                    ),
                )
            if (startsNewWindow) {
                windows += mutableListOf(record)
            } else {
                requireNotNull(current) += record
            }
        }
        return windows
    }

    private fun asRunningRun(
        record: ExerciseSessionRecord,
        metrics: HealthConnectMetricWindow,
    ): HealthConnectRun? {
        val sourcePackage = record.metadata.dataOrigin.packageName
        val distances = metrics.distances.asSequence()
            .filter { it.metadata.dataOrigin.packageName == sourcePackage }
            .filter { it.startTime < record.endTime && it.endTime > record.startTime }
            .toList()
        val distanceMeters = distances.sumOf { it.distance.inMeters }.takeIf { it > 0.0 } ?: return null
        val heartRateSamples = metrics.heartRates.asSequence()
            .filter { it.metadata.dataOrigin.packageName == sourcePackage }
            .filter { it.startTime < record.endTime && it.endTime > record.startTime }
            .flatMap { it.samples.asSequence() }
            .filter { it.time >= record.startTime && it.time <= record.endTime }
            .boundedHealthConnectSamples("heart-rate")
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
        val speeds = metrics.speeds.asSequence()
            .filter { it.metadata.dataOrigin.packageName == sourcePackage }
            .filter { it.startTime < record.endTime && it.endTime > record.startTime }
            .flatMap { it.samples.asSequence() }
            .filter { it.time >= record.startTime && it.time <= record.endTime }
            .boundedHealthConnectSamples("speed")
            .sortedBy { it.time }
        val cadence = metrics.cadence.asSequence()
            .filter { it.metadata.dataOrigin.packageName == sourcePackage }
            .filter { it.startTime < record.endTime && it.endTime > record.startTime }
            .flatMap { it.samples.asSequence() }
            .filter { it.time >= record.startTime && it.time <= record.endTime }
            .boundedHealthConnectSamples("cadence")
        val elevation = metrics.elevation.asSequence()
            .filter { it.metadata.dataOrigin.packageName == sourcePackage }
            .filter { it.startTime < record.endTime && it.endTime > record.startTime }
            .sumOf { it.elevation.inMeters }
        var routeObserved = includeRoutes
        val route = if (includeRoutes) {
            when (val routeResult = record.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> routeResult.exerciseRoute.route
                is ExerciseRouteResult.ConsentRequired -> {
                    routeOverrides[record.metadata.id]?.route ?: run {
                        routeConsentRecordId = record.metadata.id
                        routeObserved = false
                        emptyList()
                    }
                }
                else -> emptyList()
            }
        } else {
            emptyList()
        }
        if (route.size > MAX_RAW_HEALTH_CONNECT_ROUTE_POINTS_PER_RUN) {
            throw HealthConnectSourceLimitException(
                "Health Connect route point count exceeded its bounded source limit.",
            )
        }
        val retainedRoute = if (route.size <= MAX_RETAINED_HEALTH_CONNECT_SAMPLES_PER_RUN) {
            route
        } else {
            route.selectRepresentative(
                MAX_RETAINED_HEALTH_CONNECT_SAMPLES_PER_RUN,
                sortedSetOf(0, route.lastIndex),
            )
        }
        val routePoints = retainedRoute.map { point ->
            HealthConnectRoutePoint(
                elapsedSeconds = ((point.time.toEpochMilli() - record.startTime.toEpochMilli()) / 1_000)
                    .toInt().coerceAtLeast(0),
                latitudeE6 = (point.latitude * 1_000_000).toInt(),
                longitudeE6 = (point.longitude * 1_000_000).toInt(),
                speedMetersPerSecond = nearestSpeedMetersPerSecond(speeds, point.time),
            )
        }
        return HealthConnectRun(
            id = record.metadata.id,
            startEpochMs = record.startTime.toEpochMilli(),
            endEpochMs = record.endTime.toEpochMilli(),
            sourcePackage = sourcePackage,
            runningType = if (record.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL) {
                LocalHealthConnectRunningType.TreadmillRunning
            } else {
                LocalHealthConnectRunningType.Running
            },
            distanceMeters = distanceMeters,
            averageHeartRateBpm = heartRateSamples.map { it.beatsPerMinute.toDouble() }.average()
                .takeIf { !it.isNaN() },
            maxHeartRateBpm = heartRateSamples.maxOfOrNull { it.beatsPerMinute.toDouble() },
            averageSpeedMetersPerSecond = speeds.map { it.speed.inMetersPerSecond }.average()
                .takeIf { !it.isNaN() },
            averageCadenceSpm = cadence.map { it.rate }.average().takeIf { !it.isNaN() },
            elevationGainMeters = elevation.takeIf { it > 0.0 },
            heartRateSamples = boundedHeartRateSamples,
            heartRateSourceSampleCount = heartRateSamples.size,
            routePoints = routePoints,
            routeSourcePointCount = route.size,
            routeObserved = routeObserved,
        )
    }

    private companion object {
        val CHANGE_RECORD_TYPES = setOf(
            ExerciseSessionRecord::class, DistanceRecord::class, HeartRateRecord::class,
            SpeedRecord::class, StepsCadenceRecord::class, ElevationGainedRecord::class,
        )
    }
}

private data class HealthConnectMetricWindow(
    val distances: List<DistanceRecord>,
    val heartRates: List<HeartRateRecord>,
    val speeds: List<SpeedRecord>,
    val cadence: List<StepsCadenceRecord>,
    val elevation: List<ElevationGainedRecord>,
)

internal data class HealthConnectPage<T>(val records: List<T>, val nextPageToken: String?)

internal class HealthConnectSourceLimitException(message: String) : IllegalStateException(message)

/**
 * Bounded, token-driven collection keeps provider pagination explicit and testable outside Android
 * IPC. The cap matches the largest source count the upload contract can represent.
 */
internal suspend fun <T> collectHealthConnectPages(
    readPage: suspend (pageToken: String?) -> HealthConnectPage<T>,
    maximumRecords: Int = MAX_HEALTH_CONNECT_METRIC_RECORDS_PER_WINDOW,
): List<T> {
    require(maximumRecords > 0)
    val records = mutableListOf<T>()
    val seenPageTokens = mutableSetOf<String>()
    var pageToken: String? = null
    var pages = 0
    do {
        pages += 1
        if (pages > MAX_HEALTH_CONNECT_PAGES_PER_QUERY) {
            throw HealthConnectSourceLimitException(
                "Health Connect query exceeded its bounded page limit.",
            )
        }
        val page = readPage(pageToken)
        if (page.records.size > maximumRecords - records.size) {
            throw HealthConnectSourceLimitException(
                "Health Connect record query exceeded its bounded source limit.",
            )
        }
        records += page.records
        pageToken = page.nextPageToken
        if (pageToken != null && !seenPageTokens.add(requireNotNull(pageToken))) {
            throw HealthConnectSourceLimitException(
                "Health Connect repeated a pagination token.",
            )
        }
    } while (pageToken != null)
    return records
}

internal const val MAX_HEALTH_CONNECT_SESSIONS_PER_WINDOW = 512
internal const val MAX_HEALTH_CONNECT_SESSIONS_PER_CHANGE_PAGE = 256
internal const val MAX_HEALTH_CONNECT_CHANGES_PER_PAGE = 5_000
internal const val MAX_HEALTH_CONNECT_CHANGE_PAGES_PER_SYNC = 128
internal const val MAX_HEALTH_CONNECT_CHANGES_PER_SYNC = 20_000
internal const val MAX_HEALTH_CONNECT_METRIC_RECORDS_PER_WINDOW = 10_000
internal const val MAX_HEALTH_CONNECT_PAGES_PER_QUERY = 512
internal const val MAX_HEALTH_CONNECT_SESSIONS_PER_ENRICHMENT_WINDOW = 32
internal const val MAX_HEALTH_CONNECT_ENRICHMENT_WINDOW_DAYS = 7L
internal const val MAX_RAW_HEALTH_CONNECT_SAMPLES_PER_RUN = 50_000
internal const val MAX_RAW_HEALTH_CONNECT_ROUTE_POINTS_PER_RUN = 50_000
internal const val MAX_RETAINED_HEALTH_CONNECT_SAMPLES_PER_RUN = 600

internal object HealthConnectRunningPolicy {
    fun accepts(exerciseType: Int): Boolean = exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
        exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
}

internal fun downsampleHeartRate(samples: List<HealthConnectHeartRateSample>): List<HealthConnectHeartRateSample> {
    if (samples.size <= MAX_RETAINED_HEALTH_CONNECT_SAMPLES_PER_RUN) return samples
    val required = sortedSetOf(0, samples.lastIndex, samples.indices.maxBy { samples[it].bpm })
    return samples.selectRepresentative(MAX_RETAINED_HEALTH_CONNECT_SAMPLES_PER_RUN, required)
}

private fun <T> Sequence<T>.boundedHealthConnectSamples(label: String): List<T> {
    val samples = take(MAX_RAW_HEALTH_CONNECT_SAMPLES_PER_RUN + 1).toList()
    if (samples.size > MAX_RAW_HEALTH_CONNECT_SAMPLES_PER_RUN) {
        throw HealthConnectSourceLimitException(
            "Health Connect $label sample count exceeded its bounded source limit.",
        )
    }
    return samples
}

private fun <T> List<T>.selectRepresentative(limit: Int, required: SortedSet<Int>): List<T> {
    for (slot in 0 until limit) {
        if (required.size >= limit) break
        required += ((slot.toLong() * lastIndex) / (limit - 1)).toInt()
    }
    return required.take(limit).map(::get)
}

private fun nearestSpeedMetersPerSecond(
    samples: List<SpeedRecord.Sample>,
    target: Instant,
): Double? {
    if (samples.isEmpty()) return null
    val targetMillis = target.toEpochMilli()
    var low = 0
    var high = samples.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (samples[middle].time.toEpochMilli() < targetMillis) {
            low = middle + 1
        } else {
            high = middle
        }
    }
    val right = low.coerceAtMost(samples.lastIndex)
    val left = (low - 1).coerceAtLeast(0)
    return listOf(left, right)
        .distinct()
        .minBy { index ->
            kotlin.math.abs(samples[index].time.toEpochMilli() - targetMillis)
        }
        .let { samples[it].speed.inMetersPerSecond }
}

/** A local cursor is the only durable acquisition state. */
internal data class HealthConnectCursor(val token: String)

internal interface HealthConnectCursorRepository {
    fun load(): HealthConnectCursor?
    fun save(cursor: HealthConnectCursor)
    fun clear()
    fun needsAttention(): Boolean
    fun markNeedsAttention()
    fun clearNeedsAttention()
}

internal class HealthConnectCursorStore(context: Context) : HealthConnectCursorRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        "runway_health_connect", Context.MODE_PRIVATE,
    )

    override fun load(): HealthConnectCursor? = preferences.getString(KEY_TOKEN, null)?.let(::HealthConnectCursor)

    override fun save(cursor: HealthConnectCursor) {
        preferences.edit(commit = true) { putString(KEY_TOKEN, cursor.token) }
    }

    override fun clear() {
        preferences.edit(commit = true) { remove(KEY_TOKEN) }
    }

    override fun needsAttention(): Boolean = preferences.getBoolean(KEY_ATTENTION, false)

    override fun markNeedsAttention() {
        preferences.edit(commit = true) { putBoolean(KEY_ATTENTION, true) }
    }

    override fun clearNeedsAttention() {
        preferences.edit(commit = true) { remove(KEY_ATTENTION) }
    }

    fun clearAll() {
        preferences.edit(commit = true) {
            remove(KEY_TOKEN)
            remove(KEY_ATTENTION)
        }
    }

    private companion object {
        const val KEY_TOKEN = "change_token"
        const val KEY_ATTENTION = "needs_attention"
    }
}

internal sealed interface HealthSyncResult {
    data object Synced : HealthSyncResult
    data object PermissionRequired : HealthSyncResult
    data object Unavailable : HealthSyncResult
    data object UpdateRequired : HealthSyncResult
    data object Retryable : HealthSyncResult
    data object NeedsAttention : HealthSyncResult
}

/**
 * Acquires running observations from Health Connect and persists each locally. The cursor advances
 * only after every observation in a page is committed, so reruns are intentionally idempotent.
 */
internal class HealthConnectSyncCoordinator(
    private val gateway: HealthConnectGateway,
    private val cursor: HealthConnectCursorRepository,
    private val reconcile: suspend (provider: String, observation: HealthConnectObservation) -> LocalHealthConnectPersistenceResult,
    private val now: () -> Instant = Instant::now,
    private val maximumChangePages: Int = MAX_HEALTH_CONNECT_CHANGE_PAGES_PER_SYNC,
    private val maximumChanges: Int = MAX_HEALTH_CONNECT_CHANGES_PER_SYNC,
) {
    init {
        require(maximumChangePages > 0)
        require(maximumChanges > 0)
    }

    suspend fun sync(): HealthSyncResult = try {
        when (gateway.availability()) {
            HealthConnectAvailability.Unavailable -> HealthSyncResult.Unavailable
            HealthConnectAvailability.UpdateRequired -> HealthSyncResult.UpdateRequired
            HealthConnectAvailability.Available -> syncAvailable()
        }
    } catch (_: SecurityException) {
        HealthSyncResult.PermissionRequired
    } catch (error: CancellationException) {
        throw error
    } catch (_: HealthConnectSourceLimitException) {
        cursor.markNeedsAttention()
        HealthSyncResult.NeedsAttention
    } catch (_: Exception) {
        HealthSyncResult.Retryable
    }

    private suspend fun syncAvailable(): HealthSyncResult {
        if (!gateway.hasPermissions()) return HealthSyncResult.PermissionRequired
        var resetAttempted = false
        val storedCursor = cursor.load()
        var fullWindowRefreshed = storedCursor == null
        var current = storedCursor ?: bootstrap() ?: return HealthSyncResult.NeedsAttention
        val seenChangeTokens = mutableSetOf(current.token)
        var changePages = 0
        var sourceChanges = 0
        while (true) {
            changePages += 1
            if (changePages > maximumChangePages) {
                throw HealthConnectSourceLimitException(
                    "Health Connect change log exceeded its bounded page limit.",
                )
            }
            val batch = gateway.changes(current.token)
            if (batch.expired) {
                if (resetAttempted) return HealthSyncResult.Retryable
                resetAttempted = true
                cursor.clear()
                current = bootstrap() ?: return HealthSyncResult.NeedsAttention
                seenChangeTokens.clear()
                seenChangeTokens += current.token
                changePages = 0
                sourceChanges = 0
                fullWindowRefreshed = true
                continue
            }
            if (batch.sourceChangeCount < 0 || batch.sourceChangeCount > maximumChanges - sourceChanges) {
                throw HealthConnectSourceLimitException(
                    "Health Connect change log exceeded its bounded record limit.",
                )
            }
            if (batch.hasMore && !seenChangeTokens.add(batch.nextToken)) {
                throw HealthConnectSourceLimitException(
                    "Health Connect repeated a change-log token.",
                )
            }
            sourceChanges += batch.sourceChangeCount
            // Metric records have no session foreign key. Re-read the bounded window so a metric
            // edit becomes an idempotent local session upsert rather than stale derived data.
            if (batch.metricChanged && !fullWindowRefreshed) {
                if (!persist(batch = gateway.initialRuns(now().minus(30, ChronoUnit.DAYS)))) {
                    return HealthSyncResult.NeedsAttention
                }
                fullWindowRefreshed = true
            }
            if (!persist(batch.upserts) || !persistDeletes(batch.deletes)) return HealthSyncResult.NeedsAttention
            current = HealthConnectCursor(batch.nextToken)
            cursor.save(current)
            if (!batch.hasMore) break
        }
        cursor.clearNeedsAttention()
        return HealthSyncResult.Synced
    }

    private suspend fun bootstrap(): HealthConnectCursor? {
        // Capture first: sessions written during the 30-day read are recovered by the following
        // change-log pass rather than being silently missed.
        val token = gateway.newChangesToken()
        if (!persist(gateway.initialRuns(now().minus(30, ChronoUnit.DAYS)))) return null
        return HealthConnectCursor(token).also(cursor::save)
    }

    private suspend fun persist(batch: List<HealthConnectRun>): Boolean {
        for (run in batch) {
            val result = try {
                reconcile(HEALTH_CONNECT_PROVIDER, run.toObservation())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val accepted = result is LocalHealthConnectPersistenceResult.Applied
            if (!accepted) {
                cursor.markNeedsAttention()
                return false
            }
        }
        return true
    }

    private suspend fun persistDeletes(recordIds: List<String>): Boolean {
        for (recordId in recordIds) {
            val accepted = reconcile(
                HEALTH_CONNECT_PROVIDER,
                HealthConnectObservation.Deleted(recordId, now().toEpochMilli()),
            ) is LocalHealthConnectPersistenceResult.Applied
            if (!accepted) {
                cursor.markNeedsAttention()
                return false
            }
        }
        return true
    }

    private fun HealthConnectRun.toObservation(): HealthConnectObservation.RunningUpsert {
        val durationSeconds = ((endEpochMs - startEpochMs) / 1_000).toInt()
        require(durationSeconds > 0) { "Health Connect run duration must be positive." }
        val distance = distanceMeters?.roundToInt() ?: 0
        require(distance > 0) { "Health Connect running observations require a positive distance." }
        return HealthConnectObservation.RunningUpsert(
            recordId = id,
            provider = HEALTH_CONNECT_PROVIDER,
            runningType = runningType,
            originKey = sourcePackage,
            originLabel = sourcePackage,
            startedAtEpochMillis = startEpochMs,
            durationSeconds = durationSeconds,
            distanceMeters = distance,
            averageHeartRateBpm = averageHeartRateBpm?.roundToInt(),
            maxHeartRateBpm = maxHeartRateBpm?.roundToInt(),
            // StepsCadenceRecord.Sample.rate is already steps per minute.
            averageCadenceSpm = averageCadenceSpm?.roundToInt(),
            elevationGainMeters = elevationGainMeters,
            averageSpeedMetersPerSecond = averageSpeedMetersPerSecond,
            heartRate = heartRateSamples.map { LocalHealthConnectHeartRatePoint(it.elapsedSeconds, it.bpm) },
            heartRateSourceSampleCount = heartRateSourceSampleCount,
            route = routePoints.map {
                LocalHealthConnectRoutePoint(
                    elapsedSeconds = it.elapsedSeconds,
                    latitudeE6 = it.latitudeE6,
                    longitudeE6 = it.longitudeE6,
                    speedMetersPerSecond = it.speedMetersPerSecond,
                )
            },
            routeSourcePointCount = routeSourcePointCount,
            routeObserved = routeObserved,
        )
    }

    private companion object {
        const val HEALTH_CONNECT_PROVIDER = "health_connect"
    }
}

internal object HealthConnectBackgroundPolicy {
    fun supported(featureStatus: Int): Boolean =
        featureStatus == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
}
