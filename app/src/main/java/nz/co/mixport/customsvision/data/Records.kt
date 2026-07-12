package nz.co.mixport.customsvision.data

enum class AppLanguage(val code: String) {
    ENGLISH("en"),
    CHINESE("zh"),
    ;

    companion object {
        fun fromCode(code: String?): AppLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
        }
    }
}

enum class ScannerMatchStatus {
    MATCHED,
    MISMATCH,
    ERROR,
    WAITING,
}

data class SessionDraft(
    val containerCode: String = "",
    val vesselName: String = "",
    val operatorName: String = "",
    val notes: String = "",
)

data class InspectionSessionRecord(
    val id: Long,
    val containerCode: String,
    val vesselName: String,
    val operatorName: String,
    val notes: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val recordingUri: String?,
    val containerHasRemainingCargo: Boolean,
)

data class PalletRecord(
    val id: Long,
    val sessionId: Long,
    val sequenceNumber: Int,
    val status: String,
    val startedAt: Long,
    val closedAt: Long?,
    val wrapDetected: Boolean,
    val containerEmptyAtClose: Boolean,
)

data class CargoSummaryRecord(
    val id: Long,
    val palletId: Long,
    val itemLabel: String,
    val colorName: String,
    val markerText: String,
    val quantity: Int,
)

data class EventLogRecord(
    val id: Long,
    val sessionId: Long,
    val palletId: Long?,
    val eventType: String,
    val message: String,
    val payloadJson: String,
    val createdAt: Long,
)

data class PalletDetail(
    val pallet: PalletRecord,
    val items: List<CargoSummaryRecord>,
)

data class ScannerRecord(
    val scannedBarcode: String,
    val databaseRecord: String,
    val matchStatus: ScannerMatchStatus,
    val status: String,
    val source: String,
    val scannedAt: Long,
    val customersStatus: String? = null,
    val mpiStatus: String? = null,
)

data class ScannerSyncSettings(
    val apiBaseUrl: String = "",
    val bearerToken: String = "",
    val deviceId: String = "",
)

data class ScannerSyncStatus(
    val referenceCount: Int = 0,
    val pendingUploadCount: Int = 0,
    val lastReferenceSyncAt: Long? = null,
    val lastUploadAt: Long? = null,
    val lastUploadBatchId: Long? = null,
)

data class ScannerReferenceRefreshResult(
    val status: ScannerSyncStatus,
    val cursor: String? = null,
)

data class PendingScannerUploadRecord(
    val id: Long,
    val scannedBarcode: String,
    val databaseRecord: String,
    val matchStatus: ScannerMatchStatus,
    val status: String,
    val source: String,
    val scannedAt: Long,
    val cargoTrackingId: Long?,
    val parentHblNo: String?,
    val matchedChildHbl: String?,
    val matchedBy: String?,
    val childHbls: String?,
    val containerNo: String?,
    val vesselName: String?,
    val company: String?,
    val customerName: String?,
    val location: String?,
)

data class BarcodeLookupResult(
    val found: Boolean,
    val databaseRecord: String,
    val status: String,
    val source: String,
    val cargoTrackingId: Long? = null,
    val parentHblNo: String? = null,
    val matchedChildHbl: String? = null,
    val matchedBarcodeCode: String? = null,
    val matchedBy: String? = null,
    val childHbls: String? = null,
    val barcodeCodes: String? = null,
    val containerNo: String? = null,
    val vesselName: String? = null,
    val company: String? = null,
    val customerName: String? = null,
    val location: String? = null,
    val pkgs: Int? = null,
    val outTurnQty: Int? = null,
    val submissionDate: String? = null,
    val customersStatus: String? = null,
    val mpiStatus: String? = null,
)
