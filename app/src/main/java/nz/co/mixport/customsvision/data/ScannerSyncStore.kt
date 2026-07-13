package nz.co.mixport.customsvision.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.contentValuesOf
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.core.database.sqlite.transaction

class ScannerSyncStore(
    private val readableDatabaseProvider: () -> SQLiteDatabase,
    private val writableDatabaseProvider: () -> SQLiteDatabase,
) {
    fun createTables(db: SQLiteDatabase) {
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
                barcode_key TEXT NOT NULL DEFAULT '',
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
                disposition_state TEXT NOT NULL DEFAULT 'ACTIVE',
                reconciled_at INTEGER,
                reconciled_by_local_id INTEGER,
                reconciliation_reason TEXT,
                resolved_cargo_tracking_id INTEGER,
                uploaded_batch_id INTEGER,
                uploaded_at INTEGER
            )
            """.trimIndent(),
        )
    }

    fun ensureSchema(db: SQLiteDatabase) {
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
        ensureColumn(db, "scanner_scan_log", "barcode_key", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "scanner_scan_log", "disposition_state", "TEXT NOT NULL DEFAULT 'ACTIVE'")
        ensureColumn(db, "scanner_scan_log", "reconciled_at", "INTEGER")
        ensureColumn(db, "scanner_scan_log", "reconciled_by_local_id", "INTEGER")
        ensureColumn(db, "scanner_scan_log", "reconciliation_reason", "TEXT")
        ensureColumn(db, "scanner_scan_log", "resolved_cargo_tracking_id", "INTEGER")
        backfillScannerScanLogBarcodeKeys(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scanner_scan_log_barcode_key ON scanner_scan_log(barcode_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scanner_scan_log_sync_state ON scanner_scan_log(sync_state)")
    }

    fun lookupServerBarcode(barcode: String): BarcodeLookupResult? {
        val normalized = normalizeScannerBarcode(barcode)
        if (normalized.isBlank()) {
            return null
        }
        return readableDatabaseProvider().query(
            "server_barcode_reference",
            null,
            "barcode_key = ?",
            arrayOf(normalized),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.toServerBarcodeLookupResult()
            } else {
                null
            }
        }
    }

    fun storeServerBarcodeReferences(
        rows: List<ScannerBootstrapRow>,
        replaceExisting: Boolean = true,
    ) {
        val db = writableDatabaseProvider()
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
        writableDatabaseProvider().delete(
            "server_barcode_reference",
            "barcode_key = ?",
            arrayOf(normalized),
        )
    }

    fun clearServerBarcodeReferences(): Int {
        return writableDatabaseProvider().delete("server_barcode_reference", null, null)
    }

    fun recordScannerScan(record: ScannerRecord, lookupResult: BarcodeLookupResult?): Long {
        val normalizedBarcode = normalizeScannerBarcode(record.scannedBarcode)
        return writableDatabaseProvider().insertOrThrow(
            "scanner_scan_log",
            null,
            ContentValues().apply {
                put("scanned_barcode", record.scannedBarcode)
                put("barcode_key", normalizedBarcode)
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
                put("disposition_state", "ACTIVE")
                put("resolved_cargo_tracking_id", lookupResult?.cargoTrackingId)
            },
        )
    }

    fun reconcileScannerFailuresForMatchedBarcode(
        barcode: String,
        resolvedByLocalId: Long,
        resolvedCargoTrackingId: Long?,
        reconciledAt: Long,
        reason: String,
    ): Int {
        val normalizedBarcode = normalizeScannerBarcode(barcode)
        if (normalizedBarcode.isBlank()) {
            return 0
        }
        return writableDatabaseProvider().update(
            "scanner_scan_log",
            ContentValues().apply {
                put("disposition_state", "AUDIT_ONLY")
                put("reconciled_at", reconciledAt)
                put("reconciled_by_local_id", resolvedByLocalId)
                put("reconciliation_reason", reason)
                put("resolved_cargo_tracking_id", resolvedCargoTrackingId)
            },
            """
            id <> ? AND sync_state = ? AND disposition_state = ? AND
            match_status IN (?, ?) AND barcode_key = ?
            """.trimIndent(),
            arrayOf(
                resolvedByLocalId.toString(),
                "PENDING",
                "ACTIVE",
                ScannerMatchStatus.MISMATCH.name,
                ScannerMatchStatus.ERROR.name,
                normalizedBarcode,
            ),
        )
    }

    fun reconcileScannerFailuresAgainstReferenceCache(
        reconciledAt: Long,
        reason: String,
    ): Int {
        val statement = writableDatabaseProvider().compileStatement(
            """
            UPDATE scanner_scan_log
            SET disposition_state = 'AUDIT_ONLY',
                reconciled_at = ?,
                reconciled_by_local_id = NULL,
                reconciliation_reason = ?,
                resolved_cargo_tracking_id = COALESCE(
                    (
                        SELECT cargo_tracking_id
                        FROM server_barcode_reference
                        WHERE barcode_key = scanner_scan_log.barcode_key
                        LIMIT 1
                    ),
                    resolved_cargo_tracking_id
                )
            WHERE sync_state = 'PENDING'
              AND disposition_state = 'ACTIVE'
              AND match_status IN ('MISMATCH', 'ERROR')
              AND EXISTS (
                    SELECT 1
                    FROM server_barcode_reference
                    WHERE barcode_key = scanner_scan_log.barcode_key
                )
            """.trimIndent(),
        )
        statement.bindLong(1, reconciledAt)
        statement.bindString(2, reason)
        val updated = statement.executeUpdateDelete()
        statement.close()
        return updated
    }

    fun listPendingScannerUploads(limit: Int = 500): List<PendingScannerUploadRecord> {
        return readableDatabaseProvider().query(
            "scanner_scan_log",
            null,
            "sync_state = ?",
            arrayOf("PENDING"),
            null,
            null,
            "scanned_at ASC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toPendingScannerUploadRecord())
                }
            }
        }
    }

    fun supersedePendingScannerUploads(
        reason: String,
        supersededAt: Long,
    ): Int {
        return writableDatabaseProvider().update(
            "scanner_scan_log",
            ContentValues().apply {
                put("sync_state", "SUPERSEDED")
                put("disposition_state", "AUDIT_ONLY")
                put("reconciled_at", supersededAt)
                putNull("reconciled_by_local_id")
                put("reconciliation_reason", reason)
            },
            "sync_state = ?",
            arrayOf("PENDING"),
        )
    }

    fun markScannerUploadsSynced(localIds: List<Long>, batchId: Long, uploadedAt: Long) {
        if (localIds.isEmpty()) {
            return
        }
        val placeholders = localIds.joinToString(",") { "?" }
        val statement = writableDatabaseProvider().compileStatement(
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
        return readableDatabaseProvider().rawQuery("SELECT COUNT(*) FROM server_barcode_reference", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getPendingScannerUploadCount(): Int {
        return readableDatabaseProvider().rawQuery(
            "SELECT COUNT(*) FROM scanner_scan_log WHERE sync_state = 'PENDING'",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun Cursor.toServerBarcodeLookupResult(): BarcodeLookupResult = BarcodeLookupResult(
        found = true,
        databaseRecord = getString(getColumnIndexOrThrow("parent_hbl_no")),
        status = getString(getColumnIndexOrThrow("status")),
        source = "SERVER_CACHE",
        cargoTrackingId = getLong(getColumnIndexOrThrow("cargo_tracking_id")),
        parentHblNo = getString(getColumnIndexOrThrow("parent_hbl_no")),
        matchedChildHbl = getString(getColumnIndexOrThrow("matched_child_hbl")).ifBlank { null },
        matchedBarcodeCode = getString(getColumnIndexOrThrow("matched_barcode_code")).ifBlank { null },
        matchedBy = getString(getColumnIndexOrThrow("matched_by")),
        childHbls = getString(getColumnIndexOrThrow("child_hbls")),
        barcodeCodes = getString(getColumnIndexOrThrow("barcode_codes")),
        containerNo = getString(getColumnIndexOrThrow("container_no")).ifBlank { null },
        vesselName = getString(getColumnIndexOrThrow("vessel_name")).ifBlank { null },
        company = getString(getColumnIndexOrThrow("company")).ifBlank { null },
        customerName = getString(getColumnIndexOrThrow("customer_name")).ifBlank { null },
        location = getString(getColumnIndexOrThrow("location")).ifBlank { null },
        pkgs = getIntOrNull("pkgs"),
        outTurnQty = getIntOrNull("out_turn_qty"),
        submissionDate = getString(getColumnIndexOrThrow("submission_date")).ifBlank { null },
        customersStatus = getString(getColumnIndexOrThrow("customers_status")).ifBlank { null },
        mpiStatus = getString(getColumnIndexOrThrow("mpi_status")).ifBlank { null },
    )

    private fun Cursor.toPendingScannerUploadRecord(): PendingScannerUploadRecord = PendingScannerUploadRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        scannedBarcode = getString(getColumnIndexOrThrow("scanned_barcode")),
        barcodeKey = getString(getColumnIndexOrThrow("barcode_key")),
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
        dispositionState = getString(getColumnIndexOrThrow("disposition_state")),
        reconciledAt = getLongOrNull(getColumnIndexOrThrow("reconciled_at")),
        reconciledByLocalId = getLongOrNull(getColumnIndexOrThrow("reconciled_by_local_id")),
        reconciliationReason = getStringOrNull(getColumnIndexOrThrow("reconciliation_reason")),
        resolvedCargoTrackingId = getLongOrNull(getColumnIndexOrThrow("resolved_cargo_tracking_id")),
    )

    private fun backfillScannerScanLogBarcodeKeys(db: SQLiteDatabase) {
        val cursor = db.query(
            "scanner_scan_log",
            arrayOf("id", "scanned_barcode"),
            "barcode_key IS NULL OR TRIM(barcode_key) = ''",
            null,
            null,
            null,
            null,
        )
        cursor.use {
            if (!it.moveToFirst()) {
                return
            }
            val updateStatement = db.compileStatement(
                "UPDATE scanner_scan_log SET barcode_key = ? WHERE id = ?",
            )
            do {
                val id = it.getLong(it.getColumnIndexOrThrow("id"))
                val scannedBarcode = it.getString(it.getColumnIndexOrThrow("scanned_barcode"))
                updateStatement.bindString(1, normalizeScannerBarcode(scannedBarcode))
                updateStatement.bindLong(2, id)
                updateStatement.executeUpdateDelete()
                updateStatement.clearBindings()
            } while (it.moveToNext())
            updateStatement.close()
        }
    }

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
}
