package nz.co.mixport.customsvision.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.co.mixport.customsvision.camera.InspectionTuningProfile
import nz.co.mixport.customsvision.camera.LiveDetectionFrame
import nz.co.mixport.customsvision.camera.LiveRecognition
import nz.co.mixport.customsvision.camera.LiveTrackCountEngine
import nz.co.mixport.customsvision.camera.MobileVisionProfile
import nz.co.mixport.customsvision.camera.UniversalRecognitionSnapshot
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.AppPreferencesRepository
import nz.co.mixport.customsvision.data.AppStartupSnapshot
import nz.co.mixport.customsvision.data.BarcodeLookupResult
import nz.co.mixport.customsvision.data.CargoSummaryRecord
import nz.co.mixport.customsvision.data.EventLogRecord
import nz.co.mixport.customsvision.data.InspectionSessionRecord
import nz.co.mixport.customsvision.data.isUsableScannerBarcode
import nz.co.mixport.customsvision.data.normalizeScannerBarcode
import nz.co.mixport.customsvision.data.PalletDetail
import nz.co.mixport.customsvision.data.PilotRepository
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord
import nz.co.mixport.customsvision.data.ScannerSyncSettings
import nz.co.mixport.customsvision.data.SessionDraft
import nz.co.mixport.customsvision.domain.PalletWorkflowReducer
import nz.co.mixport.customsvision.domain.SessionPhase
import nz.co.mixport.customsvision.domain.WorkflowAction
import nz.co.mixport.customsvision.domain.WorkflowEvent
import nz.co.mixport.customsvision.domain.WorkflowState
import nz.co.mixport.customsvision.scanner.PdaScanWorkflowMode

enum class AppDestination {
    LIVE,
    SCANNER,
    HISTORY,
}

data class ScannerSyncUiState(
    val apiBaseUrl: String = "",
    val bearerToken: String = "",
    val deviceId: String = "",
    val referenceCount: Int = 0,
    val pendingUploadCount: Int = 0,
    val lastReferenceSyncAt: Long? = null,
    val lastUploadAt: Long? = null,
    val lastUploadBatchId: Long? = null,
    val isRefreshing: Boolean = false,
    val isUploading: Boolean = false,
    val statusMessage: String? = null,
) {
    val isConfigured: Boolean
        get() = apiBaseUrl.isNotBlank() && bearerToken.isNotBlank() && deviceId.isNotBlank()
}

data class ScannerUiState(
    val barcodeInput: String = "",
    val isAutoVerifyEnabled: Boolean = true,
    val isSoundEnabled: Boolean = true,
    val workflowMode: PdaScanWorkflowMode = PdaScanWorkflowMode.TRIGGER_ONCE,
    val onboardingDismissed: Boolean = false,
    val isProcessing: Boolean = false,
    val lastResult: ScannerMatchStatus = ScannerMatchStatus.WAITING,
    val statusMessage: String? = null,
    val history: List<ScannerRecord> = emptyList(),
    val lastProcessedBarcode: String? = null,
    val lastLookupResult: BarcodeLookupResult? = null,
    val feedbackNonce: Long = 0L,
    val sync: ScannerSyncUiState = ScannerSyncUiState(),
) {
    val matchedCount: Int
        get() = history.count { it.matchStatus == ScannerMatchStatus.MATCHED }

    val mismatchCount: Int
        get() = history.count { it.matchStatus == ScannerMatchStatus.MISMATCH }

    val errorCount: Int
        get() = history.count { it.matchStatus == ScannerMatchStatus.ERROR }
}

data class LiveInspectionUiState(
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    val inspectionTuning: InspectionTuningProfile = InspectionTuningProfile.default(),
    val mobileVisionProfile: MobileVisionProfile? = null,
    val inspectionTuningSource: String = "",
    val selectedDestination: AppDestination = AppDestination.LIVE,
    val draft: SessionDraft = SessionDraft(),
    val activeSession: InspectionSessionRecord? = null,
    val workflowState: WorkflowState = WorkflowState(),
    val currentPalletId: Long? = null,
    val currentPalletItems: List<CargoSummaryRecord> = emptyList(),
    val sealedPallets: List<PalletDetail> = emptyList(),
    val recentEvents: List<EventLogRecord> = emptyList(),
    val history: List<InspectionSessionRecord> = emptyList(),
    val isRecording: Boolean = false,
    val recordingUri: String? = null,
    val cameraPermissionGranted: Boolean = false,
    val lastFrameHeartbeatAt: Long? = null,
    val liveDetections: List<LiveRecognition> = emptyList(),
    val isUniversalRecognitionRunning: Boolean = false,
    val universalRecognitionSnapshot: UniversalRecognitionSnapshot? = null,
    val scanner: ScannerUiState = ScannerUiState(),
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)

