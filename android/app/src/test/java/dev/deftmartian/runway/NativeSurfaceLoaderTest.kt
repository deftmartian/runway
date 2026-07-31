package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalAboutReadModel
import dev.deftmartian.runway.data.LocalCalendarReadModel
import dev.deftmartian.runway.data.LocalHistoryReadModel
import dev.deftmartian.runway.data.LocalInboxReadModel
import dev.deftmartian.runway.data.LocalLoadReadModel
import dev.deftmartian.runway.data.LocalPlanHistoryReadModel
import dev.deftmartian.runway.data.LocalPlanPhase
import dev.deftmartian.runway.data.LocalPlanState
import dev.deftmartian.runway.data.LocalSettingsReadModel
import dev.deftmartian.runway.data.LocalStatsReadModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.YearMonth

class NativeSurfaceLoaderTest {
    @Test
    fun `new profile without an active plan redirects to populated setup`() = runBlocking {
        val reads = FakeSurfaceReads(
            calendar = emptyCalendar(activePlanId = null, profileExists = false),
        )
        val result = loader(reads).load(request(NativeDestination.Calendar))

        assertEquals(NativeDestination.Setup, result.surface.destination)
        assertEquals(1, reads.settingsReads)
    }

    @Test
    fun `established profile without an active plan keeps the calendar`() = runBlocking {
        val reads = FakeSurfaceReads(
            calendar = emptyCalendar(activePlanId = null, profileExists = true),
        )
        val result = loader(reads).load(request(NativeDestination.Calendar))

        assertEquals(NativeDestination.Calendar, result.surface.destination)
        assertEquals(0, reads.settingsReads)
    }

    @Test
    fun `history detail reuses supplied detail when no saved plan id exists`() = runBlocking {
        val previous = NativeSurface.HistoryDetail(
            NativeHistoryDetailPayload(onboardingRequired = false, detail = null),
        )
        val result = loader(FakeSurfaceReads()).load(
            request(
                destination = NativeDestination.HistoryDetail,
                historyPlanId = null,
                previousHistoryDetail = previous,
            ),
        )

        assertSame(previous, result.surface)
    }

    @Test
    fun `history detail returns the loaded history cache and honors its limit`() = runBlocking {
        val reads = FakeSurfaceReads(
            history = history("plan-1"),
        )
        val result = loader(reads).load(
            request(
                destination = NativeDestination.HistoryDetail,
                historyPlanId = "plan-1",
                historyPlanLimit = 123,
            ),
        )

        assertEquals(NativeDestination.HistoryDetail, result.surface.destination)
        assertSame(reads.history, result.history)
        assertEquals(123, reads.lastHistoryLimit)
    }

    private fun loader(reads: FakeSurfaceReads) =
        NativeSurfaceLoader(reads, Dispatchers.Unconfined)

    private fun request(
        destination: NativeDestination,
        historyPlanId: String? = null,
        previousHistoryDetail: NativeSurface.HistoryDetail? = null,
        historyPlanLimit: Int = 50,
    ) = SurfaceLoadRequest(
        destination = destination,
        calendarMonth = YearMonth.of(2026, 7),
        calendarMonthWasSelected = true,
        historyPlanId = historyPlanId,
        previousHistoryDetail = previousHistoryDetail,
        historyPlanLimit = historyPlanLimit,
        inboxActivityLimit = 50,
    )

    private fun emptyCalendar(
        activePlanId: String?,
        profileExists: Boolean = activePlanId != null,
    ) = LocalCalendarReadModel(
        fromEpochDay = 0,
        throughEpochDay = 30,
        activePlanId = activePlanId,
        profileExists = profileExists,
        pendingDecisionCount = 0,
        pendingDecisionCountIsExact = true,
        hasMoreActivities = false,
        days = emptyList(),
    )

    private fun history(planId: String) = LocalHistoryReadModel(
        plans = listOf(
            LocalPlanHistoryReadModel(
                planId = planId,
                goalId = "goal-1",
                goalTitle = "5K plan",
                state = LocalPlanState.ACTIVE,
                phase = LocalPlanPhase.DISTANCE,
                startEpochDay = 1,
                endEpochDay = 30,
                completedAtEpochMillis = null,
                archivedAtEpochMillis = null,
                plannedRuns = 3,
                completedRuns = 0,
                actual = LocalLoadReadModel(0, 0),
                lifecycle = emptyList(),
            ),
        ),
        unlinkedActivities = emptyList(),
        hasMorePlans = false,
        hasMoreActivities = false,
    )

    private inner class FakeSurfaceReads(
        val calendar: LocalCalendarReadModel = emptyCalendar(activePlanId = "plan-1"),
        val history: LocalHistoryReadModel = history("plan-1"),
    ) : NativeSurfaceReads {
        var settingsReads = 0
        var lastHistoryLimit: Int? = null

        override suspend fun profileTimeZone(): String = "America/Halifax"
        override suspend fun calendar(month: YearMonth): LocalCalendarReadModel = calendar
        override suspend fun inbox(limit: Int) = LocalInboxReadModel(
            reviewCount = 0,
            reviewCountIsExact = true,
            hasMore = false,
            activities = emptyList(),
        )
        override suspend fun stats() = LocalStatsReadModel(
            weeks = emptyList(),
            profileExists = false,
            recordedTotals = emptyList(),
            totalRuns = 0,
            totalDistanceMeters = 0,
            totalDurationSeconds = 0,
            longestRunMeters = null,
            weightedPaceSecondsPerKilometre = null,
            durationWeightedHeartRateBpm = null,
            isComplete = true,
        )
        override suspend fun history(limit: Int): LocalHistoryReadModel {
            lastHistoryLimit = limit
            return history
        }
        override suspend fun settings(): LocalSettingsReadModel {
            settingsReads += 1
            return LocalSettingsReadModel(
                profile = null,
                activePlan = null,
                about = LocalAboutReadModel(versionName = "test", buildRevision = "test"),
            )
        }
        override fun folderImportStatus() = NativeImportConnection.NotConnected
        override suspend fun healthConnectStatus(pendingChangeCount: Int) =
            NativeImportConnection.NotConnected
    }
}
