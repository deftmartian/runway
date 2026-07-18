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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.Executors

class NativeFolderSettingsActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var treeAccessStore: TreeAccessStore
    private lateinit var credentialStore: AndroidCredentialStore

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
    private lateinit var backgroundButton: Button

    private var backgroundEnabled = false

    private val chooseDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val connected = treeAccessStore.connect(uri)
        if (connected) {
            if (credentialStore.load() != null) {
                ReconciliationScheduler.runOnce(this)
                ReconciliationScheduler.enablePeriodic(this)
                backgroundEnabled = true
                folderStatus.setText(R.string.folder_connected_background)
            } else {
                folderStatus.setText(R.string.folder_connected_pairing_needed)
            }
        } else {
            folderStatus.setText(R.string.folder_connection_failed)
        }
        refreshScreen(keepFolderStatus = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= 25 &&
            intent.action == RunwayLauncherActivity.ACTION_OPEN_FOLDER_SETTINGS
        ) {
            getSystemService(ShortcutManager::class.java)
                ?.reportShortcutUsed(RunwayLauncherActivity.FOLDER_SHORTCUT_ID)
        }

        treeAccessStore = TreeAccessStore(this)
        credentialStore = AndroidCredentialStore(this)
        setContentView(R.layout.activity_native_folder_settings)
        bindViews()
        bindActions()
        refreshScreen()
        refreshBackgroundWorkState()
        verifyPairingStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
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
        backgroundButton = findViewById(R.id.background_action)
    }

    private fun bindActions() {
        primaryAction.setOnClickListener { performPrimaryAction() }
        deviceLabel.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            pairAccount()
            true
        }
        forgetButton.setOnClickListener {
            credentialStore.load()?.let { credential ->
                HandledImportStore(this).clearForDevice(credential.deviceId)
                ScanProgressStore(this).reset(credential.deviceId)
            }
            credentialStore.clear()
            ReconciliationScheduler.disablePeriodic(this)
            backgroundEnabled = false
            pairingStatus.setText(R.string.pairing_disconnected)
            refreshScreen(keepPairingStatus = true)
        }
        changeFolderButton.setOnClickListener { openDirectoryPicker() }
        disconnectButton.setOnClickListener {
            treeAccessStore.disconnect()
            ReconciliationScheduler.disablePeriodic(this)
            backgroundEnabled = false
            folderStatus.setText(R.string.folder_disconnected)
            refreshScreen(keepFolderStatus = true)
        }
        backgroundButton.setOnClickListener {
            if (!isReady()) {
                backgroundStatus.setText(R.string.background_setup_required)
                return@setOnClickListener
            }
            if (backgroundEnabled) {
                ReconciliationScheduler.disablePeriodic(this)
                backgroundEnabled = false
                backgroundStatus.setText(R.string.background_disabled)
            } else {
                ReconciliationScheduler.enablePeriodic(this)
                backgroundEnabled = true
                backgroundStatus.setText(R.string.background_enabled)
            }
            refreshAutomationState(keepStatus = true)
        }
        findViewById<Button>(R.id.return_to_runway).setOnClickListener {
            startActivity(Intent(this, RunwayLauncherActivity::class.java))
            finish()
        }
    }

    private fun performPrimaryAction() {
        when {
            credentialStore.load() == null -> pairAccount()
            treeAccessStore.currentState() !is TreeAccessState.Connected -> openDirectoryPicker()
            else -> {
                ReconciliationScheduler.runOnce(this)
                folderStatus.setText(R.string.check_queued)
            }
        }
    }

    private fun openDirectoryPicker() {
        val initialUri = when (val state = treeAccessStore.currentState()) {
            is TreeAccessState.Connected -> state.uri
            is TreeAccessState.PermissionRequired -> state.uri
            TreeAccessState.Missing -> null
        }
        chooseDirectory.launch(initialUri)
    }

    private fun pairAccount() {
        val code = pairingCode.text.toString().trim()
        val label = deviceLabel.text.toString().trim()
        if (code.isBlank() || label.isBlank()) {
            pairingStatus.setText(R.string.pairing_invalid)
            return
        }
        primaryAction.isEnabled = false
        pairingStatus.setText(R.string.pairing_in_progress)
        executor.execute {
            val result = RunwayApiClient().pair(code, label)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is PairingApiResult.Paired -> {
                        if (credentialStore.save(result.credential)) {
                            pairingCode.text.clear()
                            pairingStatus.setText(R.string.pairing_connected)
                            if (treeAccessStore.currentState() is TreeAccessState.Connected) {
                                ReconciliationScheduler.runOnce(this)
                                ReconciliationScheduler.enablePeriodic(this)
                                backgroundEnabled = true
                            }
                        } else {
                            pairingStatus.setText(R.string.pairing_store_failed)
                        }
                    }
                    PairingApiResult.Invalid -> pairingStatus.setText(R.string.pairing_invalid)
                    PairingApiResult.Retryable -> pairingStatus.setText(R.string.pairing_failed)
                }
                refreshScreen(keepPairingStatus = true)
            }
        }
    }

    private fun verifyPairingStatus() {
        val credential = credentialStore.load() ?: return
        executor.execute {
            val result = RunwayApiClient().status(credential)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    DeviceStatusApiResult.Connected -> pairingStatus.setText(R.string.pairing_connected)
                    DeviceStatusApiResult.Unauthorized -> {
                        HandledImportStore(this).clearForDevice(credential.deviceId)
                        ScanProgressStore(this).reset(credential.deviceId)
                        credentialStore.clear()
                        ReconciliationScheduler.disablePeriodic(this)
                        backgroundEnabled = false
                        pairingStatus.setText(R.string.pairing_expired_or_revoked)
                        refreshScreen(keepPairingStatus = true)
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
                if (isDestroyed) return@runOnUiThread
                backgroundEnabled = enabled
                refreshAutomationState()
            }
        }
    }

    private fun refreshScreen(
        keepPairingStatus: Boolean = false,
        keepFolderStatus: Boolean = false,
    ) {
        val accountConnected = credentialStore.load() != null
        val folderState = treeAccessStore.currentState()

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
            treeAccessStore.currentState() is TreeAccessState.Connected

    private companion object {
        const val PAIRING_CODE_MAX_LENGTH = 19
        const val DEVICE_LABEL_MAX_LENGTH = 60
        const val PERIODIC_WORK_NAME = "runway-folder-reconciliation"
    }
}
