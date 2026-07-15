package nz.co.mixport.customsvision.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import nz.co.mixport.customsvision.data.BarcodeLookupResult
import nz.co.mixport.customsvision.data.ScannerMatchStatus

class ScannerRecordSnapshotTest {

    @Test
    fun buildScannerRecord_keepsLookupSnapshotForMatchedBarcode() {
        val lookup = BarcodeLookupResult(
            found = true,
            databaseRecord = "VAN2026061615",
            status = "CLEAR",
            source = "SERVER",
            parentHblNo = "VAN2026061615",
            matchedChildHbl = "VAN1413050612",
            matchedBarcodeCode = "PTS0261016852",
            matchedBy = "scan_hbl",
            childHbls = "VAN1413050612,VAN1413050054",
            barcodeCodes = "PTS0261016852",
            containerNo = "MSCU1234567",
            vesselName = "PACIFIC TRADER",
            company = "Mixport",
            customerName = "Wing",
            location = "A1",
            pkgs = 4,
            outTurnQty = 2,
            submissionDate = "2026-07-15",
            customersStatus = "CLEAR",
            mpiStatus = "HOLD",
            serverScanCount = 3,
            scannerTargetMode = "pkgs",
            scannerExpectedScanCount = 4,
            scannerCompletedScanCount = 3,
            scannerRemainingScanCount = 1,
        )

        val record = buildScannerRecord(
            barcode = "pts0261016852",
            lookupResult = lookup,
            scannedAt = 123456789L,
        )

        assertEquals(ScannerMatchStatus.MATCHED, record.matchStatus)
        assertNotNull(record.lookupSnapshot)
        assertEquals("VAN1413050612", record.lookupSnapshot?.matchedChildHbl)
        assertEquals(4, record.lookupSnapshot?.scannerExpectedScanCount)
        assertEquals("CLEAR", record.customersStatus)
        assertEquals("HOLD", record.mpiStatus)
    }

    @Test
    fun buildScannerRecord_doesNotAttachSnapshotForMismatches() {
        val record = buildScannerRecord(
            barcode = "UNKNOWN1234",
            lookupResult = null,
            scannedAt = 123456789L,
        )

        assertEquals(ScannerMatchStatus.MISMATCH, record.matchStatus)
        assertNull(record.lookupSnapshot)
    }
}
