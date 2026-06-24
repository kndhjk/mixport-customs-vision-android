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
    val cameraController = remember { InspectionCameraController(context) }

    DisposableEffect(Unit) {
        onDispose {
            cameraController.release()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setCameraPermission(granted)
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setCameraPermission(granted)
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                        Text("Mixport Customs Vision")
                        Text(
                            text = "Cargo tracking, pallet counting, and evidence capture",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
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
                    label = { Text("Live") },
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
                    label = { Text("History") },
                )
            }
        },
    ) { padding ->
        when (uiState.selectedDestination) {
            AppDestination.LIVE -> LiveScreen(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                cameraController = cameraController,
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
            )

            AppDestination.HISTORY -> HistoryScreen(
                modifier = Modifier.padding(padding),
                sessions = uiState.history,
            )
        }
    }
}

@Composable
private fun LiveScreen(
    modifier: Modifier = Modifier,
    uiState: LiveInspectionUiState,
    cameraController: InspectionCameraController,
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
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            if (uiState.infoMessage != null || uiState.errorMessage != null) {
                MessageCard(
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
            )
        }
        item {
            StatusCard(uiState = uiState)
        }
        item {
            LiveDetectionsCard(
                uiState = uiState,
                onCountVisibleDetections = onCountVisibleDetections,
                onAnalyzeVisibleCargo = {
                    onUniversalRecognitionStarted()
                    cameraController.analyzeVisibleCargo(
                        detections = uiState.liveDetections,
                        onComplete = onUniversalRecognitionCompleted,
                        onError = onUniversalRecognitionError,
                    )
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
                enabled = uiState.activeSession != null,
                onWorkflowEvent = onWorkflowEvent,
            )
        }
        item {
            CurrentPalletCard(
                phase = uiState.workflowState.phase,
                currentPalletSequence = uiState.workflowState.activePalletSequence,
                items = uiState.currentPalletItems,
            )
        }
        item {
            SealedPalletsCard(pallets = uiState.sealedPallets)
        }
        item {
            EventFeedCard(uiState = uiState)
        }
        item {
            EndpointCard()
        }
    }
}

