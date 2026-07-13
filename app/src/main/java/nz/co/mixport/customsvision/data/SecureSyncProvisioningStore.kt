package nz.co.mixport.customsvision.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSyncProvisioningStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSyncSettings(deviceId: String): ScannerSyncSettings {
        val encryptedPayload = preferences.getString(KEY_PAYLOAD, null).orEmpty()
        val iv = preferences.getString(KEY_IV, null).orEmpty()
        if (encryptedPayload.isBlank() || iv.isBlank()) {
            return ScannerSyncSettings(deviceId = deviceId)
        }

        return runCatching {
            val decrypted = decryptPayload(
                encryptedPayload = encryptedPayload,
                encodedIv = iv,
            )
            val json = JSONObject(decrypted)
            ScannerSyncSettings(
                apiBaseUrl = json.optString("apiBaseUrl").trim(),
                bearerToken = json.optString("bearerToken").trim(),
                deviceId = deviceId,
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to load sync provisioning. Clearing stored profile.", throwable)
            clearProvisioning()
        }.getOrElse {
            ScannerSyncSettings(deviceId = deviceId)
        }
    }

    fun saveProvisioning(
        apiBaseUrl: String,
        bearerToken: String,
    ) {
        val payload = JSONObject().apply {
            put("apiBaseUrl", apiBaseUrl.trim())
            put("bearerToken", bearerToken.trim())
            put("provisionedAt", System.currentTimeMillis())
        }.toString()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(payload.toByteArray(StandardCharsets.UTF_8))

        preferences.edit()
            .putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun clearProvisioning() {
        preferences.edit()
            .remove(KEY_PAYLOAD)
            .remove(KEY_IV)
            .apply()
    }

    private fun decryptPayload(
        encryptedPayload: String,
        encodedIv: String,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(encodedIv, Base64.DEFAULT)
        val encrypted = Base64.decode(encryptedPayload, Base64.DEFAULT)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, StandardCharsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val TAG = "SyncProvisioningStore"
        private const val PREFS_NAME = "mixport_customs_sync_secure"
        private const val KEY_ALIAS = "mixport_customs_sync_profile"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_IV = "iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
