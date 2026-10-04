package nz.co.mixport.customsvision.ui

import nz.co.mixport.customsvision.data.ScannerMatchStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerFeedbackPlayerTest {
    @Test
    fun `matched clear cargo uses clear sound`() {
        assertEquals(
            ScannerFeedbackSound.CLEAR,
            scannerFeedbackSound(ScannerMatchStatus.MATCHED, "clear", "clear"),
        )
    }

    @Test
    fun `matched hold cargo uses warning sound`() {
        assertEquals(
            ScannerFeedbackSound.HOLD,
            scannerFeedbackSound(ScannerMatchStatus.MATCHED, "clear", "hold"),
        )
    }

    @Test
    fun `failed clearance and unmatched scans use failed sound`() {
        assertEquals(
            ScannerFeedbackSound.FAILED,
            scannerFeedbackSound(ScannerMatchStatus.MATCHED, "failed", "clear"),
        )
        assertEquals(
            ScannerFeedbackSound.FAILED,
            scannerFeedbackSound(ScannerMatchStatus.MISMATCH, null, null),
        )
    }

    @Test
    fun `missing clearance never plays a release sound`() {
        assertEquals(
            ScannerFeedbackSound.HOLD,
            scannerFeedbackSound(ScannerMatchStatus.MATCHED, "clear", null),
        )
        assertEquals(
            ScannerFeedbackSound.HOLD,
            scannerFeedbackSound(ScannerMatchStatus.MATCHED, "", "clear"),
        )
        assertEquals(
            ScannerFeedbackSound.FAILED,
            scannerFeedbackSound(ScannerMatchStatus.MATCHED, "hold", "failed"),
        )
    }

    @Test
    fun `waiting for a new scan cannot replay previous clear sound`() {
        assertEquals(
            ScannerFeedbackSound.NONE,
            scannerFeedbackSound(ScannerMatchStatus.WAITING, "clear", "clear"),
        )
    }
}
