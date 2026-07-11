package nz.co.mixport.customsvision

import android.app.Application
import android.os.SystemClock
import android.util.Log
import nz.co.mixport.customsvision.data.AppPreferencesRepository
import nz.co.mixport.customsvision.data.AppStartupSnapshot
import nz.co.mixport.customsvision.data.CustomsSyncClient
import nz.co.mixport.customsvision.data.CustomsDatabaseHelper
import nz.co.mixport.customsvision.data.InspectionTuningLoader
import nz.co.mixport.customsvision.data.PilotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.co.mixport.customsvision.scanner.HikPdaScanBridge
import nz.co.mixport.customsvision.scanner.PdaScanWorkflowMode

data class AppBootstrapPayload(
    val repository: PilotRepository,
    val preferencesRepository: AppPreferencesRepository,
    val startupSnapshot: AppStartupSnapshot,
)

class CustomsApplication : Application() {
    @Volatile
    private var bootstrapPayload: AppBootstrapPayload? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(STARTUP_TAG, "Application.onCreate at ${SystemClock.elapsedRealtime()} ms")
    }

    suspend fun loadBootstrapPayload(): AppBootstrapPayload {
        bootstrapPayload?.let { return it }
        return withContext(Dispatchers.IO) {
            bootstrapPayload?.let { return@withContext it }
            synchronized(this@CustomsApplication) {
                bootstrapPayload ?: buildBootstrapPayload().also { payload ->
                    bootstrapPayload = payload
                }
            }
        }
    }

    private fun buildBootstrapPayload(): AppBootstrapPayload {
        val preferencesRepository = AppPreferencesRepository(this)
        val loadedInspectionTuning = InspectionTuningLoader(this).load()
        return AppBootstrapPayload(
            repository = PilotRepository(
                databaseHelper = CustomsDatabaseHelper(this),
                syncClient = CustomsSyncClient(),
            ),
            preferencesRepository = preferencesRepository,
            startupSnapshot = AppStartupSnapshot(
                appLanguage = preferencesRepository.getLanguage(),
                pdaScannerAvailable = HikPdaScanBridge.isPdaServiceInstalled(this),
                loadedInspectionTuning = loadedInspectionTuning,
                scannerAutoVerifyEnabled = preferencesRepository.isScannerAutoVerifyEnabled(),
                scannerSoundEnabled = preferencesRepository.isScannerSoundEnabled(),
                scannerWorkflowMode = PdaScanWorkflowMode.TRIGGER_ONCE,
                scannerOnboardingDismissed = preferencesRepository.isScannerOnboardingDismissed(),
                scannerHistory = preferencesRepository.getScannerHistory(),
                scannerSyncSettings = preferencesRepository.getScannerSyncSettings(),
            ),
        )
    }
}

private const val STARTUP_TAG = "MixportStartup"
