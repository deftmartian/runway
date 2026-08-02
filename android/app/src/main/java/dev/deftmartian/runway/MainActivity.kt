package dev.deftmartian.runway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
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
    private var pendingNotificationChange: PendingNotificationChange? = null
    private var pendingNotificationDestination: NativeDestination? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingNotificationChange
        pendingNotificationChange = null
        if (granted && pending != null) {
            requestNotificationChange(pending)
        } else if (!granted) {
            runwayViewModel.notificationPermissionDenied()
        }
    }
    private val gpxPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runwayViewModel.importGpx(this, it) } }
    private val backupCreator = registerForActivityResult(ActivityResultContracts.CreateDocument(LocalBackupDocumentContract.MIME_TYPE)) { uri -> uri?.let { runwayViewModel.backup(this, it) } }
    private val backupRestorer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runwayViewModel.restore(this, it) } }
    private val exportCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { runwayViewModel.export(this, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNotificationChange = savedInstanceState?.pendingNotificationChange()
        handleNavigationIntent(intent)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runwayViewModel.state.collect { state ->
                    if (state is RunwayUiState.Ready) {
                        pendingNotificationDestination?.let { destination ->
                            pendingNotificationDestination = null
                            runwayViewModel.selectDestination(destination)
                        }
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
                    onRunReminderChanged = { enabled, minuteOfDay ->
                        requestNotificationChange(
                            PendingNotificationChange.RunReminder(enabled, minuteOfDay),
                        )
                    },
                    onFolderImportAlertsChanged = { enabled ->
                        requestNotificationChange(
                            PendingNotificationChange.FolderImportAlerts(enabled),
                        )
                    },
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
        pendingNotificationChange?.takeIf {
            RunwayNotificationManager.notificationsAllowed(this, it.channelId)
        }?.let { pending ->
            pendingNotificationChange = null
            applyNotificationChange(pending)
        }
        ReconciliationScheduler.runOnce(this)
        lifecycleScope.launch {
            RunReminderScheduler.reconcile(this@MainActivity)
            RunwayNotificationManager.deliverPendingFolderImportAlerts(this@MainActivity)
        }
        runwayViewModel.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingNotificationChange?.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun requestNotificationChange(change: PendingNotificationChange) {
        if (
            !change.enabled ||
            RunwayNotificationManager.notificationsAllowed(this, change.channelId)
        ) {
            pendingNotificationChange = null
            applyNotificationChange(change)
            return
        }
        pendingNotificationChange = change
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, change.channelId)
                },
            )
        }
    }

    private fun applyNotificationChange(change: PendingNotificationChange) {
        when (change) {
            is PendingNotificationChange.RunReminder ->
                runwayViewModel.updateRunReminder(change.enabled, change.minuteOfDay)
            is PendingNotificationChange.FolderImportAlerts ->
                runwayViewModel.updateFolderImportAlerts(change.enabled)
        }
    }

    private fun handleNavigationIntent(intent: Intent?) {
        pendingNotificationDestination = notificationDestination(intent?.action) ?: return
        val current = runwayViewModel.state.value
        if (current is RunwayUiState.Ready) {
            val destination = pendingNotificationDestination ?: return
            pendingNotificationDestination = null
            runwayViewModel.selectDestination(destination)
        }
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
        internal const val ACTION_OPEN_CALENDAR = "dev.deftmartian.runway.OPEN_CALENDAR"
        internal const val ACTION_OPEN_INBOX = "dev.deftmartian.runway.OPEN_INBOX"
        internal const val FOLDER_SHORTCUT_ID = "device-folder"
    }
}

private sealed interface PendingNotificationChange {
    val enabled: Boolean
    val channelId: String

    data class RunReminder(
        override val enabled: Boolean,
        val minuteOfDay: Int,
    ) : PendingNotificationChange {
        override val channelId: String = RunwayNotificationManager.RUN_REMINDER_CHANNEL_ID
    }

    data class FolderImportAlerts(
        override val enabled: Boolean,
    ) : PendingNotificationChange {
        override val channelId: String = RunwayNotificationManager.IMPORT_REVIEW_CHANNEL_ID
    }
}

private fun PendingNotificationChange.save(outState: Bundle) {
    outState.putString(
        PENDING_NOTIFICATION_KIND,
        when (this) {
            is PendingNotificationChange.RunReminder -> PENDING_NOTIFICATION_RUN_REMINDER
            is PendingNotificationChange.FolderImportAlerts -> PENDING_NOTIFICATION_FOLDER_IMPORT
        },
    )
    outState.putBoolean(PENDING_NOTIFICATION_ENABLED, enabled)
    if (this is PendingNotificationChange.RunReminder) {
        outState.putInt(PENDING_NOTIFICATION_MINUTE, minuteOfDay)
    }
}

private fun Bundle.pendingNotificationChange(): PendingNotificationChange? =
    when (getString(PENDING_NOTIFICATION_KIND)) {
        PENDING_NOTIFICATION_RUN_REMINDER -> PendingNotificationChange.RunReminder(
            enabled = getBoolean(PENDING_NOTIFICATION_ENABLED),
            minuteOfDay = getInt(
                PENDING_NOTIFICATION_MINUTE,
                dev.deftmartian.runway.data.NotificationPreferencesEntity
                    .DEFAULT_REMINDER_MINUTE_OF_DAY,
            ),
        )
        PENDING_NOTIFICATION_FOLDER_IMPORT -> PendingNotificationChange.FolderImportAlerts(
            enabled = getBoolean(PENDING_NOTIFICATION_ENABLED),
        )
        else -> null
    }

private const val PENDING_NOTIFICATION_KIND = "pending_notification_kind"
private const val PENDING_NOTIFICATION_ENABLED = "pending_notification_enabled"
private const val PENDING_NOTIFICATION_MINUTE = "pending_notification_minute"
private const val PENDING_NOTIFICATION_RUN_REMINDER = "run_reminder"
private const val PENDING_NOTIFICATION_FOLDER_IMPORT = "folder_import"

internal fun notificationDestination(action: String?): NativeDestination? = when (action) {
    MainActivity.ACTION_OPEN_CALENDAR -> NativeDestination.Calendar
    MainActivity.ACTION_OPEN_INBOX -> NativeDestination.Inbox
    else -> null
}
