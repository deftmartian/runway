package dev.deftmartian.runway.data.importing

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGpxIntakeTest {
    @Test
    fun `intake retains bounded route heart rate and deterministic dedupe material`() {
        val points = (0..650).joinToString("") { index ->
            val timestamp = "2026-05-14T12:${(index / 60).toString().padStart(2, '0')}:${(index % 60).toString().padStart(2, '0')}Z"
            "<trkpt lat=\"45.${index.toString().padStart(6, '0')}\" lon=\"-63\"><ele>${10 + index}</ele><time>$timestamp</time><extensions><gpxtpx:hr>${120 + index % 40}</gpxtpx:hr><gpxtpx:cad>82</gpxtpx:cad></extensions></trkpt>"
        }
        val xml = "<gpx xmlns:gpxtpx=\"https://www.garmin.com/xmlschemas/TrackPointExtension/v1\"><trk><trkseg>$points</trkseg></trk></gpx>"

        val parsed = LocalGpxIntake.parse(ByteArrayInputStream(xml.toByteArray()))

        assertEquals(651, parsed.pointCount)
        assertEquals(600, parsed.route.size)
        assertEquals(600, parsed.heartRate.size)
        assertEquals(0, parsed.route.first().elapsedSeconds)
        assertEquals(650, parsed.route.last().elapsedSeconds)
        assertEquals(45_000_000, parsed.route.first().latitudeE6)
        assertEquals(-63_000_000, parsed.route.first().longitudeE6)
        assertTrue(parsed.elevationGainMeters > 0)
        assertTrue(parsed.averageHeartRate != null)
        assertTrue(parsed.averageCadence != null)
        assertTrue(parsed.sourceSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(parsed.dedupeMaterial, LocalGpxIntake.parse(ByteArrayInputStream(xml.toByteArray())).dedupeMaterial)
    }

    @Test
    fun `intake never bridges GPX track segments`() {
        val xml = """<gpx><trk>
            <trkseg><trkpt lat="45" lon="-63"><time>2026-05-14T12:00:00Z</time></trkpt><trkpt lat="45.001" lon="-63"><time>2026-05-14T12:01:00Z</time></trkpt></trkseg>
            <trkseg><trkpt lat="46" lon="-64"><time>2026-05-14T12:02:00Z</time></trkpt><trkpt lat="46.001" lon="-64"><time>2026-05-14T12:03:00Z</time></trkpt></trkseg>
        </trk></gpx>"""

        val parsed = LocalGpxIntake.parse(ByteArrayInputStream(xml.toByteArray()))

        assertTrue(parsed.distanceMeters in 200..300)
        assertEquals(null, parsed.route[1].speedMetersPerSecond)
    }

    @Test
    fun `intake rejects malformed privacy hostile and invalid track input`() {
        assertFailure("<!DOCTYPE gpx><gpx/>", "gpx")
        assertFailure("<!DOCTYPE gpx [<!ENTITY route SYSTEM \"file:///private-route\">]><gpx>&route;</gpx>", "gpx")
        assertFailure("<gpx><trk><trkseg><trkpt lat=\"145\" lon=\"-63\"><time>2026-05-14T12:00:00Z</time></trkpt></trkseg></trk></gpx>", "coordinates")
        assertFailure("<gpx><trk><trkseg><trkpt lat=\"45\" lon=\"-63\"><time>2026-05-14T12:01:00Z</time></trkpt><trkpt lat=\"45\" lon=\"-63\"><time>2026-05-14T12:00:00Z</time></trkpt></trkseg></trk></gpx>", "chronological")
    }

    @Test
    fun `dedupe material changes when the source bytes change`() {
        val first = "<gpx><trk><trkseg><trkpt lat=\"45\" lon=\"-63\"><time>2026-05-14T12:00:00Z</time></trkpt><trkpt lat=\"45.001\" lon=\"-63\"><time>2026-05-14T12:01:00Z</time></trkpt></trkseg></trk></gpx>"
        val second = first.replace("45.001", "45.002")
        assertNotEquals(
            LocalGpxIntake.parse(ByteArrayInputStream(first.toByteArray())).dedupeMaterial,
            LocalGpxIntake.parse(ByteArrayInputStream(second.toByteArray())).dedupeMaterial,
        )
    }

    private fun assertFailure(xml: String, expected: String) {
        val error = runCatching { LocalGpxIntake.parse(ByteArrayInputStream(xml.toByteArray())) }.exceptionOrNull()
        assertTrue(error is LocalGpxImportException)
        assertTrue(error?.message.orEmpty().contains(expected, ignoreCase = true))
    }
}
