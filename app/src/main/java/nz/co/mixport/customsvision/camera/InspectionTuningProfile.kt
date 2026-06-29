package nz.co.mixport.customsvision.camera

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class InspectionTuningProfile(
    val metadata: TuningMetadata = TuningMetadata(),
    val tracking: TrackingTuning = TrackingTuning(),
    val mobileRuntime: MobileRuntimeTuning = MobileRuntimeTuning(),
    val cargoLabeling: CargoLabelingTuning = CargoLabelingTuning(),
    val transformer: TransformerTuning = TransformerTuning(),
    val palletReference: PalletReferenceTuning = PalletReferenceTuning(),
) {
    fun toJsonString(): String {
        val root = JsonObject().apply {
            add(
                "metadata",
                JsonObject().apply {
                    addProperty("profileName", metadata.profileName)
                    addProperty("profileVersion", metadata.profileVersion)
                    addProperty("datasetId", metadata.datasetId)
                    addProperty("generatedAt", metadata.generatedAt)
                    addProperty("notes", metadata.notes)
                },
            )
            add(
                "tracking",
                JsonObject().apply {
                    addProperty("minStableFrames", tracking.minStableFrames)
                    addProperty("maxTrackGapMs", tracking.maxTrackGapMs)
                    addProperty("countedEvidenceTtlMs", tracking.countedEvidenceTtlMs)
                },
            )
            add(
                "mobileRuntime",
                JsonObject().apply {
                    addProperty("liveAnalysisMinIntervalMs", mobileRuntime.liveAnalysisMinIntervalMs)
                    addProperty("maxSnapshotDetectionsPerPass", mobileRuntime.maxSnapshotDetectionsPerPass)
                    addProperty("recognitionDownsampleMaxEdgePx", mobileRuntime.recognitionDownsampleMaxEdgePx)
                    addProperty("minRecognitionCropEdgePx", mobileRuntime.minRecognitionCropEdgePx)
                },
            )
            add(
                "cargoLabeling",
                JsonObject().apply {
                    addProperty("minImageLabelConfidence", cargoLabeling.minImageLabelConfidence)
                    addProperty("maxLabelHints", cargoLabeling.maxLabelHints)
                    addProperty("minReliableDetectionConfidence", cargoLabeling.minReliableDetectionConfidence)
                },
            )
            add(
                "transformer",
                JsonObject().apply {
                    addProperty("enabled", transformer.enabled)
                    addProperty("preferredFamily", transformer.preferredFamily)
                    addProperty("modelInputSizePx", transformer.modelInputSizePx)
                    addProperty("quantized", transformer.quantized)
                    addProperty("runOnStableTracksOnly", transformer.runOnStableTracksOnly)
                    addProperty("preferNnapi", transformer.preferNnapi)
                    addProperty("cpuThreadCount", transformer.cpuThreadCount)
                    addProperty("maxTracksPerPass", transformer.maxTracksPerPass)
                    addProperty("targetLatencyMs", transformer.targetLatencyMs)
                },
            )
            add(
                "palletReference",
                JsonObject().apply {
                    addProperty("targetAspectRatio", palletReference.targetAspectRatio)
                    addProperty("aspectTolerance", palletReference.aspectTolerance)
                    addProperty("targetWidthRatio", palletReference.targetWidthRatio)
                    addProperty("widthTolerance", palletReference.widthTolerance)
                    addProperty("targetHeightRatio", palletReference.targetHeightRatio)
                    addProperty("heightTolerance", palletReference.heightTolerance)
                    addProperty("bottomAnchorRatio", palletReference.bottomAnchorRatio)
                    addProperty("livePalletThreshold", palletReference.livePalletThreshold)
                    addProperty("recognitionPalletThreshold", palletReference.recognitionPalletThreshold)
                    addProperty("zoneHorizontalPaddingRatio", palletReference.zoneHorizontalPaddingRatio)
                    addProperty("zoneSupportAboveRatio", palletReference.zoneSupportAboveRatio)
                    addProperty("zoneSupportBelowRatio", palletReference.zoneSupportBelowRatio)
                    addProperty("zoneMinOverlapRatio", palletReference.zoneMinOverlapRatio)
                    addProperty("zoneItemMaxWidthRatio", palletReference.zoneItemMaxWidthRatio)
                    addProperty("zoneItemMaxHeightRatio", palletReference.zoneItemMaxHeightRatio)
                },
            )
        }
        return prettyGson.toJson(root)
    }

    companion object {
        const val DEFAULT_ASSET_FILE_NAME = "inspection_tuning_profile.json"
        private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun default(): InspectionTuningProfile = InspectionTuningProfile()

        fun fromJsonString(raw: String): InspectionTuningProfile {
            val root = JsonParser.parseString(raw).asJsonObject
            val metadataJson = root.optObject("metadata")
            val trackingJson = root.optObject("tracking")
            val mobileRuntimeJson = root.optObject("mobileRuntime")
            val cargoJson = root.optObject("cargoLabeling")
            val transformerJson = root.optObject("transformer")
            val palletJson = root.optObject("palletReference")
            val defaultMetadata = TuningMetadata()
            val defaultTracking = TrackingTuning()
            val defaultMobileRuntime = MobileRuntimeTuning()
            val defaultCargo = CargoLabelingTuning()
            val defaultTransformer = TransformerTuning()
            val defaultPallet = PalletReferenceTuning()

            return InspectionTuningProfile(
                metadata = TuningMetadata(
                    profileName = metadataJson.optStringOrDefault("profileName", defaultMetadata.profileName),
                    profileVersion = metadataJson.optStringOrDefault("profileVersion", defaultMetadata.profileVersion),
                    datasetId = metadataJson.optStringOrDefault("datasetId", defaultMetadata.datasetId, allowBlank = true),
                    generatedAt = metadataJson.optStringOrDefault("generatedAt", defaultMetadata.generatedAt, allowBlank = true),
                    notes = metadataJson.optStringOrDefault("notes", defaultMetadata.notes, allowBlank = true),
                ),
                tracking = TrackingTuning(
                    minStableFrames = trackingJson.optPositiveIntOrDefault("minStableFrames", defaultTracking.minStableFrames),
                    maxTrackGapMs = trackingJson.optPositiveLongOrDefault("maxTrackGapMs", defaultTracking.maxTrackGapMs),
                    countedEvidenceTtlMs = trackingJson.optPositiveLongOrDefault("countedEvidenceTtlMs", defaultTracking.countedEvidenceTtlMs),
                ),
                mobileRuntime = MobileRuntimeTuning(
                    liveAnalysisMinIntervalMs = mobileRuntimeJson.optPositiveLongOrDefault(
                        "liveAnalysisMinIntervalMs",
                        defaultMobileRuntime.liveAnalysisMinIntervalMs,
                    ),
                    maxSnapshotDetectionsPerPass = mobileRuntimeJson.optPositiveIntOrDefault(
                        "maxSnapshotDetectionsPerPass",
                        defaultMobileRuntime.maxSnapshotDetectionsPerPass,
                    ),
                    recognitionDownsampleMaxEdgePx = mobileRuntimeJson.optPositiveIntOrDefault(
                        "recognitionDownsampleMaxEdgePx",
                        defaultMobileRuntime.recognitionDownsampleMaxEdgePx,
                    ),
                    minRecognitionCropEdgePx = mobileRuntimeJson.optPositiveIntOrDefault(
                        "minRecognitionCropEdgePx",
                        defaultMobileRuntime.minRecognitionCropEdgePx,
                    ),
                ),
                cargoLabeling = CargoLabelingTuning(
                    minImageLabelConfidence = cargoJson.optFloatOrDefault("minImageLabelConfidence", defaultCargo.minImageLabelConfidence),
                    maxLabelHints = cargoJson.optPositiveIntOrDefault("maxLabelHints", defaultCargo.maxLabelHints),
                    minReliableDetectionConfidence = cargoJson.optFloatOrDefault("minReliableDetectionConfidence", defaultCargo.minReliableDetectionConfidence),
                ),
                transformer = TransformerTuning(
                    enabled = transformerJson.optBooleanOrDefault("enabled", defaultTransformer.enabled),
                    preferredFamily = transformerJson.optStringOrDefault("preferredFamily", defaultTransformer.preferredFamily),
                    modelInputSizePx = transformerJson.optPositiveIntOrDefault("modelInputSizePx", defaultTransformer.modelInputSizePx),
                    quantized = transformerJson.optBooleanOrDefault("quantized", defaultTransformer.quantized),
                    runOnStableTracksOnly = transformerJson.optBooleanOrDefault(
                        "runOnStableTracksOnly",
                        defaultTransformer.runOnStableTracksOnly,
                    ),
                    preferNnapi = transformerJson.optBooleanOrDefault("preferNnapi", defaultTransformer.preferNnapi),
                    cpuThreadCount = transformerJson.optPositiveIntOrDefault("cpuThreadCount", defaultTransformer.cpuThreadCount),
                    maxTracksPerPass = transformerJson.optPositiveIntOrDefault("maxTracksPerPass", defaultTransformer.maxTracksPerPass),
                    targetLatencyMs = transformerJson.optPositiveLongOrDefault("targetLatencyMs", defaultTransformer.targetLatencyMs),
                ),
                palletReference = PalletReferenceTuning(
                    targetAspectRatio = palletJson.optFloatOrDefault("targetAspectRatio", defaultPallet.targetAspectRatio),
                    aspectTolerance = palletJson.optFloatOrDefault("aspectTolerance", defaultPallet.aspectTolerance),
                    targetWidthRatio = palletJson.optFloatOrDefault("targetWidthRatio", defaultPallet.targetWidthRatio),
                    widthTolerance = palletJson.optFloatOrDefault("widthTolerance", defaultPallet.widthTolerance),
                    targetHeightRatio = palletJson.optFloatOrDefault("targetHeightRatio", defaultPallet.targetHeightRatio),
                    heightTolerance = palletJson.optFloatOrDefault("heightTolerance", defaultPallet.heightTolerance),
                    bottomAnchorRatio = palletJson.optFloatOrDefault("bottomAnchorRatio", defaultPallet.bottomAnchorRatio),
                    livePalletThreshold = palletJson.optFloatOrDefault("livePalletThreshold", defaultPallet.livePalletThreshold),
                    recognitionPalletThreshold = palletJson.optFloatOrDefault("recognitionPalletThreshold", defaultPallet.recognitionPalletThreshold),
                    zoneHorizontalPaddingRatio = palletJson.optFloatOrDefault("zoneHorizontalPaddingRatio", defaultPallet.zoneHorizontalPaddingRatio),
                    zoneSupportAboveRatio = palletJson.optFloatOrDefault("zoneSupportAboveRatio", defaultPallet.zoneSupportAboveRatio),
                    zoneSupportBelowRatio = palletJson.optFloatOrDefault("zoneSupportBelowRatio", defaultPallet.zoneSupportBelowRatio),
                    zoneMinOverlapRatio = palletJson.optFloatOrDefault("zoneMinOverlapRatio", defaultPallet.zoneMinOverlapRatio),
                    zoneItemMaxWidthRatio = palletJson.optFloatOrDefault("zoneItemMaxWidthRatio", defaultPallet.zoneItemMaxWidthRatio),
                    zoneItemMaxHeightRatio = palletJson.optFloatOrDefault("zoneItemMaxHeightRatio", defaultPallet.zoneItemMaxHeightRatio),
                ),
            )
        }

        private fun JsonObject?.optObject(key: String): JsonObject? {
            if (this == null || !has(key)) {
                return null
            }
            val value = get(key)
            return if (value != null && value.isJsonObject) value.asJsonObject else null
        }

        private fun JsonObject?.optStringOrDefault(
            key: String,
            fallback: String,
            allowBlank: Boolean = false,
        ): String {
            if (this == null || !has(key)) {
                return fallback
            }
            val value = runCatching { get(key).asString }.getOrNull() ?: return fallback
            return if (allowBlank || value.isNotBlank()) value else fallback
        }

        private fun JsonObject?.optPositiveIntOrDefault(
            key: String,
            fallback: Int,
        ): Int {
            if (this == null || !has(key)) {
                return fallback
            }
            return runCatching { get(key).asInt }.getOrNull()
                ?.takeIf { it > 0 }
                ?: fallback
        }

        private fun JsonObject?.optPositiveLongOrDefault(
            key: String,
            fallback: Long,
        ): Long {
            if (this == null || !has(key)) {
                return fallback
            }
            return runCatching { get(key).asLong }.getOrNull()
                ?.takeIf { it > 0L }
                ?: fallback
        }

        private fun JsonObject?.optBooleanOrDefault(
            key: String,
            fallback: Boolean,
        ): Boolean {
            if (this == null || !has(key)) {
                return fallback
            }
            return runCatching { get(key).asBoolean }.getOrNull() ?: fallback
        }

        private fun JsonObject?.optFloatOrDefault(
            key: String,
            fallback: Float,
        ): Float {
            if (this == null || !has(key)) {
                return fallback
            }
            return runCatching { get(key).asFloat }.getOrNull()
                ?.takeIf { !it.isNaN() }
                ?: fallback
        }
    }
}

