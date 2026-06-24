package nz.co.mixport.customsvision.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.co.mixport.customsvision.camera.LiveDetectionFrame
import nz.co.mixport.customsvision.camera.LiveRecognition
import nz.co.mixport.customsvision.camera.UniversalRecognition
import nz.co.mixport.customsvision.camera.UniversalRecognitionSnapshot
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.AppPreferencesRepository
import nz.co.mixport.customsvision.data.BarcodeLookupResult
import nz.co.mixport.customsvision.data.CargoSummaryRecord
import nz.co.mixport.customsvision.data.EventLogRecord
import nz.co.mixport.customsvision.data.InspectionSessionRecord
import nz.co.mixport.customsvision.data.PalletDetail
import nz.co.mixport.customsvision.data.PilotRepository
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord
import nz.co.mixport.customsvision.data.SessionDraft
import nz.co.mixport.customsvision.domain.PalletWorkflowReducer
import nz.co.mixport.customsvision.domain.SessionPhase
import nz.co.mixport.customsvision.domain.WorkflowAction
import nz.co.mixport.customsvision.domain.WorkflowEvent
import nz.co.mixport.customsvision.domain.WorkflowState

enum class AppDestination {
    LIVE,
    SCANNER,
    HISTORY,
}

data class ScannerUiState(
    val barcodeInput: String = "",
    val isAutoVerifyEnabled: Boolean = true,
    val isSoundEnabled: Boolean = true,
    val onboardingDismissed: Boolean = false,
    val isProcessing: Boolean = false,
    val lastResult: ScannerMatchStatus = ScannerMatchStatus.WAITING,
    val statusMessage: String? = null,
    val history: List<ScannerRecord> = emptyList(),
    val lastProcessedBarcode: String? = null,
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
) : ViewModel() {
    private val reducer = PalletWorkflowReducer()
    private val _uiState = MutableStateFlow(
        LiveInspectionUiState(
            appLanguage = preferencesRepository.getLanguage(),
            scanner = ScannerUiState(
                isAutoVerifyEnabled = preferencesRepository.isScannerAutoVerifyEnabled(),
                isSoundEnabled = preferencesRepository.isScannerSoundEnabled(),
                onboardingDismissed = preferencesRepository.isScannerOnboardingDismissed(),
                history = preferencesRepository.getScannerHistory(),
            ),
        ),
    )
    val uiState: StateFlow<LiveInspectionUiState> = _uiState.asStateFlow()
    private val countedTrackingIds = linkedSetOf<Int>()

    init {
        refreshHistory()
    }

    fun selectDestination(destination: AppDestination) {
        _uiState.update { it.copy(selectedDestination = destination) }
    }

    fun toggleLanguage() {
        val nextLanguage = if (_uiState.value.appLanguage == AppLanguage.ENGLISH) {
            AppLanguage.CHINESE
        } else {
            AppLanguage.ENGLISH
        }
        preferencesRepository.setLanguage(nextLanguage)
        _uiState.update { it.copy(appLanguage = nextLanguage) }
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
                    barcodeInput = value.take(48),
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
                ),
            )
        }
    }

    fun verifyScannerBarcode(barcode: String = _uiState.value.scanner.barcodeInput) {
        val normalized = barcode.trim().uppercase()
        if (normalized.isBlank()) {
            _uiState.update {
                it.copy(
                    scanner = it.scanner.copy(
                        lastResult = ScannerMatchStatus.ERROR,
                        statusMessage = it.appLanguage.pick(
                            "Please scan or enter a barcode first.",
                            "请先扫描或输入条码。",
                        ),
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
                        "正在验证条码...",
                    ),
                ),
            )
        }

        viewModelScope.launch {
            val verifiedAt = System.currentTimeMillis()
            val record = runCatching {
                buildScannerRecord(
                    barcode = normalized,
                    lookupResult = lookupBarcode(normalized),
                    scannedAt = verifiedAt,
                )
            }.getOrElse { throwable ->
                ScannerRecord(
                    scannedBarcode = normalized,
                    databaseRecord = localizedErrorRecord(),
                    matchStatus = ScannerMatchStatus.ERROR,
                    status = throwable.message ?: currentLanguage().pick("Error", "错误"),
                    source = "LOCAL",
                    scannedAt = verifiedAt,
                )
            }

            val updatedHistory = listOf(record) + _uiState.value.scanner.history
                .filterNot { it.scannedBarcode == record.scannedBarcode && it.scannedAt == record.scannedAt }
                .take(59)
            preferencesRepository.setScannerHistory(updatedHistory)

            _uiState.update {
                it.copy(
                    scanner = it.scanner.copy(
                        barcodeInput = "",
                        isProcessing = false,
                        lastResult = record.matchStatus,
                        statusMessage = scannerMessageFor(record),
                        history = updatedHistory,
                        lastProcessedBarcode = record.scannedBarcode,
                    ),
                )
            }
        }
    }

    fun onFrameHeartbeat(heartbeatAt: Long) {
        _uiState.update { it.copy(lastFrameHeartbeatAt = heartbeatAt) }
    }

    fun onDetections(frame: LiveDetectionFrame) {
        _uiState.update { state ->
            state.copy(
                lastFrameHeartbeatAt = frame.analyzedAt,
                liveDetections = frame.detections.map(::markCountedState),
                universalRecognitionSnapshot = state.universalRecognitionSnapshot?.copy(
                    items = state.universalRecognitionSnapshot.items.map(::markCountedRecognitionState),
                ),
            )
        }
    }

    fun onUniversalRecognitionStarted() {
        _uiState.update {
            it.copy(
                isUniversalRecognitionRunning = true,
                infoMessage = it.appLanguage.pick(
                    "Analyzing visible cargo with OCR, color, and target labels...",
                    "正在结合 OCR、颜色和目标类别识别可见货物...",
                ),
                errorMessage = null,
            )
        }
    }

    fun onUniversalRecognitionCompleted(snapshot: UniversalRecognitionSnapshot) {
        _uiState.update {
            it.copy(
                isUniversalRecognitionRunning = false,
                universalRecognitionSnapshot = snapshot.copy(
                    items = snapshot.items.map(::markCountedRecognitionState),
                ),
                infoMessage = if (snapshot.items.isEmpty()) {
                    it.appLanguage.pick(
                        "No recognizable cargo was found in the current view.",
                        "当前画面里没有识别出明确货物。",
                    )
                } else {
                    it.appLanguage.pick(
                        "Analyzed ${snapshot.items.size} visible cargo item(s).",
                        "已分析 ${snapshot.items.size} 个可见货物对象。",
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
                infoMessage = "Video recording saved.",
                isRecording = false,
                recordingUri = recordingUri,
            )
        }
    }

    fun onRecordingStarted() {
        _uiState.update { it.copy(isRecording = true, infoMessage = "Recording started.", errorMessage = null) }
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
                        "开始作业前必须填写柜号和操作员。",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            countedTrackingIds.clear()
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
            countedTrackingIds.clear()
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
                        "开始作业后才能统计跟踪到的货物。",
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            var latestSnapshot = _uiState.value
            val palletRecognitionTrackingIds = latestSnapshot.universalRecognitionSnapshot?.items
                ?.mapNotNull { recognition ->
                    recognition.trackingId?.takeIf { recognition.isPalletLike }
                }
                ?.toSet()
                .orEmpty()
            if (latestSnapshot.workflowState.activePalletSequence == null) {
                val hasVisiblePallet = latestSnapshot.liveDetections.any { detection ->
                    detection.isPalletCandidate ||
                        detection.trackingId != null && palletRecognitionTrackingIds.contains(detection.trackingId)
                }
                if (!hasVisiblePallet) {
                    _uiState.update {
                        it.copy(
                            errorMessage = it.appLanguage.pick(
                                "No pallet candidate is visible yet. Aim the camera at the pallet first.",
                                "当前还没有看到托盘候选，请先把镜头对准托盘。",
                            ),
                        )
                    }
                    return@launch
                }
                applyWorkflowEvent(WorkflowEvent.PalletArrived(System.currentTimeMillis()))
                latestSnapshot = _uiState.value
            }

            val countableDetections = latestSnapshot.liveDetections.filter { detection ->
                !detection.isPalletCandidate &&
                    (detection.trackingId == null || !palletRecognitionTrackingIds.contains(detection.trackingId)) &&
                    detection.trackingId != null &&
                    !countedTrackingIds.contains(detection.trackingId)
            }

            if (countableDetections.isEmpty()) {
                _uiState.update {
                    it.copy(
                        infoMessage = it.appLanguage.pick(
                            "No new tracked cargo is ready to count.",
                            "当前没有新的可统计货物。",
                        ),
                        errorMessage = null,
                    )
                }
                return@launch
            }

            val recognitionByTrackingId = latestSnapshot.universalRecognitionSnapshot?.items
                ?.mapNotNull { insight ->
                    insight.trackingId?.let { trackingId -> trackingId to insight }
                }
                ?.toMap()
                .orEmpty()

            countableDetections.forEach { detection ->
                val recognition = detection.trackingId?.let(recognitionByTrackingId::get)
                detection.trackingId?.let(countedTrackingIds::add)
                applyWorkflowEvent(
                    WorkflowEvent.CargoPlaced(
                        itemLabel = recognition?.bestLabel?.ifBlank { detection.label } ?: detection.label,
                        colorName = recognition?.dominantColor ?: "Unclassified",
                        markerText = recognition?.markerText?.ifBlank {
                            detection.category.takeIf { it != "Unknown" }.orEmpty()
                        } ?: detection.category.takeIf { it != "Unknown" }.orEmpty(),
                        observedAt = System.currentTimeMillis(),
                    ),
                )
            }

            _uiState.update {
                it.copy(
                    liveDetections = it.liveDetections.map(::markCountedState),
                    universalRecognitionSnapshot = it.universalRecognitionSnapshot?.copy(
                        items = it.universalRecognitionSnapshot.items.map(::markCountedRecognitionState),
                    ),
                    infoMessage = it.appLanguage.pick(
                        "${countableDetections.size} tracked object(s) counted from the live view.",
                        "已从实时画面统计 ${countableDetections.size} 个跟踪对象。",
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

        _uiState.update {
            it.copy(
                activeSession = session,
                workflowState = workflowState,
                currentPalletId = currentPalletId,
                currentPalletItems = currentItems,
                sealedPallets = pallets.filter { detail -> detail.pallet.closedAt != null },
                recentEvents = events,
                history = history,
                liveDetections = it.liveDetections.map(::markCountedState),
                infoMessage = infoMessage,
                errorMessage = null,
                isRecording = isRecording,
                recordingUri = recordingUri ?: session?.recordingUri,
            )
        }
    }

    private fun markCountedState(detection: LiveRecognition): LiveRecognition {
        return detection.copy(
            isCounted = detection.trackingId != null && countedTrackingIds.contains(detection.trackingId),
        )
    }

    private fun markCountedRecognitionState(recognition: UniversalRecognition): UniversalRecognition {
        return recognition.copy(
            isCounted = recognition.trackingId != null && countedTrackingIds.contains(recognition.trackingId),
        )
    }

    private suspend fun lookupBarcode(barcode: String): BarcodeLookupResult? {
        if (!BARCODE_PATTERN.matches(barcode)) {
            return null
        }
        return repository.lookupBarcode(barcode)
    }

    private fun buildScannerRecord(
        barcode: String,
        lookupResult: BarcodeLookupResult?,
        scannedAt: Long,
    ): ScannerRecord {
        return when {
            !BARCODE_PATTERN.matches(barcode) -> {
                ScannerRecord(
                    scannedBarcode = barcode,
                    databaseRecord = localizedErrorRecord(),
                    matchStatus = ScannerMatchStatus.ERROR,
                    status = currentLanguage().pick("Invalid barcode format", "条码格式无效"),
                    source = "LOCAL",
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
                )
            }

            else -> {
                ScannerRecord(
                    scannedBarcode = barcode,
                    databaseRecord = currentLanguage().pick("Not found", "未找到"),
                    matchStatus = ScannerMatchStatus.MISMATCH,
                    status = currentLanguage().pick("Unknown", "未知"),
                    source = "LOCAL",
                    scannedAt = scannedAt,
                )
            }
        }
    }

    private fun scannerMessageFor(record: ScannerRecord): String {
        return when (record.matchStatus) {
            ScannerMatchStatus.MATCHED -> currentLanguage().pick(
                "\"${record.scannedBarcode}\" verified successfully.",
                "条码“${record.scannedBarcode}”验证成功。",
            )

            ScannerMatchStatus.MISMATCH -> currentLanguage().pick(
                "\"${record.scannedBarcode}\" was not found.",
                "未找到条码“${record.scannedBarcode}”。",
            )

            ScannerMatchStatus.ERROR -> currentLanguage().pick(
                "\"${record.scannedBarcode}\" could not be verified.",
                "条码“${record.scannedBarcode}”无法验证。",
            )

            ScannerMatchStatus.WAITING -> currentLanguage().pick(
                "Waiting for barcode input.",
                "等待条码输入。",
            )
        }
    }

    private fun localizedErrorRecord(): String {
        return currentLanguage().pick("Error", "错误")
    }

    private fun currentLanguage(): AppLanguage = _uiState.value.appLanguage

    companion object {
        private val BARCODE_PATTERN = Regex("^[A-Z0-9_\\-/]{6,40}$")
    }
}

class AppViewModelFactory(
    private val repository: PilotRepository,
    private val preferencesRepository: AppPreferencesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(repository, preferencesRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

