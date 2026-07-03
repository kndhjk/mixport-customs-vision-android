package nz.co.mixport.customsvision.scanner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

enum class PdaOutputMode(val wireValue: String) {
    BROADCAST("BROADCASTMODE"),
}

enum class PdaOutputObject(val wireValue: String) {
    PDA("PDA"),
}

enum class PdaScanMode(val wireValue: String) {
    MANUAL("MANUALMODE"),
    SINGLE("SINGLEMODE"),
    CONTINUOUS("CONTINUOUSMODE"),
}

enum class PdaScanType(val wireValue: String) {
    SINGLE("SINGLE"),
    CONTINUOUS("CONTINUOUS"),
}

enum class PdaScanEndType(val wireValue: String) {
    RELEASE("RELEASE"),
    CLICK("CLICK"),
    RELEASE_START("RELEASESTART"),
}

enum class PdaWorkingDistance(val level: Int) {
    DEVICE_DEFAULT(1000),
}

object HikPdaScanBridge {
    private const val TAG = "HikPdaScanBridge"

    const val PDA_SERVICE_PACKAGE = "com.hikrobotics.pdaservice"
    private const val LAUNCH_RECEIVER_CLASS = "com.pda.service.broadcast.LaunchReceiver"
    private const val CONTROL_RECEIVER_CLASS = "com.pda.service.broadcast.ServiceControlReceiver"

    private const val ACTION_SERVICE_START = "com.pda.service.start"
    private const val ACTION_SERVICE_STOP = "com.pda.service.stop"
    private const val ACTION_SERVICE_RESTART = "com.service.scanner.restart.pda.service"
    private const val ACTION_REQUEST_INIT_STATUS = "com.service.scanner.initstatus"
    private const val ACTION_REQUEST_INIT_STATUS_SCAN_CONFIG = "com.service.scanner.init.status.scan.config"
    private const val ACTION_REQUEST_INIT_STATUS_OUTPUT_CONFIG = "com.service.scanner.init.status.output.config"
    private const val ACTION_REQUEST_INIT_STATUS_IMAGE_PARAM = "com.service.scanner.init.status.image.param"
    private const val ACTION_REQUEST_INIT_STATUS_RUN_STATUS = "com.service.scanner.init.status.run.status"
    private const val ACTION_STATUS_VALUES = "com.service.scanner.statusvalues"
    private const val ACTION_CUSTOM_START_READ_CODE = "com.service.scanner.start.read.code.broadcast"
    private const val ACTION_CUSTOM_STOP_READ_CODE = "com.service.scanner.stop.read.code.broadcast"
    private const val ACTION_START_SCANNING = "com.service.scanner.start.scanning"
    private const val ACTION_STOP_SCANNING = "com.service.scanner.stop.scanning"
    private const val ACTION_LEGACY_START_SCANNING = "com.pda.scanner.start"
    private const val ACTION_LEGACY_STOP_SCANNING = "com.pda.scanner.stop"
    private const val ACTION_OUTPUT_MODE = "com.service.scanner.outputmode"
    private const val ACTION_OUTPUT_OBJECT = "com.service.scanner.outputobject"
    private const val ACTION_BROADCAST_OUTPUT = "com.service.scanner.broadcast.output"
    private const val ACTION_CLIPBOARD_OUTPUT = "com.service.scanner.clipboard.output"
    private const val ACTION_PC_OUTPUT = "com.service.scanner.pc.output"
    private const val ACTION_BROAD_ACTION = "com.service.scanner.config.broad.action"
    private const val ACTION_BROAD_CODE = "com.service.scanner.config.broad.code"
    private const val ACTION_BROAD_CODE_BYTE = "com.service.scanner.config.broad.code.byte"
    private const val ACTION_BROAD_CODE_TYPE = "com.service.scanner.config.broad.code.type"
    private const val ACTION_BROAD_IMAGE = "com.service.scanner.config.broad.image"
    private const val ACTION_BROAD_OCR = "com.service.scanner.config.broad.ocr"
    private const val ACTION_BROAD_WITH_IMAGE = "com.service.scanner.config.broad.with.image.enable"
    private const val ACTION_FOCUS_SPOT_COVER = "com.service.scanner.focus.spot.cover"
    private const val ACTION_FOCUS_SPOT_OUTPUT = "com.service.scanner.focus.spot.output"
    private const val ACTION_SAVE_IMAGE = "com.service.scanner.saveimage"
    private const val ACTION_SCAN_MODE = "com.service.scanner.scanmode"
    private const val ACTION_SCAN_TYPE = "com.service.scanner.scan.type"
    private const val ACTION_CONTINUOUS_END_TYPE = "com.service.scanner.scan.continue.end.type"
    private const val ACTION_CONTINUOUS_TIME = "com.service.scanner.scan.continuous.time"
    private const val ACTION_CONTINUOUS_INTERVAL = "com.service.scanner.scan.continuous.interval"
    private const val ACTION_END_COUNT = "com.service.scanner.scan.end.count"
    private const val ACTION_REPEATED_FILTER = "com.service.scanner.repeated.filter"
    private const val ACTION_WORKING_DISTANCE = "com.service.scanner.working.distance"
    private const val ACTION_LIGHT_BRIGHTNESS = "com.service.scanner.lightness"
    private const val ACTION_FILL_SIGHT = "com.service.scanner.fillsight"
    private const val ACTION_SIGHT = "com.service.scanner.sight"
    private const val ACTION_AUTO_EXPOSURE = "com.service.scanner.auto.exposure"
    private const val ACTION_AEC_MODE = "com.service.scanner.aec.mode"
    private const val ACTION_PRECISE_ENABLE = "com.service.scanner.precise.enable"
    private const val ACTION_COMPLICATED_CODE_ENABLE = "com.service.scanner.complicated.code.enable"
    private const val ACTION_COMPLICATED_CODE_MODE = "com.service.scanner.complicated.code.mode"
    private const val ACTION_SCAN_SHOCK = "com.service.scanner.shock"
    private const val ACTION_SCAN_VIBRATOR_DURATION = "com.service.scanner.shock.duration"
    private const val ACTION_SCAN_VOICE = "com.service.scanner.voice"
    private const val ACTION_USER_APPLICATION_ID = "com.user.applicationid.set"

