package nz.co.mixport.customsvision.data

import nz.co.mixport.customsvision.scanner.PdaScanWorkflowMode

data class AppStartupSnapshot(
    val appLanguage: AppLanguage,
    val pdaScannerAvailable: Boolean,
    val loadedInspectionTuning: LoadedInspectionTuning,
    val scannerAutoVerifyEnabled: Boolean,
    val scannerSoundEnabled: Boolean,
    val scannerWorkflowMode: PdaScanWorkflowMode,
    val scannerOnboardingDismissed: Boolean,
    val scannerHistory: List<ScannerRecord>,
)