data class TuningMetadata(
    val profileName: String = "mixport-default-pilot",
    val profileVersion: String = "0.4.0",
    val datasetId: String = "",
    val generatedAt: String = "",
    val notes: String = "Bundled fallback tuning profile for the Mixport pilot.",
)

data class TrackingTuning(
    val minStableFrames: Int = 3,
    val maxTrackGapMs: Long = 1_500L,
    val countedEvidenceTtlMs: Long = 20_000L,
)

data class MobileRuntimeTuning(
    val liveAnalysisMinIntervalMs: Long = 120L,
    val maxSnapshotDetectionsPerPass: Int = 3,
    val recognitionDownsampleMaxEdgePx: Int = 320,
    val minRecognitionCropEdgePx: Int = 32,
)

data class CargoLabelingTuning(
    val minImageLabelConfidence: Float = 0.28f,
    val maxLabelHints: Int = 5,
    val minReliableDetectionConfidence: Float = 0.72f,
)

data class TransformerTuning(
    val enabled: Boolean = true,
    val preferredFamily: String = "MobileViTv2",
    val modelInputSizePx: Int = 256,
    val quantized: Boolean = true,
    val runOnStableTracksOnly: Boolean = true,
    val preferNnapi: Boolean = true,
    val cpuThreadCount: Int = 2,
    val maxTracksPerPass: Int = 3,
    val targetLatencyMs: Long = 60L,
)

data class PalletReferenceTuning(
    val targetAspectRatio: Float = 2.1f,
    val aspectTolerance: Float = 0.9f,
    val targetWidthRatio: Float = 0.44f,
    val widthTolerance: Float = 0.22f,
    val targetHeightRatio: Float = 0.22f,
    val heightTolerance: Float = 0.12f,
    val bottomAnchorRatio: Float = 0.72f,
    val livePalletThreshold: Float = 0.66f,
    val recognitionPalletThreshold: Float = 0.70f,
    val zoneHorizontalPaddingRatio: Float = 0.18f,
    val zoneSupportAboveRatio: Float = 0.12f,
    val zoneSupportBelowRatio: Float = 0.45f,
    val zoneMinOverlapRatio: Float = 0.35f,
    val zoneItemMaxWidthRatio: Float = 1.35f,
    val zoneItemMaxHeightRatio: Float = 3.5f,
)