class AppViewModel(
    private val repository: PilotRepository,
    private val preferencesRepository: AppPreferencesRepository,
    startupSnapshot: AppStartupSnapshot,
) : ViewModel() {
    private val loadedInspectionTuning = startupSnapshot.loadedInspectionTuning
    private val initialScannerRecord = startupSnapshot.scannerHistory.firstOrNull()
    private val reducer = PalletWorkflowReducer()
    private val _uiState = MutableStateFlow(
        LiveInspectionUiState(
            appLanguage = startupSnapshot.appLanguage,
            inspectionTuning = loadedInspectionTuning.profile,
            mobileVisionProfile = loadedInspectionTuning.mobileVisionProfile,
            inspectionTuningSource = loadedInspectionTuning.sourceDescription,
            selectedDestination = AppDestination.LIVE,
            scanner = ScannerUiState(
                isAutoVerifyEnabled = startupSnapshot.scannerAutoVerifyEnabled,
                isSoundEnabled = startupSnapshot.scannerSoundEnabled,
                workflowMode = PdaScanWorkflowMode.TRIGGER_ONCE,
                onboardingDismissed = startupSnapshot.scannerOnboardingDismissed,
                lastResult = initialScannerRecord?.matchStatus ?: ScannerMatchStatus.WAITING,
                statusMessage = initialScannerRecord?.let { record ->
                    scannerMessageFor(record, startupSnapshot.appLanguage)
                } ?: startupSnapshot.appLanguage.pick(
                    "Waiting for barcode input.",
                    "等待扫描序列号。",
                ),
                history = startupSnapshot.scannerHistory,
                lastProcessedBarcode = initialScannerRecord?.scannedBarcode,
                sync = ScannerSyncUiState(
                    apiBaseUrl = startupSnapshot.scannerSyncSettings.apiBaseUrl,
                    bearerToken = startupSnapshot.scannerSyncSettings.bearerToken,
                    deviceId = startupSnapshot.scannerSyncSettings.deviceId,
                    lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt(),
                    lastUploadAt = preferencesRepository.getScannerLastUploadAt(),
                    lastUploadBatchId = preferencesRepository.getScannerLastUploadBatchId(),
                ),
            ),
        ),
    )
    val uiState: StateFlow<LiveInspectionUiState> = _uiState.asStateFlow()
    private val trackCountEngine = LiveTrackCountEngine(loadedInspectionTuning.profile)

    init {
        refreshHistory()
        refreshScannerSyncStatus()
    }

    fun selectDestination(destination: AppDestination) {
        _uiState.update { it.copy(selectedDestination = destination) }
        if (destination == AppDestination.SCANNER) {
            autoRefreshScannerReferences()
        }
    }

    fun toggleLanguage() {
        val currentState = _uiState.value
        val nextLanguage = if (currentState.appLanguage == AppLanguage.ENGLISH) {
            AppLanguage.CHINESE
        } else {
            AppLanguage.ENGLISH
        }
        preferencesRepository.setLanguage(nextLanguage)
        val translatedScannerStatus = when {
            currentState.scanner.isProcessing -> nextLanguage.pick(
                "Verifying barcode...",
                "正在比对序列号...",
            )

            currentState.scanner.history.isNotEmpty() -> scannerMessageFor(
                record = currentState.scanner.history.first(),
                language = nextLanguage,
            )

            currentState.scanner.lastResult == ScannerMatchStatus.WAITING -> nextLanguage.pick(
                "Waiting for barcode input.",
                "等待扫描序列号。",
            )

            else -> null
        }
        _uiState.update {
            it.copy(
                appLanguage = nextLanguage,
                infoMessage = null,
                errorMessage = null,
                scanner = it.scanner.copy(statusMessage = translatedScannerStatus),
            )
        }
    }

    fun setCameraPermission(granted: Boolean) {
        _uiState.update { it.copy(cameraPermissionGranted = granted) }
    }

    fun updateContainerCode(value: String) {
        _uiState.update { it.copy(draft = it.draft.copy(containerCode = value)) }
    }

    fun updateVesselName(value: String) {
        _uiState.update { it.copy(draft = it.draft.copy(vesselName = value)) }
    }

    fun updateOperatorName(value: String) {
        _uiState.update { it.copy(draft = it.draft.copy(operatorName = value)) }
    }

    fun updateNotes(value: String) {
        _uiState.update { it.copy(draft = it.draft.copy(notes = value)) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(infoMessage = null, errorMessage = null) }
    }

    fun updateScannerInput(value: String) {
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    barcodeInput = value.filterNot(Char::isISOControl).take(64),
                ),
            )
        }
    }

    fun setScannerAutoVerifyEnabled(enabled: Boolean) {
        preferencesRepository.setScannerAutoVerifyEnabled(enabled)
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    isAutoVerifyEnabled = enabled,
                ),
            )
        }
    }

    fun setScannerSoundEnabled(enabled: Boolean) {
        preferencesRepository.setScannerSoundEnabled(enabled)
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    isSoundEnabled = enabled,
                ),
            )
        }
    }

    fun setScannerWorkflowMode(mode: PdaScanWorkflowMode) {
        preferencesRepository.setScannerWorkflowMode(mode)
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    workflowMode = mode,
                ),
            )
        }
    }

    fun dismissScannerOnboarding() {
        preferencesRepository.setScannerOnboardingDismissed(true)
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(onboardingDismissed = true),
            )
        }
    }

    fun clearScannerHistory() {
        preferencesRepository.setScannerHistory(emptyList())
        _uiState.update {
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
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    barcodeInput = "",
                    lastResult = ScannerMatchStatus.WAITING,
                    statusMessage = it.appLanguage.pick(
                        "Waiting for barcode input.",
                        "等待扫描序列号。",
                    ),
                    lastProcessedBarcode = null,
                    lastLookupResult = null,
                ),
            )
        }
    }

    fun updateScannerApiBaseUrl(value: String) {
        preferencesRepository.setScannerApiBaseUrl(value)
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(apiBaseUrl = value.trim()),
                ),
            )
        }
    }

    fun updateScannerBearerToken(value: String) {
        preferencesRepository.setScannerApiBearerToken(value)
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(bearerToken = value.trim()),
                ),
            )
        }
    }

    fun updateScannerDeviceId(value: String) {
        preferencesRepository.setScannerDeviceId(value)
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(deviceId = value.trim()),
                ),
            )
        }
    }

    fun refreshScannerReferences(manual: Boolean = true) {
        val language = currentLanguage()
        val settings = currentScannerSyncSettings()
        if (!settings.isValid()) {
            _uiState.update {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = it.scanner.sync.copy(
                            statusMessage = language.pick(
                                "Set the API address, token, and device ID before syncing.",
                                "请先填写 API 地址、令牌和设备 ID，再同步。",
                            ),
                        ),
                    ),
                )
            }
            return
        }
        if (_uiState.value.scanner.sync.isRefreshing) {
            return
        }
        _uiState.update {
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
        viewModelScope.launch {
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
                _uiState.update {
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
        if (_uiState.value.scanner.sync.pendingUploadCount <= 0) {
            _uiState.update {
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
        if (!settings.isValid()) {
            _uiState.update {
                it.copy(
                    scanner = it.scanner.copy(
                        sync = it.scanner.sync.copy(
                            statusMessage = language.pick(
                                "Set the API address, token, and device ID before uploading.",
                                "请先填写 API 地址、令牌和设备 ID，再上传。",
                            ),
                        ),
                    ),
                )
            }
            return
        }
        if (_uiState.value.scanner.sync.isUploading) {
            return
        }
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(
                        isUploading = true,
                        statusMessage = language.pick(
                            "Uploading pending scanner results to Mixport...",
                            "正在把待上传扫码结果提交到 Mixport...",
                        ),
                    ),
                ),
            )
        }
        viewModelScope.launch {
            runCatching {
                repository.uploadPendingScannerScans(
                    settings = settings,
                    operatorName = activeOperatorName(),
                    workflowMode = _uiState.value.scanner.workflowMode.name,
                    lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt(),
                )
            }.onSuccess { status ->
                preferencesRepository.setScannerLastUploadAt(status.lastUploadAt)
                preferencesRepository.setScannerLastUploadBatchId(status.lastUploadBatchId)
                applyScannerSyncStatus(
                    status = status,
                    statusMessage = currentLanguage().pick(
                        "Scanner results uploaded. Batch #${status.lastUploadBatchId ?: 0} is now on the server.",
                        "扫码结果已上传，批次 #${status.lastUploadBatchId ?: 0} 已写入服务器。",
                    ),
                    isUploading = false,
                )
            }.onFailure { throwable ->
                _uiState.update {
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

    fun verifyScannerBarcode(barcode: String = _uiState.value.scanner.barcodeInput) {
        val normalized = normalizeScannerBarcode(barcode)
        if (normalized.isBlank()) {
            _uiState.update {
                it.copy(
                    scanner = it.scanner.copy(
                        lastResult = ScannerMatchStatus.ERROR,
                        statusMessage = it.appLanguage.pick(
                            "Please scan or enter a barcode first.",
                            "\u8bf7\u5148\u626b\u63cf\u6216\u8f93\u5165\u5e8f\u5217\u53f7\u3002",
                        ),
                        lastLookupResult = null,
                        feedbackNonce = it.scanner.feedbackNonce + 1,
                    ),
                )
            }
            return
        }
        if (_uiState.value.scanner.isProcessing) {
            return
        }

        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    isProcessing = true,
                    statusMessage = it.appLanguage.pick(
                        "Verifying barcode...",
                        "\u6b63\u5728\u6bd4\u5bf9\u5e8f\u5217\u53f7...",
                    ),
                ),
            )
        }

        viewModelScope.launch {
            val verifiedAt = System.currentTimeMillis()
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
                        databaseRecord = localizedErrorRecord(),
                        matchStatus = ScannerMatchStatus.ERROR,
                        status = throwable.message?.takeIf(String::isNotBlank) ?: SCANNER_STATUS_ERROR,
                        source = SCANNER_SOURCE_LOCAL,
                        scannedAt = verifiedAt,
                    )
                },
            )
            repository.recordScannerScan(record, lookupResult)

            val updatedHistory = listOf(record) + _uiState.value.scanner.history
                .filterNot { it.scannedBarcode == record.scannedBarcode && it.scannedAt == record.scannedAt }
                .take(59)
            preferencesRepository.setScannerHistory(updatedHistory)
            val syncStatus = repository.getScannerSyncStatus(
                lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt(),
                lastUploadAt = preferencesRepository.getScannerLastUploadAt(),
                lastUploadBatchId = preferencesRepository.getScannerLastUploadBatchId(),
            )

            _uiState.update {
                it.copy(
                    scanner = it.scanner.copy(
                        barcodeInput = "",
                        isProcessing = false,
                        lastResult = record.matchStatus,
                        statusMessage = scannerMessageFor(record),
                        history = updatedHistory,
                        lastProcessedBarcode = record.scannedBarcode,
                        lastLookupResult = lookupResult,
                        feedbackNonce = it.scanner.feedbackNonce + 1,
                        sync = it.scanner.sync.copy(
                            referenceCount = syncStatus.referenceCount,
                            pendingUploadCount = syncStatus.pendingUploadCount,
                            lastReferenceSyncAt = syncStatus.lastReferenceSyncAt,
                            lastUploadAt = syncStatus.lastUploadAt,
                            lastUploadBatchId = syncStatus.lastUploadBatchId,
                        ),
                    ),
                )
            }
        }
    }

    fun onScannerPdaDetected(
        barcode: String,
        codeType: String,
    ) {
        val normalized = normalizeScannerBarcode(barcode)
        if (normalized.isBlank() || _uiState.value.scanner.isProcessing) {
            return
        }

        _uiState.update {
            val typeSuffix = codeType
                .trim()
                .takeIf(String::isNotBlank)
                ?.let { type -> " ($type)" }
                .orEmpty()
            it.copy(
                scanner = it.scanner.copy(
                    statusMessage = it.appLanguage.pick(
                        "PDA scanner detected ${normalized}${typeSuffix}. Verifying...",
                        "PDA \u5df2\u8bfb\u5230 ${normalized}${typeSuffix}\uff0c\u6b63\u5728\u6bd4\u5bf9\u6570\u636e\u5e93...",
                    ),
                ),
            )
        }
        verifyScannerBarcode(normalized)
    }

    fun onFrameHeartbeat(heartbeatAt: Long) {
        _uiState.update { it.copy(lastFrameHeartbeatAt = heartbeatAt) }
    }

    fun onDetections(frame: LiveDetectionFrame) {
        val decoratedDetections = trackCountEngine.observeDetections(
            detections = frame.detections,
            analyzedAt = frame.analyzedAt,
        )
        _uiState.update { state ->
            state.copy(
                lastFrameHeartbeatAt = frame.analyzedAt,
                liveDetections = decoratedDetections,
                universalRecognitionSnapshot = state.universalRecognitionSnapshot
                    ?.let(trackCountEngine::decorateRecognitionSnapshot),
            )
        }
    }

    fun onUniversalRecognitionStarted() {
        _uiState.update {
            it.copy(
                isUniversalRecognitionRunning = true,
                infoMessage = it.appLanguage.pick(
                    "Analyzing visible cargo with OCR, color, and target labels...",
                    "ÃƒÂ¦Ã‚Â­Ã‚Â£ÃƒÂ¥Ã…â€œÃ‚Â¨ÃƒÂ§Ã‚Â»Ã¢â‚¬Å“ÃƒÂ¥Ã‚ÂÃ‹â€  OCRÃƒÂ£Ã¢â€šÂ¬Ã‚ÂÃƒÂ©Ã‚Â¢Ã…â€œÃƒÂ¨Ã¢â‚¬Â°Ã‚Â²ÃƒÂ¥Ã¢â‚¬â„¢Ã…â€™ÃƒÂ§Ã¢â‚¬ÂºÃ‚Â®ÃƒÂ¦Ã‚Â Ã¢â‚¬Â¡ÃƒÂ§Ã‚Â±Ã‚Â»ÃƒÂ¥Ã‹â€ Ã‚Â«ÃƒÂ¨Ã‚Â¯Ã¢â‚¬Â ÃƒÂ¥Ã‹â€ Ã‚Â«ÃƒÂ¥Ã‚ÂÃ‚Â¯ÃƒÂ¨Ã‚Â§Ã‚ÂÃƒÂ¨Ã‚Â´Ã‚Â§ÃƒÂ§Ã¢â‚¬Â°Ã‚Â©...",
                ),
                errorMessage = null,
            )
        }
    }

    fun onUniversalRecognitionCompleted(snapshot: UniversalRecognitionSnapshot) {
        val decoratedSnapshot = trackCountEngine.observeRecognition(snapshot)
        _uiState.update {
            it.copy(
                isUniversalRecognitionRunning = false,
                universalRecognitionSnapshot = decoratedSnapshot,
                infoMessage = if (decoratedSnapshot.items.isEmpty()) {
                    it.appLanguage.pick(
                        "No recognizable cargo was found in the current view.",
                        "ÃƒÂ¥Ã‚Â½Ã¢â‚¬Å“ÃƒÂ¥Ã¢â‚¬Â°Ã‚ÂÃƒÂ§Ã¢â‚¬ÂÃ‚Â»ÃƒÂ©Ã‚ÂÃ‚Â¢ÃƒÂ©Ã¢â‚¬Â¡Ã…â€™ÃƒÂ¦Ã‚Â²Ã‚Â¡ÃƒÂ¦Ã…â€œÃ¢â‚¬Â°ÃƒÂ¨Ã‚Â¯Ã¢â‚¬Â ÃƒÂ¥Ã‹â€ Ã‚Â«ÃƒÂ¥Ã¢â‚¬Â¡Ã‚ÂºÃƒÂ¦Ã‹Å“Ã…Â½ÃƒÂ§Ã‚Â¡Ã‚Â®ÃƒÂ¨Ã‚Â´Ã‚Â§ÃƒÂ§Ã¢â‚¬Â°Ã‚Â©ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                    )
                } else {
                    it.appLanguage.pick(
                        "Analyzed ${decoratedSnapshot.items.size} visible cargo item(s).",
                        "ÃƒÂ¥Ã‚Â·Ã‚Â²ÃƒÂ¥Ã‹â€ Ã¢â‚¬Â ÃƒÂ¦Ã…Â¾Ã‚Â ${snapshot.items.size} ÃƒÂ¤Ã‚Â¸Ã‚ÂªÃƒÂ¥Ã‚ÂÃ‚Â¯ÃƒÂ¨Ã‚Â§Ã‚ÂÃƒÂ¨Ã‚Â´Ã‚Â§ÃƒÂ§Ã¢â‚¬Â°Ã‚Â©ÃƒÂ¥Ã‚Â¯Ã‚Â¹ÃƒÂ¨Ã‚Â±Ã‚Â¡ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                    )
                },
                errorMessage = null,
            )
        }
    }

    fun onUniversalRecognitionError(message: String) {
        _uiState.update { it.copy(isUniversalRecognitionRunning = false, errorMessage = message) }
    }

    fun onRecordingSaved(recordingUri: String) {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch {
            repository.updateRecordingUri(sessionId, recordingUri)
            refreshCurrentSession(
                sessionId = sessionId,
                workflowState = _uiState.value.workflowState,
                currentPalletId = _uiState.value.currentPalletId,
                infoMessage = currentLanguage().pick(
                    "Video recording saved.",
                    "录像已保存。",
                ),
                isRecording = false,
                recordingUri = recordingUri,
            )
        }
    }

    fun onRecordingStarted() {
        _uiState.update {
            it.copy(
                isRecording = true,
                infoMessage = it.appLanguage.pick(
                    "Recording started.",
                    "已开始录像。",
                ),
                errorMessage = null,
            )
        }
    }

    fun onRecordingError(message: String) {
        _uiState.update { it.copy(isRecording = false, errorMessage = message) }
    }

    fun startSession() {
        val draft = _uiState.value.draft
        if (draft.containerCode.isBlank() || draft.operatorName.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = it.appLanguage.pick(
                        "Container code and operator are required before the session can start.",
                        "ÃƒÂ¥Ã‚Â¼Ã¢â€šÂ¬ÃƒÂ¥Ã‚Â§Ã¢â‚¬Â¹ÃƒÂ¤Ã‚Â½Ã…â€œÃƒÂ¤Ã‚Â¸Ã…Â¡ÃƒÂ¥Ã¢â‚¬Â°Ã‚ÂÃƒÂ¥Ã‚Â¿Ã¢â‚¬Â¦ÃƒÂ©Ã‚Â¡Ã‚Â»ÃƒÂ¥Ã‚Â¡Ã‚Â«ÃƒÂ¥Ã¢â‚¬Â Ã¢â€žÂ¢ÃƒÂ¦Ã…Â¸Ã…â€œÃƒÂ¥Ã‚ÂÃ‚Â·ÃƒÂ¥Ã¢â‚¬â„¢Ã…â€™ÃƒÂ¦Ã¢â‚¬Å“Ã‚ÂÃƒÂ¤Ã‚Â½Ã…â€œÃƒÂ¥Ã¢â‚¬ËœÃ‹Å“ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            trackCountEngine.resetSession()
            val session = repository.createSession(draft)
            refreshCurrentSession(
                sessionId = session.id,
                workflowState = WorkflowState(),
                currentPalletId = null,
                infoMessage = currentLanguage().pick(
                    "Session ${session.containerCode} started.",
                    "ÃƒÂ¤Ã‚Â½Ã…â€œÃƒÂ¤Ã‚Â¸Ã…Â¡ ${session.containerCode} ÃƒÂ¥Ã‚Â·Ã‚Â²ÃƒÂ¥Ã‚Â¼Ã¢â€šÂ¬ÃƒÂ¥Ã‚Â§Ã¢â‚¬Â¹ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                ),
            )
            _uiState.update {
                it.copy(
                    isUniversalRecognitionRunning = false,
                    universalRecognitionSnapshot = null,
                )
            }
        }
    }

    fun closeSession() {
        val snapshot = _uiState.value
        val session = snapshot.activeSession ?: return
        viewModelScope.launch {
            val isComplete = snapshot.workflowState.phase == SessionPhase.READY_TO_COMPLETE &&
                !snapshot.workflowState.containerHasRemainingCargo
            repository.finishSession(session.id, isComplete)
            refreshHistory()
            trackCountEngine.resetSession()
            _uiState.update {
                it.copy(
                    activeSession = null,
                    workflowState = WorkflowState(phase = SessionPhase.CLOSED),
                    currentPalletId = null,
                    currentPalletItems = emptyList(),
                    sealedPallets = emptyList(),
                    recentEvents = emptyList(),
                    liveDetections = emptyList(),
                    isUniversalRecognitionRunning = false,
                    universalRecognitionSnapshot = null,
                    isRecording = false,
                    infoMessage = it.appLanguage.pick("Session closed.", "ÃƒÂ¤Ã‚Â½Ã…â€œÃƒÂ¤Ã‚Â¸Ã…Â¡ÃƒÂ¥Ã‚Â·Ã‚Â²ÃƒÂ¥Ã¢â‚¬Â¦Ã‚Â³ÃƒÂ©Ã¢â‚¬â€Ã‚Â­ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡"),
                    errorMessage = null,
                )
            }
        }
    }

    fun submitWorkflowEvent(event: WorkflowEvent) {
        viewModelScope.launch {
            applyWorkflowEvent(event)
        }
    }

    fun countVisibleDetections() {
        val snapshot = _uiState.value
        if (snapshot.activeSession == null) {
            _uiState.update {
                it.copy(
                    errorMessage = it.appLanguage.pick(
                        "Start a session before counting tracked objects.",
                        "ÃƒÂ¥Ã‚Â¼Ã¢â€šÂ¬ÃƒÂ¥Ã‚Â§Ã¢â‚¬Â¹ÃƒÂ¤Ã‚Â½Ã…â€œÃƒÂ¤Ã‚Â¸Ã…Â¡ÃƒÂ¥Ã‚ÂÃ…Â½ÃƒÂ¦Ã¢â‚¬Â°Ã‚ÂÃƒÂ¨Ã†â€™Ã‚Â½ÃƒÂ§Ã‚Â»Ã…Â¸ÃƒÂ¨Ã‚Â®Ã‚Â¡ÃƒÂ¨Ã‚Â·Ã…Â¸ÃƒÂ¨Ã‚Â¸Ã‚ÂªÃƒÂ¥Ã‹â€ Ã‚Â°ÃƒÂ§Ã…Â¡Ã¢â‚¬Å¾ÃƒÂ¨Ã‚Â´Ã‚Â§ÃƒÂ§Ã¢â‚¬Â°Ã‚Â©ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            var latestSnapshot = _uiState.value
            val palletRecognitionTrackKeys = latestSnapshot.universalRecognitionSnapshot?.items
                ?.mapNotNull { recognition ->
                    recognition.trackKey.takeIf { recognition.isPalletLike && recognition.trackKey.isNotBlank() }
                }
                ?.toSet()
                .orEmpty()
            if (latestSnapshot.workflowState.activePalletSequence == null) {
                val hasVisiblePallet = latestSnapshot.liveDetections.any { detection ->
                    detection.isPalletCandidate ||
                        detection.trackKey.isNotBlank() && palletRecognitionTrackKeys.contains(detection.trackKey)
                }
                if (!hasVisiblePallet) {
                    _uiState.update {
                        it.copy(
                            errorMessage = it.appLanguage.pick(
                                "No pallet candidate is visible yet. Aim the camera at the pallet first.",
                                "ÃƒÂ¥Ã‚Â½Ã¢â‚¬Å“ÃƒÂ¥Ã¢â‚¬Â°Ã‚ÂÃƒÂ¨Ã‚Â¿Ã‹Å“ÃƒÂ¦Ã‚Â²Ã‚Â¡ÃƒÂ¦Ã…â€œÃ¢â‚¬Â°ÃƒÂ§Ã…â€œÃ¢â‚¬Â¹ÃƒÂ¥Ã‹â€ Ã‚Â°ÃƒÂ¦Ã¢â‚¬Â°Ã‹Å“ÃƒÂ§Ã¢â‚¬ÂºÃ‹Å“ÃƒÂ¥Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÂ©Ã¢â€šÂ¬Ã¢â‚¬Â°ÃƒÂ¯Ã‚Â¼Ã…â€™ÃƒÂ¨Ã‚Â¯Ã‚Â·ÃƒÂ¥Ã¢â‚¬Â¦Ã‹â€ ÃƒÂ¦Ã…Â Ã…Â ÃƒÂ©Ã¢â‚¬Â¢Ã…â€œÃƒÂ¥Ã‚Â¤Ã‚Â´ÃƒÂ¥Ã‚Â¯Ã‚Â¹ÃƒÂ¥Ã¢â‚¬Â¡Ã¢â‚¬Â ÃƒÂ¦Ã¢â‚¬Â°Ã‹Å“ÃƒÂ§Ã¢â‚¬ÂºÃ‹Å“ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                            ),
                        )
                    }
                    return@launch
                }
                applyWorkflowEvent(WorkflowEvent.PalletArrived(System.currentTimeMillis()))
                latestSnapshot = _uiState.value
            }

            val recognitionByTrackKey = latestSnapshot.universalRecognitionSnapshot?.items
                ?.mapNotNull { insight ->
                    insight.trackKey.takeIf { it.isNotBlank() }?.let { trackKey -> trackKey to insight }
                }
                ?.toMap()
                .orEmpty()

            val countableDetections = latestSnapshot.liveDetections.filter { detection ->
                !detection.isPalletCandidate &&
                    detection.trackKey.isNotBlank() &&
                    !palletRecognitionTrackKeys.contains(detection.trackKey) &&
                    detection.isInPalletZone &&
                    detection.isCountReady &&
                    !detection.isCounted &&
                    hasReliableCargoIdentity(
                        detection = detection,
                        recognition = recognitionByTrackKey[detection.trackKey],
                    )
            }

            if (countableDetections.isEmpty()) {
                val stabilizingCount = latestSnapshot.liveDetections.count { detection ->
                    !detection.isPalletCandidate &&
                        !detection.isCounted &&
                        detection.isInPalletZone &&
                        detection.stableFrameCount < LiveTrackCountEngine.DEFAULT_MIN_STABLE_FRAMES
                }
                val awaitingPlacementCount = latestSnapshot.liveDetections.count { detection ->
                    !detection.isPalletCandidate &&
                        !detection.isCounted &&
                        !detection.isInPalletZone
                }
                val awaitingAnalysisCount = latestSnapshot.liveDetections.count { detection ->
                    !detection.isPalletCandidate &&
                        !detection.isCounted &&
                        detection.isInPalletZone &&
                        detection.stableFrameCount >= LiveTrackCountEngine.DEFAULT_MIN_STABLE_FRAMES &&
                        !hasReliableCargoIdentity(
                            detection = detection,
                            recognition = recognitionByTrackKey[detection.trackKey],
                        )
                }
                _uiState.update {
                    it.copy(
                        infoMessage = it.appLanguage.pick(
                            "No new tracked cargo is ready to count.",
                            "ÃƒÂ¥Ã‚Â½Ã¢â‚¬Å“ÃƒÂ¥Ã¢â‚¬Â°Ã‚ÂÃƒÂ¦Ã‚Â²Ã‚Â¡ÃƒÂ¦Ã…â€œÃ¢â‚¬Â°ÃƒÂ¦Ã¢â‚¬â€œÃ‚Â°ÃƒÂ§Ã…Â¡Ã¢â‚¬Å¾ÃƒÂ¥Ã‚ÂÃ‚Â¯ÃƒÂ§Ã‚Â»Ã…Â¸ÃƒÂ¨Ã‚Â®Ã‚Â¡ÃƒÂ¨Ã‚Â´Ã‚Â§ÃƒÂ§Ã¢â‚¬Â°Ã‚Â©ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                        ),
                        errorMessage = null,
                    )
                }
                if (stabilizingCount > 0) {
                    _uiState.update {
                        it.copy(
                            infoMessage = it.appLanguage.pick(
                                "$stabilizingCount tracked object(s) are still stabilizing. Hold the camera steady for another moment.",
                                "ÃƒÆ’Ã‚Â¨Ãƒâ€šÃ‚Â¿Ãƒâ€¹Ã…â€œÃƒÆ’Ã‚Â¦Ãƒâ€¦Ã¢â‚¬Å“ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â° $stabilizingCount ÃƒÆ’Ã‚Â¤Ãƒâ€šÃ‚Â¸Ãƒâ€šÃ‚ÂªÃƒÆ’Ã‚Â¨Ãƒâ€šÃ‚Â·Ãƒâ€¦Ã‚Â¸ÃƒÆ’Ã‚Â¨Ãƒâ€šÃ‚Â¸Ãƒâ€šÃ‚ÂªÃƒÆ’Ã‚Â¥Ãƒâ€šÃ‚Â¯Ãƒâ€šÃ‚Â¹ÃƒÆ’Ã‚Â¨Ãƒâ€šÃ‚Â±Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã‚Â¥Ãƒâ€¦Ã¢â‚¬Å“Ãƒâ€šÃ‚Â¨ÃƒÆ’Ã‚Â§Ãƒâ€šÃ‚Â¨Ãƒâ€šÃ‚Â³ÃƒÆ’Ã‚Â¥Ãƒâ€šÃ‚Â®Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã‚Â¤Ãƒâ€šÃ‚Â¸Ãƒâ€šÃ‚Â­ÃƒÆ’Ã‚Â¯Ãƒâ€šÃ‚Â¼Ãƒâ€¦Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¨Ãƒâ€šÃ‚Â¯Ãƒâ€šÃ‚Â·ÃƒÆ’Ã‚Â¥ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â§Ãƒâ€šÃ‚Â¨Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¥Ãƒâ€šÃ‚Â¾Ãƒâ€šÃ‚Â®ÃƒÆ’Ã‚Â¤Ãƒâ€šÃ‚Â¿Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¦Ãƒâ€¦Ã¢â‚¬â„¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â§ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒâ€šÃ‚Â»ÃƒÆ’Ã‚Â©Ãƒâ€šÃ‚ÂÃƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â§Ãƒâ€šÃ‚Â¨Ãƒâ€šÃ‚Â³ÃƒÆ’Ã‚Â¥Ãƒâ€šÃ‚Â®Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã‚Â£ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡",
                            ),
                            errorMessage = null,
                        )
                    }
                } else if (awaitingPlacementCount > 0) {
                    _uiState.update {
                        it.copy(
                            infoMessage = it.appLanguage.pick(
                                "$awaitingPlacementCount tracked object(s) are visible but not yet sitting on the pallet zone.",
                                "$awaitingPlacementCount ÃƒÂ¤Ã‚Â¸Ã‚ÂªÃƒÂ¨Ã‚Â·Ã…Â¸ÃƒÂ¨Ã‚Â¸Ã‚ÂªÃƒÂ¥Ã‚Â¯Ã‚Â¹ÃƒÂ¨Ã‚Â±Ã‚Â¡ÃƒÂ¥Ã‚Â·Ã‚Â²ÃƒÂ¥Ã¢â‚¬Â¡Ã‚ÂºÃƒÂ§Ã…Â½Ã‚Â°ÃƒÂ¯Ã‚Â¼Ã…â€™ÃƒÂ¤Ã‚Â½Ã¢â‚¬Â ÃƒÂ¨Ã‚Â¿Ã‹Å“ÃƒÂ¦Ã‚Â²Ã‚Â¡ÃƒÂ¦Ã…â€œÃ¢â‚¬Â°ÃƒÂ¨Ã‚Â¿Ã¢â‚¬ÂºÃƒÂ¥Ã¢â‚¬Â¦Ã‚Â¥ÃƒÂ¦Ã¢â‚¬Â°Ã‹Å“ÃƒÂ§Ã¢â‚¬ÂºÃ‹Å“ÃƒÂ¨Ã‚Â£Ã¢â‚¬Â¦ÃƒÂ¨Ã‚Â´Ã‚Â§ÃƒÂ¥Ã…â€™Ã‚ÂºÃƒÂ¥Ã…Â¸Ã…Â¸ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                            ),
                            errorMessage = null,
                        )
                    }
                } else if (awaitingAnalysisCount > 0) {
                    _uiState.update {
                        it.copy(
                            infoMessage = it.appLanguage.pick(
                                "$awaitingAnalysisCount stable object(s) need richer OCR or label evidence before counting.",
                                "$awaitingAnalysisCount ÃƒÂ¤Ã‚Â¸Ã‚ÂªÃƒÂ§Ã‚Â¨Ã‚Â³ÃƒÂ¥Ã‚Â®Ã…Â¡ÃƒÂ¥Ã‚Â¯Ã‚Â¹ÃƒÂ¨Ã‚Â±Ã‚Â¡ÃƒÂ¨Ã‚Â¿Ã‹Å“ÃƒÂ©Ã…â€œÃ¢â€šÂ¬ÃƒÂ¨Ã‚Â¦Ã‚ÂÃƒÂ¦Ã¢â‚¬ÂºÃ‚Â´ÃƒÂ¥Ã‚Â¤Ã…Â¡ OCR ÃƒÂ¦Ã‹â€ Ã¢â‚¬â€œÃƒÂ¦Ã‚Â Ã¢â‚¬Â¡ÃƒÂ§Ã‚Â­Ã‚Â¾ÃƒÂ¨Ã‚Â¯Ã‚ÂÃƒÂ¦Ã‚ÂÃ‚Â®ÃƒÂ¥Ã‚ÂÃ…Â½ÃƒÂ¦Ã¢â‚¬Â°Ã‚ÂÃƒÂ¤Ã‚Â¼Ã…Â¡ÃƒÂ¨Ã‚Â®Ã‚Â¡ÃƒÂ¦Ã¢â‚¬Â¢Ã‚Â°ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                            ),
                            errorMessage = null,
                        )
                    }
                }
                return@launch
            }

            val countedAt = System.currentTimeMillis()
            countableDetections.forEach { detection ->
                val recognition = detection.trackKey.let(recognitionByTrackKey::get)
                applyWorkflowEvent(
                    WorkflowEvent.CargoPlaced(
                        itemLabel = recognition?.bestLabel?.ifBlank { detection.label } ?: detection.label,
                        colorName = recognition?.dominantColor ?: "Unclassified",
                        markerText = recognition?.markerText?.ifBlank {
                            detection.category.takeIf { it != "Unknown" }.orEmpty()
                        } ?: detection.category.takeIf { it != "Unknown" }.orEmpty(),
                        observedAt = countedAt,
                        trackKey = detection.trackKey,
                        stableFrameCount = detection.stableFrameCount,
                        detectionConfidence = detection.confidence,
                        recognitionConfidence = recognition?.confidence,
                    ),
                )
            }
            trackCountEngine.markCounted(
                detections = countableDetections,
                countedAt = countedAt,
            )

            _uiState.update {
                it.copy(
                    liveDetections = decorateLiveDetections(
                        detections = it.liveDetections,
                        analyzedAt = countedAt,
                    ),
                    universalRecognitionSnapshot = decorateRecognitionSnapshot(it.universalRecognitionSnapshot),
                    infoMessage = it.appLanguage.pick(
                        "${countableDetections.size} tracked object(s) counted from the live view.",
                        "ÃƒÂ¥Ã‚Â·Ã‚Â²ÃƒÂ¤Ã‚Â»Ã…Â½ÃƒÂ¥Ã‚Â®Ã…Â¾ÃƒÂ¦Ã¢â‚¬â€Ã‚Â¶ÃƒÂ§Ã¢â‚¬ÂÃ‚Â»ÃƒÂ©Ã‚ÂÃ‚Â¢ÃƒÂ§Ã‚Â»Ã…Â¸ÃƒÂ¨Ã‚Â®Ã‚Â¡ ${countableDetections.size} ÃƒÂ¤Ã‚Â¸Ã‚ÂªÃƒÂ¨Ã‚Â·Ã…Â¸ÃƒÂ¨Ã‚Â¸Ã‚ÂªÃƒÂ¥Ã‚Â¯Ã‚Â¹ÃƒÂ¨Ã‚Â±Ã‚Â¡ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                    ),
                    errorMessage = null,
                )
            }
        }
    }

    private fun refreshHistory() {
        viewModelScope.launch {
            val history = repository.listSessions()
            _uiState.update { it.copy(history = history) }
        }
    }

    private suspend fun applyWorkflowEvent(event: WorkflowEvent) {
        val activeSession = _uiState.value.activeSession ?: run {
            _uiState.update {
                it.copy(
                    errorMessage = it.appLanguage.pick(
                        "Start a session before processing detections.",
                        "ÃƒÂ¨Ã‚Â¯Ã‚Â·ÃƒÂ¥Ã¢â‚¬Â¦Ã‹â€ ÃƒÂ¥Ã‚Â¼Ã¢â€šÂ¬ÃƒÂ¥Ã‚Â§Ã¢â‚¬Â¹ÃƒÂ¤Ã‚Â½Ã…â€œÃƒÂ¤Ã‚Â¸Ã…Â¡ÃƒÂ¯Ã‚Â¼Ã…â€™ÃƒÂ¥Ã¢â‚¬Â Ã‚ÂÃƒÂ¥Ã‚Â¤Ã¢â‚¬Å¾ÃƒÂ§Ã‚ÂÃ¢â‚¬Â ÃƒÂ¨Ã‚Â¯Ã¢â‚¬Â ÃƒÂ¥Ã‹â€ Ã‚Â«ÃƒÂ§Ã‚Â»Ã¢â‚¬Å“ÃƒÂ¦Ã…Â¾Ã…â€œÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                    ),
                )
            }
            return
        }

        val currentSnapshot = _uiState.value
        val transition = reducer.reduce(currentSnapshot.workflowState, event)
        var activePalletId = currentSnapshot.currentPalletId
        var infoMessage: String? = null

        transition.actions.forEach { action ->
            when (action) {
                is WorkflowAction.OpenPallet -> {
                    val pallet = repository.openPallet(
                        sessionId = activeSession.id,
                        sequenceNumber = action.sequenceNumber,
                        startedAt = action.startedAt,
                    )
                    activePalletId = pallet.id
                }

                is WorkflowAction.IncrementItem -> {
                    val palletId = activePalletId ?: repository.openPallet(
                        sessionId = activeSession.id,
                        sequenceNumber = action.sequenceNumber,
                        startedAt = action.recordedAt,
                    ).id.also { activePalletId = it }

                    repository.incrementPalletItem(
                        palletId = palletId,
                        itemLabel = action.itemLabel,
                        colorName = action.colorName,
                        markerText = action.markerText,
                        delta = action.quantity,
                    )
                    infoMessage = currentLanguage().pick(
                        "${action.itemLabel} counted.",
                        "${action.itemLabel} ÃƒÂ¥Ã‚Â·Ã‚Â²ÃƒÂ¨Ã‚Â®Ã‚Â¡ÃƒÂ¦Ã¢â‚¬Â¢Ã‚Â°ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                    )
                }

                is WorkflowAction.Log -> {
                    repository.insertEvent(
                        sessionId = activeSession.id,
                        palletId = activePalletId,
                        eventType = action.eventType,
                        message = action.message,
                        payloadJson = action.payloadJson,
                        createdAt = action.recordedAt,
                    )
                }

                is WorkflowAction.ClosePallet -> {
                    val palletId = activePalletId
                    if (palletId != null) {
                        repository.closePallet(
                            palletId = palletId,
                            closedAt = action.closedAt,
                            wrapDetected = true,
                            containerEmptyAtClose = action.containerEmptyAtClose,
                        )
                        activePalletId = null
                        trackCountEngine.resetForNextPallet()
                        infoMessage = currentLanguage().pick(
                            "Pallet #${action.sequenceNumber} archived.",
                            "ÃƒÂ¦Ã¢â‚¬Â°Ã‹Å“ÃƒÂ§Ã¢â‚¬ÂºÃ‹Å“ #${action.sequenceNumber} ÃƒÂ¥Ã‚Â·Ã‚Â²ÃƒÂ¥Ã‚Â½Ã¢â‚¬â„¢ÃƒÂ¦Ã‚Â¡Ã‚Â£ÃƒÂ£Ã¢â€šÂ¬Ã¢â‚¬Å¡",
                        )
                    }
                }

                is WorkflowAction.SetContainerHasRemainingCargo -> {
                    repository.updateContainerFlag(activeSession.id, action.hasRemaining)
                }

                WorkflowAction.MarkSessionReady -> {
                    repository.markReadyToComplete(activeSession.id)
                }
            }
        }

        refreshCurrentSession(
            sessionId = activeSession.id,
            workflowState = transition.state,
            currentPalletId = activePalletId,
            infoMessage = infoMessage,
        )
    }

    private suspend fun refreshCurrentSession(
        sessionId: Long,
        workflowState: WorkflowState,
        currentPalletId: Long?,
        infoMessage: String? = null,
        isRecording: Boolean = _uiState.value.isRecording,
        recordingUri: String? = _uiState.value.recordingUri,
    ) {
        val session = repository.getSession(sessionId)
        val pallets = repository.listPalletDetails(sessionId)
        val currentItems = currentPalletId?.let { repository.listPalletItems(it) }.orEmpty()
        val events = repository.listEvents(sessionId)
        val history = repository.listSessions()
        val shouldResetVisualState = currentPalletId == null && workflowState.phase != SessionPhase.LOADING

        _uiState.update {
            it.copy(
                activeSession = session,
                workflowState = workflowState,
                currentPalletId = currentPalletId,
                currentPalletItems = currentItems,
                sealedPallets = pallets.filter { detail -> detail.pallet.closedAt != null },
                recentEvents = events,
                history = history,
                liveDetections = if (shouldResetVisualState) {
                    emptyList()
                } else {
                    decorateLiveDetections(
                        detections = it.liveDetections,
                        analyzedAt = it.lastFrameHeartbeatAt ?: System.currentTimeMillis(),
                    )
                },
                universalRecognitionSnapshot = if (shouldResetVisualState) {
                    null
                } else {
                    decorateRecognitionSnapshot(it.universalRecognitionSnapshot)
                },
                infoMessage = infoMessage,
                errorMessage = null,
                isRecording = isRecording,
                recordingUri = recordingUri ?: session?.recordingUri,
            )
        }
    }

    private fun decorateLiveDetections(
        detections: List<LiveRecognition>,
        analyzedAt: Long,
    ): List<LiveRecognition> {
        return trackCountEngine.decorateDetections(
            detections = detections,
            analyzedAt = analyzedAt,
        )
    }

    private fun decorateRecognitionSnapshot(
        snapshot: UniversalRecognitionSnapshot?,
    ): UniversalRecognitionSnapshot? {
        return snapshot?.let(trackCountEngine::decorateRecognitionSnapshot)
    }

    private suspend fun lookupBarcode(barcode: String): BarcodeLookupResult? {
        if (!isUsableScannerBarcode(barcode)) {
            return null
        }
        return repository.lookupBarcode(barcode, currentScannerSyncSettings())
    }

    private fun buildScannerRecord(
        barcode: String,
        lookupResult: BarcodeLookupResult?,
        scannedAt: Long,
    ): ScannerRecord {
        return when {
            !isUsableScannerBarcode(barcode) -> {
                ScannerRecord(
                    scannedBarcode = barcode,
                    databaseRecord = localizedErrorRecord(),
                    matchStatus = ScannerMatchStatus.ERROR,
                    status = SCANNER_STATUS_INVALID_FORMAT,
                    source = SCANNER_SOURCE_LOCAL,
                    scannedAt = scannedAt,
                )
            }

            lookupResult != null && lookupResult.found -> {
                ScannerRecord(
                    scannedBarcode = barcode,
                    databaseRecord = lookupResult.databaseRecord,
                    matchStatus = ScannerMatchStatus.MATCHED,
                    status = lookupResult.status,
                    source = lookupResult.source,
                    scannedAt = scannedAt,
                    customersStatus = lookupResult.customersStatus,
                    mpiStatus = lookupResult.mpiStatus,
                )
            }

            else -> {
                ScannerRecord(
                    scannedBarcode = barcode,
                    databaseRecord = SCANNER_RECORD_NOT_FOUND,
                    matchStatus = ScannerMatchStatus.MISMATCH,
                    status = SCANNER_STATUS_UNKNOWN,
                    source = SCANNER_SOURCE_LOCAL,
                    scannedAt = scannedAt,
                )
            }
        }
    }

    private fun scannerMessageFor(record: ScannerRecord): String {
        return scannerMessageFor(record, currentLanguage())
    }

    private fun scannerMessageFor(
        record: ScannerRecord,
        language: AppLanguage,
    ): String {
        return when (record.matchStatus) {
            ScannerMatchStatus.MATCHED -> scannerMatchedMessage(record, language)

            ScannerMatchStatus.MISMATCH -> language.pick(
                "\"${record.scannedBarcode}\" was not found.",
                "数据库里没有找到序列号 ${record.scannedBarcode}。",
            )

            ScannerMatchStatus.ERROR -> language.pick(
                "\"${record.scannedBarcode}\" could not be verified.",
                "序列号 ${record.scannedBarcode} 无法完成校验。",
            )

            ScannerMatchStatus.WAITING -> language.pick(
                "Waiting for barcode input.",
                "等待扫描序列号。",
            )
        }
    }

    private fun scannerMatchedMessage(
        record: ScannerRecord,
        language: AppLanguage,
    ): String {
        val nzcsStatus = localizedStatus(
            language,
            canonicalScannerClearanceStatus(record.customersStatus),
        )
        val mpiStatus = localizedStatus(
            language,
            canonicalScannerClearanceStatus(record.mpiStatus),
        )
        return when (overallScannerClearanceStatus(record.customersStatus, record.mpiStatus)) {
            "CLEAR" -> language.pick(
                "\"${record.scannedBarcode}\" matched and is clear. NZCS $nzcsStatus | MPI $mpiStatus.",
                "序列号 ${record.scannedBarcode} 已匹配并已放行。NZCS ${nzcsStatus} | MPI ${mpiStatus}。",
            )

            "FAILED" -> language.pick(
                "\"${record.scannedBarcode}\" matched, but clearance failed. NZCS $nzcsStatus | MPI $mpiStatus.",
                "序列号 ${record.scannedBarcode} 已匹配，但清关未通过。NZCS ${nzcsStatus} | MPI ${mpiStatus}。",
            )

            else -> language.pick(
                "\"${record.scannedBarcode}\" matched, but clearance is on hold. NZCS $nzcsStatus | MPI $mpiStatus.",
                "序列号 ${record.scannedBarcode} 已匹配，但当前仍为待处理。NZCS ${nzcsStatus} | MPI ${mpiStatus}。",
            )
        }
    }

    private fun localizedErrorRecord(): String {
        return SCANNER_RECORD_ERROR
    }

    private fun refreshScannerSyncStatus() {
        viewModelScope.launch {
            val status = repository.getScannerSyncStatus(
                lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt(),
                lastUploadAt = preferencesRepository.getScannerLastUploadAt(),
                lastUploadBatchId = preferencesRepository.getScannerLastUploadBatchId(),
            )
            applyScannerSyncStatus(
                status = status,
                statusMessage = _uiState.value.scanner.sync.statusMessage,
            )
        }
    }

    private fun applyScannerSyncStatus(
        status: nz.co.mixport.customsvision.data.ScannerSyncStatus,
        statusMessage: String?,
        isRefreshing: Boolean = false,
        isUploading: Boolean = false,
    ) {
        _uiState.update {
            it.copy(
                scanner = it.scanner.copy(
                    sync = it.scanner.sync.copy(
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

    private fun autoRefreshScannerReferences() {
        val sync = _uiState.value.scanner.sync
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

    private fun currentScannerSyncSettings(): ScannerSyncSettings {
        val sync = _uiState.value.scanner.sync
        return ScannerSyncSettings(
            apiBaseUrl = sync.apiBaseUrl,
            bearerToken = sync.bearerToken,
            deviceId = sync.deviceId,
        )
    }

    private fun activeOperatorName(): String {
        return _uiState.value.activeSession?.operatorName
            ?.takeIf(String::isNotBlank)
            ?: _uiState.value.draft.operatorName.takeIf(String::isNotBlank)
            ?: "Scanner Operator"
    }

    private fun hasReliableCargoIdentity(
        detection: LiveRecognition,
        recognition: nz.co.mixport.customsvision.camera.UniversalRecognition?,
    ): Boolean {
        val recognitionLabel = recognition?.bestLabel.orEmpty()
        if (isMeaningfulCargoLabel(recognitionLabel)) {
            return true
        }
        if (isMeaningfulCargoLabel(detection.label)) {
            return true
        }
        return isMeaningfulCargoLabel(detection.category) &&
            (detection.confidence ?: 0f) >= loadedInspectionTuning.profile.cargoLabeling.minReliableDetectionConfidence
    }

    private fun isMeaningfulCargoLabel(value: String): Boolean {
        return value.isNotBlank() && value !in GENERIC_CARGO_LABELS
    }

    private fun currentLanguage(): AppLanguage = _uiState.value.appLanguage

    private fun ScannerSyncSettings.isValid(): Boolean {
        return apiBaseUrl.isNotBlank() && bearerToken.isNotBlank() && deviceId.isNotBlank()
    }

    companion object {
        private const val SCANNER_REFERENCE_REFRESH_INTERVAL_MS = 120_000L
        private const val SCANNER_SOURCE_LOCAL = "LOCAL"
        private const val SCANNER_RECORD_NOT_FOUND = "NOT_FOUND"
        private const val SCANNER_RECORD_ERROR = "ERROR"
        private const val SCANNER_STATUS_UNKNOWN = "UNKNOWN"
        private const val SCANNER_STATUS_ERROR = "ERROR"
        private const val SCANNER_STATUS_INVALID_FORMAT = "INVALID_BARCODE_FORMAT"
        private val GENERIC_CARGO_LABELS = setOf(
            "Tracked cargo",
            "Pallet candidate",
            "Wood pallet base",
            "Unknown",
            "Unclassified",
        )
    }
}

class AppViewModelFactory(
    private val repository: PilotRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val startupSnapshot: AppStartupSnapshot,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(repository, preferencesRepository, startupSnapshot) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

