package dev.deftmartian.runway

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.deftmartian.runway.data.importing.LocalGpxImportException
import dev.deftmartian.runway.data.importing.LocalGpxImportFailure
import dev.deftmartian.runway.data.importing.LocalGpxImportOutcome
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal enum class OneOffGpxImportOutcome {
    Imported,
    Duplicate,
    DeletedPreviously,
    ConfigurationRequired,
    FutureActivity,
    Interrupted,
    Rejected,
    TooLarge,
}

/** Shared, streaming, review-only GPX path for Android shares and the in-app document picker. */
internal object OneOffGpxImport {
    suspend fun importUri(
        context: Context,
        uri: Uri,
    ): OneOffGpxImportOutcome =
        try {
            AndroidStateCoordinator.withImportDataBoundary {
                importUriWithinBoundary(context, uri)
            }
        } catch (_: ImportAcquisitionClosedException) {
            OneOffGpxImportOutcome.Interrupted
        }

    private suspend fun importUriWithinBoundary(
        context: Context,
        uri: Uri,
    ): OneOffGpxImportOutcome {
        if (uri.scheme != "content") return OneOffGpxImportOutcome.Rejected
        val metadata = metadata(context, uri)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val size = metadata.second
        if (!GpxCandidatePolicy.isCandidate(metadata.first, mime, size)) {
            return if (size != null && size > GpxCandidatePolicy.MAX_FILE_BYTES) {
                OneOffGpxImportOutcome.TooLarge
            } else {
                OneOffGpxImportOutcome.Rejected
            }
        }
        return try {
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                context.runwayServices.gpxImports.import(input)
            } ?: return OneOffGpxImportOutcome.Rejected
            when (result) {
                is LocalGpxImportOutcome.Imported -> OneOffGpxImportOutcome.Imported
                is LocalGpxImportOutcome.Duplicate -> OneOffGpxImportOutcome.Duplicate
                LocalGpxImportOutcome.Tombstoned -> OneOffGpxImportOutcome.DeletedPreviously
                LocalGpxImportOutcome.ConfigurationRequired ->
                    OneOffGpxImportOutcome.ConfigurationRequired
                LocalGpxImportOutcome.FutureActivity -> OneOffGpxImportOutcome.FutureActivity
            }
        } catch (error: LocalGpxImportException) {
            if (error.reason == LocalGpxImportFailure.TOO_LARGE) {
                OneOffGpxImportOutcome.TooLarge
            } else {
                OneOffGpxImportOutcome.Rejected
            }
        } catch (_: SecurityException) {
            OneOffGpxImportOutcome.Rejected
        } catch (_: IOException) {
            OneOffGpxImportOutcome.Rejected
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            OneOffGpxImportOutcome.Rejected
        }
    }

    private fun metadata(context: Context, uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
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
    OneOffGpxImportOutcome.DeletedPreviously -> R.string.share_deleted_previously
    OneOffGpxImportOutcome.ConfigurationRequired -> R.string.share_setup_required
    OneOffGpxImportOutcome.FutureActivity -> R.string.share_future_activity
    OneOffGpxImportOutcome.Interrupted -> R.string.share_interrupted
    OneOffGpxImportOutcome.Rejected -> R.string.share_rejected
    OneOffGpxImportOutcome.TooLarge -> R.string.share_too_large
}
