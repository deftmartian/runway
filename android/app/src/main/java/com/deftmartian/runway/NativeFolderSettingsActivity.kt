package com.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

class NativeFolderSettingsActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var treeAccessStore: TreeAccessStore
    private lateinit var credentialStore: AndroidCredentialStore
    private lateinit var folderStatus: TextView
    private lateinit var pairingStatus: TextView
    private lateinit var pairingCode: EditText
    private lateinit var deviceLabel: EditText
    private lateinit var pairButton: Button
    private lateinit var forgetButton: Button
    private lateinit var disconnectButton: Button

    private val chooseDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val connected = treeAccessStore.connect(uri)
        refreshFolderState()
        if (connected) {
            if (credentialStore.load() != null) {
                ReconciliationScheduler.runOnce(this)
                ReconciliationScheduler.enablePeriodic(this)
                folderStatus.setText(R.string.folder_connected_background)
            } else {
                folderStatus.setText(R.string.folder_connected_pairing_needed)
            }
        } else {
            folderStatus.setText(R.string.folder_connection_failed)
        }
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
        setContentView(buildContent())
        refreshPairingState()
        refreshFolderState()
        verifyPairingStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }

        content.addView(textView(R.string.folder_screen_title, 26f, Typeface.BOLD))
        content.addView(textView(R.string.folder_screen_intro, 16f).withTopMargin(8))

        content.addView(textView(R.string.pairing_title, 19f, Typeface.BOLD).withTopMargin(28))
        content.addView(textView(R.string.pairing_intro, 15f).withTopMargin(6))
        pairingStatus = textView(R.string.pairing_intro, 15f)
        content.addView(pairingStatus.withTopMargin(14))
        pairingCode = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.pairing_code)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(19))
            if (Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        content.addView(labeledInput(R.string.pairing_code, pairingCode).withTopMargin(10))
        deviceLabel = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.device_label)
            setText(R.string.device_label_default)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(60))
        }
        content.addView(labeledInput(R.string.device_label, deviceLabel).withTopMargin(10))
        pairButton = button(R.string.pair_device, ::pairAccount)
        content.addView(pairButton.withTopMargin(10))
        forgetButton = button(R.string.forget_account) {
            credentialStore.load()?.let { HandledImportStore(this).clearForDevice(it.deviceId) }
            credentialStore.clear()
            ReconciliationScheduler.disablePeriodic(this)
            pairingStatus.setText(R.string.pairing_disconnected)
            refreshPairingState(keepStatus = true)
        }
        content.addView(forgetButton.withTopMargin(8))

        content.addView(textView(R.string.folder_section_title, 19f, Typeface.BOLD).withTopMargin(30))
        folderStatus = textView(R.string.folder_not_connected, 16f)
        content.addView(folderStatus.withTopMargin(10))
        content.addView(button(R.string.choose_folder) {
            val initialUri = when (val state = treeAccessStore.currentState()) {
                is TreeAccessState.Connected -> state.uri
                is TreeAccessState.PermissionRequired -> state.uri
                TreeAccessState.Missing -> null
            }
            chooseDirectory.launch(initialUri)
        }.withTopMargin(8))

        disconnectButton = button(R.string.disconnect_folder) {
            treeAccessStore.disconnect()
            ReconciliationScheduler.disablePeriodic(this)
            refreshFolderState()
            folderStatus.setText(R.string.folder_disconnected)
        }
        content.addView(disconnectButton.withTopMargin(8))

        content.addView(button(R.string.check_now) {
            when {
                credentialStore.load() == null -> pairingStatus.setText(R.string.share_pairing_required)
                treeAccessStore.currentState() is TreeAccessState.Connected -> {
                    ReconciliationScheduler.runOnce(this)
                    folderStatus.setText(R.string.check_queued)
                }
                else -> refreshFolderState()
            }
        }.withTopMargin(20))
        content.addView(button(R.string.enable_background) {
            when {
                credentialStore.load() == null -> pairingStatus.setText(R.string.share_pairing_required)
                treeAccessStore.currentState() is TreeAccessState.Connected -> {
                    ReconciliationScheduler.enablePeriodic(this)
                    folderStatus.setText(R.string.background_enabled)
                }
                else -> refreshFolderState()
            }
        }.withTopMargin(8))
        content.addView(button(R.string.disable_background) {
            ReconciliationScheduler.disablePeriodic(this)
            folderStatus.setText(R.string.background_disabled)
        }.withTopMargin(8))

        content.addView(textView(R.string.folder_upload_ready, 14f).withTopMargin(28))
        content.addView(button(R.string.return_to_runway) {
            startActivity(Intent(this, RunwayLauncherActivity::class.java))
            finish()
        }.withTopMargin(24))

        return ScrollView(this).apply { addView(content) }
    }

    private fun pairAccount() {
        val code = pairingCode.text.toString().trim()
        val label = deviceLabel.text.toString().trim()
        if (code.isBlank() || label.isBlank()) {
            pairingStatus.setText(R.string.pairing_invalid)
            return
        }
        pairButton.isEnabled = false
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
                            }
                        } else {
                            pairingStatus.setText(R.string.pairing_store_failed)
                        }
                    }
                    PairingApiResult.Invalid -> pairingStatus.setText(R.string.pairing_invalid)
                    PairingApiResult.Retryable -> pairingStatus.setText(R.string.pairing_failed)
                }
                refreshPairingState(keepStatus = true)
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
                        credentialStore.clear()
                        ReconciliationScheduler.disablePeriodic(this)
                        pairingStatus.setText(R.string.pairing_expired_or_revoked)
                        refreshPairingState(keepStatus = true)
                    }
                    DeviceStatusApiResult.Retryable -> {
                        pairingStatus.setText(R.string.pairing_status_unavailable)
                    }
                }
            }
        }
    }

    private fun refreshPairingState(keepStatus: Boolean = false) {
        val connected = credentialStore.load() != null
        pairButton.isEnabled = !connected
        pairingCode.isEnabled = !connected
        deviceLabel.isEnabled = !connected
        forgetButton.isEnabled = connected
        if (!keepStatus) {
            pairingStatus.setText(if (connected) R.string.pairing_connected else R.string.pairing_intro)
        }
    }

    private fun refreshFolderState() {
        when (treeAccessStore.currentState()) {
            is TreeAccessState.Connected -> {
                folderStatus.setText(R.string.folder_connected)
                disconnectButton.isEnabled = true
            }
            is TreeAccessState.PermissionRequired -> {
                folderStatus.setText(R.string.folder_permission_needed)
                disconnectButton.isEnabled = true
            }
            TreeAccessState.Missing -> {
                folderStatus.setText(R.string.folder_not_connected)
                disconnectButton.isEnabled = false
            }
        }
    }

    private fun labeledInput(labelResource: Int, input: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(textView(labelResource, 14f, Typeface.BOLD).apply { labelFor = input.id })
        addView(input)
    }

    private fun textView(stringResource: Int, sizeSp: Float, style: Int = Typeface.NORMAL) =
        TextView(this).apply {
            setText(stringResource)
            textSize = sizeSp
            setTypeface(typeface, style)
        }

    private fun button(stringResource: Int, onClick: () -> Unit) = Button(this).apply {
        setText(stringResource)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun View.withTopMargin(marginDp: Int): View = apply {
        val existing = layoutParams as? LinearLayout.LayoutParams
        layoutParams = (existing ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )).also { it.topMargin = dp(marginDp) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
