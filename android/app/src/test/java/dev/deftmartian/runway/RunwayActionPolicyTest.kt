package dev.deftmartian.runway

import dev.deftmartian.runway.data.RetentionRepairNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunwayActionPolicyTest {
    @Test
    fun `local result never converts coroutine cancellation into a user failure`() {
        val cancellation = CancellationException("test cancellation")

        val thrown = try {
            runBlocking {
                localResult<Unit> { throw cancellation }
            }
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `local result captures ordinary action failure`() = runBlocking {
        val result = localResult<Unit> { error("storage failed") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `surface completion preserves an action already in progress`() {
        val previous = RunwayUiState.Ready(
            surface = NativeSurface.Calendar(null),
            loading = true,
            actionPending = false,
        )
        val current = previous.copy(actionPending = true)

        val merged = mergeLoadedSurface(
            current = current,
            previous = previous,
            surface = NativeSurface.Calendar(null),
        )

        assertEquals(true, merged.actionPending)
        assertEquals(false, merged.loading)
    }

    @Test
    fun `activity evidence may publish only for the active surface generation`() {
        assertTrue(
            activityEvidenceRequestIsCurrent(
                requestGeneration = 4,
                requestDestination = NativeDestination.History,
                currentGeneration = 4,
                currentDestination = NativeDestination.History,
            ),
        )
        assertEquals(
            false,
            activityEvidenceRequestIsCurrent(
                requestGeneration = 4,
                requestDestination = NativeDestination.History,
                currentGeneration = 5,
                currentDestination = NativeDestination.History,
            ),
        )
        assertEquals(
            false,
            activityEvidenceRequestIsCurrent(
                requestGeneration = 4,
                requestDestination = NativeDestination.History,
                currentGeneration = 4,
                currentDestination = NativeDestination.Stats,
            ),
        )
    }

    @Test
    fun `starting a surface reload releases evidence requests from the old generation`() {
        val ready =
            RunwayUiState.Ready(
                surface = NativeSurface.History(null),
                loading = false,
                activityEvidenceLoading = setOf("activity-1"),
            )

        val reloading = ready.withoutActiveEvidenceRequests()

        assertEquals(emptySet<String>(), reloading.activityEvidenceLoading)
        assertTrue(reloading === reloading.withoutActiveEvidenceRequests())
    }

    @Test
    fun `readable export reports when its bounded sections were truncated`() {
        assertEquals(
            "Readable training history exported.",
            trainingExportMessage(emptySet()),
        )
        assertTrue(trainingExportMessage(setOf("activities")).contains("limited to 2,000 rows"))
        assertTrue(trainingExportMessage(setOf("activities")).contains("Use Backup"))
    }

    @Test
    fun `retention repair notice states preserved data consequence and next action`() {
        val message = RetentionRepairNotice(
            routeModeRestored = true,
            heartRateModeRestored = true,
        ).message()

        assertTrue(message.contains("private route and heart-rate data"))
        assertTrue(message.contains("restored to Keep private"))
        assertTrue(message.contains("Review Privacy in Settings"))
        assertTrue(
            RetentionRepairNotice(
                routeModeRestored = true,
                heartRateModeRestored = false,
            ).settingsMessage().contains("kept the data"),
        )
    }

    @Test
    fun `retention repair remains on settings until explicit acknowledgement`() {
        val repair = RetentionRepairNotice(
            routeModeRestored = true,
            heartRateModeRestored = false,
        )
        val settings = NativeSurface.Settings(NativeSettingsState())

        assertEquals(
            repair,
            (settings.withRetentionRepair(repair) as NativeSurface.Settings)
                .payload
                ?.retentionRepair,
        )
        assertEquals(
            null,
            (settings.withRetentionRepair(null) as NativeSurface.Settings)
                .payload
                ?.retentionRepair,
        )
    }
}
