package nz.co.mixport.customsvision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileVisionProfileTest {
    @Test
    fun `entry tier tightens latency and crop budgets`() {
        val profile = MobileVisionProfile.fromCapabilities(
            totalRamGb = 4,
            preferredAbi = "arm64-v8a",
            tuning = InspectionTuningProfile.default(),
        )

        assertEquals(MobileDeviceTier.ENTRY, profile.deviceTier)
        assertEquals(160L, profile.liveAnalysisIntervalMs)
        assertEquals(2, profile.maxSnapshotDetectionsPerPass)
        assertEquals(256, profile.transformerInputSizePx)
        assertTrue(profile.transformerQuantized)
    }

    @Test
    fun `high tier allows faster analysis cadence`() {
        val profile = MobileVisionProfile.fromCapabilities(
            totalRamGb = 12,
            preferredAbi = "arm64-v8a",
            tuning = InspectionTuningProfile.default(),
        )

        assertEquals(MobileDeviceTier.HIGH, profile.deviceTier)
        assertEquals(100L, profile.liveAnalysisIntervalMs)
        assertEquals(4, profile.maxSnapshotDetectionsPerPass)
        assertEquals(45L, profile.transformerTargetLatencyMs)
        assertEquals("arm64-v8a", profile.preferredAbi)
    }
}
