package dev.deftmartian.runway

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Optional six-hour local import. First setup and the 30-day bootstrap remain foreground-led. */
class HealthConnectWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val gateway = AndroidHealthConnectGateway(applicationContext)
        val backgroundAllowed = runCatching {
            gateway.supportsBackgroundRead() && gateway.hasBackgroundPermission()
        }.getOrDefault(false)
        if (!backgroundAllowed) {
            ReconciliationScheduler.disableHealthConnectPeriodic(applicationContext)
            return Result.success()
        }

        val outcome = kotlinx.coroutines.runBlocking {
            HealthConnectSyncCoordinator(
                gateway = gateway,
                cursor = HealthConnectCursorStore(applicationContext),
                reconcile = applicationContext.runwayServices.healthConnect::reconcile,
            ).sync()
        }
        return when (outcome) {
            HealthSyncResult.Retryable -> Result.retry()
            HealthSyncResult.Synced,
            HealthSyncResult.PermissionRequired,
            HealthSyncResult.Unavailable,
            HealthSyncResult.UpdateRequired,
            HealthSyncResult.NeedsAttention,
            -> Result.success()
        }
    }
}
