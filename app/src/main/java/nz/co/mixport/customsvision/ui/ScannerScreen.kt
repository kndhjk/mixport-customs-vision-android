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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    onScannerSoundChanged: (Boolean) -> Unit,
    onScannerWorkflowModeChanged: (PdaScanWorkflowMode) -> Unit,
    onScannerHistoryCleared: () -> Unit,
    onScannerOnboardingDismissed: () -> Unit,
    onScannerApiBaseUrlChanged: (String) -> Unit,
    onScannerBearerTokenChanged: (String) -> Unit,
    onScannerDeviceIdChanged: (String) -> Unit,
    onScannerRefreshReferences: (Boolean) -> Unit,
    onScannerUploadPending: () -> Unit,
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
                    "Unable to stop the FDA trigger.",
                    "无法停止 FDA 触发。",
                )
            }
            .onSuccess {
                if (updateStatus) {
                    pdaStatusMessage = currentLanguage.pick(
                        "Manual trigger released. Scanner is waiting for the next key press.",
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
                        "Manual scan trigger sent. Tap once for one read, or hold the side key to keep scanning.",
                        "已发送手动扫描触发。按一下扫一次，按住侧键可持续扫描。",
                    )
                }
            }
            .onFailure { throwable ->
                isPdaBridgeReady = false
                pdaStatusMessage = throwable.message ?: currentLanguage.pick(
                    "Unable to trigger the FDA scanner.",
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
                "Hikrobot PDA service is missing on this device.",
                "当前设备缺少 Hikrobot PDA 服务。",
            )
            return@LaunchedEffect
        }

        delay(120)
        val didBind = pdaScanController.bind(
            workflowMode = PdaScanWorkflowMode.TRIGGER_ONCE,
            onReady = {
                pdaStatusMessage = currentLanguage.pick(
                    "FDA scanner bridge ready. Manual trigger mode is loading...",
                    "FDA 扫码桥已就绪，正在切到手动触发模式...",
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
                    "Manual FDA mode is ready. Use the side scan keys or tap Trigger once.",
                    "手动 FDA 模式已就绪。使用机身扫码键，或点击“触发一次”。",
                )
            }
            .onFailure { throwable ->
                isPdaBridgeReady = false
                pdaStatusMessage = throwable.message ?: currentLanguage.pick(
                    "Unable to arm manual FDA mode.",
                    "无法启用手动 FDA 模式。",
                )
            }
    }

    LaunchedEffect(language) {
        pdaStatusMessage = when {
            !isPdaServiceInstalled -> language.pick(
                "Hikrobot PDA service is missing on this device.",
                "当前设备缺少 Hikrobot PDA 服务。",
            )

            activeHardwareKey != null -> language.pick(
                "Hold the side scan key to keep scanning. Release it to stop.",
                "按住侧边扫码键可持续扫描，松开即停止。",
            )

            isPdaBridgeReady -> language.pick(
                "Manual FDA mode is ready. Use the side scan keys or tap Trigger once.",
                "手动 FDA 模式已就绪。使用机身扫码键，或点击“触发一次”。",
            )

            else -> language.pick(
                "Connecting to the Hikrobot PDA service...",
                "正在连接 Hikrobot PDA 服务...",
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
            ScannerSyncCard(
                language = language,
                scanner = scanner,
                onApiBaseUrlChanged = onScannerApiBaseUrlChanged,
                onBearerTokenChanged = onScannerBearerTokenChanged,
                onDeviceIdChanged = onScannerDeviceIdChanged,
                onRefreshReferences = { onScannerRefreshReferences(true) },
                onUploadPending = onScannerUploadPending,
            )
        }
        item {
            ScannerTriggerCard(
                language = language,
                scanner = scanner,
                isPdaServiceInstalled = isPdaServiceInstalled,
                isPdaBridgeReady = isPdaBridgeReady,
                isHardwareKeyActive = activeHardwareKey != null,
                onTriggerOnce = {
                    triggerManualScan(showHint = true)
                    coroutineScope.launch {
                        delay(SINGLE_TRIGGER_LIGHT_OFF_DELAY_MS)
                        if (activeHardwareKey == null) {
                            pdaScanController.stopScan()
                        }
                    }
                },
                onRefresh = {
                    pdaScanController.reconfigure(PdaScanWorkflowMode.TRIGGER_ONCE)
                        .onSuccess {
                            isPdaBridgeReady = true
                            pdaStatusMessage = language.pick(
                                "Scanner settings refreshed. Manual trigger mode is ready.",
                                "扫码配置已刷新，手动触发模式已就绪。",
                            )
                        }
                        .onFailure { throwable ->
                            isPdaBridgeReady = false
                            pdaStatusMessage = throwable.message ?: language.pick(
                                "Unable to refresh the scanner.",
                                "无法刷新扫码配置。",
                            )
                        }
                },
                onSoundChanged = onScannerSoundChanged,
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
    val resultColor = scannerStatusColor(scanner.lastResult, scanner.isProcessing)
    val isWaitingState = !scanner.isProcessing && scanner.lastResult == ScannerMatchStatus.WAITING
    val visibleRecord = latestRecord.takeUnless { isWaitingState }
    val liveLookup = scanner.lastLookupResult.takeUnless { isWaitingState }
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
        liveLookup?.customersStatus?.takeIf(String::isNotBlank)?.let {
            add(language.pick("Customer ${localizedStatus(language, it)}", "\u5ba2\u6237\u72b6\u6001 ${localizedStatus(language, it)}"))
        }
        liveLookup?.mpiStatus?.takeIf(String::isNotBlank)?.let {
            add(language.pick("MPI ${localizedStatus(language, it)}", "MPI \u72b6\u6001 ${localizedStatus(language, it)}"))
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
                        colors = scannerResultBackground(scanner.lastResult, scanner.isProcessing),
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
                        text = scannerResultTitle(language, scanner.lastResult, scanner.isProcessing),
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
                                !isPdaServiceInstalled -> language.pick("Service missing", "\u670d\u52a1\u7f3a\u5931")
                                isPdaBridgeReady -> language.pick("Manual ready", "\u624b\u52a8\u5c31\u7eea")
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
                            label = language.pick("Result", "\u7ed3\u679c"),
                            value = scannerResultTitle(language, scanner.lastResult, scanner.isProcessing),
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
                        label = language.pick("Status", "\u72b6\u6001"),
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
private fun ScannerSyncCard(
    language: AppLanguage,
    scanner: ScannerUiState,
    onApiBaseUrlChanged: (String) -> Unit,
    onBearerTokenChanged: (String) -> Unit,
    onDeviceIdChanged: (String) -> Unit,
    onRefreshReferences: () -> Unit,
    onUploadPending: () -> Unit,
) {
    val sync = scanner.sync
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = language.pick("Server Sync", "服务器同步"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = language.pick(
                    "Pull the latest HBL scanner cache from the Mixport server, keep scanning offline, then upload the completed result batch manually.",
                    "先从 Mixport 服务器拉取最新 HBL 扫码缓存，离线继续扫描，全部完成后再由工作人员手动上传结果批次。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScannerMiniStat(
                    label = language.pick("Cache", "缓存"),
                    value = sync.referenceCount.toString(),
                    color = if (sync.referenceCount > 0) ScannerOk else ScannerWarn,
                )
                ScannerMiniStat(
                    label = language.pick("Pending", "待上传"),
                    value = sync.pendingUploadCount.toString(),
                    color = if (sync.pendingUploadCount > 0) ScannerWarn else ScannerIdle,
                )
            }

            OutlinedTextField(
                value = sync.apiBaseUrl,
                onValueChange = onApiBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(language.pick("API base URL", "API 地址")) },
                singleLine = true,
                enabled = !sync.isRefreshing && !sync.isUploading,
            )
            OutlinedTextField(
                value = sync.bearerToken,
                onValueChange = onBearerTokenChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(language.pick("Bearer token", "Bearer 令牌")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !sync.isRefreshing && !sync.isUploading,
            )
            OutlinedTextField(
                value = sync.deviceId,
                onValueChange = onDeviceIdChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(language.pick("Device ID", "设备 ID")) },
                singleLine = true,
                enabled = !sync.isRefreshing && !sync.isUploading,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onRefreshReferences,
                    enabled = !sync.isRefreshing && !sync.isUploading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (sync.isRefreshing) {
                            language.pick("Refreshing...", "刷新中...")
                        } else {
                            language.pick("Pull latest cache", "拉取最新缓存")
                        },
                    )
                }
                FilledTonalButton(
                    onClick = onUploadPending,
                    enabled = !sync.isRefreshing && !sync.isUploading && sync.pendingUploadCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (sync.isUploading) {
                            language.pick("Uploading...", "上传中...")
                        } else {
                            language.pick("Upload pending", "上传待处理")
                        },
                    )
                }
            }

            sync.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val syncTime = sync.lastReferenceSyncAt?.let(::formatTimestamp)
                ?: language.pick("Not synced yet", "尚未同步")
            val uploadTime = sync.lastUploadAt?.let(::formatTimestamp)
                ?: language.pick("No upload yet", "尚未上传")
            ScannerDetailRow(
                label = language.pick("Last cache sync", "上次缓存同步"),
                value = syncTime,
            )
            ScannerDetailRow(
                label = language.pick("Last manual upload", "上次手动上传"),
                value = if (sync.lastUploadBatchId != null) {
                    "$uploadTime · #${sync.lastUploadBatchId}"
                } else {
                    uploadTime
                },
            )
        }
    }
}

