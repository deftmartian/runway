package dev.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
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
                val runwayViewModel: RunwayViewModel = viewModel()
                val state = runwayViewModel.state.collectAsStateWithLifecycle().value
                RunwayNativeApp(
                    state = state,
                    onStartAuthorization = runwayViewModel::startAuthorization,
                    onCancelAuthorization = runwayViewModel::cancelAuthorization,
                    onRetry = runwayViewModel::retry,
                    onDestinationSelected = runwayViewModel::selectDestination,
                    onCalendarMonthSelected = runwayViewModel::loadCalendarMonth,
                    onRefresh = runwayViewModel::refresh,
                    onAction = runwayViewModel::submitAction,
                    onConfirmActionPreview = runwayViewModel::confirmActionPreview,
                    onDismissActionPreview = runwayViewModel::dismissActionPreview,
                    onSignOut = runwayViewModel::signOut,
                    onOpenServer = {
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

    companion object {
        internal const val ACTION_OPEN_FOLDER_SETTINGS =
            "dev.deftmartian.runway.OPEN_FOLDER_SETTINGS"
        internal const val FOLDER_SHORTCUT_ID = "device-folder"
        internal const val SERVER_SHORTCUT_ID = "server-connection"
    }
}
