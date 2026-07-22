package com.deftmartian.runway

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One process-wide boundary for the server, credential, and SAF-folder identity used by imports.
 * Reads may run together. Any identity mutation waits for active API work to finish and excludes
 * every other mutation, so a completed account or server change cannot be followed by stale work.
 */
internal object AndroidStateCoordinator {
    private val lifecycleLock = ReentrantReadWriteLock(true)

    fun <T> read(block: () -> T): T = lifecycleLock.read(block)

    fun <T> write(block: () -> T): T = lifecycleLock.write(block)
}
