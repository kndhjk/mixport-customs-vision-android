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

    fun refreshScannerReferences(manual: Boolean = true) {
        val language = currentLanguage()
        val settings = currentScannerSyncSettings()
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
        if (state.value.scanner.sync.isRefreshing) {
            return
        }
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(
                        isRefreshing = true,
                        statusMessage = language.pick(
                            if (manual) "Downloading the latest HBL cache..." else "Refreshing live scanner cache...",
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
                applyScannerSyncStatus(
                    status = result.status,
                    statusMessage = currentLanguage().pick(
                        "Scanner cache updated. ${result.status.referenceCount} scan records are ready offline.",
                        "扫码缓存已更新，可离线使用 ${result.status.referenceCount} 条记录。",
                    ),
                    isRefreshing = false,
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
        val settings = currentScannerSyncSettings()
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
                            "Uploading pending scanner results...",
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
                applyScannerSyncStatus(
                    status = syncStatus,
                    statusMessage = currentLanguage().pick(
                        "Scanner results uploaded. Batch #${syncStatus.lastUploadBatchId ?: 0} is now on the server.",
                        "扫码结果已上传，批次 #${syncStatus.lastUploadBatchId ?: 0} 已写入服务器。",
                    ),
                    isUploading = false,
                    networkAvailable = true,
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
        if (!state.value.scanner.sync.isConfigured) {
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
            state.update {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = it.scanner.sync.copy(networkAvailable = networkAvailable),
                    ),
                )
            }
        }
    }

    fun maybeAutoUploadPendingScannerScans() {
        val settings = currentScannerSyncSettings()
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
                }
                applyScannerSyncStatus(
                    status = result.status,
                    statusMessage = scannerAutoUploadMessage(result, currentLanguage()),
                    isUploading = result.state == ScannerAutoUploadState.UPLOADED &&
                        result.status.pendingUploadCount > 0,
                    networkAvailable = result.networkAvailable,
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
            repository.recordScannerScan(record, lookupResult)
            val updatedHistory = listOf(record) + state.value.scanner.history
                .filterNot { it.scannedBarcode == record.scannedBarcode && it.scannedAt == record.scannedAt }
                .take(59)
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
                        lastResult = record.matchStatus,
                        statusMessage = scannerMessageFor(record, it.appLanguage),
                        history = updatedHistory,
                        lastProcessedBarcode = record.scannedBarcode,
                        lastLookupResult = lookupResult,
                        feedbackNonce = it.scanner.feedbackNonce + 1,
                        sync = it.scanner.sync.copy(
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
            val settings = currentScannerSyncSettings()
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
            )
        }
    }

    fun autoRefreshScannerReferences() {
        val sync = state.value.scanner.sync
        if (!sync.isConfigured || sync.isRefreshing || sync.isUploading) {
            return
        }
        if (sync.referenceCount <= 0) {
            refreshScannerReferences(manual = false)
            return
        }
        val lastSyncAt = sync.lastReferenceSyncAt ?: 0L
        if (System.currentTimeMillis() - lastSyncAt < SCANNER_REFERENCE_REFRESH_INTERVAL_MS) {
            return
        }
        refreshScannerReferences(manual = false)
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
    ) {
        state.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(
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

    private fun currentScannerSyncSettings(): ScannerSyncSettings {
        return preferencesRepository.getScannerSyncSettings()
    }

    private fun currentLanguage(): AppLanguage = state.value.appLanguage
}
