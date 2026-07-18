package com.deftmartian.runway

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri

sealed interface TreeAccessState {
    data object Missing : TreeAccessState
    data class PermissionRequired(val uri: Uri) : TreeAccessState
    data class Connected(val uri: Uri) : TreeAccessState
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
            preferences.edit().putString(TREE_URI_KEY, uri.toString()).apply()
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
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return TreeAccessState.Missing
        val permission = appContext.contentResolver.persistedUriPermissions.firstOrNull {
            it.uri == uri && it.isReadPermission
        }
        return if (permission == null) {
            TreeAccessState.PermissionRequired(uri)
        } else {
            TreeAccessState.Connected(uri)
        }
    }

    fun disconnect() {
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
        preferences.edit().remove(TREE_URI_KEY).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_android"
        const val TREE_URI_KEY = "gpx_tree_uri"
    }
}
