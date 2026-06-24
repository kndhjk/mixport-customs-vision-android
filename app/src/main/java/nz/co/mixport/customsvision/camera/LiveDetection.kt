package nz.co.mixport.customsvision.camera

data class LiveDetectionFrame(
    val detections: List<LiveRecognition>,
    val analyzedAt: Long,
)

data class LiveRecognition(
    val trackingId: Int?,
    val label: String,
    val confidence: Float?,
    val category: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val isPalletCandidate: Boolean,
    val palletScore: Float? = null,
    val isCounted: Boolean = false,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val overlayTitle: String
        get() = buildString {
            append(label)
            trackingId?.let {
                append(" #")
                append(it)
            }
        }
}

data class UniversalRecognitionSnapshot(
    val analyzedAt: Long,
    val items: List<UniversalRecognition>,
)

data class UniversalRecognition(
    val trackingId: Int?,
    val sourceLabel: String,
    val bestLabel: String,
    val confidence: Float?,
    val dominantColor: String,
    val markerText: String,
    val labelHints: List<String>,
    val isPalletLike: Boolean = false,
    val palletScore: Float? = null,
    val isCounted: Boolean = false,
) {
    val displayTitle: String
        get() = buildString {
            append(bestLabel)
            trackingId?.let {
                append(" #")
                append(it)
            }
        }
}
