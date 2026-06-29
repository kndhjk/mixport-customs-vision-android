package nz.co.mixport.customsvision.camera

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class MobileDeviceTier {
    ENTRY,
    MID,
    HIGH,
}

data class MobileVisionProfile(
    val deviceTier: MobileDeviceTier,
    val totalRamGb: Int,
    val preferredAbi: String,
    val liveAnalysisIntervalMs: Long,
    val maxSnapshotDetectionsPerPass: Int,
    val recognitionDownsampleMaxEdgePx: Int,
    val transformerFamily: String,
    val transformerInputSizePx: Int,
    val transformerQuantized: Boolean,
    val transformerTargetLatencyMs: Long,
    val transformerMaxTracksPerPass: Int,
) {
    val liveAnalysisFpsCap: Int
        get() = if (liveAnalysisIntervalMs <= 0L) 0 else (1000L / liveAnalysisIntervalMs).toInt()

    val transformerSummary: String
        get() = buildString {
            append(transformerFamily)
            append(' ')
            append(transformerInputSizePx)
            append("px")
            append(if (transformerQuantized) " INT8" else " FP16/FP32")
        }

    companion object {
        fun fromContext(
            context: Context,
            tuning: InspectionTuningProfile,
        ): MobileVisionProfile {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val totalRamGb = (memoryInfo.totalMem / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)
            val preferredAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            return fromCapabilities(
                totalRamGb = totalRamGb,
                preferredAbi = preferredAbi,
                tuning = tuning,
            )
        }

        fun fromCapabilities(
            totalRamGb: Int,
            preferredAbi: String,
            tuning: InspectionTuningProfile,
        ): MobileVisionProfile {
            val deviceTier = when {
                totalRamGb >= 8 -> MobileDeviceTier.HIGH
                totalRamGb >= 5 -> MobileDeviceTier.MID
                else -> MobileDeviceTier.ENTRY
            }
            val runtime = tuning.mobileRuntime
            val transformer = tuning.transformer

            val interval = when (deviceTier) {
                MobileDeviceTier.HIGH -> runtime.liveAnalysisMinIntervalMs.coerceAtMost(100L)
                MobileDeviceTier.MID -> runtime.liveAnalysisMinIntervalMs
                MobileDeviceTier.ENTRY -> runtime.liveAnalysisMinIntervalMs.coerceAtLeast(160L)
            }
            val snapshotCount = when (deviceTier) {
                MobileDeviceTier.HIGH -> minOf(runtime.maxSnapshotDetectionsPerPass + 1, transformer.maxTracksPerPass + 1)
                MobileDeviceTier.MID -> minOf(runtime.maxSnapshotDetectionsPerPass, transformer.maxTracksPerPass)
                MobileDeviceTier.ENTRY -> minOf(2, runtime.maxSnapshotDetectionsPerPass, transformer.maxTracksPerPass)
            }.coerceAtLeast(1)
            val downsampleMaxEdge = when (deviceTier) {
                MobileDeviceTier.HIGH -> maxOf(runtime.recognitionDownsampleMaxEdgePx, transformer.modelInputSizePx + 64)
                MobileDeviceTier.MID -> maxOf(runtime.recognitionDownsampleMaxEdgePx, transformer.modelInputSizePx)
                MobileDeviceTier.ENTRY -> minOf(runtime.recognitionDownsampleMaxEdgePx, transformer.modelInputSizePx)
            }
            val targetLatency = when (deviceTier) {
                MobileDeviceTier.HIGH -> transformer.targetLatencyMs.coerceAtMost(45L)
                MobileDeviceTier.MID -> transformer.targetLatencyMs
                MobileDeviceTier.ENTRY -> transformer.targetLatencyMs.coerceAtLeast(70L)
            }

            return MobileVisionProfile(
                deviceTier = deviceTier,
                totalRamGb = totalRamGb,
                preferredAbi = preferredAbi,
                liveAnalysisIntervalMs = interval,
                maxSnapshotDetectionsPerPass = snapshotCount,
                recognitionDownsampleMaxEdgePx = downsampleMaxEdge,
                transformerFamily = transformer.preferredFamily,
                transformerInputSizePx = transformer.modelInputSizePx,
                transformerQuantized = transformer.quantized,
                transformerTargetLatencyMs = targetLatency,
                transformerMaxTracksPerPass = snapshotCount,
            )
        }
    }
}
