package nz.co.mixport.customsvision.nativebridge

internal object ScannerNativeBridge {
    private const val LIBRARY_NAME = "scannerbridge"

    private val isAvailable: Boolean = runCatching {
        System.loadLibrary(LIBRARY_NAME)
        true
    }.getOrDefault(false)

    fun normalizeScannerBarcode(rawValue: String): String? {
        if (!isAvailable) {
            return null
        }
        return runCatching { nativeNormalizeScannerBarcode(rawValue) }.getOrNull()
    }

    fun canonicalScannerClearanceStatus(status: String?): String? {
        if (!isAvailable) {
            return null
        }
        val candidate = status?.trim().orEmpty()
        if (candidate.any { it.code > 0x7F }) {
            return null
        }
        return runCatching { nativeCanonicalScannerClearanceStatus(candidate) }.getOrNull()
    }

    fun overallScannerClearanceStatus(
        nzcsStatus: String?,
        mpiStatus: String?,
    ): String? {
        if (!isAvailable) {
            return null
        }
        val normalizedNzcs = nzcsStatus?.trim().orEmpty()
        val normalizedMpi = mpiStatus?.trim().orEmpty()
        if (normalizedNzcs.any { it.code > 0x7F } || normalizedMpi.any { it.code > 0x7F }) {
            return null
        }
        return runCatching {
            nativeOverallScannerClearanceStatus(normalizedNzcs, normalizedMpi)
        }.getOrNull()
    }

    private external fun nativeNormalizeScannerBarcode(rawValue: String): String

    private external fun nativeCanonicalScannerClearanceStatus(status: String): String

    private external fun nativeOverallScannerClearanceStatus(
        nzcsStatus: String,
        mpiStatus: String,
    ): String
}
