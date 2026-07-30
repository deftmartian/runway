package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderImportIndexPolicyTest {
    @Test
    fun `handled revision suppresses only the bounded recheck window`() {
        val revision = "a".repeat(64)
        val handled = FolderImportIndex.FolderHandledRevision(
            revision = revision,
            handledAtEpochMillis = 1_000L,
        )

        assertTrue(handled.isFreshAt(1_000L + 23L * 60L * 60L * 1_000L))
        assertFalse(handled.isFreshAt(1_000L + 24L * 60L * 60L * 1_000L))
        assertFalse(handled.isFreshAt(999L))
    }

    @Test
    fun `legacy revision-only handled entries are rechecked safely`() {
        assertNull(FolderImportIndex.FolderHandledRevision.decode("a".repeat(64)))
    }
}
