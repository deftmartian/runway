package dev.deftmartian.runway

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** Optional six-hour local import. First setup and the 30-day bootstrap remain foreground-led. */
class HealthConnectWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val gateway = AndroidHealthConnectGateway(applicationContext)
        val backgroundAllowed = try {
            gateway.supportsBackgroundRead() && gateway.hasBackgroundPermission()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!backgroundAllowed) {
            ReconciliationScheduler.disableHealthConnectPeriodic(applicationContext)
            return Result.success()
        }

        val outcome = AndroidStateCoordinator.withImportDataBoundary {
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
