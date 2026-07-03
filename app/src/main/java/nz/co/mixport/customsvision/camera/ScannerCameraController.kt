package nz.co.mixport.customsvision.camera

import android.content.Context
import android.graphics.Rect
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.EnumSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerCameraController(
    private val context: Context,
) {
    private val decodeHints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(
            DecodeHintType.POSSIBLE_FORMATS,
            EnumSet.of(
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93,
                BarcodeFormat.CODABAR,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.ITF,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E,
                BarcodeFormat.QR_CODE,
                BarcodeFormat.PDF_417,
                BarcodeFormat.DATA_MATRIX,
            ),
        )
        put(DecodeHintType.TRY_HARDER, true)
    }
    private val reader = MultiFormatReader().apply {
        setHints(decodeHints)
    }
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundPreviewView: PreviewView? = null
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var isBinding = false
    private var isCameraBound = false
    private var lastFailureMessage: String? = null
    private var lastDeliveredBarcode: String? = null
    private var lastDeliveredAt: Long = 0L

    @Volatile
    private var isProcessing = false

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onBarcodeDetected: (String) -> Unit,
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
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(
                                cameraExecutor,
                                BarcodeAnalyzer(
                                    onBarcodeDetected = onBarcodeDetected,
                                    onError = onError,
                                ),
                            )
                        }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )

                    isCameraBound = true
                    isBinding = false
                }.onFailure { throwable ->
                    isBinding = false
                    isCameraBound = false
                    onError(throwable.message ?: "Failed to bind the scanner camera.")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        boundPreviewView = null
        boundLifecycleOwner = null
        isBinding = false
        isCameraBound = false
        isProcessing = false
    }

    fun release() {
        unbind()
        cameraProvider = null
        cameraExecutor.shutdown()
    }

    private inner class BarcodeAnalyzer(
        private val onBarcodeDetected: (String) -> Unit,
        private val onError: (String) -> Unit,
    ) : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            if (isProcessing) {
                imageProxy.close()
                return
            }

            isProcessing = true
            val result = runCatching {
                decodeImageProxy(imageProxy)
            }

            result.onSuccess { decoded ->
                if (decoded == null) {
                    return@onSuccess
                }
                val now = System.currentTimeMillis()
                if (
                    decoded == lastDeliveredBarcode &&
                    now - lastDeliveredAt < SAME_BARCODE_COOLDOWN_MS
                ) {
                    return@onSuccess
                }
                lastDeliveredBarcode = decoded
                lastDeliveredAt = now
                onBarcodeDetected(decoded)
            }.onFailure { throwable ->
                val message = throwable.message ?: "Barcode scanning failed."
                if (message != lastFailureMessage) {
                    lastFailureMessage = message
                    onError(message)
                }
            }

            isProcessing = false
            imageProxy.close()
        }
    }

    private fun decodeImageProxy(imageProxy: ImageProxy): String? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val luma = copyLumaPlane(plane.buffer, width, height, plane.rowStride)
        val rotations = decodeAttemptsFor(imageProxy.imageInfo.rotationDegrees, luma, width, height)

        for ((data, rotatedWidth, rotatedHeight) in rotations) {
            val centerCrop = centeredCrop(rotatedWidth, rotatedHeight)
            val source = PlanarYUVLuminanceSource(
                data,
                rotatedWidth,
                rotatedHeight,
                centerCrop.left,
                centerCrop.top,
                centerCrop.width(),
                centerCrop.height(),
                false,
            )
            val text = decodeSource(source)?.text?.trim()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        return null
    }

    private fun decodeSource(source: PlanarYUVLuminanceSource): Result? {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decodeWithState(bitmap)
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun centeredCrop(width: Int, height: Int): Rect {
        val cropWidth = (width * 0.82f).toInt().coerceAtLeast(1)
        val cropHeight = (height * 0.34f).toInt().coerceAtLeast(1)
        val left = ((width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((height - cropHeight) / 2).coerceAtLeast(0)
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun decodeAttemptsFor(
        rotationDegrees: Int,
        data: ByteArray,
        width: Int,
        height: Int,
    ): List<DecodeAttempt> {
        val upright = when (rotationDegrees) {
            90 -> rotateLuma90(data, width, height)
            180 -> rotateLuma180(data, width, height)
            270 -> rotateLuma270(data, width, height)
            else -> DecodeAttempt(data, width, height)
        }
        val fallbackRotated = rotateLuma90(upright.data, upright.width, upright.height)
        return listOf(upright, fallbackRotated)
    }

    private fun rotateLuma90(data: ByteArray, width: Int, height: Int): DecodeAttempt {
        val rotated = ByteArray(data.size)
        var index = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[index++] = data[y * width + x]
            }
        }
        return DecodeAttempt(rotated, height, width)
    }

    private fun rotateLuma180(data: ByteArray, width: Int, height: Int): DecodeAttempt {
        val rotated = ByteArray(data.size)
        var index = 0
        for (i in data.indices.reversed()) {
            rotated[index++] = data[i]
        }
        return DecodeAttempt(rotated, width, height)
    }

    private fun rotateLuma270(data: ByteArray, width: Int, height: Int): DecodeAttempt {
        val rotated = ByteArray(data.size)
        var index = 0
        for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                rotated[index++] = data[y * width + x]
            }
        }
        return DecodeAttempt(rotated, height, width)
    }

    private fun copyLumaPlane(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
    ): ByteArray {
        buffer.rewind()
        return if (rowStride == width) {
            ByteArray(width * height).also(buffer::get)
        } else {
            val rows = ByteArray(rowStride * height).also(buffer::get)
            ByteArray(width * height).also { compact ->
                for (row in 0 until height) {
                    System.arraycopy(rows, row * rowStride, compact, row * width, width)
                }
            }
        }
    }

    private data class DecodeAttempt(
        val data: ByteArray,
        val width: Int,
        val height: Int,
    )

    private companion object {
        private const val SAME_BARCODE_COOLDOWN_MS = 1_400L
    }
}
