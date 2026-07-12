package nz.co.mixport.customsvision.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerBarcodeNormalizerTest {
    @Test
    fun normalizeScannerBarcode_removesWhitespaceAndUppercases() {
        assertEquals("VAN1413050612", normalizeScannerBarcode(" van 1413050612 \n"))
    }

    @Test
    fun normalizeScannerBarcode_stripsCode39GuardStars() {
        assertEquals("PTS0261010", normalizeScannerBarcode("*pts0261010*"))
    }

    @Test
    fun isUsableScannerBarcode_acceptsShorterNumericLabels() {
        assertTrue(isUsableScannerBarcode("6970479741787"))
        assertTrue(isUsableScannerBarcode("ABCD"))
    }

    @Test
    fun isUsableScannerBarcode_rejectsBlankOrTooLongValues() {
        assertFalse(isUsableScannerBarcode(""))
        assertFalse(isUsableScannerBarcode("ABC"))
        assertFalse(isUsableScannerBarcode("X".repeat(65)))
    }
}
