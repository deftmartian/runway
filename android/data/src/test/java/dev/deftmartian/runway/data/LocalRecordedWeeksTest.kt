package dev.deftmartian.runway.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRecordedWeeksTest {
    private val zone = ZoneId.of("America/Halifax")
    private val today = LocalDate.of(2026, 11, 4) // Wednesday

    @Test
    fun `weeks run Monday to Sunday oldest first and keep empty weeks`() {
        val weeks = recordedWeeks(
            listOf(
                run("mon", LocalDate.of(2026, 11, 2), 5_000, 1_800),
                run("sun-before", LocalDate.of(2026, 11, 1), 8_000, 3_000),
            ),
            zone,
            today.toEpochDay(),
            weeks = 3,
        )

        assertEquals(
            listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2)),
            weeks.map { LocalDate.ofEpochDay(it.startEpochDay) },
        )
        assertEquals(listOf(0, 1, 1), weeks.map { it.runs })
        assertEquals(8_000, weeks[1].distanceMeters)
        assertEquals(5_000, weeks[2].distanceMeters)
    }

    @Test
    fun `pace and heart rate use only runs that recorded them`() {
        val week = recordedWeeks(
            listOf(
                run("a", LocalDate.of(2026, 11, 2), 5_000, 1_500, heartRate = 150),
                run("b", LocalDate.of(2026, 11, 3), 10_000, 3_300, heartRate = 140),
                run("time-only", LocalDate.of(2026, 11, 4), null, 1_200),
                run("review", LocalDate.of(2026, 11, 4), 20_000, 6_000, state = "review"),
            ),
            zone,
            today.toEpochDay(),
            weeks = 1,
        ).single()

        assertEquals(3, week.runs)
        assertEquals(15_000, week.distanceMeters)
        assertEquals(6_000, week.durationSeconds)
        assertEquals(10_000, week.longestRunMeters)
        assertEquals(320.0, week.paceSecondsPerKilometre!!, 0.001) // (1500 + 3300) s / 15 km
        assertEquals(143, week.averageHeartRateBpm) // (150*1500 + 140*3300) / 4800
    }

    @Test
    fun `a week without paced or heart-rate runs leaves them empty`() {
        val week = recordedWeeks(
            listOf(run("time-only", LocalDate.of(2026, 11, 3), null, 1_800)),
            zone,
            today.toEpochDay(),
            weeks = 1,
        ).single()
        assertNull(week.paceSecondsPerKilometre)
        assertNull(week.averageHeartRateBpm)
        assertNull(week.longestRunMeters)
    }

    private fun run(
        id: String,
        date: LocalDate,
        distance: Int?,
        duration: Int?,
        heartRate: Int? = null,
        state: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ) = ActivityEntity(
        activityId = id,
        source = "manual",
        sourceRecordId = null,
        reviewState = state,
        occurredAtEpochMillis = date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
        durationSeconds = duration,
        distanceMeters = distance,
        averageHeartRateBpm = heartRate,
        averageCadenceSpm = null,
        linkedWorkoutId = null,
        acceptedAtEpochMillis = null,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )
}
