package com.deftmartian.runway

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import java.util.concurrent.Executors

class ServerConnectionActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: ServerConnectionStore
    private lateinit var serverInput: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var cancelButton: Button
    private var initialConnection: ServerConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerConnectionStore(this)
        initialConnection = store.currentConnection()

        val editing = intent.action == ACTION_CHANGE_SERVER
        if (!editing && initialConnection != null) {
            openRunway(requireNotNull(initialConnection).origin)
            return
        }

        if (Build.VERSION.SDK_INT >= 25 && editing) {
            getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(
                RunwayLauncherActivity.SERVER_SHORTCUT_ID,
            )
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_server_connection)
        EdgeToEdgeLayout.applySystemBarPadding(findViewById(R.id.server_connection_scroll))
        bindViews()
    }

    override fun onDestroy() {
        // A confirmed switch may be finishing its server-side revocation while Android recreates
        // the Activity. Let it reach the generation-checked local transition.
        executor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        ViewCompat.setAccessibilityHeading(findViewById(R.id.server_heading), true)
        val savedOrigin = initialConnection?.origin
        serverInput = findViewById<EditText>(R.id.server_origin).apply {
            setText(savedOrigin.orEmpty())
            setSelection(text.length)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
                verifyServer()
                true
            }
        }
        status = findViewById(R.id.server_status)
        connectButton = findViewById<Button>(R.id.connect_server).apply {
            setOnClickListener { verifyServer() }
        }
        cancelButton = findViewById<Button>(R.id.cancel_server_change).apply {
            visibility = if (savedOrigin == null) View.GONE else View.VISIBLE
            setOnClickListener { finish() }
        }
        if (savedOrigin == null) {
            status.setText(R.string.server_intro)
        } else {
            status.text = getString(R.string.server_current, savedOrigin)
        }
    }

    private fun verifyServer() {
        if (!isInitialConnectionCurrent()) {
            showConnectionChanged()
            return
        }
        val origin = InstanceOriginPolicy.normalizeOrigin(serverInput.text.toString(), BuildConfig.DEBUG)
        if (origin == null) {
            status.setText(
                if (BuildConfig.DEBUG) R.string.server_invalid_debug else R.string.server_invalid,
            )
            return
        }
        setPending(true)
        status.setText(R.string.server_checking)
        executor.execute {
            val result = RunwayApiClient(origin).probe()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!isInitialConnectionCurrent()) {
                    showConnectionChanged()
                    return@runOnUiThread
                }
                setPending(false)
                when (result) {
                    InstanceProbeResult.Compatible -> acceptCompatibleOrigin(origin)
                    InstanceProbeResult.UpgradeRequired -> status.setText(R.string.server_upgrade_required)
                    InstanceProbeResult.NotRunway -> status.setText(R.string.server_not_runway)
                    InstanceProbeResult.Unreachable -> status.setText(R.string.server_unreachable)
                }
            }
        }
    }

    private fun acceptCompatibleOrigin(origin: String) {
        if (!isInitialConnectionCurrent()) {
            showConnectionChanged()
            return
        }
        val previous = initialConnection?.origin
        if (previous == origin) {
            openRunway(origin)
            return
        }
        if (previous != null && previous != origin) {
            AlertDialog.Builder(this)
                .setTitle(R.string.server_change_title)
                .setMessage(R.string.server_change_consequence)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.change_server) { _, _ -> beginSwitch(origin) }
                .show()
            return
        }
        beginSwitch(origin)
    }

    private fun beginSwitch(origin: String, allowUnrevoked: Boolean = false) {
        if (!isInitialConnectionCurrent()) {
            showConnectionChanged()
            return
        }
        setPending(true)
        status.setText(
            if (initialConnection != null && !allowUnrevoked) {
                R.string.server_disconnecting_old
            } else {
                R.string.server_switching
            },
        )
        executor.execute {
            if (!isInitialConnectionCurrent()) {
                deliverSwitchResult(origin, SwitchResult.Conflict)
                return@execute
            }
            val previous = initialConnection
            if (previous != null && !allowUnrevoked) {
                val credentialStore = AndroidCredentialStore(this, previous.origin)
                val credentialState = credentialStore.snapshot()
                val disconnected = if (credentialState.credential == null) {
                    DeviceDisconnectApiResult.Unauthorized
                } else {
                    credentialStore.useIfCurrent(credentialState) { current ->
                        if (!isInitialConnectionCurrent()) {
                            return@useIfCurrent DeviceDisconnectApiResult.Retryable
                        }
                        RunwayApiClient(previous.origin).disconnect(current)
                    } ?: DeviceDisconnectApiResult.Retryable
                }
                if (disconnected == DeviceDisconnectApiResult.Retryable) {
                    deliverSwitchResult(origin, SwitchResult.RevocationUnavailable)
                    return@execute
                }
            }
            val result = when (val transition = store.replace(initialConnection, origin)) {
                is ServerConnectionTransition.Changed -> SwitchResult.Changed(transition.connection)
                ServerConnectionTransition.Conflict -> SwitchResult.Conflict
                ServerConnectionTransition.Invalid -> SwitchResult.Invalid
            }
            deliverSwitchResult(origin, result)
        }
    }

    private fun deliverSwitchResult(origin: String, result: SwitchResult) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            when (result) {
                is SwitchResult.Changed -> openRunway(result.connection.origin)
                SwitchResult.Conflict -> showConnectionChanged()
                SwitchResult.Invalid -> {
                    setPending(false)
                    status.setText(R.string.server_invalid)
                }
                SwitchResult.RevocationUnavailable -> {
                    setPending(false)
                    status.setText(R.string.server_disconnect_unavailable)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.server_disconnect_unavailable_title)
                        .setMessage(R.string.server_disconnect_unavailable_consequence)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.switch_anyway) { _, _ ->
                            beginSwitch(origin, allowUnrevoked = true)
                        }
                        .show()
                }
            }
        }
    }

    private fun isInitialConnectionCurrent(): Boolean =
        store.currentConnection() == initialConnection

    private fun showConnectionChanged() {
        setPending(false)
        status.setText(R.string.server_connection_changed)
    }

    private fun openRunway(origin: String) {
        startActivity(Intent(this, RunwayLauncherActivity::class.java).apply {
            data = Uri.parse("$origin/app")
        })
        finish()
    }

    private fun setPending(pending: Boolean) {
        serverInput.isEnabled = !pending
        connectButton.isEnabled = !pending
        cancelButton.isEnabled = !pending
    }

    companion object {
        const val ACTION_CHANGE_SERVER = "com.deftmartian.runway.CHANGE_SERVER"
    }

    private sealed interface SwitchResult {
        data class Changed(val connection: ServerConnection) : SwitchResult
        data object Conflict : SwitchResult
        data object Invalid : SwitchResult
        data object RevocationUnavailable : SwitchResult
    }
}
