package nz.co.mixport.customsvision.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProvisioningValidatorTest {
    private val validator = SyncProvisioningValidator(
        allowedHostSuffixes = setOf("mixport.co.nz"),
        allowDebugLocalHosts = false,
    )

    @Test
    fun `accepts https host on approved suffix and normalizes path`() {
        val validated = validator.validate(
            SyncProvisioningPayload(
                apiBaseUrl = "https://api.mixport.co.nz/private-sync",
                bearerToken = "tokenvalue12345",
                deviceId = "hik-001",
            ),
        )

        assertEquals("https://api.mixport.co.nz/private-sync/", validated.apiBaseUrl)
        assertEquals("api.mixport.co.nz", validated.host)
        assertEquals("hik-001", validated.deviceId)
    }

    @Test
    fun `rejects non https url for release policy`() {
        val error = runCatching {
            validator.validate(
                SyncProvisioningPayload(
                    apiBaseUrl = "http://api.mixport.co.nz/private-sync",
                    bearerToken = "tokenvalue12345",
                    deviceId = null,
                ),
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertEquals("Sync API URL must use HTTPS.", error.message)
    }

    @Test
    fun `rejects host outside allowlist`() {
        val error = runCatching {
            validator.validate(
                SyncProvisioningPayload(
                    apiBaseUrl = "https://evil.example.com/private-sync",
                    bearerToken = "tokenvalue12345",
                    deviceId = null,
                ),
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertEquals("Sync API host is not in the approved allowlist.", error.message)
    }

    @Test
    fun `rejects invalid device id characters`() {
        val error = runCatching {
            validator.validate(
                SyncProvisioningPayload(
                    apiBaseUrl = "https://api.mixport.co.nz/private-sync",
                    bearerToken = "tokenvalue12345",
                    deviceId = "hik/001",
                ),
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertEquals("Device ID must use 3-64 letters, digits, dot, dash, or underscore.", error.message)
    }

    @Test
    fun `debug policy allows local http host`() {
        val debugValidator = SyncProvisioningValidator(
            allowedHostSuffixes = emptySet(),
            allowDebugLocalHosts = true,
        )

        val validated = debugValidator.validate(
            SyncProvisioningPayload(
                apiBaseUrl = "http://10.0.2.2:8080/api",
                bearerToken = "tokenvalue12345",
                deviceId = null,
            ),
        )

        assertEquals("http://10.0.2.2:8080/api/", validated.apiBaseUrl)
        assertEquals("10.0.2.2", validated.host)
        assertNull(validated.deviceId)
    }
}
