package nz.co.mixport.customsvision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import nz.co.mixport.customsvision.BuildConfig
import nz.co.mixport.customsvision.data.AppLanguage
import nz.co.mixport.customsvision.update.AppUpdateCheckResult

@Composable
internal fun AppVersionDialog(
    language: AppLanguage,
    hasCompanyConnection: Boolean,
    isChecking: Boolean,
    checkResult: AppUpdateCheckResult?,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val available = checkResult as? AppUpdateCheckResult.Available
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(language.pick("App version", "应用版本")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text(
                    if (hasCompanyConnection) {
                        language.pick(
                            "Company connection is stored on this device and retained by an in-place update.",
                            "公司连接配置保存在本机，覆盖安装更新后会保留。",
                        )
                    } else {
                        language.pick(
                            "No company connection is configured on this device. The public APK contains no credentials.",
                            "此设备尚未配置公司连接；公开 APK 不包含令牌。",
                        )
                    },
                )
                when {
                    isChecking -> CircularProgressIndicator()
                    available != null -> Text(
                        language.pick(
                            "Version ${available.release.versionName} is available.",
                            "可更新至 ${available.release.versionName}。",
                        ),
                    )
                    checkResult == AppUpdateCheckResult.UpToDate -> Text(
                        language.pick("This is the latest version.", "当前已是最新版本。"),
                    )
                    checkResult == AppUpdateCheckResult.Failed -> Text(
                        language.pick(
                            "Unable to check GitHub. Check the internet connection and try again.",
                            "无法连接 GitHub 检查版本，请检查网络后重试。",
                        ),
                    )
                    checkResult == AppUpdateCheckResult.Skipped -> Text(
                        language.pick("An update is already in progress.", "更新正在进行中。"),
                    )
                }
            }
        },
        confirmButton = {
            if (available != null && !isChecking) {
                Button(onClick = onUpdate) {
                    Text(language.pick("Update", "更新"))
                }
            } else {
                Button(onClick = onCheck, enabled = !isChecking) {
                    Text(language.pick("Check again", "重新检查"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(language.pick("Close", "关闭"))
            }
        },
    )
}