    const val ACTION_DEFAULT_SCAN_CONFIG = "com.service.scanner.default.values.scan.config"
    const val ACTION_DEFAULT_OUTPUT_CONFIG = "com.service.scanner.default.values.output.config"
    const val ACTION_DEFAULT_IMAGE_PARAM = "com.service.scanner.default.values.image.param"
    const val ACTION_DEFAULT_RUN_STATUS = "com.service.scanner.default.values.run.status"
    const val ACTION_STATUS_SERVICE_TO_ASSIST = "com.service.scanner.statusvalues.service2assist"
    const val ACTION_CAMERA_INIT_COMPLETE = "com.service.init.complete"
    const val ACTION_TEST_RESULT = "com.service.scanner.test.result"

    private const val EXTRA_BROAD_ACTION = "broad_action"
    private const val EXTRA_BROAD_CODE = "broad_code"
    private const val EXTRA_BROAD_CODE_BYTE = "broad_code_byte"
    private const val EXTRA_BROAD_CODE_TYPE = "broad_code_type"
    private const val EXTRA_BROAD_IMAGE = "broad_image"
    private const val EXTRA_BROAD_OCR = "broad_ocr"
    private const val EXTRA_BROAD_WITH_IMAGE = "broad_with_image"
    private const val EXTRA_FOCUS_SPOT_COVER = "focusSpotCover"
    private const val EXTRA_FOCUS_SPOT_OUTPUT = "focusSpotOutput"
    private const val EXTRA_OUTPUT_MODE = "outputMode"
    private const val EXTRA_OUTPUT_OBJECT = "outputObject"
    private const val EXTRA_BROADCAST_OUTPUT = "broadcastOutput"
    private const val EXTRA_CLIPBOARD_OUTPUT = "clipboardOutput"
    private const val EXTRA_PC_OUTPUT = "pcOutput"
    private const val EXTRA_SAVE_IMAGE_ENABLE = "saveImageEnable"
    private const val EXTRA_JPEG_QUALITY = "jpgImageQuality"
    private const val EXTRA_SCAN_MODE = "scanMode"
    private const val EXTRA_SCAN_TYPE = "scanType"
    private const val EXTRA_SCAN_CONTINUOUS_END_TYPE = "scanContinueEndType"
    private const val EXTRA_SCAN_CONTINUOUS_TIME = "continuousTime"
    private const val EXTRA_SCAN_INTERVAL = "intervalTime"
    private const val EXTRA_END_COUNT = "endCount"
    private const val EXTRA_REPEATED_FILTER = "scanCodeFilterRepeated"
    private const val EXTRA_WORKING_DISTANCE = "workingDistance"
    private const val EXTRA_LIGHT_BRIGHTNESS = "lightBrightness"
    private const val EXTRA_FILL_SIGHT_STATUS = "fillSightStatus"
    private const val EXTRA_SIGHT_STATUS = "sightStatus"
    private const val EXTRA_AUTO_EXPOSURE = "autoExposure"
    private const val EXTRA_AEC_MODE = "aecMode"
    private const val EXTRA_PRECISE_ENABLE = "preciseEnable"
    private const val EXTRA_COMPLICATED_CODE_ENABLE = "complicatedCodeEnable"
    private const val EXTRA_COMPLICATED_CODE_MODE = "complicatedCodeMode"
    private const val EXTRA_SCAN_SHOCK = "scanShock"
    private const val EXTRA_VIBRATOR_DURATION = "vibratorDuration"
    private const val EXTRA_SCAN_VOICE = "scanVoice"
    private const val EXTRA_CUSTOM_START_ACTION = "customStartReadCodeBroadcast"
    private const val EXTRA_CUSTOM_STOP_ACTION = "customStopReadCodeBroadcast"
    private const val EXTRA_DEFAULT_VALUE = "default_value"
    private const val EXTRA_SERVICE_MESSAGE = "ServiceMessage"
    private const val EXTRA_APPLICATION_ID = "applicationId"
    private const val EXTRA_APPLICATION_ID_ALT = "applicationID"
    private const val EXTRA_PACKAGE_NAME = "packageName"
    private const val EXTRA_SERVICE_INIT = "serviceinit"

