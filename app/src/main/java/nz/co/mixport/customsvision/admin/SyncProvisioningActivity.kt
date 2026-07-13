package nz.co.mixport.customsvision.admin

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.co.mixport.customsvision.CustomsApplication
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.data.AppPreferencesRepository
import nz.co.mixport.customsvision.data.SyncProvisioningApplyResult
import nz.co.mixport.customsvision.data.SyncProvisioningContract
import nz.co.mixport.customsvision.data.SyncProvisioningManager
import nz.co.mixport.customsvision.data.SyncProvisioningValidator
import nz.co.mixport.customsvision.data.ValidatedSyncProvisioning
import nz.co.mixport.customsvision.ui.pick
import nz.co.mixport.customsvision.ui.theme.MixportCustomsTheme

class SyncProvisioningActivity : ComponentActivity() {
    private lateinit var language: AppLanguage
    private lateinit var validator: SyncProvisioningValidator
    private lateinit var provisioningManager: SyncProvisioningManager
    private var uiState by mutableStateOf<ProvisioningUiState>(ProvisioningUiState.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        language = AppPreferencesRepository(this).getLanguage()
        validator = SyncProvisioningValidator(
            allowedHostSuffixes = resources
                .getStringArray(nz.co.mixport.customsvision.R.array.sync_provisioning_allowed_host_suffixes)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(String::lowercase)
                .toSet(),
            allowDebugLocalHosts = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        provisioningManager = SyncProvisioningManager(this)
        uiState = resolveUiState(intent)

        setContent {
            MixportCustomsTheme {
                SyncProvisioningScreen(
                    language = language,
                    state = uiState,
                    onApply = ::applyProvisioning,
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        uiState = resolveUiState(intent)
    }

    private fun resolveUiState(intent: Intent?): ProvisioningUiState {
        val payload = SyncProvisioningContract.parse(intent)
            ?: return ProvisioningUiState.Failure(
                language.pick(
                    "Missing or invalid sync provisioning request.",
                    "缺少或无效的同步配置请求。",
                ),
            )
        val validated = runCatching { validator.validate(payload) }
            .getOrElse { throwable ->
                return ProvisioningUiState.Failure(
                    throwable.message ?: language.pick(
                        "Sync provisioning validation failed.",
                        "同步配置校验失败。",
                    ),
                )
            }
        return ProvisioningUiState.Review(validated)
    }

    private fun applyProvisioning(validated: ValidatedSyncProvisioning) {
        if (uiState is ProvisioningUiState.Applying) {
            return
        }
        uiState = ProvisioningUiState.Applying(validated)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    provisioningManager.applyProvisioning(validated)
                }
            }.onSuccess { result ->
                (application as CustomsApplication).invalidateBootstrapPayload()
                setResult(Activity.RESULT_OK)
                uiState = ProvisioningUiState.Success(result)
            }.onFailure { throwable ->
                setResult(Activity.RESULT_CANCELED)
                uiState = ProvisioningUiState.Failure(
                    throwable.message ?: language.pick(
                        "Unable to apply sync provisioning.",
                        "无法应用同步配置。",
                    ),
                )
            }
        }
    }
}

private sealed interface ProvisioningUiState {
    data object Loading : ProvisioningUiState

    data class Review(
        val validated: ValidatedSyncProvisioning,
    ) : ProvisioningUiState

    data class Applying(
        val validated: ValidatedSyncProvisioning,
    ) : ProvisioningUiState

    data class Success(
        val result: SyncProvisioningApplyResult,
    ) : ProvisioningUiState

