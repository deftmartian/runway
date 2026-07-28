package dev.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRoute
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.Executors

class NativeFolderSettingsActivity : ComponentActivity() {
    private enum class AutomationMutation {
        Changed,
        SetupRequired,
        ServerChanged,
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var treeAccessStore: TreeAccessStore
    private lateinit var credentialStore: AndroidCredentialStore
    private lateinit var mobileSessionStore: MobileSessionStore
    private lateinit var serverConnection: ServerConnection
    private lateinit var serverOrigin: String

    private var uiState by mutableStateOf(NativeImportSetupUiState())

    private var backgroundEnabled = false
    private val workRunningByName = mutableMapOf<String, Boolean>()
    private var pickerConnection: ServerConnection? = null
    private var oneOffPickerConnection: ServerConnection? = null
    private var healthConnectBackgroundEnabled = false
    private var healthConnectPermissionsGranted = false
    private var healthConnectBackgroundSupported = false
    private var pendingRouteConsentRecordId: String? = null
    private val routeOverrides = mutableMapOf<String, ExerciseRoute>()
    private var routePromptConsumed = false

    private val requestHealthConnectPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        queryHealthConnectPermissions()
        refreshHealthConnectState()
    }

    private val requestExerciseRoute = registerForActivityResult(ExerciseRouteRequestContract()) { route ->
        val recordId = pendingRouteConsentRecordId ?: return@registerForActivityResult
        pendingRouteConsentRecordId = null
        if (route != null) {
            routeOverrides[recordId] = route
            // The prior foreground pass has already advanced the change cursor. Rebaseline so the
            // consent result is sent as an explicit session upsert rather than waiting for a new
            // provider mutation that may never arrive.
            HealthConnectCursorStore(this, serverOrigin).clear()
            syncHealthConnectForeground(newAction = false)
        } else {
            updateUi { copy(healthConnectStatus = getString(R.string.health_connect_route_not_granted)) }
        }
    }

