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

