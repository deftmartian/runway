package dev.deftmartian.runway

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Process-wide coordination for Android-owned import state.
 *
 * The short read/write lock keeps Storage Access Framework identity preferences coherent. The
 * suspendable import-data boundary below covers long parsing/provider calls and Room mutations.
 */
internal object AndroidStateCoordinator {
    private val lifecycleLock = ReentrantReadWriteLock(true)
    private val importDataMutex = Mutex()

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
        importDataMutex.lock()
        return try {
            block()
        } finally {
            importDataMutex.unlock()
        }
    }
}
