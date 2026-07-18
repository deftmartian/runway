package com.deftmartian.runway

import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class NativeFolderSettingsActivity : ComponentActivity() {
    private lateinit var treeAccessStore: TreeAccessStore
    private lateinit var folderStatus: TextView
    private lateinit var disconnectButton: Button

    private val chooseDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val connected = treeAccessStore.connect(uri)
        refreshFolderState()
        if (connected) {
            ReconciliationScheduler.runOnce(this)
            ReconciliationScheduler.enablePeriodic(this)
            folderStatus.setText(R.string.folder_connected_background)
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
        setContentView(buildContent())
        refreshFolderState()
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }

        content.addView(textView(R.string.folder_screen_title, 26f, Typeface.BOLD))
        content.addView(textView(R.string.folder_screen_intro, 16f).withTopMargin(8))

        folderStatus = textView(R.string.folder_not_connected, 16f)
        content.addView(folderStatus.withTopMargin(24))
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
            if (treeAccessStore.currentState() is TreeAccessState.Connected) {
                ReconciliationScheduler.runOnce(this)
                folderStatus.setText(R.string.check_queued)
            } else {
                refreshFolderState()
            }
        }.withTopMargin(20))
        content.addView(button(R.string.enable_background) {
            if (treeAccessStore.currentState() is TreeAccessState.Connected) {
                ReconciliationScheduler.enablePeriodic(this)
                folderStatus.setText(R.string.background_enabled)
            } else {
                refreshFolderState()
            }
        }.withTopMargin(8))
        content.addView(button(R.string.disable_background) {
            ReconciliationScheduler.disablePeriodic(this)
            folderStatus.setText(R.string.background_disabled)
        }.withTopMargin(8))

        content.addView(textView(R.string.upload_blocked, 14f).withTopMargin(28))
        content.addView(button(R.string.return_to_runway) {
            startActivity(Intent(this, RunwayLauncherActivity::class.java))
            finish()
        }.withTopMargin(24))

        return ScrollView(this).apply { addView(content) }
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
