package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AndroidStateCoordinatorTest {
    @Test
    fun `folder identity write waits for an active state read`() {
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
    fun `competing folder identity write cannot enter during bundled cleanup`() {
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

    @Test
    fun `destructive data work waits for an active import critical section`() = runBlocking {
        val importEntered = CompletableDeferred<Unit>()
        val releaseImport = CompletableDeferred<Unit>()
        val eraseEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val import = async(Dispatchers.Default) {
            AndroidStateCoordinator.withImportDataBoundary {
                events += "import entered"
                importEntered.complete(Unit)
                releaseImport.await()
                events += "import finished"
            }
        }
        importEntered.await()
        val erase = async(Dispatchers.Default) {
            AndroidStateCoordinator.withImportDataBoundary {
                events += "erase entered"
                eraseEntered.complete(Unit)
            }
        }

        delay(100)
        assertFalse(eraseEntered.isCompleted)
        releaseImport.complete(Unit)
        withTimeout(2_000) {
            import.await()
            erase.await()
        }
        assertEquals(
            listOf("import entered", "import finished", "erase entered"),
            events,
        )
    }
}
