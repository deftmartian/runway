package dev.deftmartian.runway.data


class LocalSurfaceRepository(
    private val ledger: LocalSurfaceLedgerReader,
    private val limits: LocalSurfaceReadLimits = LocalSurfaceReadLimits(),
) {
    suspend fun calendar(fromEpochDay: Long, throughEpochDay: Long): LocalCalendarReadModel {
        require(throughEpochDay >= fromEpochDay)
        require(throughEpochDay - fromEpochDay <= 42) { "Calendar reads are limited to 43 days." }
        return LocalSurfaceMappers.calendar(ledger.calendar(fromEpochDay, throughEpochDay, limits))
    }

    suspend fun inbox(cursor: LocalInboxPagingCursor): LocalInboxReadModel = LocalSurfaceMappers.inbox(
        ledger.inboxPage(limits, cursor),
    )
    suspend fun stats(): LocalStatsReadModel = LocalSurfaceMappers.stats(ledger.stats(limits))
    suspend fun history(
        planOffset: Int = 0,
        activityOffset: Int = 0,
    ): LocalHistoryReadModel = LocalSurfaceMappers.history(
        ledger.history(limits, planOffset, activityOffset),
    )
    suspend fun historyPlan(planId: String): LocalHistoryReadModel? {
        require(planId.isNotBlank())
        return ledger.historyPlan(planId, limits)?.let(LocalSurfaceMappers::history)
    }
    suspend fun settings(): LocalSettingsReadModel = LocalSurfaceMappers.settings(ledger.settings(limits))
    suspend fun activityEvidence(activityId: String): LocalActivityReadModel? {
        require(activityId.isNotBlank())
        return ledger.activityEvidence(activityId, limits)?.let(LocalSurfaceMappers::activityEvidence)
    }
}

