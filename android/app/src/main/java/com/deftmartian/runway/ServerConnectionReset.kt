package com.deftmartian.runway

import android.content.Context

object ServerConnectionReset {
    fun clearDeviceState(context: Context, origin: String?) {
        val appContext = context.applicationContext
        ReconciliationScheduler.cancelAll(appContext)
        AndroidCredentialStore.clearLegacyState(appContext)
        if (origin != null) {
            val credentialStore = AndroidCredentialStore(appContext, origin)
            credentialStore.load()?.let { credential ->
                HandledImportStore(appContext).clearForDevice(credential.deviceId)
            }
            credentialStore.clear()
        }
        TreeAccessStore(appContext).disconnectForReset()
        HandledImportStore(appContext).clearAll()
        ReconciliationStatusStore(appContext).clear()
    }
}