    private const val DEFAULT_SCAN_INTERVAL_MS = 1_000
    private const val DEFAULT_SINGLE_TIMEOUT_MS = 5_000
    private const val DEFAULT_CONTINUOUS_TIMEOUT_SECONDS = 100
    private const val DEFAULT_LIGHT_BRIGHTNESS = 24
    private const val DEFAULT_VIBRATOR_DURATION_MS = 80

    private val launchComponent = ComponentName(PDA_SERVICE_PACKAGE, LAUNCH_RECEIVER_CLASS)
    private val controlComponent = ComponentName(PDA_SERVICE_PACKAGE, CONTROL_RECEIVER_CLASS)

    const val RESULT_ACTION = "nz.co.mixport.customsvision.PDA_SCAN_RESULT"
    const val STOCK_RESULT_ACTION = "com.service.scanner.data"
    const val KEY_CODE = "ScanCode"
    const val KEY_CODE_TYPE = "ScanCodeType"
    const val KEY_BYTES = "ScanCodeBytes"
    const val KEY_IMAGE = "ScanJpegData"
    const val KEY_OCR = "ScanOcrPhone"

    @Volatile
    private var startReadAction = ACTION_START_SCANNING

    @Volatile
    private var stopReadAction = ACTION_STOP_SCANNING

    fun isPdaServiceInstalled(context: Context): Boolean {
        val packageManager = context.packageManager
        val hasPackage = runCatching {
            packageManager.getApplicationInfo(PDA_SERVICE_PACKAGE, 0)
        }.isSuccess
        val hasLaunchReceiver = runCatching {
            packageManager.getReceiverInfo(launchComponent, 0)
        }.isSuccess
        val hasControlReceiver = runCatching {
            packageManager.getReceiverInfo(controlComponent, 0)
        }.isSuccess
        return hasPackage || (hasLaunchReceiver && hasControlReceiver)
    }

