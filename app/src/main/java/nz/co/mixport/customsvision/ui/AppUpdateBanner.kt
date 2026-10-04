package nz.co.mixport.customsvision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.update.AppUpdateState

@Composable
internal fun AppUpdateBanner(
    language: AppLanguage,
    state: AppUpdateState,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
    val visibleState = when (state) {
        is AppUpdateState.Available,
        is AppUpdateState.Downloading,
        is AppUpdateState.PermissionRequired,
        is AppUpdateState.Failed,
        -> state

        AppUpdateState.Checking,
        AppUpdateState.Idle,
        -> return
    }
    val isDownloading = visibleState is AppUpdateState.Downloading
    val isFailure = visibleState is AppUpdateState.Failed
    val release = when (visibleState) {
        is AppUpdateState.Available -> visibleState.release
        is AppUpdateState.Downloading -> visibleState.release
        is AppUpdateState.PermissionRequired -> visibleState.release
        is AppUpdateState.Failed -> visibleState.release
        else -> null
    }

    Surface(
        color = if (isFailure) Color(0xFFFFE9E5) else Color(0xFFFFF0E5),
        contentColor = Color(0xFF55210D),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (visibleState) {
                            is AppUpdateState.PermissionRequired -> language.pick(
                                "Allow app updates",
                                "允许应用更新",
                            )

                            is AppUpdateState.Failed -> language.pick(
                                "Update could not be prepared",
                                "更新准备失败",
                            )

                            else -> language.pick(
                                "Version ${release?.versionName.orEmpty()} is available",
                                "发现新版本 ${release?.versionName.orEmpty()}",
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when (visibleState) {
                            is AppUpdateState.Downloading -> language.pick(
                                "Downloading and verifying the signed APK",
                                "正在下载并验证签名 APK",
                            )

                            is AppUpdateState.PermissionRequired -> language.pick(
                                "Enable this source, then return and tap Continue.",
                                "请允许此来源安装，返回后点击继续。",
                            )

                            is AppUpdateState.Failed -> language.pick(
                                "No data was changed. Check the network and retry.",
                                "现有数据未受影响，请检查网络后重试。",
                            )

                            else -> language.pick(
                                "The system will ask for installation confirmation. Local data is retained.",
                                "系统将要求确认安装，本地数据会保留。",
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!isDownloading && release != null) {
                    Button(
                        onClick = if (isFailure) onRetry else onInstall,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9571C)),
                    ) {
                        Text(
                            when (visibleState) {
                                is AppUpdateState.PermissionRequired -> language.pick("Continue", "继续")
                                is AppUpdateState.Failed -> language.pick("Retry", "重试")
                                else -> language.pick("Update", "更新")
                            },
                        )
                    }
                }
            }
            if (visibleState is AppUpdateState.Downloading) {
                val progress = visibleState.percent
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
