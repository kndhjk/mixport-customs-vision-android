package nz.co.mixport.customsvision.data

import android.content.Intent

data class SyncProvisioningPayload(
    val apiBaseUrl: String,
    val bearerToken: String,
    val deviceId: String?,
)

object SyncProvisioningContract {
    const val ACTION_APPLY_PROVISIONING = "nz.co.mixport.customsvision.action.APPLY_SYNC_PROVISIONING"
    const val EXTRA_API_BASE_URL = "nz.co.mixport.customsvision.extra.SYNC_API_BASE_URL"
    const val EXTRA_BEARER_TOKEN = "nz.co.mixport.customsvision.extra.SYNC_BEARER_TOKEN"
    const val EXTRA_DEVICE_ID = "nz.co.mixport.customsvision.extra.SYNC_DEVICE_ID"

    fun parse(intent: Intent?): SyncProvisioningPayload? {
        if (intent == null) {
            return null
        }
        if (intent.action != ACTION_APPLY_PROVISIONING) {
            return null
        }
        val apiBaseUrl = intent.getStringExtra(EXTRA_API_BASE_URL)?.trim().orEmpty()
        val bearerToken = intent.getStringExtra(EXTRA_BEARER_TOKEN)?.trim().orEmpty()
        if (apiBaseUrl.isBlank() || bearerToken.isBlank()) {
            return null
        }
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return SyncProvisioningPayload(
            apiBaseUrl = apiBaseUrl,
            bearerToken = bearerToken,
            deviceId = deviceId,
        )
    }
}
