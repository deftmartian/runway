package dev.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val runwayViewModel: RunwayViewModel by viewModels()
    private val gpxPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runwayViewModel.importGpx(this, it) } }
    private val backupCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { runwayViewModel.backup(this, it) } }
    private val backupRestorer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runwayViewModel.restore(this, it) } }
    private val exportCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { runwayViewModel.export(this, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        publishShortcuts()
        enableEdgeToEdge()
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
                    onRefresh = runwayViewModel::refresh,
                    onAction = runwayViewModel::submitAction,
                    onApplyWorkoutPreview = runwayViewModel::applyWorkoutPreview,
                    onDismissWorkoutPreview = runwayViewModel::dismissWorkoutPreview,
                    onOpenFolder = {
                        startActivity(Intent(this, NativeFolderSettingsActivity::class.java).apply {
                            action = ACTION_OPEN_FOLDER_SETTINGS
                        })
                    },
                    onImportGpx = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")) },
                    onOpenHealthConnect = {
                        startActivity(Intent(this, NativeFolderSettingsActivity::class.java).apply { action = ACTION_OPEN_FOLDER_SETTINGS })
                    },
                    onCreateBackup = { backupCreator.launch("runway-backup.json") },
                    onRestoreBackup = { backupRestorer.launch(arrayOf("application/json", "text/json")) },
                    onExportData = { exportCreator.launch("runway-training-export.json") },
                    onTimeZoneChanged = runwayViewModel::updateTimeZone,
                    onRoutePrivacyChanged = runwayViewModel::updateRoutePrivacy,
                    onHeartRateChanged = runwayViewModel::updateHeartRate,
                    onHealthContextChanged = runwayViewModel::updateHealthContext,
                    onEraseAllData = {
                        ReconciliationScheduler.cancelAll(this)
                        TreeAccessStore(this).disconnect()
                        runwayViewModel.eraseAllData()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReconciliationScheduler.runOnce(this)
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
        internal const val FOLDER_SHORTCUT_ID = "device-folder"
    }
}
