package nz.co.mixport.customsvision

import android.app.Application
import nz.co.mixport.customsvision.data.AppPreferencesRepository
import nz.co.mixport.customsvision.data.CustomsDatabaseHelper
import nz.co.mixport.customsvision.data.PilotRepository

class CustomsApplication : Application() {
    val repository: PilotRepository by lazy {
        PilotRepository(CustomsDatabaseHelper(this))
    }

    val preferencesRepository: AppPreferencesRepository by lazy {
        AppPreferencesRepository(this)
    }
}
