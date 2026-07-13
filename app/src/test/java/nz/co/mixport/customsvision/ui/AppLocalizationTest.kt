package nz.co.mixport.customsvision.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocalizationTest {
    @Test
    fun `blank clearance status defaults to hold`() {
        assertEquals("HOLD", canonicalScannerClearanceStatus(null))
        assertEquals("HOLD", canonicalScannerClearanceStatus(""))
    }

    @Test
    fun `failed beats hold and clear`() {
        assertEquals("FAILED", overallScannerClearanceStatus("failed", "clear"))
        assertEquals("FAILED", overallScannerClearanceStatus("clear", "failed"))
        assertEquals("FAILED", overallScannerClearanceStatus("failed", "hold"))
    }

    @Test
    fun `both nzcs and mpi must clear before shipment turns green`() {
        assertEquals("HOLD", overallScannerClearanceStatus("clear", "hold"))
        assertEquals("HOLD", overallScannerClearanceStatus("hold", "clear"))
        assertEquals("HOLD", overallScannerClearanceStatus("hold", "hold"))
        assertEquals("CLEAR", overallScannerClearanceStatus("clear", "clear"))
    }
}
