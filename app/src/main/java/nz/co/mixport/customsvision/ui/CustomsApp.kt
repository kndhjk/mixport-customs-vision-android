package nz.co.mixport.customsvision.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
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
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.co.mixport.customsvision.BuildConfig
import nz.co.mixport.customsvision.camera.InspectionCameraController
import nz.co.mixport.customsvision.data.CargoSummaryRecord
import nz.co.mixport.customsvision.data.InspectionSessionRecord
import nz.co.mixport.customsvision.data.PalletDetail
import nz.co.mixport.customsvision.domain.SessionPhase
import nz.co.mixport.customsvision.domain.WorkflowEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mixport Customs Pilot")
                        Text(
                            text = "Android MVP for video capture, pallet counting, and audit logs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = uiState.selectedDestination == AppDestination.LIVE,
                    onClick = { viewModel.selectDestination(AppDestination.LIVE) },
                    icon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                    label = { Text("Live") },
                )
                NavigationBarItem(
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
                onHeartbeat = viewModel::onFrameHeartbeat,
                onRecordingStarted = viewModel::onRecordingStarted,
                onRecordingSaved = viewModel::onRecordingSaved,
                onRecordingError = viewModel::onRecordingError,
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
    onHeartbeat: (Long) -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingSaved: (String) -> Unit,
    onRecordingError: (String) -> Unit,
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
            StatusCard(uiState = uiState)
        }
        item {
            CameraCard(
                uiState = uiState,
                cameraController = cameraController,
                onHeartbeat = onHeartbeat,
                onRecordingStarted = onRecordingStarted,
                onRecordingSaved = onRecordingSaved,
                onRecordingError = onRecordingError,
            )
        }
        item {
            DemoControlsCard(
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Session Setup", style = MaterialTheme.typography.titleMedium)
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
                    text = "Active session: ${session.containerCode} • ${session.operatorName}",
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Workflow Status", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("Phase: ${uiState.workflowState.phase.name}") },
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (uiState.workflowState.containerHasRemainingCargo) {
                                "Container not empty"
                            } else {
                                "Container empty"
                            },
                        )
                    },
                )
            }
            val heartbeatText = uiState.lastFrameHeartbeatAt?.let {
                "Video heartbeat: ${formatTimestamp(it)}"
            } ?: "Video heartbeat: waiting"
            Text(
                text = heartbeatText,
                style = MaterialTheme.typography.bodyMedium,
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
    onRecordingStarted: () -> Unit,
    onRecordingSaved: (String) -> Unit,
    onRecordingError: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeSession = uiState.activeSession

    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Live Camera", style = MaterialTheme.typography.titleMedium)
            if (!uiState.cameraPermissionGranted) {
                Text(
                    text = "Camera permission is required to preview and record the unloading process.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    factory = { context ->
                        PreviewView(context).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            cameraController.bind(
                                previewView = this,
                                lifecycleOwner = lifecycleOwner,
                                onFrameHeartbeat = onHeartbeat,
                                onError = onRecordingError,
                            )
                        }
                    },
                    update = { previewView ->
                        cameraController.bind(
                            previewView = previewView,
                            lifecycleOwner = lifecycleOwner,
                            onFrameHeartbeat = onHeartbeat,
                            onError = onRecordingError,
                        )
                    },
                )
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
                    Text(if (activeSession == null) "No session" else "Session live")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoControlsCard(
    enabled: Boolean,
    onWorkflowEvent: (WorkflowEvent) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pilot Demo Controls", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "These buttons simulate the vision pipeline on an emulator. Replace them later with on-device object detection and OCR outputs.",
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
                    Text("Detect Pallet")
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
                                itemLabel = "Wooden pallet carton",
                                colorName = "Amber",
                                markerText = "MPI",
                                observedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    enabled = enabled,
                ) {
                    Text("Add Amber MPI")
                }
                FilledTonalButton(
                    onClick = {
                        onWorkflowEvent(
                            WorkflowEvent.CargoPlaced(
                                itemLabel = "Wrapped export box",
                                colorName = "Blue",
                                markerText = "NZCS",
                                observedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    enabled = enabled,
                ) {
                    Text("Add Blue NZCS")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onWorkflowEvent(WorkflowEvent.PalletWrapped(System.currentTimeMillis()))
                    },
                    enabled = enabled,
                ) {
                    Text("Wrap Pallet")
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
                "${item.colorName} • ${item.markerText.ifBlank { "No OCR tag" }}",
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
                text = "This Android app is prepared to sync through the same Mixport server stack, but it intentionally does not embed database credentials.",
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

