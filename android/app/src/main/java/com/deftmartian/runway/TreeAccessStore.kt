package com.deftmartian.runway

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
    data object Conflict : TreeAccessMutation
    data object Failed : TreeAccessMutation
}

class TreeAccessStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        // A pre-0.2.0 grant has no server owner and cannot safely be reused after server selection.
        AndroidStateCoordinator.write {
            if (
                storedUri() != null &&
                (
                    !preferences.contains(TREE_OWNER_ORIGIN_KEY) ||
                        !preferences.contains(TREE_OWNER_SERVER_GENERATION_KEY)
                )
            ) {
                disconnectLocked(recordStatus = false)
            }
        }
    }

    fun connect(uri: Uri, expectedConnection: ServerConnection): TreeAccessMutation =
        AndroidStateCoordinator.write {
            if (ServerConnectionStore(appContext).currentConnection() != expectedConnection) {
                return@write TreeAccessMutation.Conflict
            }
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@write TreeAccessMutation.Failed
            val previousUri = storedUri()
            val persisted = runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)
            if (!persisted) return@write TreeAccessMutation.Failed

            // The lifecycle write lock keeps a server switch or competing picker result from
            // committing between the ownership check and the URI record.
            ReconciliationScheduler.cancelAll(appContext)
            if (previousUri != null && previousUri != uri) {
                HandledImportStore(appContext).clearAll()
            }
            preferences.edit(commit = true) {
                putString(TREE_URI_KEY, uri.toString())
                putLong(TREE_GENERATION_KEY, nextGeneration())
                putString(TREE_OWNER_ORIGIN_KEY, expectedConnection.origin)
                putLong(TREE_OWNER_SERVER_GENERATION_KEY, expectedConnection.generation)
            }
            if (previousUri != null && previousUri != uri) release(previousUri)
            TreeAccessMutation.Changed
        }

    fun currentState(expectedConnection: ServerConnection): TreeAccessState =
        AndroidStateCoordinator.read {
            if (
                preferences.getString(TREE_OWNER_ORIGIN_KEY, null) != expectedConnection.origin ||
                preferences.getLong(TREE_OWNER_SERVER_GENERATION_KEY, Long.MIN_VALUE) !=
                expectedConnection.generation
            ) {
                return@read TreeAccessState.Missing
            }
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

    fun disconnect(expectedConnection: ServerConnection): TreeAccessMutation =
        AndroidStateCoordinator.write {
            if (ServerConnectionStore(appContext).currentConnection() != expectedConnection) {
                return@write TreeAccessMutation.Conflict
            }
            disconnectLocked(recordStatus = true)
            TreeAccessMutation.Changed
        }

    internal fun disconnectForReset() {
        AndroidStateCoordinator.write { disconnectLocked(recordStatus = false) }
    }

    private fun disconnectLocked(recordStatus: Boolean) {
        ReconciliationScheduler.cancelAll(appContext)
        storedUri()?.let(::release)
        preferences.edit(commit = true) {
            remove(TREE_URI_KEY)
            remove(TREE_OWNER_ORIGIN_KEY)
            remove(TREE_OWNER_SERVER_GENERATION_KEY)
            putLong(TREE_GENERATION_KEY, nextGeneration())
        }
        HandledImportStore(appContext).clearAll()
        if (recordStatus) {
            ReconciliationStatusStore(appContext).record(
                ReconciliationWorker.STATE_PERMISSION_REQUIRED,
            )
        }
    }

    private fun storedUri(): Uri? {
        val rawUri = preferences.getString(TREE_URI_KEY, null) ?: return null
        return runCatching { rawUri.toUri() }.getOrNull()
    }

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
        const val PREFERENCES_NAME = "runway_android"
        const val TREE_URI_KEY = "gpx_tree_uri"
        const val TREE_GENERATION_KEY = "gpx_tree_generation"
        const val TREE_OWNER_ORIGIN_KEY = "gpx_tree_owner_origin"
        const val TREE_OWNER_SERVER_GENERATION_KEY = "gpx_tree_owner_server_generation"
    }
}