@Composable
private fun PilotHeroCard(uiState: LiveInspectionUiState) {
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
                    text = "Mixport pilot deck",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                )
                Text(
                    text = "Unload cargo with live camera tracking, pallet event capture, and shipment evidence on one screen.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (uiState.cameraPermissionGranted) "Camera ready" else "Camera access needed",
                                )
                            },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    uiState.activeSession?.containerCode ?: "No live session",
                                )
                            },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text("Tracks ${uiState.liveDetections.size}")
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
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Session Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Use the same orange-white workflow language as the Mixport cargo pages: start a lane, keep the camera rolling, and archive pallet evidence at the end.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.draft.containerCode,
                onValueChange = onContainerCodeChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Container Code") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draft.vesselName,
                onValueChange = onVesselNameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Vessel / Lane") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draft.operatorName,
                onValueChange = onOperatorNameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Operator") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draft.notes,
                onValueChange = onNotesChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStartSession,
                    enabled = uiState.activeSession == null,
                ) {
                    Text("Start Session")
                }
                OutlinedButton(
                    onClick = onCloseSession,
                    enabled = uiState.activeSession != null,
                ) {
                    Text("Close Session")
                }
            }
            uiState.activeSession?.let { session ->
                Text(
                    text = "Active session: ${session.containerCode} | ${session.operatorName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: LiveInspectionUiState) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Workflow Status", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    AssistChip(
                        onClick = {},
                        label = { Text("Phase ${uiState.workflowState.phase.name}") },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (uiState.workflowState.containerHasRemainingCargo) {
                                    "Cargo still in container"
                                } else {
                                    "Container looks empty"
                                },
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = { Text("Tracks ${uiState.liveDetections.size}") },
                    )
                }
            }
            val heartbeatText = uiState.lastFrameHeartbeatAt?.let {
                "Vision heartbeat: ${formatTimestamp(it)}"
            } ?: "Vision heartbeat: waiting"
            Text(text = heartbeatText, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Green boxes come from live ML Kit object tracking. The pallet wrap step is still manual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uiState.recordingUri?.let {
                Text(
                    text = "Last video: $it",
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
private fun CameraCard(
    uiState: LiveInspectionUiState,
    cameraController: InspectionCameraController,
    onHeartbeat: (Long) -> Unit,
    onDetectionFrame: (LiveDetectionFrame) -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingSaved: (String) -> Unit,
    onRecordingError: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeSession = uiState.activeSession
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(previewView, lifecycleOwner, uiState.cameraPermissionGranted) {
        val boundPreviewView = previewView
        if (uiState.cameraPermissionGranted && boundPreviewView != null) {
            boundPreviewView.post {
                cameraController.bind(
                    previewView = boundPreviewView,
                    lifecycleOwner = lifecycleOwner,
                    onFrameHeartbeat = onHeartbeat,
                    onDetections = onDetectionFrame,
                    onError = onRecordingError,
                )
            }
        }
        onDispose {}
    }

    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Live Camera", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Primary live zone for the customs pilot. This panel should open first and mirror the orange brand language from the Mixport cargo portal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!uiState.cameraPermissionGranted) {
                Text(
                    text = "Camera permission is required to preview and track cargo.",
                    color = MaterialTheme.colorScheme.error,
                )
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
                    LiveDetectionOverlay(detections = uiState.liveDetections)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        color = if (uiState.lastFrameHeartbeatAt == null) OverlayScrim else CountedGreen.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = if (uiState.lastFrameHeartbeatAt == null) {
                                "Connecting camera..."
                            } else {
                                "Live feed active"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (uiState.liveDetections.isEmpty()) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp),
                            color = OverlayScrim,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = if (uiState.lastFrameHeartbeatAt == null) {
                                    "Point the camera at the cargo area"
                                } else {
                                    "No tracked object yet"
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (uiState.isRecording) {
                            cameraController.stopRecording()
                        } else if (activeSession != null) {
                            val stamp = activeSession.containerCode.replace(" ", "_")
                            cameraController.startRecording(
                                displayName = "mixport-customs-$stamp-${System.currentTimeMillis()}",
                                onSaved = onRecordingSaved,
                                onError = onRecordingError,
                            )
                            onRecordingStarted()
                        } else {
                            onRecordingError("Start a session before recording.")
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
                    Text(if (uiState.isRecording) "Stop Recording" else "Start Recording")
                }
                FilledTonalButton(
                    onClick = {},
                    enabled = activeSession != null,
                ) {
                    Text(if (activeSession == null) "No session" else "Tracking live")
                }
            }
        }
    }
}

@Composable
private fun LiveDetectionOverlay(detections: List<LiveRecognition>) {
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
                    text = detection.overlayTitle,
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
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Live Detections", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "This section reflects the live green-box tracker. The pallet rule is now tuned toward low, wide wooden shipping pallets like your reference photo, then the auxiliary recognizer adds OCR, labels, and color hints before counting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Button(
                        onClick = onCountVisibleDetections,
                        enabled = uiState.activeSession != null &&
                            uiState.liveDetections.any { !it.isPalletCandidate && !it.isCounted },
                    ) {
                        Text("Count Visible Cargo")
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
                                "Analyzing..."
                            } else {
                                "Analyze Visible Cargo"
                            },
                        )
                    }
                }
                item {
                    AssistChip(
                        onClick = {},
                        label = { Text("${uiState.liveDetections.size} tracked") },
                    )
                }
            }
            if (uiState.isUniversalRecognitionRunning) {
                Text(
                    text = "Capturing the current frame and extracting OCR, labels, and color hints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.liveDetections.isEmpty()) {
                Text(
                    text = "Aim the camera at a pallet or cargo. The first launch can take a moment while the detector model warms up.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                uiState.liveDetections.take(6).forEach { detection ->
                    DetectionRow(detection = detection)
                }
            }
            uiState.universalRecognitionSnapshot?.let { snapshot ->
                HorizontalDivider()
                Text("Visible Cargo Insights", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Snapshot ${formatTimestamp(snapshot.analyzedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.items.isEmpty()) {
                    Text(
                        text = "No auxiliary recognition results are available for the current frame.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    snapshot.items.forEach { recognition ->
                        RecognitionRow(recognition = recognition)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectionRow(detection: LiveRecognition) {
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
                    text = detection.overlayTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                detection.isPalletCandidate -> "Pallet"
                                detection.isCounted -> "Counted"
                                else -> "Visible"
                            },
                        )
                    },
                )
            }
            Text(
                text = "Category: ${detection.category}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Confidence: ${formatConfidence(detection.confidence)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecognitionRow(recognition: UniversalRecognition) {
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
                    text = recognition.displayTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                recognition.isPalletLike -> "Pallet base"
                                recognition.isCounted -> "Counted"
                                else -> "Ready"
                            },
                        )
                    },
                )
            }
            if (recognition.sourceLabel != recognition.bestLabel) {
                Text(
                    text = "Tracked as: ${recognition.sourceLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Color: ${recognition.dominantColor}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recognition.isPalletLike) {
                Text(
                    text = "This object matches the wooden pallet profile and will be excluded from cargo counting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "OCR / marker: ${recognition.markerText.ifBlank { "None detected" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Label hints: ${recognition.labelHints.ifEmpty { listOf("No hints") }.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Confidence: ${formatConfidence(recognition.confidence)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManualControlsCard(
    enabled: Boolean,
    onWorkflowEvent: (WorkflowEvent) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Manual Controls", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Keep these controls as fallback while pallet wrap detection and custom cargo classification are still being trained.",
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
                    Text("Open Pallet")
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
                    Text("Container Has Cargo")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        onWorkflowEvent(
                            WorkflowEvent.CargoPlaced(
                                itemLabel = "Manual cargo",
                                colorName = "Unclassified",
                                markerText = "Manual",
                                observedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    enabled = enabled,
                ) {
                    Text("Add Manual Cargo")
                }
                Button(
                    onClick = {
                        onWorkflowEvent(WorkflowEvent.PalletWrapped(System.currentTimeMillis()))
                    },
                    enabled = enabled,
                ) {
                    Text("Wrap Pallet")
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
                Text("Container Empty")
            }
        }
    }
}

@Composable
private fun CurrentPalletCard(
    phase: SessionPhase,
    currentPalletSequence: Int?,
    items: List<CargoSummaryRecord>,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Current Pallet", style = MaterialTheme.typography.titleMedium)
            if (currentPalletSequence == null) {
                Text(
                    text = when (phase) {
                        SessionPhase.READY_TO_COMPLETE -> "No active pallet. Container can be closed."
                        SessionPhase.CLOSED -> "Session closed."
                        else -> "Waiting for the next pallet to enter the camera zone."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Pallet #$currentPalletSequence is active.")
                if (items.isEmpty()) {
                    Text(
                        text = "No items counted yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    items.forEach { item ->
                        SummaryRow(item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(item: CargoSummaryRecord) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(item.itemLabel, fontWeight = FontWeight.SemiBold)
            Text(
                "${item.colorName} | ${item.markerText.ifBlank { "No OCR tag" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(item.quantity.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SealedPalletsCard(pallets: List<PalletDetail>) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Archived Pallets", style = MaterialTheme.typography.titleMedium)
            if (pallets.isEmpty()) {
                Text(
                    text = "No sealed pallets yet.",
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
                                text = "Pallet #${detail.pallet.sequenceNumber}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = detail.pallet.closedAt?.let(::formatTimestamp) ?: "Open",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        detail.items.forEach { item ->
                            SummaryRow(item = item)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EventFeedCard(uiState: LiveInspectionUiState) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Event Feed", style = MaterialTheme.typography.titleMedium)
            if (uiState.recentEvents.isEmpty()) {
                Text(
                    text = "Event logs will appear after the first session starts.",
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
                            Text(event.eventType, fontWeight = FontWeight.SemiBold)
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
private fun EndpointCard() {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pilot Sync Profile", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "This Android app is prepared to sync through the same Mixport server stack, but it does not embed database credentials.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Default API base URL: ${BuildConfig.DEFAULT_API_BASE_URL}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    modifier: Modifier = Modifier,
    sessions: List<InspectionSessionRecord>,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Session History",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        if (sessions.isEmpty()) {
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No sessions recorded yet.")
                        Text(
                            "Start a pilot run on the Live tab to populate local history.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(sessions, key = { it.id }) { session ->
                SessionHistoryCard(session = session)
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(session: InspectionSessionRecord) {
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
                AssistChip(onClick = {}, label = { Text(session.status) })
            }
            Text("Operator: ${session.operatorName}")
            Text("Vessel / lane: ${session.vesselName.ifBlank { "Not set" }}")
            Text(
                "Started: ${formatTimestamp(session.startedAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            session.endedAt?.let {
                Text(
                    "Ended: ${formatTimestamp(it)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.recordingUri != null) {
                Text(
                    "Video: ${session.recordingUri}",
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
                Text("Dismiss")
            }
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String {
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

