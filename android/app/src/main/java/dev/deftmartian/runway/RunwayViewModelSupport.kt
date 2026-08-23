package dev.deftmartian.runway

import dev.deftmartian.runway.data.RetentionRepairNotice
import kotlinx.coroutines.CancellationException

internal suspend fun <T> localResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }


internal fun activityEvidenceRequestIsCurrent(
    requestGeneration: Long,
    requestDestination: NativeDestination,
    currentGeneration: Long,
    currentDestination: NativeDestination,
): Boolean =
    requestGeneration == currentGeneration &&
        requestDestination == currentDestination


internal fun trainingExportMessage(truncatedTables: Set<String>): String =
    if (truncatedTables.isEmpty()) {
        "Readable training history exported."
    } else {
        "Readable export created, but some large sections were limited to 2,000 rows. " +
            "Use Backup for a complete copy."
    }
