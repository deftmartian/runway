package com.deftmartian.runway

import android.content.Context
import androidx.core.content.edit

data class ServerConnection(
    val origin: String,
    val generation: Long,
)

sealed interface ServerConnectionTransition {
    data class Changed(val connection: ServerConnection) : ServerConnectionTransition
    data object Conflict : ServerConnectionTransition
    data object Invalid : ServerConnectionTransition
}

class ServerConnectionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun currentOrigin(): String? = currentConnection()?.origin

    fun currentConnection(): ServerConnection? {
        val fast = AndroidStateCoordinator.read { readConsistentConnection() }
        if (fast.consistent) return fast.connection
        return AndroidStateCoordinator.write { reconcileConnection() }
    }

    /**
     * Replaces the selected server only when the caller's complete connection snapshot is still
     * current. A small transition journal makes cleanup restartable if Android kills the process
     * between removing the old origin and committing the new one.
     */
    fun replace(
        expected: ServerConnection?,
        origin: String,
    ): ServerConnectionTransition {
        val normalized = InstanceOriginPolicy.normalizeOrigin(origin, BuildConfig.DEBUG)
            ?: return ServerConnectionTransition.Invalid
        return AndroidStateCoordinator.write {
            if (reconcileConnection() != expected) {
                return@write ServerConnectionTransition.Conflict
            }
            if (expected?.origin == normalized) {
                return@write ServerConnectionTransition.Changed(expected)
            }

            beginTransition(expected?.origin, normalized)
            finishPendingTransition()
            val connection = reconcileConnection()
            if (connection?.origin != normalized) {
                ServerConnectionTransition.Conflict
            } else {
                ServerConnectionTransition.Changed(connection)
            }
        }
    }

    fun isCurrent(connection: ServerConnection): Boolean = currentConnection() == connection

    internal fun <T> mutateIfCurrent(expected: ServerConnection, block: () -> T): T? =
        AndroidStateCoordinator.write {
            if (reconcileConnection() != expected) null else block()
        }

    private fun readConsistentConnection(): ConnectionRead {
        if (preferences.contains(PENDING_TARGET_ORIGIN_KEY)) return ConnectionRead(false, null)
        val origin = readNormalizedOrigin()
        if (origin.needsRewrite) return ConnectionRead(false, null)
        val hasAdoptedOrigin = preferences.contains(ADOPTED_ORIGIN_KEY)
        val adoptedOrigin = preferences.getString(ADOPTED_ORIGIN_KEY, null)?.ifEmpty { null }
        if (!hasAdoptedOrigin || adoptedOrigin != origin.value) return ConnectionRead(false, null)
        return ConnectionRead(true, origin.value?.let { ServerConnection(it, generation()) })
    }

    private fun reconcileConnection(): ServerConnection? {
        finishPendingTransition()
        val normalized = readNormalizedOrigin()
        if (normalized.needsRewrite) {
            preferences.edit(commit = true) {
                if (normalized.value == null) remove(ORIGIN_KEY) else putString(ORIGIN_KEY, normalized.value)
            }
        }
        val effectiveOrigin = normalized.value
        val hasAdoptedOrigin = preferences.contains(ADOPTED_ORIGIN_KEY)
        val adoptedOrigin = preferences.getString(ADOPTED_ORIGIN_KEY, null)?.ifEmpty { null }
        if (!hasAdoptedOrigin || adoptedOrigin != effectiveOrigin) {
            beginTransition(adoptedOrigin, effectiveOrigin)
            finishPendingTransition()
        }
        return effectiveOrigin?.let { ServerConnection(it, generation()) }
    }

    private fun beginTransition(cleanupOrigin: String?, targetOrigin: String?) {
        advanceGeneration()
        preferences.edit(commit = true) {
            putString(PENDING_CLEANUP_ORIGIN_KEY, cleanupOrigin.orEmpty())
            putString(PENDING_TARGET_ORIGIN_KEY, targetOrigin.orEmpty())
            remove(ORIGIN_KEY)
            putString(ADOPTED_ORIGIN_KEY, "")
        }
    }

    private fun finishPendingTransition() {
        if (!preferences.contains(PENDING_TARGET_ORIGIN_KEY)) return
        val cleanupOrigin = preferences.getString(PENDING_CLEANUP_ORIGIN_KEY, null)?.ifEmpty { null }
        val targetOrigin = preferences.getString(PENDING_TARGET_ORIGIN_KEY, null)
            ?.ifEmpty { null }
            ?.let { InstanceOriginPolicy.normalizeOrigin(it, BuildConfig.DEBUG) }
        ServerConnectionReset.clearDeviceState(appContext, cleanupOrigin)
        preferences.edit(commit = true) {
            if (targetOrigin == null) remove(ORIGIN_KEY) else putString(ORIGIN_KEY, targetOrigin)
            putString(ADOPTED_ORIGIN_KEY, targetOrigin.orEmpty())
            remove(PENDING_CLEANUP_ORIGIN_KEY)
            remove(PENDING_TARGET_ORIGIN_KEY)
        }
    }

    private fun readNormalizedOrigin(): NormalizedOrigin {
        val raw = preferences.getString(ORIGIN_KEY, null)
        val normalized = raw?.let { InstanceOriginPolicy.normalizeOrigin(it, BuildConfig.DEBUG) }
        return NormalizedOrigin(normalized, raw != null && raw != normalized)
    }

    private fun generation(): Long = preferences.getLong(GENERATION_KEY, 0)

    private fun advanceGeneration() {
        val current = generation()
        preferences.edit(commit = true) {
            putLong(GENERATION_KEY, if (current == Long.MAX_VALUE) 1 else current + 1)
        }
    }

    private data class ConnectionRead(
        val consistent: Boolean,
        val connection: ServerConnection?,
    )

    private data class NormalizedOrigin(
        val value: String?,
        val needsRewrite: Boolean,
    )

    private companion object {
        const val PREFERENCES_NAME = "runway_android_server"
        const val ORIGIN_KEY = "origin"
        const val ADOPTED_ORIGIN_KEY = "adopted_origin"
        const val GENERATION_KEY = "connection_generation"
        const val PENDING_CLEANUP_ORIGIN_KEY = "pending_cleanup_origin"
        const val PENDING_TARGET_ORIGIN_KEY = "pending_target_origin"
    }
}
