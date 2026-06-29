package nz.co.mixport.customsvision.data

import android.content.Context
import android.os.Environment
import java.io.File
import nz.co.mixport.customsvision.camera.InspectionTuningProfile
import nz.co.mixport.customsvision.camera.MobileVisionProfile

data class LoadedInspectionTuning(
    val profile: InspectionTuningProfile,
    val mobileVisionProfile: MobileVisionProfile,
    val sourceDescription: String,
    val searchedPaths: List<String>,
)

class InspectionTuningLoader(
    private val context: Context,
) {
    fun load(): LoadedInspectionTuning {
        val searchedPaths = mutableListOf<String>()
        val overrideCandidates = listOfNotNull(
            File(context.filesDir, InspectionTuningProfile.DEFAULT_ASSET_FILE_NAME),
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?.let { File(it, InspectionTuningProfile.DEFAULT_ASSET_FILE_NAME) },
        )

        overrideCandidates.forEach { file ->
            searchedPaths += file.absolutePath
            if (!file.exists()) {
                return@forEach
            }
            val profile = runCatching {
                InspectionTuningProfile.fromJsonString(file.readText(Charsets.UTF_8))
            }.getOrNull()
            if (profile != null) {
                return LoadedInspectionTuning(
                    profile = profile,
                    mobileVisionProfile = MobileVisionProfile.fromContext(context, profile),
                    sourceDescription = "Override file: ${file.absolutePath}",
                    searchedPaths = searchedPaths.toList(),
                )
            }
        }

        searchedPaths += "assets/${InspectionTuningProfile.DEFAULT_ASSET_FILE_NAME}"
        val bundledProfile = runCatching {
            context.assets.open(InspectionTuningProfile.DEFAULT_ASSET_FILE_NAME).bufferedReader().use { reader ->
                InspectionTuningProfile.fromJsonString(reader.readText())
            }
        }.getOrElse {
            InspectionTuningProfile.default()
        }

        return LoadedInspectionTuning(
            profile = bundledProfile,
            mobileVisionProfile = MobileVisionProfile.fromContext(context, bundledProfile),
            sourceDescription = "Bundled asset: ${InspectionTuningProfile.DEFAULT_ASSET_FILE_NAME}",
            searchedPaths = searchedPaths.toList(),
        )
    }
}
