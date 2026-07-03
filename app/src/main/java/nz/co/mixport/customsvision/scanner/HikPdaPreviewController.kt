package nz.co.mixport.customsvision.scanner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.pda.service.IPdaService
import com.pda.service.RealTimeImageCallback
import java.io.FileInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HikPdaPreviewState(
    val isSupported: Boolean = false,
    val isServiceBound: Boolean = false,
    val isStreaming: Boolean = false,
    val frameBitmap: Bitmap? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val lastFrameAt: Long? = null,
    val errorMessage: String? = null,
)

class HikPdaPreviewController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(
        HikPdaPreviewState(
            isSupported = HikPdaScanBridge.isPdaServiceInstalled(appContext),
        ),
    )
    val state: StateFlow<HikPdaPreviewState> = _state.asStateFlow()

    private var serviceConnection: ServiceConnection? = null
    private var pdaService: IPdaService? = null
    private var previewCallback: RealTimeImageCallback? = null
    private var callbackRegistered = false
    private var lastDecodedAt = 0L
    private var lastFrameSignalAt = 0L
    private var workflowMode = PdaScanWorkflowMode.AUTO_CONTINUOUS
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryPreviewRunnable = object : Runnable {
        override fun run() {
            if (!state.value.isServiceBound) {
                return
            }
            val now = SystemClock.elapsedRealtime()
            val hasRecentFrameSignal = lastFrameSignalAt != 0L &&
                now - lastFrameSignalAt <= PREVIEW_STALE_AFTER_MS
            if (!hasRecentFrameSignal) {
                registerPreviewCallback()
            }
            if (!hasRecentFrameSignal && workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
                runCatching {
                    HikPdaScanBridge.triggerScan(appContext)
                }
            }
            mainHandler.postDelayed(this, PREVIEW_RETRY_INTERVAL_MS)
        }
    }

    fun updateWorkflowMode(mode: PdaScanWorkflowMode) {
        workflowMode = mode
    }

    fun bind() {
        if (!HikPdaScanBridge.isPdaServiceInstalled(appContext)) {
            _state.update {
                it.copy(
                    isSupported = false,
                    errorMessage = "Hikrobot PDA preview service is unavailable on this device.",
                )
            }
            return
        }
        if (serviceConnection != null) {
            return
        }
        val callback = object : RealTimeImageCallback.Stub() {
            override fun onImageInfo(
                fileDescriptor: ParcelFileDescriptor?,
                byteCount: Int,
                width: Int,
                height: Int,
            ) {
                handleFrame(fileDescriptor, byteCount, width, height)
            }
        }
        previewCallback = callback
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                pdaService = IPdaService.Stub.asInterface(service)
                lastFrameSignalAt = 0L
                _state.update {
                    it.copy(
                        isSupported = true,
                        isServiceBound = true,
                        errorMessage = null,
                    )
                }
                registerPreviewCallback()
                if (workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
                    runCatching {
                        HikPdaScanBridge.triggerScan(appContext)
                    }
                }
                schedulePreviewRetry()
                Log.i(TAG, "PDA preview service connected: ${name?.className}")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                mainHandler.removeCallbacks(retryPreviewRunnable)
                callbackRegistered = false
                pdaService = null
                lastFrameSignalAt = 0L
                _state.update {
                    it.copy(
                        isServiceBound = false,
                        isStreaming = false,
                        errorMessage = "PDA preview service disconnected.",
                    )
                }
                Log.w(TAG, "PDA preview service disconnected: ${name?.className}")
            }
        }
        val didBind = runCatching {
            appContext.bindService(
                Intent(SERVICE_BIND_ACTION).apply {
                    setPackage(HikPdaScanBridge.PDA_SERVICE_PACKAGE)
                },
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        serviceConnection = connection
        if (!didBind) {
            serviceConnection = null
            previewCallback = null
            _state.update {
                it.copy(
                    isSupported = true,
                    errorMessage = "Unable to bind Hikrobot PDA preview service.",
                )
            }
        }
        Log.i(TAG, "bind preview service result=$didBind")
    }

    fun unbind() {
        unregisterPreviewCallback()
        serviceConnection?.let { connection ->
            runCatching {
                appContext.unbindService(connection)
            }
        }
        mainHandler.removeCallbacks(retryPreviewRunnable)
        serviceConnection = null
        pdaService = null
        callbackRegistered = false
        previewCallback = null
        lastFrameSignalAt = 0L
        _state.update {
            it.copy(
                isServiceBound = false,
                isStreaming = false,
            )
        }
    }

    private fun registerPreviewCallback() {
        val service = pdaService ?: return
        val callback = previewCallback ?: return
        if (callbackRegistered) {
            return
        }
        runCatching {
            service.registerRealTimeImageCallback(callback)
            callbackRegistered = true
            Log.i(TAG, "Registered PDA preview callback")
        }.onFailure { throwable ->
            _state.update {
                it.copy(
                    isServiceBound = true,
                    errorMessage = throwable.message ?: "Unable to register PDA preview callback.",
                )
            }
            Log.e(TAG, "Failed to register PDA preview callback", throwable)
        }
    }

    private fun unregisterPreviewCallback() {
        val service = pdaService ?: return
        val callback = previewCallback ?: return
        if (!callbackRegistered) {
            return
        }
        runCatching {
            service.unregisterRealTimeImageCallback(callback)
        }.onFailure { throwable ->
            if (throwable !is RemoteException) {
                Log.w(TAG, "Failed to unregister PDA preview callback", throwable)
            }
        }
        callbackRegistered = false
    }

    private fun handleFrame(
        fileDescriptor: ParcelFileDescriptor?,
        byteCount: Int,
        width: Int,
        height: Int,
    ) {
        val descriptor = fileDescriptor ?: return
        val now = SystemClock.elapsedRealtime()
        lastFrameSignalAt = now
        try {
            if (byteCount <= 0 || width <= 0 || height <= 0) {
                _state.update {
                    it.copy(
                        isStreaming = true,
                        lastFrameAt = now,
                        frameWidth = width,
                        frameHeight = height,
                    )
                }
                return
            }
            if (now - lastDecodedAt < FRAME_DECODE_INTERVAL_MS) {
                _state.update {
                    it.copy(
                        isStreaming = true,
                        lastFrameAt = now,
                        frameWidth = width,
                        frameHeight = height,
                    )
                }
                return
            }
            val frameBytes = FileInputStream(descriptor.fileDescriptor).use { stream ->
                val buffer = ByteArray(byteCount)
                var offset = 0
                while (offset < byteCount) {
                    val read = stream.read(buffer, offset, byteCount - offset)
                    if (read <= 0) {
                        break
                    }
                    offset += read
                }
                if (offset == buffer.size) {
                    buffer
                } else {
                    buffer.copyOf(offset)
                }
            }
            val previewBitmap = decodeMonoFrame(
                data = frameBytes,
                width = width,
                height = height,
            )
            lastDecodedAt = now
            _state.update {
                it.copy(
                    isSupported = true,
                    isServiceBound = true,
                    isStreaming = true,
                    frameBitmap = previewBitmap,
                    frameWidth = width,
                    frameHeight = height,
                    lastFrameAt = now,
                    errorMessage = null,
                )
            }
        } catch (throwable: Throwable) {
            _state.update {
                it.copy(
                    errorMessage = throwable.message ?: "Unable to decode PDA preview frame.",
                )
            }
            Log.e(TAG, "Failed to decode PDA preview frame", throwable)
        } finally {
            runCatching {
                descriptor.close()
            }
        }
    }

    private fun schedulePreviewRetry() {
        mainHandler.removeCallbacks(retryPreviewRunnable)
        mainHandler.postDelayed(retryPreviewRunnable, PREVIEW_RETRY_INTERVAL_MS)
    }

    private fun decodeMonoFrame(
        data: ByteArray,
        width: Int,
        height: Int,
    ): Bitmap {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val downsample = if (safeWidth >= 1000 || safeHeight >= 700) 6 else 3
        val outputWidth = (safeWidth / downsample).coerceAtLeast(1)
        val outputHeight = (safeHeight / downsample).coerceAtLeast(1)
        val pixels = IntArray(outputWidth * outputHeight)
        var pixelIndex = 0
        for (y in 0 until outputHeight) {
            val sourceRow = y * downsample * safeWidth
            for (x in 0 until outputWidth) {
                val sourceIndex = (sourceRow + (x * downsample)).coerceAtMost(data.lastIndex)
                val luma = data[sourceIndex].toInt() and 0xFF
                pixels[pixelIndex++] = -0x1000000 or (luma shl 16) or (luma shl 8) or luma
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.RGB_565)
        return if (outputWidth >= outputHeight) {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(90f) },
                true,
            )
        } else {
            bitmap
        }
    }

    private companion object {
        const val TAG = "HikPdaPreview"
        const val SERVICE_BIND_ACTION = "com.hikrobotics.pdaservice.PdaBaseService"
        const val FRAME_DECODE_INTERVAL_MS = 380L
        const val PREVIEW_RETRY_INTERVAL_MS = 1500L
        const val PREVIEW_STALE_AFTER_MS = 3_200L
    }
}
