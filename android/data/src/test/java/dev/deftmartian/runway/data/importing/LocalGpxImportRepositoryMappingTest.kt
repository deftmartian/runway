package dev.deftmartian.runway.data.importing

import dev.deftmartian.runway.data.isFutureLocalActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LocalGpxImportRepositoryMappingTest {
    @Test
    fun `mapping creates a pending review GPX activity with bounded metadata`() {
        val entity = localGpxActivityEntity(
            activity(),
            nowEpochMillis = 1234,
            retainRoute = true,
            retainHeartRate = true,
        )

        assertEquals("gpx", entity.source)
        assertEquals(activity().dedupeMaterial, entity.sourceRecordId)
        assertEquals("review", entity.reviewState)
        assertEquals(1234, entity.createdAtEpochMillis)
        assertEquals(2, entity.routePointCount)
        assertEquals(1, entity.heartRatePointCount)
        assertTrue(entity.routeTraceRetained)
        assertTrue(entity.heartRateSeriesRetained)
        assertEquals(null, entity.maxSpeedMetersPerSecond)
    }

    @Test
    fun `discard modes retain totals but omit route and heart-rate evidence`() {
        val entity = localGpxActivityEntity(
            activity(),
            nowEpochMillis = 1234,
            retainRoute = false,
            retainHeartRate = false,
        )

        assertEquals(500, entity.distanceMeters)
        assertEquals(60, entity.durationSeconds)
        assertEquals(0, entity.routePointCount)
        assertFalse(entity.routeTraceRetained)
        assertTrue(entity.routeStartEndRedacted)
        assertEquals(null, entity.averageHeartRateBpm)
        assertEquals(null, entity.maxHeartRateBpm)
        assertEquals(0, entity.heartRatePointCount)
        assertFalse(entity.heartRateSeriesRetained)
    }

    @Test
    fun `sample mapping retains parser ordering in E6 microdegrees`() {
        val parsed = activity()
        val route = localGpxRouteSamples("activity", parsed)
        val heartRate = localGpxHeartRateSamples("activity", parsed)

        assertEquals(2, route.size)
        assertEquals(45_000_000, route.first().latitudeE6)
        assertEquals(-63_000_000, route.first().longitudeE6)
        assertEquals(0, route.first().ordinal)
        assertEquals(1, route.last().ordinal)
        assertEquals(1, heartRate.size)
        assertEquals(30, heartRate.single().elapsedSeconds)
        assertEquals(140, heartRate.single().beatsPerMinute)
    }

    @Test
    fun `activity ID is stable valid UUID version five shape and content scoped`() {
        val first = localGpxActivityId("a".repeat(64))
        val second = localGpxActivityId("a".repeat(64))
        val other = localGpxActivityId("b".repeat(64))

        assertEquals(first, second)
        assertFalse(first == other)
        assertTrue(first.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }

    @Test
    fun `future rejection follows the configured local date rather than UTC`() {
        val now = Instant.parse("2026-07-30T12:00:00Z").toEpochMilli()
        val laterUtcButSameHalifaxDay = Instant.parse("2026-07-30T23:30:00Z").toEpochMilli()
        val nextHalifaxDay = Instant.parse("2026-07-31T03:01:00Z").toEpochMilli()
        val halifax = ZoneId.of("America/Halifax")

        assertFalse(isFutureLocalActivity(laterUtcButSameHalifaxDay, now, halifax))
        assertTrue(isFutureLocalActivity(nextHalifaxDay, now, halifax))
    }

    private fun activity() = LocalGpxActivity(
        sourceSha256 = "c".repeat(64),
        dedupeMaterial = "a".repeat(64),
        startedAtEpochMillis = 1_000,
        durationSeconds = 60,
        distanceMeters = 500,
        elevationGainMeters = 4,
        pointCount = 2,
        averageHeartRate = 140,
        maxHeartRate = 150,
        averageCadence = 170,
        averageSpeedMetersPerSecond = 8.3,
        route = listOf(
            LocalRoutePoint(45_000_000, -63_000_000, 0, 0, 8.1),
            LocalRoutePoint(45_000_100, -63_000_100, 60, 0, 8.5),
        ),
        heartRate = listOf(LocalHeartRatePoint(30, 140)),
    )
}
