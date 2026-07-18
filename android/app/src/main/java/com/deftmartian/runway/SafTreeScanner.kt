package com.deftmartian.runway

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

data class GpxTreeCandidate(
    val uri: Uri,
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long?,
)

data class TreeScanSummary(
    val entriesScanned: Int,
    val candidates: List<GpxTreeCandidate>,
    val truncated: Boolean,
) {
    val gpxCandidates: Int = candidates.size
}

sealed interface TreeScanResult {
    data class Success(val summary: TreeScanSummary) : TreeScanResult
    data object PermissionRequired : TreeScanResult
    data object ProviderError : TreeScanResult
}

class SafTreeScanner(private val contentResolver: ContentResolver) {
    fun scan(state: TreeAccessState): TreeScanResult {
        if (state !is TreeAccessState.Connected) return TreeScanResult.PermissionRequired

        return try {
            val parentDocumentId = DocumentsContract.getTreeDocumentId(state.uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                state.uri,
                parentDocumentId,
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val cursor = contentResolver.query(childrenUri, projection, null, null, null)
                ?: return TreeScanResult.ProviderError

            cursor.use {
                val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val documentIdColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                var entries = 0
                val candidates = mutableListOf<GpxTreeCandidate>()

                while (entries < MAX_ENTRIES_PER_SCAN && it.moveToNext()) {
                    entries += 1
                    val displayName = if (nameColumn >= 0 && !it.isNull(nameColumn)) {
                        it.getString(nameColumn)
                    } else {
                        null
                    }
                    val mimeType = if (mimeColumn >= 0 && !it.isNull(mimeColumn)) {
                        it.getString(mimeColumn)
                    } else {
                        null
                    }
                    val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) {
                        it.getLong(sizeColumn)
                    } else {
                        null
                    }
                    if (GpxCandidatePolicy.isCandidate(displayName, mimeType, size)) {
                        if (documentIdColumn < 0 || it.isNull(documentIdColumn)) continue
                        candidates += GpxTreeCandidate(
                            uri = DocumentsContract.buildDocumentUriUsingTree(
                                state.uri,
                                it.getString(documentIdColumn),
                            ),
                            sizeBytes = size,
                            lastModifiedEpochMs = if (modifiedColumn >= 0 && !it.isNull(modifiedColumn)) {
                                it.getLong(modifiedColumn).takeIf { value -> value > 0 }
                            } else {
                                null
                            },
                        )
                    }
                }

                val hasMore = entries == MAX_ENTRIES_PER_SCAN && it.moveToNext()
                TreeScanResult.Success(
                    TreeScanSummary(
                        entriesScanned = entries,
                        candidates = candidates,
                        truncated = hasMore,
                    ),
                )
            }
        } catch (_: SecurityException) {
            TreeScanResult.PermissionRequired
        } catch (_: RuntimeException) {
            TreeScanResult.ProviderError
        }
    }

    private companion object {
        const val MAX_ENTRIES_PER_SCAN = 10_000
    }
}
