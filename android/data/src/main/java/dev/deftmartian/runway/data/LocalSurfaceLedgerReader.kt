package dev.deftmartian.runway.data


interface LocalSurfaceLedgerReader {
    suspend fun calendar(
        fromEpochDay: Long,
        throughEpochDay: Long,
        limits: LocalSurfaceReadLimits,
    ): LocalCalendarLedgerSlice

    suspend fun inbox(limits: LocalSurfaceReadLimits): LocalInboxLedgerSlice
    suspend fun inboxPage(
        limits: LocalSurfaceReadLimits,
        cursor: LocalInboxPagingCursor,
    ): LocalInboxLedgerSlice = inbox(limits)
    suspend fun stats(limits: LocalSurfaceReadLimits): LocalStatsLedgerSlice
    suspend fun history(
        limits: LocalSurfaceReadLimits,
        planOffset: Int = 0,
        activityOffset: Int = 0,
    ): LocalHistoryLedgerSlice
    suspend fun historyPlan(
        planId: String,
        limits: LocalSurfaceReadLimits,
    ): LocalHistoryLedgerSlice?
    suspend fun settings(limits: LocalSurfaceReadLimits): LocalSettingsLedgerSlice
    suspend fun activityEvidence(
        activityId: String,
        limits: LocalSurfaceReadLimits,
    ): LocalActivityLedgerSlice?
}

