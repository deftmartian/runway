package dev.deftmartian.runway.data.importing

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.xml.parsers.SAXParserFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Bounded, raw-byte-free local GPX intake. The caller owns persistence and review decisions. */
object LocalGpxIntake {
    const val MAX_INPUT_BYTES = 10 * 1024 * 1024
    const val MAX_TRACK_POINTS = 20_000
    const val MAX_RETAINED_POINTS = 600
    private const val MAX_DURATION_SECONDS = 24 * 60 * 60
    private const val MAX_DISTANCE_METERS = 100_000.0

    fun parse(input: InputStream): LocalGpxActivity {
        val digest = MessageDigest.getInstance("SHA-256")
        val bounded = ByteQuotaInputStream(BufferedInputStream(input), MAX_INPUT_BYTES.toLong())
        val stream = DigestInputStream(bounded, digest)
        val parserInput = DoctypeRejectingInputStream(stream)
        try {
            val parsed = GpxSaxHandler().also { handler ->
                val factory = SAXParserFactory.newInstance().apply {
                    isNamespaceAware = true
                    // The input filter below is the portable enforcement; these are defence in depth.
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                    runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                }
                factory.newSAXParser().xmlReader.apply {
                    contentHandler = handler
                    entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External entities are not supported.") }
                    parse(InputSource(parserInput).apply { encoding = "UTF-8" })
                }
            }
            if (!parsed.seenGpx) fail("File is not a GPX document.")
            if (parsed.route.size < 2) fail("GPX file does not contain enough track points.")
            val startedAt = parsed.route.first().epochMillis
            val duration = max(1, ((parsed.route.last().epochMillis - startedAt) / 1_000).toInt())
            if (duration > MAX_DURATION_SECONDS) fail("GPX activity duration is outside the supported running range.")
            if (parsed.distance > MAX_DISTANCE_METERS) fail("GPX activity distance is outside the supported running range.")
            val averageHeartRate = parsed.heartRate.takeIf(List<MetricPoint>::isNotEmpty)?.let { weightedAverage(it).roundToInt() }
            if (averageHeartRate != null && averageHeartRate !in 30..240) fail("GPX heart-rate values are outside the supported range.")
            val digestHex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return LocalGpxActivity(
                sourceSha256 = digestHex,
                dedupeMaterial = sha256("runway-local-gpx-v1\u0000$digestHex"),
                startedAtEpochMillis = startedAt,
                durationSeconds = duration,
                distanceMeters = parsed.distance.roundToInt(),
                elevationGainMeters = parsed.elevationGain.roundToInt(),
                pointCount = parsed.route.size,
                averageHeartRate = averageHeartRate,
                maxHeartRate = parsed.heartRate.maxOfOrNull { it.value }?.roundToInt(),
                averageCadence = parsed.cadence.takeIf(List<MetricPoint>::isNotEmpty)?.let { weightedAverage(it).roundToInt() },
                averageSpeedMetersPerSecond = parsed.speed.takeIf(List<MetricPoint>::isNotEmpty)?.let { round(it = weightedAverage(it), decimals = 2) },
                route = retainRoute(parsed.route, startedAt),
                heartRate = retainHeartRate(parsed.heartRate, startedAt),
            )
        } catch (error: LocalGpxImportException) {
            throw error
        } catch (_: ByteQuotaExceededException) {
            throw LocalGpxImportException(
                reason = LocalGpxImportFailure.TOO_LARGE,
                message = "GPX file is too large for import.",
            )
        } catch (_: DoctypeRejectedException) {
            throw LocalGpxImportException("GPX files with document type declarations or entities are not supported.")
        } catch (error: SAXException) {
            if (error.cause is ByteQuotaExceededException) {
                throw LocalGpxImportException("GPX file is too large for import.")
            }
            if (error.message.orEmpty().contains("doctype", ignoreCase = true) || error.message.orEmpty().contains("entity", ignoreCase = true)) {
                throw LocalGpxImportException("GPX files with document type declarations or entities are not supported.")
            }
            throw LocalGpxImportException("GPX file contains malformed XML.")
        } catch (_: Exception) {
            throw LocalGpxImportException("GPX file contains malformed XML.")
        } finally {
            parserInput.close()
        }
    }

    private fun retainRoute(points: List<RoutePoint>, start: Long): List<LocalRoutePoint> =
        retainIndexes(points.size).map { index ->
            val point = points[index]
            LocalRoutePoint(
                latitudeE6 = (point.latitude * 1_000_000).roundToInt(),
                longitudeE6 = (point.longitude * 1_000_000).roundToInt(),
                elapsedSeconds = max(0, ((point.epochMillis - start) / 1_000).toInt()),
                segmentIndex = point.segment,
                speedMetersPerSecond = point.speed ?: derivedSpeed(points, index),
            )
        }

