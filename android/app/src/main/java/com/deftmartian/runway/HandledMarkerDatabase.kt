package com.deftmartian.runway

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class HandledMarkerDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_HANDLED_MARKER (
                device_key TEXT NOT NULL,
                marker TEXT NOT NULL,
                handled_order INTEGER NOT NULL,
                PRIMARY KEY (device_key, marker)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX handled_marker_recency
            ON $TABLE_HANDLED_MARKER (device_key, handled_order DESC)
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun contains(deviceKey: String, marker: String): Boolean = readableDatabase.query(
        TABLE_HANDLED_MARKER,
        arrayOf(COLUMN_MARKER),
        "$COLUMN_DEVICE_KEY = ? AND $COLUMN_MARKER = ?",
        arrayOf(deviceKey, marker),
        null,
        null,
        null,
        "1",
    ).use { it.moveToFirst() }

    fun findHandled(deviceKey: String, markers: Collection<String>): Set<String> {
        if (markers.isEmpty()) return emptySet()
        val handled = mutableSetOf<String>()
        markers.chunked(MAX_QUERY_MARKERS).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            val selection = "$COLUMN_DEVICE_KEY = ? AND $COLUMN_MARKER IN ($placeholders)"
            val arguments = arrayOf(deviceKey, *chunk.toTypedArray())
            readableDatabase.query(
                TABLE_HANDLED_MARKER,
                arrayOf(COLUMN_MARKER),
                selection,
                arguments,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) handled += cursor.getString(0)
            }
        }
        return handled
    }

    fun record(deviceKey: String, marker: String) {
        writableDatabase.inTransaction { database ->
            insert(database, deviceKey, marker)
            prune(database, deviceKey)
        }
    }

    fun migrate(deviceKey: String, markers: Collection<String>) {
        if (markers.isEmpty()) return
        writableDatabase.inTransaction { database ->
            markers.forEach { marker -> insert(database, deviceKey, marker) }
            prune(database, deviceKey)
        }
    }

    fun clearDevice(deviceKey: String) {
        writableDatabase.delete(
            TABLE_HANDLED_MARKER,
            "$COLUMN_DEVICE_KEY = ?",
            arrayOf(deviceKey),
        )
    }

    fun clearAll() {
        writableDatabase.delete(TABLE_HANDLED_MARKER, null, null)
    }

    private fun insert(database: SQLiteDatabase, deviceKey: String, marker: String) {
        val nextOrder = DatabaseUtils.longForQuery(
            database,
            """
            SELECT COALESCE(MAX($COLUMN_HANDLED_ORDER), 0) + 1
            FROM $TABLE_HANDLED_MARKER
            WHERE $COLUMN_DEVICE_KEY = ?
            """.trimIndent(),
            arrayOf(deviceKey),
        )
        val values = ContentValues(3).apply {
            put(COLUMN_DEVICE_KEY, deviceKey)
            put(COLUMN_MARKER, marker)
            put(COLUMN_HANDLED_ORDER, nextOrder)
        }
        val inserted = database.insertWithOnConflict(
            TABLE_HANDLED_MARKER,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(inserted != -1L) { "Unable to persist handled import marker" }
    }

    private fun prune(database: SQLiteDatabase, deviceKey: String) {
        database.execSQL(
            """
            DELETE FROM $TABLE_HANDLED_MARKER
            WHERE $COLUMN_DEVICE_KEY = ?
              AND rowid NOT IN (
                SELECT rowid
                FROM $TABLE_HANDLED_MARKER
                WHERE $COLUMN_DEVICE_KEY = ?
                ORDER BY $COLUMN_HANDLED_ORDER DESC, rowid DESC
                LIMIT $MAX_MARKERS_PER_DEVICE
              )
            """.trimIndent(),
            arrayOf(deviceKey, deviceKey),
        )
    }

    private inline fun SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> Unit) {
        beginTransaction()
        try {
            block(this)
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val DATABASE_NAME = "runway_handled_imports.db"
        const val DATABASE_VERSION = 1
        const val TABLE_HANDLED_MARKER = "handled_marker"
        const val COLUMN_DEVICE_KEY = "device_key"
        const val COLUMN_MARKER = "marker"
        const val COLUMN_HANDLED_ORDER = "handled_order"
        const val MAX_MARKERS_PER_DEVICE = 10_000
        const val MAX_QUERY_MARKERS = 800
    }
}
