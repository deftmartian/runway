package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AndroidImportSourceControllerTest {
    @Test
    fun `disconnect attempts every source and reports the failed boundary`() {
        val attempted = mutableListOf<String>()
        val controller = AndroidImportSourceController(
            cancelWorkers = { attempted += "workers" },
            disconnectFolder = {
                attempted += "folder"
                error("provider failure")
            },
            clearHealthConnectCursor = { attempted += "cursor" },
            revokeHealthConnectPermissions = {
                attempted += "health"
                true
            },
        )

        val failure = runCatching(controller::disconnectAll).exceptionOrNull()

        assertEquals(listOf("workers", "folder", "cursor", "health"), attempted)
        assertTrue(failure?.message.orEmpty().contains("GPX folder access"))
        assertTrue(failure?.message.orEmpty().contains("No local data was erased"))
        assertTrue(failure?.message.orEmpty().contains("may already be disconnected"))
    }

    @Test
    fun `health connect must confirm permission removal`() {
        val failure = runCatching {
            AndroidImportSourceController({}, {}, {}, { false }).disconnectAll()
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("Health Connect permission"))
    }

    @Test
    fun `every source is disconnected before the erase operation starts`() = runBlocking {
        val events = mutableListOf<String>()
        val controller = AndroidImportSourceController(
            cancelWorkers = { events += "workers" },
            disconnectFolder = { events += "folder" },
            clearHealthConnectCursor = { events += "cursor" },
            revokeHealthConnectPermissions = {
                events += "health"
                true
            },
        )

        val result = controller.disconnectBeforeErase {
            events += "erase"
            42
        }

        assertEquals(42, result)
        assertEquals(listOf("workers", "folder", "cursor", "health", "erase"), events)
    }

    @Test
    fun `database failure reports that sources were already disconnected`() = runBlocking {
        val controller = AndroidImportSourceController({}, {}, {}, { true })

        val failure = runCatching {
            controller.disconnectBeforeErase<Unit> { error("database failure") }
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("Import sources were disconnected"))
        assertTrue(failure?.message.orEmpty().contains("Existing data is still on this phone"))
    }

    @Test
    fun `erase does not start when a source cannot be disconnected`() = runBlocking {
        var eraseStarted = false
        val controller = AndroidImportSourceController(
            cancelWorkers = {},
            disconnectFolder = { error("provider failure") },
            clearHealthConnectCursor = {},
            revokeHealthConnectPermissions = { true },
        )

        val failure = runCatching {
            controller.disconnectBeforeErase {
                eraseStarted = true
            }
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("No local data was erased"))
        assertTrue(!eraseStarted)
    }
}
