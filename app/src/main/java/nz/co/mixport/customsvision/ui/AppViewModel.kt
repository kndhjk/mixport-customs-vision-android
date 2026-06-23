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
import nz.co.mixport.customsvision.data.CargoSummaryRecord
import nz.co.mixport.customsvision.data.EventLogRecord
import nz.co.mixport.customsvision.data.InspectionSessionRecord
import nz.co.mixport.customsvision.data.PalletDetail
import nz.co.mixport.customsvision.data.PilotRepository
import nz.co.mixport.customsvision.data.SessionDraft
import nz.co.mixport.customsvision.domain.PalletWorkflowReducer
import nz.co.mixport.customsvision.domain.SessionPhase
import nz.co.mixport.customsvision.domain.WorkflowAction
import nz.co.mixport.customsvision.domain.WorkflowEvent
import nz.co.mixport.customsvision.domain.WorkflowState

enum class AppDestination {
    LIVE,
    HISTORY,
}

data class LiveInspectionUiState(
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
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)

class AppViewModel(
    private val repository: PilotRepository,
) : ViewModel() {
    private val reducer = PalletWorkflowReducer()
    private val _uiState = MutableStateFlow(LiveInspectionUiState())
    val uiState: StateFlow<LiveInspectionUiState> = _uiState.asStateFlow()
    private val countedTrackingIds = linkedSetOf<Int>()

    init {
        refreshHistory()
    }

    fun selectDestination(destination: AppDestination) {
        _uiState.update { it.copy(selectedDestination = destination) }
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

    fun onFrameHeartbeat(heartbeatAt: Long) {
        _uiState.update { it.copy(lastFrameHeartbeatAt = heartbeatAt) }
    }

    fun onDetections(frame: LiveDetectionFrame) {
        _uiState.update { state ->
            state.copy(
                lastFrameHeartbeatAt = frame.analyzedAt,
                liveDetections = frame.detections.map(::markCountedState),
            )
        }
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
                it.copy(errorMessage = "Container code and operator are required before the session can start.")
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
                infoMessage = "Session ${session.containerCode} started.",
            )
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
                    isRecording = false,
                    infoMessage = "Session closed.",
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
            _uiState.update { it.copy(errorMessage = "Start a session before counting tracked objects.") }
            return
        }

        viewModelScope.launch {
            var latestSnapshot = _uiState.value
            if (latestSnapshot.workflowState.activePalletSequence == null) {
                val hasVisiblePallet = latestSnapshot.liveDetections.any { it.isPalletCandidate }
                if (!hasVisiblePallet) {
                    _uiState.update {
                        it.copy(errorMessage = "No pallet candidate is visible yet. Aim the camera at the pallet first.")
                    }
                    return@launch
                }
                applyWorkflowEvent(WorkflowEvent.PalletArrived(System.currentTimeMillis()))
                latestSnapshot = _uiState.value
            }

            val countableDetections = latestSnapshot.liveDetections.filter { detection ->
                !detection.isPalletCandidate &&
                    detection.trackingId != null &&
                    !countedTrackingIds.contains(detection.trackingId)
            }

            if (countableDetections.isEmpty()) {
                _uiState.update {
                    it.copy(
                        infoMessage = "No new tracked cargo is ready to count.",
                        errorMessage = null,
                    )
                }
                return@launch
            }

            countableDetections.forEach { detection ->
                detection.trackingId?.let(countedTrackingIds::add)
                applyWorkflowEvent(
                    WorkflowEvent.CargoPlaced(
                        itemLabel = detection.label,
                        colorName = "Unclassified",
                        markerText = detection.category.takeIf { it != "Unknown" }.orEmpty(),
                        observedAt = System.currentTimeMillis(),
                    ),
                )
            }

            _uiState.update {
                it.copy(
                    liveDetections = it.liveDetections.map(::markCountedState),
                    infoMessage = "${countableDetections.size} tracked object(s) counted from the live view.",
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
            _uiState.update { it.copy(errorMessage = "Start a session before processing detections.") }
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
                    infoMessage = "${action.itemLabel} counted."
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
                        infoMessage = "Pallet #${action.sequenceNumber} archived."
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
}

class AppViewModelFactory(
    private val repository: PilotRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

