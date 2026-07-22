package com.deftmartian.runway

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat

class RunwayLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        publishShortcuts()
        ReconciliationScheduler.runOnce(this)

        val configuredOrigin = ServerConnectionStore(this).currentOrigin()
        if (configuredOrigin == null) {
            startActivity(Intent(this, ServerConnectionActivity::class.java))
            finish()
            return
        }

        val candidate = intent.data?.toString()
        val launchingUrl = candidate
            ?.takeIf { InstanceOriginPolicy.belongsTo(it, configuredOrigin) }
            ?.let(Uri::parse)
            ?: Uri.parse("$configuredOrigin/app")
        openInCustomTab(launchingUrl)
    }

    private fun openInCustomTab(url: Uri) {
        val lightColors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(this, R.color.runway_surface))
            .setNavigationBarColor(ContextCompat.getColor(this, R.color.runway_surface))
            .build()
        val darkColors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(this, R.color.runway_surface_dark))
            .setNavigationBarColor(ContextCompat.getColor(this, R.color.runway_surface_dark))
            .build()
        val customTab = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(lightColors)
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, darkColors)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .build()
        try {
            customTab.launchUrl(this, url)
            finish()
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url))
                finish()
            } catch (_: ActivityNotFoundException) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.browser_unavailable_title)
                    .setMessage(R.string.browser_unavailable_message)
                    .setPositiveButton(R.string.ok) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    private fun publishShortcuts() {
        if (Build.VERSION.SDK_INT < 25) return
        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
        val folderIntent = Intent(this, NativeFolderSettingsActivity::class.java).apply {
            action = ACTION_OPEN_FOLDER_SETTINGS
        }
        val folderShortcut = ShortcutInfo.Builder(this, FOLDER_SHORTCUT_ID)
            .setShortLabel(getString(R.string.folder_shortcut_short))
            .setLongLabel(getString(R.string.folder_shortcut_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(folderIntent)
            .build()
        val serverIntent = Intent(this, ServerConnectionActivity::class.java).apply {
            action = ServerConnectionActivity.ACTION_CHANGE_SERVER
        }
        val serverShortcut = ShortcutInfo.Builder(this, SERVER_SHORTCUT_ID)
            .setShortLabel(getString(R.string.server_shortcut_short))
            .setLongLabel(getString(R.string.server_shortcut_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(serverIntent)
            .build()
        runCatching { shortcutManager.setDynamicShortcuts(listOf(folderShortcut, serverShortcut)) }
    }

    companion object {
        internal const val ACTION_OPEN_FOLDER_SETTINGS = "com.deftmartian.runway.OPEN_FOLDER_SETTINGS"
        internal const val FOLDER_SHORTCUT_ID = "device-folder"
        internal const val SERVER_SHORTCUT_ID = "server-connection"
    }
}