    fun configureForMixportWorkflow(
        context: Context,
        workflowMode: PdaScanWorkflowMode = PdaScanWorkflowMode.AUTO_CONTINUOUS,
    ) {
        startService(context)
        setApplicationIdentity(context)
        setCustomReadActions(
            context = context,
            startAction = ACTION_START_SCANNING,
            stopAction = ACTION_STOP_SCANNING,
        )
        configureOutput(context)
        configureDecodeProfile(context)
        when (workflowMode) {
            PdaScanWorkflowMode.AUTO_CONTINUOUS -> configureContinuousWorkflow(context)
            PdaScanWorkflowMode.TRIGGER_ONCE -> configureTriggerWorkflow(context)
        }
        queryStatus(context)
    }

    fun prepareRuntime(context: Context) {
        startService(context)
        setApplicationIdentity(context)
        queryStatus(context)
    }

    fun startService(context: Context) {
        send(
            context = context,
            action = ACTION_SERVICE_START,
            component = launchComponent,
            includeStoppedPackages = true,
        )
    }

    fun stopService(context: Context) {
        send(context = context, action = ACTION_SERVICE_STOP, component = launchComponent)
    }

    fun restartService(context: Context) {
        send(context = context, action = ACTION_SERVICE_RESTART, component = controlComponent)
    }

    fun triggerScan(context: Context) {
        setFrontFillLight(context, true)
        setAimerLight(context, true)
        setFocusSpotCover(context, enabled = true)
        sendLegacyTriggerAction(context, ACTION_LEGACY_START_SCANNING)
        sendTriggerAction(context, startReadAction)
    }

    fun stopScan(context: Context) {
        sendTriggerAction(context, stopReadAction)
        sendLegacyTriggerAction(context, ACTION_LEGACY_STOP_SCANNING)
        setFocusSpotCover(context, enabled = false)
        setAimerLight(context, false)
        setFrontFillLight(context, false)
    }

    private fun configureDecodeProfile(context: Context) {
        setRepeatedFilter(context, true)
        setLightBrightness(context, DEFAULT_LIGHT_BRIGHTNESS)
        setFocusSpotOutput(context, enabled = true)
        setAutoExposure(context, enabled = true)
        setPreciseMode(context, enabled = true)
        setComplicatedCode(context, enabled = true)
        setShock(context, enabled = true, durationMs = DEFAULT_VIBRATOR_DURATION_MS)
        setVoice(context, true)
        setWorkingDistance(context, PdaWorkingDistance.DEVICE_DEFAULT)
        setFrontFillLight(context, true)
        setAimerLight(context, true)
    }

    private fun configureContinuousWorkflow(context: Context) {
        setScanMode(
            context = context,
            mode = PdaScanMode.CONTINUOUS,
            intervalMs = 0,
            singleTimeMs = DEFAULT_SINGLE_TIMEOUT_MS,
        )
        setScanType(context, PdaScanType.CONTINUOUS)
        setContinuousEndType(context, PdaScanEndType.RELEASE)
        setContinuousTimeoutSeconds(context, DEFAULT_CONTINUOUS_TIMEOUT_SECONDS)
        setEndCount(context, 0)
        setFocusSpotCover(context, enabled = true)
    }

    private fun configureTriggerWorkflow(context: Context) {
        setScanMode(
            context = context,
            mode = PdaScanMode.SINGLE,
            intervalMs = 0,
            singleTimeMs = DEFAULT_SINGLE_TIMEOUT_MS,
        )
        setScanType(context, PdaScanType.SINGLE)
        setEndCount(context, 1)
        setFocusSpotCover(context, enabled = true)
    }

