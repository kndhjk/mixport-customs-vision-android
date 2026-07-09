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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
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
import com.google.mlkit.common.MlKit
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
    private val tuning: InspectionTuningProfile = InspectionTuningProfile.default(),
) {
    private data class CargoVocabularyEntry(
        val label: String,
        val keywords: Set<String>,
    )

    private data class PalletReferenceProfile(
        val targetAspectRatio: Float = 2.1f,
        val aspectTolerance: Float = 0.9f,
        val targetWidthRatio: Float = 0.44f,
        val widthTolerance: Float = 0.22f,
        val targetHeightRatio: Float = 0.22f,
        val heightTolerance: Float = 0.12f,
        val bottomAnchorRatio: Float = 0.72f,
    )

    private val palletReferenceProfile = PalletReferenceProfile(
        targetAspectRatio = tuning.palletReference.targetAspectRatio,
        aspectTolerance = tuning.palletReference.aspectTolerance,
        targetWidthRatio = tuning.palletReference.targetWidthRatio,
        widthTolerance = tuning.palletReference.widthTolerance,
        targetHeightRatio = tuning.palletReference.targetHeightRatio,
        heightTolerance = tuning.palletReference.heightTolerance,
        bottomAnchorRatio = tuning.palletReference.bottomAnchorRatio,
    )
    private val cargoVocabulary = listOf(
        CargoVocabularyEntry(
            label = "Electric kettle",
            keywords = setOf("kettle", "teapot", "water boiler", "boiler", "coffee pot", "烧水壶", "水壶"),
        ),
        CargoVocabularyEntry(
            label = "Cup / mug",
            keywords = setOf("cup", "mug", "tumbler", "glass", "teacup", "杯", "杯子", "马克杯"),
        ),
        CargoVocabularyEntry(
            label = "Bowl",
            keywords = setOf("bowl", "dish", "basin", "碗"),
        ),
        CargoVocabularyEntry(
            label = "Plate",
            keywords = setOf("plate", "tray", "盘", "盘子", "托盘餐盘"),
        ),
        CargoVocabularyEntry(
            label = "Chopsticks / cutlery",
            keywords = setOf("chopstick", "chopsticks", "cutlery", "utensil", "utensils", "sticks", "筷", "筷子", "餐具"),
        ),
        CargoVocabularyEntry(
            label = "Spoon / fork",
            keywords = setOf("spoon", "fork", "ladle", "勺", "勺子", "叉子"),
        ),
        CargoVocabularyEntry(
            label = "Pot / pan",
            keywords = setOf("pot", "pan", "cookware", "skillet", "锅", "锅具", "平底锅"),
        ),
        CargoVocabularyEntry(
            label = "Pen / marker",
            keywords = setOf("pen", "marker", "pencil", "stylus", "签字笔", "笔", "记号笔"),
        ),
        CargoVocabularyEntry(
            label = "Computer",
            keywords = setOf("computer", "desktop", "monitor", "display", "screen", "电脑", "显示器"),
        ),
        CargoVocabularyEntry(
            label = "Laptop",
            keywords = setOf("laptop", "notebook computer", "macbook", "笔记本电脑", "手提电脑"),
        ),
        CargoVocabularyEntry(
            label = "Notebook / book",
            keywords = setOf("notebook", "book", "exercise book", "journal", "笔记本", "本子", "书"),
        ),
        CargoVocabularyEntry(
            label = "Keyboard",
            keywords = setOf("keyboard", "keypad", "键盘"),
        ),
        CargoVocabularyEntry(
            label = "Mouse / pointer",
            keywords = setOf("mouse", "computer mouse", "鼠标"),
        ),
        CargoVocabularyEntry(
            label = "Phone / tablet",
            keywords = setOf("phone", "smartphone", "mobile phone", "tablet", "ipad", "手机", "平板"),
        ),
        CargoVocabularyEntry(
            label = "Printer",
            keywords = setOf("printer", "scanner", "打印机"),
        ),
        CargoVocabularyEntry(
            label = "Cable / charger",
            keywords = setOf("cable", "charger", "adapter", "wire", "cord", "数据线", "充电器", "线材"),
        ),
        CargoVocabularyEntry(
            label = "Speaker / audio",
            keywords = setOf("speaker", "audio", "sound box", "headphone", "耳机", "音箱", "音响"),
        ),
        CargoVocabularyEntry(
            label = "Carton / box",
            keywords = setOf("carton", "box", "package", "parcel", "crate", "箱", "纸箱", "盒子"),
        ),
        CargoVocabularyEntry(
            label = "Bottle",
            keywords = setOf("bottle", "thermos", "flask", "瓶", "瓶子", "保温瓶"),
        ),
        CargoVocabularyEntry(
            label = "Bag / backpack",
            keywords = setOf("bag", "backpack", "handbag", "luggage bag", "包", "背包", "手提包"),
        ),
        CargoVocabularyEntry(
            label = "Suitcase / luggage",
            keywords = setOf("suitcase", "luggage", "travel case", "行李箱", "旅行箱"),
        ),
        CargoVocabularyEntry(
            label = "Chair / stool",
            keywords = setOf("chair", "stool", "seat", "椅子", "凳子"),
        ),
        CargoVocabularyEntry(
            label = "Lamp / lighting",
            keywords = setOf("lamp", "light", "lantern", "台灯", "灯具"),
        ),
        CargoVocabularyEntry(
            label = "Fan / appliance",
            keywords = setOf("fan", "blower", "ventilator", "电风扇", "风扇"),
        ),
        CargoVocabularyEntry(
            label = "Rice cooker / appliance",
            keywords = setOf("rice cooker", "appliance", "kitchen appliance", "电饭煲", "小家电"),
        ),
        CargoVocabularyEntry(
            label = "Clothing / fabric",
            keywords = setOf("clothing", "garment", "shirt", "pants", "fabric", "衣服", "服装", "布料"),
        ),
        CargoVocabularyEntry(
            label = "Toy / gift",
            keywords = setOf("toy", "doll", "gift", "玩具", "礼品"),
        ),
        CargoVocabularyEntry(
            label = "Helmet / safety gear",
            keywords = setOf("helmet", "hard hat", "safety helmet", "头盔", "安全帽"),
        ),
        CargoVocabularyEntry(
            label = "Umbrella",
            keywords = setOf("umbrella", "伞", "雨伞"),
        ),
        CargoVocabularyEntry(
            label = "Tissue / paper goods",
            keywords = setOf("tissue", "paper towel", "napkin", "纸巾", "抽纸"),
        ),
        CargoVocabularyEntry(
            label = "Detergent / household liquids",
            keywords = setOf("detergent", "cleaner", "liquid soap", "washing liquid", "清洁剂", "洗衣液", "洗洁精"),
        ),
    )
    private val cargoLabelResolver = CargoLabelResolver()
    private val mobileVisionProfile = MobileVisionProfile.fromContext(context, tuning)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var boundPreviewView: PreviewView? = null
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var objectDetector: ObjectDetector? = null
    private var imageLabeler: ImageLabeler? = null
    private var latinTextRecognizer: VisionTextRecognizer? = null
    private var chineseTextRecognizer: VisionTextRecognizer? = null
    private var isBinding = false
    private var isCameraBound = false
    private var isAnalysisBound = false
    @Volatile
    private var isUniversalRecognitionRunning = false

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        enableAnalysis: Boolean,
        onFrameHeartbeat: (Long) -> Unit,
        onDetections: (LiveDetectionFrame) -> Unit,
        onCameraReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (boundPreviewView === previewView &&
            boundLifecycleOwner === lifecycleOwner &&
            isAnalysisBound == enableAnalysis &&
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
                    val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
                    var nextVideoCapture: VideoCapture<Recorder>? = null

                    if (enableAnalysis) {
                        ensureMlKitInitialized(context.applicationContext)
                        val recorder = Recorder.Builder()
                            .setQualitySelector(
                                QualitySelector.from(
                                    Quality.SD,
                                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                                ),
                            )
                            .build()
                        nextVideoCapture = VideoCapture.withOutput(recorder)
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
                        useCases += nextVideoCapture
                        useCases += imageAnalysis
                    }

                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        *useCases.toTypedArray(),
                    )

                    boundCamera = camera
                    videoCapture = nextVideoCapture
                    isCameraBound = true
                    isAnalysisBound = enableAnalysis
                    isBinding = false
                    onCameraReady()
                }.onFailure { throwable ->
                    isBinding = false
                    isCameraBound = false
                    isAnalysisBound = false
                    boundCamera = null
                    videoCapture = null
                    onError(throwable.message ?: "Failed to bind the camera.")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun hasRearFlashUnit(): Boolean {
        return boundCamera?.cameraInfo?.hasFlashUnit() == true
    }

    fun isRearTorchEnabled(): Boolean {
        return boundCamera?.cameraInfo?.torchState?.value == TorchState.ON
    }

    fun setRearTorchEnabled(
        enabled: Boolean,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        val camera = boundCamera
        if (camera == null || !isCameraBound) {
            onError("Camera is not ready yet.")
            return
        }
        if (!camera.cameraInfo.hasFlashUnit()) {
            onError("Rear flash is not available on this device.")
            return
        }
        val torchFuture = camera.cameraControl.enableTorch(enabled)
        torchFuture.addListener(
            {
                runCatching { torchFuture.get() }
                    .onSuccess { onComplete(camera.cameraInfo.torchState.value == TorchState.ON) }
                    .onFailure { throwable ->
                        onError(throwable.message ?: "Unable to switch the rear flash.")
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
        ensureMlKitInitialized(context.applicationContext)
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
            .sortedByDescending { it.width * it.height }
            .take(
                minOf(
                    mobileVisionProfile.maxSnapshotDetectionsPerPass,
                    tuning.mobileRuntime.maxSnapshotDetectionsPerPass,
                    tuning.transformer.maxTracksPerPass,
                ),
            )
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

    fun unbind() {
        activeRecording?.stop()
        activeRecording = null
        boundCamera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()
        boundCamera = null
        boundPreviewView = null
        boundLifecycleOwner = null
        isBinding = false
        isCameraBound = false
        isAnalysisBound = false
        videoCapture = null
    }

    fun release() {
        activeRecording?.close()
        unbind()
        cameraProvider = null
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
                .setConfidenceThreshold(tuning.cargoLabeling.minImageLabelConfidence)
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
            val optimizedBitmap = optimizeRecognitionBitmap(croppedBitmap)
            try {
                analyzeCroppedBitmap(optimizedBitmap, detection)
            } finally {
                if (optimizedBitmap !== croppedBitmap) {
                    optimizedBitmap.recycle()
                }
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
        if (
            cropRect.width() < tuning.mobileRuntime.minRecognitionCropEdgePx ||
            cropRect.height() < tuning.mobileRuntime.minRecognitionCropEdgePx
        ) {
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

    private fun optimizeRecognitionBitmap(bitmap: Bitmap): Bitmap {
        val maxEdge = max(bitmap.width, bitmap.height)
        val targetEdge = minOf(
            mobileVisionProfile.recognitionDownsampleMaxEdgePx,
            tuning.mobileRuntime.recognitionDownsampleMaxEdgePx,
            maxOf(tuning.transformer.modelInputSizePx, tuning.mobileRuntime.minRecognitionCropEdgePx),
        )
        if (targetEdge <= 0 || maxEdge <= targetEdge) {
            return bitmap
        }
        val scale = targetEdge.toFloat() / maxEdge.toFloat()
        val scaledWidth = maxOf(1, (bitmap.width * scale).toInt())
        val scaledHeight = maxOf(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
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
        val mergedMarkerText = mergeMarkerText(latinText, chineseText)
        val labelHints = labels
            .map { it.text.toReadableLabel() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(tuning.cargoLabeling.maxLabelHints)
        val dominantColor = detectDominantColor(croppedBitmap)
        val palletScore = scoreWoodPalletProfile(
            detection = detection,
            dominantColor = dominantColor,
            labelHints = labelHints,
            markerText = mergedMarkerText,
        )
        val isPalletLike = palletScore >= tuning.palletReference.recognitionPalletThreshold
        val mappedCargoLabel = resolveCargoLabel(
            labelHints = labelHints,
            markerText = mergedMarkerText,
            sourceLabel = detection.label,
        )
        val bestLabel = when {
            isPalletLike -> "Wood pallet base"
            mappedCargoLabel != null -> mappedCargoLabel
            else -> labelHints.firstOrNull().orEmpty().ifBlank { detection.label }
        }

        return UniversalRecognition(
            trackingId = detection.trackingId,
            sourceLabel = detection.label,
            bestLabel = bestLabel,
            confidence = labels.firstOrNull()?.confidence ?: detection.confidence,
            dominantColor = dominantColor,
            markerText = mergedMarkerText,
            labelHints = labelHints,
            isPalletLike = isPalletLike,
            palletScore = palletScore,
        )
    }

    private fun scoreWoodPalletProfile(
        detection: LiveRecognition,
        dominantColor: String,
        labelHints: List<String>,
        markerText: String,
    ): Float {
        val aspectRatio = detection.width / max(detection.height, 1f)
        val hasWoodTone = dominantColor in setOf("Brown", "Orange", "Yellow")
        val labelSuggestsPallet = labelHints.any { hint ->
            hint.contains("Pallet", ignoreCase = true) ||
                hint.contains("Wood", ignoreCase = true) ||
                hint.contains("Lumber", ignoreCase = true) ||
                hint.contains("Furniture", ignoreCase = true) ||
                hint.contains("Table", ignoreCase = true)
        }
        val hasLittleText = markerText.isBlank() || markerText.length <= 4
        val aspectMatch = normalizedProfileMatch(
            value = aspectRatio,
            target = palletReferenceProfile.targetAspectRatio,
            tolerance = palletReferenceProfile.aspectTolerance,
        )
        val liveSignal = detection.palletScore ?: 0f
        var score = 0f
        score += aspectMatch * 0.28f
        score += liveSignal * 0.28f
        if (hasWoodTone) score += 0.22f
        if (labelSuggestsPallet) score += 0.14f
        if (hasLittleText) score += 0.08f
        return score.coerceIn(0f, 1f)
    }

    private fun resolveCargoLabel(
        labelHints: List<String>,
        markerText: String,
        sourceLabel: String,
    ): String? {
        return cargoLabelResolver.resolve(
            sourceLabel = sourceLabel,
            markerText = markerText,
            labelHints = labelHints,
        )?.label
    }

    private fun normalizedProfileMatch(
        value: Float,
        target: Float,
        tolerance: Float,
    ): Float {
        if (tolerance <= 0f) {
            return 0f
        }
        val delta = kotlin.math.abs(value - target)
        return (1f - delta / tolerance).coerceIn(0f, 1f)
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

    private inner class LiveObjectAnalyzer(
        private val previewView: PreviewView,
        private val objectDetector: ObjectDetector,
        private val onFrameHeartbeat: (Long) -> Unit,
        private val onDetections: (LiveDetectionFrame) -> Unit,
        private val onError: (String) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private var lastDeliveredAt: Long = 0L
        private var lastAnalyzedAt: Long = 0L
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

            if (now - lastAnalyzedAt < mobileVisionProfile.liveAnalysisIntervalMs) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage == null || previewView.width == 0 || previewView.height == 0) {
                imageProxy.close()
                return
            }

            lastAnalyzedAt = now
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
            val palletScore = scoreLivePalletProfile(rect, previewWidth, previewHeight, category)
            val isPalletCandidate = palletScore >= tuning.palletReference.livePalletThreshold

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
                palletScore = palletScore,
            )
        }

        private fun scoreLivePalletProfile(
            rect: RectF,
            previewWidth: Float,
            previewHeight: Float,
            category: String,
        ): Float {
            val widthRatio = rect.width() / previewWidth
            val heightRatio = rect.height() / previewHeight
            val aspectRatio = rect.width() / max(rect.height(), 1f)
            val bottomRatio = rect.bottom / previewHeight
            val sitsNearBottom = bottomRatio > palletReferenceProfile.bottomAnchorRatio - 0.08f
            val categoryHintsPallet = category.contains("Home", ignoreCase = true) ||
                category.contains("Place", ignoreCase = true) ||
                category.contains("Furniture", ignoreCase = true) ||
                category.contains("Table", ignoreCase = true) ||
                category.contains("Wood", ignoreCase = true)
            val aspectMatch = normalizedProfileMatch(
                value = aspectRatio,
                target = palletReferenceProfile.targetAspectRatio,
                tolerance = palletReferenceProfile.aspectTolerance,
            )
            val widthMatch = normalizedProfileMatch(
                value = widthRatio,
                target = palletReferenceProfile.targetWidthRatio,
                tolerance = palletReferenceProfile.widthTolerance,
            )
            val heightMatch = normalizedProfileMatch(
                value = heightRatio,
                target = palletReferenceProfile.targetHeightRatio,
                tolerance = palletReferenceProfile.heightTolerance,
            )
            val bottomMatch = normalizedProfileMatch(
                value = bottomRatio,
                target = palletReferenceProfile.bottomAnchorRatio,
                tolerance = 0.18f,
            )

            var score = 0f
            score += aspectMatch * 0.34f
            score += widthMatch * 0.20f
            score += heightMatch * 0.20f
            score += bottomMatch * 0.16f
            if (categoryHintsPallet) score += 0.10f
            if (sitsNearBottom && aspectRatio > 1.75f && heightRatio < 0.30f) {
                score += 0.08f
            }
            return score.coerceIn(0f, 1f)
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

    companion object {
        @Volatile
        private var isMlKitInitialized = false

        private val mlKitInitLock = Any()

        private fun ensureMlKitInitialized(context: Context) {
            if (isMlKitInitialized) {
                return
            }
            synchronized(mlKitInitLock) {
                if (isMlKitInitialized) {
                    return
                }
                MlKit.initialize(context)
                isMlKitInitialized = true
            }
        }
    }
}
