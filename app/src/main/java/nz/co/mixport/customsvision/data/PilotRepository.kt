package nz.co.mixport.customsvision.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class PilotRepository(
    private val appContext: Context,
    private val databaseHelper: CustomsDatabaseHelper,
    private val syncClient: CustomsSyncClient,
) {
    suspend fun createSession(draft: SessionDraft): InspectionSessionRecord = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val session = databaseHelper.createSession(draft, startedAt)
        databaseHelper.insertEvent(
            sessionId = session.id,
            palletId = null,
            eventType = "SESSION_STARTED",
            message = "Session started for container ${draft.containerCode}.",
            payloadJson = """{"containerCode":"${draft.containerCode}","operator":"${draft.operatorName}"}""",
            createdAt = startedAt,
        )
        session
    }

    suspend fun listSessions(): List<InspectionSessionRecord> = withContext(Dispatchers.IO) {
        databaseHelper.listSessions()
    }

    suspend fun getSession(sessionId: Long): InspectionSessionRecord? = withContext(Dispatchers.IO) {
        databaseHelper.getSession(sessionId)
    }

    suspend fun openPallet(sessionId: Long, sequenceNumber: Int, startedAt: Long): PalletRecord =
        withContext(Dispatchers.IO) {
            databaseHelper.createPallet(sessionId, sequenceNumber, startedAt)
        }

    suspend fun listPalletDetails(sessionId: Long): List<PalletDetail> = withContext(Dispatchers.IO) {
        databaseHelper.listPallets(sessionId).map { pallet ->
            PalletDetail(
                pallet = pallet,
                items = databaseHelper.getPalletItems(pallet.id),
            )
        }
    }

    suspend fun listPalletItems(palletId: Long): List<CargoSummaryRecord> = withContext(Dispatchers.IO) {
        databaseHelper.getPalletItems(palletId)
    }

    suspend fun incrementPalletItem(
        palletId: Long,
        itemLabel: String,
        colorName: String,
        markerText: String,
        delta: Int,
    ): CargoSummaryRecord = withContext(Dispatchers.IO) {
        databaseHelper.incrementPalletItem(palletId, itemLabel, colorName, markerText, delta)
    }

    suspend fun closePallet(
        palletId: Long,
        closedAt: Long,
        wrapDetected: Boolean,
        containerEmptyAtClose: Boolean,
    ): PalletRecord = withContext(Dispatchers.IO) {
        databaseHelper.closePallet(palletId, closedAt, wrapDetected, containerEmptyAtClose)
    }

    suspend fun insertEvent(
        sessionId: Long,
        palletId: Long?,
        eventType: String,
        message: String,
        payloadJson: String,
        createdAt: Long,
    ): EventLogRecord = withContext(Dispatchers.IO) {
        databaseHelper.insertEvent(sessionId, palletId, eventType, message, payloadJson, createdAt)
    }

    suspend fun listEvents(sessionId: Long, limit: Int = 20): List<EventLogRecord> =
        withContext(Dispatchers.IO) {
            databaseHelper.listEvents(sessionId, limit)
        }

    suspend fun lookupBarcode(
        barcode: String,
        settings: ScannerSyncSettings? = null,
    ): BarcodeLookupResult? = withContext(Dispatchers.IO) {
        val normalizedBarcode = normalizeScannerBarcode(barcode)
        val local = databaseHelper.lookupBarcode(normalizedBarcode)
        if (settings == null || !settings.hasPrivateProfile()) {
            return@withContext local
        }

        try {
            val remote = syncClient.verifyBarcode(settings, normalizedBarcode)
            if (!remote.found) {
                databaseHelper.deleteServerBarcodeReference(normalizedBarcode)
                return@withContext null
            }

            val liveLookup = remote.lookupResult ?: return@withContext null
            databaseHelper.storeServerBarcodeReferences(
                rows = listOf(liveLookup.toBootstrapRow(normalizedBarcode)),
                replaceExisting = false,
            )
            return@withContext liveLookup
        } catch (throwable: CustomsSyncException) {
            Log.w(TAG, "Remote barcode lookup failed. Falling back to local cache.", throwable)
            return@withContext local
        }
    }

    suspend fun recordScannerScan(
        record: ScannerRecord,
        lookupResult: BarcodeLookupResult?,
    ): Long = withContext(Dispatchers.IO) {
        val localId = databaseHelper.recordScannerScan(record, lookupResult)
        if (record.matchStatus == ScannerMatchStatus.MATCHED) {
            databaseHelper.reconcileScannerFailuresForMatchedBarcode(
                barcode = record.scannedBarcode,
                resolvedByLocalId = localId,
                resolvedCargoTrackingId = lookupResult?.cargoTrackingId,
                reconciledAt = record.scannedAt,
                reason = "resolved_by_successful_rescan",
            )
        }
        localId
    }

    suspend fun getScannerRecordDetail(record: ScannerRecord): ScannerRecordDetail = withContext(Dispatchers.IO) {
        val audit = databaseHelper.getScannerRecordAudit(
            localLogId = record.localLogId,
            scannedBarcode = record.scannedBarcode,
            scannedAt = record.scannedAt,
        )
        val lookupSnapshot = record.lookupSnapshot ?: databaseHelper.lookupBarcode(record.scannedBarcode)
        ScannerRecordDetail(
            record = record.copy(
                localLogId = record.localLogId ?: audit?.localLogId,
                lookupSnapshot = lookupSnapshot,
            ),
            lookupSnapshot = lookupSnapshot,
            audit = audit,
        )
    }

    suspend fun refreshScannerReferences(
        settings: ScannerSyncSettings,
        lastUploadAt: Long?,
        lastUploadBatchId: Long?,
        lastReferenceCursor: String?,
    ): ScannerReferenceRefreshResult = withContext(Dispatchers.IO) {
        var cursor = lastReferenceCursor?.trim().orEmpty()
        var replaceExisting = cursor.isBlank()
        var syncedAt = System.currentTimeMillis()
        var page = 0
        while (page < MAX_BOOTSTRAP_PAGES) {
            val payload = syncClient.fetchScannerBootstrap(
                settings = settings,
                sinceCursor = cursor.takeIf(String::isNotBlank),
                limit = CustomsSyncClient.DEFAULT_BOOTSTRAP_PAGE_SIZE,
            )
            syncedAt = payload.syncedAt
            if (replaceExisting || payload.rows.isNotEmpty()) {
                databaseHelper.storeServerBarcodeReferences(payload.rows, replaceExisting = replaceExisting)
                databaseHelper.reconcileScannerFailuresAgainstReferenceCache(
                    reconciledAt = syncedAt,
                    reason = "resolved_by_reference_refresh",
                )
            }
            replaceExisting = false

            val nextCursor = payload.cursor.trim().ifBlank { cursor }
            val reachedEnd = payload.rows.isEmpty() ||
                payload.rows.size < CustomsSyncClient.DEFAULT_BOOTSTRAP_PAGE_SIZE ||
                nextCursor == cursor
            cursor = nextCursor
            if (reachedEnd) {
                break
            }
            page += 1
        }
        ScannerReferenceRefreshResult(
            status = ScannerSyncStatus(
                referenceCount = databaseHelper.getScannerReferenceCount(),
                pendingUploadCount = databaseHelper.getPendingScannerUploadCount(),
                lastReferenceSyncAt = syncedAt,
                lastUploadAt = lastUploadAt,
                lastUploadBatchId = lastUploadBatchId,
            ),
            cursor = cursor.takeIf(String::isNotBlank),
        )
    }

    suspend fun getScannerSyncStatus(
        lastReferenceSyncAt: Long?,
        lastUploadAt: Long?,
        lastUploadBatchId: Long?,
    ): ScannerSyncStatus = withContext(Dispatchers.IO) {
        buildScannerSyncStatus(
            lastReferenceSyncAt = lastReferenceSyncAt,
            lastUploadAt = lastUploadAt,
            lastUploadBatchId = lastUploadBatchId,
        )
    }

    suspend fun uploadPendingScannerScans(
        settings: ScannerSyncSettings,
        operatorName: String,
        workflowMode: String,
        lastReferenceSyncAt: Long?,
    ): ScannerSyncStatus = withContext(Dispatchers.IO) {
        val pending = databaseHelper.listPendingScannerUploads()
        if (pending.isEmpty()) {
            return@withContext getScannerSyncStatus(lastReferenceSyncAt, null, null)
        }
        val response = syncClient.uploadScannerRecords(settings, operatorName, workflowMode, pending)
        databaseHelper.markScannerUploadsSynced(
            localIds = pending.map { it.id },
            batchId = response.batchId,
            uploadedAt = response.uploadedAt,
        )
        buildScannerSyncStatus(
            lastReferenceSyncAt = lastReferenceSyncAt,
            lastUploadAt = response.uploadedAt,
            lastUploadBatchId = response.batchId,
        )
    }

    suspend fun autoUploadPendingScannerScansIfOnline(
        settings: ScannerSyncSettings,
        operatorName: String,
        workflowMode: String,
        lastReferenceSyncAt: Long?,
        lastUploadAt: Long?,
        lastUploadBatchId: Long?,
    ): ScannerAutoUploadResult = withContext(Dispatchers.IO) {
        if (!settings.isValid()) {
            return@withContext ScannerAutoUploadResult(
                status = buildScannerSyncStatus(
                    lastReferenceSyncAt = lastReferenceSyncAt,
                    lastUploadAt = lastUploadAt,
                    lastUploadBatchId = lastUploadBatchId,
                ),
                state = ScannerAutoUploadState.CACHED_UNCONFIGURED,
                networkAvailable = null,
            )
        }

        val networkAvailable = isNetworkAvailable()
        if (!networkAvailable) {
            return@withContext ScannerAutoUploadResult(
                status = buildScannerSyncStatus(
                    lastReferenceSyncAt = lastReferenceSyncAt,
                    lastUploadAt = lastUploadAt,
                    lastUploadBatchId = lastUploadBatchId,
                ),
                state = ScannerAutoUploadState.CACHED_OFFLINE,
                networkAvailable = false,
            )
        }

        val pending = databaseHelper.listPendingScannerUploads()
        if (pending.isEmpty()) {
            return@withContext ScannerAutoUploadResult(
                status = buildScannerSyncStatus(
                    lastReferenceSyncAt = lastReferenceSyncAt,
                    lastUploadAt = lastUploadAt,
                    lastUploadBatchId = lastUploadBatchId,
                ),
                state = ScannerAutoUploadState.UPLOADED,
                networkAvailable = true,
            )
        }

        try {
            val response = syncClient.uploadScannerRecords(settings, operatorName, workflowMode, pending)
            databaseHelper.markScannerUploadsSynced(
                localIds = pending.map { it.id },
                batchId = response.batchId,
                uploadedAt = response.uploadedAt,
            )
            ScannerAutoUploadResult(
                status = buildScannerSyncStatus(
                    lastReferenceSyncAt = lastReferenceSyncAt,
                    lastUploadAt = response.uploadedAt,
                    lastUploadBatchId = response.batchId,
                ),
                state = ScannerAutoUploadState.UPLOADED,
                networkAvailable = true,
            )
        } catch (throwable: CustomsSyncException) {
            Log.w(TAG, "Auto-upload failed; keeping scanner records queued locally.", throwable)
            ScannerAutoUploadResult(
                status = buildScannerSyncStatus(
                    lastReferenceSyncAt = lastReferenceSyncAt,
                    lastUploadAt = lastUploadAt,
                    lastUploadBatchId = lastUploadBatchId,
                ),
                state = ScannerAutoUploadState.CACHED_UPLOAD_FAILED,
                networkAvailable = true,
                errorMessage = throwable.message,
            )
        }
    }

    suspend fun isScannerNetworkAvailable(): Boolean = withContext(Dispatchers.IO) {
        isNetworkAvailable()
    }

    suspend fun updateContainerFlag(sessionId: Long, hasRemainingCargo: Boolean) =
        withContext(Dispatchers.IO) {
            val session = databaseHelper.getSession(sessionId) ?: return@withContext
            databaseHelper.updateSessionStatus(
                sessionId = session.id,
                status = session.status,
                recordingUri = session.recordingUri,
                containerHasRemainingCargo = hasRemainingCargo,
            )
        }

    suspend fun markReadyToComplete(sessionId: Long) = withContext(Dispatchers.IO) {
        val session = databaseHelper.getSession(sessionId) ?: return@withContext
        databaseHelper.updateSessionStatus(
            sessionId = session.id,
            status = "READY_TO_COMPLETE",
            recordingUri = session.recordingUri,
            containerHasRemainingCargo = false,
        )
    }

    suspend fun updateRecordingUri(sessionId: Long, recordingUri: String) = withContext(Dispatchers.IO) {
        val session = databaseHelper.getSession(sessionId) ?: return@withContext
        databaseHelper.updateSessionStatus(
            sessionId = session.id,
            status = session.status,
            recordingUri = recordingUri,
            containerHasRemainingCargo = session.containerHasRemainingCargo,
        )
    }

    suspend fun finishSession(sessionId: Long, isComplete: Boolean) = withContext(Dispatchers.IO) {
        val status = if (isComplete) "COMPLETED" else "PAUSED"
        val endedAt = System.currentTimeMillis()
        val session = databaseHelper.getSession(sessionId) ?: return@withContext
        databaseHelper.updateSessionStatus(
            sessionId = session.id,
            status = status,
            endedAt = endedAt,
            recordingUri = session.recordingUri,
            containerHasRemainingCargo = session.containerHasRemainingCargo,
        )
        databaseHelper.insertEvent(
            sessionId = session.id,
            palletId = null,
            eventType = "SESSION_FINISHED",
            message = "Session closed with status $status.",
            payloadJson = """{"status":"$status"}""",
            createdAt = endedAt,
        )
    }

    private fun BarcodeLookupResult.toBootstrapRow(barcode: String): ScannerBootstrapRow {
        return ScannerBootstrapRow(
            barcodeKey = normalizeScannerBarcode(barcode),
            cargoTrackingId = cargoTrackingId ?: 0L,
            parentHblNo = parentHblNo ?: databaseRecord,
            matchedChildHbl = matchedChildHbl.orEmpty(),
            matchedBarcodeCode = matchedBarcodeCode.orEmpty(),
            matchedBy = matchedBy ?: "hbl_no",
            childHbls = childHbls.orEmpty(),
            barcodeCodes = barcodeCodes.orEmpty(),
            status = status,
            customersStatus = customersStatus.orEmpty(),
            mpiStatus = mpiStatus.orEmpty(),
            location = location.orEmpty(),
            pkgs = pkgs,
            outTurnQty = outTurnQty,
            submissionDate = submissionDate.orEmpty(),
            containerNo = containerNo.orEmpty(),
            vesselName = vesselName.orEmpty(),
            company = company.orEmpty(),
            customerName = customerName.orEmpty(),
            updatedCursor = Instant.now().toString(),
            serverScanCount = serverScanCount,
            serverMatchedScanCount = serverMatchedScanCount,
            serverMismatchScanCount = serverMismatchScanCount,
            serverErrorScanCount = serverErrorScanCount,
            serverLastScannedAt = serverLastScannedAt.orEmpty(),
            serverLastMatchStatus = serverLastMatchStatus.orEmpty(),
            scannerTargetMode = scannerTargetMode ?: "events",
            scannerExpectedScanCount = scannerExpectedScanCount ?: 0,
            scannerCompletedScanCount = scannerCompletedScanCount ?: 0,
            scannerRemainingScanCount = scannerRemainingScanCount ?: 0,
            scannerRepeatMatchCount = scannerRepeatMatchCount ?: 0,
            scannerIsComplete = scannerIsComplete ?: false,
        )
    }

    private fun buildScannerSyncStatus(
        lastReferenceSyncAt: Long?,
        lastUploadAt: Long?,
        lastUploadBatchId: Long?,
    ): ScannerSyncStatus {
        return ScannerSyncStatus(
            referenceCount = databaseHelper.getScannerReferenceCount(),
            pendingUploadCount = databaseHelper.getPendingScannerUploadCount(),
            lastReferenceSyncAt = lastReferenceSyncAt,
            lastUploadAt = lastUploadAt,
            lastUploadBatchId = lastUploadBatchId,
        )
    }

    private fun ScannerSyncSettings.isValid(): Boolean {
        return isConfigured()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val TAG = "PilotRepository"
        private const val MAX_BOOTSTRAP_PAGES = 8
    }
}
