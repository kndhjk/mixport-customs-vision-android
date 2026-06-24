package nz.co.mixport.customsvision.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.ScannerMatchStatus
import nz.co.mixport.customsvision.data.ScannerRecord

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
    onScannerAutoVerifyChanged: (Boolean) -> Unit,
    onScannerSoundChanged: (Boolean) -> Unit,
    onScannerHistoryCleared: () -> Unit,
    onScannerOnboardingDismissed: () -> Unit,
) {
    val language = uiState.appLanguage
    val scanner = uiState.scanner
    val focusRequester = remember { FocusRequester() }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85) }
    var lastPlayedScanAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(toneGenerator) {
        onDispose {
            toneGenerator.release()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!scanner.onboardingDismissed) {
            item {
                ScannerOnboardingCard(
                    language = language,
                    onDismiss = onScannerOnboardingDismissed,
                )
            }
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
private fun ScannerOnboardingCard(
    language: AppLanguage,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        shadowElevation = 4.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(ScannerBrandStart, ScannerBrandEnd),
                    ),
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = language.pick("Quick Start Guide", "快速入门"),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    FilledTonalButton(onClick = onDismiss) {
                        Text(language.pick("Got it!", "知道了"))
                    }
                }
                val steps = listOf(
                    language.pick(
                        "Point the scanner gun at any barcode. Auto-verify starts after the input settles.",
                        "扫描枪对准条码后，输入稳定会自动开始验证。",
                    ),
                    language.pick(
                        "The app checks local session and pallet records immediately.",
                        "应用会立即检查本地柜号和托盘记录。",
                    ),
                    language.pick(
                        "Every scan is kept in history below, with matched, not-found, and error counts.",
                        "每次扫描都会写入下方历史，并统计匹配、未找到和错误次数。",
                    ),
                )
                steps.forEachIndexed { index, description ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = (index + 1).toString(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = description,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
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
    focusRequester: FocusRequester,
    onInputChanged: (String) -> Unit,
    onVerify: (String) -> Unit,
    onAutoVerifyChanged: (Boolean) -> Unit,
    onSoundChanged: (Boolean) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(ScannerBrandStart, ScannerBrandEnd),
                        ),
                    )
                    .padding(20.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = language.pick("Barcode Scanner", "条码扫描"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = language.pick("Scan or enter barcode", "扫描或输入条码"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                scanner.isProcessing -> ScannerInfo
                                scanner.lastResult == ScannerMatchStatus.MATCHED -> ScannerOk
                                scanner.lastResult == ScannerMatchStatus.MISMATCH -> ScannerWarn
                                scanner.lastResult == ScannerMatchStatus.ERROR -> ScannerErr
                                else -> ScannerOk
                            },
                        ),
                )
                Column {
                    Text(
                        text = when {
                            scanner.isProcessing -> language.pick("Verifying", "正在验证")
                            scanner.lastResult == ScannerMatchStatus.MATCHED -> language.pick("Matched", "已匹配")
                            scanner.lastResult == ScannerMatchStatus.MISMATCH -> language.pick("Not found", "未找到")
                            scanner.lastResult == ScannerMatchStatus.ERROR -> language.pick("Verification error", "验证错误")
                            else -> language.pick("Ready to scan", "准备扫描")
                        },
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
                    val sanitized = it.replace("\n", "").replace("\r", "")
                    onInputChanged(sanitized)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text(language.pick("Scanner input", "扫码输入")) },
                placeholder = { Text(language.pick("Scan barcode here...", "扫描枪自动输入或手动填写条码...")) },
                singleLine = true,
                enabled = !scanner.isProcessing,
                supportingText = {
                    Text(
                        language.pick(
                            "The field stays focused so most scanner guns can type straight into it.",
                            "输入框会保持可聚焦，大多数扫码枪可直接回车录入。",
                        ),
                    )
                },
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
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Text(
                        text = if (scanner.isProcessing) {
                            language.pick("Verifying...", "验证中...")
                        } else {
                            language.pick("Verify", "验证")
                        },
                    )
                }
                FilledTonalButton(
                    onClick = { focusRequester.requestFocus() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Text(language.pick("Focus scanner", "聚焦扫码框"))
                }
            }

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
                        text = language.pick("Auto-verify", "自动验证"),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (scanner.isAutoVerifyEnabled) {
                            language.pick("On after 180ms of stable scanner input.", "扫码输入稳定 180ms 后自动验证。")
                        } else {
                            language.pick("Manual verify only.", "仅手动点击验证。")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = scanner.isAutoVerifyEnabled,
                    onCheckedChange = onAutoVerifyChanged,
                )
            }

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
                        text = language.pick("Sound effects", "声音提示"),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = language.pick(
                            "Play different tones for matched, not-found, and error results.",
                            "匹配、未找到和错误结果播放不同提示音。",
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
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = language.pick("Scan History", "扫描记录"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                OutlinedButton(
                    onClick = onClearHistory,
                    enabled = scanner.history.isNotEmpty(),
                ) {
                    Text(language.pick("Clear History", "清除记录"))
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
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = language.pick("No scan records yet", "暂无扫描记录"),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = language.pick(
                                "Start scanning to see verification history.",
                                "开始扫码后，这里会显示验证历史。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                scanner.history.forEach { record ->
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

