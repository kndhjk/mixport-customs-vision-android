package nz.co.mixport.customsvision.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.contentValuesOf
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.core.database.sqlite.transaction

class CustomsDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE inspection_session (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                container_code TEXT NOT NULL,
                vessel_name TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                notes TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                recording_uri TEXT,
                container_has_remaining_cargo INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pallet (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                sequence_number INTEGER NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                closed_at INTEGER,
                wrap_detected INTEGER NOT NULL DEFAULT 0,
                container_empty_at_close INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(session_id) REFERENCES inspection_session(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pallet_item_summary (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pallet_id INTEGER NOT NULL,
                item_label TEXT NOT NULL,
                color_name TEXT NOT NULL,
                marker_text TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                FOREIGN KEY(pallet_id) REFERENCES pallet(id) ON DELETE CASCADE,
                UNIQUE(pallet_id, item_label, color_name, marker_text)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE event_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                pallet_id INTEGER,
                event_type TEXT NOT NULL,
                message TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES inspection_session(id) ON DELETE CASCADE,
                FOREIGN KEY(pallet_id) REFERENCES pallet(id) ON DELETE SET NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS event_log")
        db.execSQL("DROP TABLE IF EXISTS pallet_item_summary")
        db.execSQL("DROP TABLE IF EXISTS pallet")
        db.execSQL("DROP TABLE IF EXISTS inspection_session")
        onCreate(db)
    }

    fun createSession(draft: SessionDraft, startedAt: Long): InspectionSessionRecord {
        val values = contentValuesOf(
            "container_code" to draft.containerCode,
            "vessel_name" to draft.vesselName,
            "operator_name" to draft.operatorName,
            "notes" to draft.notes,
            "status" to "ACTIVE",
            "started_at" to startedAt,
            "container_has_remaining_cargo" to 1,
        )
        val id = writableDatabase.insertOrThrow("inspection_session", null, values)
        return getSession(id) ?: error("Failed to read newly created session.")
    }

    fun getSession(sessionId: Long): InspectionSessionRecord? {
        val cursor = readableDatabase.query(
            "inspection_session",
            null,
            "id = ?",
            arrayOf(sessionId.toString()),
            null,
            null,
            null,
        )
        return cursor.use {
            if (it.moveToFirst()) {
                it.toSessionRecord()
            } else {
                null
            }
        }
    }

    fun listSessions(): List<InspectionSessionRecord> {
        val cursor = readableDatabase.query(
            "inspection_session",
            null,
            null,
            null,
            null,
            null,
            "started_at DESC",
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.toSessionRecord())
                }
            }
        }
    }

    fun lookupBarcode(barcode: String): BarcodeLookupResult? {
        val normalized = barcode.trim().uppercase()
        if (normalized.isBlank()) {
            return null
        }

        val sessionCursor = readableDatabase.query(
            "inspection_session",
            arrayOf("container_code", "status"),
            "UPPER(container_code) = ?",
            arrayOf(normalized),
            null,
            null,
            "started_at DESC",
            "1",
        )
        sessionCursor.use { cursor ->
            if (cursor.moveToFirst()) {
                return BarcodeLookupResult(
                    found = true,
                    databaseRecord = cursor.getString(cursor.getColumnIndexOrThrow("container_code")),
                    status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                    source = "SESSION",
                )
            }
        }

        val itemCursor = readableDatabase.rawQuery(
            """
            SELECT pis.marker_text, pis.item_label, s.container_code
            FROM pallet_item_summary pis
            INNER JOIN pallet p ON p.id = pis.pallet_id
            INNER JOIN inspection_session s ON s.id = p.session_id
            WHERE UPPER(pis.marker_text) = ?
                OR UPPER(pis.marker_text) LIKE ?
                OR UPPER(pis.item_label) = ?
            ORDER BY p.started_at DESC, pis.id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(normalized, "%$normalized%", normalized),
        )
        itemCursor.use { cursor ->
            if (cursor.moveToFirst()) {
                val markerText = cursor.getString(cursor.getColumnIndexOrThrow("marker_text"))
                val itemLabel = cursor.getString(cursor.getColumnIndexOrThrow("item_label"))
                val containerCode = cursor.getString(cursor.getColumnIndexOrThrow("container_code"))
                return BarcodeLookupResult(
                    found = true,
                    databaseRecord = markerText.ifBlank { itemLabel },
                    status = "$itemLabel · $containerCode",
                    source = "PALLET_ITEM",
                )
            }
        }

        return null
    }

    fun updateSessionStatus(
        sessionId: Long,
        status: String,
        endedAt: Long? = null,
        recordingUri: String? = null,
        containerHasRemainingCargo: Boolean? = null,
    ) {
        val values = ContentValues().apply {
            put("status", status)
            endedAt?.let { put("ended_at", it) }
            if (recordingUri != null) {
                put("recording_uri", recordingUri)
            }
            if (containerHasRemainingCargo != null) {
                put("container_has_remaining_cargo", if (containerHasRemainingCargo) 1 else 0)
            }
        }
        writableDatabase.update(
            "inspection_session",
            values,
            "id = ?",
            arrayOf(sessionId.toString()),
        )
    }

    fun createPallet(sessionId: Long, sequenceNumber: Int, startedAt: Long): PalletRecord {
        val values = contentValuesOf(
            "session_id" to sessionId,
            "sequence_number" to sequenceNumber,
            "status" to "LOADING",
            "started_at" to startedAt,
            "wrap_detected" to 0,
            "container_empty_at_close" to 0,
        )
        val id = writableDatabase.insertOrThrow("pallet", null, values)
        return getPallet(id) ?: error("Failed to read newly created pallet.")
    }

    fun getPallet(palletId: Long): PalletRecord? {
        val cursor = readableDatabase.query(
            "pallet",
            null,
            "id = ?",
            arrayOf(palletId.toString()),
            null,
            null,
            null,
        )
        return cursor.use {
            if (it.moveToFirst()) {
                it.toPalletRecord()
            } else {
                null
            }
        }
    }

    fun listPallets(sessionId: Long): List<PalletRecord> {
        val cursor = readableDatabase.query(
            "pallet",
            null,
            "session_id = ?",
            arrayOf(sessionId.toString()),
            null,
            null,
            "sequence_number DESC",
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.toPalletRecord())
                }
            }
        }
    }

    fun closePallet(
        palletId: Long,
        closedAt: Long,
        wrapDetected: Boolean,
        containerEmptyAtClose: Boolean,
    ): PalletRecord {
        val values = contentValuesOf(
            "status" to "SEALED",
            "closed_at" to closedAt,
            "wrap_detected" to if (wrapDetected) 1 else 0,
            "container_empty_at_close" to if (containerEmptyAtClose) 1 else 0,
        )
        writableDatabase.update(
            "pallet",
            values,
            "id = ?",
            arrayOf(palletId.toString()),
        )
        return getPallet(palletId) ?: error("Failed to read sealed pallet.")
    }

    fun incrementPalletItem(
        palletId: Long,
        itemLabel: String,
        colorName: String,
        markerText: String,
        delta: Int,
    ): CargoSummaryRecord {
        val db = writableDatabase
        db.transaction {
            val cursor = query(
                "pallet_item_summary",
                null,
                "pallet_id = ? AND item_label = ? AND color_name = ? AND marker_text = ?",
                arrayOf(palletId.toString(), itemLabel, colorName, markerText),
                null,
                null,
                null,
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("id"))
                    val currentQuantity = it.getInt(it.getColumnIndexOrThrow("quantity"))
                    val updatedValues = contentValuesOf("quantity" to currentQuantity + delta)
                    update(
                        "pallet_item_summary",
                        updatedValues,
                        "id = ?",
                        arrayOf(id.toString()),
                    )
                } else {
                    val values = contentValuesOf(
                        "pallet_id" to palletId,
                        "item_label" to itemLabel,
                        "color_name" to colorName,
                        "marker_text" to markerText,
                        "quantity" to delta,
                    )
                    insertOrThrow("pallet_item_summary", null, values)
                }
            }
        }
        return getPalletItems(palletId).first {
            it.itemLabel == itemLabel &&
                it.colorName == colorName &&
                it.markerText == markerText
        }
    }

    fun getPalletItems(palletId: Long): List<CargoSummaryRecord> {
        val cursor = readableDatabase.query(
            "pallet_item_summary",
            null,
            "pallet_id = ?",
            arrayOf(palletId.toString()),
            null,
            null,
            "quantity DESC, item_label ASC",
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.toCargoSummaryRecord())
                }
            }
        }
    }

    fun insertEvent(
        sessionId: Long,
        palletId: Long?,
        eventType: String,
        message: String,
        payloadJson: String,
        createdAt: Long,
    ): EventLogRecord {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            if (palletId != null) {
                put("pallet_id", palletId)
            } else {
                putNull("pallet_id")
            }
            put("event_type", eventType)
            put("message", message)
            put("payload_json", payloadJson)
            put("created_at", createdAt)
        }
        val id = writableDatabase.insertOrThrow("event_log", null, values)
        return getEvent(id) ?: error("Failed to read event log row.")
    }

    fun listEvents(sessionId: Long, limit: Int = 20): List<EventLogRecord> {
        val cursor = readableDatabase.query(
            "event_log",
            null,
            "session_id = ?",
            arrayOf(sessionId.toString()),
            null,
            null,
            "created_at DESC",
            limit.toString(),
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.toEventLogRecord())
                }
            }
        }
    }

    private fun getEvent(eventId: Long): EventLogRecord? {
        val cursor = readableDatabase.query(
            "event_log",
            null,
            "id = ?",
            arrayOf(eventId.toString()),
            null,
            null,
            null,
        )
        return cursor.use {
            if (it.moveToFirst()) {
                it.toEventLogRecord()
            } else {
                null
            }
        }
    }

    private fun Cursor.toSessionRecord(): InspectionSessionRecord = InspectionSessionRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        containerCode = getString(getColumnIndexOrThrow("container_code")),
        vesselName = getString(getColumnIndexOrThrow("vessel_name")),
        operatorName = getString(getColumnIndexOrThrow("operator_name")),
        notes = getString(getColumnIndexOrThrow("notes")),
        status = getString(getColumnIndexOrThrow("status")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        endedAt = getLongOrNull(getColumnIndexOrThrow("ended_at")),
        recordingUri = getStringOrNull(getColumnIndexOrThrow("recording_uri")),
        containerHasRemainingCargo = getInt(getColumnIndexOrThrow("container_has_remaining_cargo")) == 1,
    )

    private fun Cursor.toPalletRecord(): PalletRecord = PalletRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        sessionId = getLong(getColumnIndexOrThrow("session_id")),
        sequenceNumber = getInt(getColumnIndexOrThrow("sequence_number")),
        status = getString(getColumnIndexOrThrow("status")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        closedAt = getLongOrNull(getColumnIndexOrThrow("closed_at")),
        wrapDetected = getInt(getColumnIndexOrThrow("wrap_detected")) == 1,
        containerEmptyAtClose = getInt(getColumnIndexOrThrow("container_empty_at_close")) == 1,
    )

    private fun Cursor.toCargoSummaryRecord(): CargoSummaryRecord = CargoSummaryRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        palletId = getLong(getColumnIndexOrThrow("pallet_id")),
        itemLabel = getString(getColumnIndexOrThrow("item_label")),
        colorName = getString(getColumnIndexOrThrow("color_name")),
        markerText = getString(getColumnIndexOrThrow("marker_text")),
        quantity = getInt(getColumnIndexOrThrow("quantity")),
    )

    private fun Cursor.toEventLogRecord(): EventLogRecord = EventLogRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        sessionId = getLong(getColumnIndexOrThrow("session_id")),
        palletId = getLongOrNull(getColumnIndexOrThrow("pallet_id")),
        eventType = getString(getColumnIndexOrThrow("event_type")),
        message = getString(getColumnIndexOrThrow("message")),
        payloadJson = getString(getColumnIndexOrThrow("payload_json")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
    )

    companion object {
        private const val DATABASE_NAME = "mixport_customs_pilot.db"
        private const val DATABASE_VERSION = 1
    }
}
