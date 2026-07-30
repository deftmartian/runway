package dev.deftmartian.runway

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

sealed interface TreeAccessState {
    data object Missing : TreeAccessState
    data class PermissionRequired(val uri: Uri, val generation: Long) : TreeAccessState
    data class Connected(val uri: Uri, val generation: Long) : TreeAccessState
}

sealed interface TreeAccessMutation {
    data object Changed : TreeAccessMutation
    data object Failed : TreeAccessMutation
}

/**
 * Owns one durable Storage Access Framework tree grant.
 *
 * The grant belongs to this local installation, not to an account or remote origin. A generation
 * lets an in-flight worker notice that the user changed or removed the folder before it records a
 * result.
 */
class TreeAccessStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun connect(uri: Uri): TreeAccessMutation = AndroidStateCoordinator.write {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@write TreeAccessMutation.Failed
        val persisted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        }.getOrDefault(false)
        if (!persisted) return@write TreeAccessMutation.Failed

        val previous = storedUri()
        ReconciliationScheduler.cancelFolderWork(appContext)
        preferences.edit(commit = true) {
            putString(TREE_URI_KEY, uri.toString())
            putLong(TREE_GENERATION_KEY, nextGeneration())
        }
        if (previous != null && previous != uri) {
            release(previous)
            FolderImportIndex(appContext).clear()
        }
        TreeAccessMutation.Changed
    }

    fun currentState(): TreeAccessState = AndroidStateCoordinator.read {
        val uri = storedUri() ?: return@read TreeAccessState.Missing
        val generation = preferences.getLong(TREE_GENERATION_KEY, 0)
        val permission = appContext.contentResolver.persistedUriPermissions.firstOrNull {
            it.uri == uri && it.isReadPermission
        }
        if (permission == null) {
            TreeAccessState.PermissionRequired(uri, generation)
        } else {
            TreeAccessState.Connected(uri, generation)
        }
    }

    fun disconnect(): TreeAccessMutation = AndroidStateCoordinator.write {
        ReconciliationScheduler.cancelFolderWork(appContext)
        storedUri()?.let(::release)
        preferences.edit(commit = true) {
            remove(TREE_URI_KEY)
            putLong(TREE_GENERATION_KEY, nextGeneration())
        }
        FolderImportIndex(appContext).clear()
        ReconciliationStatusStore(appContext).record(ReconciliationWorker.STATE_PERMISSION_REQUIRED)
        TreeAccessMutation.Changed
    }

    private fun storedUri(): Uri? =
        preferences.getString(TREE_URI_KEY, null)
            ?.let { raw -> runCatching { raw.toUri() }.getOrNull() }

    private fun release(uri: Uri) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun nextGeneration(): Long {
        val current = preferences.getLong(TREE_GENERATION_KEY, 0)
        return if (current == Long.MAX_VALUE) 1 else current + 1
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_folder_access"
        const val TREE_URI_KEY = "tree_uri"
        const val TREE_GENERATION_KEY = "tree_generation"
    }
}
