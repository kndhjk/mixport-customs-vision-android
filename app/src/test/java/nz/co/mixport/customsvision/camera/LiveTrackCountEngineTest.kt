package nz.co.mixport.customsvision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackCountEngineTest {
    private val engine = LiveTrackCountEngine()

    @Test
    fun `object becomes count ready after stable consecutive frames`() {
        val frame1 = observeCargoFrame(trackingId = 11, analyzedAt = 1_000L)
        val frame2 = observeCargoFrame(trackingId = 11, analyzedAt = 1_300L)
        val frame3 = observeCargoFrame(trackingId = 11, analyzedAt = 1_600L)

        assertEquals(1, frame1.stableFrameCount)
        assertFalse(frame1.isCountReady)
        assertEquals(2, frame2.stableFrameCount)
        assertFalse(frame2.isCountReady)
        assertEquals(3, frame3.stableFrameCount)
        assertTrue(frame3.isInPalletZone)
        assertTrue(frame3.isCountReady)
    }

    @Test
    fun `recently counted object stays counted even if tracking id changes`() {
        val readyDetection = observeCargoFrame(trackingId = 21, analyzedAt = 2_000L)
        observeCargoFrame(trackingId = 21, analyzedAt = 2_300L)
        val stableDetection = observeCargoFrame(trackingId = 21, analyzedAt = 2_600L)
        assertTrue(stableDetection.isCountReady)

        engine.markCounted(listOf(stableDetection), countedAt = 2_700L)

        val reacquiredDetection = observeCargoFrame(
            trackingId = 99,
            analyzedAt = 3_000L,
            left = 112f,
            top = 118f,
        )

        assertTrue(readyDetection.trackKey.isNotBlank() || stableDetection.trackKey.isNotBlank())
        assertTrue(reacquiredDetection.isCounted)
        assertFalse(reacquiredDetection.isCountReady)
    }

    @Test
    fun `recognition snapshot upgrades generic tracked cargo label`() {
        observeCargoFrame(trackingId = 31, analyzedAt = 4_000L)
        observeCargoFrame(trackingId = 31, analyzedAt = 4_250L)
        val stableDetection = observeCargoFrame(trackingId = 31, analyzedAt = 4_500L)

        val decoratedSnapshot = engine.observeRecognition(
            UniversalRecognitionSnapshot(
                analyzedAt = 4_600L,
                items = listOf(
                    UniversalRecognition(
                        trackingId = 31,
                        sourceLabel = "Tracked cargo",
                        bestLabel = "Electric kettle",
                        confidence = 0.88f,
                        dominantColor = "White",
                        markerText = "",
                        labelHints = listOf("Kettle", "Appliance"),
                    ),
                ),
            ),
        )

        val decoratedDetection = engine.decorateDetections(
            detections = listOf(stableDetection),
            analyzedAt = 4_700L,
        ).single()

        assertEquals("Electric kettle", decoratedSnapshot.items.single().bestLabel)
        assertEquals("Electric kettle", decoratedDetection.label)
        assertTrue(decoratedDetection.isCountReady)
    }

    @Test
    fun `reset for next pallet clears counted evidence`() {
        observeCargoFrame(trackingId = 41, analyzedAt = 5_000L)
        observeCargoFrame(trackingId = 41, analyzedAt = 5_250L)
        val stableDetection = observeCargoFrame(trackingId = 41, analyzedAt = 5_500L)
        engine.markCounted(listOf(stableDetection), countedAt = 5_600L)

        engine.resetForNextPallet()

        observeCargoFrame(trackingId = 52, analyzedAt = 6_000L)
        observeCargoFrame(trackingId = 52, analyzedAt = 6_250L)
        val nextPalletDetection = observeCargoFrame(trackingId = 52, analyzedAt = 6_500L)

        assertFalse(nextPalletDetection.isCounted)
        assertTrue(nextPalletDetection.isCountReady)
    }

    @Test
    fun `stable object outside pallet zone is not count ready`() {
        val frame1 = engine.observeDetections(
            detections = listOf(
                samplePalletDetection(),
                sampleDetection(trackingId = 77, left = 410f, top = 120f),
            ),
            analyzedAt = 7_000L,
        ).last()
        val frame2 = engine.observeDetections(
            detections = listOf(
                samplePalletDetection(),
                sampleDetection(trackingId = 77, left = 420f, top = 120f),
            ),
            analyzedAt = 7_250L,
        ).last()
        val frame3 = engine.observeDetections(
            detections = listOf(
                samplePalletDetection(),
                sampleDetection(trackingId = 77, left = 418f, top = 124f),
            ),
            analyzedAt = 7_500L,
        ).last()

        assertFalse(frame1.isInPalletZone)
        assertFalse(frame2.isInPalletZone)
        assertFalse(frame3.isInPalletZone)
        assertFalse(frame3.isCountReady)
    }

    private fun observeCargoFrame(
        trackingId: Int,
        analyzedAt: Long,
        left: Float = 100f,
        top: Float = 120f,
    ): LiveRecognition {
        return engine.observeDetections(
            detections = listOf(
                samplePalletDetection(),
                sampleDetection(trackingId = trackingId, left = left, top = top),
            ),
            analyzedAt = analyzedAt,
        ).last()
    }

    private fun sampleDetection(
        trackingId: Int,
        left: Float = 100f,
        top: Float = 120f,
    ): LiveRecognition {
        return LiveRecognition(
            trackingId = trackingId,
            label = "Tracked cargo",
            confidence = 0.82f,
            category = "Unknown",
            left = left,
            top = top,
            right = left + 160f,
            bottom = top + 160f,
            isPalletCandidate = false,
        )
    }

    private fun samplePalletDetection(): LiveRecognition {
        return LiveRecognition(
            trackingId = 501,
            label = "Pallet candidate",
            confidence = 0.91f,
            category = "Furniture",
            left = 60f,
            top = 220f,
            right = 340f,
            bottom = 320f,
            isPalletCandidate = true,
            palletScore = 0.9f,
        )
    }
}