    private fun retainHeartRate(points: List<MetricPoint>, start: Long): List<LocalHeartRatePoint> {
        if (points.isEmpty()) return emptyList()
        val peak = points.indices.maxByOrNull { points[it].value } ?: 0
        return retainIndexes(points.size, setOf(peak)).map { index ->
            val point = points[index]
            LocalHeartRatePoint(
                max(0, ((point.epochMillis - start) / 1_000).toInt()),
                point.value.roundToInt(),
            )
        }
    }

    private fun retainIndexes(size: Int, required: Set<Int> = emptySet()): List<Int> {
        if (size <= MAX_RETAINED_POINTS) return (0 until size).toList()
        val indexes = sortedSetOf(0, size - 1).apply {
            addAll(required.filter { it in 0 until size })
        }
        val available = MAX_RETAINED_POINTS - indexes.size
        for (slot in 1..available) {
            val candidate = (slot * (size - 1).toDouble() / (available + 1)).roundToInt()
            if (!indexes.add(candidate)) {
                var offset = 1
                while (indexes.size < MAX_RETAINED_POINTS) {
                    val before = candidate - offset
                    val after = candidate + offset
                    if (before > 0 && indexes.add(before)) break
                    if (after < size - 1 && indexes.add(after)) break
                    offset += 1
                }
            }
        }
        return indexes.toList()
    }

    private fun weightedAverage(points: List<MetricPoint>): Double {
        if (points.size == 1) return points.first().value
        var weighted = 0.0; var seconds = 0.0
        points.zipWithNext().forEach { (first, second) ->
            if (first.segment == second.segment) {
                val delta = max(0.0, (second.epochMillis - first.epochMillis) / 1_000.0)
                weighted += ((first.value + second.value) / 2) * delta; seconds += delta
            }
        }
        return if (seconds > 0) weighted / seconds else points.first().value
    }

    private fun derivedSpeed(points: List<RoutePoint>, index: Int): Double? {
        val current = points[index]; val next = points.getOrNull(index + 1) ?: return null
        if (current.segment != next.segment) return null
        val seconds = (next.epochMillis - current.epochMillis) / 1_000.0
        return if (seconds <= 0) null else round(haversine(current.latitude, current.longitude, next.latitude, next.longitude) / seconds, 2)
    }

