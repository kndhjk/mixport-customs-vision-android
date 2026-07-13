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
        createCoreTables(db)
        createScannerSyncTables(db)
        ensureScannerSyncSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createScannerSyncTables(db)
        }
        ensureScannerSyncSchema(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureScannerSyncSchema(db)
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
        val normalized = normalizeScannerBarcode(barcode)
        if (normalized.isBlank()) {
            return null
        }

        val referenceCursor = readableDatabase.query(
            "server_barcode_reference",
            null,
            "barcode_key = ?",
            arrayOf(normalized),
            null,
            null,
            null,
            "1",
        )
        referenceCursor.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toServerBarcodeLookupResult()
            }
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
                    status = "$itemLabel | $containerCode",
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

    fun storeServerBarcodeReferences(
        rows: List<ScannerBootstrapRow>,
        replaceExisting: Boolean = true,
    ) {
        val db = writableDatabase
        db.transaction {
            if (replaceExisting) {
                delete("server_barcode_reference", null, null)
            }
            rows.forEach { row ->
                insertWithOnConflict(
                    "server_barcode_reference",
                    null,
                    contentValuesOf(
                        "barcode_key" to normalizeScannerBarcode(row.barcodeKey),
                        "cargo_tracking_id" to row.cargoTrackingId,
                        "parent_hbl_no" to row.parentHblNo,
                        "matched_child_hbl" to row.matchedChildHbl,
                        "matched_barcode_code" to row.matchedBarcodeCode,
                        "matched_by" to row.matchedBy,
                        "child_hbls" to row.childHbls,
                        "barcode_codes" to row.barcodeCodes,
                        "status" to row.status,
                        "customers_status" to row.customersStatus,
                        "mpi_status" to row.mpiStatus,
                        "location" to row.location,
                        "pkgs" to row.pkgs,
                        "out_turn_qty" to row.outTurnQty,
                        "submission_date" to row.submissionDate,
                        "container_no" to row.containerNo,
                        "vessel_name" to row.vesselName,
                        "company" to row.company,
                        "customer_name" to row.customerName,
                        "updated_cursor" to row.updatedCursor,
                        "server_scan_count" to row.serverScanCount,
                        "server_matched_scan_count" to row.serverMatchedScanCount,
                        "server_mismatch_scan_count" to row.serverMismatchScanCount,
                        "server_error_scan_count" to row.serverErrorScanCount,
                        "server_last_scanned_at" to row.serverLastScannedAt,
                        "server_last_match_status" to row.serverLastMatchStatus,
                    ),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    fun deleteServerBarcodeReference(barcode: String) {
        val normalized = normalizeScannerBarcode(barcode)
        if (normalized.isBlank()) {
            return
        }
        writableDatabase.delete(
            "server_barcode_reference",
            "barcode_key = ?",
            arrayOf(normalized),
        )
    }

    fun recordScannerScan(record: ScannerRecord, lookupResult: BarcodeLookupResult?) {
        writableDatabase.insertOrThrow(
            "scanner_scan_log",
            null,
            ContentValues().apply {
                put("scanned_barcode", record.scannedBarcode)
                put("database_record", record.databaseRecord)
                put("match_status", record.matchStatus.name)
                put("status_text", record.status)
                put("source", record.source)
                put("scanned_at", record.scannedAt)
                put("cargo_tracking_id", lookupResult?.cargoTrackingId)
                put("parent_hbl_no", lookupResult?.parentHblNo)
                put("matched_child_hbl", lookupResult?.matchedChildHbl)
                put("matched_by", lookupResult?.matchedBy)
                put("child_hbls", lookupResult?.childHbls)
                put("container_no", lookupResult?.containerNo)
                put("vessel_name", lookupResult?.vesselName)
                put("company", lookupResult?.company)
                put("customer_name", lookupResult?.customerName)
                put("location", lookupResult?.location)
                put("sync_state", "PENDING")
            },
        )
    }

    fun listPendingScannerUploads(limit: Int = 500): List<PendingScannerUploadRecord> {
        val cursor = readableDatabase.query(
            "scanner_scan_log",
            null,
            "sync_state = ?",
            arrayOf("PENDING"),
            null,
            null,
            "scanned_at ASC",
            limit.toString(),
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.toPendingScannerUploadRecord())
                }
            }
        }
    }

    fun markScannerUploadsSynced(localIds: List<Long>, batchId: Long, uploadedAt: Long) {
        if (localIds.isEmpty()) {
            return
        }
        val placeholders = localIds.joinToString(",") { "?" }
        val statement = writableDatabase.compileStatement(
            "UPDATE scanner_scan_log SET sync_state='SYNCED', uploaded_batch_id=?, uploaded_at=? WHERE id IN ($placeholders)",
        )
        statement.bindLong(1, batchId)
        statement.bindLong(2, uploadedAt)
        localIds.forEachIndexed { index, localId ->
            statement.bindLong(index + 3, localId)
        }
        statement.executeUpdateDelete()
        statement.close()
    }

    fun getScannerReferenceCount(): Int {
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM server_barcode_reference", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getPendingScannerUploadCount(): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM scanner_scan_log WHERE sync_state = 'PENDING'",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
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

    private fun createCoreTables(db: SQLiteDatabase) {
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

    private fun createScannerSyncTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS server_barcode_reference (
                barcode_key TEXT PRIMARY KEY,
                cargo_tracking_id INTEGER NOT NULL,
                parent_hbl_no TEXT NOT NULL,
                matched_child_hbl TEXT NOT NULL,
                matched_barcode_code TEXT NOT NULL DEFAULT '',
                matched_by TEXT NOT NULL,
                child_hbls TEXT NOT NULL,
                barcode_codes TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                customers_status TEXT NOT NULL,
                mpi_status TEXT NOT NULL,
                location TEXT NOT NULL,
                pkgs INTEGER,
                out_turn_qty INTEGER,
                submission_date TEXT NOT NULL,
                container_no TEXT NOT NULL,
                vessel_name TEXT NOT NULL,
                company TEXT NOT NULL,
                customer_name TEXT NOT NULL,
                updated_cursor TEXT NOT NULL,
                server_scan_count INTEGER NOT NULL DEFAULT 0,
                server_matched_scan_count INTEGER NOT NULL DEFAULT 0,
                server_mismatch_scan_count INTEGER NOT NULL DEFAULT 0,
                server_error_scan_count INTEGER NOT NULL DEFAULT 0,
                server_last_scanned_at TEXT NOT NULL DEFAULT '',
                server_last_match_status TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scanner_scan_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scanned_barcode TEXT NOT NULL,
                database_record TEXT NOT NULL,
                match_status TEXT NOT NULL,
                status_text TEXT NOT NULL,
                source TEXT NOT NULL,
                scanned_at INTEGER NOT NULL,
                cargo_tracking_id INTEGER,
                parent_hbl_no TEXT,
                matched_child_hbl TEXT,
                matched_by TEXT,
                child_hbls TEXT,
                container_no TEXT,
                vessel_name TEXT,
                company TEXT,
                customer_name TEXT,
                location TEXT,
                sync_state TEXT NOT NULL DEFAULT 'PENDING',
                uploaded_batch_id INTEGER,
                uploaded_at INTEGER
            )
            """.trimIndent(),
        )
    }

    private fun ensureScannerSyncSchema(db: SQLiteDatabase) {
        ensureColumn(db, "server_barcode_reference", "matched_barcode_code", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "barcode_codes", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "customers_status", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "mpi_status", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "location", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "pkgs", "INTEGER")
        ensureColumn(db, "server_barcode_reference", "out_turn_qty", "INTEGER")
        ensureColumn(db, "server_barcode_reference", "submission_date", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "container_no", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "vessel_name", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "company", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "customer_name", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "updated_cursor", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "server_scan_count", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "server_barcode_reference", "server_matched_scan_count", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "server_barcode_reference", "server_mismatch_scan_count", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "server_barcode_reference", "server_error_scan_count", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "server_barcode_reference", "server_last_scanned_at", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "server_barcode_reference", "server_last_match_status", "TEXT NOT NULL DEFAULT ''")
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

    private fun Cursor.toServerBarcodeLookupResult(): BarcodeLookupResult = BarcodeLookupResult(
        found = true,
        databaseRecord = getString(getColumnIndexOrThrow("parent_hbl_no")),
        status = getString(getColumnIndexOrThrow("status")),
        source = "SERVER_CACHE",
        cargoTrackingId = getLong(getColumnIndexOrThrow("cargo_tracking_id")),
        parentHblNo = getString(getColumnIndexOrThrow("parent_hbl_no")),
        matchedChildHbl = getString(getColumnIndexOrThrow("matched_child_hbl")).ifBlank { null },
        matchedBarcodeCode = getStringOrNull(getColumnIndexOrThrow("matched_barcode_code"))?.ifBlank { null },
        matchedBy = getString(getColumnIndexOrThrow("matched_by")).ifBlank { null },
        childHbls = getString(getColumnIndexOrThrow("child_hbls")).ifBlank { null },
        barcodeCodes = getStringOrNull(getColumnIndexOrThrow("barcode_codes"))?.ifBlank { null },
        containerNo = getString(getColumnIndexOrThrow("container_no")).ifBlank { null },
        vesselName = getString(getColumnIndexOrThrow("vessel_name")).ifBlank { null },
        company = getString(getColumnIndexOrThrow("company")).ifBlank { null },
        customerName = getString(getColumnIndexOrThrow("customer_name")).ifBlank { null },
        location = getString(getColumnIndexOrThrow("location")).ifBlank { null },
        pkgs = getIntOrNull("pkgs"),
        outTurnQty = getIntOrNull("out_turn_qty"),
        submissionDate = getString(getColumnIndexOrThrow("submission_date")).ifBlank { null },
        customersStatus = getStringOrNull(getColumnIndexOrThrow("customers_status"))?.ifBlank { null },
        mpiStatus = getStringOrNull(getColumnIndexOrThrow("mpi_status"))?.ifBlank { null },
    )

    private fun Cursor.toPendingScannerUploadRecord(): PendingScannerUploadRecord = PendingScannerUploadRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        scannedBarcode = getString(getColumnIndexOrThrow("scanned_barcode")),
        databaseRecord = getString(getColumnIndexOrThrow("database_record")),
        matchStatus = ScannerMatchStatus.valueOf(getString(getColumnIndexOrThrow("match_status"))),
        status = getString(getColumnIndexOrThrow("status_text")),
        source = getString(getColumnIndexOrThrow("source")),
        scannedAt = getLong(getColumnIndexOrThrow("scanned_at")),
        cargoTrackingId = getLongOrNull(getColumnIndexOrThrow("cargo_tracking_id")),
        parentHblNo = getStringOrNull(getColumnIndexOrThrow("parent_hbl_no")),
        matchedChildHbl = getStringOrNull(getColumnIndexOrThrow("matched_child_hbl")),
        matchedBy = getStringOrNull(getColumnIndexOrThrow("matched_by")),
        childHbls = getStringOrNull(getColumnIndexOrThrow("child_hbls")),
        containerNo = getStringOrNull(getColumnIndexOrThrow("container_no")),
        vesselName = getStringOrNull(getColumnIndexOrThrow("vessel_name")),
        company = getStringOrNull(getColumnIndexOrThrow("company")),
        customerName = getStringOrNull(getColumnIndexOrThrow("customer_name")),
        location = getStringOrNull(getColumnIndexOrThrow("location")),
    )

    private fun Cursor.getIntOrNull(columnName: String): Int? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getInt(index)
    }

    private fun ensureColumn(
        db: SQLiteDatabase,
        tableName: String,
        columnName: String,
        definition: String,
    ) {
        if (!db.hasColumn(tableName, columnName)) {
            db.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
        }
    }

    private fun SQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean {
        rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        private const val DATABASE_NAME = "mixport_customs_pilot.db"
        private const val DATABASE_VERSION = 3
    }
}
