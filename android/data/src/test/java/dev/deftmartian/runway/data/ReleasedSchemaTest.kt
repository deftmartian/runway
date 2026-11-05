package dev.deftmartian.runway.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the promise that local data survives updates from v0.11.0 onward.
 *
 * A released schema file and identity never change. A schema change adds a new version, its exported
 * schema, its released identity, and an explicit migration from the previous version.
 */
class ReleasedSchemaTest {
    private val schemaDirectory = File("schemas/dev.deftmartian.runway.data.RunwayLedgerDatabase")
    private val released = RunwayLedgerDatabase.RELEASED_SCHEMA_IDENTITY_HASHES

    @Test
    fun everyVersionFromTheFirstPreservedSchemaIsReleasedAndExported() {
        val versions = RunwayLedgerDatabase.FIRST_PRESERVED_SCHEMA_VERSION..RunwayLedgerDatabase.SCHEMA_VERSION
        assertEquals(versions.toList(), released.keys.sorted())
        assertEquals(RunwayLedgerDatabase.SCHEMA_IDENTITY_HASH, released[RunwayLedgerDatabase.SCHEMA_VERSION])
        versions.forEach { version ->
            val schema = File(schemaDirectory, "$version.json")
            assertTrue("Missing exported schema ${schema.path}", schema.isFile)
            assertEquals(
                "Exported schema $version no longer matches its released identity",
                released[version],
                identityHash(schema.readText()),
            )
        }
    }

    @Test
    fun migrationsFormOneExplicitPathFromEveryReleasedSchema() {
        val steps = RunwayLedgerDatabase.MIGRATIONS.associate { it.startVersion to it.endVersion }
        assertEquals(RunwayLedgerDatabase.MIGRATIONS.size, steps.size)
        (RunwayLedgerDatabase.FIRST_PRESERVED_SCHEMA_VERSION until RunwayLedgerDatabase.SCHEMA_VERSION).forEach { version ->
            assertEquals("Missing migration from schema $version", version + 1, steps[version])
        }
    }

    @Test
    fun restoreAcceptsReleasedSchemasAndRejectsOthers() {
        released.forEach { (version, hash) -> assertNull(LocalRestoreCandidate.schemaError(version, hash)) }
        val current = RunwayLedgerDatabase.SCHEMA_VERSION
        assertNotNull(LocalRestoreCandidate.schemaError(RunwayLedgerDatabase.FIRST_PRESERVED_SCHEMA_VERSION - 1, "any"))
        assertNotNull(LocalRestoreCandidate.schemaError(current + 1, "any"))
        assertNotNull(LocalRestoreCandidate.schemaError(current, "0".repeat(32)))
    }

    private fun identityHash(json: String): String? =
        Regex("\"identityHash\"\\s*:\\s*\"([0-9a-f]+)\"").find(json)?.groupValues?.get(1)
}
