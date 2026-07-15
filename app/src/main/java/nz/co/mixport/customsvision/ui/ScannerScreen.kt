package nz.co.mixport.customsvision.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.normalizeScannerBarcode
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord
import nz.co.mixport.customsvision.scanner.HikPdaScanController
import nz.co.mixport.customsvision.scanner.PdaHardwareKeyDispatcher
import nz.co.mixport.customsvision.scanner.PdaScanWorkflowMode

private val ScannerOk = Color(0xFF0F9D58)
private val ScannerWarn = Color(0xFFD97706)
private val ScannerErr = Color(0xFFD92D20)
private val ScannerIdle = Color(0xFF475467)
private val ScannerBrandStart = Color(0xFFE53935)
private val ScannerBrandEnd = Color(0xFFB42318)
private val ScannerHoldStart = Color(0xFFF4B400)
private val ScannerHoldEnd = Color(0xFFD97706)
private val ScannerPanelTint = Color(0xFFFFF7F5)
private const val MANUAL_TRIGGER_REPEAT_MS = 420L
private const val SINGLE_TRIGGER_LIGHT_OFF_DELAY_MS = 650L

@Composable
fun ScannerScreen(
    modifier: Modifier = Modifier,
    uiState: LiveInspectionUiState,
    onScannerInputChanged: (String) -> Unit,
    onScannerVerify: (String) -> Unit,
    onScannerAwaitingNextScan: () -> Unit,
    onScannerPdaDetected: (String, String) -> Unit,
    onScannerWorkflowModeChanged: (PdaScanWorkflowMode) -> Unit,
    onScannerHistoryCleared: () -> Unit,
    onScannerOnboardingDismissed: () -> Unit,
) {
    val language = uiState.appLanguage
    val scanner = uiState.scanner
    val latestRecord = scanner.history.firstOrNull()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pdaScanController = remember(context) { HikPdaScanController(context) }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }
    val coroutineScope = rememberCoroutineScope()
    val currentLanguage by rememberUpdatedState(language)
    val currentOnScannerPdaDetected by rememberUpdatedState(onScannerPdaDetected)
    var isPdaServiceInstalled by remember { mutableStateOf(pdaScanController.isPdaServiceInstalled()) }
    var isPdaBridgeReady by remember { mutableStateOf(false) }
    var pdaStatusMessage by remember { mutableStateOf<String?>(null) }
    var lastPdaBarcode by remember { mutableStateOf<String?>(null) }
    var lastPdaCodeType by remember { mutableStateOf<String?>(null) }
    var lastPlayedFeedbackNonce by remember { mutableLongStateOf(0L) }
    var manualTriggerJob by remember { mutableStateOf<Job?>(null) }
    var activeHardwareKey by remember { mutableStateOf<Int?>(null) }

    fun resetScannerResultForNextAttempt() {
        if (scanner.isProcessing) {
            return
        }
        lastPdaBarcode = null
        lastPdaCodeType = null
        onScannerAwaitingNextScan()
    }

    fun stopManualTrigger(updateStatus: Boolean) {
        manualTriggerJob?.cancel()
        manualTriggerJob = null
        activeHardwareKey = null
        pdaScanController.stopScan()
            .onFailure { throwable ->
                pdaStatusMessage = throwable.message ?: currentLanguage.pick(
                    "Unable to stop scanning.",
                    "无法停止 FDA 触发。",
                )
            }
            .onSuccess {
                if (updateStatus) {
                    pdaStatusMessage = currentLanguage.pick(
                        "Scan stopped. The scanner is waiting for the next key press.",
                        "手动触发已停止，等待下一次按键扫描。",
                    )
                }
            }
    }

    fun triggerManualScan(showHint: Boolean) {
        resetScannerResultForNextAttempt()
        pdaScanController.triggerSingleScan()
            .onSuccess {
                isPdaBridgeReady = true
                if (showHint) {
                    pdaStatusMessage = currentLanguage.pick(
                        "Scan started. Tap once for one read, or hold the side key to keep scanning.",
                        "已发送手动扫描触发。按一下扫一次，按住侧键可持续扫描。",
                    )
                }
            }
            .onFailure { throwable ->
                isPdaBridgeReady = false
                pdaStatusMessage = throwable.message ?: currentLanguage.pick(
                    "Unable to start scanning.",
                    "无法触发 FDA 扫码。",
                )
            }
    }

    fun startManualTriggerLoop(keyCode: Int) {
        if (activeHardwareKey == keyCode && manualTriggerJob?.isActive == true) {
            return
        }
        manualTriggerJob?.cancel()
        activeHardwareKey = keyCode
        manualTriggerJob = coroutineScope.launch {
            triggerManualScan(showHint = true)
            while (isActive) {
                delay(MANUAL_TRIGGER_REPEAT_MS)
                if (activeHardwareKey != keyCode) {
                    break
                }
                triggerManualScan(showHint = false)
            }
        }
    }

    LaunchedEffect(Unit) {
        onScannerWorkflowModeChanged(PdaScanWorkflowMode.TRIGGER_ONCE)
        if (!scanner.onboardingDismissed) {
            onScannerOnboardingDismissed()
        }
    }

    LaunchedEffect(pdaScanController) {
        isPdaServiceInstalled = pdaScanController.isPdaServiceInstalled()
    }

    LaunchedEffect(isPdaServiceInstalled) {
        if (!isPdaServiceInstalled) {
            isPdaBridgeReady = false
            pdaStatusMessage = currentLanguage.pick(
                "Scanner service is unavailable on this device.",
                "当前设备的扫码服务不可用。",
            )
            return@LaunchedEffect
        }

        delay(120)
        val didBind = pdaScanController.bind(
            workflowMode = PdaScanWorkflowMode.TRIGGER_ONCE,
            onReady = {
                pdaStatusMessage = currentLanguage.pick(
                    "Scanner connected. Preparing side-key scanning...",
                    "扫码器已连接，正在准备侧键扫描...",
                )
            },
            onBarcodeDetected = { barcode, codeType ->
                val normalized = barcode.trim()
                lastPdaBarcode = normalized
                lastPdaCodeType = codeType.takeIf(String::isNotBlank)
                pdaStatusMessage = currentLanguage.pick(
                    "Read $normalized. Comparing with the database...",
                    "已读到 $normalized，正在比对数据库...",
                )
                currentOnScannerPdaDetected(barcode, codeType)
            },
            onError = { message ->
                isPdaBridgeReady = false
                pdaStatusMessage = message
            },
        )

        if (!didBind) {
            isPdaBridgeReady = false
            return@LaunchedEffect
        }

        pdaScanController.applyWorkflowMode(PdaScanWorkflowMode.TRIGGER_ONCE)
            .onSuccess {
                isPdaBridgeReady = true
                pdaStatusMessage = currentLanguage.pick(
                    "Scanner is ready. Use the side scan key.",
                    "扫码器已就绪，请使用机身侧边扫码键。",
                )
            }
            .onFailure { throwable ->
                isPdaBridgeReady = false
                pdaStatusMessage = throwable.message ?: currentLanguage.pick(
                    "Unable to prepare the scanner.",
                    "无法准备扫码器。",
                )
            }
    }

    LaunchedEffect(language) {
        pdaStatusMessage = when {
            !isPdaServiceInstalled -> language.pick(
                "Scanner service is unavailable on this device.",
                "当前设备的扫码服务不可用。",
            )

            activeHardwareKey != null -> language.pick(
                "Hold the side scan key to keep scanning. Release it to stop.",
                "按住侧边扫码键可持续扫描，松开即停止。",
            )

            isPdaBridgeReady -> language.pick(
                "Scanner is ready. Use the side scan key.",
                "扫码器已就绪，请使用机身侧边扫码键。",
            )

            else -> language.pick(
                "Connecting to the scanner service...",
                "正在连接扫码服务...",
            )
        }
    }

    DisposableEffect(pdaScanController, isPdaServiceInstalled) {
        if (isPdaServiceInstalled) {
            PdaHardwareKeyDispatcher.setHandlers(
                onKeyDown = { keyCode ->
                    startManualTriggerLoop(keyCode)
                    true
                },
                onKeyUp = { keyCode ->
                    if (activeHardwareKey == keyCode) {
                        stopManualTrigger(updateStatus = false)
                    }
                    true
                },
            )
        } else {
            PdaHardwareKeyDispatcher.setHandlers(onKeyDown = null, onKeyUp = null)
        }

        onDispose {
            PdaHardwareKeyDispatcher.setHandlers(onKeyDown = null, onKeyUp = null)
            stopManualTrigger(updateStatus = false)
            pdaScanController.unbind()
        }
    }

    DisposableEffect(toneGenerator) {
        onDispose {
            toneGenerator.release()
        }
    }

    LaunchedEffect(scanner.feedbackNonce, scanner.isSoundEnabled) {
        if (!scanner.isSoundEnabled ||
            scanner.feedbackNonce == 0L ||
            scanner.feedbackNonce == lastPlayedFeedbackNonce
        ) {
            return@LaunchedEffect
        }

        when (scanner.lastResult) {
            ScannerMatchStatus.MATCHED -> toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 140)
            ScannerMatchStatus.MISMATCH -> toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 210)
            ScannerMatchStatus.ERROR -> toneGenerator.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 220)
            ScannerMatchStatus.WAITING -> Unit
        }
        lastPlayedFeedbackNonce = scanner.feedbackNonce
    }

    LaunchedEffect(scanner.barcodeInput, scanner.isAutoVerifyEnabled, scanner.isProcessing) {
        val candidate = normalizeScannerBarcode(scanner.barcodeInput)
        if (!scanner.isAutoVerifyEnabled || scanner.isProcessing || candidate.length < 4) {
            return@LaunchedEffect
        }
        delay(180)
        if (normalizeScannerBarcode(scanner.barcodeInput) == candidate &&
            scanner.lastProcessedBarcode != candidate
        ) {
            onScannerVerify(candidate)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScannerResultCard(
                language = language,
                scanner = scanner,
                latestRecord = latestRecord,
                lastPdaBarcode = lastPdaBarcode,
                lastPdaCodeType = lastPdaCodeType,
                statusMessage = pdaStatusMessage,
                isPdaServiceInstalled = isPdaServiceInstalled,
                isPdaBridgeReady = isPdaBridgeReady,
            )
        }
        item {
            ScannerInputCard(
                language = language,
                scanner = scanner,
                onInputChanged = onScannerInputChanged,
                onVerify = onScannerVerify,
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
private fun ScannerResultCard(
    language: AppLanguage,
    scanner: ScannerUiState,
    latestRecord: ScannerRecord?,
    lastPdaBarcode: String?,
    lastPdaCodeType: String?,
    statusMessage: String?,
    isPdaServiceInstalled: Boolean,
    isPdaBridgeReady: Boolean,
) {
    val isWaitingState = !scanner.isProcessing && scanner.lastResult == ScannerMatchStatus.WAITING
    val visibleRecord = latestRecord.takeUnless { isWaitingState }
    val liveLookup = scanner.lastLookupResult.takeUnless { isWaitingState }
    val nzcsStatus = liveLookup?.customersStatus ?: visibleRecord?.customersStatus
    val mpiStatus = liveLookup?.mpiStatus ?: visibleRecord?.mpiStatus
    val clearanceOverallStatus = if (scanner.lastResult == ScannerMatchStatus.MATCHED) {
        overallScannerClearanceStatus(nzcsStatus, mpiStatus)
    } else {
        null
    }
    val normalizedNzcsStatus = clearanceOverallStatus?.let { canonicalScannerClearanceStatus(nzcsStatus) }
    val normalizedMpiStatus = clearanceOverallStatus?.let { canonicalScannerClearanceStatus(mpiStatus) }
    val resultColor = scannerStatusColor(
        result = scanner.lastResult,
        isProcessing = scanner.isProcessing,
        clearanceOverallStatus = clearanceOverallStatus,
    )
    val liveCode = when {
        scanner.isProcessing -> lastPdaBarcode ?: visibleRecord?.scannedBarcode
        isWaitingState -> lastPdaBarcode
        else -> visibleRecord?.scannedBarcode ?: lastPdaBarcode
    }
    val liveRecord = visibleRecord?.let { localizedScannerDatabaseRecord(language, it.databaseRecord) }
    val liveStatus = visibleRecord?.let { localizedScannerStatusText(language, it.status) }
    val liveSource = visibleRecord?.let { localizedScannerSource(language, it.source) }
    val liveMatchRoute = liveLookup?.matchedBy?.let { localizedScannerMatchRoute(language, it) }
    val liveMatchedValue = when {
        !liveLookup?.matchedBarcodeCode.isNullOrBlank() -> liveLookup?.matchedBarcodeCode
        !liveLookup?.matchedChildHbl.isNullOrBlank() -> liveLookup?.matchedChildHbl
        !liveLookup?.parentHblNo.isNullOrBlank() -> liveLookup?.parentHblNo
        else -> null
    }
    val liveContainerLine = listOfNotNull(
        liveLookup?.containerNo?.takeIf(String::isNotBlank),
        liveLookup?.vesselName?.takeIf(String::isNotBlank),
    ).joinToString(" | ").ifBlank { null }
    val livePartyLine = listOfNotNull(
        liveLookup?.company?.takeIf(String::isNotBlank),
        liveLookup?.customerName?.takeIf(String::isNotBlank),
    ).joinToString(" | ").ifBlank { null }
    val liveStatusPair = buildList {
        normalizedNzcsStatus?.let {
            add("NZCS ${localizedStatus(language, it)}")
        }
        normalizedMpiStatus?.let {
            add("MPI ${localizedStatus(language, it)}")
        }
    }.joinToString(" | ").ifBlank { null }
    val liveQuantityLine = buildList {
        liveLookup?.pkgs?.let {
            add(language.pick("Packages $it", "\u4ef6\u6570 $it"))
        }
        liveLookup?.outTurnQty?.let {
            add(language.pick("Out-turn $it", "\u51fa\u5e93\u6570 $it"))
        }
        liveLookup?.location?.takeIf(String::isNotBlank)?.let {
            add(language.pick("Location $it", "\u5e93\u4f4d $it"))
        }
    }.joinToString(" | ").ifBlank { null }
    val liveProgressLine = liveLookup?.let { lookup ->
        val expected = lookup.scannerExpectedScanCount ?: 0
        val completed = lookup.scannerCompletedScanCount ?: 0
        val remaining = lookup.scannerRemainingScanCount ?: 0
        val progressLabel = when (lookup.scannerTargetMode?.trim()?.lowercase()) {
            "child_hbls" -> language.pick("Scan HBLs", "Scan HBLs")
            "pkgs" -> language.pick("Packages", "\u4ef6\u6570")
            else -> language.pick("This cargo", "\u672c\u7968")
        }
        buildList {
            if (expected > 0) {
                add("$progressLabel $completed/$expected")
                add(language.pick("Remaining $remaining", "\u5269\u4f59 $remaining"))
            } else if (lookup.serverScanCount > 0) {
                add(language.pick("Scans ${lookup.serverScanCount}", "\u7d2f\u8ba1\u626b\u63cf ${lookup.serverScanCount} \u6b21"))
            }
            lookup.scannerRepeatMatchCount?.takeIf { it > 0 }?.let { repeats ->
                add(language.pick("Repeat matches $repeats", "\u91cd\u590d\u547d\u4e2d $repeats \u6b21"))
            }
        }.joinToString(" | ").ifBlank { null }
    }
    val liveCountLine = liveLookup?.let { lookup ->
        buildList {
            if (lookup.serverScanCount > 0) {
                add(language.pick("This record scans ${lookup.serverScanCount}", "\u672c\u7968\u7d2f\u8ba1\u626b\u63cf ${lookup.serverScanCount} \u6b21"))
            }
            lookup.containerScanCount?.let { containerScans ->
                val rowCount = lookup.containerRowCount
                val matchedRowCount = lookup.containerMatchedRowCount
                add(
                    if (rowCount != null && rowCount > 0 && matchedRowCount != null) {
                        language.pick(
                            "Container scans $containerScans | Rows $matchedRowCount/$rowCount",
                            "\u672c\u67dc\u626b\u63cf $containerScans \u6b21 | \u5df2\u626b\u884c $matchedRowCount/$rowCount",
                        )
                    } else {
                        language.pick(
                            "Container scans $containerScans",
                            "\u672c\u67dc\u626b\u63cf $containerScans \u6b21",
                        )
                    }
                )
            }
        }.joinToString(" | ").ifBlank { null }
    }
    val resultMessage = when {
        scanner.isProcessing || !isWaitingState -> scanner.statusMessage ?: statusMessage
        else -> statusMessage ?: scanner.statusMessage
    } ?: language.pick(
        "Use the side scan key to start.",
        "\u4f7f\u7528\u673a\u8eab\u626b\u7801\u952e\u5f00\u59cb\u626b\u63cf\u3002",
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp,
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = scannerResultBackground(
                                result = scanner.lastResult,
                                isProcessing = scanner.isProcessing,
                                clearanceOverallStatus = clearanceOverallStatus,
                            ),
                        ),
                    )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = language.pick("Scanner Result", "\u626b\u7801\u7ed3\u679c"),
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = scannerResultTitle(
                            language = language,
                            result = scanner.lastResult,
                            isProcessing = scanner.isProcessing,
                            clearanceOverallStatus = clearanceOverallStatus,
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = when {
                                !isPdaServiceInstalled -> language.pick("Scanner unavailable", "\u626b\u7801\u4e0d\u53ef\u7528")
                                isPdaBridgeReady -> language.pick("Ready", "\u5c31\u7eea")
                                else -> language.pick("Connecting", "\u8fde\u63a5\u4e2d")
                            },
                        )
                    },
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = language.pick("Latest serial number", "\u6700\u65b0\u5e8f\u5217\u53f7"),
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = liveCode ?: language.pick("Awaiting scan", "\u7b49\u5f85\u626b\u63cf"),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScannerStatusPill(
                            label = language.pick("Status", "\u72b6\u6001"),
                            value = scannerResultTitle(
                                language = language,
                                result = scanner.lastResult,
                                isProcessing = scanner.isProcessing,
                                clearanceOverallStatus = clearanceOverallStatus,
                            ),
                            color = resultColor,
                        )
                        ScannerStatusPill(
                            label = language.pick("Type", "\u7c7b\u578b"),
                            value = lastPdaCodeType ?: language.pick("PDA", "PDA"),
                            color = Color.White.copy(alpha = 0.88f),
                        )
                    }
                    Text(
                        text = resultMessage,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.White,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScannerDetailRow(
                        label = language.pick("Database record", "\u6570\u636e\u5e93\u8bb0\u5f55"),
                        value = liveRecord ?: language.pick("No matched record yet", "\u6682\u672a\u5339\u914d\u5230\u8bb0\u5f55"),
                    )
                    ScannerDetailRow(
                        label = language.pick("Pickup status", "\u63d0\u8d27\u72b6\u6001"),
                        value = when {
                            liveStatus != null -> liveStatus
                            scanner.isProcessing -> language.pick("Verifying", "\u6bd4\u5bf9\u4e2d")
                            else -> language.pick("Waiting", "\u7b49\u5f85\u4e2d")
                        },
                    )
                    liveMatchRoute?.let { matchRoute ->
                        ScannerDetailRow(
                            label = language.pick("Matched by", "\u5339\u914d\u65b9\u5f0f"),
                            value = matchRoute,
                        )
                    }
                    liveMatchedValue?.let { matchedValue ->
                        ScannerDetailRow(
                            label = language.pick("Matched value", "\u547d\u4e2d\u503c"),
                            value = matchedValue,
                        )
                    }
                    liveContainerLine?.let { containerLine ->
                        ScannerDetailRow(
                            label = language.pick("Container / vessel", "\u67dc\u53f7 / \u8239\u540d"),
                            value = containerLine,
                        )
                    }
                    livePartyLine?.let { partyLine ->
                        ScannerDetailRow(
                            label = language.pick("Company / customer", "\u516c\u53f8 / \u5ba2\u6237"),
                            value = partyLine,
                        )
                    }
                    liveStatusPair?.let { statusPair ->
                        ScannerDetailRow(
                            label = language.pick("Clearance state", "\u653e\u884c\u72b6\u6001"),
                            value = statusPair,
                        )
                    }
                    liveQuantityLine?.let { quantityLine ->
                        ScannerDetailRow(
                            label = language.pick("Cargo detail", "\u8d27\u7269\u8be6\u60c5"),
                            value = quantityLine,
                        )
                    }
                    liveProgressLine?.let { progressLine ->
                        ScannerDetailRow(
                            label = language.pick("Scan progress", "\u626b\u7801\u8fdb\u5ea6"),
                            value = progressLine,
                        )
                    }
                    liveCountLine?.let { countLine ->
                        ScannerDetailRow(
                            label = language.pick("Scan totals", "\u626b\u7801\u603b\u8ba1"),
                            value = countLine,
                        )
                    }
                    ScannerDetailRow(
                        label = language.pick("Source", "\u6765\u6e90"),
                        value = liveSource ?: language.pick("Local scanner flow", "\u672c\u5730\u626b\u7801\u6d41\u7a0b"),
                    )
                    visibleRecord?.let { record ->
                        ScannerDetailRow(
                            label = language.pick("Time", "\u65f6\u95f4"),
                            value = formatTimestamp(record.scannedAt),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerInputCard(
    language: AppLanguage,
    scanner: ScannerUiState,
    onInputChanged: (String) -> Unit,
    onVerify: (String) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = language.pick("Manual serial check", "手动序列号校验"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = language.pick(
                    "Use this field only when a serial number needs to be typed manually.",
                    "只有在需要手动输入序列号时才使用这里。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = scanner.barcodeInput,
                onValueChange = { onInputChanged(it.replace("\n", "").replace("\r", "")) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(language.pick("Serial number", "序列号")) },
                placeholder = { Text(language.pick("Scan or type here", "扫描或手动输入")) },
                singleLine = true,
                enabled = !scanner.isProcessing,
            )
            Button(
                onClick = { onVerify(scanner.barcodeInput) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanner.isProcessing,
            ) {
                Text(
                    if (scanner.isProcessing) {
                        language.pick("Verifying...", "正在比对...")
                    } else {
                        language.pick("Verify now", "立即校验")
                    },
                )
            }
        }
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
                Text(
                    text = language.pick("Recent scans", "最近扫码"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = onClearHistory,
                    enabled = scanner.history.isNotEmpty(),
                ) {
                    Text(language.pick("Clear", "清空"))
                }
            }

            if (scanner.history.isEmpty()) {
                Text(
                    text = language.pick(
                        "No scan history yet.",
                        "暂时还没有扫码记录。",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                scanner.history.take(6).forEach { record ->
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
    val clearanceOverallStatus = if (record.matchStatus == ScannerMatchStatus.MATCHED) {
        overallScannerClearanceStatus(record.customersStatus, record.mpiStatus)
    } else {
        null
    }
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
                ScannerStatusPill(
                    label = language.pick("Status", "状态"),
                    value = scannerResultTitle(
                        language = language,
                        result = record.matchStatus,
                        isProcessing = false,
                        clearanceOverallStatus = clearanceOverallStatus,
                    ),
                    color = scannerStatusColor(
                        result = record.matchStatus,
                        isProcessing = false,
                        clearanceOverallStatus = clearanceOverallStatus,
                    ),
                )
            }
            ScannerDetailRow(
                label = language.pick("Database record", "数据库记录"),
                value = localizedScannerDatabaseRecord(language, record.databaseRecord),
            )
            if (record.matchStatus == ScannerMatchStatus.MATCHED &&
                (record.customersStatus != null || record.mpiStatus != null)
            ) {
                ScannerDetailRow(
                    label = language.pick("NZCS / MPI", "NZCS / MPI"),
                    value = listOf(
                        "NZCS ${localizedStatus(language, canonicalScannerClearanceStatus(record.customersStatus))}",
                        "MPI ${localizedStatus(language, canonicalScannerClearanceStatus(record.mpiStatus))}",
                    ).joinToString(" | "),
                )
            }
            ScannerDetailRow(
                label = language.pick("Pickup status", "提货状态"),
                value = localizedScannerStatusText(language, record.status),
            )
            ScannerDetailRow(
                label = language.pick("Source", "来源"),
                value = localizedScannerSource(language, record.source),
            )
            ScannerDetailRow(
                label = language.pick("Time", "时间"),
                value = formatTimestamp(record.scannedAt),
            )
        }
    }
}

@Composable
private fun ScannerDetailRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ScannerMiniStat(
    label: String,
    value: String,
    color: Color,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.08f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(color)
                        .padding(4.dp),
                )
                Text(
                    text = value,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ScannerStatusPill(
    label: String,
    value: String,
    color: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun scannerStatusColor(
    result: ScannerMatchStatus,
    isProcessing: Boolean,
    clearanceOverallStatus: String? = null,
): Color {
    return when {
        isProcessing -> ScannerIdle
        result == ScannerMatchStatus.MATCHED && clearanceOverallStatus == "CLEAR" -> ScannerOk
        result == ScannerMatchStatus.MATCHED && clearanceOverallStatus == "FAILED" -> ScannerErr
        result == ScannerMatchStatus.MATCHED -> ScannerWarn
        result == ScannerMatchStatus.MISMATCH -> ScannerErr
        result == ScannerMatchStatus.ERROR -> ScannerErr
        else -> ScannerIdle
    }
}

private fun scannerResultBackground(
    result: ScannerMatchStatus,
    isProcessing: Boolean,
    clearanceOverallStatus: String? = null,
): List<Color> {
    return when {
        isProcessing -> listOf(ScannerBrandStart, ScannerBrandEnd)
        result == ScannerMatchStatus.MATCHED && clearanceOverallStatus == "CLEAR" ->
            listOf(Color(0xFF169B62), Color(0xFF0B6E3E))
        result == ScannerMatchStatus.MATCHED && clearanceOverallStatus == "FAILED" ->
            listOf(ScannerBrandStart, ScannerBrandEnd)
        result == ScannerMatchStatus.MATCHED -> listOf(ScannerHoldStart, ScannerHoldEnd)
        else -> listOf(ScannerBrandStart, ScannerBrandEnd)
    }
}

private fun scannerResultTitle(
    language: AppLanguage,
    result: ScannerMatchStatus,
    isProcessing: Boolean,
    clearanceOverallStatus: String? = null,
): String {
    return when {
        isProcessing -> language.pick("Verifying", "比对中")
        result == ScannerMatchStatus.MATCHED -> localizedStatus(language, clearanceOverallStatus ?: "HOLD")
        result == ScannerMatchStatus.MISMATCH -> language.pick("Not found", "未找到")
        result == ScannerMatchStatus.ERROR -> language.pick("Error", "错误")
        else -> language.pick("Waiting", "等待中")
    }
}

