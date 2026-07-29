package dev.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import java.util.concurrent.Executors

class ServerConnectionActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: ServerConnectionStore
    private var initialConnection: ServerConnection? = null
    private var originInput by mutableStateOf("")
    private var statusMessage by mutableStateOf("")
    private var pending by mutableStateOf(false)
    private var dialog by mutableStateOf<ServerConnectionDialog?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerConnectionStore(this)
        initialConnection = store.currentConnection()

        val editing = intent.action == ACTION_CHANGE_SERVER
        if (!editing && initialConnection != null) {
            openRunway()
            return
        }

        if (editing) {
            getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(
                MainActivity.SERVER_SHORTCUT_ID,
            )
        }
        enableEdgeToEdge()
        originInput = initialConnection?.origin.orEmpty()
        statusMessage = if (initialConnection == null) {
            getString(R.string.server_intro)
        } else {
            getString(R.string.server_current, initialConnection?.origin)
        }
        setContent {
            RunwayTheme {
                ServerConnectionScreen(
                    origin = originInput,
                    status = statusMessage,
                    pending = pending,
                    canCancel = initialConnection != null,
                    onOriginChange = { originInput = it },
                    onConnect = ::verifyServer,
                    onCancel = ::finish,
                )
                when (val activeDialog = dialog) {
                    is ServerConnectionDialog.ConfirmChange -> ConfirmServerChangeDialog(
                        onDismiss = { dialog = null },
                        onConfirm = {
                            dialog = null
                            beginSwitch(activeDialog.origin)
                        },
                    )
                    is ServerConnectionDialog.ConfirmOfflineSwitch -> OfflineSwitchDialog(
                        onDismiss = { dialog = null },
                        onConfirm = {
                            dialog = null
                            beginSwitch(activeDialog.origin, allowUnrevoked = true)
                        },
                    )
                    null -> Unit
                }
            }
        }
    }

    override fun onDestroy() {
        // A confirmed switch may be finishing its server-side revocation while Android recreates
        // the Activity. Let it reach the generation-checked local transition.
        executor.shutdown()
        super.onDestroy()
    }

    private fun verifyServer() {
        if (!isInitialConnectionCurrent()) {
            showConnectionChanged()
            return
        }
        val origin = InstanceOriginPolicy.normalizeOrigin(originInput, BuildConfig.DEBUG)
        if (origin == null) {
            statusMessage = getString(
                if (BuildConfig.DEBUG) R.string.server_invalid_debug else R.string.server_invalid,
            )
            return
        }
        updatePending(true)
        statusMessage = getString(R.string.server_checking)
        executor.execute {
            val result = RunwayApiClient(origin).probe()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!isInitialConnectionCurrent()) {
                    showConnectionChanged()
                    return@runOnUiThread
                }
                updatePending(false)
                when (result) {
                    InstanceProbeResult.Compatible -> acceptCompatibleOrigin(origin)
                    is InstanceProbeResult.OriginMismatch -> {
                        statusMessage = getString(
                            R.string.server_origin_mismatch,
                            result.canonicalOrigin,
                        )
                    }
                    InstanceProbeResult.UpgradeRequired -> statusMessage = getString(R.string.server_upgrade_required)
                    InstanceProbeResult.NotRunway -> statusMessage = getString(R.string.server_not_runway)
                    InstanceProbeResult.Unreachable -> statusMessage = getString(R.string.server_unreachable)
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
            openRunway()
            return
        }
        if (previous != null && previous != origin) {
            dialog = ServerConnectionDialog.ConfirmChange(origin)
            return
        }
        beginSwitch(origin)
    }

    private fun beginSwitch(origin: String, allowUnrevoked: Boolean = false) {
        if (!isInitialConnectionCurrent()) {
            showConnectionChanged()
            return
        }
        updatePending(true)
        statusMessage = getString(
            if (initialConnection != null && !allowUnrevoked) R.string.server_disconnecting_old
            else R.string.server_switching,
        )
        executor.execute {
            if (!isInitialConnectionCurrent()) {
                deliverSwitchResult(origin, SwitchResult.Conflict)
                return@execute
            }
            val previous = initialConnection
            if (previous != null && !allowUnrevoked) {
                val mobileSession = MobileSessionStore(this, previous.origin).loadSession()
                if (
                    mobileSession != null &&
                    !MobileApiClient(previous.origin).signOut(mobileSession)
                ) {
                    deliverSwitchResult(origin, SwitchResult.RevocationUnavailable)
                    return@execute
                }
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
                is SwitchResult.Changed -> openRunway()
                SwitchResult.Conflict -> showConnectionChanged()
                SwitchResult.Invalid -> {
                    updatePending(false)
                    statusMessage = getString(R.string.server_invalid)
                }
                SwitchResult.RevocationUnavailable -> {
                    updatePending(false)
                    statusMessage = getString(R.string.server_disconnect_unavailable)
                    dialog = ServerConnectionDialog.ConfirmOfflineSwitch(origin)
                }
            }
        }
    }

    private fun isInitialConnectionCurrent(): Boolean =
        store.currentConnection() == initialConnection

    private fun showConnectionChanged() {
        updatePending(false)
        statusMessage = getString(R.string.server_connection_changed)
    }

    private fun openRunway() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        finish()
    }

    private fun updatePending(pending: Boolean) {
        this.pending = pending
    }

    companion object {
        const val ACTION_CHANGE_SERVER = "dev.deftmartian.runway.CHANGE_SERVER"
    }

    private sealed interface SwitchResult {
        data class Changed(val connection: ServerConnection) : SwitchResult
        data object Conflict : SwitchResult
        data object Invalid : SwitchResult
        data object RevocationUnavailable : SwitchResult
    }

    private sealed interface ServerConnectionDialog {
        data class ConfirmChange(val origin: String) : ServerConnectionDialog
        data class ConfirmOfflineSwitch(val origin: String) : ServerConnectionDialog
    }
}

@Composable
private fun ServerConnectionScreen(
    origin: String,
    status: String,
    pending: Boolean,
    canCancel: Boolean,
    onOriginChange: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ScreenIntro(
                    title = "Connect to runway",
                    body = "Choose the runway server you use. The app checks it before sign-in.",
                )
                SettingCard("Server address") {
                    OutlinedTextField(
                        value = origin,
                        onValueChange = onOriginChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Server address") },
                        placeholder = { Text("https://runway.example.com") },
                        enabled = !pending,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { onConnect() }),
                    )
                    Text(
                        text = status,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), enabled = !pending) {
                    Text(if (pending) "Checking server…" else "Connect")
                }
                if (canCancel) {
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally), enabled = !pending) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmServerChangeDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change runway server?") },
        text = { Text(stringResource(R.string.server_change_consequence)) },
        confirmButton = { Button(onClick = onConfirm) { Text("Change server") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OfflineSwitchDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Current server unavailable") },
        text = {
            Text(
                "Switching anyway removes local access, but the old server may keep this phone’s account session " +
                    "or import device active until it expires or you disconnect it there. An upload already in progress " +
                    "may finish. Cancel if you can bring the old server back online first.",
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Switch anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
