package com.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.google.androidbrowserhelper.trusted.TwaLauncher

class RunwayLauncherActivity : LauncherActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        publishFolderShortcut()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        ReconciliationScheduler.runOnce(this)
    }

    override fun getUrlForIntent(intent: Intent): Uri? {
        val candidate = intent.data ?: return null
        return candidate.takeIf {
            InstanceOriginPolicy.belongsTo(it.toString(), BuildConfig.RUNWAY_ORIGIN)
        }
    }

    override fun getFallbackStrategy(): TwaLauncher.FallbackStrategy =
        TwaLauncher.CCT_FALLBACK_STRATEGY

    private fun publishFolderShortcut() {
        if (Build.VERSION.SDK_INT < 25) return
        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
        val folderIntent = Intent(this, NativeFolderSettingsActivity::class.java).apply {
            action = ACTION_OPEN_FOLDER_SETTINGS
        }
        val shortcut = ShortcutInfo.Builder(this, FOLDER_SHORTCUT_ID)
            .setShortLabel(getString(R.string.folder_shortcut_short))
            .setLongLabel(getString(R.string.folder_shortcut_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(folderIntent)
            .build()
        runCatching { shortcutManager.setDynamicShortcuts(listOf(shortcut)) }
    }

    companion object {
        internal const val ACTION_OPEN_FOLDER_SETTINGS = "com.deftmartian.runway.OPEN_FOLDER_SETTINGS"
        internal const val FOLDER_SHORTCUT_ID = "device-folder"
    }
}
