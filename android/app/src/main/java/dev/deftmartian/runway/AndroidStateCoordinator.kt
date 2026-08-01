package dev.deftmartian.runway

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Acquisition was closed by an erase or restore before this import was allowed to touch Room.
 *
 * This is cancellation rather than a data error: callers may offer a fresh user-initiated retry,
 * but must not report a corrupt file, enqueue an automatic retry, or expose a partial import.
 */
internal class ImportAcquisitionClosedException : CancellationException(
    "Local import acquisition is temporarily closed.",
)

/**
 * Process-wide coordination for Android-owned import state.
 *
 * The short read/write lock keeps Storage Access Framework identity preferences coherent. The
 * suspendable import-data boundary below covers long parsing/provider calls and Room mutations.
 */
internal object AndroidStateCoordinator {
    private val lifecycleLock = ReentrantReadWriteLock(true)
    private val importDataMutex = Mutex()
    private val destructiveImportMutex = Mutex()
    private val acquisitionStateMutex = Mutex()
    private var acquisitionClosed = false
    private var acquisitionCount = 0
    private var acquisitionsDrained = completedDeferred()

    fun <T> read(block: () -> T): T = lifecycleLock.read(block)

    fun <T> write(block: () -> T): T = lifecycleLock.write(block)

    /**
     * Serializes every Android acquisition path with destructive ledger replacement/removal.
     *
     * WorkManager cancellation alone does not prove that an already-running coroutine has left a
     * Room transaction. This boundary does: erase and restore wait for an active import to finish,
     * then prevent another import from entering until the destructive operation is complete.
     */
    suspend fun <T> withImportDataBoundary(block: suspend () -> T): T {
        reserveAcquisition()
        return try {
            importDataMutex.withLock {
                // A request can reserve immediately before a destructive operation closes the
                // gate, then wait behind an active import. Do not let it start afterwards.
                ensureAcquisitionOpen()
                block()
            }
        } finally {
            releaseAcquisition()
        }
    }

    /**
     * Closes new acquisition before requesting worker cancellation, waits for every active or
     * queued acquisition to leave [withImportDataBoundary], then runs one destructive mutation.
     *
     * [closeAcquisition] must promptly cancel workers and disconnect/revoke any Android-owned
     * sources. It deliberately runs before waiting for imports: a blocked provider operation must
     * receive cancellation rather than making erase/restore wait forever behind the mutex. If the
     * suspendable close is cancelled, draining and mutation are skipped and the gate is reopened.
     *
     * [keepAcquisitionClosedAfter] is only for a terminal mutation such as replacing and closing
     * Room before an immediate process restart. Ordinary mutations must use the default so imports
     * can resume.
     */
    suspend fun <T> withDestructiveImportBoundary(
        closeAcquisition: suspend () -> Unit,
        keepAcquisitionClosedAfter: (T) -> Boolean = { false },
        mutation: suspend () -> T,
    ): T = destructiveImportMutex.withLock {
        val drained = closeAcquisitionGate()
        var keepClosed = false
        try {
            closeAcquisition()
            drained.await()
            mutation().also { keepClosed = keepAcquisitionClosedAfter(it) }
        } finally {
            // A cancellation while a suspendable source disconnect is in progress must not leave
            // future imports permanently rejected after this destructive operation has stopped.
            if (!keepClosed) withContext(NonCancellable) { reopenAcquisitionGate() }
        }
    }

    private suspend fun reserveAcquisition() {
        acquisitionStateMutex.withLock {
            if (acquisitionClosed) throw ImportAcquisitionClosedException()
            if (acquisitionCount == 0) acquisitionsDrained = CompletableDeferred()
            acquisitionCount++
        }
    }

    private suspend fun ensureAcquisitionOpen() {
        acquisitionStateMutex.withLock {
            if (acquisitionClosed) throw ImportAcquisitionClosedException()
        }
    }

    private suspend fun releaseAcquisition() {
        acquisitionStateMutex.withLock {
            check(acquisitionCount > 0) { "Import acquisition was released without a reservation." }
            acquisitionCount--
            if (acquisitionCount == 0) acquisitionsDrained.complete(Unit)
        }
    }

    private suspend fun closeAcquisitionGate(): CompletableDeferred<Unit> =
        acquisitionStateMutex.withLock {
            check(!acquisitionClosed) { "Import acquisition is already closed." }
            acquisitionClosed = true
            acquisitionsDrained
        }

    private suspend fun reopenAcquisitionGate() {
        acquisitionStateMutex.withLock {
            acquisitionClosed = false
        }
    }

    private fun completedDeferred(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { it.complete(Unit) }
}
