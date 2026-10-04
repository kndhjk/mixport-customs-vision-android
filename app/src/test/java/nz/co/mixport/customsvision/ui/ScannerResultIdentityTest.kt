package nz.co.mixport.customsvision.ui

import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerResultIdentityTest {
    private val older = ScannerRecord(
        scannedBarcode = "OLD-CLEAR",
        databaseRecord = "OLD-CLEAR",
        matchStatus = ScannerMatchStatus.MATCHED,
        status = "clear",
        source = "SERVER_LIVE",
        scannedAt = 100L,
        localLogId = 1L,
    )
    private val newer = older.copy(
        scannedBarcode = "NEW-HOLD",
        databaseRecord = "NEW-HOLD",
        scannedAt = 200L,
        localLogId = 2L,
    )

    @Test
    fun `late refresh cannot replace a newer scan`() {
        val current = ScannerUiState(
            history = listOf(newer, older),
            lastProcessedBarcode = newer.scannedBarcode,
            lastResult = ScannerMatchStatus.MATCHED,
        )
        assertFalse(isCurrentScannerResult(current, older))
        assertTrue(isCurrentScannerResult(current, newer))
    }

    @Test
    fun `refresh cannot restore an old clear result while waiting or verifying`() {
        val previous = ScannerUiState(
            history = listOf(older),
            lastProcessedBarcode = older.scannedBarcode,
            lastResult = ScannerMatchStatus.MATCHED,
        )
        assertFalse(isCurrentScannerResult(previous.copy(lastProcessedBarcode = null, lastResult = ScannerMatchStatus.WAITING), older))
        assertFalse(isCurrentScannerResult(previous.copy(isProcessing = true), older))
    }

    @Test
    fun `same barcode rescanned must use the latest event`() {
        val repeated = older.copy(scannedAt = 300L, localLogId = 3L)
        val current = ScannerUiState(
            history = listOf(repeated, older),
            lastProcessedBarcode = older.scannedBarcode,
            lastResult = ScannerMatchStatus.MATCHED,
        )
        assertFalse(isCurrentScannerResult(current, older))
        assertTrue(isCurrentScannerResult(current, repeated))
    }
}