    fun applyScannerBroadcast(intent: Intent) {
        when (intent.action) {
            ACTION_DEFAULT_SCAN_CONFIG -> {
                val defaultValues = intent.defaultValueMap()
                val configuredStart = intent.getStringExtra(EXTRA_CUSTOM_START_ACTION)
                    ?.takeIf(String::isNotBlank)
                    ?: defaultValues.stringValue(EXTRA_CUSTOM_START_ACTION)
                    ?.takeIf(String::isNotBlank)
                val configuredStop = intent.getStringExtra(EXTRA_CUSTOM_STOP_ACTION)
                    ?.takeIf(String::isNotBlank)
                    ?: defaultValues.stringValue(EXTRA_CUSTOM_STOP_ACTION)
                    ?.takeIf(String::isNotBlank)
                startReadAction = configuredStart ?: ACTION_START_SCANNING
                stopReadAction = configuredStop ?: ACTION_STOP_SCANNING
                logIncoming(intent)
            }

            ACTION_DEFAULT_OUTPUT_CONFIG,
            ACTION_DEFAULT_IMAGE_PARAM,
            ACTION_DEFAULT_RUN_STATUS,
            ACTION_STATUS_SERVICE_TO_ASSIST,
            ACTION_CAMERA_INIT_COMPLETE,
            ACTION_TEST_RESULT,
            STOCK_RESULT_ACTION,
            RESULT_ACTION -> logIncoming(intent)
        }
    }

    fun queryStatus(context: Context) {
        send(context = context, action = ACTION_REQUEST_INIT_STATUS, component = controlComponent)
        send(context = context, action = ACTION_REQUEST_INIT_STATUS_SCAN_CONFIG, component = controlComponent)
        send(context = context, action = ACTION_REQUEST_INIT_STATUS_OUTPUT_CONFIG, component = controlComponent)
        send(context = context, action = ACTION_REQUEST_INIT_STATUS_IMAGE_PARAM, component = controlComponent)
        send(context = context, action = ACTION_REQUEST_INIT_STATUS_RUN_STATUS, component = controlComponent)
        send(context = context, action = ACTION_STATUS_VALUES, component = controlComponent)
    }

    private fun configureOutput(context: Context) {
        send(
            context = context,
            action = ACTION_BROAD_ACTION,
            component = controlComponent,
            extras = listOf(EXTRA_BROAD_ACTION to STOCK_RESULT_ACTION),
        )
        send(
            context = context,
            action = ACTION_BROAD_CODE,
            component = controlComponent,
            extras = listOf(EXTRA_BROAD_CODE to KEY_CODE),
        )
        send(
            context = context,
            action = ACTION_BROAD_CODE_BYTE,
            component = controlComponent,
            extras = listOf(EXTRA_BROAD_CODE_BYTE to KEY_BYTES),
        )
        send(
            context = context,
            action = ACTION_BROAD_CODE_TYPE,
            component = controlComponent,
            extras = listOf(EXTRA_BROAD_CODE_TYPE to KEY_CODE_TYPE),
        )
        send(
            context = context,
            action = ACTION_BROAD_IMAGE,
            component = controlComponent,
            extras = listOf(EXTRA_BROAD_IMAGE to KEY_IMAGE),
        )
        send(
            context = context,
            action = ACTION_BROAD_OCR,
            component = controlComponent,
            extras = listOf(EXTRA_BROAD_OCR to KEY_OCR),
        )
        send(
            context = context,
            action = ACTION_BROAD_WITH_IMAGE,
            component = controlComponent,
            extras = listOf(EXTRA_BROAD_WITH_IMAGE to false),
        )
        send(
            context = context,
            action = ACTION_OUTPUT_MODE,
            component = controlComponent,
            extras = listOf(EXTRA_OUTPUT_MODE to PdaOutputMode.BROADCAST.wireValue),
        )
        send(
            context = context,
            action = ACTION_OUTPUT_OBJECT,
            component = controlComponent,
            extras = listOf(EXTRA_OUTPUT_OBJECT to PdaOutputObject.PDA.wireValue),
        )
        send(
            context = context,
            action = ACTION_BROADCAST_OUTPUT,
            component = controlComponent,
            extras = listOf(EXTRA_BROADCAST_OUTPUT to true),
        )
        send(
            context = context,
            action = ACTION_CLIPBOARD_OUTPUT,
            component = controlComponent,
            extras = listOf(EXTRA_CLIPBOARD_OUTPUT to false),
        )
        send(
            context = context,
            action = ACTION_PC_OUTPUT,
            component = controlComponent,
            extras = listOf(EXTRA_PC_OUTPUT to false),
        )
        send(
            context = context,
            action = ACTION_SAVE_IMAGE,
            component = controlComponent,
            extras = listOf(
                EXTRA_SAVE_IMAGE_ENABLE to false,
                EXTRA_JPEG_QUALITY to 70,
            ),
        )
    }

