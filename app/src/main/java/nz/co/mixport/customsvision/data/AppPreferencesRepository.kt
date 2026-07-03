package nz.co.mixport.customsvision.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import nz.co.mixport.customsvision.scanner.PdaScanWorkflowMode

class AppPreferencesRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
                },
            )
        }
        preferences.edit().putString(KEY_SCANNER_HISTORY, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "mixport_customs_preferences"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SCANNER_AUTO_VERIFY = "scanner_auto_verify"
        private const val KEY_SCANNER_SOUND = "scanner_sound"
        private const val KEY_SCANNER_WORKFLOW_MODE = "scanner_workflow_mode"
        private const val KEY_SCANNER_ONBOARDING_DISMISSED = "scanner_onboarding_dismissed"
        private const val KEY_SCANNER_HISTORY = "scanner_history"
    }
}
