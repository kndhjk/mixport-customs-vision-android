package nz.co.mixport.customsvision.ui

import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord

internal fun isCurrentScannerResult(scanner: ScannerUiState, expected: ScannerRecord): Boolean {
    val current = scanner.history.firstOrNull() ?: return false
    return !scanner.isProcessing &&
        scanner.lastResult != ScannerMatchStatus.WAITING &&
        scanner.lastProcessedBarcode == expected.scannedBarcode &&
        current.scannedBarcode == expected.scannedBarcode &&
        current.scannedAt == expected.scannedAt &&
        (expected.localLogId == null || current.localLogId == expected.localLogId)
}
