package nz.co.mixport.customsvision.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord
import nz.co.mixport.customsvision.scanner.PdaHardwareKeyDispatcher
import nz.co.mixport.customsvision.scanner.HikPdaPreviewController
import nz.co.mixport.customsvision.scanner.HikPdaPreviewState
import nz.co.mixport.customsvision.scanner.HikPdaScanController
import nz.co.mixport.customsvision.scanner.PdaScanWorkflowMode

private val ScannerOk = Color(0xFF059669)
private val ScannerWarn = Color(0xFFD97706)
private val ScannerErr = Color(0xFFDC2626)
private val ScannerInfo = Color(0xFF2563EB)
private val ScannerPanelTint = Color(0xFFF8F4F1)
private val ScannerBrandStart = Color(0xFFF45D22)
private val ScannerBrandEnd = Color(0xFFD94D1A)

@Composable
fun ScannerScreen(
    modifier: Modifier = Modifier,
    uiState: LiveInspectionUiState,
    onScannerInputChanged: (String) -> Unit,
    onScannerVerify: (String) -> Unit,
    onScannerPdaDetected: (String, String) -> Unit,
    onScannerAutoVerifyChanged: (Boolean) -> Unit,
    onScannerSoundChanged: (Boolean) -> Unit,
    onScannerWorkflowModeChanged: (PdaScanWorkflowMode) -> Unit,
    onScannerHistoryCleared: () -> Unit,
    onScannerOnboardingDismissed: () -> Unit,
) {
    val language = uiState.appLanguage
    val scanner = uiState.scanner
    val context = LocalContext.current
    val pdaScanController = remember(context) { HikPdaScanController(context) }
    val pdaPreviewController = remember(context) { HikPdaPreviewController(context) }
    val focusRequester = remember { FocusRequester() }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85) }
    var lastPlayedScanAt by remember { mutableLongStateOf(0L) }
    var isPdaServiceInstalled by remember { mutableStateOf(pdaScanController.isPdaServiceInstalled()) }
    var isPdaBridgeReady by remember { mutableStateOf(false) }
    var pdaStatusMessage by remember { mutableStateOf<String?>(null) }
    var lastPdaBarcode by remember { mutableStateOf<String?>(null) }
    var lastPdaCodeType by remember { mutableStateOf<String?>(null) }
    var isPdaSessionStarted by remember { mutableStateOf(false) }
    val currentLanguage by rememberUpdatedState(language)
    val currentWorkflowMode by rememberUpdatedState(scanner.workflowMode)
    val currentOnScannerPdaDetected by rememberUpdatedState(onScannerPdaDetected)

    LaunchedEffect(pdaScanController) {
        isPdaServiceInstalled = pdaScanController.isPdaServiceInstalled()
    }

    LaunchedEffect(isPdaServiceInstalled) {
        if (!isPdaServiceInstalled) {
            isPdaSessionStarted = false
            isPdaBridgeReady = false
            pdaStatusMessage = currentLanguage.pick(
                "Hikrobot PDA service is missing on this device. This page now targets only the front FDA scanner hardware.",
                "当前设备缺少 Hikrobot PDA 服务。这个页面现在只支持前置 FDA 扫码硬件。",
            )
            return@LaunchedEffect
        }
        delay(160)
        pdaPreviewController.updateWorkflowMode(currentWorkflowMode)
        pdaPreviewController.bind()
        val didBind = pdaScanController.bind(
            workflowMode = currentWorkflowMode,
            onReady = {
                isPdaSessionStarted = true
                pdaStatusMessage = currentLanguage.pick(
                    "FDA scanner bridge ready. Applying the selected scan mode...",
                    "FDA 扫码桥已就绪，正在应用当前扫码模式。",
                )
            },
            onBarcodeDetected = { barcode, codeType ->
                val normalized = barcode.trim()
                lastPdaBarcode = normalized
                lastPdaCodeType = codeType.takeIf(String::isNotBlank)
                pdaStatusMessage = currentLanguage.pick(
                    "Scanned $normalized",
                    "已扫描 $normalized",
                )
                currentOnScannerPdaDetected(barcode, codeType)
            },
            onError = { message ->
                isPdaSessionStarted = false
                isPdaBridgeReady = false
                pdaStatusMessage = message
            },
        )
        if (!didBind) {
            isPdaSessionStarted = false
        }
    }

    DisposableEffect(pdaScanController, pdaPreviewController) {
        onDispose {
            isPdaSessionStarted = false
            pdaScanController.unbind()
            pdaPreviewController.unbind()
        }
    }

    DisposableEffect(toneGenerator) {
        onDispose {
            toneGenerator.release()
        }
    }

    DisposableEffect(pdaScanController, isPdaServiceInstalled, scanner.workflowMode) {
        if (isPdaServiceInstalled && scanner.workflowMode == PdaScanWorkflowMode.TRIGGER_ONCE) {
            Log.i("ScannerScreen", "Registering hardware FDA key handler for trigger-once mode.")
            PdaHardwareKeyDispatcher.setHandler { keyCode ->
                Log.i(
                    "ScannerScreen",
                    "Hardware FDA key ${PdaHardwareKeyDispatcher.describeKey(keyCode)} pressed.",
                )
                pdaScanController.triggerSingleScan()
                    .onSuccess {
                        isPdaBridgeReady = true
                        pdaStatusMessage = currentLanguage.pick(
                            "Side button ${PdaHardwareKeyDispatcher.describeKey(keyCode)} triggered one FDA scan.",
                            "侧边按键 ${PdaHardwareKeyDispatcher.describeKey(keyCode)} 已触发一次 FDA 扫码。",
                        )
                    }
                    .onFailure { throwable ->
                        isPdaBridgeReady = false
                        pdaStatusMessage = throwable.message ?: currentLanguage.pick(
                            "Unable to trigger a side-button FDA scan.",
                            "无法通过侧边按键触发 FDA 扫码。",
                        )
                    }
                true
            }
        } else {
            Log.i("ScannerScreen", "Clearing hardware FDA key handler.")
            PdaHardwareKeyDispatcher.setHandler(null)
        }
        onDispose {
            Log.i("ScannerScreen", "Disposing hardware FDA key handler.")
            PdaHardwareKeyDispatcher.setHandler(null)
        }
    }

    LaunchedEffect(Unit) {
        delay(220)
        runCatching {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(scanner.workflowMode, isPdaServiceInstalled, isPdaSessionStarted) {
        if (!isPdaServiceInstalled || !isPdaSessionStarted) {
            return@LaunchedEffect
        }
        delay(120)
        pdaPreviewController.updateWorkflowMode(scanner.workflowMode)
        pdaScanController.applyWorkflowMode(scanner.workflowMode)
            .onSuccess {
                isPdaBridgeReady = true
                pdaStatusMessage = scannerLiveMessage(language, scanner.workflowMode)
            }
            .onFailure { throwable ->
                isPdaBridgeReady = false
                pdaStatusMessage = throwable.message ?: language.pick(
                    "Unable to switch FDA scanner mode.",
                    "无法切换 FDA 扫码模式。",
                )
            }
    }

    LaunchedEffect(scanner.barcodeInput, scanner.isAutoVerifyEnabled, scanner.isProcessing) {
        val candidate = scanner.barcodeInput.trim().uppercase()
        if (!scanner.isAutoVerifyEnabled || scanner.isProcessing || candidate.length < 6) {
            return@LaunchedEffect
        }
        delay(180)
        if (scanner.barcodeInput.trim().uppercase() == candidate &&
            scanner.lastProcessedBarcode != candidate
        ) {
            onScannerVerify(candidate)
        }
    }

    LaunchedEffect(scanner.history.firstOrNull()?.scannedAt, scanner.isSoundEnabled) {
        val record = scanner.history.firstOrNull() ?: return@LaunchedEffect
        if (!scanner.isSoundEnabled || record.scannedAt == lastPlayedScanAt) {
            return@LaunchedEffect
        }
        when (record.matchStatus) {
            ScannerMatchStatus.MATCHED -> toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 140)
            ScannerMatchStatus.MISMATCH -> toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 180)
            ScannerMatchStatus.ERROR -> toneGenerator.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 180)
            ScannerMatchStatus.WAITING -> Unit
        }
        lastPlayedScanAt = record.scannedAt
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!scanner.onboardingDismissed) {
            item {
                ScannerTipCard(
                    language = language,
                    onDismiss = onScannerOnboardingDismissed,
                )
            }
        }
        item {
            ScannerPreviewCard(
                language = language,
                previewController = pdaPreviewController,
                isPdaServiceInstalled = isPdaServiceInstalled,
            )
        }
        item {
            ScannerAutoDeck(
                language = language,
                scanner = scanner,
                isPdaServiceInstalled = isPdaServiceInstalled,
                isPdaBridgeReady = isPdaBridgeReady,
                statusMessage = pdaStatusMessage,
                workflowMode = scanner.workflowMode,
                lastPdaBarcode = lastPdaBarcode,
                lastPdaCodeType = lastPdaCodeType,
                onWorkflowModeChanged = onScannerWorkflowModeChanged,
                onResumeScan = {
                    pdaScanController.resumeAutoScan()
                        .onSuccess {
                            isPdaBridgeReady = true
                            onScannerWorkflowModeChanged(PdaScanWorkflowMode.AUTO_CONTINUOUS)
                            pdaStatusMessage = scannerLiveMessage(
                                language,
                                PdaScanWorkflowMode.AUTO_CONTINUOUS,
                            )
                        }
                        .onFailure { throwable ->
                            isPdaBridgeReady = false
                            pdaStatusMessage = throwable.message ?: language.pick(
                                "Unable to start FDA scanning.",
                                "无法启动 FDA 扫码。",
                            )
                        }
                },
                onPauseScan = {
                    pdaScanController.stopScan()
                        .onSuccess {
                            isPdaBridgeReady = false
                            pdaStatusMessage = scannerPausedMessage(language)
                        }
                        .onFailure { throwable ->
                            pdaStatusMessage = throwable.message ?: language.pick(
                                "Unable to pause FDA scanning.",
                                "无法暂停 FDA 扫码。",
                            )
                        }
                },
                onRefreshConfig = {
                    pdaScanController.reconfigure(scanner.workflowMode)
                        .onSuccess {
                            isPdaBridgeReady = true
                            pdaStatusMessage = scannerRefreshedMessage(language, scanner.workflowMode)
                            if (scanner.workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
                                pdaScanController.triggerScan()
                            }
                        }
                        .onFailure { throwable ->
                            isPdaBridgeReady = false
                            pdaStatusMessage = throwable.message ?: language.pick(
                                "Unable to refresh FDA scanner settings.",
                                "无法刷新 FDA 扫码配置。",
                            )
                        }
                },
                onTriggerSingleScan = {
                    pdaScanController.triggerSingleScan()
                        .onSuccess {
                            isPdaBridgeReady = true
                            pdaStatusMessage = language.pick(
                                "Single FDA scan trigger sent. Side buttons and the on-screen trigger now scan once.",
                                "已发送单次 FDA 扫码触发。现在可用机身左右扫码键或页面按钮单次扫码。",
                            )
                        }
                        .onFailure { throwable ->
                            isPdaBridgeReady = false
                            pdaStatusMessage = throwable.message ?: language.pick(
                                "Unable to send a single FDA scan trigger.",
                                "无法发送单次 FDA 扫码触发。",
                            )
                        }
                },
            )
        }
        item {
            ScannerInputCard(
                language = language,
                scanner = scanner,
                focusRequester = focusRequester,
                onInputChanged = onScannerInputChanged,
                onVerify = onScannerVerify,
                onAutoVerifyChanged = onScannerAutoVerifyChanged,
                onSoundChanged = onScannerSoundChanged,
            )
        }
        item {
            ScannerHistoryCard(
                language = language,
                scanner = scanner,
                onClearHistory = onScannerHistoryCleared,
            )
        }
    }
}

