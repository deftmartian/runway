package dev.deftmartian.runway

/** Pure restoration rule for the small native destination graph. */
internal fun restoredNativeDestination(
    savedDestination: String?,
    savedHistoryPlanId: String?,
): NativeDestination {
    val destination = savedDestination
        ?.let { name -> runCatching { NativeDestination.valueOf(name) }.getOrNull() }
        ?: NativeDestination.Calendar
    return if (
        destination == NativeDestination.HistoryDetail &&
        savedHistoryPlanId.isNullOrBlank()
    ) {
        NativeDestination.History
    } else {
        destination
    }
}
