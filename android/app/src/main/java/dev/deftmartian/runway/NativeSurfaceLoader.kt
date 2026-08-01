package dev.deftmartian.runway

import android.app.Application
import dev.deftmartian.runway.data.LocalCalendarReadModel
import dev.deftmartian.runway.data.LocalHistoryReadModel
import dev.deftmartian.runway.data.LocalInboxReadModel
import dev.deftmartian.runway.data.LocalInboxPagingCursor
import dev.deftmartian.runway.data.LocalSettingsReadModel
import dev.deftmartian.runway.data.LocalStatsReadModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

internal data class SurfaceLoadRequest(
    val destination: NativeDestination,
    val calendarMonth: YearMonth,
    val calendarMonthWasSelected: Boolean,
    val historyPlanId: String?,
    val previousHistoryDetail: NativeSurface.HistoryDetail?,
    val historyPlanOffset: Int,
    val historyActivityOffset: Int,
    val previousHistory: LocalHistoryReadModel?,
    val inboxPagingCursor: LocalInboxPagingCursor = LocalInboxPagingCursor(),
)

internal data class SurfaceLoadResult(
    val surface: NativeSurface,
    val calendarMonth: YearMonth,
    val history: LocalHistoryReadModel? = null,
)

/**
 * Loads one native surface without owning navigation, saved state, generations, or UI state.
 *
 * All Room reads and Android capability probes remain behind the injected IO dispatcher. The
 * caller owns stale-result rejection and decides whether to publish the returned surface.
 */
internal class NativeSurfaceLoader(
    private val reads: NativeSurfaceReads,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        application: Application,
        services: RunwayServices,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(RunwayNativeSurfaceReads(application, services), ioDispatcher)

    suspend fun load(request: SurfaceLoadRequest): SurfaceLoadResult =
        withContext(ioDispatcher) {
            when (request.destination) {
                NativeDestination.Calendar ->
                    reads.ensureRoutineHorizon(request.calendarMonth.atEndOfMonth().toEpochDay())
                NativeDestination.Inbox, NativeDestination.Stats -> reads.ensureRoutineHorizon()
                else -> Unit
            }
            when (request.destination) {
                NativeDestination.Calendar -> loadCalendar(request)
                NativeDestination.Inbox -> SurfaceLoadResult(
                    NativeSurface.Inbox(reads.inbox(request.inboxPagingCursor).toNativeInbox()),
                    request.calendarMonth,
                )
                NativeDestination.Stats -> SurfaceLoadResult(
                    NativeSurface.Stats(reads.stats().toNativeStats()),
                    request.calendarMonth,
                )
                NativeDestination.History -> {
                    val page = reads.history(
                        request.historyPlanOffset,
                        request.historyActivityOffset,
                    )
                    val history = request.previousHistory
                        ?.takeIf {
                            request.historyPlanOffset > 0 ||
                                request.historyActivityOffset > 0
                        }
                        ?.merge(page)
                        ?: page
                    SurfaceLoadResult(
                        NativeSurface.History(history.toNativeHistory()),
                        request.calendarMonth,
                        history,
                    )
                }
                NativeDestination.Settings -> {
                    val settings = reads.settings()
                    SurfaceLoadResult(
                        NativeSurface.Settings(
                            settings.toNativeSettingsState().copy(
                                folderImport = reads.folderImportStatus(),
                                healthConnectImport = reads.healthConnectStatus(
                                    settings.pendingHealthConnect.size,
                                ),
                            ),
                        ),
                        request.calendarMonth,
                    )
                }
                NativeDestination.Setup -> SurfaceLoadResult(
                    NativeSurface.Setup(reads.settings().toNativeOnboardingPayload()),
                    request.calendarMonth,
                )
                NativeDestination.HistoryDetail -> loadHistoryDetail(request)
            }
        }

    fun cachedHistoryDetail(
        history: LocalHistoryReadModel,
        planId: String,
    ): NativeSurface.HistoryDetail? = history.plans
        .firstOrNull { it.planId == planId }
        ?.let { item ->
            NativeSurface.HistoryDetail(
                item.toNativeHistoryDetail(
                    timeZone = history.timeZone,
                    todayEpochDay = history.todayEpochDay,
                ),
            )
        }

    private suspend fun loadCalendar(request: SurfaceLoadRequest): SurfaceLoadResult {
        val month = if (request.calendarMonthWasSelected) {
            request.calendarMonth
        } else {
            val zone = reads.profileTimeZone()
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.systemDefault()
            YearMonth.now(zone)
        }
        val payload = reads.calendar(month).toNativeCalendar()
        val surface = if (payload.onboardingRequired == true) {
            NativeSurface.Setup(reads.settings().toNativeOnboardingPayload())
        } else {
            NativeSurface.Calendar(payload)
        }
        return SurfaceLoadResult(surface, month)
    }

    private suspend fun loadHistoryDetail(
        request: SurfaceLoadRequest,
    ): SurfaceLoadResult {
        val planId = request.historyPlanId
        if (planId == null) {
            return SurfaceLoadResult(
                request.previousHistoryDetail
                    ?: error("A plan record must be selected before opening detail."),
                request.calendarMonth,
            )
        }
        val history = reads.historyPlan(planId)
            ?: error("That local plan record is no longer available.")
        val item = history.plans.firstOrNull { it.planId == planId }
            ?: error("That local plan record is no longer available.")
        return SurfaceLoadResult(
            NativeSurface.HistoryDetail(
                item.toNativeHistoryDetail(
                    timeZone = history.timeZone,
                    todayEpochDay = history.todayEpochDay,
                ),
            ),
            request.calendarMonth,
            history,
        )
    }
}