    private val chooseDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val expected = pickerConnection
        pickerConnection = null
        if (uri == null || expected == null) return@registerForActivityResult
        executor.execute {
            val result = treeAccessStore.connect(uri, expected)
            val automationStarted =
                result == TreeAccessMutation.Changed && startAutomationIfReady()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    TreeAccessMutation.Changed -> {
                        if (automationStarted) {
                            backgroundEnabled = true
                            updateUi { copy(folderStatus = getString(R.string.folder_connected_background), lastCheckStatus = getString(R.string.last_check_queued)) }
                        } else {
                            updateUi { copy(folderStatus = getString(R.string.folder_connected_pairing_needed)) }
                        }
                    }
                    TreeAccessMutation.Conflict -> {
                        finish()
                        return@runOnUiThread
                    }
                    TreeAccessMutation.Failed -> updateUi { copy(folderStatus = getString(R.string.folder_connection_failed)) }
                }
                refreshScreen(keepFolderStatus = true)
            }
        }
    }

    private val chooseOneOffGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val expected = oneOffPickerConnection
        oneOffPickerConnection = null
        if (uri == null || expected == null) return@registerForActivityResult
        executor.execute {
            val outcome = OneOffGpxImport.importUri(this, expected, uri)
            runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                updateUi { copy(oneOffImportStatus = getString(oneOffGpxStatus(outcome))) }
                refreshScreen(keepPairingStatus = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configuredConnection = ServerConnectionStore(this).currentConnection()
        if (configuredConnection == null) {
            startActivity(Intent(this, ServerConnectionActivity::class.java))
            finish()
            return
        }
        serverConnection = configuredConnection
        serverOrigin = configuredConnection.origin
        pickerConnection = savedInstanceState?.let {
            restorePickerConnection(it, PICKER_ORIGIN_KEY, PICKER_GENERATION_KEY)
        }
        oneOffPickerConnection = savedInstanceState?.let {
            restorePickerConnection(it, ONE_OFF_PICKER_ORIGIN_KEY, ONE_OFF_PICKER_GENERATION_KEY)
        }
        enableEdgeToEdge()
        if (intent.action == MainActivity.ACTION_OPEN_FOLDER_SETTINGS) {
            getSystemService(ShortcutManager::class.java)
                ?.reportShortcutUsed(MainActivity.FOLDER_SHORTCUT_ID)
        }

        treeAccessStore = TreeAccessStore(this)
        credentialStore = AndroidCredentialStore(this, serverOrigin)
        mobileSessionStore = MobileSessionStore(this, serverOrigin)
        val setupStillCurrent = ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
            if (credentialStore.load() == null) ReconciliationScheduler.cancelAll(this)
            true
        } == true
        if (!setupStillCurrent) {
            finish()
            return
        }
        uiState = uiState.copy(
            serverOrigin = serverOrigin,
            deviceLabel = savedInstanceState?.getString(DEVICE_LABEL_KEY)
                ?: getString(R.string.device_label_default),
        )
        setContent {
            RunwayTheme {
                NativeImportSetupScreen(
                    state = uiState,
                    onDeviceLabelChange = { label -> updateUi { copy(deviceLabel = label.take(DEVICE_LABEL_MAX_LENGTH)) } },
                    onPrimaryAction = ::performPrimaryAction,
                    onChangeServer = ::changeServer,
                    onForgetAccount = ::beginForgetAccount,
                    onChangeFolder = { if (requireCurrentServer()) openDirectoryPicker() },
                    onDisconnectFolder = ::disconnectFolder,
                    onBackgroundAction = ::toggleBackground,
                    onHealthPermission = ::showHealthPermissionDialog,
                    onHealthSync = { syncHealthConnectForeground() },
                    onHealthBackground = ::toggleHealthBackground,
                    onPickOneOffGpx = ::openOneOffGpxPicker,
                    onReturnToRunway = ::returnToRunway,
                    onDismissDialog = ::dismissDialog,
                    onConfirmDialog = ::confirmDialog,
                )
            }
        }
        refreshScreen()
        refreshBackgroundWorkState()
        refreshHealthConnectWorkState()
        observeReconciliationWork()
        refreshLastCheckStatus()
        verifyPairingStatus()
        queryHealthConnectPermissions()
    }

    override fun onDestroy() {
        // A pairing code is single-use. Let an in-flight exchange reach credential persistence even
        // when this Activity is recreated for rotation; UI delivery is still lifecycle-gated below.
        executor.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pickerConnection?.let { connection ->
            outState.putString(PICKER_ORIGIN_KEY, connection.origin)
            outState.putLong(PICKER_GENERATION_KEY, connection.generation)
        }
        oneOffPickerConnection?.let { connection ->
            outState.putString(ONE_OFF_PICKER_ORIGIN_KEY, connection.origin)
            outState.putLong(ONE_OFF_PICKER_GENERATION_KEY, connection.generation)
        }
        outState.putString(DEVICE_LABEL_KEY, uiState.deviceLabel)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::serverConnection.isInitialized && !isCurrentServer()) finish()
    }

    private fun changeServer() {
        if (!requireCurrentServer()) return
        startActivity(Intent(this, ServerConnectionActivity::class.java).apply {
            action = ServerConnectionActivity.ACTION_CHANGE_SERVER
        })
        finish()
    }

    private fun disconnectFolder() {
        if (!requireCurrentServer()) return
        executor.execute {
            val result = treeAccessStore.disconnect(serverConnection)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (result == TreeAccessMutation.Conflict) {
                    finish()
                    return@runOnUiThread
                }
                backgroundEnabled = false
                updateUi { copy(folderStatus = getString(if (result == TreeAccessMutation.Changed) R.string.folder_disconnected else R.string.folder_connection_failed)) }
                refreshScreen(keepFolderStatus = true)
                refreshLastCheckStatus()
            }
        }
    }

    private fun toggleBackground() {
        if (!requireCurrentServer()) return
        val enable = !backgroundEnabled
        when (setPeriodicAutomationEnabled(enable)) {
            AutomationMutation.Changed -> {
                backgroundEnabled = enable
                updateUi { copy(backgroundStatus = getString(if (enable) R.string.background_enabled else R.string.background_disabled)) }
                refreshAutomationState(keepStatus = true)
            }
            AutomationMutation.SetupRequired -> refreshScreen()
            AutomationMutation.ServerChanged -> finish()
        }
    }

    private fun showHealthPermissionDialog() {
        if (requireCurrentServer()) updateUi { copy(dialog = NativeImportSetupDialog.HealthPermission) }
    }

    private fun toggleHealthBackground() {
        if (!requireCurrentServer()) return
        if (healthConnectBackgroundEnabled) {
            ReconciliationScheduler.disableHealthConnectPeriodic(this)
            healthConnectBackgroundEnabled = false
            refreshHealthConnectState()
            return
        }
        updateUi { copy(healthBackgroundActionEnabled = false) }
        executor.execute {
            val gateway = AndroidHealthConnectGateway(this)
            val granted = runCatching { gateway.supportsBackgroundRead() && gateway.hasBackgroundPermission() }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                if (!granted) updateUi { copy(dialog = NativeImportSetupDialog.HealthBackgroundPermission) }
                else {
                    ReconciliationScheduler.enableHealthConnectPeriodic(this)
                    healthConnectBackgroundEnabled = true
                }
                refreshHealthConnectState()
            }
        }
    }

    private fun returnToRunway() {
        if (!requireCurrentServer()) return
        startActivity(Intent(this, ServerConnectionActivity::class.java))
        finish()
    }

    private fun confirmDialog(dialog: NativeImportSetupDialog) {
        updateUi { copy(dialog = NativeImportSetupDialog.None) }
        when (dialog) {
            NativeImportSetupDialog.HealthPermission -> requestHealthConnectPermissions.launch(HEALTH_CONNECT_PERMISSIONS)
            NativeImportSetupDialog.HealthBackgroundPermission -> requestHealthConnectPermissions.launch(HEALTH_CONNECT_PERMISSIONS + HEALTH_CONNECT_BACKGROUND_PERMISSION)
            NativeImportSetupDialog.ForgetUnrevoked -> beginForgetAccount(allowUnrevoked = true)
            NativeImportSetupDialog.RouteConsent -> pendingRouteConsentRecordId?.let(requestExerciseRoute::launch)
            NativeImportSetupDialog.None -> Unit
        }
    }

    private fun dismissDialog() {
        if (uiState.dialog == NativeImportSetupDialog.RouteConsent) pendingRouteConsentRecordId = null
        updateUi { copy(dialog = NativeImportSetupDialog.None) }
    }

    private fun performPrimaryAction() {
        if (!requireCurrentServer()) return
        when {
            credentialStore.load() == null && mobileSessionStore.loadSession() == null -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            credentialStore.load() == null -> pairAccount()
            treeAccessStore.currentState(serverConnection) !is TreeAccessState.Connected -> openDirectoryPicker()
            else -> {
                when (queueReconciliationIfReady()) {
                    AutomationMutation.Changed -> {
                        updateUi { copy(folderStatus = getString(R.string.check_queued), lastCheckStatus = getString(R.string.last_check_queued)) }
                    }
                    AutomationMutation.SetupRequired -> refreshScreen()
                    AutomationMutation.ServerChanged -> finish()
                }
            }
        }
    }

    private fun openDirectoryPicker() {
        if (!requireCurrentServer()) return
        val initialUri = when (val state = treeAccessStore.currentState(serverConnection)) {
            is TreeAccessState.Connected -> state.uri
            is TreeAccessState.PermissionRequired -> state.uri
            TreeAccessState.Missing -> null
        }
        pickerConnection = serverConnection
        chooseDirectory.launch(initialUri)
    }

    private fun openOneOffGpxPicker() {
        if (!requireCurrentServer() || credentialStore.load() == null) return
        oneOffPickerConnection = serverConnection
        chooseOneOffGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
    }

    private fun pairAccount() {
        if (!requireCurrentServer()) return
        val session = mobileSessionStore.loadSession()
        if (session == null) {
            updateUi { copy(pairingStatus = getString(R.string.pairing_sign_in_needed)) }
            refreshScreen(keepPairingStatus = true)
            return
        }
        val label = uiState.deviceLabel.trim()
        if (label.isBlank()) {
            updateUi { copy(pairingStatus = getString(R.string.pairing_label_invalid)) }
            return
        }
        updateUi { copy(primaryActionEnabled = false, pairingStatus = getString(R.string.pairing_in_progress)) }
        val expectedCredentialState = credentialStore.snapshot()
        executor.execute {
            if (!isCurrentServer()) return@execute
            val pairing = MobileApiClient(serverOrigin).createImportPairing(session, label)
            if (!isCurrentServer()) return@execute
            if (pairing !is MobileImportPairingResult.Ready) {
                if (pairing == MobileImportPairingResult.Unauthorized) {
                    mobileSessionStore.clear()
                }
                runOnUiThread {
                    if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                    updateUi { copy(pairingStatus = getString(when (pairing) {
                            MobileImportPairingResult.Unauthorized ->
                                R.string.pairing_sign_in_needed
                            is MobileImportPairingResult.Rejected ->
                                R.string.pairing_failed
                            MobileImportPairingResult.Retryable ->
                                R.string.pairing_failed
                            is MobileImportPairingResult.Ready ->
                                error("Handled before UI delivery")
                        })) }
                    refreshScreen(keepPairingStatus = true)
                }
                return@execute
            }
            val result = RunwayApiClient(serverOrigin).pair(pairing.code, pairing.label)
            if (!isCurrentServer()) return@execute
            val completion = completePairing(
                result = result,
                saveCredential = { credential ->
                    saveCredentialIfCurrent(expectedCredentialState, credential)
                },
            )
            val automationStarted =
                completion is PairingCompletion.Connected && startAutomationIfReady(completion.credential)
            runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                when (completion) {
                    is PairingCompletion.Connected -> {
                        updateUi { copy(pairingStatus = getString(R.string.pairing_connected)) }
                        backgroundEnabled = automationStarted
                        if (automationStarted) updateUi { copy(lastCheckStatus = getString(R.string.last_check_queued)) }
                    }
                    PairingCompletion.Invalid -> updateUi { copy(pairingStatus = getString(R.string.pairing_invalid)) }
                    PairingCompletion.Retryable -> updateUi { copy(pairingStatus = getString(R.string.pairing_failed)) }
                    PairingCompletion.StorageFailed -> updateUi { copy(pairingStatus = getString(R.string.pairing_store_failed)) }
                }
                refreshScreen(keepPairingStatus = true)
            }
        }
    }

    private fun verifyPairingStatus() {
        if (!isCurrentServer()) return
        val credentialState = credentialStore.snapshot()
        val credential = credentialState.credential ?: return
        executor.execute {
            val result = credentialStore.useIfCurrent(credentialState) { current ->
                if (!isCurrentServer()) return@useIfCurrent DeviceStatusApiResult.Retryable
                RunwayApiClient(serverOrigin).status(current)
            } ?: return@execute
            if (!isCurrentServer()) return@execute
            val credentialCleared = if (result == DeviceStatusApiResult.Unauthorized) {
                ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
                    if (!credentialStore.clearIfCurrent(credentialState)) {
                        false
                    } else {
                        HandledImportStore(this).clearForDevice(credential.deviceId)
                        ReconciliationScheduler.cancelAll(this)
                        ReconciliationStatusStore(this).record(
                            ReconciliationWorker.STATE_PAIRING_REQUIRED,
                        )
                        true
                    }
                } == true
            } else {
                false
            }
            if (result == DeviceStatusApiResult.Unauthorized && !credentialCleared) return@execute
            if (result is DeviceStatusApiResult.Connected &&
                result.importGeneration != credential.importGeneration &&
                !credentialStore.replace(credentialState, credential.copy(importGeneration = result.importGeneration))
            ) return@execute
            runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                when (result) {
                    is DeviceStatusApiResult.Connected -> updateUi { copy(pairingStatus = getString(R.string.pairing_connected)) }
                    DeviceStatusApiResult.Unauthorized -> {
                        backgroundEnabled = false
                        updateUi { copy(pairingStatus = getString(R.string.pairing_expired_or_revoked)) }
                        refreshScreen(keepPairingStatus = true)
                        refreshLastCheckStatus()
                    }
                    DeviceStatusApiResult.Retryable -> {
                        updateUi { copy(pairingStatus = getString(R.string.pairing_status_unavailable)) }
                    }
                }
            }
        }
    }

    private fun refreshBackgroundWorkState() {
        executor.execute {
            if (!isCurrentServer()) return@execute
            val enabled = runCatching {
                WorkManager.getInstance(this)
                    .getWorkInfosForUniqueWork(PERIODIC_WORK_NAME)
                    .get()
                    .any { info ->
                        info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED
                    }
            }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                backgroundEnabled = enabled
                refreshAutomationState()
            }
        }
    }

    private fun refreshHealthConnectWorkState() {
        executor.execute {
            if (!isCurrentServer()) return@execute
            val enabled = runCatching {
                WorkManager.getInstance(this)
                    .getWorkInfosForUniqueWork(ReconciliationScheduler.HEALTH_CONNECT_WORK_NAME)
                    .get()
                    .any { info ->
                        info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED
                    }
            }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                healthConnectBackgroundEnabled = enabled
                refreshHealthConnectState()
            }
        }
    }

    private fun observeReconciliationWork() {
        listOf(
            ReconciliationScheduler.ONE_TIME_WORK_NAME,
            ReconciliationScheduler.PERIODIC_WORK_NAME,
        ).forEach { workName ->
            WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(workName)
                .observe(this) { workInfos ->
                    if (!isCurrentServer()) return@observe
                    workRunningByName[workName] = workInfos.any { it.state == WorkInfo.State.RUNNING }
                    refreshLastCheckStatus()
                }
        }
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ReconciliationScheduler.HEALTH_CONNECT_WORK_NAME)
            .observe(this) { workInfos ->
                if (!isCurrentServer()) return@observe
                healthConnectBackgroundEnabled = workInfos.any { info ->
                    info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.BLOCKED
                }
                refreshHealthConnectState()
            }
    }

    private fun refreshLastCheckStatus() {
        if (workRunningByName.values.any { it }) {
            updateUi { copy(lastCheckStatus = getString(R.string.last_check_running)) }
            return
        }

        val record = ReconciliationStatusStore(this).load()
        if (record == null) {
            updateUi { copy(lastCheckStatus = getString(R.string.last_check_never)) }
            return
        }
        var text = getString(
            when (record.state) {
                ReconciliationWorker.STATE_IMPORTED -> R.string.last_check_imported
                ReconciliationWorker.STATE_DUPLICATE -> R.string.last_check_duplicate
                ReconciliationWorker.STATE_QUARANTINED -> R.string.last_check_quarantined
                ReconciliationWorker.STATE_NO_CANDIDATES -> R.string.last_check_empty
                ReconciliationWorker.STATE_PAIRING_REQUIRED -> R.string.last_check_pairing_required
                ReconciliationWorker.STATE_PERMISSION_REQUIRED -> R.string.last_check_permission_required
                ReconciliationWorker.STATE_PROVIDER_ERROR -> R.string.last_check_provider_error
                ReconciliationWorker.STATE_SCAN_LIMIT -> R.string.last_check_scan_limit
                ReconciliationWorker.STATE_SETTLING -> R.string.last_check_settling
                ReconciliationWorker.STATE_RETRYING -> R.string.last_check_retrying
                ReconciliationWorker.STATE_STALE_CONNECTION -> R.string.last_check_stale
                ReconciliationWorker.STATE_SERVER_REQUIRED -> R.string.last_check_server_required
                else -> R.string.last_check_unknown
            },
        )
        if (record.backlog > 0) {
            text = resources.getQuantityString(
                R.plurals.last_check_backlog,
                record.backlog,
                text,
                record.backlog,
            )
        }
        if (record.scanTruncated && record.state != ReconciliationWorker.STATE_SCAN_LIMIT) {
            text = getString(R.string.last_check_scan_limit_suffix, text)
        }
        updateUi { copy(lastCheckStatus = text) }
    }

    private fun refreshScreen(
        keepPairingStatus: Boolean = false,
        keepFolderStatus: Boolean = false,
    ) {
        val accountConnected = credentialStore.load() != null
        val signedIn = mobileSessionStore.loadSession() != null
        val folderState = treeAccessStore.currentState(serverConnection)

        updateUi {
            copy(
                showPairingForm = !accountConnected && signedIn,
                showForgetAccount = accountConnected,
                oneOffImportEnabled = accountConnected,
                pairingStatus = if (keepPairingStatus) pairingStatus else getString(when {
                    accountConnected -> R.string.pairing_connected
                    signedIn -> R.string.pairing_intro
                    else -> R.string.pairing_sign_in_needed
                }),
                showChangeFolder = folderState is TreeAccessState.Connected,
                showDisconnectFolder = folderState !is TreeAccessState.Missing,
                folderStatus = if (keepFolderStatus) folderStatus else getString(when (folderState) {
                    is TreeAccessState.Connected -> R.string.folder_connected
                    is TreeAccessState.PermissionRequired -> R.string.folder_permission_needed
                    TreeAccessState.Missing -> R.string.folder_not_connected
                }),
            )
        }

        val primaryLabel = when {
            !accountConnected && !signedIn -> R.string.pairing_open_sign_in
            !accountConnected -> R.string.pair_device
            folderState is TreeAccessState.PermissionRequired -> R.string.restore_folder_access
            folderState is TreeAccessState.Missing -> R.string.choose_folder
            else -> R.string.check_now
        }
        updateUi {
            copy(
                primaryActionLabel = getString(primaryLabel),
                primaryActionEnabled = true,
                setupStatus = getString(when {
                    !accountConnected -> R.string.setup_connect_account
                    folderState is TreeAccessState.PermissionRequired -> R.string.setup_restore_folder
                    folderState is TreeAccessState.Missing -> R.string.setup_choose_folder
                    else -> R.string.setup_ready
                }),
                setupReady = accountConnected && folderState !is TreeAccessState.PermissionRequired,
            )
        }
        refreshAutomationState()
        refreshHealthConnectState()
    }

    private fun refreshHealthConnectState() {
        val gateway = AndroidHealthConnectGateway(this)
        val availability = gateway.availability()
        val connected = credentialStore.load() != null
        val permitted = availability == HealthConnectAvailability.Available && healthConnectPermissionsGranted
        updateUi {
            copy(
                showHealthPermission = connected && !permitted,
                showHealthSync = connected && permitted,
                showHealthBackground = connected && permitted && healthConnectBackgroundSupported,
                healthSyncActionEnabled = connected && permitted,
                healthBackgroundActionEnabled = connected && permitted && healthConnectBackgroundSupported,
                healthBackgroundActionLabel = getString(if (healthConnectBackgroundEnabled) R.string.health_connect_disable_background else R.string.health_connect_enable_background),
                healthConnectStatus = getString(when {
                    !connected -> R.string.health_connect_pairing_needed
                    availability == HealthConnectAvailability.Unavailable -> R.string.health_connect_unavailable
                    availability == HealthConnectAvailability.UpdateRequired -> R.string.health_connect_update_required
                    !permitted -> R.string.health_connect_permission_needed
                    HealthConnectCursorStore(this@NativeFolderSettingsActivity, serverOrigin).needsAttention() -> R.string.health_connect_needs_attention
                    healthConnectBackgroundEnabled -> R.string.health_connect_background_enabled
                    else -> R.string.health_connect_ready
                }),
            )
        }
    }

    private fun queryHealthConnectPermissions() {
        executor.execute {
            val gateway = AndroidHealthConnectGateway(this)
            val available = gateway.availability() == HealthConnectAvailability.Available
            val granted = available && runCatching { gateway.hasPermissions() }.getOrDefault(false)
            val backgroundSupported = available &&
                runCatching { gateway.supportsBackgroundRead() }.getOrDefault(false)
            val backgroundGranted = backgroundSupported &&
                runCatching { gateway.hasBackgroundPermission() }.getOrDefault(false)
            if (!backgroundSupported || !backgroundGranted) {
                ReconciliationScheduler.disableHealthConnectPeriodic(this)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                healthConnectPermissionsGranted = granted
                healthConnectBackgroundSupported = backgroundSupported
                refreshHealthConnectState()
            }
        }
    }

    private fun syncHealthConnectForeground(newAction: Boolean = true) {
        if (!requireCurrentServer()) return
        if (newAction) {
            routePromptConsumed = false
            routeOverrides.clear()
        }
        val credentialState = credentialStore.snapshot()
        if (credentialState.credential == null) {
            refreshHealthConnectState()
            return
        }
        updateUi { copy(healthSyncActionEnabled = false, healthConnectStatus = getString(R.string.health_connect_syncing)) }
        executor.execute {
            val cursor = HealthConnectCursorStore(this, serverOrigin)
            val refreshed = refreshHealthCredential(
                AndroidHealthCredentialRepository(credentialStore),
                credentialState,
                cursor,
            ) { credential -> RunwayApiClient(serverOrigin).status(credential) }
            val currentState = when (refreshed) {
                is HealthCredentialRefresh.Ready -> refreshed.state
                HealthCredentialRefresh.PairingRequired -> {
                    deliverHealthSyncOutcome(HealthSyncResult.PairingRequired, null)
                    return@execute
                }
                HealthCredentialRefresh.Retryable -> {
                    deliverHealthSyncOutcome(HealthSyncResult.Retryable, null)
                    return@execute
                }
            }
            val gateway = AndroidHealthConnectGateway(this, includeRoutes = true, routeOverrides = routeOverrides)
            val outcome = HealthConnectSyncCoordinator(
                gateway = gateway,
                send = { _, payload ->
                    if (!ServerConnectionStore(this).isCurrent(serverConnection)) {
                        return@HealthConnectSyncCoordinator HealthConnectApiResult.Retryable
                    }
                    credentialStore.useIfCurrent(currentState) { current ->
                        RunwayApiClient(serverOrigin).syncHealthConnectChanges(current, payload)
                    } ?: HealthConnectApiResult.Retryable
                },
                cursor = cursor,
            ).sync(serverConnection, currentState)
            if (outcome == HealthSyncResult.PairingRequired) {
                ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
                    if (credentialStore.clearIfCurrent(currentState)) {
                        currentState.credential?.let { credential ->
                            HandledImportStore(this).clearForDevice(credential.deviceId)
                        }
                        cursor.clearAll()
                        ReconciliationScheduler.cancelAll(this)
                        true
                    } else {
                        false
                    }
                }
            }
            deliverHealthSyncOutcome(outcome, gateway)
        }
    }

    private fun deliverHealthSyncOutcome(outcome: HealthSyncResult, gateway: AndroidHealthConnectGateway?) {
        runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                updateUi { copy(healthConnectStatus = getString(when (outcome) {
                        HealthSyncResult.Synced -> R.string.health_connect_synced
                        HealthSyncResult.PermissionRequired -> R.string.health_connect_permission_needed
                        HealthSyncResult.Unavailable -> R.string.health_connect_unavailable
                        HealthSyncResult.UpdateRequired -> R.string.health_connect_update_required
                        HealthSyncResult.PairingRequired -> R.string.health_connect_pairing_needed
                        HealthSyncResult.Retryable -> R.string.health_connect_retryable
                        HealthSyncResult.NeedsAttention -> R.string.health_connect_needs_attention
                    })) }
                refreshHealthConnectState()
                gateway?.routeConsentRecordId?.takeIf { !routePromptConsumed }?.let { recordId ->
                    pendingRouteConsentRecordId = recordId
                    routePromptConsumed = true
                    updateUi { copy(dialog = NativeImportSetupDialog.RouteConsent) }
                }
        }
    }

    private fun refreshAutomationState(keepStatus: Boolean = false) {
        val ready = isReady()
        updateUi { copy(backgroundActionEnabled = ready, backgroundActionLabel = getString(if (backgroundEnabled) R.string.disable_background else R.string.enable_background)) }
        if (!keepStatus) {
            updateUi { copy(backgroundStatus = getString(when {
                    !ready -> R.string.background_setup_required
                    backgroundEnabled -> R.string.background_enabled
                    else -> R.string.background_disabled
                })) }
        }
    }

    private fun isReady(): Boolean =
        credentialStore.load() != null &&
            treeAccessStore.currentState(serverConnection) is TreeAccessState.Connected

    private fun saveCredentialIfCurrent(
        expectedCredentialState: AndroidCredentialState,
        credential: AndroidCredential,
    ): Boolean = ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
        credentialStore.replace(expectedCredentialState, credential)
    } == true

    private fun startAutomationIfReady(expectedCredential: AndroidCredential? = null): Boolean =
        ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
            val credential = credentialStore.snapshot().credential ?: return@mutateIfCurrent false
            if (expectedCredential != null && credential != expectedCredential) {
                return@mutateIfCurrent false
            }
            if (treeAccessStore.currentState(serverConnection) !is TreeAccessState.Connected) {
                return@mutateIfCurrent false
            }
            ReconciliationScheduler.runOnce(this)
            ReconciliationScheduler.enablePeriodic(this)
            true
        } == true

    private fun setPeriodicAutomationEnabled(enable: Boolean): AutomationMutation =
        ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
            if (
                credentialStore.load() == null ||
                treeAccessStore.currentState(serverConnection) !is TreeAccessState.Connected
            ) {
                return@mutateIfCurrent AutomationMutation.SetupRequired
            }
            if (enable) {
                ReconciliationScheduler.enablePeriodic(this)
            } else {
                ReconciliationScheduler.disablePeriodic(this)
            }
            AutomationMutation.Changed
        } ?: AutomationMutation.ServerChanged

    private fun queueReconciliationIfReady(): AutomationMutation =
        ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
            if (
                credentialStore.load() == null ||
                treeAccessStore.currentState(serverConnection) !is TreeAccessState.Connected
            ) {
                return@mutateIfCurrent AutomationMutation.SetupRequired
            }
            ReconciliationScheduler.runOnce(this)
            AutomationMutation.Changed
        } ?: AutomationMutation.ServerChanged

    private fun isCurrentServer(): Boolean =
        ServerConnectionStore(this).isCurrent(serverConnection)

    private fun requireCurrentServer(): Boolean {
        if (isCurrentServer()) return true
        finish()
        return false
    }

    private fun beginForgetAccount(allowUnrevoked: Boolean = false) {
        if (!requireCurrentServer()) return
        val credentialState = credentialStore.snapshot()
        val credential = credentialState.credential ?: return
        updateUi { copy(forgetActionEnabled = false, pairingStatus = getString(R.string.pairing_disconnecting)) }
        executor.execute {
            val disconnected = if (allowUnrevoked) {
                DeviceDisconnectApiResult.Unauthorized
            } else {
                credentialStore.useIfCurrent(credentialState) { current ->
                    if (!isCurrentServer()) return@useIfCurrent DeviceDisconnectApiResult.Retryable
                    RunwayApiClient(serverOrigin).disconnect(current)
                } ?: return@execute
            }
            if (disconnected == DeviceDisconnectApiResult.Retryable) {
                runOnUiThread {
                    if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                    updateUi {
                        copy(
                            forgetActionEnabled = true,
                            pairingStatus = getString(R.string.pairing_disconnect_unavailable),
                            dialog = NativeImportSetupDialog.ForgetUnrevoked,
                        )
                    }
                }
                return@execute
            }
            val cleared = ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
                if (!credentialStore.clearIfCurrent(credentialState)) {
                    false
                } else {
                    HandledImportStore(this).clearForDevice(credential.deviceId)
                    ReconciliationScheduler.cancelAll(this)
                    ReconciliationStatusStore(this).record(
                        ReconciliationWorker.STATE_PAIRING_REQUIRED,
                    )
                    true
                }
            } == true
            if (!cleared) return@execute
            runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                backgroundEnabled = false
                updateUi { copy(pairingStatus = getString(R.string.pairing_disconnected)) }
                refreshScreen(keepPairingStatus = true)
                refreshLastCheckStatus()
            }
        }
    }

    private fun restorePickerConnection(
        state: Bundle,
        originKey: String,
        generationKey: String,
    ): ServerConnection? {
        val origin = state.getString(originKey) ?: return null
        if (!state.containsKey(generationKey)) return null
        return ServerConnection(origin, state.getLong(generationKey))
    }

    private fun updateUi(transform: NativeImportSetupUiState.() -> NativeImportSetupUiState) {
        uiState = uiState.transform()
    }

    private companion object {
        const val DEVICE_LABEL_MAX_LENGTH = 60
        const val PERIODIC_WORK_NAME = "runway-folder-reconciliation"
        const val PICKER_ORIGIN_KEY = "picker_server_origin"
        const val PICKER_GENERATION_KEY = "picker_server_generation"
        const val ONE_OFF_PICKER_ORIGIN_KEY = "one_off_picker_server_origin"
        const val ONE_OFF_PICKER_GENERATION_KEY = "one_off_picker_server_generation"
        const val DEVICE_LABEL_KEY = "device_label"
    }
}
