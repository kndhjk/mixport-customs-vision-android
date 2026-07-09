package nz.co.mixport.customsvision.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.co.mixport.customsvision.BuildConfig
import nz.co.mixport.customsvision.camera.InspectionCameraController
import nz.co.mixport.customsvision.camera.LiveDetectionFrame
import nz.co.mixport.customsvision.camera.LiveRecognition
import nz.co.mixport.customsvision.camera.LiveTrackCountEngine
import nz.co.mixport.customsvision.camera.UniversalRecognition
import nz.co.mixport.customsvision.data.CargoSummaryRecord
import nz.co.mixport.customsvision.data.InspectionSessionRecord
import nz.co.mixport.customsvision.data.PalletDetail
import nz.co.mixport.customsvision.domain.SessionPhase
import nz.co.mixport.customsvision.domain.WorkflowEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private val TrackingGreen = Color(0xFF39D353)
private val CountedGreen = Color(0xFF238636)
private val OverlayScrim = Color(0x22000000)
private val BrandGradientStart = Color(0xFFF45D22)
private val BrandGradientEnd = Color(0xFFD94D1A)
private val BrandTint = Color(0xFFFFF3EB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomsApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val language = uiState.appLanguage

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setCameraPermission(granted)
    }
    val requestCameraPermission = remember(permissionLauncher) {
        {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setCameraPermission(granted)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                title = {
                    Column {
                        Text(language.pick("Mixport Customs Vision", "Mixport 智能海关"))
                        Text(
                            text = language.pick(
                                "Cargo tracking, pallet counting, evidence capture, and scanner workflows",
                                "货物追踪、托盘计数、证据留存与扫码作业",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = viewModel::toggleLanguage,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            if (language == nz.co.mixport.customsvision.data.AppLanguage.ENGLISH) {
                                "中文"
                            } else {
                                "EN"
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                NavigationBarItem(
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    selected = uiState.selectedDestination == AppDestination.LIVE,
                    onClick = { viewModel.selectDestination(AppDestination.LIVE) },
                    icon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                    label = { Text(language.pick("Live", "识别")) },
                )
                NavigationBarItem(
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    selected = uiState.selectedDestination == AppDestination.SCANNER,
                    onClick = { viewModel.selectDestination(AppDestination.SCANNER) },
                    icon = { Icon(Icons.Outlined.StopCircle, contentDescription = null) },
                    label = { Text(language.pick("Scanner", "扫码")) },
                )
                NavigationBarItem(
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    selected = uiState.selectedDestination == AppDestination.HISTORY,
                    onClick = { viewModel.selectDestination(AppDestination.HISTORY) },
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    label = { Text(language.pick("History", "历史")) },
                )
            }
        },
    ) { padding ->
        when (uiState.selectedDestination) {
            AppDestination.LIVE -> LiveScreen(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                onDismissMessage = viewModel::clearMessages,
                onContainerCodeChanged = viewModel::updateContainerCode,
                onVesselNameChanged = viewModel::updateVesselName,
                onOperatorNameChanged = viewModel::updateOperatorName,
                onNotesChanged = viewModel::updateNotes,
                onStartSession = viewModel::startSession,
                onCloseSession = viewModel::closeSession,
                onWorkflowEvent = viewModel::submitWorkflowEvent,
                onCountVisibleDetections = viewModel::countVisibleDetections,
                onDetectionFrame = viewModel::onDetections,
                onHeartbeat = viewModel::onFrameHeartbeat,
                onRecordingStarted = viewModel::onRecordingStarted,
                onRecordingSaved = viewModel::onRecordingSaved,
                onRecordingError = viewModel::onRecordingError,
                onUniversalRecognitionStarted = viewModel::onUniversalRecognitionStarted,
                onUniversalRecognitionCompleted = viewModel::onUniversalRecognitionCompleted,
                onUniversalRecognitionError = viewModel::onUniversalRecognitionError,
                onRequestCameraPermission = requestCameraPermission,
            )

            AppDestination.SCANNER -> ScannerScreen(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                onScannerInputChanged = viewModel::updateScannerInput,
                onScannerVerify = viewModel::verifyScannerBarcode,
                onScannerPdaDetected = viewModel::onScannerPdaDetected,
                onScannerAutoVerifyChanged = viewModel::setScannerAutoVerifyEnabled,
                onScannerSoundChanged = viewModel::setScannerSoundEnabled,
                onScannerWorkflowModeChanged = viewModel::setScannerWorkflowMode,
                onScannerHistoryCleared = viewModel::clearScannerHistory,
                onScannerOnboardingDismissed = viewModel::dismissScannerOnboarding,
            )

            AppDestination.HISTORY -> HistoryScreen(
                modifier = Modifier.padding(padding),
                language = language,
                sessions = uiState.history,
            )
        }
    }
}

@Composable
private fun LiveScreen(
    modifier: Modifier = Modifier,
    uiState: LiveInspectionUiState,
    onDismissMessage: () -> Unit,
    onContainerCodeChanged: (String) -> Unit,
    onVesselNameChanged: (String) -> Unit,
    onOperatorNameChanged: (String) -> Unit,
    onNotesChanged: (String) -> Unit,
    onStartSession: () -> Unit,
    onCloseSession: () -> Unit,
    onWorkflowEvent: (WorkflowEvent) -> Unit,
    onCountVisibleDetections: () -> Unit,
    onDetectionFrame: (LiveDetectionFrame) -> Unit,
    onHeartbeat: (Long) -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingSaved: (String) -> Unit,
    onRecordingError: (String) -> Unit,
    onUniversalRecognitionStarted: () -> Unit,
    onUniversalRecognitionCompleted: (nz.co.mixport.customsvision.camera.UniversalRecognitionSnapshot) -> Unit,
    onUniversalRecognitionError: (String) -> Unit,
    onRequestCameraPermission: () -> Unit,
) {
    val context = LocalContext.current
    val shouldCreateCameraController = uiState.cameraPermissionGranted
    val cameraController = remember(
        context,
        shouldCreateCameraController,
        uiState.inspectionTuning,
    ) {
        if (shouldCreateCameraController) {
            InspectionCameraController(context, uiState.inspectionTuning)
        } else {
            null
        }
    }

    DisposableEffect(cameraController) {
        onDispose {
            cameraController?.release()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            if (uiState.infoMessage != null || uiState.errorMessage != null) {
                MessageCard(
                    language = uiState.appLanguage,
                    infoMessage = uiState.infoMessage,
                    errorMessage = uiState.errorMessage,
                    onDismiss = onDismissMessage,
                )
            }
        }
        item {
            PilotHeroCard(uiState = uiState)
        }
        item {
            CameraCard(
                uiState = uiState,
                cameraController = cameraController,
                onHeartbeat = onHeartbeat,
                onDetectionFrame = onDetectionFrame,
                onRecordingStarted = onRecordingStarted,
                onRecordingSaved = onRecordingSaved,
                onRecordingError = onRecordingError,
                onRequestCameraPermission = onRequestCameraPermission,
            )
        }
        item {
            StatusCard(uiState = uiState)
        }
        item {
            TuningProfileCard(uiState = uiState)
        }
        item {
            LiveDetectionsCard(
                uiState = uiState,
                onCountVisibleDetections = onCountVisibleDetections,
                onAnalyzeVisibleCargo = {
                    val liveCameraController = cameraController
                    if (liveCameraController == null) {
                        onUniversalRecognitionError(
                            uiState.appLanguage.pick(
                                "Start a session and wait for the camera to connect before running cargo analysis.",
                                "请先开始作业并等待相机连接后，再运行货物分析。",
                            ),
                        )
                    } else {
                        onUniversalRecognitionStarted()
                        liveCameraController.analyzeVisibleCargo(
                            detections = uiState.liveDetections,
                            onComplete = onUniversalRecognitionCompleted,
                            onError = onUniversalRecognitionError,
                        )
                    }
                },
            )
        }
        item {
            SessionSetupCard(
                uiState = uiState,
                onContainerCodeChanged = onContainerCodeChanged,
                onVesselNameChanged = onVesselNameChanged,
                onOperatorNameChanged = onOperatorNameChanged,
                onNotesChanged = onNotesChanged,
                onStartSession = onStartSession,
                onCloseSession = onCloseSession,
            )
        }
        item {
            ManualControlsCard(
                language = uiState.appLanguage,
                enabled = uiState.activeSession != null,
                onWorkflowEvent = onWorkflowEvent,
            )
        }
        item {
            CurrentPalletCard(
                language = uiState.appLanguage,
                phase = uiState.workflowState.phase,
                currentPalletSequence = uiState.workflowState.activePalletSequence,
                items = uiState.currentPalletItems,
            )
        }
        item {
            SealedPalletsCard(
                language = uiState.appLanguage,
                pallets = uiState.sealedPallets,
            )
        }
        item {
            EventFeedCard(uiState = uiState, language = uiState.appLanguage)
        }
        item {
            EndpointCard(language = uiState.appLanguage)
        }
    }
}

@Composable
private fun PilotHeroCard(uiState: LiveInspectionUiState) {
    val language = uiState.appLanguage
    Surface(
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(BrandGradientStart, BrandGradientEnd),
                    ),
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = language.pick("Mixport pilot deck", "Mixport 试点作业台"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                )
                Text(
                    text = language.pick(
                        "Unload cargo with live camera tracking, pallet event capture, scanner support, and shipment evidence on one screen.",
                        "在一个页面里完成实时货物追踪、托盘事件记录、扫码支持和作业证据留存。",
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (uiState.cameraPermissionGranted) {
                                        language.pick("Camera ready", "相机已就绪")
                                    } else {
                                        language.pick("Camera access needed", "需要相机权限")
                                    },
                                )
                            },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    uiState.activeSession?.containerCode ?: language.pick("No live session", "暂无作业"),
                                )
                            },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(language.pick("Tracks ${uiState.liveDetections.size}", "跟踪 ${uiState.liveDetections.size}"))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSetupCard(
    uiState: LiveInspectionUiState,
    onContainerCodeChanged: (String) -> Unit,
    onVesselNameChanged: (String) -> Unit,
    onOperatorNameChanged: (String) -> Unit,
    onNotesChanged: (String) -> Unit,
    onStartSession: () -> Unit,
    onCloseSession: () -> Unit,
) {
    val language = uiState.appLanguage
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(language.pick("Session Setup", "作业设置"), style = MaterialTheme.typography.titleMedium)
            Text(
                text = language.pick(
                    "Use the same orange-white workflow language as the Mixport cargo pages: start a lane, keep the camera rolling, and archive pallet evidence at the end.",
                    "沿用 Mixport 货运页面的橙白作业语言：开始一票作业、持续录像，并在结束时归档托盘证据。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.draft.containerCode,
                onValueChange = onContainerCodeChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(language.pick("Container Code", "柜号")) },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draft.vesselName,
                onValueChange = onVesselNameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(language.pick("Vessel / Lane", "船名 / 航线")) },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draft.operatorName,
                onValueChange = onOperatorNameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(language.pick("Operator", "操作员")) },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draft.notes,
                onValueChange = onNotesChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(language.pick("Notes", "备注")) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStartSession,
                    enabled = uiState.activeSession == null,
                ) {
                    Text(language.pick("Start Session", "开始作业"))
                }
                OutlinedButton(
                    onClick = onCloseSession,
                    enabled = uiState.activeSession != null,
                ) {
                    Text(language.pick("Close Session", "关闭作业"))
                }
            }
            uiState.activeSession?.let { session ->
                Text(
                    text = language.pick(
                        "Active session: ${session.containerCode} | ${session.operatorName}",
                        "当前作业：${session.containerCode} | ${session.operatorName}",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: LiveInspectionUiState) {
    val language = uiState.appLanguage
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(language.pick("Workflow Status", "流程状态"), style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    AssistChip(
                        onClick = {},
                        label = { Text(language.pick("Phase ${uiState.workflowState.phase.name}", "阶段 ${uiState.workflowState.phase.name}")) },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (uiState.workflowState.containerHasRemainingCargo) {
                                    language.pick("Cargo still in container", "柜内仍有货物")
                                } else {
                                    language.pick("Container looks empty", "货柜看起来已空")
                                },
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = { Text(language.pick("Tracks ${uiState.liveDetections.size}", "跟踪 ${uiState.liveDetections.size}")) },
                    )
                }
            }
            val heartbeatText = uiState.lastFrameHeartbeatAt?.let {
                language.pick(
                    "Vision heartbeat: ${formatTimestamp(it)}",
                    "视觉心跳：${formatTimestamp(it)}",
                )
            } ?: language.pick("Vision heartbeat: waiting", "视觉心跳：等待中")
            Text(text = heartbeatText, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = language.pick(
                    "Green boxes come from the live proposal detector. On-device optimization now limits frame cadence first, then reserves heavier recognition for cropped stable targets. The pallet wrap step is still manual.",
                    "绿色框来自实时 proposal 检测器。当前端侧优化会先限制分析帧率，再把更重的识别留给稳定目标的裁剪图。托盘缠膜步骤目前仍为手动确认。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uiState.recordingUri?.let {
                Text(
                    text = language.pick("Last video: $it", "最近视频：$it"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TuningProfileCard(uiState: LiveInspectionUiState) {
    val language = uiState.appLanguage
    val tuning = uiState.inspectionTuning
    val mobileVisionProfile = uiState.mobileVisionProfile
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                language.pick("Active Tuning Profile", "当前参数档案"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = language.pick(
                    "Source: ${uiState.inspectionTuningSource}",
                    "来源：${uiState.inspectionTuningSource}",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                language.pick(
                                    "Stable ${tuning.tracking.minStableFrames}",
                                    "稳定帧 ${tuning.tracking.minStableFrames}",
                                ),
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                language.pick(
                                    "Gap ${tuning.tracking.maxTrackGapMs}ms",
                                    "断帧 ${tuning.tracking.maxTrackGapMs}ms",
                                ),
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                language.pick(
                                    "Pallet live ${formatPercent(tuning.palletReference.livePalletThreshold)}",
                                    "托盘实时 ${formatPercent(tuning.palletReference.livePalletThreshold)}",
                                ),
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                language.pick(
                                    "Pallet OCR ${formatPercent(tuning.palletReference.recognitionPalletThreshold)}",
                                    "托盘识别 ${formatPercent(tuning.palletReference.recognitionPalletThreshold)}",
                                ),
                            )
                        },
                    )
                }
                mobileVisionProfile?.let { profile ->
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    language.pick(
                                        "Tier ${profile.deviceTier.name}",
                                        "档位 ${profile.deviceTier.name}",
                                    ),
                                )
                            },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    language.pick(
                                        "~${profile.liveAnalysisFpsCap} FPS cap",
                                        "约 ${profile.liveAnalysisFpsCap} FPS 上限",
                                    ),
                                )
                            },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    language.pick(
                                        "Crop ${profile.recognitionDownsampleMaxEdgePx}px",
                                        "裁剪 ${profile.recognitionDownsampleMaxEdgePx}px",
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            Text(
                text = language.pick(
                    "Image labels under ${formatPercent(tuning.cargoLabeling.minImageLabelConfidence)} are ignored. Generic detections only count after ${formatPercent(tuning.cargoLabeling.minReliableDetectionConfidence)} fallback confidence or richer OCR/label evidence.",
                    "低于 ${formatPercent(tuning.cargoLabeling.minImageLabelConfidence)} 的图像标签会被忽略。通用目标只有在达到 ${formatPercent(tuning.cargoLabeling.minReliableDetectionConfidence)} 的兜底置信度，或拿到更丰富的 OCR / 标签证据后才会计数。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            mobileVisionProfile?.let { profile ->
                Text(
                    text = language.pick(
                        "Mobile path: live proposal detector first, then ${profile.transformerSummary} on at most ${profile.transformerMaxTracksPerPass} stable crops per pass. This keeps the phone-friendly path ready before custom training data arrives.",
                        "端侧路径：先做实时 proposal 检测，再对每轮最多 ${profile.transformerMaxTracksPerPass} 个稳定裁剪目标跑 ${profile.transformerSummary}。这样在自定义训练数据到位前，也先保持手机可运行。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = language.pick(
                    "Future dataset calibration should emit an `inspection_tuning_profile.json` file matching this schema so the app can adopt new thresholds directly.",
                    "后续数据集校准时，应产出同结构的 `inspection_tuning_profile.json`，这样 app 可以直接接入新阈值。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CameraCard(
    uiState: LiveInspectionUiState,
    cameraController: InspectionCameraController?,
    onHeartbeat: (Long) -> Unit,
    onDetectionFrame: (LiveDetectionFrame) -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingSaved: (String) -> Unit,
    onRecordingError: (String) -> Unit,
    onRequestCameraPermission: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeSession = uiState.activeSession
    val language = uiState.appLanguage
    val shouldBindCamera = uiState.cameraPermissionGranted
    val analysisEnabled = activeSession != null
    val liveFeedActive = analysisEnabled && uiState.lastFrameHeartbeatAt != null
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var isPreviewReady by remember(cameraController) { mutableStateOf(false) }
    var isRearFlashAvailable by remember(cameraController) { mutableStateOf(false) }
    var isRearFlashEnabled by remember(cameraController) { mutableStateOf(false) }
    var cameraStatusNote by remember(cameraController, language) { mutableStateOf<String?>(null) }

    DisposableEffect(previewView, lifecycleOwner, shouldBindCamera, analysisEnabled) {
        val boundPreviewView = previewView
        val liveCameraController = cameraController
        if (shouldBindCamera && boundPreviewView != null && liveCameraController != null) {
            boundPreviewView.post {
                liveCameraController.bind(
                    previewView = boundPreviewView,
                    lifecycleOwner = lifecycleOwner,
                    enableAnalysis = analysisEnabled,
                    onFrameHeartbeat = onHeartbeat,
                    onDetections = onDetectionFrame,
                    onCameraReady = {
                        isPreviewReady = true
                        isRearFlashAvailable = liveCameraController.hasRearFlashUnit()
                        isRearFlashEnabled = liveCameraController.isRearTorchEnabled()
                    },
                    onError = onRecordingError,
                )
            }
        } else {
            isPreviewReady = false
            liveCameraController?.unbind()
        }
        onDispose {
            isPreviewReady = false
            liveCameraController?.unbind()
        }
    }

    LaunchedEffect(cameraController, shouldBindCamera, analysisEnabled, liveFeedActive, isPreviewReady, language) {
        if (!shouldBindCamera || cameraController == null) {
            isPreviewReady = false
            isRearFlashAvailable = false
            isRearFlashEnabled = false
            cameraStatusNote = null
            return@LaunchedEffect
        }
        isRearFlashAvailable = cameraController.hasRearFlashUnit()
        isRearFlashEnabled = cameraController.isRearTorchEnabled()
        cameraStatusNote = when {
            !isPreviewReady -> language.pick(
                "Preparing the rear preview...",
                "正在准备后置预览...",
            )

            liveFeedActive -> language.pick(
                "Live view uses the phone's rear camera and rear flash. The Hikvision PDA scanner stays separate on the Scanner tab.",
                "识别页使用手机后置摄像头和后置闪光灯，海康 PDA 扫码链路仍然只保留在扫码页。",
            )

            analysisEnabled -> language.pick(
                "Rear preview is running. Live counting will start as soon as stable objects enter view.",
                "后置预览已启动，稳定目标进入画面后就会开始实时计数。",
            )

            else -> language.pick(
                "Rear preview is live. Start a session when you want tracking, counting, and recording.",
                "后置预览已启动。需要追踪、计数和录像时再开始作业。",
            )
        }
    }

    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(language.pick("Live Camera", "实时相机"), style = MaterialTheme.typography.titleMedium)
            Text(
                text = language.pick(
                    "Primary live zone for the customs pilot. This panel should open first and mirror the orange brand language from the Mixport cargo portal.",
                    "这是智能海关试点的主识别区。它会优先打开，并延续 Mixport 货运门户的橙色品牌语言。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!uiState.cameraPermissionGranted) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = language.pick(
                            "Camera permission is only needed when you start live cargo vision on this device.",
                            "这台设备只有在你真正进入实时货物识别时，才需要相机权限。",
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onRequestCameraPermission) {
                        Text(language.pick("Grant Camera Permission", "授予相机权限"))
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            PreviewView(context).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                previewView = this
                            }
                        },
                        update = { view ->
                            previewView = view
                        },
                    )
                    LiveDetectionOverlay(
                        detections = uiState.liveDetections,
                        language = language,
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        color = when {
                            liveFeedActive -> CountedGreen.copy(alpha = 0.88f)
                            isPreviewReady -> MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
                            else -> OverlayScrim
                        },
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = when {
                                liveFeedActive -> language.pick("Live feed active", "实时画面已激活")
                                isPreviewReady -> language.pick("Rear preview active", "后置预览已激活")
                                else -> language.pick("Connecting camera...", "正在连接相机...")
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        color = OverlayScrim,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = when {
                                !isPreviewReady -> language.pick(
                                    "Point the rear camera at the cargo area",
                                    "请将后置镜头对准货物区域",
                                )

                                !analysisEnabled -> language.pick(
                                    "Preview only. Start a session to enable tracking and recording.",
                                    "当前仅预览。开始作业后会启用追踪和录像。",
                                )

                                uiState.liveDetections.isEmpty() -> language.pick(
                                    "No tracked object yet",
                                    "暂未跟踪到对象",
                                )

                                else -> language.pick(
                                    "Green boxes are tracking stable cargo candidates.",
                                    "绿色框正在追踪稳定货物目标。",
                                )
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val liveCameraController = cameraController
                        if (uiState.isRecording) {
                            liveCameraController?.stopRecording()
                        } else if (activeSession != null) {
                            val stamp = activeSession.containerCode.replace(" ", "_")
                            if (liveCameraController == null) {
                                onRecordingError(
                                    language.pick(
                                        "Camera is still preparing. Wait a moment and try again.",
                                        "相机仍在准备中，请稍等后重试。",
                                    ),
                                )
                                return@Button
                            }
                            liveCameraController.startRecording(
                                displayName = "mixport-customs-$stamp-${System.currentTimeMillis()}",
                                onSaved = onRecordingSaved,
                                onError = onRecordingError,
                            )
                            onRecordingStarted()
                        } else {
                            onRecordingError(language.pick("Start a session before recording.", "开始作业后才能录像。"))
                        }
                    },
                    enabled = uiState.cameraPermissionGranted,
                ) {
                    Icon(
                        imageVector = if (uiState.isRecording) {
                            Icons.Outlined.StopCircle
                        } else {
                            Icons.Outlined.CameraAlt
                        },
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (uiState.isRecording) {
                            language.pick("Stop Recording", "停止录像")
                        } else {
                            language.pick("Start Recording", "开始录像")
                        },
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val liveCameraController = cameraController
                        if (liveCameraController == null) {
                            cameraStatusNote = language.pick(
                                "Rear camera is still preparing.",
                                "后置相机仍在准备中。",
                            )
                            return@FilledTonalButton
                        }
                        liveCameraController.setRearTorchEnabled(
                            enabled = !isRearFlashEnabled,
                            onComplete = { enabled ->
                                isRearFlashAvailable = liveCameraController.hasRearFlashUnit()
                                isRearFlashEnabled = enabled
                                cameraStatusNote = if (enabled) {
                                    language.pick(
                                        "Rear flash enabled for live cargo vision.",
                                        "已为识别页开启后置闪光灯。",
                                    )
                                } else {
                                    language.pick(
                                        "Rear flash turned off. The Hikvision PDA light is unaffected.",
                                        "后置闪光灯已关闭，不影响海康 PDA 的扫码补光灯。",
                                    )
                                }
                            },
                            onError = {
                                isRearFlashAvailable = liveCameraController.hasRearFlashUnit()
                                isRearFlashEnabled = liveCameraController.isRearTorchEnabled()
                                cameraStatusNote = if (liveCameraController.hasRearFlashUnit()) {
                                    language.pick(
                                        "Unable to switch the rear flash right now.",
                                        "暂时无法切换后置闪光灯。",
                                    )
                                } else {
                                    language.pick(
                                        "This rear camera does not expose a flash unit.",
                                        "这台设备的后置相机没有可用闪光灯。",
                                    )
                                }
                            },
                        )
                    },
                    enabled = uiState.cameraPermissionGranted,
                ) {
                    Text(
                        if (isRearFlashEnabled) {
                            language.pick("Rear flash on", "后闪已开")
                        } else if (isRearFlashAvailable) {
                            language.pick("Rear flash off", "后闪已关")
                        } else {
                            language.pick("Rear flash", "后置闪光灯")
                        },
                    )
                }
            }
            cameraStatusNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LiveDetectionOverlay(
    detections: List<LiveRecognition>,
    language: nz.co.mixport.customsvision.data.AppLanguage,
) {
    val density = LocalDensity.current
    Box(modifier = Modifier.fillMaxSize()) {
        detections.forEach { detection ->
            val widthDp = with(density) { detection.width.toDp() }
            val heightDp = with(density) { detection.height.toDp() }
            val labelTop = max(
                0f,
                detection.top - with(density) { 32.dp.toPx() },
            )

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = detection.left.roundToInt(),
                            y = detection.top.roundToInt(),
                        )
                    }
                    .size(width = widthDp, height = heightDp)
                    .border(
                        width = 2.dp,
                        color = TrackingGreen,
                        shape = RoundedCornerShape(12.dp),
                    ),
            )

            Surface(
                modifier = Modifier.offset {
                    IntOffset(
                        x = detection.left.roundToInt(),
                        y = labelTop.roundToInt(),
                    )
                },
                color = if (detection.isCounted) CountedGreen else OverlayScrim,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = localizedDetectionTitle(language, detection),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LiveDetectionsCard(
    uiState: LiveInspectionUiState,
    onCountVisibleDetections: () -> Unit,
    onAnalyzeVisibleCargo: () -> Unit,
) {
    val language = uiState.appLanguage
    val recognitionByTrackKey = uiState.universalRecognitionSnapshot?.items
        ?.mapNotNull { item -> item.trackKey.takeIf { it.isNotBlank() }?.let { trackKey -> trackKey to item } }
        ?.toMap()
        .orEmpty()
    val genericLabels = setOf("Tracked cargo", "Pallet candidate", "Wood pallet base", "Unknown", "Unclassified")
    fun hasReliableIdentity(detection: LiveRecognition): Boolean {
        val recognition = recognitionByTrackKey[detection.trackKey]
        return recognition?.bestLabel?.let { it.isNotBlank() && it !in genericLabels } == true ||
            detection.label.isNotBlank() && detection.label !in genericLabels ||
            detection.category.isNotBlank() && detection.category !in genericLabels
    }
    val readyToCount = uiState.liveDetections.count { detection ->
        !detection.isPalletCandidate &&
            detection.isInPalletZone &&
            detection.isCountReady &&
            hasReliableIdentity(detection) &&
            !detection.isCounted
    }
    val stabilizing = uiState.liveDetections.count { detection ->
        !detection.isPalletCandidate &&
            detection.isInPalletZone &&
            detection.stableFrameCount < LiveTrackCountEngine.DEFAULT_MIN_STABLE_FRAMES &&
            !detection.isCounted
    }
    val awaitingPlacement = uiState.liveDetections.count { detection ->
        !detection.isPalletCandidate && !detection.isInPalletZone && !detection.isCounted
    }
    val awaitingAnalysis = uiState.liveDetections.count { detection ->
        !detection.isPalletCandidate &&
            detection.isInPalletZone &&
            detection.stableFrameCount >= LiveTrackCountEngine.DEFAULT_MIN_STABLE_FRAMES &&
            !hasReliableIdentity(detection) &&
            !detection.isCounted
    }
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                language.pick("Live Detections", "实时识别"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = language.pick(
                    "This section reflects the live green-box tracker. The pallet rule is now tuned toward low, wide wooden shipping pallets like your reference photo, then the auxiliary recognizer adds OCR, labels, and color hints before counting.",
                    "这里展示实时绿色框追踪结果。托盘规则已针对你提供的低矮宽木托盘样式做过加强，之后再结合 OCR、标签和颜色线索完成辅助识别与计数。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Button(
                        onClick = onCountVisibleDetections,
                        enabled = uiState.activeSession != null &&
                            readyToCount > 0,
                    ) {
                        Text(language.pick("Count Visible Cargo", "计数当前货物"))
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = onAnalyzeVisibleCargo,
                        enabled = uiState.liveDetections.any { !it.isPalletCandidate } &&
                            !uiState.isUniversalRecognitionRunning,
                    ) {
                        Text(
                            if (uiState.isUniversalRecognitionRunning) {
                                language.pick("Analyzing...", "分析中...")
                            } else {
                                language.pick("Analyze Visible Cargo", "分析当前画面")
                            },
                        )
                    }
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                language.pick(
                                    "${uiState.liveDetections.size} tracked",
                                    "已追踪 ${uiState.liveDetections.size} 个",
                                ),
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(language.pick("Ready $readyToCount", "可计数 $readyToCount"))
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(language.pick("Stabilizing $stabilizing", "稳定中 $stabilizing"))
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(language.pick("Off pallet $awaitingPlacement", "未上托盘 $awaitingPlacement"))
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(language.pick("Need labels $awaitingAnalysis", "待补标签 $awaitingAnalysis"))
                        },
                    )
                }
            }
            if (uiState.isUniversalRecognitionRunning) {
                Text(
                    text = language.pick(
                        "Capturing the current frame and extracting OCR, labels, and color hints.",
                        "正在截取当前画面并提取 OCR、标签和颜色线索。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.liveDetections.isEmpty()) {
                Text(
                    text = language.pick(
                        "Aim the camera at a pallet or cargo. The first launch can take a moment while the detector model warms up.",
                        "将相机对准托盘或货物。首次启动时模型预热可能需要几秒。",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                uiState.liveDetections.take(6).forEach { detection ->
                    DetectionRow(
                        language = language,
                        detection = detection,
                    )
                }
            }
            uiState.universalRecognitionSnapshot?.let { snapshot ->
                HorizontalDivider()
                Text(
                    language.pick("Visible Cargo Insights", "货物识别详情"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = language.pick(
                        "Snapshot ${formatTimestamp(snapshot.analyzedAt)}",
                        "快照时间 ${formatTimestamp(snapshot.analyzedAt)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.items.isEmpty()) {
                    Text(
                        text = language.pick(
                            "No auxiliary recognition results are available for the current frame.",
                            "当前画面暂无辅助识别结果。",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    snapshot.items.forEach { recognition ->
                        RecognitionRow(
                            language = language,
                            recognition = recognition,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectionRow(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    detection: LiveRecognition,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = BrandTint,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = localizedDetectionTitle(language, detection),
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                detection.isPalletCandidate -> language.pick("Pallet", "托盘")
                                detection.isCounted -> language.pick("Counted", "已计数")
                                detection.isCountReady -> language.pick("Ready", "可计数")
                                !detection.isInPalletZone -> language.pick("Off pallet", "未上托盘")
                                else -> language.pick("Stabilizing", "稳定中")
                            },
                        )
                    },
                )
            }
            Text(
                text = language.pick(
                    "Category: " + detection.category,
                    "类别：" + detection.category,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = language.pick(
                    "Confidence: " + formatConfidence(detection.confidence),
                    "置信度：" + formatConfidence(detection.confidence),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = language.pick(
                    "Stable frames: " + detection.stableFrameCount + "/" + LiveTrackCountEngine.DEFAULT_MIN_STABLE_FRAMES,
                    "稳定帧数：" + detection.stableFrameCount + "/" + LiveTrackCountEngine.DEFAULT_MIN_STABLE_FRAMES,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (detection.isCountReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = language.pick(
                    if (detection.isInPalletZone) {
                        "Pallet zone: inside loading area"
                    } else {
                        "Pallet zone: not seated on pallet yet"
                    },
                    if (detection.isInPalletZone) {
                        "托盘区域：已进入装货区域"
                    } else {
                        "托盘区域：还没有坐落到托盘上"
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (detection.isInPalletZone) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            detection.palletScore?.let { score ->
                Text(
                    text = language.pick(
                        "Pallet profile: " + formatPercent(score),
                        "托盘特征匹配：" + formatPercent(score),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detection.isPalletCandidate) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun RecognitionRow(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    recognition: UniversalRecognition,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (recognition.isCounted) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = localizedRecognitionTitle(language, recognition),
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                recognition.isPalletLike -> language.pick("Pallet base", "托盘底座")
                                recognition.isCounted -> language.pick("Counted", "已计数")
                                recognition.isCountReady -> language.pick("Ready", "可计数")
                                !recognition.isInPalletZone -> language.pick("Off pallet", "未上托盘")
                                else -> language.pick("Stabilizing", "稳定中")
                            },
                        )
                    },
                )
            }
            if (recognition.sourceLabel != recognition.bestLabel) {
                Text(
                    text = language.pick(
                        "Tracked as: " + localizedCargoLabel(language, recognition.sourceLabel),
                        "原始跟踪标签：" + localizedCargoLabel(language, recognition.sourceLabel),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = language.pick(
                    "Color: " + recognition.dominantColor,
                    "颜色：" + recognition.dominantColor,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recognition.isPalletLike) {
                Text(
                    text = language.pick(
                        "This object matches the wooden pallet profile and will be excluded from cargo counting.",
                        "该目标与木托盘特征匹配，会从货物计数中排除。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = language.pick(
                    "OCR / marker: " + recognition.markerText.ifBlank { "None detected" },
                    "OCR / 标记：" + recognition.markerText.ifBlank { "未识别" },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = language.pick(
                    "Label hints: " + recognition.labelHints.ifEmpty { listOf("No hints") }.joinToString(),
                    "标签线索：" + recognition.labelHints.ifEmpty { listOf("无") }.joinToString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = language.pick(
                    "Confidence: " + formatConfidence(recognition.confidence),
                    "置信度：" + formatConfidence(recognition.confidence),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = language.pick(
                    "Stable frames: " + recognition.stableFrameCount + "/" + LiveTrackCountEngine.DEFAULT_MIN_STABLE_FRAMES,
                    "稳定帧数：" + recognition.stableFrameCount + "/" + LiveTrackCountEngine.DEFAULT_MIN_STABLE_FRAMES,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (recognition.isCountReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = language.pick(
                    if (recognition.isInPalletZone) {
                        "Pallet zone: inside loading area"
                    } else {
                        "Pallet zone: not seated on pallet yet"
                    },
                    if (recognition.isInPalletZone) {
                        "托盘区域：已进入装货区域"
                    } else {
                        "托盘区域：还没有坐落到托盘上"
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (recognition.isInPalletZone) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            recognition.palletScore?.let { score ->
                Text(
                    text = language.pick(
                        "Pallet profile: " + formatPercent(score),
                        "托盘特征匹配：" + formatPercent(score),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recognition.isPalletLike) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ManualControlsCard(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    enabled: Boolean,
    onWorkflowEvent: (WorkflowEvent) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                language.pick("Manual Controls", "人工控制"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = language.pick(
                    "Keep these controls as fallback while pallet wrap detection and custom cargo classification are still being trained.",
                    "在托盘缠膜识别和自定义货物分类继续训练前，这组按钮作为人工兜底流程保留。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onWorkflowEvent(WorkflowEvent.PalletArrived(System.currentTimeMillis()))
                    },
                    enabled = enabled,
                ) {
                    Text(language.pick("Open Pallet", "开启托盘"))
                }
                OutlinedButton(
                    onClick = {
                        onWorkflowEvent(
                            WorkflowEvent.ContainerContentUpdated(
                                hasRemainingCargo = true,
                                observedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    enabled = enabled,
                ) {
                    Text(language.pick("Container Has Cargo", "货柜还有货"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        onWorkflowEvent(
                            WorkflowEvent.CargoPlaced(
                                itemLabel = language.pick("Manual cargo", "人工货物"),
                                colorName = language.pick("Unclassified", "未分类"),
                                markerText = language.pick("Manual", "人工"),
                                observedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    enabled = enabled,
                ) {
                    Text(language.pick("Add Manual Cargo", "添加人工货物"))
                }
                Button(
                    onClick = {
                        onWorkflowEvent(WorkflowEvent.PalletWrapped(System.currentTimeMillis()))
                    },
                    enabled = enabled,
                ) {
                    Text(language.pick("Wrap Pallet", "封膜托盘"))
                }
            }
            OutlinedButton(
                onClick = {
                    onWorkflowEvent(
                        WorkflowEvent.ContainerContentUpdated(
                            hasRemainingCargo = false,
                            observedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                enabled = enabled,
            ) {
                Text(language.pick("Container Empty", "货柜已空"))
            }
        }
    }
}

@Composable
private fun CurrentPalletCard(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    phase: SessionPhase,
    currentPalletSequence: Int?,
    items: List<CargoSummaryRecord>,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                language.pick("Current Pallet", "当前托盘"),
                style = MaterialTheme.typography.titleMedium,
            )
            if (currentPalletSequence == null) {
                Text(
                    text = when (phase) {
                        SessionPhase.READY_TO_COMPLETE -> language.pick(
                            "No active pallet. Container can be closed.",
                            "当前没有活动托盘，可以结束本次货柜作业。",
                        )
                        SessionPhase.CLOSED -> language.pick("Session closed.", "本次作业已关闭。")
                        else -> language.pick(
                            "Waiting for the next pallet to enter the camera zone.",
                            "等待下一个托盘进入相机区域。",
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    language.pick(
                        "Pallet #$currentPalletSequence is active.",
                        "托盘 #$currentPalletSequence 正在装货。",
                    ),
                )
                if (items.isEmpty()) {
                    Text(
                        text = language.pick("No items counted yet.", "还没有计数到货物。"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    items.forEach { item ->
                        SummaryRow(language = language, item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    item: CargoSummaryRecord,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                localizedCargoLabel(language, item.itemLabel),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${item.colorName} | ${item.markerText.ifBlank { language.pick("No OCR tag", "无 OCR 标记") }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(item.quantity.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SealedPalletsCard(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    pallets: List<PalletDetail>,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                language.pick("Archived Pallets", "已归档托盘"),
                style = MaterialTheme.typography.titleMedium,
            )
            if (pallets.isEmpty()) {
                Text(
                    text = language.pick("No sealed pallets yet.", "还没有完成封膜的托盘。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                pallets.take(4).forEach { detail ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = language.pick(
                                    "Pallet #${detail.pallet.sequenceNumber}",
                                    "托盘 #${detail.pallet.sequenceNumber}",
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = detail.pallet.closedAt?.let(::formatTimestamp)
                                    ?: language.pick("Open", "开启中"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        detail.items.forEach { item ->
                            SummaryRow(language = language, item = item)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EventFeedCard(
    uiState: LiveInspectionUiState,
    language: nz.co.mixport.customsvision.data.AppLanguage,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                language.pick("Event Feed", "事件流"),
                style = MaterialTheme.typography.titleMedium,
            )
            if (uiState.recentEvents.isEmpty()) {
                Text(
                    text = language.pick(
                        "Event logs will appear after the first session starts.",
                        "开始第一单作业后，这里会显示事件日志。",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                uiState.recentEvents.forEach { event ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                localizedEventType(language, event.eventType),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(event.message)
                            Text(
                                formatTimestamp(event.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointCard(language: nz.co.mixport.customsvision.data.AppLanguage) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                language.pick("Pilot Sync Profile", "试点同步配置"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = language.pick(
                    "This Android app is prepared to sync through the same Mixport server stack, but it does not embed database credentials.",
                    "这个 Android 应用已按 Mixport 同一套服务器体系预留同步能力，但不会把数据库凭据直接写进客户端。",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = language.pick(
                    "Default API base URL: ${BuildConfig.DEFAULT_API_BASE_URL}",
                    "默认 API 地址：${BuildConfig.DEFAULT_API_BASE_URL}",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    modifier: Modifier = Modifier,
    language: nz.co.mixport.customsvision.data.AppLanguage,
    sessions: List<InspectionSessionRecord>,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = language.pick("Session History", "作业历史"),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        if (sessions.isEmpty()) {
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(language.pick("No sessions recorded yet.", "还没有历史作业记录。"))
                        Text(
                            language.pick(
                                "Start a pilot run on the Live tab to populate local history.",
                                "在识别页开始一次试点作业后，这里会自动生成本地历史。",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(sessions, key = { it.id }) { session ->
                SessionHistoryCard(language = language, session = session)
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    session: InspectionSessionRecord,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = session.containerCode,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(localizedStatus(language, session.status)) },
                )
            }
            Text(
                language.pick(
                    "Operator: ${session.operatorName}",
                    "操作员：${session.operatorName}",
                ),
            )
            Text(
                language.pick(
                    "Vessel / lane: ${session.vesselName.ifBlank { "Not set" }}",
                    "船名 / 航线：${session.vesselName.ifBlank { "未填写" }}",
                ),
            )
            Text(
                language.pick(
                    "Started: ${formatTimestamp(session.startedAt)}",
                    "开始时间：${formatTimestamp(session.startedAt)}",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            session.endedAt?.let {
                Text(
                    language.pick(
                        "Ended: ${formatTimestamp(it)}",
                        "结束时间：${formatTimestamp(it)}",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.recordingUri != null) {
                Text(
                    language.pick(
                        "Video: ${session.recordingUri}",
                        "视频：${session.recordingUri}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageCard(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    infoMessage: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(20.dp),
        color = if (errorMessage != null) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                infoMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            OutlinedButton(onClick = onDismiss) {
                Text(language.pick("Dismiss", "关闭"))
            }
        }
    }
}

private fun localizedDetectionTitle(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    detection: LiveRecognition,
): String {
    return buildString {
        append(localizedCargoLabel(language, detection.label))
        detection.trackingId?.let {
            append(" #")
            append(it)
        }
    }
}

private fun localizedRecognitionTitle(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    recognition: UniversalRecognition,
): String {
    return buildString {
        append(localizedCargoLabel(language, recognition.bestLabel))
        recognition.trackingId?.let {
            append(" #")
            append(it)
        }
    }
}

private fun localizedEventType(
    language: nz.co.mixport.customsvision.data.AppLanguage,
    eventType: String,
): String {
    return when (eventType.uppercase()) {
        "SESSION_STARTED" -> language.pick("Session started", "作业开始")
        "SESSION_FINISHED" -> language.pick("Session finished", "作业结束")
        "PALLET_DETECTED" -> language.pick("Pallet detected", "识别到托盘")
        "PALLET_IMPLICITLY_OPENED" -> language.pick("Pallet opened", "托盘已开启")
        "CARGO_COUNTED" -> language.pick("Cargo counted", "货物已计数")
        "PALLET_WRAPPED" -> language.pick("Pallet wrapped", "托盘已封膜")
        "CONTAINER_STATUS" -> language.pick("Container status", "货柜状态")
        else -> eventType
    }
}

internal fun formatTimestamp(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

private fun formatConfidence(confidence: Float?): String {
    return if (confidence == null) {
        "Warm-up"
    } else {
        "${(confidence * 100).roundToInt()}%"
    }
}

private fun formatPercent(value: Float): String {
    return "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%"
}

