package nz.co.mixport.customsvision.data

object NativeScannerBridge {
    private const val LIB_NAME = "mixportscanner"

    val isLoaded: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            System.loadLibrary(LIB_NAME)
            true
        }.getOrDefault(false)
    }

    fun normalizeBarcodeOrNull(rawValue: String): String? {
        if (!isLoaded) {
            return null
        }
        if (rawValue.any { it.code == 0 || it.code > 0x7F }) {
            return null
        }
        return runCatching { nativeNormalizeBarcode(rawValue) }.getOrNull()
    }

    fun canonicalClearanceStatusOrNull(status: String?): String? {
        if (!isLoaded || status == null) {
            return null
        }
        if (status.any { it.code == 0 || it.code > 0x7F }) {
            return null
        }
        return runCatching { nativeCanonicalClearanceStatus(status) }.getOrNull()
    }

    fun overallClearanceStatusOrNull(
        nzcsStatus: String?,
        mpiStatus: String?,
    ): String? {
        if (!isLoaded) {
            return null
        }
        if ((nzcsStatus?.any { it.code == 0 || it.code > 0x7F } == true) ||
            (mpiStatus?.any { it.code == 0 || it.code > 0x7F } == true)
        ) {
            return null
        }
        return runCatching {
            nativeOverallClearanceStatus(nzcsStatus.orEmpty(), mpiStatus.orEmpty())
        }.getOrNull()
    }

    private external fun nativeNormalizeBarcode(rawValue: String): String

    private external fun nativeCanonicalClearanceStatus(status: String): String

    private external fun nativeOverallClearanceStatus(
        nzcsStatus: String,
        mpiStatus: String,
    ): String
}
