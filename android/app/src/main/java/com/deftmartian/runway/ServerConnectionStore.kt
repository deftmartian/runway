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

    fun currentConnection(): ServerConnection? = synchronized(adoptionLock) {
        val effectiveOrigin = effectiveOrigin()
        val hasAdoptedOrigin = preferences.contains(ADOPTED_ORIGIN_KEY)
        val adoptedOrigin = preferences.getString(ADOPTED_ORIGIN_KEY, null)?.ifEmpty { null }
        if (!hasAdoptedOrigin || adoptedOrigin != effectiveOrigin) {
            advanceGeneration()
            ServerConnectionReset.clearDeviceState(appContext, adoptedOrigin)
            preferences.edit(commit = true) {
                putString(ADOPTED_ORIGIN_KEY, effectiveOrigin.orEmpty())
            }
        }
        effectiveOrigin?.let { ServerConnection(it, generation()) }
    }

    private fun effectiveOrigin(): String? {
        val saved = preferences.getString(ORIGIN_KEY, null) ?: return null
        return InstanceOriginPolicy.normalizeOrigin(saved, BuildConfig.DEBUG).also { normalized ->
            if (normalized == null || normalized != saved) preferences.edit(commit = true) {
                if (normalized == null) remove(ORIGIN_KEY) else putString(ORIGIN_KEY, normalized)
            }
        }
    }

    /**
     * Replaces the selected server only when the caller's complete connection snapshot is still
     * current. The generation changes before any teardown and the new origin is committed only
     * after old device state has been cleared, so stale Activities and workers fail closed.
     */
    fun replace(
        expected: ServerConnection?,
        origin: String,
    ): ServerConnectionTransition {
        val normalized = InstanceOriginPolicy.normalizeOrigin(origin, BuildConfig.DEBUG)
            ?: return ServerConnectionTransition.Invalid
        return synchronized(adoptionLock) {
            if (currentConnection() != expected) return@synchronized ServerConnectionTransition.Conflict
            if (expected?.origin == normalized) {
                return@synchronized ServerConnectionTransition.Changed(expected)
            }

            advanceGeneration()
            preferences.edit(commit = true) {
                remove(ORIGIN_KEY)
                putString(ADOPTED_ORIGIN_KEY, "")
            }
            ServerConnectionReset.clearDeviceState(appContext, expected?.origin)
            preferences.edit(commit = true) {
                putString(ORIGIN_KEY, normalized)
                putString(ADOPTED_ORIGIN_KEY, normalized)
            }
            ServerConnectionTransition.Changed(ServerConnection(normalized, generation()))
        }
    }

    fun isCurrent(connection: ServerConnection): Boolean = currentConnection() == connection

    private fun generation(): Long = preferences.getLong(GENERATION_KEY, 0)

    private fun advanceGeneration() {
        val current = generation()
        preferences.edit(commit = true) {
            putLong(GENERATION_KEY, if (current == Long.MAX_VALUE) 1 else current + 1)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_android_server"
        const val ORIGIN_KEY = "origin"
        const val ADOPTED_ORIGIN_KEY = "adopted_origin"
        const val GENERATION_KEY = "connection_generation"
        val adoptionLock = Any()
    }
}
