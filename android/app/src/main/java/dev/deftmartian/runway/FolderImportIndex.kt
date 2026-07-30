package dev.deftmartian.runway

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.security.MessageDigest

internal sealed interface FolderCandidateReadiness {
    data object WaitingForStableFile : FolderCandidateReadiness
    data object Ready : FolderCandidateReadiness
    data object AlreadyHandled : FolderCandidateReadiness
}

/**
 * A bounded scan accelerator only. Room's import digest remains authoritative for duplicate and
 * deletion behavior; losing this index merely causes safe re-reading.
 */
internal class FolderImportIndex(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun readiness(
        candidate: GpxTreeCandidate,
        observedAtEpochMillis: Long,
        settleDurationMillis: Long,
    ): FolderCandidateReadiness = synchronized(lock) {
        val key = candidate.key()
        val revision = candidate.revision()
        val handledKey = "${HANDLED_PREFIX}$key"
        val handled = FolderHandledRevision.decode(preferences.getString(handledKey, null))
        if (handled?.revision == revision) {
            if (handled.isFreshAt(observedAtEpochMillis)) {
                return@synchronized FolderCandidateReadiness.AlreadyHandled
            }
            // Metadata is not a content identity. A bridge can overwrite the same document with
            // the same size and coarse timestamp; eventually re-read it and let Room's digest be
            // the final duplicate authority.
            preferences.edit(commit = true) { remove(handledKey) }
        }
        val observationKey = "${OBSERVED_PREFIX}$key"
        val previous = FolderObservation.decode(preferences.getString(observationKey, null))
        if (previous == null || previous.revision != revision) {
            preferences.edit(commit = true) {
                putString(
                    observationKey,
                    FolderObservation(revision, observedAtEpochMillis).encode(),
                )
            }
            return@synchronized FolderCandidateReadiness.WaitingForStableFile
        }
        if (observedAtEpochMillis - previous.firstSeenAtEpochMillis < settleDurationMillis) {
            FolderCandidateReadiness.WaitingForStableFile
        } else {
            FolderCandidateReadiness.Ready
        }
    }

    fun markHandled(candidate: GpxTreeCandidate) = synchronized(lock) {
        val key = candidate.key()
        preferences.edit(commit = true) {
            putString(
                "${HANDLED_PREFIX}$key",
                FolderHandledRevision(candidate.revision(), System.currentTimeMillis()).encode(),
            )
            remove("${OBSERVED_PREFIX}$key")
        }
    }

    fun clear() = synchronized(lock) {
        preferences.edit(commit = true) { clear() }
    }

    private fun GpxTreeCandidate.key(): String = sha256(uri.toString())

    /**
     * Providers normally expose both size and mtime. When they do not, the document URI is the
     * revision: reconnecting the folder clears the accelerator, while Room still detects content
     * duplicates on any subsequent read.
     */
    private fun GpxTreeCandidate.revision(): String =
        listOf(uri.toString(), sizeBytes?.toString().orEmpty(), lastModifiedEpochMs?.toString().orEmpty())
            .joinToString("\u0000")
            .let(::sha256)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class FolderObservation(
        val revision: String,
        val firstSeenAtEpochMillis: Long,
    ) {
        fun encode(): String = "$revision:$firstSeenAtEpochMillis"

        companion object {
            fun decode(raw: String?): FolderObservation? {
                val parts = raw?.split(':') ?: return null
                if (parts.size != 2 || !parts[0].matches(Regex("[0-9a-f]{64}"))) return null
                val firstSeen = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
                return FolderObservation(parts[0], firstSeen)
            }
        }
    }

    internal data class FolderHandledRevision(
        val revision: String,
        val handledAtEpochMillis: Long,
    ) {
        fun isFreshAt(observedAtEpochMillis: Long): Boolean =
            observedAtEpochMillis >= handledAtEpochMillis &&
                observedAtEpochMillis - handledAtEpochMillis < HANDLED_RECHECK_MILLIS

        fun encode(): String = "$revision:$handledAtEpochMillis"

        companion object {
            fun decode(raw: String?): FolderHandledRevision? {
                val parts = raw?.split(':') ?: return null
                if (parts.size != 2 || !parts[0].matches(Regex("[0-9a-f]{64}"))) return null
                val handledAt = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
                return FolderHandledRevision(parts[0], handledAt)
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_folder_import_index"
        const val OBSERVED_PREFIX = "observed_"
        const val HANDLED_PREFIX = "handled_"
        const val HANDLED_RECHECK_MILLIS = 24L * 60L * 60L * 1_000L
        val lock = Any()
    }
}
