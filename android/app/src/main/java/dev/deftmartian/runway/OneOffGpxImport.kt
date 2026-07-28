package dev.deftmartian.runway

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.security.MessageDigest

internal enum class OneOffGpxImportOutcome {
    Imported, Duplicate, Quarantined, Retryable, PairingRequired, ServerChanged, Rejected, TooLarge,
}

/** Shared bounded, review-only GPX path for Android shares and the in-app document picker. */
internal object OneOffGpxImport {
    fun importUri(context: Context, expected: ServerConnection, uri: Uri): OneOffGpxImportOutcome {
        if (uri.scheme != "content") return OneOffGpxImportOutcome.Rejected
        if (!ServerConnectionStore(context).isCurrent(expected)) {
            return OneOffGpxImportOutcome.ServerChanged
        }
        val store = AndroidCredentialStore(context, expected.origin)
        val state = store.snapshot()
        val credential = state.credential ?: run {
            ReconciliationScheduler.cancelAll(context)
            ReconciliationStatusStore(context).record(ReconciliationWorker.STATE_PAIRING_REQUIRED)
            return OneOffGpxImportOutcome.PairingRequired
        }
        val metadata = metadata(context, uri)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val size = metadata.second
        if (!GpxCandidatePolicy.isCandidate(metadata.first, mime, size)) {
            return if (size != null && size > GpxCandidatePolicy.MAX_FILE_BYTES) {
                OneOffGpxImportOutcome.TooLarge
            } else OneOffGpxImportOutcome.Rejected
        }
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use {
                BoundedStreamInspector.readBytes(it, GpxCandidatePolicy.MAX_FILE_BYTES)
            } ?: return OneOffGpxImportOutcome.Rejected
            if (bytes.isEmpty()) return OneOffGpxImportOutcome.Rejected
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val requests = ShareImportRequestStore(context)
            val requestId = requests.requestIdFor(expected.origin, credential.deviceId, digest)
            val result = store.useIfCurrent(state) { current ->
                if (!ServerConnectionStore(context).isCurrent(expected)) null
                else RunwayApiClient(expected.origin).importGpx(current, bytes, requestId)
            } ?: return OneOffGpxImportOutcome.ServerChanged
            when (result) {
                is ImportApiResult.Handled -> {
                    requests.clear(expected.origin, credential.deviceId, digest)
                    when (result.result) {
                        "imported" -> OneOffGpxImportOutcome.Imported
                        "duplicate" -> OneOffGpxImportOutcome.Duplicate
                        else -> OneOffGpxImportOutcome.Quarantined
                    }
                }
                ImportApiResult.Unauthorized -> {
                    requests.clear(expected.origin, credential.deviceId, digest)
                    if (ServerConnectionStore(context).mutateIfCurrent(expected) {
                            store.clearIfCurrent(state).also { cleared ->
                                if (cleared) {
                                    HandledImportStore(context).clearForDevice(credential.deviceId)
                                    ReconciliationScheduler.cancelAll(context)
                                    ReconciliationStatusStore(context).record(
                                        ReconciliationWorker.STATE_PAIRING_REQUIRED,
                                    )
                                }
                            }
                        } == true) OneOffGpxImportOutcome.PairingRequired else OneOffGpxImportOutcome.ServerChanged
                }
                ImportApiResult.RequestConflict -> {
                    requests.clear(expected.origin, credential.deviceId, digest)
                    OneOffGpxImportOutcome.Retryable
                }
                ImportApiResult.Retryable -> OneOffGpxImportOutcome.Retryable
            }
        } catch (_: PayloadTooLargeException) { OneOffGpxImportOutcome.TooLarge
        } catch (_: SecurityException) { OneOffGpxImportOutcome.Rejected
        } catch (_: IOException) { OneOffGpxImportOutcome.Rejected
        } catch (_: RuntimeException) { OneOffGpxImportOutcome.Rejected }
    }

    private fun metadata(context: Context, uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.takeIf { !cursor.isNull(it) }?.let(cursor::getString)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                    ?.takeIf { !cursor.isNull(it) }?.let(cursor::getLong)
                name to size
            } ?: (null to null)
    }.getOrDefault(null to null)
}

internal fun oneOffGpxStatus(outcome: OneOffGpxImportOutcome): Int = when (outcome) {
    OneOffGpxImportOutcome.Imported -> R.string.share_imported
    OneOffGpxImportOutcome.Duplicate -> R.string.share_duplicate
    OneOffGpxImportOutcome.Quarantined -> R.string.share_quarantined
    OneOffGpxImportOutcome.Retryable -> R.string.share_retryable
    OneOffGpxImportOutcome.PairingRequired -> R.string.share_pairing_required
    OneOffGpxImportOutcome.ServerChanged -> R.string.share_server_changed
    OneOffGpxImportOutcome.Rejected -> R.string.share_rejected
    OneOffGpxImportOutcome.TooLarge -> R.string.share_too_large
}
