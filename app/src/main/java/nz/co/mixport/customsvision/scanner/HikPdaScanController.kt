package nz.co.mixport.customsvision.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

class HikPdaScanController(
    context: Context,
) {
    private val tag = "HikPdaScanCtrl"
    private val appContext = context.applicationContext
    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false
    private var shouldKeepScanning = false
    private var workflowMode = PdaScanWorkflowMode.AUTO_CONTINUOUS
    private var pendingSingleTrigger = false

    fun isPdaServiceInstalled(): Boolean = HikPdaScanBridge.isPdaServiceInstalled(appContext)

    fun bind(
        workflowMode: PdaScanWorkflowMode,
        onReady: (String) -> Unit,
        onBarcodeDetected: (String, String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (!isPdaServiceInstalled()) {
            return false
        }
        this.workflowMode = workflowMode
        runCatching {
            ensureReceiverRegistered(onBarcodeDetected)
            "FDA scanner bridge ready."
        }.onSuccess(onReady)
            .onFailure { throwable ->
                onError(throwable.message ?: "Unable to prepare Hikrobot PDA scan bridge.")
            }
        return true
    }

    fun triggerScan(): Result<String> = runCatching {
        if (workflowMode == PdaScanWorkflowMode.TRIGGER_ONCE) {
            pendingSingleTrigger = true
        } else {
            shouldKeepScanning = true
        }
        HikPdaScanBridge.triggerScan(appContext)
        "FDA scanner trigger sent."
    }

    fun triggerSingleScan(): Result<String> = runCatching {
        workflowMode = PdaScanWorkflowMode.TRIGGER_ONCE
        pendingSingleTrigger = true
        HikPdaScanBridge.triggerScan(appContext)
        "FDA single scan trigger sent."
    }

    fun stopScan(): Result<String> = runCatching {
        shouldKeepScanning = false
        pendingSingleTrigger = false
        HikPdaScanBridge.stopScan(appContext)
        "FDA scanner paused."
    }

    fun resumeAutoScan(): Result<String> = runCatching {
        applyWorkflowMode(PdaScanWorkflowMode.AUTO_CONTINUOUS).getOrThrow()
    }

    fun applyWorkflowMode(mode: PdaScanWorkflowMode): Result<String> = runCatching {
        workflowMode = mode
        pendingSingleTrigger = false
        shouldKeepScanning = mode == PdaScanWorkflowMode.AUTO_CONTINUOUS
        HikPdaScanBridge.configureForMixportWorkflow(appContext, mode)
        if (mode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
            HikPdaScanBridge.triggerScan(appContext)
            "FDA scanner configured for continuous auto scan."
        } else {
            "FDA scanner configured for side-button and single-trigger scanning."
        }
    }

    fun reconfigure(mode: PdaScanWorkflowMode = workflowMode): Result<String> = runCatching {
        applyWorkflowMode(mode).getOrThrow()
    }

    fun unbind() {
        shouldKeepScanning = false
        pendingSingleTrigger = false
        runCatching {
            HikPdaScanBridge.stopScan(appContext)
        }
        val boundReceiver = receiver ?: return
        if (isRegistered) {
            runCatching {
                appContext.unregisterReceiver(boundReceiver)
            }
        }
        receiver = null
        isRegistered = false
    }

    private fun ensureReceiverRegistered(
        onBarcodeDetected: (String, String) -> Unit,
    ) {
        if (isRegistered) {
            return
        }
        val scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val broadcast = intent ?: return
                HikPdaScanBridge.applyScannerBroadcast(broadcast)
                if (broadcast.action == HikPdaScanBridge.ACTION_CAMERA_INIT_COMPLETE) {
                    if (shouldKeepScanning) {
                        Log.i(tag, "Camera init complete; re-triggering continuous FDA scan.")
                        runCatching {
                            HikPdaScanBridge.triggerScan(appContext)
                        }
                    } else if (pendingSingleTrigger) {
                        Log.i(tag, "Camera init complete; re-triggering single FDA scan.")
                        runCatching {
                            HikPdaScanBridge.triggerScan(appContext)
                        }
                        pendingSingleTrigger = false
                    }
                }
                if (broadcast.action != HikPdaScanBridge.RESULT_ACTION &&
                    broadcast.action != HikPdaScanBridge.STOCK_RESULT_ACTION &&
                    broadcast.action != HikPdaScanBridge.ACTION_TEST_RESULT
                ) {
                    return
                }
                val code = broadcast.getStringExtra(HikPdaScanBridge.KEY_CODE)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: broadcast.getByteArrayExtra(HikPdaScanBridge.KEY_BYTES)
                        ?.toString(Charsets.UTF_8)
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                    ?: return
                val codeType = broadcast.getStringExtra(HikPdaScanBridge.KEY_CODE_TYPE).orEmpty()
                Log.i(tag, "Received PDA code via ${broadcast.action}: $code [$codeType]")
                pendingSingleTrigger = false
                onBarcodeDetected(code, codeType)
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            scanReceiver,
            IntentFilter(HikPdaScanBridge.RESULT_ACTION).apply {
                addAction(HikPdaScanBridge.STOCK_RESULT_ACTION)
                addAction(HikPdaScanBridge.ACTION_DEFAULT_SCAN_CONFIG)
                addAction(HikPdaScanBridge.ACTION_DEFAULT_OUTPUT_CONFIG)
                addAction(HikPdaScanBridge.ACTION_DEFAULT_IMAGE_PARAM)
                addAction(HikPdaScanBridge.ACTION_DEFAULT_RUN_STATUS)
                addAction(HikPdaScanBridge.ACTION_STATUS_SERVICE_TO_ASSIST)
                addAction(HikPdaScanBridge.ACTION_CAMERA_INIT_COMPLETE)
                addAction(HikPdaScanBridge.ACTION_TEST_RESULT)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiver = scanReceiver
        isRegistered = true
    }
}
