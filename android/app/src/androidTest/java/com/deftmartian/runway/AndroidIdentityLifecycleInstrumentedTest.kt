@file:Suppress("DEPRECATION")

package com.deftmartian.runway

import android.content.Context
import android.test.InstrumentationTestCase
import androidx.core.content.edit

class AndroidIdentityLifecycleInstrumentedTest : InstrumentationTestCase() {
    private val firstOrigin = "https://first.runway.test"
    private val secondOrigin = "https://second.runway.test"

    fun testInterruptedServerTransitionRecoversBeforeNewWorkCanReadState() {
        val context = instrumentation.targetContext
        val serverStore = ServerConnectionStore(context)
        val changed = serverStore.replace(serverStore.currentConnection(), firstOrigin)
        assertTrue(changed is ServerConnectionTransition.Changed)
        val first = (changed as ServerConnectionTransition.Changed).connection
        val credentialStore = AndroidCredentialStore(context, firstOrigin)
        val initialCredentialState = credentialStore.snapshot()
        assertTrue(
            credentialStore.replace(
                initialCredentialState,
                credential(firstOrigin, "first-device", "rwy1_first_test_token"),
            ),
        )

        val preferences = context.getSharedPreferences(
            SERVER_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        preferences.edit(commit = true) {
            putLong(CONNECTION_GENERATION_KEY, first.generation + 1)
            putString(PENDING_CLEANUP_ORIGIN_KEY, firstOrigin)
            putString(PENDING_TARGET_ORIGIN_KEY, secondOrigin)
            remove(ORIGIN_KEY)
            putString(ADOPTED_ORIGIN_KEY, "")
        }

        val recovered = ServerConnectionStore(context).currentConnection()
        assertEquals(secondOrigin, recovered?.origin)
        assertEquals(first.generation + 1, recovered?.generation)
        assertNull(AndroidCredentialStore(context, firstOrigin).load())
        assertFalse(preferences.contains(PENDING_CLEANUP_ORIGIN_KEY))
        assertFalse(preferences.contains(PENDING_TARGET_ORIGIN_KEY))
    }

    fun testStaleCredentialSnapshotCannotClearReplacement() {
        val context = instrumentation.targetContext
        val store = AndroidCredentialStore(context, secondOrigin)
        store.clear()
        val empty = store.snapshot()
        assertTrue(
            store.replace(
                empty,
                credential(secondOrigin, "second-device", "rwy1_original_test_token"),
            ),
        )
        val stale = store.snapshot()
        assertTrue(
            store.replace(
                stale,
                credential(secondOrigin, "second-device", "rwy1_replacement_test_token"),
            ),
        )

        assertFalse(store.clearIfCurrent(stale))
        assertEquals("rwy1_replacement_test_token", store.load()?.token)
        store.clear()
    }

    private fun credential(origin: String, deviceId: String, token: String) = AndroidCredential(
        origin = origin,
        deviceId = deviceId,
        token = token,
        expiresAtEpochMs = System.currentTimeMillis() + 60_000,
    )

    private companion object {
        const val SERVER_PREFERENCES_NAME = "runway_android_server"
        const val ORIGIN_KEY = "origin"
        const val ADOPTED_ORIGIN_KEY = "adopted_origin"
        const val CONNECTION_GENERATION_KEY = "connection_generation"
        const val PENDING_CLEANUP_ORIGIN_KEY = "pending_cleanup_origin"
        const val PENDING_TARGET_ORIGIN_KEY = "pending_target_origin"
    }
}
