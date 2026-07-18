package com.deftmartian.runway

import android.content.ContentResolver
import android.provider.DocumentsContract

data class TreeScanSummary(
    val entriesScanned: Int,
    val gpxCandidates: Int,
    val truncated: Boolean,
)

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
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val cursor = contentResolver.query(childrenUri, projection, null, null, null)
                ?: return TreeScanResult.ProviderError

            cursor.use {
                val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                var entries = 0
                var candidates = 0
                var truncated = false

                while (it.moveToNext()) {
                    if (entries >= MAX_ENTRIES_PER_SCAN) {
                        truncated = true
                        break
                    }
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
                        candidates += 1
                    }
                }

                TreeScanResult.Success(TreeScanSummary(entries, candidates, truncated))
            }
        } catch (_: SecurityException) {
            TreeScanResult.PermissionRequired
        } catch (_: RuntimeException) {
            TreeScanResult.ProviderError
        }
    }

    private companion object {
        const val MAX_ENTRIES_PER_SCAN = 2_000
    }
}
