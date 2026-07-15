package nz.co.mixport.customsvision.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.AppPreferencesRepository
import nz.co.mixport.customsvision.data.BarcodeLookupResult
import nz.co.mixport.customsvision.data.PilotRepository
import nz.co.mixport.customsvision.data.ScannerAutoUploadState
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord
import nz.co.mixport.customsvision.data.ScannerSyncSettings
import nz.co.mixport.customsvision.data.ScannerSyncStatus
import nz.co.mixport.customsvision.data.isUsableScannerBarcode
import nz.co.mixport.customsvision.data.normalizeScannerBarcode

internal class ScannerWorkflowController(
    private val repository: PilotRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val state: MutableStateFlow<LiveInspectionUiState>,
    private val scope: CoroutineScope,
    private val activeOperatorName: () -> String,
) {
    private var scannerAutoUploadJob: Job? = null
    private var lastObservedNetworkAvailable: Boolean? = null

    fun clearScannerHistory() {
        preferencesRepository.setScannerHistory(emptyList())
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    history = emptyList(),
                    lastResult = ScannerMatchStatus.WAITING,
                    statusMessage = null,
                    lastProcessedBarcode = null,
                    lastLookupResult = null,
                    selectedHistoryRecord = null,
                    selectedHistoryDetail = null,
                    isHistoryDetailLoading = false,
                    historyDetailError = null,
                ),
            )
        }
    }

    fun prepareScannerForNextScan() {
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    barcodeInput = "",
                    lastResult = ScannerMatchStatus.WAITING,
                    statusMessage = waitingScannerMessage(it.appLanguage),
                    lastProcessedBarcode = null,
                    lastLookupResult = null,
                ),
            )
        }
    }

    fun showScannerHistoryDetail(record: ScannerRecord) {
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    selectedHistoryRecord = record,
                    selectedHistoryDetail = null,
                    isHistoryDetailLoading = true,
                    historyDetailError = null,
                ),
            )
        }
        scope.launch {
            runCatching {
                repository.getScannerRecordDetail(record)
            }.onSuccess { detail ->
                state.update {
                    it.copy(
                        scanner = it.scanner.copy(
                            selectedHistoryRecord = detail.record,
                            selectedHistoryDetail = detail,
                            isHistoryDetailLoading = false,
                            historyDetailError = null,
                        ),
                    )
                }
            }.onFailure { throwable ->
                state.update {
                    it.copy(
                        scanner = it.scanner.copy(
                            selectedHistoryRecord = record,
                            selectedHistoryDetail = null,
                            isHistoryDetailLoading = false,
                            historyDetailError = throwable.message ?: currentLanguage().pick(
                                "Unable to load the full scanner detail.",
                                "无法加载完整扫码详情。",
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun dismissScannerHistoryDetail() {
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    selectedHistoryRecord = null,
                    selectedHistoryDetail = null,
                    isHistoryDetailLoading = false,
                    historyDetailError = null,
                ),
            )
        }
    }

    fun refreshScannerReferences(
        manual: Boolean = true,
        force: Boolean = false,
    ) {
        val language = currentLanguage()
        val settings = refreshProvisioningState()
        if (!settings.isConfigured()) {
            state.update {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = it.scanner.sync.copy(
                            statusMessage = privateSyncMissingMessage(language),
                        ),
                    ),
                )
            }
            return
        }
        if (!shouldRefreshScannerReferenceCache(
                isConfigured = settings.isConfigured(),
                isRefreshing = state.value.scanner.sync.isRefreshing,
                isUploading = state.value.scanner.sync.isUploading,
                referenceCount = state.value.scanner.sync.referenceCount,
                lastReferenceSyncAt = state.value.scanner.sync.lastReferenceSyncAt,
                now = System.currentTimeMillis(),
                force = force,
            )
        ) {
            return
        }
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(
                        isRefreshing = true,
                        statusMessage = language.pick(
                            if (manual) "Updating the latest cargo list..." else "Refreshing the live cargo list...",
                            if (manual) "正在下载最新 HBL 缓存..." else "正在刷新在线扫码缓存...",
                        ),
                    ),
                ),
            )
        }
        scope.launch {
            runCatching {
                repository.refreshScannerReferences(
                    settings = settings,
                    lastUploadAt = preferencesRepository.getScannerLastUploadAt(),
                    lastUploadBatchId = preferencesRepository.getScannerLastUploadBatchId(),
                    lastReferenceCursor = preferencesRepository.getScannerLastReferenceCursor(),
                )
            }.onSuccess { result ->
                preferencesRepository.setScannerLastReferenceSyncAt(result.status.lastReferenceSyncAt)
                preferencesRepository.setScannerLastReferenceCursor(result.cursor)
                applyDeletedScannerHistory(result.deletedBarcodeKeys)
                refreshLatestVisibleScannerResult(settings)
                applyScannerSyncStatus(
                    status = result.status,
                    statusMessage = scannerRefreshSuccessMessage(
                        referenceCount = result.status.referenceCount,
                        deletedCount = result.deletedBarcodeKeys.size,
                        language = currentLanguage(),
                    ),
                    isRefreshing = false,
                    settings = settings,
                )
            }.onFailure { throwable ->
                state.update {
                    it.copy(
                        scanner = it.scanner.copy(
                            sync = it.scanner.sync.copy(
                                isRefreshing = false,
                                statusMessage = throwable.message ?: currentLanguage().pick(
                                    "Unable to refresh scanner cache.",
                                    "无法刷新扫码缓存。",
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun uploadPendingScannerScans() {
        val language = currentLanguage()
        val settings = refreshProvisioningState()
        if (state.value.scanner.sync.pendingUploadCount <= 0) {
            state.update {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = it.scanner.sync.copy(
                            statusMessage = language.pick(
                                "There are no pending scanner results to upload.",
                                "当前没有待上传的扫码结果。",
                            ),
                        ),
                    ),
                )
            }
            return
        }
        if (!settings.isConfigured()) {
            state.update {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = it.scanner.sync.copy(
                            statusMessage = privateSyncMissingMessage(language),
                        ),
                    ),
                )
            }
            return
        }
        if (state.value.scanner.sync.isUploading) {
            return
        }
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(
                        isUploading = true,
                        statusMessage = language.pick(
                            "Uploading saved scan results...",
                            "正在上传待处理扫码结果...",
                        ),
                    ),
                ),
            )
        }
        scope.launch {
            runCatching {
                repository.uploadPendingScannerScans(
                    settings = settings,
                    operatorName = activeOperatorName(),
                    workflowMode = state.value.scanner.workflowMode.name,
                    lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt(),
                )
            }.onSuccess { syncStatus ->
                preferencesRepository.setScannerLastUploadAt(syncStatus.lastUploadAt)
                preferencesRepository.setScannerLastUploadBatchId(syncStatus.lastUploadBatchId)
                refreshLatestVisibleScannerResult(settings)
                applyScannerSyncStatus(
                    status = syncStatus,
                    statusMessage = currentLanguage().pick(
                        "Saved scan results uploaded successfully.",
                        "扫码结果已上传，批次 #${syncStatus.lastUploadBatchId ?: 0} 已写入服务器。",
                    ),
                    isUploading = false,
                    networkAvailable = true,
                    settings = settings,
                )
            }.onFailure { throwable ->
                state.update {
                    it.copy(
                        scanner = it.scanner.copy(
                            sync = it.scanner.sync.copy(
                                isUploading = false,
                                statusMessage = throwable.message ?: currentLanguage().pick(
                                    "Unable to upload scanner results.",
                                    "无法上传扫码结果。",
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun refreshScannerConnectionState() {
        val settings = refreshProvisioningState()
        if (!settings.isConfigured()) {
            lastObservedNetworkAvailable = null
            state.update {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = it.scanner.sync.copy(networkAvailable = null),
                    ),
                )
            }
            return
        }
        scope.launch {
            val networkAvailable = repository.isScannerNetworkAvailable()
            val previousNetworkAvailable = lastObservedNetworkAvailable
            lastObservedNetworkAvailable = networkAvailable
            state.update {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = it.scanner.sync.copy(networkAvailable = networkAvailable),
                    ),
                )
            }
            if (networkAvailable && previousNetworkAvailable == false) {
                refreshScannerReferences(manual = false, force = true)
                maybeAutoUploadPendingScannerScans()
            }
        }
    }

    fun maybeAutoUploadPendingScannerScans() {
        val settings = refreshProvisioningState()
        if (!settings.isConfigured()) {
            refreshScannerConnectionState()
            return
        }
        if (scannerAutoUploadJob?.isActive == true) {
            return
        }
        if (state.value.scanner.sync.pendingUploadCount <= 0) {
            refreshScannerConnectionState()
            return
        }

        scannerAutoUploadJob = scope.launch {
            var keepUploading = true
            while (keepUploading) {
                state.update {
                    it.copy(
                        scanner = it.scanner.copy(
                            sync = it.scanner.sync.copy(isUploading = true),
                        ),
                    )
                }
                val result = repository.autoUploadPendingScannerScansIfOnline(
                    settings = settings,
                    operatorName = activeOperatorName(),
                    workflowMode = state.value.scanner.workflowMode.name,
                    lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt(),
                    lastUploadAt = preferencesRepository.getScannerLastUploadAt(),
                    lastUploadBatchId = preferencesRepository.getScannerLastUploadBatchId(),
                )
                if (result.state == ScannerAutoUploadState.UPLOADED) {
                    preferencesRepository.setScannerLastUploadAt(result.status.lastUploadAt)
                    preferencesRepository.setScannerLastUploadBatchId(result.status.lastUploadBatchId)
                    refreshLatestVisibleScannerResult(settings)
                }
                applyScannerSyncStatus(
                    status = result.status,
                    statusMessage = scannerAutoUploadMessage(result, currentLanguage()),
                    isUploading = result.state == ScannerAutoUploadState.UPLOADED &&
                        result.status.pendingUploadCount > 0,
                    networkAvailable = result.networkAvailable,
                    settings = settings,
                )
                keepUploading = result.state == ScannerAutoUploadState.UPLOADED &&
                    result.status.pendingUploadCount > 0
            }
        }
    }

    fun verifyScannerBarcode(barcode: String = state.value.scanner.barcodeInput) {
        val normalized = normalizeScannerBarcode(barcode)
        if (normalized.isBlank()) {
            state.update {
                it.copy(
                    scanner = it.scanner.copy(
                        lastResult = ScannerMatchStatus.ERROR,
                        statusMessage = it.appLanguage.pick(
                            "Please scan or enter a barcode first.",
                            "请先扫描或输入序列号。",
                        ),
                        lastLookupResult = null,
                        feedbackNonce = it.scanner.feedbackNonce + 1,
                    ),
                )
            }
            return
        }
        if (state.value.scanner.isProcessing) {
            return
        }

        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    isProcessing = true,
                    statusMessage = it.appLanguage.pick(
                        "Verifying barcode...",
                        "正在比对序列号...",
                    ),
                    selectedHistoryRecord = null,
                    selectedHistoryDetail = null,
                    isHistoryDetailLoading = false,
                    historyDetailError = null,
                ),
            )
        }

        scope.launch {
            val verifiedAt = System.currentTimeMillis()
            val syncSettings = currentScannerSyncSettings()
            val lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt()
            val lastUploadAt = preferencesRepository.getScannerLastUploadAt()
            val lastUploadBatchId = preferencesRepository.getScannerLastUploadBatchId()
            val lookupAttempt = runCatching { lookupBarcode(normalized) }
            val lookupResult = lookupAttempt.getOrNull()
            val record = lookupAttempt.fold(
                onSuccess = {
                    buildScannerRecord(
                        barcode = normalized,
                        lookupResult = it,
                        scannedAt = verifiedAt,
                    )
                },
                onFailure = { throwable ->
                    ScannerRecord(
                        scannedBarcode = normalized,
                        databaseRecord = SCANNER_RECORD_ERROR,
                        matchStatus = ScannerMatchStatus.ERROR,
                        status = throwable.message?.takeIf(String::isNotBlank) ?: SCANNER_STATUS_ERROR,
                        source = SCANNER_SOURCE_LOCAL,
                        scannedAt = verifiedAt,
                    )
                },
            )
            val localLogId = repository.recordScannerScan(record, lookupResult)
            val persistedRecord = record.copy(localLogId = localLogId)
            val updatedHistory = listOf(persistedRecord) + state.value.scanner.history
                .filterNot {
                    it.scannedBarcode == persistedRecord.scannedBarcode &&
                        it.scannedAt == persistedRecord.scannedAt
                }
                .take(MAX_SCANNER_HISTORY_ITEMS - 1)
            preferencesRepository.setScannerHistory(updatedHistory)
            val syncStatus = repository.getScannerSyncStatus(
                lastReferenceSyncAt = lastReferenceSyncAt,
                lastUploadAt = lastUploadAt,
                lastUploadBatchId = lastUploadBatchId,
            )
            val networkAvailable = if (syncSettings.isConfigured()) {
                repository.isScannerNetworkAvailable()
            } else {
                null
            }

            state.update {
                it.copy(
                    scanner = it.scanner.copy(
                        barcodeInput = "",
                        isProcessing = false,
                        lastResult = persistedRecord.matchStatus,
                        statusMessage = scannerMessageFor(persistedRecord, it.appLanguage),
                        history = updatedHistory,
                        lastProcessedBarcode = persistedRecord.scannedBarcode,
                        lastLookupResult = lookupResult,
                        feedbackNonce = it.scanner.feedbackNonce + 1,
                        sync = it.scanner.sync.copy(
                            isProvisioned = syncSettings.hasPrivateProfile(),
                            deviceId = syncSettings.deviceId,
                            networkAvailable = networkAvailable,
                            referenceCount = syncStatus.referenceCount,
                            pendingUploadCount = syncStatus.pendingUploadCount,
                            lastReferenceSyncAt = syncStatus.lastReferenceSyncAt,
                            lastUploadAt = syncStatus.lastUploadAt,
                            lastUploadBatchId = syncStatus.lastUploadBatchId,
                        ),
                    ),
                )
            }
            maybeAutoUploadPendingScannerScans()
        }
    }

    fun onScannerPdaDetected(
        barcode: String,
        codeType: String,
    ) {
        val normalized = normalizeScannerBarcode(barcode)
        if (normalized.isBlank() || state.value.scanner.isProcessing) {
            return
        }

        state.update {
            val typeSuffix = codeType
                .trim()
                .takeIf(String::isNotBlank)
                ?.let { type -> " ($type)" }
                .orEmpty()
            it.copy(
                scanner = it.scanner.copy(
                    statusMessage = it.appLanguage.pick(
                        "PDA scanner detected ${normalized}${typeSuffix}. Verifying...",
                        "PDA 已读到 ${normalized}${typeSuffix}，正在比对数据库...",
                    ),
                ),
            )
        }
        verifyScannerBarcode(normalized)
    }

    fun refreshScannerSyncStatus() {
        scope.launch {
            val settings = refreshProvisioningState()
            val syncStatus = repository.getScannerSyncStatus(
                lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt(),
                lastUploadAt = preferencesRepository.getScannerLastUploadAt(),
                lastUploadBatchId = preferencesRepository.getScannerLastUploadBatchId(),
            )
            val networkAvailable = if (settings.isConfigured()) {
                repository.isScannerNetworkAvailable()
            } else {
                null
            }
            applyScannerSyncStatus(
                status = syncStatus,
                statusMessage = state.value.scanner.sync.statusMessage,
                networkAvailable = networkAvailable,
                settings = settings,
            )
        }
    }

    fun autoRefreshScannerReferences() {
        autoRefreshScannerReferences(force = false)
    }

    fun autoRefreshScannerReferences(force: Boolean) {
        val settings = refreshProvisioningState()
        val sync = state.value.scanner.sync
        if (!shouldRefreshScannerReferenceCache(
                isConfigured = settings.isConfigured(),
                isRefreshing = sync.isRefreshing,
                isUploading = sync.isUploading,
                referenceCount = sync.referenceCount,
                lastReferenceSyncAt = sync.lastReferenceSyncAt,
                now = System.currentTimeMillis(),
                force = force,
            )
        ) {
            return
        }
        refreshScannerReferences(manual = false, force = force)
    }

    private suspend fun lookupBarcode(barcode: String): BarcodeLookupResult? {
        if (!isUsableScannerBarcode(barcode)) {
            return null
        }
        return repository.lookupBarcode(barcode, currentScannerSyncSettings())
    }

    private fun applyScannerSyncStatus(
        status: ScannerSyncStatus,
        statusMessage: String?,
        isRefreshing: Boolean = state.value.scanner.sync.isRefreshing,
        isUploading: Boolean = state.value.scanner.sync.isUploading,
        networkAvailable: Boolean? = state.value.scanner.sync.networkAvailable,
        settings: ScannerSyncSettings = currentScannerSyncSettings(),
    ) {
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(
                        isProvisioned = settings.hasPrivateProfile(),
                        deviceId = settings.deviceId,
                        networkAvailable = networkAvailable,
                        referenceCount = status.referenceCount,
                        pendingUploadCount = status.pendingUploadCount,
                        lastReferenceSyncAt = status.lastReferenceSyncAt,
                        lastUploadAt = status.lastUploadAt,
                        lastUploadBatchId = status.lastUploadBatchId,
                        isRefreshing = isRefreshing,
                        isUploading = isUploading,
                        statusMessage = statusMessage,
                    ),
                ),
            )
        }
    }

    private fun refreshProvisioningState(): ScannerSyncSettings {
        val settings = currentScannerSyncSettings()
        state.update {
            val sync = it.scanner.sync
            if (
                sync.isProvisioned == settings.hasPrivateProfile() &&
                sync.deviceId == settings.deviceId
            ) {
                it
            } else {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = sync.copy(
                            isProvisioned = settings.hasPrivateProfile(),
                            deviceId = settings.deviceId,
                        ),
                    ),
                )
            }
        }
        return settings
    }

    private fun currentScannerSyncSettings(): ScannerSyncSettings {
        return preferencesRepository.getScannerSyncSettings()
    }

    private suspend fun refreshLatestVisibleScannerResult(settings: ScannerSyncSettings) {
        val scannerSnapshot = state.value.scanner
        val latestRecord = scannerSnapshot.history.firstOrNull() ?: return
        val latestBarcode = scannerSnapshot.lastProcessedBarcode
            ?.takeIf(String::isNotBlank)
            ?: latestRecord.scannedBarcode.takeIf(String::isNotBlank)
            ?: return

        val lookupAttempt = runCatching {
            repository.lookupBarcode(latestBarcode, settings)
        }
        if (lookupAttempt.isFailure) {
            return
        }
        val refreshedLookup = lookupAttempt.getOrNull()

        val refreshedRecord = buildScannerRecord(
            barcode = latestBarcode,
            lookupResult = refreshedLookup,
            scannedAt = latestRecord.scannedAt,
        ).copy(localLogId = latestRecord.localLogId)
        val updatedHistory = listOf(refreshedRecord) + scannerSnapshot.history.drop(1)
        preferencesRepository.setScannerHistory(updatedHistory)

        state.update {
            val isWaitingState = !it.scanner.isProcessing && it.scanner.lastResult == ScannerMatchStatus.WAITING
            val refreshedSelectedRecord = it.scanner.selectedHistoryRecord?.takeIf { selected ->
                selected.localLogId == refreshedRecord.localLogId ||
                    (selected.scannedBarcode == refreshedRecord.scannedBarcode &&
                        selected.scannedAt == refreshedRecord.scannedAt)
            }?.let { refreshedRecord }
            val refreshedSelectedDetail = it.scanner.selectedHistoryDetail?.takeIf { detail ->
                detail.record.localLogId == refreshedRecord.localLogId ||
                    (detail.record.scannedBarcode == refreshedRecord.scannedBarcode &&
                        detail.record.scannedAt == refreshedRecord.scannedAt)
            }?.copy(
                record = refreshedRecord,
                lookupSnapshot = refreshedLookup,
            )
            it.copy(
                scanner = it.scanner.copy(
                    history = updatedHistory,
                    lastResult = if (isWaitingState) it.scanner.lastResult else refreshedRecord.matchStatus,
                    statusMessage = if (isWaitingState) {
                        it.scanner.statusMessage
                    } else {
                        scannerMessageFor(refreshedRecord, it.appLanguage)
                    },
                    lastLookupResult = refreshedLookup,
                    lastProcessedBarcode = refreshedRecord.scannedBarcode,
                    selectedHistoryRecord = refreshedSelectedRecord ?: it.scanner.selectedHistoryRecord,
                    selectedHistoryDetail = refreshedSelectedDetail ?: it.scanner.selectedHistoryDetail,
                ),
            )
        }
    }

    private fun applyDeletedScannerHistory(barcodeKeys: Collection<String>) {
        val normalizedKeys = barcodeKeys
            .asSequence()
            .map(::normalizeScannerBarcode)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedKeys.isEmpty()) {
            return
        }

        val updatedHistory = preferencesRepository.pruneScannerHistoryByBarcodeKeys(normalizedKeys)
        state.update { currentState ->
            val scannerState = currentState.scanner
            val lastProcessedRemoved = scannerState.lastProcessedBarcode
                ?.let(::normalizeScannerBarcode)
                ?.let(normalizedKeys::contains)
                ?: false
            val selectedRecordRemoved = scannerState.selectedHistoryRecord
                ?.let { normalizeScannerBarcode(it.scannedBarcode) in normalizedKeys }
                ?: false
            val selectedDetailRemoved = scannerState.selectedHistoryDetail
                ?.let { normalizeScannerBarcode(it.record.scannedBarcode) in normalizedKeys }
                ?: false
            val nextLatestRecord = updatedHistory.firstOrNull()
            currentState.copy(
                scanner = scannerState.copy(
                    history = updatedHistory,
                    lastProcessedBarcode = if (lastProcessedRemoved) nextLatestRecord?.scannedBarcode else scannerState.lastProcessedBarcode,
                    lastLookupResult = if (lastProcessedRemoved) nextLatestRecord?.lookupSnapshot else scannerState.lastLookupResult,
                    lastResult = if (lastProcessedRemoved && nextLatestRecord == null) {
                        ScannerMatchStatus.WAITING
                    } else {
                        scannerState.lastResult
                    },
                    statusMessage = if (lastProcessedRemoved && nextLatestRecord == null) {
                        waitingScannerMessage(currentState.appLanguage)
                    } else {
                        scannerState.statusMessage
                    },
                    selectedHistoryRecord = if (selectedRecordRemoved) null else scannerState.selectedHistoryRecord,
                    selectedHistoryDetail = if (selectedDetailRemoved) null else scannerState.selectedHistoryDetail,
                    isHistoryDetailLoading = if (selectedRecordRemoved || selectedDetailRemoved) false else scannerState.isHistoryDetailLoading,
                    historyDetailError = if (selectedRecordRemoved || selectedDetailRemoved) null else scannerState.historyDetailError,
                ),
            )
        }
    }

    private fun scannerRefreshSuccessMessage(
        referenceCount: Int,
        deletedCount: Int,
        language: AppLanguage,
    ): String {
        return if (deletedCount > 0) {
            language.pick(
                "Cargo list updated. $referenceCount records are ready offline and $deletedCount removed codes were cleared locally.",
                "扫码缓存已更新，可离线使用 $referenceCount 条记录，已同步清除 $deletedCount 个失效条码。",
            )
        } else {
            language.pick(
                "Cargo list updated. $referenceCount records are ready offline.",
                "扫码缓存已更新，可离线使用 $referenceCount 条记录。",
            )
        }
    }

    private fun currentLanguage(): AppLanguage = state.value.appLanguage

    private companion object {
        const val MAX_SCANNER_HISTORY_ITEMS = 60
    }
}
