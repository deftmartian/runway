package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalRunReminderCandidate
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunReminderSchedulerTest {
    private val halifax = ZoneId.of("America/Halifax")

    @Test
    fun `a passed reminder time skips todays candidate`() {
        val now = Instant.parse("2026-08-01T15:00:00Z")
        val today = now.atZone(halifax).toLocalDate().toEpochDay()

        assertNull(
            nextFutureCandidate(
                LocalRunReminderCandidate(today, listOf("run-1")),
                minuteOfDay = 8 * 60,
                now = now,
                zone = halifax,
            ),
        )
    }

    @Test
    fun `a future planned day retains its local wall clock reminder`() {
        val now = Instant.parse("2026-08-01T15:00:00Z")
        val tomorrow = now.atZone(halifax).toLocalDate().plusDays(1).toEpochDay()
        val candidate = LocalRunReminderCandidate(tomorrow, listOf("run-1", "run-2"))

        assertEquals(candidate, nextFutureCandidate(candidate, 7 * 60 + 30, now, halifax))
        assertEquals(
            7 * 60 + 30,
            runReminderTrigger(tomorrow, 7 * 60 + 30, halifax)
                .atZone(halifax)
                .toLocalTime()
                .hour * 60 +
                runReminderTrigger(tomorrow, 7 * 60 + 30, halifax)
                    .atZone(halifax)
                    .toLocalTime()
                    .minute,
        )
    }

    @Test
    fun `a daylight saving gap resolves forward without changing the local day`() {
        val springForward = java.time.LocalDate.parse("2026-03-08").toEpochDay()
        val resolved = runReminderTrigger(springForward, 2 * 60 + 30, halifax).atZone(halifax)

        assertEquals("2026-03-08", resolved.toLocalDate().toString())
        assertEquals("03:30", resolved.toLocalTime().toString())
    }
}
