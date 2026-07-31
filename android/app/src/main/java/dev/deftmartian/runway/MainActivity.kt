package dev.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Process
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.deftmartian.runway.data.LocalBackupDocumentContract
import kotlin.system.exitProcess
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val runwayViewModel: RunwayViewModel by viewModels()
    private val gpxPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runwayViewModel.importGpx(this, it) } }
    private val backupCreator = registerForActivityResult(ActivityResultContracts.CreateDocument(LocalBackupDocumentContract.MIME_TYPE)) { uri -> uri?.let { runwayViewModel.backup(this, it) } }
    private val backupRestorer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runwayViewModel.restore(this, it) } }
    private val exportCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { runwayViewModel.export(this, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        publishShortcuts()
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runwayViewModel.restartAfterRestore.collect { restartRequired ->
                    if (restartRequired) {
                        runwayViewModel.consumeRestartAfterRestore()
                        restartAfterRestore()
                    }
                }
            }
        }
        setContent {
            RunwayTheme {
                RunwayNativeApp(
                    state = runwayViewModel.state.collectAsStateWithLifecycle().value,
                    onDestinationSelected = runwayViewModel::selectDestination,
                    onCalendarMonthSelected = runwayViewModel::loadCalendarMonth,
                    onLoadMoreHistory = runwayViewModel::loadMoreHistory,
                    onLoadMoreInbox = runwayViewModel::loadMoreInbox,
                    onLoadActivityTrace = runwayViewModel::loadActivityTrace,
                    onOpenHistoryDetail = runwayViewModel::openHistoryDetail,
                    onRetryOpen = runwayViewModel::refresh,
                    onAction = runwayViewModel::submitAction,
                    onApplyWorkoutPreview = runwayViewModel::applyWorkoutPreview,
                    onDismissWorkoutPreview = runwayViewModel::dismissWorkoutPreview,
                    onApplyPlanDecisionPreview = runwayViewModel::applyPlanDecisionPreview,
                    onDismissPlanDecisionPreview = runwayViewModel::dismissPlanDecisionPreview,
                    onOpenFolder = {
                        startActivity(Intent(this, NativeFolderSettingsActivity::class.java).apply {
                            action = ACTION_OPEN_FOLDER_SETTINGS
                        })
                    },
                    onImportGpx = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")) },
                    onOpenHealthConnect = {
                        startActivity(Intent(this, NativeFolderSettingsActivity::class.java).apply {
                            action = ACTION_OPEN_HEALTH_CONNECT_SETTINGS
                        })
                    },
                    onCreateBackup = { backupCreator.launch(LocalBackupDocumentContract.DEFAULT_FILE_NAME) },
                    onRestoreBackup = {
                        backupRestorer.launch(
                            arrayOf(
                                LocalBackupDocumentContract.MIME_TYPE,
                                "application/x-sqlite3",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onExportData = { exportCreator.launch("runway-training-export.json") },
                    onTimeZoneChanged = runwayViewModel::updateTimeZone,
                    onRoutePrivacyChanged = runwayViewModel::updateRoutePrivacy,
                    onHeartRatePrivacyChanged = runwayViewModel::updateHeartRatePrivacy,
                    onHeartRateChanged = runwayViewModel::updateHeartRate,
                    onHealthContextChanged = runwayViewModel::updateHealthContext,
                    onEraseImportedActivityData = runwayViewModel::eraseImportedActivityData,
                    onEraseAllData = runwayViewModel::eraseAllData,
                    onAcknowledgeRetentionRepair =
                        runwayViewModel::acknowledgeRetentionRepair,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReconciliationScheduler.runOnce(this)
        runwayViewModel.refresh()
    }

    private fun restartAfterRestore() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: error("runway's launch activity could not be resolved after restore.")
        val component = requireNotNull(launch.component) {
            "runway's launch component could not be resolved after restore."
        }
        startActivity(Intent.makeRestartActivityTask(component))
        finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private fun publishShortcuts() {
        val manager = getSystemService(ShortcutManager::class.java) ?: return
        val folder = ShortcutInfo.Builder(this, FOLDER_SHORTCUT_ID)
            .setShortLabel(getString(R.string.folder_shortcut_short))
            .setLongLabel(getString(R.string.folder_shortcut_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(Intent(this, NativeFolderSettingsActivity::class.java).apply {
                action = ACTION_OPEN_FOLDER_SETTINGS
            })
            .build()
        runCatching { manager.setDynamicShortcuts(listOf(folder)) }
    }

    companion object {
        internal const val ACTION_OPEN_FOLDER_SETTINGS = "dev.deftmartian.runway.OPEN_FOLDER_SETTINGS"
        internal const val ACTION_OPEN_HEALTH_CONNECT_SETTINGS =
            "dev.deftmartian.runway.OPEN_HEALTH_CONNECT_SETTINGS"
        internal const val FOLDER_SHORTCUT_ID = "device-folder"
    }
}
