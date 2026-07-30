package dev.deftmartian.runway.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLocalSurfaceLedgerReaderTest {
    @Test
    fun `large id reads stay below sqlite binds and preserve the total cap`() = runBlocking {
        val chunkSizes = mutableListOf<Int>()

        val rows = chunkedSqliteIdRead(
            ids = (1..2_100).toList() + 1,
            limit = 1_950,
        ) { ids, remaining ->
            chunkSizes += ids.size
            ids.take(remaining)
        }

        assertTrue(chunkSizes.all { it <= 900 })
        assertEquals(listOf(900, 900, 300), chunkSizes)
        assertEquals((1..1_950).toList(), rows)
    }

    @Test
    fun `empty and zero limit reads do not query storage`() = runBlocking {
        var calls = 0
        val read: suspend (List<Int>, Int) -> List<Int> = { ids, _ ->
            calls += 1
            ids
        }

        assertEquals(emptyList<Int>(), chunkedSqliteIdRead(emptyList(), 10, read))
        assertEquals(emptyList<Int>(), chunkedSqliteIdRead(listOf(1), 0, read))
        assertEquals(0, calls)
    }
}
