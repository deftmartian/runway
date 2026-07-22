package com.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
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
    private lateinit var serverConnection: ServerConnection
    private lateinit var serverOrigin: String

    private lateinit var setupStatus: TextView
    private lateinit var setupIndicator: View
    private lateinit var pairingStatus: TextView
    private lateinit var pairingForm: View
    private lateinit var pairingCode: EditText
    private lateinit var deviceLabel: EditText
    private lateinit var primaryAction: Button
    private lateinit var forgetButton: Button
    private lateinit var folderStatus: TextView
    private lateinit var changeFolderButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var backgroundStatus: TextView
    private lateinit var lastCheckStatus: TextView
    private lateinit var backgroundButton: Button

    private var backgroundEnabled = false
    private val workRunningByName = mutableMapOf<String, Boolean>()
    private var pickerConnection: ServerConnection? = null

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
                            folderStatus.setText(R.string.folder_connected_background)
                            lastCheckStatus.setText(R.string.last_check_queued)
                        } else {
                            folderStatus.setText(R.string.folder_connected_pairing_needed)
                        }
                    }
                    TreeAccessMutation.Conflict -> {
                        finish()
                        return@runOnUiThread
                    }
                    TreeAccessMutation.Failed -> folderStatus.setText(R.string.folder_connection_failed)
                }
                refreshScreen(keepFolderStatus = true)
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
        pickerConnection = savedInstanceState?.let(::restorePickerConnection)
        enableEdgeToEdge()
        if (
            Build.VERSION.SDK_INT >= 25 &&
            intent.action == RunwayLauncherActivity.ACTION_OPEN_FOLDER_SETTINGS
        ) {
            getSystemService(ShortcutManager::class.java)
                ?.reportShortcutUsed(RunwayLauncherActivity.FOLDER_SHORTCUT_ID)
        }

        treeAccessStore = TreeAccessStore(this)
        credentialStore = AndroidCredentialStore(this, serverOrigin)
        val setupStillCurrent = ServerConnectionStore(this).mutateIfCurrent(serverConnection) {
            if (credentialStore.load() == null) ReconciliationScheduler.cancelAll(this)
            true
        } == true
        if (!setupStillCurrent) {
            finish()
            return
        }
        setContentView(R.layout.activity_native_folder_settings)
        EdgeToEdgeLayout.applySystemBarPadding(findViewById(R.id.folder_settings_scroll))
        bindViews()
        bindActions()
        refreshScreen()
        refreshBackgroundWorkState()
        observeReconciliationWork()
        refreshLastCheckStatus()
        verifyPairingStatus()
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
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::serverConnection.isInitialized && !isCurrentServer()) finish()
    }

    private fun bindViews() {
        listOf(
            R.id.screen_heading,
            R.id.server_section_heading,
            R.id.pairing_heading,
            R.id.folder_heading,
            R.id.background_heading,
        ).forEach { headingId ->
            ViewCompat.setAccessibilityHeading(findViewById<View>(headingId), true)
        }
        setupStatus = findViewById(R.id.setup_status)
        setupIndicator = findViewById(R.id.setup_indicator)
        pairingStatus = findViewById(R.id.pairing_status)
        pairingForm = findViewById(R.id.pairing_form)
        pairingCode = findViewById<EditText>(R.id.pairing_code).apply {
            filters = arrayOf(InputFilter.LengthFilter(PAIRING_CODE_MAX_LENGTH))
        }
        deviceLabel = findViewById<EditText>(R.id.device_label).apply {
            filters = arrayOf(InputFilter.LengthFilter(DEVICE_LABEL_MAX_LENGTH))
        }
        primaryAction = findViewById(R.id.primary_action)
        forgetButton = findViewById(R.id.forget_account)
        folderStatus = findViewById(R.id.folder_status)
        changeFolderButton = findViewById(R.id.change_folder)
        disconnectButton = findViewById(R.id.disconnect_folder)
        backgroundStatus = findViewById(R.id.background_status)
        lastCheckStatus = findViewById(R.id.last_check_status)
        backgroundButton = findViewById(R.id.background_action)
        findViewById<TextView>(R.id.server_origin_status).text = serverOrigin
        findViewById<Button>(R.id.change_server).apply {
            setOnClickListener {
                if (!requireCurrentServer()) return@setOnClickListener
                startActivity(Intent(this@NativeFolderSettingsActivity, ServerConnectionActivity::class.java).apply {
                    action = ServerConnectionActivity.ACTION_CHANGE_SERVER
                })
                finish()
            }
        }
    }

    private fun bindActions() {
        primaryAction.setOnClickListener { performPrimaryAction() }
        deviceLabel.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            pairAccount()
            true
        }
        forgetButton.setOnClickListener {
            beginForgetAccount()
        }
        changeFolderButton.setOnClickListener {
            if (requireCurrentServer()) openDirectoryPicker()
        }
        disconnectButton.setOnClickListener {
            if (!requireCurrentServer()) return@setOnClickListener
            executor.execute {
                val result = treeAccessStore.disconnect(serverConnection)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (result == TreeAccessMutation.Conflict) {
                        finish()
                        return@runOnUiThread
                    }
                    backgroundEnabled = false
                    folderStatus.setText(
                        if (result == TreeAccessMutation.Changed) {
                            R.string.folder_disconnected
                        } else {
                            R.string.folder_connection_failed
                        },
                    )
                    refreshScreen(keepFolderStatus = true)
                    refreshLastCheckStatus()
                }
            }
        }
        backgroundButton.setOnClickListener {
            if (!requireCurrentServer()) return@setOnClickListener
            val enable = !backgroundEnabled
            when (setPeriodicAutomationEnabled(enable)) {
                AutomationMutation.Changed -> {
                    backgroundEnabled = enable
                    backgroundStatus.setText(
                        if (enable) R.string.background_enabled else R.string.background_disabled,
                    )
                    refreshAutomationState(keepStatus = true)
                }
                AutomationMutation.SetupRequired -> {
                    refreshScreen()
                }
                AutomationMutation.ServerChanged -> finish()
            }
        }
        findViewById<Button>(R.id.return_to_runway).setOnClickListener {
            if (!requireCurrentServer()) return@setOnClickListener
            startActivity(Intent(this, ServerConnectionActivity::class.java))
            finish()
        }
    }

    private fun performPrimaryAction() {
        if (!requireCurrentServer()) return
        when {
            credentialStore.load() == null -> pairAccount()
            treeAccessStore.currentState(serverConnection) !is TreeAccessState.Connected -> openDirectoryPicker()
            else -> {
                when (queueReconciliationIfReady()) {
                    AutomationMutation.Changed -> {
                        folderStatus.setText(R.string.check_queued)
                        lastCheckStatus.setText(R.string.last_check_queued)
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

    private fun pairAccount() {
        if (!requireCurrentServer()) return
        val code = pairingCode.text.toString().trim()
        val label = deviceLabel.text.toString().trim()
        if (code.isBlank() || label.isBlank()) {
            pairingStatus.setText(R.string.pairing_invalid)
            return
        }
        primaryAction.isEnabled = false
        pairingStatus.setText(R.string.pairing_in_progress)
        val expectedCredentialState = credentialStore.snapshot()
        executor.execute {
            if (!isCurrentServer()) return@execute
            val result = RunwayApiClient(serverOrigin).pair(code, label)
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
                        pairingCode.text.clear()
                        pairingStatus.setText(R.string.pairing_connected)
                        backgroundEnabled = automationStarted
                        if (automationStarted) lastCheckStatus.setText(R.string.last_check_queued)
                    }
                    PairingCompletion.Invalid -> pairingStatus.setText(R.string.pairing_invalid)
                    PairingCompletion.Retryable -> pairingStatus.setText(R.string.pairing_failed)
                    PairingCompletion.StorageFailed -> pairingStatus.setText(R.string.pairing_store_failed)
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
            runOnUiThread {
                if (isDestroyed || !isCurrentServer()) return@runOnUiThread
                when (result) {
                    DeviceStatusApiResult.Connected -> pairingStatus.setText(R.string.pairing_connected)
                    DeviceStatusApiResult.Unauthorized -> {
                        backgroundEnabled = false
                        pairingStatus.setText(R.string.pairing_expired_or_revoked)
                        refreshScreen(keepPairingStatus = true)
                        refreshLastCheckStatus()
                    }
                    DeviceStatusApiResult.Retryable -> {
                        pairingStatus.setText(R.string.pairing_status_unavailable)
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
    }

    private fun refreshLastCheckStatus() {
        if (workRunningByName.values.any { it }) {
            lastCheckStatus.setText(R.string.last_check_running)
            return
        }

        val record = ReconciliationStatusStore(this).load()
        if (record == null) {
            lastCheckStatus.setText(R.string.last_check_never)
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
        lastCheckStatus.text = text
    }

    private fun refreshScreen(
        keepPairingStatus: Boolean = false,
        keepFolderStatus: Boolean = false,
    ) {
        val accountConnected = credentialStore.load() != null
        val folderState = treeAccessStore.currentState(serverConnection)

        pairingForm.visibility = if (accountConnected) View.GONE else View.VISIBLE
        forgetButton.visibility = if (accountConnected) View.VISIBLE else View.GONE
        if (!keepPairingStatus) {
            pairingStatus.setText(
                if (accountConnected) R.string.pairing_connected else R.string.pairing_intro,
            )
        }

        changeFolderButton.visibility =
            if (folderState is TreeAccessState.Connected) View.VISIBLE else View.GONE
        disconnectButton.visibility =
            if (folderState is TreeAccessState.Missing) View.GONE else View.VISIBLE
        if (!keepFolderStatus) {
            folderStatus.setText(
                when (folderState) {
                    is TreeAccessState.Connected -> R.string.folder_connected
                    is TreeAccessState.PermissionRequired -> R.string.folder_permission_needed
                    TreeAccessState.Missing -> R.string.folder_not_connected
                },
            )
        }

        val primaryLabel = when {
            !accountConnected -> R.string.pair_device
            folderState is TreeAccessState.PermissionRequired -> R.string.restore_folder_access
            folderState is TreeAccessState.Missing -> R.string.choose_folder
            else -> R.string.check_now
        }
        primaryAction.setText(primaryLabel)
        primaryAction.isEnabled = true
        setupStatus.setText(
            when {
                !accountConnected -> R.string.setup_connect_account
                folderState is TreeAccessState.PermissionRequired -> R.string.setup_restore_folder
                folderState is TreeAccessState.Missing -> R.string.setup_choose_folder
                else -> R.string.setup_ready
            },
        )
        setupIndicator.backgroundTintList = ColorStateList.valueOf(
            getColor(if (isReady()) R.color.runway_status else R.color.runway_attention),
        )
        refreshAutomationState()
    }

    private fun refreshAutomationState(keepStatus: Boolean = false) {
        val ready = isReady()
        backgroundButton.isEnabled = ready
        backgroundButton.setText(
            if (backgroundEnabled) R.string.disable_background else R.string.enable_background,
        )
        if (!keepStatus) {
            backgroundStatus.setText(
                when {
                    !ready -> R.string.background_setup_required
                    backgroundEnabled -> R.string.background_enabled
                    else -> R.string.background_disabled
                },
            )
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
        forgetButton.isEnabled = false
        pairingStatus.setText(R.string.pairing_disconnecting)
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
                    forgetButton.isEnabled = true
                    pairingStatus.setText(R.string.pairing_disconnect_unavailable)
                    android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.pairing_disconnect_unavailable_title)
                        .setMessage(R.string.pairing_disconnect_unavailable_consequence)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.forget_anyway) { _, _ ->
                            beginForgetAccount(allowUnrevoked = true)
                        }
                        .show()
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
                pairingStatus.setText(R.string.pairing_disconnected)
                refreshScreen(keepPairingStatus = true)
                refreshLastCheckStatus()
            }
        }
    }

    private fun restorePickerConnection(state: Bundle): ServerConnection? {
        val origin = state.getString(PICKER_ORIGIN_KEY) ?: return null
        if (!state.containsKey(PICKER_GENERATION_KEY)) return null
        return ServerConnection(origin, state.getLong(PICKER_GENERATION_KEY))
    }

    private companion object {
        const val PAIRING_CODE_MAX_LENGTH = 19
        const val DEVICE_LABEL_MAX_LENGTH = 60
        const val PERIODIC_WORK_NAME = "runway-folder-reconciliation"
        const val PICKER_ORIGIN_KEY = "picker_server_origin"
        const val PICKER_GENERATION_KEY = "picker_server_generation"
    }
}
