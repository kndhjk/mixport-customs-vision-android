package nz.co.mixport.customsvision.data

import android.content.Context

data class SyncProvisioningApplyResult(
    val host: String,
    val deviceId: String,
    val supersededPendingUploadCount: Int,
    val clearedReferenceCount: Int,
)

class SyncProvisioningManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferencesRepository = AppPreferencesRepository(appContext)
    private val databaseHelper = CustomsDatabaseHelper(appContext)

    fun applyProvisioning(
        provisioning: ValidatedSyncProvisioning,
        provisionedAt: Long = System.currentTimeMillis(),
    ): SyncProvisioningApplyResult {
        val supersededPendingUploadCount = databaseHelper.supersedePendingScannerUploads(
            reason = "superseded_by_reprovision",
            supersededAt = provisionedAt,
        )
        val clearedReferenceCount = databaseHelper.clearServerBarcodeReferences()
        preferencesRepository.saveScannerSyncProvisioning(
            apiBaseUrl = provisioning.apiBaseUrl,
            bearerToken = provisioning.bearerToken,
            deviceId = provisioning.deviceId,
        )
        preferencesRepository.resetScannerSyncRuntimeState(clearHistory = true)
        val resolvedDeviceId = preferencesRepository.getScannerSyncSettings().deviceId
        preferencesRepository.recordScannerSyncProvisioningAudit(
            host = provisioning.host,
            deviceId = resolvedDeviceId,
            provisionedAt = provisionedAt,
        )
        return SyncProvisioningApplyResult(
            host = provisioning.host,
            deviceId = resolvedDeviceId,
            supersededPendingUploadCount = supersededPendingUploadCount,
            clearedReferenceCount = clearedReferenceCount,
        )
    }
}
