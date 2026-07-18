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

class TreeAccessStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun connect(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val previousUri = when (val state = currentState()) {
            is TreeAccessState.Connected -> state.uri
            is TreeAccessState.PermissionRequired -> state.uri
            TreeAccessState.Missing -> null
        }

        val persisted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        }.getOrDefault(false)

        if (persisted) {
            ReconciliationScheduler.cancelAll(appContext)
            if (previousUri != null && previousUri != uri) {
                HandledImportStore(appContext).clearAll()
            }
            preferences.edit(commit = true) {
                putString(TREE_URI_KEY, uri.toString())
                putLong(TREE_GENERATION_KEY, nextGeneration())
            }
            if (previousUri != null && previousUri != uri) {
                runCatching {
                    appContext.contentResolver.releasePersistableUriPermission(
                        previousUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
        return persisted
    }

    fun currentState(): TreeAccessState {
        val rawUri = preferences.getString(TREE_URI_KEY, null) ?: return TreeAccessState.Missing
        val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return TreeAccessState.Missing
        val generation = preferences.getLong(TREE_GENERATION_KEY, 0)
        val permission = appContext.contentResolver.persistedUriPermissions.firstOrNull {
            it.uri == uri && it.isReadPermission
        }
        return if (permission == null) {
            TreeAccessState.PermissionRequired(uri, generation)
        } else {
            TreeAccessState.Connected(uri, generation)
        }
    }

    fun disconnect() {
        ReconciliationScheduler.cancelAll(appContext)
        val state = currentState()
        val uri = when (state) {
            is TreeAccessState.Connected -> state.uri
            is TreeAccessState.PermissionRequired -> state.uri
            TreeAccessState.Missing -> null
        }
        if (uri != null) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        preferences.edit(commit = true) {
            remove(TREE_URI_KEY)
            putLong(TREE_GENERATION_KEY, nextGeneration())
        }
        HandledImportStore(appContext).clearAll()
        ReconciliationStatusStore(appContext).record(
            ReconciliationWorker.STATE_PERMISSION_REQUIRED,
        )
    }

    private fun nextGeneration(): Long {
        val current = preferences.getLong(TREE_GENERATION_KEY, 0)
        return if (current == Long.MAX_VALUE) 1 else current + 1
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_android"
        const val TREE_URI_KEY = "gpx_tree_uri"
        const val TREE_GENERATION_KEY = "gpx_tree_generation"
    }
}
