package nz.co.mixport.customsvision.data

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import nz.co.mixport.customsvision.scanner.PdaScanWorkflowMode

class AppPreferencesRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureSyncStore = SecureSyncProvisioningStore(appContext)

    fun getLanguage(): AppLanguage {
        return AppLanguage.fromCode(preferences.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code))
    }

    fun setLanguage(language: AppLanguage) {
        preferences.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    fun isScannerAutoVerifyEnabled(): Boolean {
        return preferences.getBoolean(KEY_SCANNER_AUTO_VERIFY, true)
    }

    fun setScannerAutoVerifyEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SCANNER_AUTO_VERIFY, enabled).apply()
    }

    fun isScannerSoundEnabled(): Boolean {
        return preferences.getBoolean(KEY_SCANNER_SOUND, true)
    }

    fun setScannerSoundEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SCANNER_SOUND, enabled).apply()
    }

    fun getScannerWorkflowMode(): PdaScanWorkflowMode {
        return PdaScanWorkflowMode.fromPreference(
            preferences.getString(KEY_SCANNER_WORKFLOW_MODE, null),
        )
    }

    fun setScannerWorkflowMode(mode: PdaScanWorkflowMode) {
        preferences.edit().putString(KEY_SCANNER_WORKFLOW_MODE, mode.preferenceValue).apply()
    }

    fun isScannerOnboardingDismissed(): Boolean {
        return preferences.getBoolean(KEY_SCANNER_ONBOARDING_DISMISSED, false)
    }

    fun setScannerOnboardingDismissed(dismissed: Boolean) {
        preferences.edit().putBoolean(KEY_SCANNER_ONBOARDING_DISMISSED, dismissed).apply()
    }

    fun getScannerHistory(): List<ScannerRecord> {
        val raw = preferences.getString(KEY_SCANNER_HISTORY, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(index)
                    add(
                        ScannerRecord(
                            scannedBarcode = item.optString("scannedBarcode"),
                            databaseRecord = item.optString("databaseRecord"),
                            matchStatus = ScannerMatchStatus.valueOf(
                                item.optString("matchStatus", ScannerMatchStatus.WAITING.name),
                            ),
                            status = item.optString("status"),
                            source = item.optString("source"),
                            scannedAt = item.optLong("scannedAt"),
                            localLogId = item.optLong("localLogId").takeIf { it > 0L },
                            customersStatus = item.optString("customersStatus").ifBlank { null },
                            mpiStatus = item.optString("mpiStatus").ifBlank { null },
                            lookupSnapshot = item.optJSONObject("lookupSnapshot")
                                ?.takeIf { snapshot -> snapshot.length() > 0 }
                                ?.toBarcodeLookupResult(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setScannerHistory(history: List<ScannerRecord>) {
        val jsonArray = JSONArray()
        history.forEach { record ->
            jsonArray.put(
                JSONObject().apply {
                    put("scannedBarcode", record.scannedBarcode)
                    put("databaseRecord", record.databaseRecord)
                    put("matchStatus", record.matchStatus.name)
                    put("status", record.status)
                    put("source", record.source)
                    put("scannedAt", record.scannedAt)
                    put("localLogId", record.localLogId)
                    put("customersStatus", record.customersStatus)
                    put("mpiStatus", record.mpiStatus)
                    put("lookupSnapshot", record.lookupSnapshot?.toJsonObject())
                },
            )
        }
        preferences.edit().putString(KEY_SCANNER_HISTORY, jsonArray.toString()).apply()
    }

    fun getScannerSyncSettings(): ScannerSyncSettings {
        val storedDeviceId = preferences.getString(KEY_SCANNER_DEVICE_ID, null).orEmpty().trim()
        val resolvedDeviceId = storedDeviceId.ifBlank {
            val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeLast(8)
                ?.uppercase()
                .orEmpty()
            "hik-${androidId.ifBlank { "PILOT" }}"
        }
        if (storedDeviceId != resolvedDeviceId) {
            preferences.edit().putString(KEY_SCANNER_DEVICE_ID, resolvedDeviceId).apply()
        }
        return secureSyncStore.loadSyncSettings(deviceId = resolvedDeviceId)
    }

    fun setScannerDeviceId(value: String) {
        preferences.edit().putString(KEY_SCANNER_DEVICE_ID, value.trim()).apply()
    }

    fun saveScannerSyncProvisioning(
        apiBaseUrl: String,
        bearerToken: String,
        deviceId: String? = null,
    ) {
        secureSyncStore.saveProvisioning(
            apiBaseUrl = apiBaseUrl,
            bearerToken = bearerToken,
        )
        deviceId?.let(::setScannerDeviceId)
    }

    fun clearScannerSyncProvisioning() {
        secureSyncStore.clearProvisioning()
    }

    fun resetScannerSyncRuntimeState(clearHistory: Boolean) {
        preferences.edit().apply {
            remove(KEY_SCANNER_LAST_REFERENCE_SYNC_AT)
            remove(KEY_SCANNER_LAST_REFERENCE_CURSOR)
            remove(KEY_SCANNER_LAST_UPLOAD_AT)
            remove(KEY_SCANNER_LAST_UPLOAD_BATCH_ID)
            if (clearHistory) {
                remove(KEY_SCANNER_HISTORY)
            }
        }.apply()
    }

    fun recordScannerSyncProvisioningAudit(
        host: String,
        deviceId: String,
        provisionedAt: Long,
    ) {
        preferences.edit()
            .putString(KEY_SCANNER_LAST_PROVISIONED_HOST, host.trim())
            .putString(KEY_SCANNER_LAST_PROVISIONED_DEVICE_ID, deviceId.trim())
            .putLong(KEY_SCANNER_LAST_PROVISIONED_AT, provisionedAt)
            .apply()
    }

    fun getScannerLastReferenceSyncAt(): Long? {
        return preferences.getLong(KEY_SCANNER_LAST_REFERENCE_SYNC_AT, 0L).takeIf { it > 0L }
    }

    fun setScannerLastReferenceSyncAt(value: Long?) {
        preferences.edit().apply {
            if (value == null) remove(KEY_SCANNER_LAST_REFERENCE_SYNC_AT) else putLong(KEY_SCANNER_LAST_REFERENCE_SYNC_AT, value)
        }.apply()
    }

    fun getScannerLastReferenceCursor(): String? {
        return preferences.getString(KEY_SCANNER_LAST_REFERENCE_CURSOR, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    fun setScannerLastReferenceCursor(value: String?) {
        preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_SCANNER_LAST_REFERENCE_CURSOR) else putString(KEY_SCANNER_LAST_REFERENCE_CURSOR, value.trim())
        }.apply()
    }

    fun getScannerLastUploadAt(): Long? {
        return preferences.getLong(KEY_SCANNER_LAST_UPLOAD_AT, 0L).takeIf { it > 0L }
    }

    fun setScannerLastUploadAt(value: Long?) {
        preferences.edit().apply {
            if (value == null) remove(KEY_SCANNER_LAST_UPLOAD_AT) else putLong(KEY_SCANNER_LAST_UPLOAD_AT, value)
        }.apply()
    }

    fun getScannerLastUploadBatchId(): Long? {
        return preferences.getLong(KEY_SCANNER_LAST_UPLOAD_BATCH_ID, 0L).takeIf { it > 0L }
    }

    fun setScannerLastUploadBatchId(value: Long?) {
        preferences.edit().apply {
            if (value == null) remove(KEY_SCANNER_LAST_UPLOAD_BATCH_ID) else putLong(KEY_SCANNER_LAST_UPLOAD_BATCH_ID, value)
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "mixport_customs_preferences"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SCANNER_AUTO_VERIFY = "scanner_auto_verify"
        private const val KEY_SCANNER_SOUND = "scanner_sound"
        private const val KEY_SCANNER_WORKFLOW_MODE = "scanner_workflow_mode"
        private const val KEY_SCANNER_ONBOARDING_DISMISSED = "scanner_onboarding_dismissed"
        private const val KEY_SCANNER_HISTORY = "scanner_history"
        private const val KEY_SCANNER_DEVICE_ID = "scanner_device_id"
        private const val KEY_SCANNER_LAST_REFERENCE_SYNC_AT = "scanner_last_reference_sync_at"
        private const val KEY_SCANNER_LAST_REFERENCE_CURSOR = "scanner_last_reference_cursor"
        private const val KEY_SCANNER_LAST_UPLOAD_AT = "scanner_last_upload_at"
        private const val KEY_SCANNER_LAST_UPLOAD_BATCH_ID = "scanner_last_upload_batch_id"
        private const val KEY_SCANNER_LAST_PROVISIONED_HOST = "scanner_last_provisioned_host"
        private const val KEY_SCANNER_LAST_PROVISIONED_DEVICE_ID = "scanner_last_provisioned_device_id"
        private const val KEY_SCANNER_LAST_PROVISIONED_AT = "scanner_last_provisioned_at"
    }
}

private fun BarcodeLookupResult.toJsonObject(): JSONObject {
    return JSONObject().apply {
        put("found", found)
        put("databaseRecord", databaseRecord)
        put("status", status)
        put("source", source)
        put("cargoTrackingId", cargoTrackingId)
        put("parentHblNo", parentHblNo)
        put("matchedChildHbl", matchedChildHbl)
        put("matchedBarcodeCode", matchedBarcodeCode)
        put("matchedBy", matchedBy)
        put("childHbls", childHbls)
        put("barcodeCodes", barcodeCodes)
        put("containerNo", containerNo)
        put("vesselName", vesselName)
        put("company", company)
        put("customerName", customerName)
        put("location", location)
        put("pkgs", pkgs)
        put("outTurnQty", outTurnQty)
        put("submissionDate", submissionDate)
        put("customersStatus", customersStatus)
        put("mpiStatus", mpiStatus)
        put("serverScanCount", serverScanCount)
        put("serverMatchedScanCount", serverMatchedScanCount)
        put("serverMismatchScanCount", serverMismatchScanCount)
        put("serverErrorScanCount", serverErrorScanCount)
        put("serverLastScannedAt", serverLastScannedAt)
        put("serverLastMatchStatus", serverLastMatchStatus)
        put("scannerTargetMode", scannerTargetMode)
        put("scannerExpectedScanCount", scannerExpectedScanCount)
        put("scannerCompletedScanCount", scannerCompletedScanCount)
        put("scannerRemainingScanCount", scannerRemainingScanCount)
        put("scannerRepeatMatchCount", scannerRepeatMatchCount)
        put("scannerIsComplete", scannerIsComplete)
        put("containerScanCount", containerScanCount)
        put("containerMatchedScanCount", containerMatchedScanCount)
        put("containerRowCount", containerRowCount)
        put("containerMatchedRowCount", containerMatchedRowCount)
    }
}

private fun JSONObject.toBarcodeLookupResult(): BarcodeLookupResult {
    return BarcodeLookupResult(
        found = optBoolean("found", false),
        databaseRecord = optString("databaseRecord"),
        status = optString("status"),
        source = optString("source"),
        cargoTrackingId = optLong("cargoTrackingId").takeIf { it > 0L },
        parentHblNo = optString("parentHblNo").ifBlank { null },
        matchedChildHbl = optString("matchedChildHbl").ifBlank { null },
        matchedBarcodeCode = optString("matchedBarcodeCode").ifBlank { null },
        matchedBy = optString("matchedBy").ifBlank { null },
        childHbls = optString("childHbls").ifBlank { null },
        barcodeCodes = optString("barcodeCodes").ifBlank { null },
        containerNo = optString("containerNo").ifBlank { null },
        vesselName = optString("vesselName").ifBlank { null },
        company = optString("company").ifBlank { null },
        customerName = optString("customerName").ifBlank { null },
        location = optString("location").ifBlank { null },
        pkgs = optInt("pkgs").takeIf { has("pkgs") && !isNull("pkgs") },
        outTurnQty = optInt("outTurnQty").takeIf { has("outTurnQty") && !isNull("outTurnQty") },
        submissionDate = optString("submissionDate").ifBlank { null },
        customersStatus = optString("customersStatus").ifBlank { null },
        mpiStatus = optString("mpiStatus").ifBlank { null },
        serverScanCount = optInt("serverScanCount", 0),
        serverMatchedScanCount = optInt("serverMatchedScanCount", 0),
        serverMismatchScanCount = optInt("serverMismatchScanCount", 0),
        serverErrorScanCount = optInt("serverErrorScanCount", 0),
        serverLastScannedAt = optString("serverLastScannedAt").ifBlank { null },
        serverLastMatchStatus = optString("serverLastMatchStatus").ifBlank { null },
        scannerTargetMode = optString("scannerTargetMode").ifBlank { null },
        scannerExpectedScanCount = optInt("scannerExpectedScanCount")
            .takeIf { has("scannerExpectedScanCount") && !isNull("scannerExpectedScanCount") },
        scannerCompletedScanCount = optInt("scannerCompletedScanCount")
            .takeIf { has("scannerCompletedScanCount") && !isNull("scannerCompletedScanCount") },
        scannerRemainingScanCount = optInt("scannerRemainingScanCount")
            .takeIf { has("scannerRemainingScanCount") && !isNull("scannerRemainingScanCount") },
        scannerRepeatMatchCount = optInt("scannerRepeatMatchCount")
            .takeIf { has("scannerRepeatMatchCount") && !isNull("scannerRepeatMatchCount") },
        scannerIsComplete = optBoolean("scannerIsComplete")
            .takeIf { has("scannerIsComplete") && !isNull("scannerIsComplete") },
        containerScanCount = optInt("containerScanCount")
            .takeIf { has("containerScanCount") && !isNull("containerScanCount") },
        containerMatchedScanCount = optInt("containerMatchedScanCount")
            .takeIf { has("containerMatchedScanCount") && !isNull("containerMatchedScanCount") },
        containerRowCount = optInt("containerRowCount")
            .takeIf { has("containerRowCount") && !isNull("containerRowCount") },
        containerMatchedRowCount = optInt("containerMatchedRowCount")
            .takeIf { has("containerMatchedRowCount") && !isNull("containerMatchedRowCount") },
    )
}
