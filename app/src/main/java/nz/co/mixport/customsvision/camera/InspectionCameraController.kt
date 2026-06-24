package nz.co.mixport.customsvision.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer as VisionTextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class InspectionCameraController(
    private val context: Context,
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundPreviewView: PreviewView? = null
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var objectDetector: ObjectDetector? = null
    private var imageLabeler: ImageLabeler? = null
    private var latinTextRecognizer: VisionTextRecognizer? = null
    private var chineseTextRecognizer: VisionTextRecognizer? = null
    private var isBinding = false
    private var isCameraBound = false
    @Volatile
    private var isUniversalRecognitionRunning = false

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onFrameHeartbeat: (Long) -> Unit,
        onDetections: (LiveDetectionFrame) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (boundPreviewView === previewView &&
            boundLifecycleOwner === lifecycleOwner &&
            (isBinding || isCameraBound)
        ) {
            return
        }
        boundPreviewView = previewView
        boundLifecycleOwner = lifecycleOwner
        isBinding = true

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val cameraProvider = providerFuture.get()
                    this.cameraProvider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                Quality.SD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                            ),
                        )
                        .build()
                    val nextVideoCapture = VideoCapture.withOutput(recorder)
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(
                                cameraExecutor,
                                LiveObjectAnalyzer(
                                    previewView = previewView,
                                    objectDetector = detector(),
                                    onFrameHeartbeat = onFrameHeartbeat,
                                    onDetections = onDetections,
                                    onError = onError,
                                ),
                            )
                        }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        nextVideoCapture,
                        imageAnalysis,
                    )

                    videoCapture = nextVideoCapture
                    isCameraBound = true
                    isBinding = false
                }.onFailure { throwable ->
                    isBinding = false
                    isCameraBound = false
                    videoCapture = null
                    onError(throwable.message ?: "Failed to bind the camera.")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun analyzeVisibleCargo(
        detections: List<LiveRecognition>,
        onComplete: (UniversalRecognitionSnapshot) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isUniversalRecognitionRunning) {
            onError("Visible cargo recognition is already running.")
            return
        }

        val previewView = boundPreviewView
        if (!isCameraBound || previewView == null) {
            onError("Camera preview is not ready yet.")
            return
        }

        val visibleCargo = detections.filterNot { it.isPalletCandidate }
        if (visibleCargo.isEmpty()) {
            onError("No visible cargo is ready for recognition.")
            return
        }

        val snapshotBitmap = previewView.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
        if (snapshotBitmap == null || snapshotBitmap.width == 0 || snapshotBitmap.height == 0) {
            snapshotBitmap?.recycle()
            onError("Unable to capture the current preview frame.")
            return
        }

        isUniversalRecognitionRunning = true
        cameraExecutor.execute {
            val result = runCatching {
                analyzeBitmapSnapshot(snapshotBitmap, visibleCargo)
            }
            snapshotBitmap.recycle()
            isUniversalRecognitionRunning = false

            ContextCompat.getMainExecutor(context).execute {
                result
                    .onSuccess(onComplete)
                    .onFailure { throwable ->
                        onError(throwable.message ?: "Visible cargo recognition failed.")
                    }
            }
        }
    }

    fun startRecording(
        displayName: String,
        onSaved: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val capture = videoCapture
        if (capture == null) {
            onError("Camera is not ready yet.")
            return
        }
        if (activeRecording != null) {
            onError("Recording is already running.")
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MixportCustoms")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()

        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        if (event.hasError()) {
                            onError("Recording failed: ${event.error}")
                        } else {
                            onSaved(event.outputResults.outputUri.toString())
                        }
                    }

                    else -> Unit
                }
            }
    }

    fun stopRecording() {
        activeRecording?.stop()
    }

    fun release() {
        activeRecording?.close()
        cameraProvider?.unbindAll()
        cameraProvider = null
        isBinding = false
        isCameraBound = false
        videoCapture = null
        objectDetector?.close()
        objectDetector = null
        imageLabeler?.close()
        imageLabeler = null
        latinTextRecognizer?.close()
        latinTextRecognizer = null
        chineseTextRecognizer?.close()
        chineseTextRecognizer = null
        cameraExecutor.shutdown()
    }

    private fun detector(): ObjectDetector {
        return objectDetector ?: ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build(),
        ).also { objectDetector = it }
    }

    private fun bundledImageLabeler(): ImageLabeler {
        return imageLabeler ?: ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.35f)
                .build(),
        ).also { imageLabeler = it }
    }

    private fun latinRecognizer(): VisionTextRecognizer {
        return latinTextRecognizer ?: TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS,
        ).also { latinTextRecognizer = it }
    }

    private fun chineseRecognizer(): VisionTextRecognizer {
        return chineseTextRecognizer ?: TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        ).also { chineseTextRecognizer = it }
    }

    private fun analyzeBitmapSnapshot(
        snapshotBitmap: Bitmap,
        detections: List<LiveRecognition>,
    ): UniversalRecognitionSnapshot {
        val insights = detections.mapNotNull { detection ->
            val croppedBitmap = cropDetection(snapshotBitmap, detection) ?: return@mapNotNull null
            try {
                analyzeCroppedBitmap(croppedBitmap, detection)
            } finally {
                croppedBitmap.recycle()
            }
        }

        return UniversalRecognitionSnapshot(
            analyzedAt = System.currentTimeMillis(),
            items = insights,
        )
    }

    private fun cropDetection(
        snapshotBitmap: Bitmap,
        detection: LiveRecognition,
    ): Bitmap? {
        val left = detection.left.toInt().coerceIn(0, snapshotBitmap.width - 1)
        val top = detection.top.toInt().coerceIn(0, snapshotBitmap.height - 1)
        val right = detection.right.toInt().coerceIn(left + 1, snapshotBitmap.width)
        val bottom = detection.bottom.toInt().coerceIn(top + 1, snapshotBitmap.height)
        val cropRect = Rect(left, top, right, bottom)
        if (cropRect.width() < 24 || cropRect.height() < 24) {
            return null
        }
        return Bitmap.createBitmap(
            snapshotBitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height(),
        )
    }

    private fun analyzeCroppedBitmap(
        croppedBitmap: Bitmap,
        detection: LiveRecognition,
    ): UniversalRecognition {
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val labels = Tasks.await(bundledImageLabeler().process(image))
            .sortedByDescending { it.confidence }
        val latinText = Tasks.await(latinRecognizer().process(image)).text.normalizeMarkerText()
        val chineseText = Tasks.await(chineseRecognizer().process(image)).text.normalizeMarkerText()
        val labelHints = labels
            .map { it.text.toReadableLabel() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
        val dominantColor = detectDominantColor(croppedBitmap)
        val isPalletLike = isLikelyWoodPallet(
            detection = detection,
            dominantColor = dominantColor,
            labelHints = labelHints,
        )
        val bestLabel = when {
            isPalletLike -> "Wood pallet base"
            else -> labelHints.firstOrNull().orEmpty().ifBlank { detection.label }
        }

        return UniversalRecognition(
            trackingId = detection.trackingId,
            sourceLabel = detection.label,
            bestLabel = bestLabel,
            confidence = labels.firstOrNull()?.confidence ?: detection.confidence,
            dominantColor = dominantColor,
            markerText = mergeMarkerText(latinText, chineseText),
            labelHints = labelHints,
            isPalletLike = isPalletLike,
        )
    }

    private fun isLikelyWoodPallet(
        detection: LiveRecognition,
        dominantColor: String,
        labelHints: List<String>,
    ): Boolean {
        if (detection.isPalletCandidate) {
            return true
        }

        val aspectRatio = detection.width / max(detection.height, 1f)
        val isFlatDeck = aspectRatio > 1.55f && detection.height < detection.width * 0.58f
        val hasWoodTone = dominantColor in setOf("Brown", "Orange", "Yellow")
        val labelSuggestsPallet = labelHints.any { hint ->
            hint.contains("Pallet", ignoreCase = true) ||
                hint.contains("Wood", ignoreCase = true) ||
                hint.contains("Lumber", ignoreCase = true) ||
                hint.contains("Furniture", ignoreCase = true) ||
                hint.contains("Table", ignoreCase = true)
        }

        return isFlatDeck && hasWoodTone && (labelSuggestsPallet || aspectRatio > 2.0f)
    }

    private fun mergeMarkerText(vararg textValues: String): String {
        return textValues
            .flatMap { text ->
                text.split('|')
                    .map(String::trim)
                    .filter(String::isNotBlank)
            }
            .distinct()
            .take(3)
            .joinToString(" | ")
    }

    private fun String.normalizeMarkerText(): String {
        return lineSequence()
            .map { line -> line.trim().replace("\\s+".toRegex(), " ") }
            .filter { line ->
                line.isNotBlank() &&
                    (line.length > 1 || line.any(Char::isDigit))
            }
            .distinct()
            .take(3)
            .joinToString(" | ")
    }

    private fun detectDominantColor(bitmap: Bitmap): String {
        val hsv = FloatArray(3)
        val stepX = max(1, bitmap.width / 18)
        val stepY = max(1, bitmap.height / 18)
        var sampleCount = 0
        var hueVectorX = 0.0
        var hueVectorY = 0.0
        var saturationTotal = 0f
        var valueTotal = 0f

        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                val hueRadians = hsv[0] / 180f * PI
                hueVectorX += cos(hueRadians) * hsv[1]
                hueVectorY += sin(hueRadians) * hsv[1]
                saturationTotal += hsv[1]
                valueTotal += hsv[2]
                sampleCount++
            }
        }

        if (sampleCount == 0) {
            return "Unclassified"
        }

        val averageSaturation = saturationTotal / sampleCount
        val averageValue = valueTotal / sampleCount

        if (averageValue < 0.18f) {
            return "Black"
        }
        if (averageSaturation < 0.12f) {
            return when {
                averageValue > 0.84f -> "White"
                averageValue > 0.42f -> "Gray"
                else -> "Black"
            }
        }

        var hueDegrees = Math.toDegrees(atan2(hueVectorY, hueVectorX))
        if (hueDegrees < 0.0) {
            hueDegrees += 360.0
        }

        return when {
            hueDegrees < 16.0 || hueDegrees >= 345.0 -> "Red"
            hueDegrees < 36.0 -> if (averageValue < 0.72f) "Brown" else "Orange"
            hueDegrees < 58.0 -> "Orange"
            hueDegrees < 78.0 -> "Yellow"
            hueDegrees < 165.0 -> "Green"
            hueDegrees < 250.0 -> "Blue"
            hueDegrees < 320.0 -> "Purple"
            else -> "Red"
        }
    }

    private fun String.toReadableLabel(): String {
        return split('_', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.lowercase().replaceFirstChar { first ->
                    if (first.isLowerCase()) first.titlecase() else first.toString()
                }
            }
    }

    private class LiveObjectAnalyzer(
        private val previewView: PreviewView,
        private val objectDetector: ObjectDetector,
        private val onFrameHeartbeat: (Long) -> Unit,
        private val onDetections: (LiveDetectionFrame) -> Unit,
        private val onError: (String) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private var lastDeliveredAt: Long = 0L
        @Volatile
        private var isProcessing = false
        private var lastFailureMessage: String? = null

        override fun analyze(imageProxy: ImageProxy) {
            val now = System.currentTimeMillis()
            if (now - lastDeliveredAt >= 1000L) {
                lastDeliveredAt = now
                onFrameHeartbeat(now)
            }

            if (isProcessing) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage == null || previewView.width == 0 || previewView.height == 0) {
                imageProxy.close()
                return
            }

            isProcessing = true
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees,
            )
            val correctionMatrix = getCorrectionMatrix(imageProxy, previewView)

            objectDetector.process(inputImage)
                .addOnSuccessListener { objects ->
                    val detections = objects.mapNotNull { detectedObject ->
                        detectedObject.toLiveRecognition(
                            correctionMatrix = correctionMatrix,
                            previewWidth = previewView.width.toFloat(),
                            previewHeight = previewView.height.toFloat(),
                        )
                    }
                    onDetections(
                        LiveDetectionFrame(
                            detections = detections,
                            analyzedAt = now,
                        ),
                    )
                }
                .addOnFailureListener { throwable ->
                    val message = throwable.message ?: "Live object detection failed."
                    if (message != lastFailureMessage) {
                        lastFailureMessage = message
                        onError(message)
                    }
                }
                .addOnCompleteListener {
                    isProcessing = false
                    imageProxy.close()
                }
        }

        private fun getCorrectionMatrix(imageProxy: ImageProxy, previewView: PreviewView): Matrix {
            val cropRect = imageProxy.cropRect
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix()
            val source = floatArrayOf(
                cropRect.left.toFloat(),
                cropRect.top.toFloat(),
                cropRect.right.toFloat(),
                cropRect.top.toFloat(),
                cropRect.right.toFloat(),
                cropRect.bottom.toFloat(),
                cropRect.left.toFloat(),
                cropRect.bottom.toFloat(),
            )
            val destination = floatArrayOf(
                0f,
                0f,
                previewView.width.toFloat(),
                0f,
                previewView.width.toFloat(),
                previewView.height.toFloat(),
                0f,
                previewView.height.toFloat(),
            )
            val vertexSize = 2
            val shiftOffset = rotationDegrees / 90 * vertexSize
            val tempArray = destination.clone()
            for (toIndex in source.indices) {
                val fromIndex = (toIndex + shiftOffset) % source.size
                destination[toIndex] = tempArray[fromIndex]
            }
            matrix.setPolyToPoly(source, 0, destination, 0, 4)
            return matrix
        }

        private fun DetectedObject.toLiveRecognition(
            correctionMatrix: Matrix,
            previewWidth: Float,
            previewHeight: Float,
        ): LiveRecognition? {
            val rect = RectF(boundingBox)
            correctionMatrix.mapRect(rect)
            rect.sort()
            rect.left = rect.left.coerceIn(0f, previewWidth)
            rect.top = rect.top.coerceIn(0f, previewHeight)
            rect.right = rect.right.coerceIn(0f, previewWidth)
            rect.bottom = rect.bottom.coerceIn(0f, previewHeight)
            if (rect.width() <= 4f || rect.height() <= 4f) {
                return null
            }

            val primaryLabel = labels.maxByOrNull { it.confidence }
            val category = primaryLabel?.text?.toReadableLabel() ?: "Unknown"
            val isPalletCandidate = isLikelyPallet(rect, previewWidth, previewHeight, category)

            return LiveRecognition(
                trackingId = trackingId,
                label = when {
                    isPalletCandidate -> "Pallet candidate"
                    category != "Unknown" -> category
                    else -> "Tracked cargo"
                },
                confidence = primaryLabel?.confidence,
                category = category,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                isPalletCandidate = isPalletCandidate,
            )
        }

        private fun isLikelyPallet(
            rect: RectF,
            previewWidth: Float,
            previewHeight: Float,
            category: String,
        ): Boolean {
            val widthRatio = rect.width() / previewWidth
            val heightRatio = rect.height() / previewHeight
            val aspectRatio = rect.width() / max(rect.height(), 1f)
            val sitsNearBottom = rect.bottom > previewHeight * 0.64f
            val isFlatDeck = aspectRatio > 1.45f && heightRatio in 0.10f..0.36f
            val spansUsefulWidth = widthRatio > 0.26f
            val categoryHintsPallet = category.contains("Home", ignoreCase = true) ||
                category.contains("Place", ignoreCase = true) ||
                category.contains("Furniture", ignoreCase = true) ||
                category.contains("Table", ignoreCase = true) ||
                category.contains("Wood", ignoreCase = true)
            val strongBaseFallback = widthRatio > 0.42f && heightRatio < 0.28f && aspectRatio > 1.75f

            return sitsNearBottom && spansUsefulWidth && isFlatDeck &&
                (categoryHintsPallet || strongBaseFallback)
        }

        private fun String.toReadableLabel(): String {
            return split('_', ' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { token ->
                    token.lowercase().replaceFirstChar { first ->
                        if (first.isLowerCase()) first.titlecase() else first.toString()
                    }
                }
        }
    }
}