    private fun coordinate(value: String?, min: Double, max: Double): Double = number(value, "coordinates").also {
        if (it !in min..max) fail("GPX track point has invalid coordinates.")
    }
    private fun metric(value: String?, name: String, min: Double, max: Double): Double = number(value, name).also {
        if (it !in min..max) fail("GPX $name values are outside the supported range.")
    }
    private fun number(value: String?, name: String): Double = value?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite)
        ?: fail("GPX $name contains an invalid numeric value.")
    private fun instant(value: String?): Long = runCatching { java.time.Instant.parse(value?.trim()).toEpochMilli() }
        .getOrElse { fail("GPX track point is missing a valid timestamp.") }
    private fun localName(value: String?): String = value.orEmpty().substringAfterLast(':').lowercase()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun round(it: Double, decimals: Int): Double { val scale = Math.pow(10.0, decimals.toDouble()); return (it * scale).roundToInt() / scale }
    private fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = min(1.0, max(0.0, sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)))
        return 6_371_000 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
    private fun fail(message: String): Nothing = throw LocalGpxImportException(message)

    private class GpxSaxHandler : DefaultHandler() {
        var seenGpx = false
        var segment = -1
        var current: PointBuilder? = null
        var previousTime: Long? = null
        val route = mutableListOf<RoutePoint>()
        val heartRate = mutableListOf<MetricPoint>()
        val cadence = mutableListOf<MetricPoint>()
        val speed = mutableListOf<MetricPoint>()
        var pointCount = 0
        var distance = 0.0
        var elevationGain = 0.0
        private var previousInSegment: PointSnapshot? = null
        private var textName: String? = null
        private var text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes) {
            val name = elementName(localName, qName)
            when (name) {
                "gpx" -> seenGpx = true
                "trkseg" -> {
                    segment += 1
                    previousInSegment = null
                }
                "trkpt" -> {
                    if (segment < 0) fail("GPX track points must be inside a track segment.")
                    if (++pointCount > MAX_TRACK_POINTS) fail("GPX file has too many track points for import.")
                    current = PointBuilder(
                        latitude = coordinate(attributes.getValue("lat"), -90.0, 90.0),
                        longitude = coordinate(attributes.getValue("lon"), -180.0, 180.0),
                        segment = segment,
                    )
                }
                "time", "ele", "hr", "heartrate", "cad", "cadence", "speed" -> if (current != null) {
                    textName = name
                    text = StringBuilder()
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (textName != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = elementName(localName, qName)
            current?.let { point ->
                if (name == textName) {
                    when (name) {
                        "time" -> point.time = instant(text.toString())
                        "ele" -> point.elevation = number(text.toString(), "elevation")
                        "hr", "heartrate" -> point.heartRate = metric(text.toString(), "heart rate", 30.0, 260.0)
                        "cad", "cadence" -> point.cadence = metric(text.toString(), "cadence", 0.0, Double.MAX_VALUE)
                        "speed" -> point.speed = metric(text.toString(), "speed", 0.0, Double.MAX_VALUE)
                    }
                    textName = null
                }
            }
            if (name != "trkpt") return
            val point = current ?: fail("GPX file contains an invalid track point.")
            val timestamp = point.time ?: fail("GPX track point is missing a valid timestamp.")
            if (previousTime != null && timestamp < previousTime!!) fail("GPX track point timestamps must be chronological.")
            previousTime = timestamp
            val snapshot = PointSnapshot(point.latitude, point.longitude, point.elevation, timestamp, point.segment)
            previousInSegment?.let { prior ->
                distance += haversine(prior.latitude, prior.longitude, snapshot.latitude, snapshot.longitude)
                if (prior.elevation != null && snapshot.elevation != null) elevationGain += max(0.0, snapshot.elevation - prior.elevation)
            }
            previousInSegment = snapshot
            route += RoutePoint(point.latitude, point.longitude, timestamp, point.segment, point.speed)
            point.heartRate?.let { heartRate += MetricPoint(timestamp, point.segment, it) }
            point.cadence?.let { cadence += MetricPoint(timestamp, point.segment, it) }
            point.speed?.let { speed += MetricPoint(timestamp, point.segment, it) }
            current = null
            textName = null
        }

        private fun elementName(localName: String?, qName: String?): String =
            LocalGpxIntake.localName(localName?.takeIf { it.isNotBlank() } ?: qName)
    }

    private data class PointBuilder(val latitude: Double, val longitude: Double, val segment: Int, var time: Long? = null, var elevation: Double? = null, var heartRate: Double? = null, var cadence: Double? = null, var speed: Double? = null)
    private data class PointSnapshot(val latitude: Double, val longitude: Double, val elevation: Double?, val epochMillis: Long, val segment: Int)
    private data class RoutePoint(val latitude: Double, val longitude: Double, val epochMillis: Long, val segment: Int, val speed: Double?)
    private data class MetricPoint(val epochMillis: Long, val segment: Int, val value: Double)
}

data class LocalGpxActivity(
    val sourceSha256: String, val dedupeMaterial: String, val startedAtEpochMillis: Long,
    val durationSeconds: Int, val distanceMeters: Int, val elevationGainMeters: Int, val pointCount: Int,
    val averageHeartRate: Int?, val maxHeartRate: Int?, val averageCadence: Int?, val averageSpeedMetersPerSecond: Double?,
    val route: List<LocalRoutePoint>, val heartRate: List<LocalHeartRatePoint>,
)
data class LocalRoutePoint(val latitudeE6: Int, val longitudeE6: Int, val elapsedSeconds: Int, val segmentIndex: Int, val speedMetersPerSecond: Double?)
data class LocalHeartRatePoint(val elapsedSeconds: Int, val bpm: Int)
enum class LocalGpxImportFailure { INVALID_FILE, TOO_LARGE }

class LocalGpxImportException(
    message: String,
    val reason: LocalGpxImportFailure = LocalGpxImportFailure.INVALID_FILE,
) : IllegalArgumentException(message)

private class ByteQuotaInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var read = 0L
    override fun read(): Int { val value = super.read(); if (value >= 0) count(1); return value }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { val count = super.read(buffer, offset, length); if (count > 0) this.count(count.toLong()); return count }
    private fun count(delta: Long) { read += delta; if (read > limit) throw ByteQuotaExceededException() }
}
private class ByteQuotaExceededException : RuntimeException()

/** Rejects DTD/entity declarations before a platform SAX implementation can resolve them. */
private class DoctypeRejectingInputStream(input: InputStream) : FilterInputStream(input) {
    private val recent = StringBuilder()
    private var inComment = false
    private var inCdata = false
    override fun read(): Int = super.read().also { if (it >= 0) inspect(it) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { count ->
            if (count > 0) for (index in offset until offset + count) inspect(buffer[index].toInt() and 0xff)
        }
    private fun inspect(value: Int) {
        recent.append(value.toChar().uppercaseChar())
        if (recent.length > 9) recent.deleteCharAt(0)
        if (inComment) {
            if (recent.endsWith("-->")) { inComment = false; recent.clear() }
            return
        }
        if (inCdata) {
            if (recent.endsWith("]]>")) {
                inCdata = false
                recent.clear()
            }
            return
        }
        if (recent.endsWith("<!--")) { inComment = true; recent.clear(); return }
        if (recent.endsWith("<![CDATA[")) { inCdata = true; recent.clear(); return }
        if (recent.endsWith("<!DOCTYPE") || recent.endsWith("<!ENTITY")) throw DoctypeRejectedException()
    }
}
private class DoctypeRejectedException : RuntimeException()
