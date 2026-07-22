package com.deftmartian.runway

import org.junit.Assert.assertFalse
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
}
