package nz.co.mixport.customsvision.domain

enum class SessionPhase {
    WAITING_FOR_PALLET,
    LOADING,
    READY_TO_COMPLETE,
    CLOSED,
}

data class ItemTally(
    val itemLabel: String,
    val colorName: String,
    val markerText: String,
    val quantity: Int,
)

data class WorkflowState(
    val phase: SessionPhase = SessionPhase.WAITING_FOR_PALLET,
    val activePalletSequence: Int? = null,
    val nextPalletSequence: Int = 1,
    val containerHasRemainingCargo: Boolean = true,
    val currentCounts: List<ItemTally> = emptyList(),
    val lastWrappedPalletSequence: Int? = null,
)

sealed interface WorkflowEvent {
    data class PalletArrived(val observedAt: Long) : WorkflowEvent
    data class CargoPlaced(
        val itemLabel: String,
        val colorName: String,
        val markerText: String,
        val quantity: Int = 1,
        val observedAt: Long,
        val trackKey: String = "",
        val stableFrameCount: Int = 0,
        val detectionConfidence: Float? = null,
        val recognitionConfidence: Float? = null,
    ) : WorkflowEvent

    data class PalletWrapped(val observedAt: Long) : WorkflowEvent
    data class ContainerContentUpdated(
        val hasRemainingCargo: Boolean,
        val observedAt: Long,
    ) : WorkflowEvent
}

sealed interface WorkflowAction {
    data class OpenPallet(
        val sequenceNumber: Int,
        val startedAt: Long,
    ) : WorkflowAction

    data class IncrementItem(
        val sequenceNumber: Int,
        val itemLabel: String,
        val colorName: String,
        val markerText: String,
        val quantity: Int,
        val recordedAt: Long,
    ) : WorkflowAction

    data class Log(
        val eventType: String,
        val message: String,
        val payloadJson: String,
        val recordedAt: Long,
    ) : WorkflowAction

    data class ClosePallet(
        val sequenceNumber: Int,
        val closedAt: Long,
        val containerEmptyAtClose: Boolean,
    ) : WorkflowAction

    data class SetContainerHasRemainingCargo(
        val hasRemaining: Boolean,
    ) : WorkflowAction

    data object MarkSessionReady : WorkflowAction
}

data class WorkflowTransition(
    val state: WorkflowState,
    val actions: List<WorkflowAction>,
)