    private fun setApplicationIdentity(context: Context) {
        val packageName = context.packageName
        send(
            context = context,
            action = ACTION_USER_APPLICATION_ID,
            component = controlComponent,
            extras = listOf(
                EXTRA_APPLICATION_ID to packageName,
                EXTRA_APPLICATION_ID_ALT to packageName,
                EXTRA_PACKAGE_NAME to packageName,
            ),
        )
    }

    private fun setCustomReadActions(
        context: Context,
        startAction: String,
        stopAction: String,
    ) {
        startReadAction = startAction
        stopReadAction = stopAction
        send(
            context = context,
            action = ACTION_CUSTOM_START_READ_CODE,
            component = controlComponent,
            extras = listOf(EXTRA_CUSTOM_START_ACTION to startAction),
        )
        send(
            context = context,
            action = ACTION_CUSTOM_STOP_READ_CODE,
            component = controlComponent,
            extras = listOf(EXTRA_CUSTOM_STOP_ACTION to stopAction),
        )
    }

    private fun setScanMode(
        context: Context,
        mode: PdaScanMode,
        intervalMs: Int,
        singleTimeMs: Int,
    ) {
        send(
            context = context,
            action = ACTION_SCAN_MODE,
            component = controlComponent,
            extras = listOf(
                EXTRA_SCAN_MODE to mode.wireValue,
                EXTRA_SCAN_INTERVAL to intervalMs.coerceIn(0, 10_000),
                EXTRA_SCAN_CONTINUOUS_TIME to DEFAULT_CONTINUOUS_TIMEOUT_SECONDS,
                EXTRA_SCAN_TYPE to PdaScanType.CONTINUOUS.wireValue,
                "singleTime" to singleTimeMs.coerceIn(3_000, 10_000),
            ),
        )
    }

    private fun setScanType(context: Context, scanType: PdaScanType) {
        send(
            context = context,
            action = ACTION_SCAN_TYPE,
            component = controlComponent,
            extras = listOf(EXTRA_SCAN_TYPE to scanType.wireValue),
        )
    }

    private fun setContinuousEndType(context: Context, endType: PdaScanEndType) {
        send(
            context = context,
            action = ACTION_CONTINUOUS_END_TYPE,
            component = controlComponent,
            extras = listOf(EXTRA_SCAN_CONTINUOUS_END_TYPE to endType.wireValue),
        )
    }

    private fun setContinuousTimeoutSeconds(context: Context, seconds: Int) {
        send(
            context = context,
            action = ACTION_CONTINUOUS_TIME,
            component = controlComponent,
            extras = listOf(EXTRA_SCAN_CONTINUOUS_TIME to seconds.coerceIn(10, 100)),
        )
    }

    private fun setEndCount(context: Context, count: Int) {
        send(
            context = context,
            action = ACTION_END_COUNT,
            component = controlComponent,
            extras = listOf(EXTRA_END_COUNT to count.coerceIn(0, 100)),
        )
    }

