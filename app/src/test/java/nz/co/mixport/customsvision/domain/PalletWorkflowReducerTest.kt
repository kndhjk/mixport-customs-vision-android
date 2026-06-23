package nz.co.mixport.customsvision.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalletWorkflowReducerTest {
    private val reducer = PalletWorkflowReducer()

    @Test
    fun `closing last sealed pallet makes session ready`() {
        val initial = WorkflowState(containerHasRemainingCargo = false)

        val loading = reducer.reduce(
            initial,
            WorkflowEvent.CargoPlaced(
                itemLabel = "Box",
                colorName = "Blue",
                markerText = "NZCS",
                observedAt = 1L,
            ),
        )

        val wrapped = reducer.reduce(
            loading.state,
            WorkflowEvent.PalletWrapped(observedAt = 2L),
        )

        assertEquals(SessionPhase.READY_TO_COMPLETE, wrapped.state.phase)
        assertEquals(null, wrapped.state.activePalletSequence)
        assertTrue(wrapped.actions.any { it is WorkflowAction.MarkSessionReady })
    }

    @Test
    fun `container still having cargo returns reducer to waiting state after wrap`() {
        val initial = WorkflowState(containerHasRemainingCargo = true)
        val loading = reducer.reduce(
            initial,
            WorkflowEvent.PalletArrived(observedAt = 1L),
        )

        val wrapped = reducer.reduce(
            loading.state,
            WorkflowEvent.PalletWrapped(observedAt = 2L),
        )

        assertEquals(SessionPhase.WAITING_FOR_PALLET, wrapped.state.phase)
        assertEquals(2, wrapped.state.nextPalletSequence)
    }
}