@Composable
private fun ScannerTriggerCard(
    language: AppLanguage,
    scanner: ScannerUiState,
    isPdaServiceInstalled: Boolean,
    isPdaBridgeReady: Boolean,
    isHardwareKeyActive: Boolean,
    onTriggerOnce: () -> Unit,
    onRefresh: () -> Unit,
    onSoundChanged: (Boolean) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = language.pick("Manual FDA Trigger", "手动 FDA 触发"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = language.pick(
                    "The scanner now stays in manual mode only. Tap once for one scan, or hold the side key to keep scanning until release.",
                    "扫码页现在只保留手动模式。按一下扫一次，按住机身侧键则会持续扫描，松开即停止。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScannerMiniStat(
                    label = language.pick("Service", "服务"),
                    value = when {
                        !isPdaServiceInstalled -> language.pick("Missing", "缺失")
                        isPdaBridgeReady -> language.pick("Ready", "就绪")
                        else -> language.pick("Connecting", "连接中")
                    },
                    color = if (isPdaBridgeReady) ScannerOk else ScannerWarn,
                )
                ScannerMiniStat(
                    label = language.pick("Trigger", "触发"),
                    value = if (isHardwareKeyActive) {
                        language.pick("Holding", "按住中")
                    } else {
                        language.pick("Idle", "待命")
                    },
                    color = if (isHardwareKeyActive) ScannerOk else ScannerIdle,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onTriggerOnce,
                    enabled = isPdaServiceInstalled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(language.pick("Trigger once", "触发一次"))
                }
                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = isPdaServiceInstalled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(language.pick("Refresh", "刷新配置"))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(ScannerPanelTint)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = language.pick("Sound prompt", "提示音"),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = language.pick(
                            "Matched, mismatch, and empty/error scans use different tones.",
                            "匹配成功、未匹配、空值或错误会使用不同提示音。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = scanner.isSoundEnabled,
                    onCheckedChange = onSoundChanged,
                )
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
                    label = language.pick("Result", "结果"),
                    value = scannerResultTitle(language, record.matchStatus, isProcessing = false),
                    color = scannerStatusColor(record.matchStatus, isProcessing = false),
                )
            }
            ScannerDetailRow(
                label = language.pick("Database record", "数据库记录"),
                value = localizedScannerDatabaseRecord(language, record.databaseRecord),
            )
            ScannerDetailRow(
                label = language.pick("Status", "状态"),
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
): Color {
    return when {
        isProcessing -> ScannerIdle
        result == ScannerMatchStatus.MATCHED -> ScannerOk
        result == ScannerMatchStatus.MISMATCH -> ScannerWarn
        result == ScannerMatchStatus.ERROR -> ScannerErr
        else -> ScannerIdle
    }
}

private fun scannerResultBackground(
    result: ScannerMatchStatus,
    isProcessing: Boolean,
): List<Color> {
    return when {
        isProcessing -> listOf(ScannerBrandStart, ScannerBrandEnd)
        result == ScannerMatchStatus.MATCHED -> listOf(Color(0xFF169B62), Color(0xFF0B6E3E))
        else -> listOf(ScannerBrandStart, ScannerBrandEnd)
    }
}

private fun scannerResultTitle(
    language: AppLanguage,
    result: ScannerMatchStatus,
    isProcessing: Boolean,
): String {
    return when {
        isProcessing -> language.pick("Verifying", "比对中")
        result == ScannerMatchStatus.MATCHED -> language.pick("Matched", "已匹配")
        result == ScannerMatchStatus.MISMATCH -> language.pick("Not found", "未找到")
        result == ScannerMatchStatus.ERROR -> language.pick("Error", "错误")
        else -> language.pick("Waiting", "等待中")
    }
}

