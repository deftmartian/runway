package com.deftmartian.runway

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Optional six-hour maintenance sync. First setup and the 30-day import remain foreground-only. */
class HealthConnectWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val serverStore = ServerConnectionStore(applicationContext)
        val connection = serverStore.currentConnection() ?: return Result.success()
        val credentialStore = AndroidCredentialStore(applicationContext, connection.origin)
        val cursor = HealthConnectCursorStore(applicationContext, connection.origin)
        val gateway = AndroidHealthConnectGateway(applicationContext)
        val backgroundAllowed = runCatching {
            gateway.supportsBackgroundRead() && gateway.hasBackgroundPermission()
        }.getOrDefault(false)
        if (!backgroundAllowed) {
            ReconciliationScheduler.disableHealthConnectPeriodic(applicationContext)
            return Result.success()
        }
        val credentialState = credentialStore.snapshot()
        val refreshed = refreshHealthCredential(
            AndroidHealthCredentialRepository(credentialStore),
            credentialState,
            cursor,
        ) { credential -> RunwayApiClient(connection.origin).status(credential) }
        val currentState = when (refreshed) {
            is HealthCredentialRefresh.Ready -> refreshed.state
            HealthCredentialRefresh.PairingRequired -> return Result.success()
            HealthCredentialRefresh.Retryable -> return Result.retry()
        }
        val outcome = HealthConnectSyncCoordinator(
            gateway = gateway,
            send = { _, payload ->
                if (!serverStore.isCurrent(connection)) {
                    return@HealthConnectSyncCoordinator HealthConnectApiResult.Retryable
                }
                credentialStore.useIfCurrent(currentState) { current ->
                    RunwayApiClient(connection.origin).syncHealthConnectChanges(current, payload)
                } ?: HealthConnectApiResult.Retryable
            },
            cursor = cursor,
        ).sync(connection, currentState)
        if (outcome == HealthSyncResult.PairingRequired) {
            serverStore.mutateIfCurrent(connection) {
                if (credentialStore.clearIfCurrent(currentState)) {
                    currentState.credential?.let { credential ->
                        HandledImportStore(applicationContext).clearForDevice(credential.deviceId)
                    }
                    cursor.clearAll()
                    ReconciliationScheduler.cancelAll(applicationContext)
                    true
                } else {
                    false
                }
            }
        }
        return when (outcome) {
            HealthSyncResult.Synced,
            HealthSyncResult.PermissionRequired,
            HealthSyncResult.Unavailable,
            HealthSyncResult.UpdateRequired,
            HealthSyncResult.PairingRequired,
            HealthSyncResult.NeedsAttention -> Result.success()
            HealthSyncResult.Retryable -> Result.retry()
        }
    }
}
