package nz.co.mixport.customsvision.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerRealtimeSyncTest {
    @Test
    fun `force refresh bypasses the long cache interval after debounce`() {
        assertTrue(
            shouldRefreshScannerReferenceCache(
                isConfigured = true,
                isRefreshing = false,
                isUploading = false,
                referenceCount = 12,
                lastReferenceSyncAt = 1000L,
                now = 1000L + SCANNER_REFERENCE_FORCE_REFRESH_DEBOUNCE_MS,
                force = true,
            ),
        )
    }

    @Test
    fun `force refresh is still throttled for back to back retries`() {
        assertFalse(
            shouldRefreshScannerReferenceCache(
                isConfigured = true,
                isRefreshing = false,
                isUploading = false,
                referenceCount = 12,
                lastReferenceSyncAt = 1000L,
                now = 1000L + SCANNER_REFERENCE_FORCE_REFRESH_DEBOUNCE_MS - 1L,
                force = true,
            ),
        )
    }

    @Test
    fun `automatic refresh waits for the regular interval when cache exists`() {
        assertFalse(
            shouldRefreshScannerReferenceCache(
                isConfigured = true,
                isRefreshing = false,
                isUploading = false,
                referenceCount = 12,
                lastReferenceSyncAt = 1000L,
                now = 1000L + SCANNER_REFERENCE_REFRESH_INTERVAL_MS - 1L,
                force = false,
            ),
        )
        assertTrue(
            shouldRefreshScannerReferenceCache(
                isConfigured = true,
                isRefreshing = false,
                isUploading = false,
                referenceCount = 12,
                lastReferenceSyncAt = 1000L,
                now = 1000L + SCANNER_REFERENCE_REFRESH_INTERVAL_MS,
                force = false,
            ),
        )
    }

    @Test
    fun `empty cache refreshes immediately when sync is configured`() {
        assertTrue(
            shouldRefreshScannerReferenceCache(
                isConfigured = true,
                isRefreshing = false,
                isUploading = false,
                referenceCount = 0,
                lastReferenceSyncAt = null,
                now = 1000L,
                force = false,
            ),
        )
    }
}
