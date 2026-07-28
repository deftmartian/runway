package dev.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal class OneShotBackgroundPreservation {
    private var armed = false

    fun arm() {
        armed = true
    }

    fun cancel() {
        armed = false
    }

    fun consume(): Boolean = armed.also { armed = false }
}

class MainActivity : ComponentActivity() {
    private val runwayViewModel: RunwayViewModel by viewModels()
    private val recoveryCodeHandoff = OneShotBackgroundPreservation()
    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) runwayViewModel.exportTrainingData(contentResolver, uri)
    }
    private val recoveryCodesDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        recoveryCodeHandoff.cancel()
        if (uri != null) runwayViewModel.saveRecoveryCodes(contentResolver, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ServerConnectionStore(this).currentOrigin() == null) {
            startActivity(Intent(this, ServerConnectionActivity::class.java))
            finish()
            return
        }

        publishShortcuts()
        ReconciliationScheduler.runOnce(this)
        enableEdgeToEdge()
        setContent {
            RunwayTheme {
                val state = runwayViewModel.state.collectAsStateWithLifecycle().value
                RunwayNativeApp(
                    state = state,
                    onStartAuthorization = runwayViewModel::startAuthorization,
                    onCancelAuthorization = runwayViewModel::cancelAuthorization,
                    onSignInLocal = runwayViewModel::signInLocal,
                    onSignUpLocal = runwayViewModel::signUpLocal,
                    onVerifyTwoFactor = runwayViewModel::verifyTwoFactor,
                    onSelectSecondFactor = runwayViewModel::selectSecondFactor,
                    onCancelTwoFactor = runwayViewModel::cancelTwoFactor,
                    onOpenExternalAuthorization = ::openAuthorization,
                    onOpenPasswordReset = {
                        ServerConnectionStore(this).currentOrigin()?.let { origin ->
                            openCustomTab(Uri.parse("$origin/login/forgot-password"))
                        }
                    },
                    onRetry = runwayViewModel::retry,
                    onDestinationSelected = runwayViewModel::selectDestination,
                    onCalendarMonthSelected = runwayViewModel::loadCalendarMonth,
                    onLoadMoreHistory = runwayViewModel::loadMoreHistory,
                    onLoadMoreImports = runwayViewModel::loadMoreImports,
                    onLoadActivityTrace = runwayViewModel::loadActivityTrace,
                    onOpenHistoryDetail = runwayViewModel::openHistoryDetail,
                    onRefresh = runwayViewModel::refresh,
                    onAction = runwayViewModel::submitAction,
                    onRequestPasswordReset = runwayViewModel::requestPasswordReset,
                    onChangePassword = runwayViewModel::changePassword,
                    onEnableTwoFactor = runwayViewModel::enableTwoFactor,
                    onOpenAuthenticator = ::openAuthenticator,
                    onVerifyTwoFactorSetup = runwayViewModel::verifyTwoFactorSetup,
                    onCancelTotpSetup = runwayViewModel::cancelTotpSetup,
                    onDisableTwoFactor = runwayViewModel::disableTwoFactor,
                    onRegenerateRecoveryCodes = runwayViewModel::regenerateRecoveryCodes,
                    onSaveRecoveryCodes = ::chooseRecoveryCodesDocument,
                    onClearRecoveryCodes = runwayViewModel::clearRecoveryCodes,
                    onRevokeAccountSession = runwayViewModel::revokeAccountSession,
                    onRenamePasskey = runwayViewModel::renamePasskey,
                    onDeletePasskey = runwayViewModel::deletePasskey,
                    onExportTrainingData = {
                        exportDocumentLauncher.launch("runway-training-data.json")
                    },
                    onDeleteAccount = runwayViewModel::deleteAccount,
                    onConfirmActionPreview = runwayViewModel::confirmActionPreview,
                    onDismissActionPreview = runwayViewModel::dismissActionPreview,
                    onSignOut = runwayViewModel::signOut,
                    onOpenServer = {
                        runwayViewModel.clearAccountSecuritySecrets()
                        startActivity(
                            Intent(this, ServerConnectionActivity::class.java).apply {
                                action = ServerConnectionActivity.ACTION_CHANGE_SERVER
                            },
                        )
                    },
                    onOpenFolder = {
                        startActivity(
                            Intent(this, NativeFolderSettingsActivity::class.java).apply {
                                action = ACTION_OPEN_FOLDER_SETTINGS
                            },
                        )
                    },
                )
            }
        }
        handleAuthorizationCallback(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!recoveryCodeHandoff.consume()) {
            runwayViewModel.clearAccountSecuritySecrets()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthorizationCallback(intent)
    }

    private fun publishShortcuts() {
        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
        val folderShortcut = ShortcutInfo.Builder(this, FOLDER_SHORTCUT_ID)
            .setShortLabel(getString(R.string.folder_shortcut_short))
            .setLongLabel(getString(R.string.folder_shortcut_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(Intent(this, NativeFolderSettingsActivity::class.java).apply {
                action = ACTION_OPEN_FOLDER_SETTINGS
            })
            .build()
        val serverShortcut = ShortcutInfo.Builder(this, SERVER_SHORTCUT_ID)
            .setShortLabel(getString(R.string.server_shortcut_short))
            .setLongLabel(getString(R.string.server_shortcut_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(Intent(this, ServerConnectionActivity::class.java).apply {
                action = ServerConnectionActivity.ACTION_CHANGE_SERVER
            })
            .build()
        runCatching { shortcutManager.setDynamicShortcuts(listOf(folderShortcut, serverShortcut)) }
    }

    private fun openAuthorization(verificationUri: String) {
        val serverOrigin = ServerConnectionStore(this).currentOrigin() ?: return
        if (!InstanceOriginPolicy.belongsTo(verificationUri, serverOrigin)) return
        val uri = Uri.parse(verificationUri)
            .buildUpon()
            .appendQueryParameter(AUTH_RETURN_PARAMETER, AUTH_RETURN_VALUE)
            .build()
        openCustomTab(uri)
    }

    private fun openAuthenticator(setupUri: String) {
        val uri = runCatching { Uri.parse(setupUri) }.getOrNull()
        if (
            uri == null ||
            setupUri.length !in 1..2_048 ||
            setupUri.any(Char::isISOControl) ||
            !uri.scheme.equals("otpauth", ignoreCase = true) ||
            !uri.host.equals("totp", ignoreCase = true) ||
            uri.userInfo != null ||
            uri.fragment != null
        ) {
            runwayViewModel.reportAuthenticatorAppUnavailable()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (intent.resolveActivity(packageManager) == null) {
            runwayViewModel.reportAuthenticatorAppUnavailable()
            return
        }
        runCatching { startActivity(intent) }
            .onSuccess { runwayViewModel.clearTotpEnrollmentSecret() }
            .onFailure { runwayViewModel.reportAuthenticatorAppUnavailable() }
    }

    private fun chooseRecoveryCodesDocument() {
        recoveryCodeHandoff.arm()
        runCatching {
            recoveryCodesDocumentLauncher.launch("runway-recovery-codes.txt")
        }.onFailure {
            recoveryCodeHandoff.cancel()
        }
    }

    private fun openCustomTab(uri: Uri) {
        runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(this, uri)
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }

    private fun handleAuthorizationCallback(callbackIntent: Intent) {
        when (parseNativeAuthReturn(callbackIntent.dataString)) {
            NativeAuthReturnResult.Approved ->
                runwayViewModel.authorizationReturned(approved = true)
            NativeAuthReturnResult.Denied ->
                runwayViewModel.authorizationReturned(approved = false)
            null -> return
        }
        callbackIntent.data = null
    }

    companion object {
        internal const val ACTION_OPEN_FOLDER_SETTINGS =
            "dev.deftmartian.runway.OPEN_FOLDER_SETTINGS"
        internal const val FOLDER_SHORTCUT_ID = "device-folder"
        internal const val SERVER_SHORTCUT_ID = "server-connection"
        private const val AUTH_RETURN_PARAMETER = "return_to_app"
        private const val AUTH_RETURN_VALUE = "runway-native"
    }
}
