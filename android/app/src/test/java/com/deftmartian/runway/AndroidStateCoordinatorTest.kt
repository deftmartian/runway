package com.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidStateCoordinatorTest {
    @Test
    fun `identity mutation waits for active import work`() {
        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reader = executor.submit {
                AndroidStateCoordinator.read {
                    readerEntered.countDown()
                    assertTrue(releaseReader.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(readerEntered.await(2, TimeUnit.SECONDS))
            executor.submit {
                AndroidStateCoordinator.write { writerEntered.countDown() }
            }
            assertFalse(writerEntered.await(150, TimeUnit.MILLISECONDS))
            releaseReader.countDown()
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS))
            reader.get(2, TimeUnit.SECONDS)
        } finally {
            releaseReader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `competing identity mutation cannot enter during bundled cleanup`() {
        val identityCleared = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val competingMutationEntered = CountDownLatch(1)
        val events = mutableListOf<String>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val cleanup = executor.submit {
                AndroidStateCoordinator.write {
                    synchronized(events) { events += "credential cleared" }
                    identityCleared.countDown()
                    assertTrue(releaseCleanup.await(2, TimeUnit.SECONDS))
                    synchronized(events) { events += "imports cleared" }
                    synchronized(events) { events += "work cancelled" }
                    synchronized(events) { events += "status recorded" }
                }
            }
            assertTrue(identityCleared.await(2, TimeUnit.SECONDS))
            val competingMutation = executor.submit {
                AndroidStateCoordinator.write {
                    synchronized(events) { events += "new identity committed" }
                    competingMutationEntered.countDown()
                }
            }
            assertFalse(competingMutationEntered.await(150, TimeUnit.MILLISECONDS))
            releaseCleanup.countDown()
            cleanup.get(2, TimeUnit.SECONDS)
            competingMutation.get(2, TimeUnit.SECONDS)
            assertEquals(
                listOf(
                    "credential cleared",
                    "imports cleared",
                    "work cancelled",
                    "status recorded",
                    "new identity committed",
                ),
                synchronized(events) { events.toList() },
            )
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
        }
    }
}
