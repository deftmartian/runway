package dev.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRoute
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NativeFolderSettingsActivity : ComponentActivity() {
    private lateinit var treeAccess: TreeAccessStore
    private var uiState = androidx.compose.runtime.mutableStateOf(NativeImportSettingsUiState())
    private var pendingRouteRecordId: String? = null
    private val routeOverrides = mutableMapOf<String, ExerciseRoute>()

    private val chooseDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        when (treeAccess.connect(uri)) {
            TreeAccessMutation.Changed -> {
                ReconciliationScheduler.runOnce(this)
                ReconciliationScheduler.enablePeriodic(this)
                refreshAll(folderMessage = getString(R.string.folder_connected_background))
            }
            TreeAccessMutation.Failed ->
                refreshAll(folderMessage = getString(R.string.folder_connection_failed))
        }
    }

    private val chooseOneOffGpx = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            updateState { copy(oneOffStatus = getString(R.string.share_checking)) }
            val outcome = withContext(Dispatchers.IO) {
                OneOffGpxImport.importUri(this@NativeFolderSettingsActivity, uri)
            }
            updateState { copy(oneOffStatus = getString(oneOffGpxStatus(outcome))) }
        }
    }

    private val requestHealthConnectPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        refreshHealthConnect()
    }

    private val requestExerciseRoute = registerForActivityResult(
        ExerciseRouteRequestContract(),
    ) { route ->
        val recordId = pendingRouteRecordId ?: return@registerForActivityResult
        pendingRouteRecordId = null
        if (route == null) {
            updateState {
                copy(healthStatus = getString(R.string.health_connect_route_not_granted))
            }
            return@registerForActivityResult
        }
        routeOverrides[recordId] = route
        // The first pass already advanced the provider token with the summary. Rebootstrap the
        // bounded 30-day window so the explicitly granted route is reconciled into that record.
        HealthConnectCursorStore(this).clear()
        syncHealthConnectForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        treeAccess = TreeAccessStore(this)
        pendingRouteRecordId = savedInstanceState?.getString(PENDING_ROUTE_RECORD_KEY)
        enableEdgeToEdge()
        if (intent.action == MainActivity.ACTION_OPEN_FOLDER_SETTINGS) {
            getSystemService(ShortcutManager::class.java)
                ?.reportShortcutUsed(MainActivity.FOLDER_SHORTCUT_ID)
        }
        setContent {
            RunwayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    NativeImportSetupScreen(
                        state = uiState.value,
                        initialSection = when (intent.action) {
                            MainActivity.ACTION_OPEN_HEALTH_CONNECT_SETTINGS ->
                                NativeImportSection.HealthConnect
                            MainActivity.ACTION_OPEN_FOLDER_SETTINGS ->
                                NativeImportSection.Folder
                            else -> NativeImportSection.Overview
                        },
                        onPickOneOffGpx = {
                            chooseOneOffGpx.launch(
                                arrayOf(
                                    "application/gpx+xml",
                                    "application/x-gpx+xml",
                                    "application/xml",
                                    "text/xml",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        onChooseFolder = ::openDirectoryPicker,
                        onDisconnectFolder = ::disconnectFolder,
                        onCheckFolder = {
                            ReconciliationScheduler.runOnce(this)
                            updateState {
                                copy(
                                    folderCheckRunning = true,
                                    lastFolderCheck = getString(R.string.last_check_queued),
                                )
                            }
                        },
                        onTogglePeriodicFolderChecks = ::togglePeriodicFolderChecks,
                        onHealthPermission = {
                            updateState { copy(dialog = NativeImportDialog.HealthPermission) }
                        },
                        onHealthSync = ::syncHealthConnectForeground,
                        onToggleHealthBackground = ::toggleHealthBackground,
                        onReturnToRunway = ::returnToRunway,
                        onDismissDialog = { updateState { copy(dialog = NativeImportDialog.None) } },
                        onConfirmDialog = ::confirmDialog,
                    )
                }
            }
        }
        observeWork()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingRouteRecordId?.let { outState.putString(PENDING_ROUTE_RECORD_KEY, it) }
        super.onSaveInstanceState(outState)
    }

    private fun openDirectoryPicker() {
        val initial = when (val state = treeAccess.currentState()) {
            is TreeAccessState.Connected -> state.uri
            is TreeAccessState.PermissionRequired -> state.uri
            TreeAccessState.Missing -> null
        }
        chooseDirectory.launch(initial)
    }

    private fun disconnectFolder() {
        val message = when (treeAccess.disconnect()) {
            TreeAccessMutation.Changed -> R.string.folder_disconnected
            TreeAccessMutation.Failed -> R.string.folder_disconnect_failed
        }
        refreshAll(folderMessage = getString(message))
    }

    private fun togglePeriodicFolderChecks() {
        if (uiState.value.periodicFolderChecks) {
            ReconciliationScheduler.disablePeriodic(this)
        } else if (treeAccess.currentState() is TreeAccessState.Connected) {
            ReconciliationScheduler.enablePeriodic(this)
            ReconciliationScheduler.runOnce(this)
        }
        refreshFolderWorkState()
    }

    private fun confirmDialog(dialog: NativeImportDialog) {
        updateState { copy(dialog = NativeImportDialog.None) }
        when (dialog) {
            NativeImportDialog.HealthPermission ->
                requestHealthConnectPermissions.launch(HEALTH_CONNECT_PERMISSIONS)
            NativeImportDialog.HealthBackgroundPermission ->
                requestHealthConnectPermissions.launch(
                    HEALTH_CONNECT_PERMISSIONS + HEALTH_CONNECT_BACKGROUND_PERMISSION,
                )
            NativeImportDialog.RouteConsent ->
                pendingRouteRecordId?.let(requestExerciseRoute::launch)
            NativeImportDialog.None -> Unit
        }
    }

    private fun syncHealthConnectForeground() {
        if (uiState.value.healthSyncRunning) return
        updateState {
            copy(
                healthSyncRunning = true,
                healthStatus = getString(R.string.health_connect_syncing),
            )
        }
        lifecycleScope.launch {
            val gateway = AndroidHealthConnectGateway(
                context = this@NativeFolderSettingsActivity,
                includeRoutes = true,
                routeOverrides = routeOverrides.toMap(),
            )
            val outcome = withContext(Dispatchers.IO) {
                AndroidStateCoordinator.withImportDataBoundary {
                    HealthConnectSyncCoordinator(
                        gateway = gateway,
                        cursor = HealthConnectCursorStore(this@NativeFolderSettingsActivity),
                        reconcile = runwayServices.healthConnect::reconcile,
                    ).sync()
                }
            }
            updateState {
                copy(
                    healthSyncRunning = false,
                    healthStatus = getString(healthSyncStatus(outcome)),
                )
            }
            gateway.routeConsentRecordId?.let { recordId ->
                pendingRouteRecordId = recordId
                updateState { copy(dialog = NativeImportDialog.RouteConsent) }
            }
            refreshHealthConnect()
        }
    }

    private fun toggleHealthBackground() {
        if (uiState.value.healthBackgroundEnabled) {
            ReconciliationScheduler.disableHealthConnectPeriodic(this)
            refreshHealthWorkState()
            return
        }
        lifecycleScope.launch {
            val gateway = AndroidHealthConnectGateway(this@NativeFolderSettingsActivity)
            val allowed = withContext(Dispatchers.IO) {
                try {
                    gateway.supportsBackgroundRead() && gateway.hasBackgroundPermission()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            }
            if (allowed) {
                ReconciliationScheduler.enableHealthConnectPeriodic(this@NativeFolderSettingsActivity)
                refreshHealthWorkState()
            } else {
                updateState { copy(dialog = NativeImportDialog.HealthBackgroundPermission) }
            }
        }
    }

    private fun refreshAll(folderMessage: String? = null) {
        val tree = treeAccess.currentState()
        updateState {
            copy(
                folderConnected = tree is TreeAccessState.Connected,
                folderPermissionRequired = tree is TreeAccessState.PermissionRequired,
                folderStatus = folderMessage ?: when (tree) {
                    is TreeAccessState.Connected -> getString(R.string.folder_connected)
                    is TreeAccessState.PermissionRequired ->
                        getString(R.string.folder_permission_needed)
                    TreeAccessState.Missing -> getString(R.string.folder_not_connected)
                },
                lastFolderCheck = lastFolderCheckStatus(),
            )
        }
        refreshFolderWorkState()
        refreshHealthConnect()
        refreshHealthWorkState()
    }

    private fun refreshHealthConnect() {
        lifecycleScope.launch {
            val gateway = AndroidHealthConnectGateway(this@NativeFolderSettingsActivity)
            val result = withContext(Dispatchers.IO) {
                val availability = gateway.availability()
                val permissions = availability == HealthConnectAvailability.Available &&
                    try {
                        gateway.hasPermissions()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                val backgroundSupported = availability == HealthConnectAvailability.Available &&
                    runCatching(gateway::supportsBackgroundRead).getOrDefault(false)
                Triple(availability, permissions, backgroundSupported)
            }
            updateState {
                copy(
                    healthAvailable = result.first == HealthConnectAvailability.Available,
                    healthPermissionsGranted = result.second,
                    healthBackgroundSupported = result.third,
                    healthStatus = if (healthSyncRunning) {
                        healthStatus
                    } else {
                        when (result.first) {
                            HealthConnectAvailability.Unavailable ->
                                getString(R.string.health_connect_unavailable)
                            HealthConnectAvailability.UpdateRequired ->
                                getString(R.string.health_connect_update_required)
                            HealthConnectAvailability.Available -> if (result.second) {
                                getString(R.string.health_connect_ready)
                            } else {
                                getString(R.string.health_connect_permission_needed)
                            }
                        }
                    },
                )
            }
        }
    }

    private fun refreshFolderWorkState() {
        lifecycleScope.launch {
            val states = withContext(Dispatchers.IO) {
                val manager = WorkManager.getInstance(this@NativeFolderSettingsActivity)
                val oneTime = manager.getWorkInfosForUniqueWork(
                    ReconciliationScheduler.ONE_TIME_WORK_NAME,
                ).get()
                val periodic = manager.getWorkInfosForUniqueWork(
                    ReconciliationScheduler.PERIODIC_WORK_NAME,
                ).get()
                oneTime to periodic
            }
            updateState {
                copy(
                    folderCheckRunning = states.first.any { it.state == WorkInfo.State.RUNNING },
                    periodicFolderChecks = states.second.any { it.isScheduled() },
                    lastFolderCheck = lastFolderCheckStatus(),
                )
            }
        }
    }

    private fun refreshHealthWorkState() {
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                WorkManager.getInstance(this@NativeFolderSettingsActivity)
                    .getWorkInfosForUniqueWork(ReconciliationScheduler.HEALTH_CONNECT_WORK_NAME)
                    .get()
                    .any { it.isScheduled() }
            }
            updateState { copy(healthBackgroundEnabled = enabled) }
        }
    }

    private fun observeWork() {
        val manager = WorkManager.getInstance(this)
        listOf(
            ReconciliationScheduler.ONE_TIME_WORK_NAME,
            ReconciliationScheduler.PERIODIC_WORK_NAME,
        ).forEach { workName ->
            manager.getWorkInfosForUniqueWorkLiveData(workName).observe(this) {
                refreshFolderWorkState()
            }
        }
        manager.getWorkInfosForUniqueWorkLiveData(
            ReconciliationScheduler.HEALTH_CONNECT_WORK_NAME,
        ).observe(this) {
            refreshHealthWorkState()
        }
    }

    private fun lastFolderCheckStatus(): String {
        val record = ReconciliationStatusStore(this).load()
            ?: return getString(R.string.last_check_never)
        val message = getString(
            when (record.state) {
                ReconciliationWorker.STATE_IMPORTED -> R.string.last_check_imported
                ReconciliationWorker.STATE_DUPLICATE -> R.string.last_check_duplicate
                ReconciliationWorker.STATE_DELETED_PREVIOUSLY ->
                    R.string.last_check_deleted_previously
                ReconciliationWorker.STATE_INVALID -> R.string.last_check_invalid
                ReconciliationWorker.STATE_TOO_LARGE -> R.string.last_check_too_large
                ReconciliationWorker.STATE_FUTURE_ACTIVITY -> R.string.last_check_future
                ReconciliationWorker.STATE_NO_CANDIDATES -> R.string.last_check_empty
                ReconciliationWorker.STATE_SETUP_REQUIRED -> R.string.last_check_setup_required
                ReconciliationWorker.STATE_PERMISSION_REQUIRED ->
                    R.string.last_check_permission_required
                ReconciliationWorker.STATE_PROVIDER_ERROR -> R.string.last_check_provider_error
                ReconciliationWorker.STATE_SCAN_LIMIT -> R.string.last_check_scan_limit
                ReconciliationWorker.STATE_SETTLING -> R.string.last_check_settling
                ReconciliationWorker.STATE_RETRYING -> R.string.last_check_retrying
                ReconciliationWorker.STATE_STALE_FOLDER -> R.string.last_check_stale
                else -> R.string.last_check_unknown
            },
        )
        return if (record.scanTruncated && record.state != ReconciliationWorker.STATE_SCAN_LIMIT) {
            getString(R.string.last_check_scan_limit_suffix, message)
        } else {
            message
        }
    }

    private fun healthSyncStatus(result: HealthSyncResult): Int = when (result) {
        HealthSyncResult.Synced -> R.string.health_connect_synced
        HealthSyncResult.PermissionRequired -> R.string.health_connect_permission_needed
        HealthSyncResult.Unavailable -> R.string.health_connect_unavailable
        HealthSyncResult.UpdateRequired -> R.string.health_connect_update_required
        HealthSyncResult.Retryable -> R.string.health_connect_retryable
        HealthSyncResult.NeedsAttention -> R.string.health_connect_needs_attention
    }

    private fun returnToRunway() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }

    private fun updateState(transform: NativeImportSettingsUiState.() -> NativeImportSettingsUiState) {
        uiState.value = uiState.value.transform()
    }

    private fun WorkInfo.isScheduled(): Boolean =
        state == WorkInfo.State.ENQUEUED ||
            state == WorkInfo.State.RUNNING ||
            state == WorkInfo.State.BLOCKED

    companion object {
        private const val PENDING_ROUTE_RECORD_KEY = "pending_route_record_id"
    }
}
