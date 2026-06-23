package nz.co.mixport.customsvision.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private var isBinding = false
    private var isCameraBound = false

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
            val sitsNearBottom = rect.bottom > previewHeight * 0.62f
            val isWide = rect.width() > rect.height() * 1.15f
            val categoryHintsPallet = category.contains("Home", ignoreCase = true) ||
                category.contains("Place", ignoreCase = true)

            return sitsNearBottom && isWide && widthRatio > 0.28f && heightRatio < 0.42f &&
                (categoryHintsPallet || widthRatio > 0.38f)
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
