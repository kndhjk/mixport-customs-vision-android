package nz.co.mixport.customsvision.scanner

enum class PdaScanWorkflowMode(val preferenceValue: String) {
    AUTO_CONTINUOUS("auto_continuous"),
    TRIGGER_ONCE("trigger_once"),
    ;

    companion object {
        fun fromPreference(value: String?): PdaScanWorkflowMode {
            return values().firstOrNull { it.preferenceValue == value } ?: TRIGGER_ONCE
        }
    }
}
