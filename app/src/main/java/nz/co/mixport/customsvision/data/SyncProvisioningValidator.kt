package nz.co.mixport.customsvision.data

import java.net.URI
import java.util.Locale

data class ValidatedSyncProvisioning(
    val apiBaseUrl: String,
    val bearerToken: String,
    val deviceId: String?,
    val host: String,
)

class SyncProvisioningValidator(
    private val allowedHostSuffixes: Set<String>,
    private val allowDebugLocalHosts: Boolean,
) {
    fun validate(payload: SyncProvisioningPayload): ValidatedSyncProvisioning {
        val uri = runCatching { URI(payload.apiBaseUrl.trim()) }.getOrElse {
            throw IllegalArgumentException("Sync API URL is not a valid URI.")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
            ?: throw IllegalArgumentException("Sync API URL must include a scheme.")
        val host = uri.host?.lowercase(Locale.US)
            ?: throw IllegalArgumentException("Sync API URL must include a host name.")
        if (uri.userInfo != null) {
            throw IllegalArgumentException("Sync API URL must not embed credentials.")
        }
        if (uri.query != null || uri.fragment != null) {
            throw IllegalArgumentException("Sync API URL must not include query or fragment values.")
        }
        if (!isAllowedSchemeAndHost(scheme, host)) {
            throw IllegalArgumentException(
                if (scheme != "https") {
                    "Sync API URL must use HTTPS."
                } else {
                    "Sync API host is not in the approved allowlist."
                },
            )
        }

        val bearerToken = payload.bearerToken.trim()
        if (bearerToken.length < MIN_BEARER_TOKEN_LENGTH || bearerToken.any(Char::isWhitespace)) {
            throw IllegalArgumentException("Bearer token format is invalid.")
        }

        val normalizedDeviceId = payload.deviceId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.also {
                require(DEVICE_ID_PATTERN.matches(it)) {
                    "Device ID must use 3-64 letters, digits, dot, dash, or underscore."
                }
            }

        val normalizedPath = uri.rawPath
            ?.takeIf(String::isNotBlank)
            ?.let { path -> if (path.endsWith("/")) path else "$path/" }
            ?: "/"
        val normalizedUrl = URI(
            scheme,
            null,
            host,
            uri.port,
            normalizedPath,
            null,
            null,
        ).toASCIIString()

        return ValidatedSyncProvisioning(
            apiBaseUrl = normalizedUrl,
            bearerToken = bearerToken,
            deviceId = normalizedDeviceId,
            host = host,
        )
    }

    private fun isAllowedSchemeAndHost(
        scheme: String,
        host: String,
    ): Boolean {
        if (scheme == "https" && isApprovedHost(host)) {
            return true
        }
        return allowDebugLocalHosts &&
            scheme == "http" &&
            host in DEBUG_LOCAL_HOSTS
    }

    private fun isApprovedHost(host: String): Boolean {
        return allowedHostSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    companion object {
        private const val MIN_BEARER_TOKEN_LENGTH = 12
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._-]{3,64}")
        private val DEBUG_LOCAL_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")
    }
}
