package nz.co.mixport.customsvision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoLabelResolverTest {
    private val resolver = CargoLabelResolver()

    @Test
    fun `ocr and label hints resolve kettle`() {
        val match = resolver.resolve(
            sourceLabel = "Tracked cargo",
            markerText = "不锈钢 烧水壶",
            labelHints = listOf("Kitchen appliance", "Kettle"),
        )

        requireNotNull(match)
        assertEquals("Electric kettle", match.label)
        assertTrue(match.score >= 8)
    }

    @Test
    fun `weighted matches prefer laptop over generic computer`() {
        val match = resolver.resolve(
            sourceLabel = "Laptop",
            markerText = "office notebook computer",
            labelHints = listOf("Computer", "Electronics"),
        )

        requireNotNull(match)
        assertEquals("Laptop", match.label)
    }

    @Test
    fun `returns null for unrelated weak labels`() {
        val match = resolver.resolve(
            sourceLabel = "Tracked cargo",
            markerText = "fragile load",
            labelHints = listOf("Package", "Object"),
        )

        assertNull(match)
    }
}
