package nz.co.mixport.customsvision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionTuningProfileTest {
    @Test
    fun `profile parses custom thresholds from json`() {
        val profile = InspectionTuningProfile.fromJsonString(
            """
            {
              "metadata": {
                "profileName": "warehouse-a",
                "datasetId": "pilot-batch-01"
              },
              "tracking": {
                "minStableFrames": 4,
                "maxTrackGapMs": 2200,
                "countedEvidenceTtlMs": 35000
              },
              "mobileRuntime": {
                "liveAnalysisMinIntervalMs": 150,
                "maxSnapshotDetectionsPerPass": 2,
                "recognitionDownsampleMaxEdgePx": 256,
                "minRecognitionCropEdgePx": 40
              },
              "cargoLabeling": {
                "minImageLabelConfidence": 0.33,
                "maxLabelHints": 7,
                "minReliableDetectionConfidence": 0.81
              },
              "transformer": {
                "enabled": true,
                "preferredFamily": "MobileViTv2",
                "modelInputSizePx": 224,
                "quantized": true,
                "runOnStableTracksOnly": true,
                "preferNnapi": false,
                "cpuThreadCount": 3,
                "maxTracksPerPass": 2,
                "targetLatencyMs": 75
              },
              "palletReference": {
                "livePalletThreshold": 0.74,
                "recognitionPalletThreshold": 0.78,
                "zoneMinOverlapRatio": 0.42
              }
            }
            """.trimIndent(),
        )

        assertEquals("warehouse-a", profile.metadata.profileName)
        assertEquals("pilot-batch-01", profile.metadata.datasetId)
        assertEquals(4, profile.tracking.minStableFrames)
        assertEquals(2200L, profile.tracking.maxTrackGapMs)
        assertEquals(35000L, profile.tracking.countedEvidenceTtlMs)
        assertEquals(150L, profile.mobileRuntime.liveAnalysisMinIntervalMs)
        assertEquals(2, profile.mobileRuntime.maxSnapshotDetectionsPerPass)
        assertEquals(256, profile.mobileRuntime.recognitionDownsampleMaxEdgePx)
        assertEquals(40, profile.mobileRuntime.minRecognitionCropEdgePx)
        assertEquals(0.33f, profile.cargoLabeling.minImageLabelConfidence)
        assertEquals(7, profile.cargoLabeling.maxLabelHints)
        assertEquals(0.81f, profile.cargoLabeling.minReliableDetectionConfidence)
        assertEquals("MobileViTv2", profile.transformer.preferredFamily)
        assertEquals(224, profile.transformer.modelInputSizePx)
        assertEquals(3, profile.transformer.cpuThreadCount)
        assertEquals(75L, profile.transformer.targetLatencyMs)
        assertEquals(0.74f, profile.palletReference.livePalletThreshold)
        assertEquals(0.78f, profile.palletReference.recognitionPalletThreshold)
        assertEquals(0.42f, profile.palletReference.zoneMinOverlapRatio)
    }

    @Test
    fun `profile round trip preserves schema sections`() {
        val raw = InspectionTuningProfile.default().toJsonString()

        assertTrue(raw.contains("\"tracking\""))
        assertTrue(raw.contains("\"mobileRuntime\""))
        assertTrue(raw.contains("\"cargoLabeling\""))
        assertTrue(raw.contains("\"transformer\""))
        assertTrue(raw.contains("\"palletReference\""))
        assertTrue(raw.contains("\"metadata\""))
    }
}