    data class Failure(
        val message: String,
    ) : ProvisioningUiState
}

@Composable
private fun SyncProvisioningScreen(
    language: AppLanguage,
    state: ProvisioningUiState,
    onApply: (ValidatedSyncProvisioning) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 4.dp,
        ) {
            when (state) {
                ProvisioningUiState.Loading -> ProvisioningLoading(language)
                is ProvisioningUiState.Review -> ProvisioningReview(
                    language = language,
                    validated = state.validated,
                    isApplying = false,
                    onApply = onApply,
                    onClose = onClose,
                )

                is ProvisioningUiState.Applying -> ProvisioningReview(
                    language = language,
                    validated = state.validated,
                    isApplying = true,
                    onApply = onApply,
                    onClose = onClose,
                )

                is ProvisioningUiState.Success -> ProvisioningSuccess(
                    language = language,
                    result = state.result,
                    onClose = onClose,
                )

                is ProvisioningUiState.Failure -> ProvisioningFailure(
                    language = language,
                    message = state.message,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun ProvisioningLoading(language: AppLanguage) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        CircularProgressIndicator()
        Text(
            text = language.pick(
                "Preparing admin sync provisioning...",
                "正在准备管理员同步配置...",
            ),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ProvisioningReview(
    language: AppLanguage,
    validated: ValidatedSyncProvisioning,
    isApplying: Boolean,
    onApply: (ValidatedSyncProvisioning) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = language.pick(
                "Admin Sync Provisioning",
                "管理员同步配置",
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = language.pick(
                "This admin-only screen will replace the scanner sync endpoint, clear cached reference rows, and supersede any still-pending uploads before the new profile becomes active.",
                "这个仅供管理员使用的页面会替换扫码同步端点，清空本地参考缓存，并在新配置生效前将仍待上传的旧队列切换为审计态。",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProvisioningField(
            label = language.pick("Approved host", "批准主机"),
            value = validated.host,
        )
        ProvisioningField(
            label = language.pick("Normalized API URL", "规范化 API 地址"),
            value = validated.apiBaseUrl,
        )
        ProvisioningField(
            label = language.pick("Target device ID", "目标设备 ID"),
            value = validated.deviceId ?: language.pick("Keep current device ID", "保持当前设备 ID"),
        )
        Text(
            text = language.pick(
                "Token value stays hidden. Only the validated host and device target are shown here.",
                "令牌值保持隐藏。这里仅显示通过校验的主机和设备目标。",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f),
                enabled = !isApplying,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(language.pick("Cancel", "取消"))
            }
            Button(
                onClick = { onApply(validated) },
                modifier = Modifier.weight(1f),
                enabled = !isApplying,
                shape = RoundedCornerShape(999.dp),
            ) {
                if (isApplying) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 2.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(language.pick("Apply profile", "应用配置"))
                }
            }
        }
    }
}

@Composable
private fun ProvisioningSuccess(
    language: AppLanguage,
    result: SyncProvisioningApplyResult,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = language.pick(
                "Provisioning applied",
                "配置已应用",
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = language.pick(
                "The new secure sync profile is now active.",
                "新的安全同步配置现在已经生效。",
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        ProvisioningField(
            label = language.pick("Host", "主机"),
            value = result.host,
        )
        ProvisioningField(
            label = language.pick("Resolved device ID", "最终设备 ID"),
            value = result.deviceId,
        )
        ProvisioningField(
            label = language.pick("Superseded pending uploads", "已切为审计态的待上传记录"),
            value = result.supersededPendingUploadCount.toString(),
        )
        ProvisioningField(
            label = language.pick("Cleared cached references", "已清空的本地参考缓存"),
            value = result.clearedReferenceCount.toString(),
        )
        Button(
            onClick = onClose,
            shape = RoundedCornerShape(999.dp),
        ) {
            Text(language.pick("Close", "关闭"))
        }
    }
}

@Composable
private fun ProvisioningFailure(
    language: AppLanguage,
    message: String,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = language.pick(
                "Provisioning blocked",
                "配置已阻止",
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onClose,
            shape = RoundedCornerShape(999.dp),
        ) {
            Text(language.pick("Close", "关闭"))
        }
    }
}

@Composable
private fun ProvisioningField(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