    private fun setRepeatedFilter(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_REPEATED_FILTER,
            component = controlComponent,
            extras = listOf(EXTRA_REPEATED_FILTER to enabled),
        )
    }

    private fun setFocusSpotOutput(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_FOCUS_SPOT_OUTPUT,
            component = controlComponent,
            extras = listOf(EXTRA_FOCUS_SPOT_OUTPUT to enabled),
        )
    }

    private fun setFocusSpotCover(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_FOCUS_SPOT_COVER,
            component = controlComponent,
            extras = listOf(EXTRA_FOCUS_SPOT_COVER to enabled),
        )
    }

    private fun setWorkingDistance(context: Context, workingDistance: PdaWorkingDistance) {
        send(
            context = context,
            action = ACTION_WORKING_DISTANCE,
            component = controlComponent,
            extras = listOf(EXTRA_WORKING_DISTANCE to workingDistance.level),
        )
    }

    private fun setLightBrightness(context: Context, brightness: Int) {
        send(
            context = context,
            action = ACTION_LIGHT_BRIGHTNESS,
            component = controlComponent,
            extras = listOf(EXTRA_LIGHT_BRIGHTNESS to brightness.coerceIn(1, 24)),
        )
    }

    private fun setFrontFillLight(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_FILL_SIGHT,
            component = controlComponent,
            extras = listOf(EXTRA_FILL_SIGHT_STATUS to enabled),
        )
    }

    private fun setAimerLight(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_SIGHT,
            component = controlComponent,
            extras = listOf(EXTRA_SIGHT_STATUS to enabled),
        )
    }

    private fun setAutoExposure(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_AUTO_EXPOSURE,
            component = controlComponent,
            extras = listOf(EXTRA_AUTO_EXPOSURE to enabled),
        )
        if (enabled) {
            send(
                context = context,
                action = ACTION_AEC_MODE,
                component = controlComponent,
                extras = listOf(EXTRA_AEC_MODE to 1),
            )
        }
    }

    private fun setComplicatedCode(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_COMPLICATED_CODE_ENABLE,
            component = controlComponent,
            extras = listOf(EXTRA_COMPLICATED_CODE_ENABLE to if (enabled) 1 else 0),
        )
        send(
            context = context,
            action = ACTION_COMPLICATED_CODE_MODE,
            component = controlComponent,
            extras = listOf(EXTRA_COMPLICATED_CODE_MODE to 0),
        )
    }

    private fun setPreciseMode(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_PRECISE_ENABLE,
            component = controlComponent,
            extras = listOf(EXTRA_PRECISE_ENABLE to enabled),
        )
    }

    private fun setShock(
        context: Context,
        enabled: Boolean,
        durationMs: Int,
    ) {
        send(
            context = context,
            action = ACTION_SCAN_SHOCK,
            component = controlComponent,
            extras = listOf(EXTRA_SCAN_SHOCK to enabled),
        )
        send(
            context = context,
            action = ACTION_SCAN_VIBRATOR_DURATION,
            component = controlComponent,
            extras = listOf(EXTRA_VIBRATOR_DURATION to durationMs.coerceIn(0, 10_000)),
        )
    }

    private fun setVoice(context: Context, enabled: Boolean) {
        send(
            context = context,
            action = ACTION_SCAN_VOICE,
            component = controlComponent,
            extras = listOf(EXTRA_SCAN_VOICE to enabled),
        )
    }

    private fun sendTriggerAction(context: Context, action: String) {
        send(
            context = context,
            action = action,
            component = null,
        )
    }

    private fun sendLegacyTriggerAction(context: Context, action: String) {
        send(
            context = context,
            action = action,
            component = controlComponent,
        )
    }

    private fun send(
        context: Context,
        action: String,
        component: ComponentName?,
        extras: List<Pair<String, Any?>> = emptyList(),
        includeStoppedPackages: Boolean = false,
    ) {
        val intent = Intent(action).apply {
            component?.let(::setComponent)
            if (includeStoppedPackages) {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            extras.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is Boolean -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    is Float -> putExtra(key, value)
                    is Double -> putExtra(key, value)
                    is Byte -> putExtra(key, value)
                    is Char -> putExtra(key, value)
                    is Short -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
        }
        Log.i(
            TAG,
            "send action=$action component=${component?.className ?: "<implicit>"} extras=${intent.extras.asDebugString()}",
        )
        context.sendBroadcast(intent)
    }

    private fun logIncoming(intent: Intent) {
        Log.i(
            TAG,
            "recv action=${intent.action} extras=${intent.extras.asDebugString()}",
        )
    }

    private fun Intent.defaultValueMap(): Map<*, *>? {
        val value = extras?.get(EXTRA_DEFAULT_VALUE) ?: return null
        return when (value) {
            is Map<*, *> -> value
            is Bundle -> value.keySet().associateWith(value::get)
            else -> null
        }
    }

    private fun Map<*, *>?.stringValue(key: String): String? =
        this?.get(key)?.toString()

    private fun Bundle?.asDebugString(): String {
        val extras = this ?: return "{}"
        if (extras.isEmpty) {
            return "{}"
        }
        return extras.keySet()
            .sorted()
            .joinToString(prefix = "{", postfix = "}") { key -> "$key=${extras.get(key)}" }
    }
}
