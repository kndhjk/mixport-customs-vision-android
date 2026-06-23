package nz.co.mixport.customsvision.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InspectionCameraController(
    private val context: Context,
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var boundPreviewView: PreviewView? = null
    private var boundLifecycleOwner: LifecycleOwner? = null

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onFrameHeartbeat: (Long) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (boundPreviewView === previewView &&
            boundLifecycleOwner === lifecycleOwner &&
            videoCapture != null
        ) {
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val cameraProvider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.getSurfaceProvider())
                    }
                    val recorder = Recorder.Builder().build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor, FrameHeartbeatAnalyzer(onFrameHeartbeat))
                        }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        videoCapture,
                        imageAnalysis,
                    )

                    boundPreviewView = previewView
                    boundLifecycleOwner = lifecycleOwner
                }.onFailure { throwable ->
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
        cameraExecutor.shutdown()
    }

    private class FrameHeartbeatAnalyzer(
        private val onFrameHeartbeat: (Long) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private var lastDeliveredAt: Long = 0L

        override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
            val now = System.currentTimeMillis()
            if (now - lastDeliveredAt >= 1000L) {
                lastDeliveredAt = now
                onFrameHeartbeat(now)
            }
            imageProxy.close()
        }
    }
}
