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
import nz.co.mixport.customsvision.data.PalletDetail
import nz.co.mixport.customsvision.data.PilotRepository
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecordDetail
import nz.co.mixport.customsvision.data.ScannerRecord
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
    val isProvisioned: Boolean = false,
    val deviceId: String = "",
    val networkAvailable: Boolean? = null,
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
        get() = isProvisioned && deviceId.isNotBlank()
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
    val selectedHistoryRecord: ScannerRecord? = null,
    val selectedHistoryDetail: ScannerRecordDetail? = null,
    val isHistoryDetailLoading: Boolean = false,
    val historyDetailError: String? = null,
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
                } ?: waitingScannerMessage(startupSnapshot.appLanguage),
                history = startupSnapshot.scannerHistory,
                lastProcessedBarcode = initialScannerRecord?.scannedBarcode,
                sync = ScannerSyncUiState(
                    isProvisioned = startupSnapshot.scannerSyncProvisioned,
                    deviceId = startupSnapshot.scannerDeviceId,
                    lastReferenceSyncAt = preferencesRepository.getScannerLastReferenceSyncAt(),
                    lastUploadAt = preferencesRepository.getScannerLastUploadAt(),
                    lastUploadBatchId = preferencesRepository.getScannerLastUploadBatchId(),
                ),
            ),
        ),
    )
    val uiState: StateFlow<LiveInspectionUiState> = _uiState.asStateFlow()
    private val trackCountEngine = LiveTrackCountEngine(loadedInspectionTuning.profile)
    private val scannerWorkflowController = ScannerWorkflowController(
        repository = repository,
        preferencesRepository = preferencesRepository,
        state = _uiState,
        scope = viewModelScope,
        activeOperatorName = ::activeOperatorName,
    )

    init {
        refreshHistory()
        scannerWorkflowController.refreshScannerSyncStatus()
    }

    fun selectDestination(destination: AppDestination) {
        _uiState.update { it.copy(selectedDestination = destination) }
        if (destination == AppDestination.SCANNER) {
            scannerWorkflowController.refreshScannerConnectionState()
            scannerWorkflowController.autoRefreshScannerReferences(force = true)
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

            currentState.scanner.lastResult == ScannerMatchStatus.WAITING -> waitingScannerMessage(nextLanguage)

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
        scannerWorkflowController.clearScannerHistory()
    }

    fun prepareScannerForNextScan() {
        scannerWorkflowController.prepareScannerForNextScan()
    }

    fun showScannerHistoryDetail(record: ScannerRecord) {
        scannerWorkflowController.showScannerHistoryDetail(record)
    }

    fun dismissScannerHistoryDetail() {
        scannerWorkflowController.dismissScannerHistoryDetail()
    }

    fun refreshScannerReferences(
        manual: Boolean = true,
        force: Boolean = false,
    ) {
        scannerWorkflowController.refreshScannerReferences(manual, force)
    }

    fun uploadPendingScannerScans() {
        scannerWorkflowController.uploadPendingScannerScans()
    }

    fun refreshScannerConnectionState() {
        scannerWorkflowController.refreshScannerConnectionState()
    }

    fun maybeAutoUploadPendingScannerScans() {
        scannerWorkflowController.maybeAutoUploadPendingScannerScans()
    }

    fun verifyScannerBarcode(barcode: String = _uiState.value.scanner.barcodeInput) {
        scannerWorkflowController.verifyScannerBarcode(barcode)
    }

    fun onScannerPdaDetected(
        barcode: String,
        codeType: String,
    ) {
        scannerWorkflowController.onScannerPdaDetected(barcode, codeType)
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
                    "正在结合 OCR、颜色和目标标签分析可见货物...",
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
                        "当前画面中没有识别到可确认的货物。",
                    )
                } else {
                    it.appLanguage.pick(
                        "Analyzed ${decoratedSnapshot.items.size} visible cargo item(s).",
                        "已分析 ${decoratedSnapshot.items.size} 个可见货物对象。",
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
                        "开始作业前必须先填写柜号和操作员。",
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
                    "作业 ${session.containerCode} 已开始。",
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
                    infoMessage = it.appLanguage.pick("Session closed.", "作业已关闭。"),
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
                        "请先开始作业，再统计跟踪到的货物。",
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
                                "当前还没有看到托盘候选，请先将镜头对准托盘。",
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
                            "当前没有新的跟踪货物可计数。",
                        ),
                        errorMessage = null,
                    )
                }
                if (stabilizingCount > 0) {
                    _uiState.update {
                        it.copy(
                            infoMessage = it.appLanguage.pick(
                                "$stabilizingCount tracked object(s) are still stabilizing. Hold the camera steady for another moment.",
                                "$stabilizingCount 个跟踪对象仍在稳定中，请再稳住镜头片刻。",
                            ),
                            errorMessage = null,
                        )
                    }
                } else if (awaitingPlacementCount > 0) {
                    _uiState.update {
                        it.copy(
                            infoMessage = it.appLanguage.pick(
                                "$awaitingPlacementCount tracked object(s) are visible but not yet sitting on the pallet zone.",
                                "$awaitingPlacementCount 个跟踪对象已出现，但还没有放到托盘区域内。",
                            ),
                            errorMessage = null,
                        )
                    }
                } else if (awaitingAnalysisCount > 0) {
                    _uiState.update {
                        it.copy(
                            infoMessage = it.appLanguage.pick(
                                "$awaitingAnalysisCount stable object(s) need richer OCR or label evidence before counting.",
                                "$awaitingAnalysisCount 个稳定对象在计数前还需要更充分的 OCR 或标签证据。",
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
                        "已从实时画面计数 ${countableDetections.size} 个跟踪对象。",
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
                        "请先开始作业，再处理识别结果。",
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
                        "${action.itemLabel} 已计数。",
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
                            "托盘 #${action.sequenceNumber} 已归档。",
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

    companion object {
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

