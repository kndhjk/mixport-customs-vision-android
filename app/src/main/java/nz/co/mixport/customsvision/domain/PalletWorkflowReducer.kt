package nz.co.mixport.customsvision.domain

class PalletWorkflowReducer {
    fun reduce(
        current: WorkflowState,
        event: WorkflowEvent,
    ): WorkflowTransition = when (event) {
        is WorkflowEvent.PalletArrived -> handlePalletArrived(current, event)
        is WorkflowEvent.CargoPlaced -> handleCargoPlaced(current, event)
        is WorkflowEvent.PalletWrapped -> handlePalletWrapped(current, event)
        is WorkflowEvent.ContainerContentUpdated -> handleContainerContentUpdated(current, event)
    }

    private fun handlePalletArrived(
        current: WorkflowState,
        event: WorkflowEvent.PalletArrived,
    ): WorkflowTransition {
        if (current.activePalletSequence != null) {
            return WorkflowTransition(current, emptyList())
        }

        val sequence = current.nextPalletSequence
        return WorkflowTransition(
            state = current.copy(
                phase = SessionPhase.LOADING,
                activePalletSequence = sequence,
            ),
            actions = listOf(
                WorkflowAction.OpenPallet(sequence, event.observedAt),
                WorkflowAction.Log(
                    eventType = "PALLET_DETECTED",
                    message = "Pallet #$sequence detected and ready for loading.",
                    payloadJson = """{"palletSequence":$sequence}""",
                    recordedAt = event.observedAt,
                ),
            ),
        )
    }

    private fun handleCargoPlaced(
        current: WorkflowState,
        event: WorkflowEvent.CargoPlaced,
    ): WorkflowTransition {
        val sequence = current.activePalletSequence ?: current.nextPalletSequence
        val tallies = current.currentCounts.toMutableList()
        val existingIndex = tallies.indexOfFirst {
            it.itemLabel == event.itemLabel &&
                it.colorName == event.colorName &&
                it.markerText == event.markerText
        }

        if (existingIndex >= 0) {
            val existing = tallies[existingIndex]
            tallies[existingIndex] = existing.copy(quantity = existing.quantity + event.quantity)
        } else {
            tallies += ItemTally(
                itemLabel = event.itemLabel,
                colorName = event.colorName,
                markerText = event.markerText,
                quantity = event.quantity,
            )
        }

        val actions = buildList {
            if (current.activePalletSequence == null) {
                add(WorkflowAction.OpenPallet(sequence, event.observedAt))
                add(
                    WorkflowAction.Log(
                        eventType = "PALLET_IMPLICITLY_OPENED",
                        message = "Pallet #$sequence opened after the first cargo detection.",
                        payloadJson = """{"palletSequence":$sequence}""",
                        recordedAt = event.observedAt,
                    ),
                )
            }
            add(
                WorkflowAction.IncrementItem(
                    sequenceNumber = sequence,
                    itemLabel = event.itemLabel,
                    colorName = event.colorName,
                    markerText = event.markerText,
                    quantity = event.quantity,
                    recordedAt = event.observedAt,
                ),
            )
            add(
                WorkflowAction.Log(
                    eventType = "CARGO_COUNTED",
                    message = "${event.itemLabel} counted on pallet #$sequence.",
                    payloadJson =
                        """{"palletSequence":$sequence,"itemLabel":"${event.itemLabel}","color":"${event.colorName}","marker":"${event.markerText}","quantity":${event.quantity}}""",
                    recordedAt = event.observedAt,
                ),
            )
        }

        return WorkflowTransition(
            state = current.copy(
                phase = SessionPhase.LOADING,
                activePalletSequence = sequence,
                currentCounts = tallies,
            ),
            actions = actions,
        )
    }

    private fun handlePalletWrapped(
        current: WorkflowState,
        event: WorkflowEvent.PalletWrapped,
    ): WorkflowTransition {
        val sequence = current.activePalletSequence ?: return WorkflowTransition(current, emptyList())
        val nextPhase = if (current.containerHasRemainingCargo) {
            SessionPhase.WAITING_FOR_PALLET
        } else {
            SessionPhase.READY_TO_COMPLETE
        }

        val actions = buildList {
            add(
                WorkflowAction.Log(
                    eventType = "PALLET_WRAPPED",
                    message = "Pallet #$sequence sealed and archived.",
                    payloadJson = """{"palletSequence":$sequence}""",
                    recordedAt = event.observedAt,
                ),
            )
            add(
                WorkflowAction.ClosePallet(
                    sequenceNumber = sequence,
                    closedAt = event.observedAt,
                    containerEmptyAtClose = !current.containerHasRemainingCargo,
                ),
            )
            if (!current.containerHasRemainingCargo) {
                add(WorkflowAction.MarkSessionReady)
            }
        }

        return WorkflowTransition(
            state = current.copy(
                phase = nextPhase,
                activePalletSequence = null,
                nextPalletSequence = sequence + 1,
                currentCounts = emptyList(),
                lastWrappedPalletSequence = sequence,
            ),
            actions = actions,
        )
    }

    private fun handleContainerContentUpdated(
        current: WorkflowState,
        event: WorkflowEvent.ContainerContentUpdated,
    ): WorkflowTransition {
        val nextPhase = when {
            current.activePalletSequence != null -> SessionPhase.LOADING
            event.hasRemainingCargo -> SessionPhase.WAITING_FOR_PALLET
            else -> SessionPhase.READY_TO_COMPLETE
        }
        val actions = buildList {
            add(WorkflowAction.SetContainerHasRemainingCargo(event.hasRemainingCargo))
            add(
                WorkflowAction.Log(
                    eventType = "CONTAINER_STATUS",
                    message = if (event.hasRemainingCargo) {
                        "Container still has cargo to unload."
                    } else {
                        "Container looks empty. Current session can be closed after the last pallet is sealed."
                    },
                    payloadJson = """{"hasRemainingCargo":${event.hasRemainingCargo}}""",
                    recordedAt = event.observedAt,
                ),
            )
            if (!event.hasRemainingCargo && current.activePalletSequence == null) {
                add(WorkflowAction.MarkSessionReady)
            }
        }
        return WorkflowTransition(
            state = current.copy(
                phase = nextPhase,
                containerHasRemainingCargo = event.hasRemainingCargo,
            ),
            actions = actions,
        )
    }
}