@Composable
private fun ScannerPreviewCard(
    language: AppLanguage,
    previewController: HikPdaPreviewController,
    isPdaServiceInstalled: Boolean,
) {
    val previewState by previewController.state.collectAsStateWithLifecycle()

    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = language.pick("Front FDA Preview", "前置 FDA 预览"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = language.pick(
                    "This page now uses the embedded Hikrobot FDA scanner head. It is not using the rear phone camera.",
                    "这个页面现在直接使用海康 FDA 前置扫码头，不再调用后置手机摄像头。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF101828)),
            ) {
                val previewBitmap = previewState.frameBitmap
                if (previewBitmap != null) {
                    val previewImageBitmap = remember(previewBitmap) {
                        previewBitmap.asImageBitmap()
                    }
                    Image(
                        bitmap = previewImageBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xB2059669))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = language.pick("FDA preview live", "FDA 预览已联通"),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = when {
                                !isPdaServiceInstalled -> language.pick(
                                    "PDA service not found on this device.",
                                    "当前设备未发现 PDA 服务。",
                                )
                                previewState.isServiceBound -> language.pick(
                                    "Connected to PDA service. Waiting for the front FDA image stream...",
                                    "已连接 PDA 服务，正在等待前置 FDA 图像流...",
                                )
                                else -> language.pick(
                                    "Connecting to the embedded FDA scanner preview...",
                                    "正在连接内置 FDA 扫码头预览...",
                                )
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        previewState.errorMessage?.let { message ->
                            Text(
                                text = message,
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScannerStatChip(
                    label = language.pick("PDA", "PDA"),
                    value = if (previewState.isServiceBound) {
                        language.pick("Bound", "已连接")
                    } else {
                        language.pick("Waiting", "等待中")
                    },
                    color = if (previewState.isServiceBound) ScannerOk else ScannerWarn,
                )
                ScannerStatChip(
                    label = language.pick("Preview", "预览"),
                    value = if (previewState.isStreaming) {
                        language.pick("Live", "实时")
                    } else {
                        language.pick("Idle", "未联通")
                    },
                    color = if (previewState.isStreaming) ScannerInfo else ScannerWarn,
                )
                if (previewState.frameWidth > 0 && previewState.frameHeight > 0) {
                    ScannerStatChip(
                        label = language.pick("Source", "源尺寸"),
                        value = "${previewState.frameWidth}x${previewState.frameHeight}",
                        color = ScannerInfo,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerTipCard(
    language: AppLanguage,
    onDismiss: () -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = language.pick("Quick Tip", "快速提示"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = language.pick(
                    "Open this page and the front FDA scanner starts automatically with the light on. The rear camera option has been removed.",
                    "进入这个页面后会自动启动前置 FDA 扫码头并开灯，后置摄像头选项已经移除。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onDismiss) {
                Text(language.pick("Hide tip", "隐藏提示"))
            }
        }
    }
}

@Composable
private fun ScannerAutoDeck(
    language: AppLanguage,
    scanner: ScannerUiState,
    isPdaServiceInstalled: Boolean,
    isPdaBridgeReady: Boolean,
    statusMessage: String?,
    workflowMode: PdaScanWorkflowMode,
    lastPdaBarcode: String?,
    lastPdaCodeType: String?,
    onWorkflowModeChanged: (PdaScanWorkflowMode) -> Unit,
    onResumeScan: () -> Unit,
    onPauseScan: () -> Unit,
    onRefreshConfig: () -> Unit,
    onTriggerSingleScan: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 4.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(ScannerBrandStart, ScannerBrandEnd),
                    ),
                )
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = language.pick("FDA Scanner Control", "FDA 扫码控制"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (isPdaServiceInstalled) {
                        language.pick(
                            "Open this page to wake the embedded PDA scanner. Choose continuous scan or one-shot trigger mode for the front FDA head.",
                            "打开此页就会唤醒内置 PDA 扫码头，可在前置 FDA 扫码头的连续扫描和单次触发之间切换。",
                        )
                    } else {
                        language.pick(
                            "The Hikrobot PDA service is not visible to this app yet. Retry after confirming the service exists on the device.",
                            "应用暂时还看不到 Hikrobot PDA 服务。请确认设备上的服务存在后再重试。",
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.94f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val autoSelected = workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS
                    Button(
                        onClick = { onWorkflowModeChanged(PdaScanWorkflowMode.AUTO_CONTINUOUS) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (autoSelected) Color.White else Color.White.copy(alpha = 0.14f),
                            contentColor = if (autoSelected) MaterialTheme.colorScheme.primary else Color.White,
                        ),
                    ) {
                        Text(language.pick("Auto continuous", "自动连续扫"))
                    }
                    OutlinedButton(
                        onClick = { onWorkflowModeChanged(PdaScanWorkflowMode.TRIGGER_ONCE) },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(
                            1.dp,
                            if (autoSelected) Color.White.copy(alpha = 0.72f) else Color.White,
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(language.pick("Trigger once", "按键单次扫"))
                    }
                }

                if (workflowMode == PdaScanWorkflowMode.TRIGGER_ONCE) {
                    Text(
                        text = language.pick(
                            "Manual trigger mode supports the left and right FDA hardware buttons, including the key below the power button on the right side.",
                            "单次触发模式支持机身左侧和右侧的 FDA 硬件扫码键，包括右侧电源键下方的那个按键。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (isPdaServiceInstalled) {
                                    language.pick("Service online", "服务在线")
                                } else {
                                    language.pick("Service missing", "服务缺失")
                                },
                            )
                        },
                    )
                    if (isPdaServiceInstalled) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (isPdaBridgeReady) {
                                        if (workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
                                            language.pick("Continuous scan active", "连续扫描已开启")
                                        } else {
                                            language.pick("Trigger mode armed", "单次触发已就绪")
                                        }
                                    } else {
                                        if (workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
                                            language.pick("Continuous scan paused", "连续扫描已暂停")
                                        } else {
                                            language.pick("Trigger mode idle", "单次触发待命中")
                                        }
                                    },
                                )
                            },
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = language.pick("Last scanned code", "最近扫码"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = lastPdaBarcode ?: language.pick("Waiting for barcode...", "等待条码..."),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        lastPdaCodeType?.let { codeType ->
                            AssistChip(
                                onClick = {},
                                label = { Text(language.pick("Type: $codeType", "类型：$codeType")) },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(scannerStatusColor(scanner)),
                            )
                            Text(
                                text = statusMessage ?: scanner.statusMessage ?: language.pick(
                                    "Scanner standing by.",
                                    "扫描器待命中。",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (isPdaServiceInstalled && workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onResumeScan,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                            Text(language.pick("Resume", "恢复"))
                        }
                        FilledTonalButton(
                            onClick = onPauseScan,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.StopCircle, contentDescription = null)
                            Text(language.pick("Pause", "暂停"))
                        }
                    }
                }
                if (isPdaServiceInstalled && workflowMode == PdaScanWorkflowMode.TRIGGER_ONCE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onTriggerSingleScan,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                            Text(language.pick("Trigger once", "触发一次"))
                        }
                        FilledTonalButton(
                            onClick = onPauseScan,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.StopCircle, contentDescription = null)
                            Text(language.pick("Stop light", "停止并关灯"))
                        }
                    }
                }
                OutlinedButton(
                    onClick = onRefreshConfig,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                    ),
                ) {
                    Text(language.pick("Refresh scanner config", "刷新扫码配置"))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScannerStatChip(
                        label = language.pick("Matched", "已匹配"),
                        value = scanner.matchedCount.toString(),
                        color = ScannerOk,
                    )
                    ScannerStatChip(
                        label = language.pick("Mismatch", "未找到"),
                        value = scanner.mismatchCount.toString(),
                        color = ScannerWarn,
                    )
                    ScannerStatChip(
                        label = language.pick("Error", "错误"),
                        value = scanner.errorCount.toString(),
                        color = ScannerErr,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerInputCard(
    language: AppLanguage,
    scanner: ScannerUiState,
    focusRequester: FocusRequester,
    onInputChanged: (String) -> Unit,
    onVerify: (String) -> Unit,
    onAutoVerifyChanged: (Boolean) -> Unit,
    onSoundChanged: (Boolean) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = language.pick("Backup Verification", "备用校验"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = language.pick(
                    "Keep a focused input box for manual entry or keyboard-wedge scanners. Auto-verify stays on by default.",
                    "保留一个聚焦输入框，兼容手动输入和键盘口扫码枪，默认自动校验。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ScannerPanelTint)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(scannerStatusColor(scanner)),
                )
                Column {
                    Text(
                        text = scannerResultTitle(language, scanner),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = scanner.statusMessage ?: language.pick(
                            "Waiting for barcode input...",
                            "等待条码输入...",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = scanner.barcodeInput,
                onValueChange = {
                    onInputChanged(it.replace("\n", "").replace("\r", ""))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text(language.pick("Barcode", "条码")) },
                placeholder = { Text(language.pick("Scan or type barcode here", "在这里扫码或输入条码")) },
                singleLine = true,
                enabled = !scanner.isProcessing,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onVerify(scanner.barcodeInput) },
                    enabled = !scanner.isProcessing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (scanner.isProcessing) {
                            language.pick("Verifying...", "校验中...")
                        } else {
                            language.pick("Verify now", "立即校验")
                        },
                    )
                }
                FilledTonalButton(
                    onClick = { focusRequester.requestFocus() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(language.pick("Focus input", "聚焦输入框"))
                }
            }

            ScannerToggleRow(
                title = language.pick("Auto-verify", "自动校验"),
                description = if (scanner.isAutoVerifyEnabled) {
                    language.pick(
                        "Verify after 180ms of stable input.",
                        "输入稳定 180ms 后自动校验。",
                    )
                } else {
                    language.pick("Manual verification only.", "仅手动校验。")
                },
                checked = scanner.isAutoVerifyEnabled,
                onCheckedChange = onAutoVerifyChanged,
            )

            ScannerToggleRow(
                title = language.pick("Sound prompts", "声音提示"),
                description = language.pick(
                    "Use different tones for matched, mismatch, and error results.",
                    "已匹配、未找到和错误结果使用不同提示音。",
                ),
                checked = scanner.isSoundEnabled,
                onCheckedChange = onSoundChanged,
            )
        }
    }
}

@Composable
private fun ScannerToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ScannerPanelTint)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ScannerHistoryCard(
    language: AppLanguage,
    scanner: ScannerUiState,
    onClearHistory: () -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = language.pick("Scan History", "扫码记录"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                OutlinedButton(
                    onClick = onClearHistory,
                    enabled = scanner.history.isNotEmpty(),
                ) {
                    Text(language.pick("Clear", "清空"))
                }
            }

            if (scanner.history.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = ScannerPanelTint,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = language.pick("No scan records yet.", "还没有扫码记录。"),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = language.pick(
                                "Once the PDA reads a code, the verification result will appear here.",
                                "PDA 读到条码后，校验结果会显示在这里。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                scanner.history.take(8).forEach { record ->
                    ScannerHistoryItem(
                        language = language,
                        record = record,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerHistoryItem(
    language: AppLanguage,
    record: ScannerRecord,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = ScannerPanelTint,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.scannedBarcode,
                    fontWeight = FontWeight.Bold,
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(localizedScannerMatchStatus(language, record.matchStatus))
                    },
                )
            }
            Text(
                text = language.pick("Record", "记录") + ": " + record.databaseRecord,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = language.pick("Status", "状态") + ": " + localizedStatus(language, record.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = language.pick("Source", "来源") + ": " + localizedScannerSource(language, record.source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTimestamp(record.scannedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScannerStatChip(
    label: String,
    value: String,
    color: Color,
) {
    Surface(
        modifier = Modifier
            .height(52.dp)
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = color.copy(alpha = 0.08f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Column {
                Text(value, fontWeight = FontWeight.Bold, color = color)
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun scannerStatusColor(scanner: ScannerUiState): Color {
    return when {
        scanner.isProcessing -> ScannerInfo
        scanner.lastResult == ScannerMatchStatus.MATCHED -> ScannerOk
        scanner.lastResult == ScannerMatchStatus.MISMATCH -> ScannerWarn
        scanner.lastResult == ScannerMatchStatus.ERROR -> ScannerErr
        else -> ScannerOk
    }
}

private fun scannerResultTitle(
    language: AppLanguage,
    scanner: ScannerUiState,
): String {
    return when {
        scanner.isProcessing -> language.pick("Verifying", "正在校验")
        scanner.lastResult == ScannerMatchStatus.MATCHED -> language.pick("Matched", "已匹配")
        scanner.lastResult == ScannerMatchStatus.MISMATCH -> language.pick("Not found", "未找到")
        scanner.lastResult == ScannerMatchStatus.ERROR -> language.pick("Verification error", "校验错误")
        else -> language.pick("Ready", "准备就绪")
    }
}

private fun scannerLiveMessage(language: AppLanguage): String {
    return language.pick(
        "FDA scanner is live. Front light is on and continuous scanning has started.",
        "FDA 扫码器已启动，前灯已开启，并已进入连续扫码。",
    )
}

private fun scannerLiveMessage(
    language: AppLanguage,
    workflowMode: PdaScanWorkflowMode,
): String {
    return if (workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
        scannerLiveMessage(language)
    } else {
        language.pick(
            "FDA scanner is ready for one-shot trigger scans. Use the side keys or tap Trigger once.",
            "FDA 扫码器已进入单次触发模式。可使用机身扫码键或点击触发一次。",
        )
    }
}

private fun scannerPausedMessage(language: AppLanguage): String {
    return language.pick(
        "FDA scanner paused.",
        "FDA 扫码器已暂停。",
    )
}

private fun scannerRefreshedMessage(
    language: AppLanguage,
    workflowMode: PdaScanWorkflowMode,
): String {
    return if (workflowMode == PdaScanWorkflowMode.AUTO_CONTINUOUS) {
        scannerRefreshedMessage(language)
    } else {
        language.pick(
            "FDA scanner settings refreshed and trigger mode is armed.",
            "FDA 扫码配置已刷新，单次触发模式已就绪。",
        )
    }
}

private fun scannerRefreshedMessage(language: AppLanguage): String {
    return language.pick(
        "FDA scanner settings refreshed and auto scan restarted.",
        "FDA 扫码配置已刷新，并重新开始自动扫码。",
    )
}

