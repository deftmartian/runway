package com.deftmartian.runway

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

class ShareReceiverActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val content = buildContent()
        setContentView(content)
        EdgeToEdgeLayout.applySystemBarPadding(content)
        inspectSharedFile()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun inspectSharedFile() {
        val serverStore = ServerConnectionStore(this)
        val serverConnection = serverStore.currentConnection()
        if (serverConnection == null) {
            status.setText(R.string.share_server_required)
            return
        }
        val origin = serverConnection.origin
        val credentialStore = AndroidCredentialStore(this, origin)
        val credentialState = credentialStore.snapshot()
        val credential = credentialState.credential
        if (credential == null) {
            val handled = serverStore.mutateIfCurrent(serverConnection) {
                if (!credentialStore.isCurrent(credentialState)) {
                    false
                } else {
                    ReconciliationScheduler.cancelAll(this)
                    ReconciliationStatusStore(this).record(
                        ReconciliationWorker.STATE_PAIRING_REQUIRED,
                    )
                    true
                }
            } == true
            status.setText(
                if (handled) R.string.share_pairing_required else R.string.share_server_changed,
            )
            return
        }
        val uri = resolveSingleContentUri()
        if (intent.action != Intent.ACTION_SEND || uri == null) {
            status.setText(R.string.share_rejected)
            return
        }

        val permission = checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.share_rejected)
            return
        }

        val metadata = readMetadata(uri)
        val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull() ?: intent.type
        if (!GpxCandidatePolicy.isCandidate(metadata.displayName, mimeType, metadata.sizeBytes)) {
            status.setText(
                if (metadata.sizeBytes != null && metadata.sizeBytes > GpxCandidatePolicy.MAX_FILE_BYTES) {
                    R.string.share_too_large
                } else {
                    R.string.share_rejected
                },
            )
            return
        }

        executor.execute {
            val result = try {
                val bytes = contentResolver.openInputStream(uri)?.use {
                    BoundedStreamInspector.readBytes(it, GpxCandidatePolicy.MAX_FILE_BYTES)
                } ?: ByteArray(0)
                if (bytes.isEmpty()) {
                    R.string.share_rejected
                } else {
                    val contentSha256 = sha256(bytes)
                    val requestStore = ShareImportRequestStore(this)
                    val requestId = requestStore.requestIdFor(
                        origin = origin,
                        deviceId = credential.deviceId,
                        contentSha256 = contentSha256,
                    )
                    val imported = credentialStore.useIfCurrent(credentialState) { current ->
                        if (!serverStore.isCurrent(serverConnection)) return@useIfCurrent null
                        RunwayApiClient(origin).importGpx(current, bytes, requestId)
                    }
                    if (imported == null || !serverStore.isCurrent(serverConnection)) {
                        R.string.share_server_changed
                    } else when (imported) {
                        is ImportApiResult.Handled -> {
                            requestStore.clear(origin, credential.deviceId, contentSha256)
                            when (imported.result) {
                                "imported" -> R.string.share_imported
                                "duplicate" -> R.string.share_duplicate
                                else -> R.string.share_quarantined
                            }
                        }
                        ImportApiResult.Unauthorized -> {
                            requestStore.clear(origin, credential.deviceId, contentSha256)
                            val cleared = serverStore.mutateIfCurrent(serverConnection) {
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
                            if (cleared) {
                                R.string.share_pairing_required
                            } else {
                                R.string.share_server_changed
                            }
                        }
                        ImportApiResult.RequestConflict -> {
                            requestStore.clear(origin, credential.deviceId, contentSha256)
                            R.string.share_retryable
                        }
                        ImportApiResult.Retryable -> R.string.share_retryable
                    }
                }
            } catch (_: PayloadTooLargeException) {
                R.string.share_too_large
            } catch (_: SecurityException) {
                R.string.share_rejected
            } catch (_: IOException) {
                R.string.share_rejected
            } catch (_: RuntimeException) {
                R.string.share_rejected
            }
            runOnUiThread {
                if (!isDestroyed) status.setText(result)
            }
        }
    }

    private fun resolveSingleContentUri(): Uri? {
        if (intent.clipData?.itemCount?.let { it > 1 } == true) return null

        val candidates = buildList {
            sharedStreamExtra()?.let(::add)
            intent.clipData?.getItemAt(0)?.uri?.let(::add)
        }.distinctBy(Uri::toString)

        return candidates.singleOrNull()?.takeIf { it.scheme == "content" }
    }

    @Suppress("DEPRECATION")
    private fun sharedStreamExtra(): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun readMetadata(uri: Uri): SharedFileMetadata {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use SharedFileMetadata(null, null)
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                SharedFileMetadata(
                    displayName = if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                        cursor.getString(nameColumn)
                    } else {
                        null
                    },
                    sizeBytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        cursor.getLong(sizeColumn)
                    } else {
                        null
                    },
                )
            } ?: SharedFileMetadata(null, null)
        } catch (_: RuntimeException) {
            SharedFileMetadata(null, null)
        }
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        content.addView(TextView(this).apply {
            setText(R.string.share_title)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        status = TextView(this).apply {
            setText(R.string.share_checking)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(16) }
        }
        content.addView(status)
        content.addView(Button(this).apply {
            setText(R.string.open_runway)
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@ShareReceiverActivity, ServerConnectionActivity::class.java))
                finish()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(24) }
        })
        content.addView(Button(this).apply {
            setText(R.string.close)
            isAllCaps = false
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(8) }
        })
        return content
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class SharedFileMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )
}
