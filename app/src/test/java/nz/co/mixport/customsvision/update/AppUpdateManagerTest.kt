package nz.co.mixport.customsvision.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    private val validManifest = UpdateManifest(
        schemaVersion = 1,
        packageName = "nz.co.mixport.customsvision",
        versionCode = 20,
        versionName = "0.8.1",
        apkDownloadUrl = "https://github.com/kndhjk/mixport-customs-vision-android/releases/download/v0.8.1/app.apk",
        sha256 = "a".repeat(64),
        signerSha256 = "b".repeat(64),
    )

    @Test
    fun `newer approved release is accepted`() {
        val release = validateUpdateManifest(
            manifest = validManifest,
            installedPackageName = "nz.co.mixport.customsvision",
            installedVersionCode = 19,
        )

        assertEquals(20L, release?.versionCode)
        assertEquals("0.8.1", release?.versionName)
    }

    @Test
    fun `same or older release is ignored`() {
        assertNull(
            validateUpdateManifest(
                manifest = validManifest.copy(versionCode = 19),
                installedPackageName = "nz.co.mixport.customsvision",
                installedVersionCode = 19,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong package is rejected`() {
        validateUpdateManifest(
            manifest = validManifest.copy(packageName = "example.attacker"),
            installedPackageName = "nz.co.mixport.customsvision",
            installedVersionCode = 19,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unapproved download host is rejected`() {
        validateUpdateManifest(
            manifest = validManifest.copy(apkDownloadUrl = "https://example.com/update.apk"),
            installedPackageName = "nz.co.mixport.customsvision",
            installedVersionCode = 19,
        )
    }

    @Test
    fun `only trusted https update hosts are allowed`() {
        assertTrue(isAllowedUpdateUrl("https://api.github.com/repos/a/b/releases/latest"))
        assertTrue(isAllowedUpdateUrl("https://release-assets.githubusercontent.com/file.apk"))
        assertFalse(isAllowedUpdateUrl("http://github.com/file.apk"))
        assertFalse(isAllowedUpdateUrl("https://github.com.attacker.example/file.apk"))
        assertFalse(isAllowedUpdateUrl("https://user@github.com/file.apk"))
    }
}