internal interface NativeSurfaceReads {
    /** Idempotently materializes dated slots for an active open-ended routine before any surface reads it. */
    suspend fun ensureRoutineHorizon(requestedThroughEpochDay: Long? = null) = Unit
    suspend fun profileTimeZone(): String?
    suspend fun calendar(month: YearMonth): LocalCalendarReadModel
    suspend fun inbox(cursor: LocalInboxPagingCursor): LocalInboxReadModel
    suspend fun stats(): LocalStatsReadModel
    suspend fun history(planOffset: Int, activityOffset: Int): LocalHistoryReadModel
    suspend fun historyPlan(planId: String): LocalHistoryReadModel?
    suspend fun settings(): LocalSettingsReadModel
    fun folderImportStatus(): NativeImportConnection
    suspend fun healthConnectStatus(pendingChangeCount: Int): NativeImportConnection
}

private class RunwayNativeSurfaceReads(
    application: Application,
    private val services: RunwayServices,
) : NativeSurfaceReads {
    private val context = application.applicationContext

    override suspend fun ensureRoutineHorizon(requestedThroughEpochDay: Long?) {
        val plan = services.trainingContext.activePlan()
            ?.takeIf { it.phaseType == "routine" && it.state == "active" }
            ?: return
        val profile = services.trainingContext.profile() ?: return
        val zone = runCatching { ZoneId.of(profile.timeZone) }.getOrNull()
            ?: error("The active routine needs a valid training time zone.")
        val now = Instant.now()
        val defaultThrough = now.atZone(zone).toLocalDate().plusWeeks(8).toEpochDay()
        val through = maxOf(defaultThrough, requestedThroughEpochDay ?: defaultThrough)
        when (services.routines.ensureHorizon(plan.planId, through, now.toEpochMilli())) {
            dev.deftmartian.runway.data.LocalRoutineHorizonResult.Rejected ->
                error("The active routine schedule could not be extended safely.")
            else -> Unit
        }
    }

    override suspend fun profileTimeZone(): String? =
        services.trainingContext.profile()?.timeZone

    override suspend fun calendar(month: YearMonth): LocalCalendarReadModel =
        services.surfaces.calendar(
            month.atDay(1).toEpochDay(),
            month.atEndOfMonth().toEpochDay(),
        )

    override suspend fun inbox(cursor: LocalInboxPagingCursor): LocalInboxReadModel = services.surfaces.inbox(cursor)

    override suspend fun stats(): LocalStatsReadModel = services.surfaces.stats()

    override suspend fun history(
        planOffset: Int,
        activityOffset: Int,
    ): LocalHistoryReadModel = services.surfaces.history(planOffset, activityOffset)

    override suspend fun historyPlan(planId: String): LocalHistoryReadModel? =
        services.surfaces.historyPlan(planId)

    override suspend fun settings(): LocalSettingsReadModel = services.surfaces.settings()

    override fun folderImportStatus(): NativeImportConnection =
        when (TreeAccessStore(context).currentState()) {
            is TreeAccessState.Connected -> NativeImportConnection.Connected
            is TreeAccessState.PermissionRequired -> NativeImportConnection.PermissionRequired
            TreeAccessState.Missing -> NativeImportConnection.NotConnected
        }

    override suspend fun healthConnectStatus(pendingChangeCount: Int): NativeImportConnection {
        val gateway = AndroidHealthConnectGateway(context)
        return when (gateway.availability()) {
            HealthConnectAvailability.Unavailable -> NativeImportConnection.Unavailable
            HealthConnectAvailability.UpdateRequired ->
                NativeImportConnection.Attention("Health Connect needs an update")
            HealthConnectAvailability.Available -> {
                val hasPermissions = try {
                    gateway.hasPermissions()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
                if (hasPermissions) {
                    if (pendingChangeCount == 0) {
                        NativeImportConnection.Connected
                    } else {
                        NativeImportConnection.Attention(
                            "$pendingChangeCount source " +
                                "change${if (pendingChangeCount == 1) "" else "s"} need review",
                        )
                    }
                } else {
                    NativeImportConnection.PermissionRequired
                }
            }
        }
    }
}

private fun LocalHistoryReadModel.merge(
    page: LocalHistoryReadModel,
): LocalHistoryReadModel = page.copy(
    plans = (plans + page.plans).distinctBy { it.planId },
    unlinkedActivities = (unlinkedActivities + page.unlinkedActivities)
        .distinctBy { it.activityId },
)
